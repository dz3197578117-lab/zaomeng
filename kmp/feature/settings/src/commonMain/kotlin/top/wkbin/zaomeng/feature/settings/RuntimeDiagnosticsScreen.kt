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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberToast

/**
 * 运行诊断页。
 *
 * 与「运行日志」页职责分开：
 * - 这里负责**问题捕获**（崩溃 / ANR / 进程死因 / 结构化事件）、**启动自检**、现场元信息、
 *   模型连通性、**应用内悬浮窗开关**与导出；
 * - 「运行日志」页只管常规日志采集的启停、查看与清除。
 *
 * 采集开关（[RuntimeCaptureStatus.enabled]）只控制常规日志；崩溃类捕获不受它影响，始终记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeDiagnosticsScreen(
    viewModel: RuntimeDiagnosticsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.export(sink) },
        onCancelled = {},
    )
    val writeClipboard = rememberClipboardTextWriter()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var kindFilter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行诊断") },
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
                        onClick = { fileExporter("zaomeng-diagnostics.json", "application/json") },
                        enabled = !state.exporting,
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出诊断 JSON")
                    }
                },
            )
        },
    ) { innerPadding ->
        val overview = state.overview
        when {
            overview == null && state.loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            overview == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            ) {
                Text(
                    text = state.error.ifBlank { "暂时读不到诊断概览。" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "overview") {
                    DiagnosticsOverviewCard(
                        overview = overview,
                        capture = state.capture,
                        exporting = state.exporting,
                        onCopySummary = {
                            val text = overview.copySummary.ifBlank { viewModel.fallbackSummary() }
                            if (text.isBlank()) {
                                toast("暂无可复制的诊断摘要。")
                            } else {
                                scope.launch {
                                    writeClipboard(text)
                                    viewModel.onSummaryCopied()
                                    toast("诊断摘要已复制。")
                                }
                            }
                        },
                        onExport = { fileExporter("zaomeng-diagnostics.json", "application/json") },
                    )
                }
                item(key = "overlay") {
                    OverlaySwitchCard(
                        capture = state.capture,
                        onToggle = viewModel::setOverlayEnabled,
                    )
                }
                item(key = "messages") {
                    if (state.message.isNotBlank() || state.error.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                item(key = "self-check") {
                    SectionCard(title = "启动自检") {
                        if (overview.startup.isEmpty()) {
                            Text(
                                text = "自检没有返回内容。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            overview.startup.forEach { (key, value) ->
                                Text(
                                    text = "$key：$value",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "model-tests") {
                    SectionCard(title = "模型连通性（最近 ${overview.modelTests.size} 次测试）") {
                        if (overview.modelTests.isEmpty()) {
                            Text(
                                text = "还没有测试记录：在模型设置里点一次「测试连接」就会记到这里。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            overview.modelTests.takeLast(6).reversed().forEach { test ->
                                Text(
                                    text = "[${test.testedAt}] ${if (test.success) "成功" else "失败"}" +
                                        " · ${test.provider}/${test.model} · ${test.latencyMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "${test.baseUrl} · ${test.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "event-stats") {
                    SectionCard(title = "结构化事件统计") {
                        if (overview.eventStats.isEmpty()) {
                            Text(
                                text = "没有记录到关键事件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            overview.eventStats.forEach { stat ->
                                Text(
                                    text = "${stat.category} × ${stat.count}（最近 ${stat.lastAt}）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = stat.lastMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "problem-filter") {
                    ProblemFilterRow(
                        overview = overview,
                        selected = kindFilter,
                        onSelect = { kindFilter = it },
                    )
                }
                val problems = overview.problems.filter { kindFilter.isBlank() || it.kind == kindFilter }
                if (problems.isEmpty()) {
                    item(key = "problems-empty") {
                        Text(
                            text = if (overview.problems.isEmpty()) {
                                "没有捕获到问题（崩溃、ANR、结构化事件都为空）。"
                            } else {
                                "当前筛选下没有问题。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(items = problems, key = { problemKey(it) }) { problem ->
                        ProblemCard(
                            problem = problem,
                            expanded = problemKey(problem) in state.expanded,
                            onToggle = { viewModel.toggleProblem(problemKey(problem)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsOverviewCard(
    overview: DiagnosticsOverviewDto,
    capture: RuntimeCaptureStatus,
    exporting: Boolean,
    onCopySummary: () -> Unit,
    onExport: () -> Unit,
) {
    val app = overview.environment.app
    val device = overview.environment.device
    val memory = overview.environment.memory
    SectionCard(title = "概览") {
        Text("版本：${app.versionName}（${app.versionCode}）", style = MaterialTheme.typography.bodySmall)
        Text(
            text = "机型：${device.manufacturer} ${device.model} · Android ${device.osRelease}（API ${device.apiLevel}）",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "ABI：${device.abis.joinToString(", ").ifBlank { "未知" }}" +
                (if (device.is64Bit) " · 64 位进程" else " · 32 位进程") +
                " · CPU ${device.cpuCount} 核",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "内存：堆 ${memory.heapUsedBytes / MiB} / ${memory.heapMaxBytes / MiB} MiB" +
                " · native ${memory.nativeHeapBytes / MiB} MiB" +
                (if (memory.systemAvailableBytes > 0) " · 系统可用 ${memory.systemAvailableBytes / MiB} MiB" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "后端：${overview.startup["backend_graph"] ?: "未知"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "采集：${if (capture.enabled) "采集中" else "已停止（未采集）"}" +
                "（只影响常规日志；崩溃/ANR/进程死因始终记录）",
            style = MaterialTheme.typography.bodySmall,
            color = if (capture.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (overview.environment.session.runId.isNotBlank() || overview.environment.session.sessionId.isNotBlank()) {
            Text(
                text = "当前现场：run=${overview.environment.session.runId.ifBlank { "-" }}" +
                    " session=${overview.environment.session.sessionId.ifBlank { "-" }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopySummary) { Text("复制摘要") }
            OutlinedButton(onClick = onExport, enabled = !exporting) { Text("导出 JSON") }
        }
    }
}

private const val MiB = 1024L * 1024L

@Composable
private fun OverlaySwitchCard(
    capture: RuntimeCaptureStatus,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("应用内悬浮窗", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "浮在本应用界面之上，常显采集状态；点一下直接打开运行日志页。" +
                        (if (capture.known && !capture.enabled) "当前采集已停止，悬浮窗会显示「未采集」。" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = capture.overlayEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ProblemFilterRow(
    overview: DiagnosticsOverviewDto,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val kinds = remember(overview) {
        overview.problems.map { it.kind }.distinct()
    }
    if (kinds.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "问题清单（按时间倒序，点击展开完整堆栈）",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = selected.isBlank(),
                onClick = { onSelect("") },
                label = { Text("全部 ${overview.problems.size}") },
            )
            kinds.forEach { kind ->
                FilterChip(
                    selected = selected == kind,
                    onClick = { onSelect(kind) },
                    label = { Text("${problemKindLabel(kind)} ${overview.problems.count { it.kind == kind }}") },
                )
            }
        }
    }
}

@Composable
private fun ProblemCard(
    problem: DiagnosticsProblemDto,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val accent = when (problem.severity) {
        "fatal" -> MaterialTheme.colorScheme.error
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${problemKindLabel(problem.kind)} · ${problem.severity} · ${problem.occurredAt.ifBlank { "时间未知" }}",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Text(text = problem.title, style = MaterialTheme.typography.bodyMedium)
            if (problem.source.isNotBlank()) {
                Text(
                    text = "来源：${problem.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (problem.detail.isNotBlank()) {
                TextButton(onClick = onToggle) { Text(if (expanded) "收起堆栈" else "展开堆栈") }
                if (expanded) {
                    SelectionContainer {
                        Text(
                            text = problem.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
