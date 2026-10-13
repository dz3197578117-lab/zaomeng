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
import top.wkbin.zaomeng.data.DiagnosticsExportTier
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.RuntimeCaptureStateStore
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto
import top.wkbin.zaomeng.data.api.FeatureProbeStatuses
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
    /** 正在导出的档位（compact / full）；空表示没有在导出。 */
    val exportingTier: String = "",
    /** 是否展开了「诊断自身状态」区块。 */
    val selfStatusExpanded: Boolean = false,
    /** 是否展开了操作轨迹。 */
    val trailExpanded: Boolean = false,
    /** 是否展开了「未覆盖的面 / 数据损坏审计 / 可访问性」等次要区块。 */
    val blindSpotsExpanded: Boolean = false,
    val overview: DiagnosticsOverviewDto? = null,
    val capture: RuntimeCaptureStatus = RuntimeCaptureStatus(),
    /** 已展开的问题键（点一下看完整堆栈）。 */
    val expanded: Set<String> = emptySet(),
    /** 已展开的功能自检探针 id。 */
    val expandedProbes: Set<String> = emptySet(),
    /** 是否展开了「未注册功能」列表。 */
    val unregisteredExpanded: Boolean = false,
    /** 正在重跑探针。 */
    val probing: Boolean = false,
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

    /** 展开/收起一条功能自检探针的细节。 */
    fun toggleProbe(probeId: String) {
        mutableState.update { current ->
            val expanded = current.expandedProbes
            current.copy(expandedProbes = if (probeId in expanded) expanded - probeId else expanded + probeId)
        }
    }

    /** 展开/收起「未注册功能」列表。 */
    fun toggleUnregistered() {
        mutableState.update { it.copy(unregisteredExpanded = !it.unregisteredExpanded) }
    }

    /**
     * 重新执行功能自检探针（显式刷新）。
     *
     * 概览里的探针结果是上一轮快照；点这里才会真正重跑。
     * **与运行日志采集开关无关**：探针读的是清单/档案文件与独立的事件环形缓冲。
     */
    fun refreshProbes() {
        viewModelScope.launch {
            mutableState.update { it.copy(probing = true, error = "", message = "") }
            try {
                val report = diagnostics.refreshFeatureProbes()
                mutableState.update { current ->
                    val overview = current.overview
                    current.copy(
                        probing = false,
                        overview = overview?.copy(
                            featureProbes = report,
                            probeFailCount = report.items.count { it.status == FeatureProbeStatuses.FAIL },
                        ),
                        message = "功能自检已重跑：✅${report.okCount} ⚠️${report.warnCount} ❌${report.items.count { it.status == FeatureProbeStatuses.FAIL }}" +
                            "（未注册 ${report.unregisteredCount}）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        probing = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "功能自检重跑失败。",
                    )
                }
            }
        }
    }

    /**
     * 导出完整诊断 JSON。
     *
     * 与 [SettingsViewModel.exportDiagnostics] 同理：必须 suspend 并直接写完 sink，
     * FileKit 的回调返回后目标流就被关闭了。
     */
    suspend fun export(destination: Sink, tier: String = DiagnosticsExportTier.COMPACT) {
        val compact = tier != DiagnosticsExportTier.FULL
        mutableState.update { it.copy(exporting = true, exportingTier = tier, error = "", message = "") }
        try {
            val written = withContext(platformIoDispatcher) {
                diagnostics.exportDiagnostics(destination, tier)
            }
            mutableState.update {
                it.copy(
                    exporting = false,
                    exportingTier = "",
                    message = (if (compact) "已导出精简诊断" else "已导出完整诊断") +
                        "（${written / 1024} KiB）。" +
                        if (compact) "精简档不含日志正文与完整堆栈。" else "",
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(exporting = false, exportingTier = "") }
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update {
                it.copy(
                    exporting = false,
                    exportingTier = "",
                    error = error.message?.takeIf(String::isNotBlank) ?: "诊断信息导出失败。",
                )
            }
        }
    }

    /** 展开/收起「诊断自身状态」。 */
    fun toggleSelfStatus() {
        mutableState.update { it.copy(selfStatusExpanded = !it.selfStatusExpanded) }
    }

    /** 展开/收起操作轨迹。 */
    fun toggleTrail() {
        mutableState.update { it.copy(trailExpanded = !it.trailExpanded) }
    }

    /** 展开/收起盲区补齐区块。 */
    fun toggleBlindSpots() {
        mutableState.update { it.copy(blindSpotsExpanded = !it.blindSpotsExpanded) }
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

/**
 * 问题的稳定键。
 *
 * R2 起优先用服务端算出的分组键 [DiagnosticsProblemDto.fingerprint]：
 * 同一条问题在刷新后必须落到同一个 key，否则「展开的堆栈」会跳到别的问题上。
 */
internal fun problemKey(problem: DiagnosticsProblemDto): String =
    problem.fingerprint.ifBlank { "${problem.kind}|${problem.lastAt}|${problem.title.hashCode()}" }

/**
 * 问题分类展示名。
 *
 * 与服务端 `DiagnosticsProblemTaxonomy.kindLabel` 保持同一套文案——
 * 两处不一致时，同一条问题在概览与导出里会叫两个名字。
 */
internal fun problemKindLabel(kind: String): String = when (kind) {
    "crash" -> "崩溃"
    "anr" -> "ANR"
    "freeze" -> "卡死 / 长任务"
    "jank" -> "卡顿"
    "visual" -> "视觉信号"
    "process-exit" -> "进程死因"
    "intentional-exit" -> "正常退出"
    "event" -> "结构化事件"
    "self-check" -> "自检"
    // 提供方内容审核拒绝：独立分组，文案与服务端 kindLabel 一致。
    "provider-policy" -> "提供方策略拒绝（非应用故障）"
    else -> kind
}
