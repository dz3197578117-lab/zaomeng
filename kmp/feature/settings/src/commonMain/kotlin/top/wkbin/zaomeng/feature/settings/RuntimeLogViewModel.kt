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
import okio.buffer
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.RuntimeCaptureStateStore
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 「运行日志」页状态。
 *
 * [snapshot] 是服务端（内嵌后端）返回的、已脱敏的日志快照；页面只负责展示与导出，
 * 不再二次加工，保证与「运行诊断」导出的日志口径完全一致。
 *
 * [capture] 是采集开关 + 悬浮窗开关的合并视图（与服务端/DataStore 同一份真源）。
 */
data class RuntimeLogUiState(
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val clearing: Boolean = false,
    val togglingCapture: Boolean = false,
    val snapshot: RuntimeLogDto? = null,
    val capture: RuntimeCaptureStatus = RuntimeCaptureStatus(),
    /** 清除日志的二次确认对话框是否可见。 */
    val showClearConfirm: Boolean = false,
    val error: String = "",
    val message: String = "",
)

/**
 * 运行日志页 ViewModel。
 *
 * 对应设置 → 应用与支持 → 运行日志。数据来自内嵌后端的
 * `GET /api/web/diagnostics/runtime-log`：Android 上就是本进程 logcat 的采集结果
 * （含 native、第三方库与崩溃堆栈），并附带采集开关状态与版本/现场元信息。
 */
class RuntimeLogViewModel(
    private val diagnostics: DiagnosticsRepository,
    private val captureStore: RuntimeCaptureStateStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RuntimeLogUiState())
    val state: StateFlow<RuntimeLogUiState> = mutableState.asStateFlow()

    init {
        // 订阅共享状态：开关由悬浮窗或本页改动后，两处显示始终一致
        viewModelScope.launch {
            captureStore.status.collect { status ->
                mutableState.update { it.copy(capture = status) }
            }
        }
        refresh()
    }

    /** 重新拉取日志与采集状态（页面进入时自动调用一次，也可手动刷新）。 */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "", message = "") }
            captureStore.refresh()
            try {
                val snapshot = diagnostics.runtimeLog(DISPLAY_LINES)
                mutableState.update { it.copy(loading = false, snapshot = snapshot) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "运行日志读取失败。",
                    )
                }
            }
        }
    }

    /**
     * 开关「持续采集」。
     *
     * 幂等：切换进行中再次点击直接忽略；服务端侧同样幂等，重复请求不会重复启停。
     * 关闭**不清除**已采集内容——清除是独立动作（[confirmClear]）。
     */
    fun setCaptureEnabled(enabled: Boolean) {
        if (state.value.togglingCapture) return
        viewModelScope.launch {
            mutableState.update { it.copy(togglingCapture = true, error = "", message = "") }
            captureStore.setCaptureEnabled(enabled)
                .onSuccess { status ->
                    mutableState.update {
                        it.copy(
                            togglingCapture = false,
                            message = if (status.enabled) {
                                "已开启持续采集，新日志会写入应用私有日志文件。"
                            } else {
                                "已关闭持续采集：已采集内容保留，可继续查看/导出；未捕获异常与卡顿仍会记录。"
                            },
                        )
                    }
                    // 刷新一次：关闭时日志里会多一条「已关闭采集」标记，打开时也会有对应的起始标记
                    refreshSnapshot()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            togglingCapture = false,
                            error = error.message?.takeIf(String::isNotBlank) ?: "切换采集状态失败。",
                        )
                    }
                }
        }
    }

    /** 应用内悬浮窗开关（纯客户端偏好，立即持久化）。 */
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

    /** 请求清除旧日志（弹出二次确认）。 */
    fun requestClear() {
        mutableState.update { it.copy(showClearConfirm = true, error = "", message = "") }
    }

    fun dismissClear() {
        mutableState.update { it.copy(showClearConfirm = false) }
    }

    /** 确认清除：只清运行日志与崩溃/卡顿记录，绝不动任何业务数据。 */
    fun confirmClear() {
        if (state.value.clearing) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(clearing = true, showClearConfirm = false, error = "", message = "")
            }
            captureStore.clearCaptured()
                .onSuccess { result ->
                    mutableState.update { it.copy(clearing = false, message = clearResultMessage(result)) }
                    refreshSnapshot()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            clearing = false,
                            error = error.message?.takeIf(String::isNotBlank) ?: "清除运行日志失败。",
                        )
                    }
                }
        }
    }

    /** 只刷新日志正文（切换采集/清除之后），不重置反馈文案。 */
    private suspend fun refreshSnapshot() {
        runCatching { diagnostics.runtimeLog(DISPLAY_LINES) }
            .onSuccess { snapshot -> mutableState.update { it.copy(snapshot = snapshot) } }
    }

    /**
     * 导出为文本文件。
     *
     * 与 [SettingsViewModel.exportDiagnostics] 同理：必须是 suspend 并在此处直接写完 sink。
     * FileKit 的 onSave 回调返回后目标流即被收尾关闭，改成异步写只会得到空文件。
     *
     * 导出取比页面展示更多的行数，并包含崩溃记录、进程退出记录与版本/现场元信息。
     */
    suspend fun export(destination: Sink) {
        mutableState.update { it.copy(exporting = true, error = "", message = "") }
        try {
            val snapshot = runCatching { diagnostics.runtimeLog(EXPORT_LINES) }
                .getOrElse { mutableState.value.snapshot ?: throw it }
            val bytes = formatRuntimeLog(snapshot).encodeToByteArray()
            withContext(platformIoDispatcher) {
                destination.buffer().use { it.write(bytes) }
            }
            mutableState.update {
                it.copy(
                    exporting = false,
                    snapshot = snapshot,
                    message = "运行日志已导出（${bytes.size / 1024} KiB）。",
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(exporting = false) }
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update {
                it.copy(
                    exporting = false,
                    error = error.message?.takeIf(String::isNotBlank) ?: "运行日志导出失败。",
                )
            }
        }
    }

    private companion object {
        /** 页面展示行数：够翻出问题现场，又不至于让 Compose 一次渲染过多文本。 */
        const val DISPLAY_LINES = 1_500

        /** 导出行数：单独导出时多给一些，仍受服务端上限夹紧。 */
        const val EXPORT_LINES = 5_000

        /** 清除结果反馈文案：把「清了多少」和「采集是否还在跑」一起说清楚。 */
        fun clearResultMessage(result: RuntimeLogClearResultDto): String = buildString {
            append("已清除 ")
            append(result.clearedLines)
            append(" 行日志与 ")
            append(result.clearedCrashes)
            append(" 条崩溃/卡顿记录（不影响书卷、会话、记忆与模型配置）。")
            append(if (result.captureEnabled) "采集仍在进行，新日志会继续写入。" else "采集当前已停止，不会写入新日志。")
            result.note.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
        }
    }
}

/**
 * 把日志快照格式化成可直接阅读/转发的文本。
 *
 * 头部保留来源、行数、**应用版本与现场信息**——排查时「日志是不是被系统缓冲挤掉了」、
 * 「用户装的是哪个版本」、「现场是哪个会话」和日志本身一样重要。
 */
internal fun formatRuntimeLog(snapshot: RuntimeLogDto): String = buildString {
    appendLine("# 造梦运行日志（已脱敏）")
    appendLine("# 生成时间：${snapshot.capturedAt}")
    val app = snapshot.environment.app
    if (app.versionName.isNotBlank() || app.versionCode != 0) {
        appendLine("# 应用版本：${app.versionName}（${app.versionCode}）")
    }
    val device = snapshot.environment.device
    if (device.model.isNotBlank() || device.osRelease.isNotBlank()) {
        appendLine(
            "# 设备：" + buildString {
                append(device.manufacturer.takeIf(String::isNotBlank)?.let { "$it " }.orEmpty())
                append(device.model)
                if (device.osRelease.isNotBlank()) {
                    append(" · ${device.osName.ifBlank { "Android" }} ${device.osRelease}")
                }
                if (device.apiLevel > 0) append("（API ${device.apiLevel}）")
                if (device.abis.isNotEmpty()) append(" · ${device.abis.first()}")
                if (device.is64Bit) append(" · 64位")
            },
        )
    }
    val context = snapshot.environment.session
    if (context.runId.isNotBlank() || context.sessionId.isNotBlank()) {
        appendLine(
            "# 现场会话：run=${context.runId.ifBlank { "-" }} session=${context.sessionId.ifBlank { "-" }}" +
                context.reportedBy.takeIf(String::isNotBlank)?.let { "（来源：$it）" }.orEmpty(),
        )
    }
    appendLine(
        "# 采集状态：" + if (snapshot.capture.enabled) "采集中" else "已停止（未采集）" +
            if (snapshot.capture.supported) "" else "（本平台常开，不支持启停）",
    )
    appendLine("# 采集来源：${snapshot.source}${if (snapshot.available) "" else "（不可用）"}")
    appendLine(
        "# 行数：${snapshot.lines.size}" +
            (if (snapshot.truncated) "（仅尾部）" else "") +
            " · 本进程累计：${snapshot.totalLines}",
    )
    snapshot.note.takeIf(String::isNotBlank)?.let { appendLine("# 说明：$it") }
    appendLine("# " + "-".repeat(60))
    snapshot.lines.forEach { appendLine(it) }
    if (snapshot.crashes.isNotEmpty()) {
        appendLine()
        appendLine("# " + "=".repeat(20) + " 崩溃记录（${snapshot.crashes.size}） " + "=".repeat(20))
        snapshot.crashes.forEach { crash ->
            appendLine("# [${crash.occurredAt}] thread=${crash.thread}")
            appendLine("# ${crash.summary}")
            crash.stackTrace.lineSequence().forEach { appendLine(it) }
            appendLine()
        }
    }
    appendLine()
    appendLine("# " + "=".repeat(16) + " 进程退出记录（${snapshot.processExits.size}） " + "=".repeat(16))
    appendLine("# ${snapshot.processExitsNote}")
    if (snapshot.processExits.isEmpty()) {
        appendLine("# （没有可用的进程退出记录）")
    } else {
        snapshot.processExits.forEach { exit ->
            appendLine(
                "# [${exit.occurredAt}] ${exit.reason}（code=${exit.reasonCode}）" +
                    " status=${exit.status} importance=${exit.importance}" +
                    " pss=${exit.pssBytes / 1024}KiB rss=${exit.rssBytes / 1024}KiB" +
                    " 来源=${exit.source}",
            )
            exit.description.takeIf(String::isNotBlank)?.let { appendLine("# 描述：$it") }
            exit.trace.takeIf(String::isNotBlank)?.let { trace ->
                appendLine("# 轨迹（系统提供的有限轨迹，native 崩溃不是完整堆栈）：")
                trace.lineSequence().forEach { appendLine(it) }
            }
            appendLine()
        }
    }
}
