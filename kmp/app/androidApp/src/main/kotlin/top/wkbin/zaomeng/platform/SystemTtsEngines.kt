package top.wkbin.zaomeng.platform

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/**
 * 设备上已安装的系统 TTS 引擎。
 *
 * ## 为什么需要它
 *
 * 造梦原先只会绑定「系统默认 TTS 引擎」。而 Android 允许安装第三方 TTS 引擎，
 * 例如 [NekoSpeak][NEKO_SPEAK_PACKAGE]：它通过标准 `TextToSpeechService` 暴露 Pocket-TTS 零样本克隆、
 * 名人音色与 100+ Piper 社区音色。用户把它设为系统引擎后，Legado 这类阅读器就能给它按角色指定音色。
 *
 * 造梦要做的不是「再实现一遍克隆」，而是**让用户能选中这个引擎**，并用它暴露的音色做角色分配。
 */
data class SystemTtsEngine(
    val packageName: String,
    /** 展示名（引擎自己声明的 label，取不到时回落为包名）。 */
    val label: String,
) {
    /**
     * NekoSpeak：支持零样本克隆与名人音色。
     *
     * 包名取自其应用 id（`com.nekospeak.tts`）。只用于界面标注与排序，不影响绑定逻辑——
     * 任何用户选中的引擎都会走同一条标准 TTS 路径。
     */
    val isNekoSpeak: Boolean get() = packageName == NEKO_SPEAK_PACKAGE

    companion object {
        const val NEKO_SPEAK_PACKAGE = "com.nekospeak.tts"
    }
}

/**
 * 枚举已安装的 TTS 引擎。
 *
 * 用 `queryIntentServices` 而不是创建一个 `TextToSpeech` 实例去读 `engines`：
 * 后者要真正绑定引擎（可能触发几百 ms 初始化，甚至拉起对方进程），而设置页只需要一个列表。
 *
 * 依赖 manifest 里的 `<queries>`（`android.intent.action.TTS_SERVICE`）——项目已有该声明，
 * Android 11+ 缺了它会直接返回空列表。
 */
fun listSystemTtsEngines(context: Context): List<SystemTtsEngine> {
    val pm = context.packageManager
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return runCatching {
        pm.queryIntentServices(intent, 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
                SystemTtsEngine(
                    packageName = service.packageName,
                    label = label.ifBlank { service.packageName },
                )
            }
            .distinctBy { it.packageName }
            // NekoSpeak 排最前：它是当前唯一明确支持「多音色 / 克隆」的第三方引擎。
            .sortedWith(compareByDescending<SystemTtsEngine> { it.isNekoSpeak }.thenBy { it.label })
    }.getOrDefault(emptyList())
}
