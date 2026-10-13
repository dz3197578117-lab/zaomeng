package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal class JvmPlatformTts : PlatformTts {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var activeJob: Job? = null
    private var activeProcess: Process? = null

    private val osName = System.getProperty("os.name", "").lowercase()
    private val isMac = osName.contains("mac")

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

        activeJob = scope.launch {
            _isSpeaking.value = true
            _currentSpeakingId.value = id
            try {
                // pitch 在桌面两套后端上都没有对应开关，故此处不生效（Android 端有效）。
                if (isMac) speakMac(clean, speed, volume, voiceName) else speakLinux(clean, speed)
            } catch (e: Exception) {
                // Ignore platform process launch/cancellation errors
            } finally {
                if (_currentSpeakingId.value == id) {
                    _isSpeaking.value = false
                    _currentSpeakingId.value = null
                }
            }
        }
    }

    private fun speakMac(text: String, speed: Float, volume: Float, voiceName: String) {
        val rate = (speed * 180).toInt().coerceIn(100, 350)
        val cmd = mutableListOf("say", "-r", rate.toString())
        if (voiceName.isNotBlank()) {
            cmd.addAll(listOf("-v", voiceName))
        }
        // macOS 的 say 没有音量开关，但支持内联命令 [[volm x]]（x 为 0.0~1.0）。
        // 只有明显放轻时才插入，避免每句都带一段控制串。
        val spoken = if (volume < 0.95f) "[[volm ${volume.coerceIn(0f, 1f)}]] $text" else text
        cmd.add(spoken)
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        activeProcess = process
        process.waitFor()
    }

    private fun speakLinux(text: String, speed: Float) {
        // 注意：spd-say 没有可靠可用的音量/音高开关（不同版本参数不一致，传错会直接失败并静默无声），
        // 所以 Linux 端只用语速。要完整效果请用 Android 端。
        val rate = ((speed - 1.0f) * 50).toInt().coerceIn(-50, 50)
        val process = ProcessBuilder("spd-say", "-r", rate.toString(), "-l", "zh", text)
            .redirectErrorStream(true)
            .start()
        activeProcess = process
        process.waitFor()
    }

    override fun stop() {
        activeJob?.cancel()
        activeJob = null
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}
        activeProcess = null
        _isSpeaking.value = false
        _currentSpeakingId.value = null
    }

    override fun shutdown() {
        stop()
    }
}

@Composable
actual fun rememberPlatformTts(selection: SpeechEngineSelection): PlatformTts {
    // 桌面端只有系统语音（macOS 的 say / Linux 的 spd-say），没有随包的神经引擎，
    // 因此忽略 selection：选神经引擎时表现为「和系统一样」，而不是静默无声。
    val platformTts = remember { JvmPlatformTts() }
    DisposableEffect(Unit) {
        onDispose {
            platformTts.stop()
        }
    }
    return platformTts
}
