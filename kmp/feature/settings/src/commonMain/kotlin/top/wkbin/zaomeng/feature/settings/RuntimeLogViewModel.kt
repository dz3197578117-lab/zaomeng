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
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 「运行日志」页状态。
 *
 * [snapshot] 是服务端（内嵌后端）返回的、已脱敏的日志快照；页面只负责展示与导出，
 * 不再二次加工，保证与「运行诊断」导出的日志口径完全一致。
 */
data class RuntimeLogUiState(
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val snapshot: RuntimeLogDto? = null,
    val error: String = "",
    val message: String = "",
)

/**
 * 运行日志页 ViewModel。
 *
 * 对应设置 → 应用与支持 → 运行日志。数据来自内嵌后端的
 * `GET /api/web/diagnostics/runtime-log`：Android 上就是本进程 logcat 的采集结果
 * （含 native、第三方库与崩溃堆栈）。
 */
class RuntimeLogViewModel(
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RuntimeLogUiState())
    val state: StateFlow<RuntimeLogUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** 重新拉取日志（页面进入时自动调用一次，也可手动刷新）。 */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "", message = "") }
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
     * 导出为文本文件。
     *
     * 与 [SettingsViewModel.exportDiagnostics] 同理：必须是 suspend 并在此处直接写完 sink。
     * FileKit 的 onSave 回调返回后目标流即被收尾关闭，改成异步写只会得到空文件。
     *
     * 导出取比页面展示更多的行数，并包含崩溃记录。
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
    }
}

/**
 * 把日志快照格式化成可直接阅读/转发的文本。
 *
 * 头部保留来源与行数说明——排查时「日志是不是被系统缓冲挤掉了」和日志本身一样重要。
 */
internal fun formatRuntimeLog(snapshot: RuntimeLogDto): String = buildString {
    appendLine("# 造梦运行日志（已脱敏）")
    appendLine("# 生成时间：${snapshot.capturedAt}")
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
}
