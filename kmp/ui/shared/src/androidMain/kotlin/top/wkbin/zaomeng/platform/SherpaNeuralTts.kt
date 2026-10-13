package top.wkbin.zaomeng.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 离线神经语音引擎（sherpa-onnx + VITS 多说话人中文模型）的 [PlatformTts] 实现。
 *
 * ## 与系统 TTS 的分工
 *
 * | | 系统 TTS | 这条引擎 |
 * | --- | --- | --- |
 * | 音色 | 引擎自带的 1~2 个中文音色 | **说话人编号 `sid`**（theresa 有 804 个） |
 * | 语速 | ✅ | ✅ |
 * | 音量 | ✅ | ✅（在**播放侧**用 `AudioTrack.setVolume` 施加） |
 * | 音高 | ✅ | ❌ **引擎没有音高参数**，见下 |
 *
 * **音高是这条引擎唯一做不到的**：合成入口是 `generate(text, sid, speed)`，没有音高。
 * 所以 [VoiceProfile.pitch] 在这里不生效——代码与设置界面都如实标注，不假装它生效。
 * 真要做音高，只能对合成出的 PCM 变调（会有金属音风险），那是单独一块工作。
 *
 * ## 音色分配
 *
 * 由 [setCast] 拿到整份参演名单后一次性算完（[assignNeuralSpeakers]），因此同时满足
 * 「同一角色永远同一音色」与「同场角色不撞车」。没在名单里的说话人（例如旁白、临时 NPC）
 * 回落到按名字派生的编号。
 */
internal class SherpaNeuralTts(
    private val context: Context,
    private val model: NeuralVoiceModel,
    private val modelDir: File,
) : PlatformTts {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 引擎加载很贵（几百 MB 模型），并且只该有一次；用互斥锁保证并发调用只加载一次。 */
    private val engineLock = Mutex()
    private var engine: OfflineTts? = null
    private var loadFailure: String? = null

    /**
     * 合成与播放的**串行锁**。
     *
     * sherpa-onnx 的 `OfflineTts` 不是线程安全的，而「连点两次朗读」会让两次 `generate()`
     * 撞在同一个原生对象上：`stop()` 只能**取消协程**，无法打断已经进入 JNI 的那次调用。
     * 那是**原生崩溃**，Java 侧的 `try/catch` 兜不住——必须在这里串行化。
     */
    private val playbackLock = Mutex()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var playbackJob: Job? = null
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var cast: List<String> = emptyList()

    override fun setCast(participants: List<String>) {
        // 只在名单真的变了时才替换：每次重组都换一份会让音色分配白算一遍。
        val next = participants.map { it.trim() }.filter { it.isNotEmpty() }
        if (next == cast) return
        cast = next
    }

    override fun speak(
        id: String,
        text: String,
        pitch: Float,
        speed: Float,
        volume: Float,
        speaker: String,
        voiceName: String,
    ) {
        val clean = cleanSpokenText(text)
        if (clean.isBlank()) return

        stop()
        playbackJob = scope.launch {
            // 整段（加载引擎 → 合成 → 播放）都在锁内：既避免并发调用原生引擎，
            // 也保证同一时刻只有一个 AudioTrack 在被写入。
            playbackLock.withLock {
                _isSpeaking.value = true
                _currentSpeakingId.value = id
                try {
                    val tts = ensureEngine() ?: return@launch
                    val sid = speakerIdFor(speaker, tts.numSpeakers())
                    // 每次合成都留一行**决定性**日志：说话人 → 音色编号、总音色数、语速。
                    // 「所有人一个声音」这种反馈必须能靠一行日志区分三种原因：
                    // ① 根本没走神经引擎（则这里不会有这行）；② 走了但音色编号相同；
                    // ③ 编号不同但模型本身音色相近（模型能力边界）。不打印就只能靠猜。
                    Log.i(
                        TAG,
                        "朗读：说话人=「$speaker」音色#$sid/${tts.numSpeakers()} " +
                            "语速=${"%.2f".format(speed)} 字符数=${clean.length} 候选角色=${cast.size}",
                    )
                    val audio = tts.generate(clean, sid, speed.coerceIn(MIN_SPEED, MAX_SPEED))
                    if (audio.samples.isEmpty()) {
                        Log.w(TAG, "合成为空音频：sid=$sid speed=$speed 文本长度=${clean.length}")
                        return@launch
                    }
                    play(audio.samples, audio.sampleRate, volume)
                } catch (error: Throwable) {
                    // 合成失败只影响这一次朗读；把原因留给日志（症状是「没声音」，不记就无从查起）。
                    Log.e(TAG, "神经语音播放失败：${error.message}", error)
                } finally {
                    if (_currentSpeakingId.value == id) {
                        _isSpeaking.value = false
                        _currentSpeakingId.value = null
                    }
                }
            }
        }
    }

    override fun stop() {
        // 只取消任务，**不在这里释放 AudioTrack**：轨道由正在播放的那个协程在自己的 finally 里释放。
        // 之前在这里也 release 一次，会和协程内的写入/释放撞车（同一轨道释放两次）——
        // 轻则 IllegalStateException，重则原生崩溃。
        playbackJob?.cancel()
        playbackJob = null
        _isSpeaking.value = false
        _currentSpeakingId.value = null
    }

    override fun shutdown() {
        stop()
        scope.cancel()
        runCatching { engine?.release() }
        engine = null
    }

    // ------------------------------------------------------------------
    // 引擎
    // ------------------------------------------------------------------

    private suspend fun ensureEngine(): OfflineTts? {
        engine?.let { return it }
        return engineLock.withLock {
            engine?.let { return@withLock it }
            if (loadFailure != null) return@withLock null
            // 模型加载要读上百 MB 文件，放到 IO 调度器，别占着合成用的线程。
            withContext(Dispatchers.IO) {
                SherpaNeuralEngine.load(context, model, modelDir).fold(
                    onSuccess = { loaded ->
                        engine = loaded
                        loaded
                    },
                    onFailure = { error ->
                        // 记下原因并保留：反复重试几百 MB 的加载只会让每次朗读都卡住。
                        loadFailure = error.message
                        null
                    },
                )
            }
        }
    }

    /**
     * 说话人编号。整份名单已经由 [setCast] 给到，这里只是查表。
     *
     * 名单为空（例如还没进过会话）时只按名字派生——那会有撞车概率，但好过不出声；
     * 会话一建立就会调用 [setCast] 修正。
     */
    private fun speakerIdFor(speaker: String, speakerCount: Int): Int {
        if (speakerCount <= 0) return 0
        val names = cast.ifEmpty { listOf(speaker) }
        return assignNeuralSpeakers(names, speakerCount)[speaker]
            ?: neuralSpeakerId(speaker, speakerCount)
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    /**
     * 把合成出的浮点采样播出去。
     *
     * 用 `MODE_STREAM` 而不是一次性 `write` 静态缓冲：长句子的采样数组可能很大，
     * 流式写入不会因为缓冲区上限而截断。**播完要等缓冲排空**，否则 `stop()`/`release()`
     * 会把尾音切掉——表现出来就是「最后几个字听不见」。
     */
    private suspend fun play(samples: FloatArray, sampleRateHz: Int, volume: Float) {
        val rate = sampleRateHz.coerceAtLeast(MIN_SAMPLE_RATE)
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = max(minBuffer, samples.size * BYTES_PER_SAMPLE)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(bufferBytes, minBuffer))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        activeTrack = track

        try {
            // 音量在播放侧施加：引擎没有音量参数，但 AudioTrack 有。
            // 这样 [VoiceProfile.volume]（低语/爆发）在神经引擎上**依然生效**。
            runCatching { track.setVolume(volume.coerceIn(0f, 1f)) }
            track.play()

            val shorts = ShortArray(samples.size) { index ->
                (samples[index].coerceIn(-1f, 1f) * PCM_SHORT_MAX).toInt().toShort()
            }
            var written = 0
            while (written < shorts.size) {
                currentCoroutineContext().ensureActive()
                val count = min(WRITE_CHUNK_SAMPLES, shorts.size - written)
                val accepted = track.write(shorts, written, count)
                if (accepted <= 0) break
                written += accepted
            }

            // 等缓冲排空：写完不等于播完。
            val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
            while (track.playbackHeadPosition < written && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                delay(DRAIN_POLL_MS)
            }
        } finally {
            releaseTrack()
        }
    }

    private fun releaseTrack() {
        val track = activeTrack ?: return
        activeTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private companion object {
        const val TAG = "SherpaNeuralTts"
        const val MIN_SAMPLE_RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
        const val PCM_SHORT_MAX = 32_767f
        const val WRITE_CHUNK_SAMPLES = 4_096
        const val DRAIN_POLL_MS = 20L
        const val DRAIN_TIMEOUT_MS = 60_000L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
