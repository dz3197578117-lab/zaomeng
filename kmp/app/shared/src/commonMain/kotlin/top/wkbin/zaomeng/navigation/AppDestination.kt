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
