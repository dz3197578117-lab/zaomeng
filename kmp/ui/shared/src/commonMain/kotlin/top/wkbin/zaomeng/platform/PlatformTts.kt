package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨平台文字转语音 (TTS) 播放器抽象。
 */
@Stable
interface PlatformTts {
    /** 当前是否正在播放语音 */
    val isSpeaking: StateFlow<Boolean>

    /** 当前正在播放的语句/消息 ID */
    val currentSpeakingId: StateFlow<String?>

    /**
     * 播放指定文本。
     * @param id 语句唯一标识（用于跟踪播放状态与 UI 动效高亮）
     * @param text 待朗读文本（会自动过滤舞台提示与动作括号）
     * @param pitch 音调倍率 (0.5 ~ 1.5，标准为 1.0)
     * @param speed 语速倍率 (0.5 ~ 2.0，标准为 1.0)
     * @param volume 音量倍率 (0.0 ~ 1.0，标准为 1.0)。低语、耳语、气声这类情绪靠它才做得出来，
     *               单靠「快慢高低」表现不了「放轻」。
     * @param speaker 说话的角色名。系统 TTS 用不上（它只有引擎自带音色），
     *               但**神经引擎靠它映射到说话人编号**——这正是「每个角色不同声音」的落点。
     * @param voiceName 声线预设或系统音色标识
     */
    fun speak(
        id: String,
        text: String,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        volume: Float = 1.0f,
        speaker: String = "",
        voiceName: String = "",
    )

    /**
     * 告知本次会话的参演角色，供神经引擎分配**互不相同**的音色。
     *
     * 为什么需要整份名单而不是逐个来：音色分配要同时满足「同一角色永远同一音色」与
     * 「同场角色不撞车」，只有拿到完整名单才能一次算完（见 `assignNeuralSpeakers`）。
     * 系统 TTS 忽略此项——它没有多音色可用。
     */
    fun setCast(participants: List<String>) = Unit

    /** 停止当前播放 */
    fun stop()

    /** 释放 TTS 引擎资源 */
    fun shutdown()
}

/**
 * 智能清洗朗读文本：剔除中文全角括号（...）、半角括号 (...)、方括号 [...] 中的动作与神态描写，
 * 仅保留角色真实说出的台词。
 */
fun cleanSpokenText(raw: String): String {
    if (raw.isBlank()) return ""
    val stripped = raw
        .replace(Regex("（[^）]*）"), "")
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^\\]]*\\]"), "")
        .replace(Regex("〔[^〕]*〕"), "")
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return stripped.ifBlank { raw.trim() }
}

/**
 * 取得朗读器。
 *
 * [selection] 决定用系统 TTS 还是离线神经 TTS——**由调用方从偏好传入**，不读全局状态：
 * 引擎选错的表现只是「没声音」或「声音不对」，放进全局可变状态会让「谁改的」无从追查。
 * 神经引擎不可用时会**降级到系统 TTS**，降级原因见 [resolveSpeechEngine]（界面应显示出来）。
 */
@Composable
expect fun rememberPlatformTts(selection: SpeechEngineSelection = SpeechEngineSelection()): PlatformTts
