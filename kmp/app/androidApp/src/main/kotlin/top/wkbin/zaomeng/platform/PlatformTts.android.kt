package top.wkbin.zaomeng.platform

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

private const val TAG = "PlatformTts"

/**
 * 系统 TTS 引擎的**惰性**封装。
 *
 * 引擎在**第一次 [speak] 时**才 `TextToSpeech(appContext) { … }` 绑定系统服务，而不是在
 * 构造时。此前 [rememberPlatformTts] 在**进入对话页**时就构造它，于是出现两个问题：
 *
 * 1. 用户从没点过朗读也会绑定一次系统 TTS 服务；离开页面时引擎的异步初始化还没回调，
 *    `stop()` 会打出 `TextToSpeech: stop failed: not bound to TTS engine`。
 * 2. 只调 `stop()`、**从不调 `shutdown()`**，每次进出对话页都会泄漏一个 TextToSpeech
 *    实例与它的 ServiceConnection。
 *
 * 现在改为第一次 [speak] 时才 [ensureEngine] 绑定，页面销毁时 [shutdown] 释放。
 *
 * ## 初始化失败必须留痕
 *
 * 「点朗读完全没声音、也没有任何报错」在真机上极难自查。这里把两种失败都记下来并打日志：
 * ① 引擎初始化返回非 SUCCESS（API 30+ 最常见的原因是 AndroidManifest 没声明
 * `<queries>` 里的 `android.intent.action.TTS_SERVICE`，枚举不到任何引擎）；
 * ② 引擎在、但没有中文语音数据（能初始化却读不出中文，现象同样是「没声音」）。
 */
internal class AndroidPlatformTts(context: Context) : PlatformTts {
    private val appContext = context.applicationContext
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private var isShutdown = false
    private var initFailure: String? = null

    /** 首次朗读时才绑定系统引擎；已经 shutdown 的实例不再复活。 */
    private fun ensureEngine(): TextToSpeech? {
        if (isShutdown) return null
        tts?.let { return it }
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(appContext) init@{ status ->
            if (isShutdown) return@init
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val result = engine.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val fallback = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // 引擎在，但没有中文语音数据 —— 能初始化却读不出中文，现象同样是「没声音」。
                        val failure = "语音引擎缺少中文语音数据（setLanguage=$fallback）"
                        initFailure = failure
                        Log.w(TAG, failure)
                    }
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _currentSpeakingId.value = utteranceId
                    }

                    override fun onDone(utteranceId: String?) {
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        clearSpeaking(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        clearSpeaking(utteranceId)
                    }
                })
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                // 非 SUCCESS 时原来什么都不做：既不报错也不消费 pendingSpeak，用户只会看到「没声音」。
                val failure = "语音引擎初始化失败（status=$status）。API 30+ 请确认 AndroidManifest " +
                    "声明了 <queries> 里的 android.intent.action.TTS_SERVICE"
                initFailure = failure
                Log.e(TAG, failure)
                pendingSpeak = null
            }
        }
        tts = engine
        return engine
    }

    private fun clearSpeaking(utteranceId: String?) {
        if (_currentSpeakingId.value == utteranceId) {
            _isSpeaking.value = false
            _currentSpeakingId.value = null
        }
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
        // speaker 在这里用不上：系统 TTS 只有引擎自带的 1~2 个中文音色，
        // 角色差异靠 pitch/speed/volume 表达（见 SpeechVoice.deliver）。
        val clean = cleanSpokenText(text)
        if (clean.isBlank()) return
        val engine = ensureEngine() ?: return

        val action: () -> Unit = {
            engine.stop()
            engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
            engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            if (voiceName.isNotBlank()) {
                val targetVoice = engine.voices?.firstOrNull { it.name.contains(voiceName, ignoreCase = true) }
                if (targetVoice != null) {
                    engine.voice = targetVoice
                }
            }
            val params = Bundle()
            // 音量必须走引擎参数：它是「放轻/耳语」这类情绪的唯一表达手段，
            // 用系统音量或干脆不传都做不到单句控制。
            params.putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                volume.coerceIn(0f, 1f),
            )
            // 与神经引擎对称地留一行日志：这样「用的是哪条引擎」在诊断里一眼可辨，
            // 不必去猜「为什么所有人一个声音」。
            Log.i(
                TAG,
                "朗读（系统 TTS）：说话人=「$speaker」pitch=${"%.2f".format(pitch)} " +
                    "speed=${"%.2f".format(speed)} volume=${"%.2f".format(volume)} " +
                    "voice=${voiceName.ifBlank { "默认" }}",
            )
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id)
            _isSpeaking.value = true
            _currentSpeakingId.value = id
        }

        when {
            isInitialized -> action()
            // 已经确定不可用：不要继续挂在 pendingSpeak 上装作在排队，留下原因。
            initFailure != null -> Log.w(TAG, "无法朗读：$initFailure")
            else -> pendingSpeak = action
        }
    }

    override fun stop() {
        _isSpeaking.value = false
        _currentSpeakingId.value = null
        pendingSpeak = null
        // 引擎还没绑定完成时调用 stop() 只会打一条 "not bound to TTS engine" 警告，
        // 而状态上面已经清干净了，直接跳过。
        if (isInitialized) tts?.stop()
    }

    override fun shutdown() {
        stop()
        isShutdown = true
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

/**
 * 取得朗读器。
 *
 * [selection] 决定用系统 TTS 还是离线神经 TTS——**由调用方从偏好传入**，不读全局状态：
 * 引擎选错的表现只是「没声音」或「声音不对」，放进全局可变状态会让「谁改的」无从追查。
 * 神经引擎不可用时会**降级到系统 TTS**，降级原因见 [resolveSpeechEngine]（界面应显示出来）。
 *
 * 会话内复用：只在页面存活期间保留；`onDispose` 必须调 [PlatformTts.shutdown]（不是 `stop`），
 * 否则实例与其 ServiceConnection（神经引擎还持有几百 MB 原生句柄）会随每次进出页面泄漏。
 */
@Composable
fun rememberPlatformTts(selection: SpeechEngineSelection = SpeechEngineSelection()): PlatformTts {
    val context = LocalContext.current
    // 神经引擎很重（要加载上百 MB 模型），所以只在这里按选择构造一次；
    // 选择变了才会重建。系统引擎则始终是廉价的后备。
    val platformTts = remember(selection, context) {
        val model = NeuralVoiceCatalog.byId(selection.neuralModelId)
        val modelDir = selection.neuralModelDir.takeIf { it.isNotBlank() }?.let { File(it) }
        val existing = modelDir?.takeIf { it.isDirectory }?.list()?.toList().orEmpty()
        val decision = resolveSpeechEngine(selection, model, existing)
        if (decision.engine == SpeechEngine.NEURAL && model != null && modelDir != null) {
            Log.i(TAG, "使用离线神经语音引擎：${model.id} @ ${modelDir.absolutePath}")
            SherpaNeuralTts(context, model, modelDir)
        } else {
            // 降级必须留痕：否则用户会以为「所谓不同音色就是这样」，而他听到的一直是系统 TTS。
            decision.fallbackReason?.let { Log.w(TAG, it) }
            AndroidPlatformTts(context)
        }
    }
    // key 里带 platformTts 而不是 Unit：切换引擎会**换掉实例**，
    // 旧实例（持有几百 MB 的原生引擎与协程作用域）必须被释放，否则会一直留在内存里。
    DisposableEffect(platformTts) {
        onDispose {
            // 用 shutdown 而不是 stop：神经引擎持有原生句柄，只 stop 会把引擎留在内存里。
            platformTts.shutdown()
        }
    }
    return platformTts
}
