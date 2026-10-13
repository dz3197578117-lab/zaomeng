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
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal class AndroidPlatformTts(context: Context) : PlatformTts {
    private val appContext = context.applicationContext
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    /**
     * 初始化失败的原因。存在的意义是把「点朗读完全没声音、也没有任何报错」变成一条可排查的日志——
     * 这种情况在真机上极难自查，而它最常见的原因就是 Android 11+ 未在 AndroidManifest 里
     * 声明 `<queries>` 的 `android.intent.action.TTS_SERVICE`（枚举不到任何引擎）。
     */
    private var initFailure: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val fallback = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // 引擎在，但没有中文语音数据 —— 能初始化却读不出中文，现象同样是「没声音」。
                        val failure = "语音引擎缺少中文语音数据（setLanguage=$fallback）"
                        initFailure = failure
                        Log.w(TAG, failure)
                    }
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
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

        val action: () -> Unit = {
            tts?.let { engine ->
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
                engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id)
                _isSpeaking.value = true
                _currentSpeakingId.value = id
            }
        }

        if (isInitialized) {
            action()
        } else if (initFailure != null) {
            // 已经确定不可用：不要继续挂在 pendingSpeak 上装作在排队，留下原因。
            Log.w(TAG, "无法朗读：$initFailure")
        } else {
            pendingSpeak = action
        }
    }

    override fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentSpeakingId.value = null
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private companion object {
        const val TAG = "PlatformTts"
    }
}

@Composable
actual fun rememberPlatformTts(selection: SpeechEngineSelection): PlatformTts {
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
    DisposableEffect(Unit) {
        onDispose {
            // 用 shutdown 而不是 stop：神经引擎持有原生句柄，只 stop 会把引擎留在内存里。
            platformTts.shutdown()
        }
    }
    return platformTts
}

private const val TAG = "PlatformTts"
