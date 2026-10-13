package top.wkbin.zaomeng.ktor.features

import top.wkbin.zaomeng.data.api.AccessibilityAuditDto
import top.wkbin.zaomeng.data.api.AccessibilityFindingDto
import top.wkbin.zaomeng.data.api.DataIntegrityAuditDto
import top.wkbin.zaomeng.data.api.DataIntegrityIssueDto
import top.wkbin.zaomeng.data.api.DiagnosticsCoverageDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsEvent
import top.wkbin.zaomeng.platform.ClientDiagnostics
import top.wkbin.zaomeng.platform.DataIntegrityCategories
import top.wkbin.zaomeng.platform.ProcessSnapshot
import top.wkbin.zaomeng.platform.nowIsoString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 关键文件的**可解析性**与损坏审计（R6）。
 *
 * 存在的理由：`session.json` / `run_manifest.json` / `arc.json` 一旦被写坏（断电、
 * 磁盘满、半截写入），用户看到的现象是「这本书打不开了」「会话列表是空的」，
 * 而诊断报告此前对此**一个字都没有**——排查只能靠猜。
 *
 * 硬约束：
 * - **只读**：只读文件、只解析，绝不修复（修复必须由用户显式触发）；
 * - 有界：只深查最近改动的若干本书卷 / 若干会话 / 若干角色；
 * - 失败可定位：路径（相对存储根）、原因、大小、mtime、损坏样本四件套一起给。
 */
object DataIntegrityAudit {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一次审计能检查多少本 / 会话 / 角色（保持廉价）。 */
    const val DEFAULT_MAX_RUNS = 4
    const val DEFAULT_MAX_SESSIONS = 6
    const val DEFAULT_MAX_CHARACTERS = 8

    fun audit(
        ctx: FeatureProbeContext,
        maxRuns: Int = DEFAULT_MAX_RUNS,
        maxSessions: Int = DEFAULT_MAX_SESSIONS,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    ): DataIntegrityAuditDto {
        val checked = mutableListOf<String>()
        val failing = mutableListOf<String>()

        fun check(label: String, path: okio.Path) {
            if (!ctx.isFile(path)) return
            checked += label
            val text = runCatching { ctx.storage.readTextOrNull(path) }.getOrNull()
            if (text.isNullOrBlank()) {
                failing += label
                recordParseFailure(ctx, path, "文件为空或读不到内容", "")
                return
            }
            val ok = runCatching { json.parseToJsonElement(text).jsonObject }.isSuccess
            if (!ok) {
                failing += label
                recordParseFailure(ctx, path, "JSON 解析失败（可能是半截写入或编码损坏）", text)
            }
        }

        ctx.runIdsForProbe().take(maxRuns.coerceAtLeast(1)).forEach { runId ->
            val manifestPath = runCatching { ctx.storage.getRunManifestPath(runId) }.getOrNull()
            if (manifestPath != null) check("runs/$runId/run_manifest.json", manifestPath)

            ctx.listSessionIds(runId).take(maxSessions.coerceAtLeast(1)).forEach { sessionId ->
                val sessionPath = runCatching {
                    ctx.storage.getDialogueSessionManifestFile(runId, sessionId)
                }.getOrNull()
                if (sessionPath != null) check("runs/$runId/sessions/$sessionId/session.json", sessionPath)
            }

            // 角色弧线：不猜 novelId，直接列目录——猜错路径会把「文件不存在」误报成「损坏」。
            val charactersRoot = ctx.runFile(runId, "artifacts/characters")
            if (ctx.isDirectory(charactersRoot)) {
                ctx.listFiles(charactersRoot).take(4).forEach { novelDir ->
                    if (!ctx.isDirectory(novelDir)) return@forEach
                    ctx.listFiles(novelDir).take(maxCharacters.coerceAtLeast(1)).forEach { characterDir ->
                        val arc = characterDir / "arc.json"
                        if (ctx.isFile(arc)) {
                            check("${ctx.storage.safeAuditPath(arc)}", arc)
                        }
                    }
                }
            }
        }

        // 写入审计：由 StorageService 在写入失败时写入的固定类别事件。
        val writeFailures = eventsOfCategory(ctx, DataIntegrityCategories.WRITE_FAILURE)
        val parseFailures = eventsOfCategory(ctx, DataIntegrityCategories.PARSE_FAILURE)

        return DataIntegrityAuditDto(
            keyFilesChecked = checked.size,
            keyFilesParsable = checked.size - failing.size,
            keyFilesFailing = failing.take(MAX_LISTED),
            parseFailures = parseFailures,
            writeFailures = writeFailures,
            note = "只做可解析性判定，**不做修复**；崩溃/被系统清理不会导致文件损坏，" +
                "损坏通常来自半截写入或磁盘问题。检查范围：最近 $DEFAULT_MAX_RUNS 本书卷的 " +
                "run_manifest.json / session.json / arc.json。",
        )
    }

    /** 启动自检用的「关键文件可解析性」一行结论。 */
    fun startupSelfCheckLine(audit: DataIntegrityAuditDto): String = when {
        audit.keyFilesChecked == 0 -> "无关键文件可检查（还没有书卷）"
        audit.keyFilesFailing.isEmpty() -> "ok（${audit.keyFilesParsable}/${audit.keyFilesChecked} 可解析）"
        else -> "损坏 ${audit.keyFilesFailing.size}/${audit.keyFilesChecked}：${audit.keyFilesFailing.take(3).joinToString("、")}"
    }

    /** 记一条解析失败：路径 / 原因 / 大小 / mtime / 样本由 `reportParseFailure` 统一补齐。 */
    private fun recordParseFailure(
        ctx: FeatureProbeContext,
        path: okio.Path,
        reason: String,
        sample: String,
    ) {
        runCatching {
            ctx.storage.reportParseFailure(
                target = path,
                reason = reason,
                sample = sample.take(MAX_SAMPLE_CHARS),
            )
        }
    }

    private fun eventsOfCategory(ctx: FeatureProbeContext, category: String): List<DataIntegrityIssueDto> {
        val events = ctx.recentEvents(EVENT_SCAN_LIMIT)
        return events.filter { it.category == category }
            .map { it.toIssue() }
            .takeLast(MAX_LISTED)
            .reversed()
    }

    private fun DiagnosticsEvent.toIssue(): DataIntegrityIssueDto = DataIntegrityIssueDto(
        at = ts,
        path = details["path"].orEmpty(),
        kind = if (category == DataIntegrityCategories.WRITE_FAILURE) "write-failure" else "parse-failure",
        reason = details["reason"].orEmpty().ifBlank { message },
        sizeBytes = details["size"]?.toLongOrNull() ?: 0L,
        modifiedAt = details["mtime"]?.toLongOrNull()?.let { epoch -> epoch.toString() }.orEmpty(),
        sample = details["sample"].orEmpty(),
    )

    /** 损坏样本保留时的问题：`sample` 字段本身也会进导出，因此单独再截一次。 */
    private const val MAX_SAMPLE_CHARS = 400
    private const val MAX_LISTED = 20
    private const val EVENT_SCAN_LIMIT = 400

    /** 供测试/调用方构造 JSON 审计用的轻量入口。 */
    fun parseCheck(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return runCatching { json.parseToJsonElement(text).jsonObject }.isSuccess
    }

}

/**
 * 覆盖范围如实标注（R6）。
 *
 * 诊断只能报告它**实际看得到**的东西。把「没覆盖」写清楚，比多列几条指标更重要：
 * 用户看到「没有 native 崩溃」时，必须同时知道「native 崩溃只有信号与有限轨迹」。
 */
object CoverageReportBuilder {

    fun build(snapshot: ProcessSnapshot?, sourceLabel: String): DiagnosticsCoverageDto {
        val model = snapshot?.processModel ?: "unknown"
        val names = snapshot?.processNames.orEmpty()
        val webView = snapshot?.webViewPresent ?: false
        return DiagnosticsCoverageDto(
            processModel = model,
            processNames = names.take(8),
            webViewPresent = webView,
            webViewNote = snapshot?.webViewNote.orEmpty().ifBlank {
                if (webView) "检测到 WebView；onRenderProcessGone 未接入。" else "未检测到 WebView 使用。"
            },
            coveredSurfaces = listOf(
                "主进程未捕获异常（含协程未捕获异常）",
                "结构化事件（对话解析 / 插件动作 / 端侧状态 / 数据写入）",
                "进程退出记录（Android 10+ ApplicationExitInfo）",
                "功能自检探针与启动自检",
            ),
            uncoveredSurfaces = buildList {
                if (model != "single-process") {
                    add("其它进程的崩溃与日志（本仓库若为单进程则无此项）")
                }
                add("native 崩溃的完整堆栈（只有系统信号与有限轨迹）")
                add("系统/OEM 直接 kill -9 的场景（系统未必留下 ApplicationExitInfo）")
                if (webView) add("WebView 渲染进程崩溃（需 onRenderProcessGone 才能感知）")
            },
            note = "覆盖来源：$sourceLabel。'$model' 表示实测到的进程模型；" +
                "未覆盖的面在上面的 uncoveredSurfaces 里逐条列出，不做省略。",
        )
    }
}

/**
 * 可访问性审计（R6）。
 *
 * 两项必做检查：
 * 1. **语义节点缺失**：可点击但没有任何可读标签（TalkBack 会念成「按钮」）；
 * 2. **点击目标过小**：宽或高 < 48dp（Material 的最小可点区域）。
 *
 * 对比度**未做**：[AccessibilityAuditDto.contrastChecked] 明确为 false，
 * 界面上写「未做」——对比度需要在渲染后取实际像素与背景色，成本远高于前两项，
 * 本版不做，也不假装做了。
 *
 * 纯函数，node 列表由客户端采样后经 `ClientDiagnostics` 上报。
 */
object AccessibilityAuditor {

    const val MIN_TARGET_DP = 48

    fun audit(
        nodes: List<ClientDiagnostics.A11yNode>,
        maxFindings: Int = MAX_FINDINGS,
    ): AccessibilityAuditDto {
        if (nodes.isEmpty()) {
            return AccessibilityAuditDto(
                supported = false,
                note = "没有采到语义节点：客户端采样未接入或当前平台不支持（对比度审计本版未做）。",
            )
        }
        val clickable = nodes.filter { it.clickable }
        val findings = mutableListOf<AccessibilityFindingDto>()
        clickable.forEach { node ->
            if (node.label.isBlank()) {
                findings += AccessibilityFindingDto(
                    page = node.page,
                    nodeRef = node.ref,
                    issue = "missing-label",
                    detail = "可点击但没有任何语义标签（TalkBack 读不出用途）",
                    widthDp = node.widthDp,
                    heightDp = node.heightDp,
                )
            }
            if (node.widthDp in 1 until MIN_TARGET_DP || node.heightDp in 1 until MIN_TARGET_DP) {
                findings += AccessibilityFindingDto(
                    page = node.page,
                    nodeRef = node.ref,
                    issue = "small-target",
                    detail = "点击目标 ${node.widthDp}×${node.heightDp}dp，小于 ${MIN_TARGET_DP}dp",
                    widthDp = node.widthDp,
                    heightDp = node.heightDp,
                )
            }
        }
        return AccessibilityAuditDto(
            supported = true,
            sampledNodes = nodes.size,
            clickableNodes = clickable.size,
            missingLabelCount = findings.count { it.issue == "missing-label" },
            smallTargetCount = findings.count { it.issue == "small-target" },
            findings = findings.take(maxFindings),
            contrastChecked = false,
            note = "语义节点缺失与点击目标 < ${MIN_TARGET_DP}dp 已检查；" +
                "**对比度审计未做**（需要渲染后像素与背景色，本版不实现）。",
        )
    }

    private const val MAX_FINDINGS = 40
}

/** 把客户端上报的可访问性节点补齐时间戳后审计（采样由平台侧完成）。 */
fun auditAccessibility(nodes: List<ClientDiagnostics.A11yNode> = ClientDiagnostics.a11yNodeSnapshot()): AccessibilityAuditDto =
    AccessibilityAuditor.audit(nodes)

/** 采样时间（供界面展示「这次审计是什么时候采的」）。 */
fun accessibilitySampledAt(): String = nowIsoString()
