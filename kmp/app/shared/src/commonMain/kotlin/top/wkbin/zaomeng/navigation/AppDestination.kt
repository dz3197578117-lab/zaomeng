package top.wkbin.zaomeng.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object BookshelfDestination : NavKey

@Serializable
data object ImportBookDestination : NavKey

@Serializable
data object OnlineLibraryDestination : NavKey

@Serializable
data object CrossoverDestination : NavKey

@Serializable
data object ModelSettingsDestination : NavKey

@Serializable
data object ModelConfigurationDestination : NavKey

/** 独立的「本机模型（端侧）」页：导入/下载 → 启动 → 启用本机推理。 */
@Serializable
data object OnDeviceModelsDestination : NavKey

@Serializable
data class ModelProfileEditorDestination(val profileId: String = "") : NavKey

@Serializable
data object ChatDisplaySettingsDestination : NavKey

@Serializable
data object PluginsDestination : NavKey

@Serializable
data object PluginBuilderDestination : NavKey

@Serializable
data object AppearanceSettingsDestination : NavKey

@Serializable
data object StartupRecoverySettingsDestination : NavKey

@Serializable
data object AppSupportSettingsDestination : NavKey

/** 运行日志页：展示整个应用的运行时日志（Android 为本进程 logcat 采集）。 */
@Serializable
data object RuntimeLogDestination : NavKey

/**
 * 运行诊断页：问题捕获（崩溃/ANR/进程死因/结构化事件）、启动自检、现场元信息、
 * 模型连通性、应用内悬浮窗开关与诊断导出。
 *
 * 与「运行日志」页职责分开：常规日志采集的启停与清除在 [RuntimeLogDestination]。
 */
@Serializable
data object RuntimeDiagnosticsDestination : NavKey

@Serializable
data object AppUpdateDestination : NavKey

@Serializable
data class RunDetailDestination(val runId: String) : NavKey

@Serializable
data class RedistillDestination(val runId: String) : NavKey

@Serializable
data class RelationsDestination(val runId: String) : NavKey

@Serializable
data class WorldTimelineDestination(val runId: String) : NavKey

@Serializable
data class OriginalKnowledgeDestination(val runId: String) : NavKey

@Serializable
data class ChaptersDestination(val runId: String) : NavKey

@Serializable
data object CardLibraryDestination : NavKey

@Serializable
data class PersonaDestination(val runId: String, val character: String) : NavKey

@Serializable
data class SessionsDestination(val runId: String = "") : NavKey

@Serializable
data class ChatDestination(val runId: String, val sessionId: String) : NavKey

@Serializable
data class StoryRecapDestination(val runId: String, val sessionId: String) : NavKey

/**
 * 导航目标的**可读名字**（R6 操作轨迹 / R7 页面分桶）。
 *
 * 为什么不用 `NavKey::class.simpleName`：轨迹是要给人看的，`RunDetailDestination`
 * 这种类名在报告里对用户毫无意义；而且它带不了参数（哪一本书）。
 * 未知目标退化为类名，保证新增页面也会出现在轨迹里（只是名字粗一点）。
 */
fun destinationLabel(key: NavKey?): String = when (key) {
    null -> ""
    is BookshelfDestination -> "书卷架"
    is ImportBookDestination -> "导入书卷"
    is OnlineLibraryDestination -> "在线书库"
    is CrossoverDestination -> "书卷交叉"
    is ModelSettingsDestination -> "设置首页"
    is ModelConfigurationDestination -> "模型配置"
    is OnDeviceModelsDestination -> "本机模型"
    is ModelProfileEditorDestination -> if (key.profileId.isBlank()) "新增模型档案" else "编辑模型档案"
    is ChatDisplaySettingsDestination -> "对话显示设置"
    is PluginsDestination -> "插件"
    is PluginBuilderDestination -> "插件构建器"
    is AppearanceSettingsDestination -> "外观设置"
    is StartupRecoverySettingsDestination -> "启动与恢复"
    is AppSupportSettingsDestination -> "应用与支持"
    is RuntimeLogDestination -> "运行日志"
    is RuntimeDiagnosticsDestination -> "运行诊断"
    is AppUpdateDestination -> "应用更新"
    is RunDetailDestination -> "书卷详情（${key.runId}）"
    is RedistillDestination -> "重新整理（${key.runId}）"
    is RelationsDestination -> "人物关系（${key.runId}）"
    is WorldTimelineDestination -> "世界时间线（${key.runId}）"
    is OriginalKnowledgeDestination -> "原文证据（${key.runId}）"
    is ChaptersDestination -> "章节（${key.runId}）"
    is CardLibraryDestination -> "卡片库"
    is PersonaDestination -> "人物档案（${key.character}）"
    is SessionsDestination -> "会话列表"
    is ChatDestination -> "对话（${key.sessionId}）"
    is StoryRecapDestination -> "故事回顾"
    else -> key::class.simpleName ?: "未知页面"
}
