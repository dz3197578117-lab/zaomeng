package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.rememberFileExporter

/**
 * 运行日志页。
 *
 * 展示内嵌后端返回的、已脱敏的**整个应用运行时日志**：Android 上是本进程 logcat 的
 * 持续采集结果（含 native、第三方库与崩溃堆栈），可刷新、可整体选中复制、可导出成
 * `zaomeng-runtime-log.txt` 发给排查者。
 *
 * 本页负责**常规日志采集的启停、查看与清除旧日志**：
 * - 「持续采集」开关控制后端是否把日志写入应用私有日志文件，状态持久化、重启后保持；
 * - 关闭采集不清除已采集内容，页面仍能看到已有日志（未捕获异常与卡顿不受开关影响，始终记录）；
 * - 「清除旧日志」带二次确认，只清运行日志与崩溃/卡顿记录，不动任何业务数据。
 *
 * 悬浮窗开关、问题捕获与自检在「运行诊断」页（两页职责分开）。
 * 内容与「运行诊断」导出里的 runtimeLog 段同源同口径（同一个服务端入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeLogScreen(
    viewModel: RuntimeLogViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.export(sink) },
        onCancelled = {},
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = viewModel::requestClear,
                        enabled = !state.clearing && state.snapshot != null,
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清除旧日志")
                    }
                    IconButton(
                        onClick = { fileExporter("zaomeng-runtime-log.txt", "text/plain") },
                        enabled = !state.exporting && state.snapshot != null,
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出日志")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            RuntimeCaptureCard(
                capture = state.capture,
                toggling = state.togglingCapture,
                clearing = state.clearing,
                onToggleCapture = viewModel::setCaptureEnabled,
                onRequestClear = viewModel::requestClear,
            )
            Spacer(Modifier.height(12.dp))
            RuntimeLogSummary(state)
            Spacer(Modifier.height(12.dp))
            RuntimeLogBody(state)
        }
    }

    if (state.showClearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("清除旧日志？") },
            text = {
                Text(
                    "只会清空本机已采集的运行日志与崩溃/卡顿记录，" +
                        "不会影响书卷、会话、记忆、模型配置等任何业务数据。此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClear) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClear) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuntimeCaptureCard(
    capture: RuntimeCaptureStatus,
    toggling: Boolean,
    clearing: Boolean,
    onToggleCapture: (Boolean) -> Unit,
    onRequestClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("持续采集", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = captureStatusLabel(capture),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !capture.known -> MaterialTheme.colorScheme.onSurfaceVariant
                            capture.enabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                Switch(
                    checked = capture.enabled,
                    onCheckedChange = onToggleCapture,
                    enabled = capture.supported && !toggling,
                )
            }
            if (!capture.supported) {
                Text(
                    text = "当前平台常开采集（进程内缓冲），不支持启停。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (capture.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = capture.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("清除旧日志", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "只清运行日志与崩溃记录，不影响书卷、会话、记忆与模型配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRequestClear, enabled = !clearing) { Text("清除") }
            }
        }
    }
}

/** 采集状态文案：读不到状态时绝不能显示成「未采集」（那会误导用户以为没在录）。 */
private fun captureStatusLabel(capture: RuntimeCaptureStatus): String = when {
    !capture.supported -> "常开采集"
    !capture.known -> "状态读取中…"
    capture.enabled -> "采集中"
    else -> "已停止（未采集）"
}

@Composable
private fun RuntimeLogSummary(state: RuntimeLogUiState) {
    val snapshot = state.snapshot
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = sourceLabel(snapshot),
                style = MaterialTheme.typography.titleSmall,
            )
            snapshot?.let {
                Text(
                    text = buildString {
                        append("行数 ${it.lines.size}")
                        if (it.truncated) append("（仅尾部）")
                        append(" · 本进程累计 ${it.totalLines}")
                        if (it.crashes.isNotEmpty()) append(" · 崩溃记录 ${it.crashes.size} 条")
                        if (it.processExits.isNotEmpty()) append(" · 进程退出 ${it.processExits.size} 条")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                environmentLine(it)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (it.capturedAt.isNotBlank()) {
                    Text(
                        text = "采集时间 ${it.capturedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (it.note.isNotBlank()) {
                    Text(
                        text = it.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loading) {
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 版本/机型一行：排查时第一眼要确认「装的是哪个版本、什么机器」。 */
private fun environmentLine(snapshot: RuntimeLogDto): String? {
    val app = snapshot.environment.app
    val device = snapshot.environment.device
    if (app.versionName.isBlank() && device.model.isBlank()) return null
    return buildString {
        if (app.versionName.isNotBlank()) append("v${app.versionName}（${app.versionCode}）")
        if (device.model.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(device.model)
            if (device.osRelease.isNotBlank()) append(" · Android ${device.osRelease}")
            if (device.abis.isNotEmpty()) append(" · ${device.abis.first()}")
        }
    }
}

@Composable
private fun RuntimeLogBody(state: RuntimeLogUiState) {
    val snapshot = state.snapshot
    when {
        snapshot == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        snapshot == null -> Text(
            text = state.error.ifBlank { "暂时读不到运行日志。" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    text = formatRuntimeLog(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun sourceLabel(snapshot: RuntimeLogDto?): String = when (snapshot?.source) {
    RuntimeLogDto.SOURCE_LOGCAT_FILE -> "本进程 logcat（持续采集）"
    RuntimeLogDto.SOURCE_LOGCAT_DUMP -> "logcat 一次性抓取"
    RuntimeLogDto.SOURCE_PROCESS_BUFFER -> "进程内日志缓冲（兜底）"
    RuntimeLogDto.SOURCE_UNAVAILABLE -> "运行日志不可用"
    null -> "运行日志"
    else -> snapshot.source
}
