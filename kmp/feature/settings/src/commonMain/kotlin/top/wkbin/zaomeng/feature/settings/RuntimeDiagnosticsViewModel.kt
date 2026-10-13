package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Sink
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.RuntimeCaptureStateStore
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 「运行诊断」页状态。
 *
 * [overview] 是服务端同源同口径的轻量概览（自检 + 问题清单 + 统计 + 模型连通性），
 * 不含日志正文；完整档案仍走原有 JSON 导出。
 */
data class RuntimeDiagnosticsUiState(
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val overview: DiagnosticsOverviewDto? = null,
    val capture: RuntimeCaptureStatus = RuntimeCaptureStatus(),
    /** 已展开的问题键（点一下看完整堆栈）。 */
    val expanded: Set<String> = emptySet(),
    val error: String = "",
    val message: String = "",
)

/**
 * 运行诊断页 ViewModel。
 *
 * 职责划分（与「运行日志」页分开）：问题捕获结果（崩溃 / ANR / 进程死因 / 结构化事件）、
 * 启动自检、现场元信息、模型连通性、悬浮窗开关与导出。
 * 「运行日志」页只管常规日志采集的启停、查看与清除。
 */
class RuntimeDiagnosticsViewModel(
    private val diagnostics: DiagnosticsRepository,
    private val captureStore: RuntimeCaptureStateStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RuntimeDiagnosticsUiState())
    val state: StateFlow<RuntimeDiagnosticsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            captureStore.status.collect { status ->
                mutableState.update { it.copy(capture = status) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "", message = "") }
            captureStore.refresh()
            try {
                val overview = diagnostics.overview()
                mutableState.update { it.copy(loading = false, overview = overview) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "诊断概览读取失败。",
                    )
                }
            }
        }
    }

    /** 应用内悬浮窗开关（浮在本应用界面之上）。 */
    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { captureStore.setOverlayEnabled(enabled) }
                .onSuccess {
                    mutableState.update {
                        it.copy(message = if (enabled) "已开启应用内悬浮窗。" else "已关闭应用内悬浮窗。")
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(error = error.message?.takeIf(String::isNotBlank) ?: "悬浮窗开关保存失败。")
                    }
                }
        }
    }

    /** 展开/收起一条问题的完整堆栈。 */
    fun toggleProblem(key: String) {
        mutableState.update { current ->
            val expanded = current.expanded
            current.copy(expanded = if (key in expanded) expanded - key else expanded + key)
        }
    }

    /**
     * 导出完整诊断 JSON。
     *
     * 与 [SettingsViewModel.exportDiagnostics] 同理：必须 suspend 并直接写完 sink，
     * FileKit 的回调返回后目标流就被关闭了。
     */
    suspend fun export(destination: Sink) {
        mutableState.update { it.copy(exporting = true, error = "", message = "") }
        try {
            val written = withContext(platformIoDispatcher) { diagnostics.exportDiagnostics(destination) }
            mutableState.update {
                it.copy(exporting = false, message = "脱敏诊断信息已导出（${written / 1024} KiB）。")
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(exporting = false) }
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update {
                it.copy(
                    exporting = false,
                    error = error.message?.takeIf(String::isNotBlank) ?: "诊断信息导出失败。",
                )
            }
        }
    }

    /** 复制摘要成功后的反馈。 */
    fun onSummaryCopied() {
        mutableState.update { it.copy(message = "诊断摘要已复制，可直接发给开发者。") }
    }

    /** 没有服务端摘要时的本地兜底文案（至少带上版本与问题数）。 */
    fun fallbackSummary(): String {
        val overview = state.value.overview ?: return ""
        return buildString {
            appendLine("【造梦诊断摘要】")
            appendLine("版本：${overview.environment.app.versionName}（${overview.environment.app.versionCode}）")
            appendLine("设备：${overview.environment.device.model} · Android ${overview.environment.device.osRelease}")
            appendLine("问题共 ${overview.problems.size} 条：")
            overview.problems.take(8).forEach { appendLine("- ${it.kind}/${it.severity}：${it.title}") }
        }
    }
}

/** 问题的稳定键：列表用它做 key，展开状态也用它，避免刷新后错位。 */
internal fun problemKey(problem: DiagnosticsProblemDto): String =
    "${problem.kind}|${problem.occurredAt}|${problem.title.hashCode()}"

/** 问题分类展示名。 */
internal fun problemKindLabel(kind: String): String = when (kind) {
    "crash" -> "崩溃"
    "anr" -> "ANR 卡顿"
    "process-exit" -> "进程死因"
    "event" -> "结构化事件"
    "self-check" -> "自检"
    else -> kind
}
