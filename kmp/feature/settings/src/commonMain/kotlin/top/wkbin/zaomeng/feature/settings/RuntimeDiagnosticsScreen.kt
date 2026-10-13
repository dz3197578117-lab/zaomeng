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
import top.wkbin.zaomeng.data.DiagnosticsExportTier
import top.wkbin.zaomeng.data.RuntimeCaptureStatus
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto
import top.wkbin.zaomeng.data.api.FeatureProbeStatuses
import top.wkbin.zaomeng.data.api.KnownFeature
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
    // R3：导出分级必须在动手之前就定好——默认精简，避免用户不小心导出几十 MB 发不出去。
    var exportTier by remember { mutableStateOf(DiagnosticsExportTier.COMPACT) }
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.export(sink, exportTier) },
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
                        onClick = {
                            exportTier = DiagnosticsExportTier.COMPACT
                            fileExporter("zaomeng-diagnostics.json", "application/json")
                        },
                        enabled = !state.exporting,
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出精简诊断 JSON")
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
                        onExport = { tier ->
                            exportTier = tier
                            fileExporter("zaomeng-diagnostics.json", "application/json")
                        },
                        exportingTier = state.exportingTier,
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
                item(key = "feature-probes") {
                    FeatureProbesCard(
                        overview = overview,
                        probing = state.probing,
                        expanded = state.expandedProbes,
                        unregisteredExpanded = state.unregisteredExpanded,
                        onToggleProbe = viewModel::toggleProbe,
                        onToggleUnregistered = viewModel::toggleUnregistered,
                        onRefresh = viewModel::refreshProbes,
                    )
                }
                item(key = "self-status") {
                    SelfStatusCard(
                        overview = overview,
                        expanded = state.selfStatusExpanded,
                        onToggle = viewModel::toggleSelfStatus,
                    )
                }
                item(key = "feature-events") {
                    FeatureEventsCard(overview = overview)
                }
                item(key = "operation-trail") {
                    OperationTrailCard(
                        overview = overview,
                        expanded = state.trailExpanded,
                        onToggle = viewModel::toggleTrail,
                    )
                }
                item(key = "smoothness") {
                    SmoothnessCard(overview = overview)
                }
                item(key = "blind-spots") {
                    BlindSpotsCard(
                        overview = overview,
                        expanded = state.blindSpotsExpanded,
                        onToggle = viewModel::toggleBlindSpots,
                    )
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
    onExport: (String) -> Unit,
    exportingTier: String,
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
        // 问题计数并列显示：崩溃/错误（fatal+error）、正常退出（被系统清理/被更新）、探针 FAIL。
        // 后两者**不计入**前者，避免一次覆盖安装就被当成一堆崩溃。
        // R2：区分「组数」与「次数」——「3 条崩溃」可能是同一个堆栈崩了 3 次。
        Text(
            text = "问题：${overview.problemGroupCount} 组 / 累计 ${overview.problemTotalCount} 次" +
                " · 崩溃/错误 ${overview.crashCount}" +
                " · 正常退出 ${overview.intentionalExitCount}" +
                " · 功能自检 ❌ ${overview.probeFailCount}",
            style = MaterialTheme.typography.bodySmall,
            color = if (overview.problems.isEmpty() && overview.probeFailCount == 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "功能自检：已注册 ${overview.featureProbes.registeredCount} 项探针" +
                " / ${overview.featureProbes.unregisteredCount} 个功能未注册",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
        Text(
            text = "导出分级：**精简**＝概览 + 问题清单摘要 + 探针结论；" +
                "**完整**＝另含运行日志、完整堆栈与事件明细（体积可能到几十 MB）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onExport(DiagnosticsExportTier.COMPACT) },
                enabled = !exporting,
            ) { Text(if (exportingTier == DiagnosticsExportTier.COMPACT) "导出中…" else "导出精简 JSON") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsExportTier.FULL) },
                enabled = !exporting,
            ) { Text(if (exportingTier == DiagnosticsExportTier.FULL) "导出中…" else "导出完整 JSON") }
        }
    }
}

private const val MiB = 1024L * 1024L

/**
 * 「功能自检」区块。
 *
 * 每项一行：`功能名 · ✅/⚠️/❌/— · 一句话结论 · 关键数字`，可展开看细节。
 * 顶部一行是 **「已注册 N 项探针 / M 个功能未注册」**——新增功能若忘了注册探针，
 * 这里会直接把名字摊开，而不是靠人记得。
 *
 * 采集开关对这里**没有任何影响**：探针读的是清单/档案文件与独立的事件环形缓冲；
 * 只有确实需要日志样本的项才会显示「—（无样本）」。
 */
@Composable
private fun FeatureProbesCard(
    overview: DiagnosticsOverviewDto,
    probing: Boolean,
    expanded: Set<String>,
    unregisteredExpanded: Boolean,
    onToggleProbe: (String) -> Unit,
    onToggleUnregistered: () -> Unit,
    onRefresh: () -> Unit,
) {
    val report = overview.featureProbes
    SectionCard(title = "功能自检") {
        Text(
            text = "已注册 ${report.registeredCount} 项探针 / ${report.unregisteredCount} 个功能未注册" +
                "（已知功能 ${report.knownFeatureCount}）",
            style = MaterialTheme.typography.bodyMedium,
        )
        val failCount = report.items.count { it.status == FeatureProbeStatuses.FAIL }
        Text(
            text = "✅${report.okCount} · ⚠️${report.warnCount} · ❌$failCount" +
                " · 无数据 ${report.noDataCount} · 无样本 ${report.skippedCount}" +
                (if (report.cachedCount > 0) " · 缓存 ${report.cachedCount}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "本区块与运行日志采集开关无关：探针只读清单/档案与独立的事件缓冲；" +
                "需要日志样本的项会显示「—（无样本）」，不会被当成故障。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report.unknownFeatureRefs.isNotEmpty()) {
            Text(
                text = "⚠️ 探针引用了未知功能 id（注册处与 KnownFeatures 漂移）：" +
                    report.unknownFeatureRefs.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRefresh, enabled = !probing) {
                Text(if (probing) "重跑中…" else "重新自检")
            }
            Text(
                text = "共 ${report.items.size} 项 · 耗时 ${report.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (report.items.isEmpty()) {
            Text(
                text = "还没有探针结果（服务端可能正在预热）。点「重新自检」立即跑一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 按域分组展示；域顺序与 KnownFeatures 一致。
        val domainOrder = KnownFeature.DOMAINS + report.items.map { it.domain }.distinct()
            .filter { it !in KnownFeature.DOMAINS }
        domainOrder.forEach { domain ->
            val items = report.items.filter { it.domain == domain }
            val unregistered = report.unregisteredFeatures.filter { it.domain == domain }
            if (items.isEmpty() && unregistered.isEmpty()) return@forEach
            val suffix = if (unregistered.isNotEmpty()) " · 未注册 ${unregistered.size}" else ""
            Text(
                text = "$domain（探针 ${items.size}$suffix）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { probe ->
                ProbeRow(
                    probe = probe,
                    expanded = probe.id in expanded,
                    onToggle = { onToggleProbe(probe.id) },
                )
            }
            if (unregistered.isNotEmpty() && unregisteredExpanded) {
                Text(
                    text = "未注册：" + unregistered.joinToString("、") { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (report.unregisteredCount > 0) {
            TextButton(onClick = onToggleUnregistered) {
                Text(if (unregisteredExpanded) "收起未注册功能" else "展开未注册功能（${report.unregisteredCount}）")
            }
        }
    }
}

/** 一行探针：功能名 · 状态 · 一句话结论 · 关键数字（可展开细节）。 */
@Composable
private fun ProbeRow(
    probe: top.wkbin.zaomeng.data.api.FeatureProbeDto,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val (icon, color) = when (probe.status) {
        "OK" -> "✅" to MaterialTheme.colorScheme.primary
        "WARN" -> "⚠️" to MaterialTheme.colorScheme.tertiary
        "FAIL" -> "❌" to MaterialTheme.colorScheme.error
        "SKIPPED" -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = "$icon ${probe.title} · ${probe.summary}",
            style = MaterialTheme.typography.bodySmall,
            color = if (probe.status == "FAIL") color else MaterialTheme.colorScheme.onSurface,
        )
        val numbers = probe.evidence.entries.take(4)
            .joinToString(" · ") { (key, value) -> "$key=$value" }
        if (numbers.isNotBlank()) {
            Text(
                text = numbers,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (probe.details.isNotEmpty() || probe.evidence.size > 4) {
            TextButton(onClick = onToggle, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (expanded) "收起" else "详情")
            }
            if (expanded) {
                SelectionContainer {
                    Column {
                        probe.evidence.entries.drop(4).forEach { (key, value) ->
                            Text(
                                text = "$key=$value",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        probe.details.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = "checkedAt=${probe.checkedAt} · ${probe.durationMs}ms" +
                                if (probe.features.isNotEmpty()) " · 覆盖 ${probe.features.joinToString(",")}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

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
            text = "问题清单（按严重度：崩溃 > ANR > 卡死/长任务 > 卡顿 > 视觉信号 > 事件 > 提示；" +
                "同堆栈/同签名已归为一组，点开看次数、首末时间与发生前的上下文）",
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
    // 先在表达式里算好再插值：Kotlin 的字符串模板里不能再嵌一个带引号的字符串字面量。
    val firstText = problem.firstAt.ifBlank { problem.occurredAt }.ifBlank { "时间未知" }
    val lastText = problem.lastAt.ifBlank { problem.occurredAt }.ifBlank { "时间未知" }
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
                text = "${problemKindLabel(problem.kind)} · ${problem.severity}" +
                    " · 发生 ${problem.count} 次" +
                    " · 首次 $firstText" +
                    " · 最近 $lastText" +
                    " · 权重 ${problem.priority}",
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
            // R2 面包屑：问题**发生之前**的上下文（页面跳转、关键操作、早前的事件、内存水位）。
            if (problem.breadcrumbs.isNotEmpty()) {
                Text(
                    text = "发生之前（最近 ${problem.breadcrumbs.size} 条）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                problem.breadcrumbs.take(6).forEach { crumb ->
                    Text(
                        text = "· [${crumb.at}] ${crumb.source}/${crumb.label} ${crumb.detail}".trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * 「诊断自身状态」（R3）。
 *
 * 回答的是一个很容易被忽略的问题：**清单是空的，是「真的没问题」还是「事件根本没记上」？**
 * 丢事件计数、写入失败计数、采样开销与当前占用都在这里摊开。
 */
@Composable
private fun SelfStatusCard(
    overview: DiagnosticsOverviewDto,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val status = overview.selfStatus
    val retention = status.retention
    SectionCard(title = "诊断自身状态") {
        Text(
            text = "丢事件 ${status.droppedEvents} · 写入失败 ${status.writeFailures}" +
                " · 读取失败 ${status.readFailures} · 采样开销 ${status.samplingOverheadMs}ms" +
                "（${status.samplingSamples} 次）",
            style = MaterialTheme.typography.bodySmall,
            color = if (status.droppedEvents > 0 || status.writeFailures > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "占用：事件 ${retention.eventEntries}/${retention.eventMaxEntries} 条" +
                " · ${retention.eventBytes / 1024} KiB / ${retention.eventMaxBytes / 1024} KiB" +
                " · 崩溃 ${retention.crashEntries}/${retention.crashMaxEntries}" +
                if (retention.trimmed) " · 已自动裁剪" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onToggle) { Text(if (expanded) "收起说明" else "展开说明") }
        if (expanded) {
            Text(
                text = status.note.ifBlank { "没有需要额外说明的自身状态。" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // D：如实说明这份「采样开销」覆盖了哪些采样器——不写清楚，那个数字会被当成诊断总开销。
            Text(
                text = status.samplingNote.ifBlank { "采样开销的覆盖范围未标注。" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "面包屑 ${retention.breadcrumbEntries}/${retention.breadcrumbMaxEntries}" +
                    " · 功能事件 ${retention.featureEventKeys} 组" +
                    " · 操作轨迹 ${retention.operationTrailEntries}/${retention.operationTrailMaxEntries}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (retention.note.isNotBlank()) {
                Text(
                    text = retention.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 通用功能事件（R4）。
 *
 * 这一块是「还没写诊断代码的新功能也能被抓到」的可见证据：
 * 只要操作走过服务端 HTTP 路由、客户端 repository 请求或聊天工具分发，
 * 就会自动按 `功能 × 操作` 出现在这里。
 */
@Composable
private fun FeatureEventsCard(overview: DiagnosticsOverviewDto) {
    val events = overview.featureEvents
    SectionCard(title = "功能事件（统一入口自动覆盖 ${events.size} 组）") {
        if (events.isEmpty()) {
            Text(
                text = "还没有功能事件：说明这一版还没有任何走统一入口的调用发生。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            text = "功能 = route / repository / chat.tool；成功、降级、失败分别计数，另给平均与最长耗时。" +
                "新功能只要走这些入口，就会自动出现在这里，不需要写任何诊断代码。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        events.take(12).forEach { stat ->
            val color = when {
                stat.failedCount > 0 -> MaterialTheme.colorScheme.error
                stat.degradedCount > 0 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = "${stat.feature} / ${stat.action}",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            Text(
                text = "共 ${stat.total} · ✅${stat.okCount} ⚠️${stat.degradedCount} ❌${stat.failedCount}" +
                    " · 成功率 ${stat.successRate}% · 平均 ${stat.avgDurationMs}ms · 最长 ${stat.maxDurationMs}ms" +
                    " · 最近 ${stat.lastAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stat.lastDetail.isNotBlank()) {
                Text(
                    text = "最近详情：${stat.lastDetail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (events.size > 12) {
            Text(
                text = "（只显示失败最多/调用最多的 12 组；完整清单在导出里）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 操作轨迹（R6）：导航历史 + 关键操作序列，与问题面包屑同源。 */
@Composable
private fun OperationTrailCard(
    overview: DiagnosticsOverviewDto,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val trail = overview.operationTrail
    SectionCard(title = "操作轨迹（${trail.size} 条）") {
        if (trail.isEmpty()) {
            Text(
                text = "还没有轨迹：客户端导航上报未接入或本平台不支持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val shown = if (expanded) trail.takeLast(40) else trail.takeLast(8)
        shown.reversed().forEach { entry ->
            Text(
                text = "[${entry.at}] ${entry.kind} · ${entry.label} ${entry.detail}".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trail.size > shown.size) {
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起（只看最近 8 条）" else "展开更多（最近 40 条）")
            }
        }
    }
}

/**
 * 画面与流畅度（R7）。
 *
 * 措辞纪律写在界面上：这些是**观测到的现象 + 数字**，不是「检测到 bug」。
 */
@Composable
private fun SmoothnessCard(overview: DiagnosticsOverviewDto) {
    val smoothness = overview.smoothness
    SectionCard(title = "画面与流畅度") {
        if (!smoothness.supported) {
            Text(
                text = "暂无样本：帧指标 / 重组计数需要真机采样（本机未接入或平台不支持）。" +
                    "**无样本不等于流畅。**",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            text = "帧 ${smoothness.totalFrames} · >16.7ms ${smoothness.over16Count}" +
                " · >32ms ${smoothness.over32Count} · >100ms ${smoothness.over100Count}" +
                " · 最长 ${smoothness.longestFrameMs}ms",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "主线程阻塞 ${smoothness.mainThreadBlockCount} 次",
            style = MaterialTheme.typography.bodySmall,
            color = if (smoothness.mainThreadBlockCount > 0) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        smoothness.mainThreadBlocks.take(3).forEach { block ->
            Text(
                text = block,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (smoothness.pageBuckets.isNotEmpty()) {
            Text("按页面分桶的卡顿直方图：", style = MaterialTheme.typography.labelSmall)
            smoothness.pageBuckets.take(6).forEach { bucket ->
                Text(
                    text = "${bucket.page}：${bucket.frames} 帧 · >16.7 ${bucket.over16}" +
                        " · >32 ${bucket.over32} · >100 ${bucket.over100} · 最长 ${bucket.longestMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (smoothness.recompositions.isNotEmpty()) {
            Text("过度重组计数（优先于截图）：", style = MaterialTheme.typography.labelSmall)
            smoothness.recompositions.take(8).forEach { item ->
                Text(
                    text = "${item.name}：${item.count} 次 · 单帧最多 ${item.maxPerFrame}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val duplicates = smoothness.imageRequests.filter { it.duplicateRequests > 0 }
        if (duplicates.isNotEmpty()) {
            Text("图片同 URL 重复请求：", style = MaterialTheme.typography.labelSmall)
            duplicates.take(5).forEach { item ->
                Text(
                    text = "${item.url}：请求 ${item.requests} · 缓存命中 ${item.cacheHits}" +
                        " · 重复 ${item.duplicateRequests}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (smoothness.layoutJitter.isNotEmpty()) {
            Text("布局跳动：", style = MaterialTheme.typography.labelSmall)
            smoothness.layoutJitter.take(5).forEach { item ->
                Text(
                    text = "${item.name}：${item.events} 次 · 最大位移 ${item.maxShiftDp}dp / ${item.windowMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val flicker = smoothness.flicker
        Text(
            text = "截图差分：${if (flicker.enabled) "已开启" else "默认关闭"}" +
                (if (flicker.supported) "" else " · 本平台不支持") +
                " · 像素不落盘（pixelStoredToDisk=${flicker.pixelStoredToDisk}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        flicker.regions.forEach { region ->
            // 措辞如实：只能写「疑似闪烁」，不许写「检测到 bug」。
            Text(
                text = "疑似闪烁（${region.region} ${region.windowMs}ms 内变化 ${region.changes} 次）@ ${region.at}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = smoothness.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 盲区补齐（R6）：覆盖范围 / 可访问性 / 数据损坏审计。 */
@Composable
private fun BlindSpotsCard(
    overview: DiagnosticsOverviewDto,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val coverage = overview.coverage
    val a11y = overview.accessibility
    val integrity = overview.dataIntegrity
    SectionCard(title = "盲区与覆盖范围") {
        Text(
            text = "进程模型：${coverage.processModel}" +
                if (coverage.processNames.isNotEmpty()) "（${coverage.processNames.joinToString("、")}）" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "WebView：${if (coverage.webViewPresent) "存在，需处理 onRenderProcessGone" else "未使用"} · ${coverage.webViewNote}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        coverage.uncoveredSurfaces.forEach { surface ->
            Text(
                text = "未覆盖：$surface",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = "可访问性：采样 ${a11y.sampledNodes} 个节点（可点 ${a11y.clickableNodes}）" +
                " · 缺标签 ${a11y.missingLabelCount} · 点击目标 <48dp ${a11y.smallTargetCount}" +
                " · 对比度${if (a11y.contrastChecked) "已检查" else "**未做**"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (a11y.missingLabelCount + a11y.smallTargetCount > 0) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = "数据完整性：关键文件 ${integrity.keyFilesParsable}/${integrity.keyFilesChecked} 可解析" +
                " · 解析失败 ${integrity.parseFailures.size} · 写入失败 ${integrity.writeFailures.size}",
            style = MaterialTheme.typography.bodySmall,
            color = if (integrity.keyFilesFailing.isEmpty() && integrity.writeFailures.isEmpty()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        TextButton(onClick = onToggle) { Text(if (expanded) "收起细节" else "展开细节") }
        if (expanded) {
            Text(
                text = coverage.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            coverage.coveredSurfaces.forEach { Text("已覆盖：$it", style = MaterialTheme.typography.labelSmall) }
            if (a11y.findings.isNotEmpty()) {
                Text("可访问性问题（最多 20 条）：", style = MaterialTheme.typography.labelSmall)
                a11y.findings.take(20).forEach { finding ->
                    Text(
                        text = "${finding.page} · ${finding.issue} · ${finding.detail}" +
                            if (finding.nodeRef.isNotBlank()) "（${finding.nodeRef}）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            integrity.keyFilesFailing.forEach { path ->
                Text("损坏：$path", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            (integrity.parseFailures + integrity.writeFailures).take(10).forEach { issue ->
                Text(
                    text = "${issue.kind} · ${issue.path} · ${issue.reason} · ${issue.sizeBytes}B · ${issue.modifiedAt}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = integrity.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
