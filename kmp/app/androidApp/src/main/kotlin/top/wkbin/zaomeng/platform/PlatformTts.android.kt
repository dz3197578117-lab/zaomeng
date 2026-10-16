package top.wkbin.zaomeng.platform

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 系统 TTS 引擎的**惰性**封装。
 *
 * 此前引擎在构造函数里就 `TextToSpeech(appContext) { … }` 绑定系统服务，而
 * [rememberPlatformTts] 在**进入对话页**时就构造它。于是出现两个问题：
 *
 * 1. 用户从没点过朗读也会绑定一次系统 TTS 服务；离开页面时引擎的异步初始化还没回调，
 *    `stop()` 会打出 `TextToSpeech: stop failed: not bound to TTS engine`。
 * 2. `rememberPlatformTts` 的 `onDispose` 只调 `stop()`、**从不调 `shutdown()`**，
 *    每次进出对话页都会泄漏一个 TextToSpeech 实例与它的 ServiceConnection。
 *
 * 现在改为第一次 [speak] 时才 [ensureEngine] 绑定，页面销毁时 [shutdown] 释放。
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
                    engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
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
        voiceName: String,
    ) {
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
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id)
            _isSpeaking.value = true
            _currentSpeakingId.value = id
        }

        if (isInitialized) {
            action()
        } else {
            pendingSpeak = action
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
 * 会话内复用的 TTS 句柄。
 *
 * 只在页面存活期间保留；`onDispose` 必须调 [PlatformTts.shutdown]（不是 `stop`），
 * 否则 TextToSpeech 实例与其 ServiceConnection 会随每次进出页面泄漏。
 */
@Composable
fun rememberPlatformTts(): PlatformTts {
    val context = LocalContext.current
    val platformTts = remember { AndroidPlatformTts(context) }
    DisposableEffect(Unit) {
        onDispose {
            platformTts.shutdown()
        }
    }
    return platformTts
}
