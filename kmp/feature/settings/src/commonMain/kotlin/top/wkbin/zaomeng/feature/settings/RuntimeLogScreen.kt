package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.rememberFileExporter

/**
 * 运行日志页。
 *
 * 展示内嵌后端返回的、已脱敏的**整个应用运行时日志**：Android 上是本进程 logcat 的
 * 持续采集结果（含 native、第三方库与崩溃堆栈），可刷新、可整体选中复制、可导出成
 * `zaomeng-runtime-log.txt` 发给排查者。
 *
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
            RuntimeLogSummary(state)
            Spacer(Modifier.height(12.dp))
            RuntimeLogBody(state)
        }
    }
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
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
