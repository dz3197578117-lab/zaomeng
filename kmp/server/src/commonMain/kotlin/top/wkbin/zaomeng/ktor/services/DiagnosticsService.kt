package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import okio.Path
import top.wkbin.zaomeng.ktor.models.ModelProfile
import top.wkbin.zaomeng.ktor.models.ModelSettings
import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.DiagnosticsEventStatDto
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto
import top.wkbin.zaomeng.data.api.EnvironmentInfoDto
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.data.api.ModelConnectivityRecordDto
import top.wkbin.zaomeng.data.api.ProcessExitAttributionDto
import top.wkbin.zaomeng.data.api.ProcessExitInfoDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.data.api.SessionContextDto
import top.wkbin.zaomeng.data.api.AccessibilityAuditDto
import top.wkbin.zaomeng.data.api.DataIntegrityAuditDto
import top.wkbin.zaomeng.data.api.DiagnosticsCoverageDto
import top.wkbin.zaomeng.data.api.DiagnosticsRetentionDto
import top.wkbin.zaomeng.data.api.DiagnosticsSelfStatusDto
import top.wkbin.zaomeng.data.api.FeatureEventStatDto
import top.wkbin.zaomeng.data.api.OperationTrailEntryDto
import top.wkbin.zaomeng.data.api.SmoothnessReportDto
import top.wkbin.zaomeng.ktor.features.SmoothnessReportBuilder
import top.wkbin.zaomeng.platform.ClientDiagnostics
import top.wkbin.zaomeng.platform.CrashContextRecord
import top.wkbin.zaomeng.platform.crashContextStampOf
import top.wkbin.zaomeng.platform.ExitAttributionSource
import top.wkbin.zaomeng.platform.ExitProblemKind
import top.wkbin.zaomeng.platform.ExitSeverity
import top.wkbin.zaomeng.platform.ProcessExitRecord
import top.wkbin.zaomeng.platform.RuntimeCaptureState
import top.wkbin.zaomeng.platform.RuntimeLogSource
import top.wkbin.zaomeng.platform.buildEnvironmentInfo
import top.wkbin.zaomeng.platform.classifyExitReason
import top.wkbin.zaomeng.platform.diskSpaceOf
import top.wkbin.zaomeng.platform.mappedExitReasonCodes
import top.wkbin.zaomeng.platform.mappedExitReasonLines
import top.wkbin.zaomeng.platform.unmappedExitReasonCodes
import top.wkbin.zaomeng.platform.wireName
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.systemProperty

/**
 * 诊断服务
 *
 * 生成系统诊断报告，对应 Python 的 DiagnosticsServiceMixin。
 *
 * 导出内容是自包含的：既有环境/存储/模型配置摘要，也有 [DiagnosticsEventLog] 里
 * 记录的关键事件（对话解析失败含模型输出摘录、插件动作失败、端侧推理状态变迁）、
 * 端侧 llama-server 的日志尾部，以及**整个应用的运行时日志**（[RuntimeLogDto]：
 * Android 为本进程 logcat 采集，含 native / 第三方库 / 崩溃），排查时不必再去掏 logcat。
 *
 * 日志与崩溃记录在进入报告前统一过 [LogRedactor]（密钥/令牌/Authorization 一律遮蔽）。
 * 每一节都独立容错：单节失败只降级为空，绝不让整个导出 500。
 */
class DiagnosticsService(
    private val storageRoot: Path,
    private val storageService: StorageService,
    private val eventLog: DiagnosticsEventLog? = null,
    private val onDeviceSnapshot: (() -> Map<String, String>)? = null,
    private val runtimeLogSource: RuntimeLogSource? = null,
    /**
     * 某 profile 是否真的配置了 API Key（查安全存储）。
     *
     * ModelProfile.apiKeyConfigured 是保存模型设置时写入的标记，
     * 而设置写入路径从不填这个字段，导致诊断报告永远报 false（假阴性）。
     * 用 lambda 传入以保持本服务不依赖 ModelApiKeyService，测试也可注入固定值。
     */
    private val apiKeyConfiguredOf: ((String) -> Boolean?)? = null,
    /** 模型连通性测试记录（「测试连接」的结果），让诊断里的模型一节有真实结论。 */
    private val connectivityLog: ModelConnectivityLog? = null,
    /** 数据根目录可写探测（由服务图层提供，避免 route/诊断层直接摸文件系统）。 */
    private val dataRootWritable: (() -> Boolean)? = null,
    /** 插件目录（自检展示用）。 */
    private val pluginsRoot: (() -> String)? = null,
    /** 进程已运行时长（毫秒）；-1 表示未知。 */
    private val processUptimeMillis: (() -> Long)? = null,
    /**
     * 功能自检（探针）报告。
     *
     * 用「同步快照 lambda」而不是让本服务去跑协程：探针是 suspend 且允许缓存，
     * 由服务图在装配时创建 [top.wkbin.zaomeng.ktor.features.FeatureProbeRunner] 并直接返回
     * **上一轮结果**（页面显式刷新时才重跑）。这样诊断导出与概览始终能立刻拿到结论，
     * 也不会因为某个探针慢而拖住 HTTP 请求。
     */
    private val featureProbeReport: (() -> FeatureProbeReportDto)? = null,
    /**
     * 问题面包屑（R2）：每条问题附带「发生之前」的上下文。
     *
     * 与事件日志分开持有：面包屑要把**客户端轨迹**（导航/操作）也算进去，
     * 事件日志只承载服务端事件。
     */
    private val breadcrumbs: DiagnosticsBreadcrumbs? = null,
    /** 数据损坏审计（R6）：关键文件可解析性 + 写入失败。只读，不做修复。 */
    private val dataIntegrityAudit: (() -> top.wkbin.zaomeng.data.api.DataIntegrityAuditDto)? = null,
) {
    /**
     * 触发一次功能自检刷新（显式的「重新自检」）。
     *
     * 之所以要单独一步：诊断导出与概览读的是**同步快照**（上一轮结论），
     * 不能被任何慢探针拖住；只有用户点刷新时才走这里真正重跑。
     *
     * @param refresh 返回最新探针报告的可挂起函数；为 null 时返回空报告。
     */
    suspend fun refreshFeatureProbes(refresh: (suspend () -> FeatureProbeReportDto)?): FeatureProbeReportDto =
        refresh?.invoke() ?: FeatureProbeReportDto()

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 构建诊断报告。
     *
     * @param runId / @param sessionId 客户端从「最近打开的会话」带上的现场上下文；
     *   服务端不知道 UI 状态，因此这两个值是可选的，且会在报告里标注来源。
     * @param tier 导出分级（R3）：
     *   - [DIAGNOSTICS_TIER_COMPACT]（默认）：概览 + 问题清单**摘要** + 探针结论 + 统计。
     *     不含日志正文、完整堆栈与事件明细——几十 MB 的导出根本传不出去，
     *     默认必须是能直接发给别人的这一档。
     *   - [DIAGNOSTICS_TIER_FULL]：加上运行日志、完整堆栈、完整事件明细与完整载荷。
     *     只有在需要深挖、且传递渠道允许大文件时才用。
     */
    fun buildDiagnosticsReport(
        runId: String = "",
        sessionId: String = "",
        tier: String = DIAGNOSTICS_TIER_COMPACT,
    ): DiagnosticsReport {
        val full = tier.equals(DIAGNOSTICS_TIER_FULL, ignoreCase = true)
        val modelSettings = runCatching { storageService.readModelSettings() }.getOrNull()
        val diskUsage = getDiskUsage()
        val runs = runCatching { buildRunsSummary() }.getOrDefault(emptyList())
        val profiles = runCatching { buildProfilesSummary(modelSettings) }.getOrDefault(emptyList())
        val selfCheck = runCatching { buildStartupSelfCheck() }.getOrDefault(emptyMap())
        // 历史 android_startup_report.json 仍然保留（客户端侧的自检结果），自检项覆盖同名键
        val startupReport = runCatching { loadStartupReport() }.getOrDefault(emptyMap()) +
            selfCheck.mapValues { (_, value) -> JsonPrimitive(value) }
        val recentEvents = runCatching { eventLog?.recent() ?: emptyList() }.getOrDefault(emptyList())
        val eventStats = runCatching { buildEventStats(recentEvents) }.getOrDefault(emptyList())
        val modelTests = runCatching { connectivityLog?.recent() ?: emptyList() }.getOrDefault(emptyList())
        val onDevice = runCatching { onDeviceSnapshot?.invoke().orEmpty() }.getOrDefault(emptyMap())
        val runtimeLog = if (full) {
            runCatching { buildRuntimeLogSnapshot(MAX_REPORT_LOG_LINES, runId, sessionId) }
                .getOrElse { RuntimeLogDto(capturedAt = nowIsoString(), note = "运行日志采集失败，已跳过该节。") }
        } else {
            // 精简档：保留采集状态与环境信息（排查第一步要看的就是这两样），
            // 但不带日志正文与崩溃堆栈——它们才是体积的来源。
            runCatching {
                buildRuntimeLogSnapshot(1, runId, sessionId).copy(
                    lines = emptyList(),
                    crashes = emptyList(),
                    truncated = true,
                    note = "精简导出：已省略运行日志正文与崩溃堆栈；需要完整内容请选择「导出完整 JSON」。",
                )
            }.getOrElse { RuntimeLogDto(capturedAt = nowIsoString(), note = "运行日志采集失败，已跳过该节。") }
        }
        val environment = runCatching { environmentInfo(runId, sessionId) }
            .getOrElse { EnvironmentInfoDto(capturedAt = nowIsoString()) }
        val probes = runCatching { featureProbeReport?.invoke() }.getOrNull() ?: FeatureProbeReportDto()
        val observedExitCodes = rawAllExits().map { it.reasonCode }

        val capture = runtimeCaptureState()
        val crashes = runCatching {
            runtimeLogSource?.snapshot(PROBLEM_SNAPSHOT_LINES)?.crashes.orEmpty()
        }.getOrDefault(emptyList()).let(LogRedactor::redactCrashes)
        val rawExitsForProblems = runCatching { runtimeLogSource?.processExits().orEmpty() }
            .getOrDefault(emptyList())
            .take(MAX_REPORT_EXITS)
        val problems = runCatching {
            buildProblems(crashes, rawExitsForProblems, recentEvents, selfCheck, capture, crashContexts())
        }.getOrDefault(emptyList())
        val selfStatus = buildSelfStatus(probes.durationMs)
        val coverage = runCatching { coverageReport() }.getOrDefault(top.wkbin.zaomeng.data.api.DiagnosticsCoverageDto())
        val accessibility = runCatching { auditAccessibilitySafely() }
            .getOrDefault(top.wkbin.zaomeng.data.api.AccessibilityAuditDto())
        val integrity = runCatching { dataIntegrityAudit?.invoke() }
            .getOrNull() ?: top.wkbin.zaomeng.data.api.DataIntegrityAuditDto()
        val smoothness = runCatching { top.wkbin.zaomeng.ktor.features.SmoothnessReportBuilder.build() }
            .getOrDefault(top.wkbin.zaomeng.data.api.SmoothnessReportDto())

        return DiagnosticsReport(
            kind = "zaomeng_diagnostics",
            schemaVersion = 6,
            tier = if (full) DIAGNOSTICS_TIER_FULL else DIAGNOSTICS_TIER_COMPACT,
            generatedAt = nowIsoString(),
            runtime = RuntimeInfo(
                kotlin = systemProperty("java.version") ?: "unknown",
                platform = systemProperty("os.name") ?: "unknown",
                machine = systemProperty("os.arch") ?: "unknown"
            ),
            storage = StorageInfo(
                runCount = runs.size,
                freeBytes = diskUsage.free,
                totalBytes = diskUsage.total
            ),
            model = ModelInfo(
                activeProfileId = modelSettings?.activeProfileId ?: "",
                profiles = profiles
            ),
            startup = startupReport,
            runs = runs,
            // 精简档只保留**事件统计**（类别 + 次数 + 最近样本），流水明细留给完整档：
            // 明细是「一次对话解析失败带一段完整模型输出」这种，体积可以到几十 MB。
            recentEvents = if (full) recentEvents else emptyList(),
            onDevice = onDevice,
            runtimeLog = runtimeLog,
            environment = environment,
            eventStats = eventStats,
            modelTests = modelTests,
            featureProbes = probes,
            processExitAttribution = ProcessExitAttributionDto(
                mappedReasonCodes = mappedExitReasonCodes(),
                mappedReasons = mappedExitReasonLines(),
                unmappedObservedCodes = unmappedExitReasonCodes(observedExitCodes),
                note = "归因优先取系统 description（OEM 后台清理 / 覆盖安装只在这里），" +
                    "其次按 reason 码；severity=info 表示正常退出，不计入崩溃数。",
            ),
            problems = problems,
            featureEvents = runCatching { FeatureEventAggregator.aggregate() }.getOrDefault(emptyList()),
            selfStatus = selfStatus,
            operationTrail = runCatching { operationTrail() }.getOrDefault(emptyList()),
            coverage = coverage,
            accessibility = accessibility,
            dataIntegrity = integrity,
            smoothness = smoothness,
        )
    }

    /** 全部进程退出记录（未截断、未脱敏前的原始列表）；只用于归因覆盖统计。 */
    private fun rawAllExits(): List<ProcessExitRecord> =
        runCatching { runtimeLogSource?.processExits() }.getOrNull().orEmpty()

    /**
     * 崩溃 / ANR 时同步冲刷下来的面包屑上下文（R2）。
     *
     * 这是面包屑能被用来排查**崩溃本身**的前提：内存环形缓冲随进程一起消失，
     * 只有死前落盘的那一份能留下来。
     */
    private fun crashContexts(): List<CrashContextRecord> =
        runCatching { runtimeLogSource?.crashContexts() }.getOrNull().orEmpty()

    /** 操作轨迹（R6）：导航历史 + 关键操作序列，与面包屑同源。 */
    private fun operationTrail(): List<OperationTrailEntryDto> =
        ClientDiagnostics.trailEntries().takeLast(MAX_OPERATION_TRAIL).map {
            OperationTrailEntryDto(
                at = it.at,
                kind = it.kind,
                label = it.label,
                detail = it.detail,
            )
        }

    /** 覆盖范围（R6）：进程模型 / WebView / 已覆盖与未覆盖的面。 */
    private fun coverageReport(): DiagnosticsCoverageDto {
        val snapshot = runCatching { runtimeLogSource?.processSnapshot() }.getOrNull()
        val sourceLabel = runtimeLogSource?.source ?: "无采集器"
        return top.wkbin.zaomeng.ktor.features.CoverageReportBuilder.build(snapshot, sourceLabel)
    }

    private fun auditAccessibilitySafely(): AccessibilityAuditDto =
        top.wkbin.zaomeng.ktor.features.auditAccessibility()

    /**
     * 「诊断自身状态」（R3）：丢事件 / 写入失败 / 采样开销 / 当前占用。
     *
     * 这一节的意义是**防止误判**：清单是空的，可能是「真的没问题」，
     * 也可能是「事件根本没记上」。两者在界面上必须能区分。
     */
    fun buildSelfStatus(probeDurationMs: Long = 0): DiagnosticsSelfStatusDto {
        val usage = runCatching { eventLog?.usage() }.getOrNull() ?: EventLogUsage()
        val retention = runCatching { runtimeLogSource?.retention() }.getOrNull()
        val sampling = runCatching { ClientDiagnostics.samplingCost() }.getOrNull()
        val droppedFromClient = runCatching { ClientDiagnostics.droppedCount() }.getOrDefault(0L)
        return DiagnosticsSelfStatusDto(
            droppedEvents = usage.dropped + droppedFromClient,
            writeFailures = usage.writeFailures,
            readFailures = usage.readFailures,
            samplingOverheadMs = (sampling?.overheadMs ?: 0L) + probeDurationMs,
            samplingSamples = (sampling?.samples ?: 0L) + if (probeDurationMs > 0) 1 else 0,
            samplingNote = sampling?.note.orEmpty().ifBlank {
                "采样开销只统计到「已上报自身成本」的采样器；帧 / 闪烁 / 可访问性若未上报，其成本不在此数字内。"
            },
            retention = DiagnosticsRetentionDto(
                eventEntries = usage.regularEntries,
                eventMaxEntries = usage.regularMaxEntries,
                eventBytes = usage.bytes,
                eventMaxBytes = usage.maxBytes,
                // 崩溃与常规事件**分别设限**：崩溃走独立文件配额（Android）或独立内存配额。
                crashEntries = (retention?.crashFiles ?: usage.crashEntries),
                crashMaxEntries = (retention?.crashMaxFiles ?: usage.crashMaxEntries),
                crashBytes = 0,
                crashMaxBytes = retention?.logMaxBytes ?: 0,
                breadcrumbEntries = runCatching { breadcrumbs?.size() }.getOrNull() ?: 0,
                breadcrumbMaxEntries = runCatching { breadcrumbs?.maxSize() }.getOrNull() ?: 0,
                featureEventKeys = runCatching { ClientDiagnostics.featureAggregates().size }.getOrDefault(0),
                operationTrailEntries = runCatching { ClientDiagnostics.trailEntries().size }.getOrDefault(0),
                operationTrailMaxEntries = MAX_OPERATION_TRAIL,
                trimmed = usage.trimmed,
                note = retention?.note.orEmpty().ifBlank {
                    if (retention == null) "当前平台无落盘保留策略（进程内缓冲 + 环形上限）。" else ""
                },
            ),
            note = "这一节回答的是「诊断自己有没有丢东西」。droppedEvents > 0 说明发生了裁剪，" +
                "清单里看不到的旧事件不是没发生，而是被上限挤掉了。" +
                if (usage.writeFailures > 0) " 当前有写入失败：事件可能没落盘。" else "",
        )
    }

    /**
     * 「运行诊断」概览：概览卡片 + 自检 + 可读问题清单 + 事件统计 + 模型连通性。
     *
     * 与完整 JSON 导出**同源同口径**（崩溃/退出记录/事件都取自同一批数据），
     * 只是不携带整段运行日志正文，便于用户一眼看到「到底出了什么问题」。
     */
    fun buildDiagnosticsOverview(runId: String = "", sessionId: String = ""): DiagnosticsOverviewDto {
        val environment = runCatching { environmentInfo(runId, sessionId) }
            .getOrElse { EnvironmentInfoDto(capturedAt = nowIsoString()) }
        val capture = runtimeCaptureState()
        val selfCheck = runCatching { buildStartupSelfCheck() }.getOrDefault(emptyMap())
        val events = runCatching { eventLog?.recent() ?: emptyList() }.getOrDefault(emptyList())
        val crashes = runCatching {
            runtimeLogSource?.snapshot(PROBLEM_SNAPSHOT_LINES)?.crashes.orEmpty()
        }.getOrDefault(emptyList()).let(LogRedactor::redactCrashes)
        val rawExits = runCatching { runtimeLogSource?.processExits().orEmpty() }
            .getOrDefault(emptyList())
            .take(MAX_REPORT_EXITS)
        val exits = rawExits.map { it.toDto() }.let(LogRedactor::redactProcessExits)
        val modelTests = runCatching { connectivityLog?.recent() ?: emptyList() }.getOrDefault(emptyList())
        val eventStats = runCatching { buildEventStats(events) }.getOrDefault(emptyList())
        val probes = runCatching { featureProbeReport?.invoke() }.getOrNull() ?: FeatureProbeReportDto()
        val problems = runCatching {
            buildProblems(crashes, rawExits, events, selfCheck, capture, crashContexts())
        }.getOrDefault(emptyList())
        val selfStatus = runCatching { buildSelfStatus(probes.durationMs) }
            .getOrDefault(DiagnosticsSelfStatusDto())
        val featureEvents = runCatching { FeatureEventAggregator.aggregate() }.getOrDefault(emptyList())
        val trail = runCatching { operationTrail() }.getOrDefault(emptyList())
        val coverage = runCatching { coverageReport() }.getOrDefault(DiagnosticsCoverageDto())
        val accessibility = runCatching { auditAccessibilitySafely() }.getOrDefault(AccessibilityAuditDto())
        val integrity = runCatching { dataIntegrityAudit?.invoke() }.getOrNull() ?: DataIntegrityAuditDto()
        val smoothness = runCatching { SmoothnessReportBuilder.build() }.getOrDefault(SmoothnessReportDto())

        return DiagnosticsOverviewDto(
            generatedAt = nowIsoString(),
            environment = environment,
            capture = capture,
            startup = selfCheck,
            problems = problems,
            eventStats = eventStats,
            modelTests = modelTests,
            copySummary = runCatching {
                buildCopySummary(environment, capture, selfCheck, problems, modelTests, probes, featureEvents)
            }.getOrDefault(""),
            featureProbes = probes,
            // 崩溃与「正常退出」分开计数：被系统/OEM 清理、被更新不该被当成崩溃恐吓用户。
            crashCount = problems.count {
                (it.kind == "crash" || it.kind == "process-exit" || it.kind == "anr") &&
                    (it.severity == "fatal" || it.severity == "error")
            },
            intentionalExitCount = rawExits.count {
                classifyExitReason(it.reasonCode, it.description).problemKind == ExitProblemKind.INTENTIONAL_EXIT
            },
            probeFailCount = probes.items.count { it.status == "FAIL" },
            // R2：分组后 problems.size 是**组数**，sum(count) 才是原始条数。
            // 两个数都给出来，因为「3 条崩溃」可能是同一个堆栈崩了 3 次。
            problemGroupCount = problems.size,
            problemTotalCount = problems.sumOf { it.count },
            featureEvents = featureEvents,
            selfStatus = selfStatus,
            operationTrail = trail,
            coverage = coverage,
            accessibility = accessibility,
            dataIntegrity = integrity,
            smoothness = smoothness,
        )
    }

    /**
     * 启动自检：把此前永远为 `{}` 的 `startup` 段真正填起来。
     *
     * 每一项都是「能不能用」的直接结论，值都截断成短文本，便于原样展示到诊断页。
     */
    fun buildStartupSelfCheck(): Map<String, String> {
        val checks = linkedMapOf<String, String>()
        checks["backend_graph"] = "ready（内嵌后端已装配并能响应本请求）"
        checks["data_root"] = storageRoot.toString()
        checks["data_root_writable"] = when (dataRootWritable?.invoke()) {
            true -> "ok"
            false -> "不可写（诊断与业务写入都会失败）"
            null -> "未探测"
        }
        pluginsRoot?.invoke()?.takeIf(String::isNotBlank)?.let { checks["plugins_root"] = it }
        val settings = runCatching { storageService.readModelSettings() }.getOrNull()
        val activeProfileId = settings?.activeProfileId.orEmpty()
        checks["model_active_profile"] = activeProfileId.ifBlank { "（未配置）" }
        val activeProfile = settings?.profiles?.firstOrNull { it.profileId == activeProfileId }
        // 从实际字段算，不读存储的 configured 标志位（保存路径从不写它，读了必然报 false）。
        // 详见 isProfileConfigured 的 KDoc。
        checks["model_configured"] = isProfileConfigured(activeProfile).toString()
        checks["model_api_key_configured"] = (
            activeProfile?.let { resolveApiKeyConfigured(it) } == true
            ).toString()
        val onDevice = runCatching { onDeviceSnapshot?.invoke().orEmpty() }.getOrDefault(emptyMap())
        checks["on_device_runtime"] = onDevice["state"]?.takeIf(String::isNotBlank)
            ?: onDevice["status"]?.takeIf(String::isNotBlank)
            ?: "未启用 / 当前平台不支持"
        checks["runtime_log_capture"] = if (runtimeCaptureState().enabled) "采集中" else "已停止（未采集）"
        checks["runtime_log_source"] = runtimeLogSource?.source ?: "无采集器"
        checks["last_process_exit"] = runCatching {
            val last = runtimeLogSource?.processExits(1)?.firstOrNull()
            if (last == null) {
                "无记录（或本机不支持 ApplicationExitInfo）"
            } else {
                // 带上可读归因与严重度：`UNKNOWN(16)` 这种显示会让人以为出了故障，
                // 而它其实只是「应用被覆盖安装」。
                val info = classifyExitReason(last.reasonCode, last.description)
                buildString {
                    append(info.reasonName)
                    append("（${info.reasonLabel}）")
                    append(" @ ${last.occurredAt}")
                    append(" · ${info.attribution}")
                    append(" · 严重度 ${info.severity.wireName()}")
                    if (!info.knownReason) append(" · 未映射原因码 ${last.reasonCode}")
                    if (last.description.isNotBlank()) append(" · ${last.description.take(120)}")
                }
            }
        }.getOrDefault("读取失败")
        val uptime = runCatching { processUptimeMillis?.invoke() ?: -1L }.getOrDefault(-1L)
        checks["process_uptime"] = if (uptime >= 0) formatDuration(uptime) else "未知"
        checks["app_version"] = buildEnvironmentInfo().app.let { "${it.versionName}（${it.versionCode}）" }
        // R6：关键文件可解析性——「这本书打不开了」的最常见原因是文件被写坏，
        // 而此前自检对文件本身是否完好一个字都没有。
        checks["key_files_parsable"] = runCatching {
            val audit = dataIntegrityAudit?.invoke()
            if (audit == null) {
                "未探测"
            } else {
                top.wkbin.zaomeng.ktor.features.DataIntegrityAudit.startupSelfCheckLine(audit)
            }
        }.getOrElse { "读取失败" }
        // R6：覆盖范围必须写在自检里，用户才知道「没看到进程退出记录」不等于「没崩过」。
        checks["coverage_scope"] = runCatching {
            val coverage = coverageReport()
            "${coverage.processModel}（覆盖 ${coverage.coveredSurfaces.size} 面，" +
                "未覆盖 ${coverage.uncoveredSurfaces.size} 面）"
        }.getOrElse { "未探测" }
        return checks
    }

    /** 结构化事件统计：按类别汇总 + 最近样本（样本已截断）。 */
    fun buildEventStats(events: List<DiagnosticsEvent>): List<DiagnosticsEventStatDto> =
        events.groupBy { it.category }
            .map { (category, group) ->
                DiagnosticsEventStatDto(
                    category = category,
                    count = group.size,
                    lastAt = group.lastOrNull()?.ts.orEmpty(),
                    lastMessage = group.lastOrNull()?.message.orEmpty().take(SAMPLE_LIMIT),
                )
            }
            .sortedWith(compareByDescending<DiagnosticsEventStatDto> { it.count }.thenBy { it.category })

    /**
     * 可读问题清单：崩溃 / ANR / 进程退出 / 结构化事件 / 自检。
     *
     * R2 起分两步：先把各来源摊平成 [RawDiagnosticsProblem]，再交给
     * [DiagnosticsProblemAggregator] 做**去重 + 分级 + 排序 + 面包屑**。
     * 这样「同堆栈归一组、显示次数与首末时间」的口径只有一处，
     * 不会出现「崩溃去重了、事件没去重」这种半吊子状态。
     *
     * 用户可以只把这一页截图或复制出去，不必再扔一个几十万字节的 JSON。
     */
    private fun buildProblems(
        crashes: List<CrashRecordDto>,
        exits: List<ProcessExitRecord>,
        events: List<DiagnosticsEvent>,
        selfCheck: Map<String, String>,
        capture: RuntimeCaptureStateDto,
        crashContexts: List<CrashContextRecord> = emptyList(),
    ): List<DiagnosticsProblemDto> {
        val raw = buildRawProblems(crashes, exits, events, selfCheck, capture, crashContexts)
        val timeline = runCatching { breadcrumbs?.timeline(events) }.getOrNull().orEmpty()
        return DiagnosticsProblemAggregator.aggregate(
            raw = raw,
            breadcrumbs = timeline,
            breadcrumbPicker = { all, occurredAt ->
                breadcrumbs?.before(all, occurredAt).orEmpty()
            },
        )
    }

    /** 把各来源摊平成同一种「原始问题」，供聚合器统一处理。 */
    private fun buildRawProblems(
        crashes: List<CrashRecordDto>,
        exits: List<ProcessExitRecord>,
        events: List<DiagnosticsEvent>,
        selfCheck: Map<String, String>,
        capture: RuntimeCaptureStateDto,
        crashContexts: List<CrashContextRecord> = emptyList(),
    ): List<RawDiagnosticsProblem> {
        val problems = mutableListOf<RawDiagnosticsProblem>()
        // 按文件名时间戳精确匹配：挂错问题的上下文比没有上下文更糟。
        val contextsByStamp = crashContexts.associateBy { it.stamp }
        val anrContexts = crashContexts.filter { it.source.startsWith("anr:") }
        fun contextFor(occurredAt: String, anr: Boolean): CrashContextRecord? {
            contextsByStamp[crashContextStampOf(occurredAt)]?.let { return it }
            // ANR 来自系统的 ApplicationExitInfo，时间戳与看门狗自有时间戳对不上；
            // 只有在「只有一份 ANR 上下文」时才兜底挂上，多份就宁可不挂。
            return if (anr && anrContexts.size == 1) anrContexts.first() else null
        }
        crashes.forEach { crash ->
            problems += RawDiagnosticsProblem(
                kind = DiagnosticsProblemTaxonomy.KIND_CRASH,
                severity = DiagnosticsProblemTaxonomy.SEVERITY_FATAL,
                occurredAt = crash.occurredAt,
                title = "未捕获异常：${crash.summary}（thread=${crash.thread}）",
                detail = crash.stackTrace,
                source = "uncaught-handler",
                // 分组用堆栈而不是摘要：同一条消息可能来自不同代码路径。
                signature = crash.stackTrace.ifBlank { crash.summary },
                // R2：把崩溃**之前**的现场挂上来（进程死前同步冲刷的那一份）。
                breadcrumbs = contextFor(crash.occurredAt, anr = false)?.breadcrumbs.orEmpty(),
            )
        }
        exits.forEach { exit ->
            val info = classifyExitReason(exit.reasonCode, exit.description)
            val isAnr = info.problemKind == ExitProblemKind.ANR
            val intentional = info.problemKind == ExitProblemKind.INTENTIONAL_EXIT
            // 被系统/OEM 清理、被更新、用户主动退出：**不算崩溃**，用 info 级别展示，
            // 且标题里直接给出可读归因与系统原始描述（用户报「应用自己关了」的真实原因就在这）。
            val severity = when (info.problemKind) {
                ExitProblemKind.CRASH, ExitProblemKind.CRASH_NATIVE -> DiagnosticsProblemTaxonomy.SEVERITY_FATAL
                ExitProblemKind.ANR -> DiagnosticsProblemTaxonomy.SEVERITY_ERROR
                ExitProblemKind.INTENTIONAL_EXIT -> DiagnosticsProblemTaxonomy.SEVERITY_INFO
                ExitProblemKind.PROCESS_EXIT ->
                    if (info.severity == ExitSeverity.WARN) {
                        DiagnosticsProblemTaxonomy.SEVERITY_WARNING
                    } else {
                        DiagnosticsProblemTaxonomy.SEVERITY_ERROR
                    }
            }
            problems += RawDiagnosticsProblem(
                kind = when {
                    isAnr -> DiagnosticsProblemTaxonomy.KIND_ANR
                    intentional -> DiagnosticsProblemTaxonomy.KIND_INTENTIONAL_EXIT
                    else -> DiagnosticsProblemTaxonomy.KIND_PROCESS_EXIT
                },
                severity = severity,
                occurredAt = exit.occurredAt,
                title = buildString {
                    append("上次进程退出：")
                    append(info.reasonName)
                    if (info.reasonLabel != info.reasonName) append("（${info.reasonLabel}）")
                    append(" · ${info.attribution}")
                    if (exit.status != 0) append("（status=${exit.status}）")
                    if (exit.description.isNotBlank()) append(" · ${exit.description}")
                    if (!info.knownReason) append(" · 该退出原因码尚未映射")
                },
                detail = buildString {
                    appendLine("归因：${info.attribution}（来自 ${if (info.attributionSource == ExitAttributionSource.DESCRIPTION) "系统描述" else "退出原因码"}）")
                    appendLine("严重度：$severity${if (info.isCrash) "（计入崩溃）" else "（不计入崩溃）"}")
                    if (exit.description.isNotBlank()) appendLine("系统描述：${exit.description}")
                    append(exit.trace.ifBlank { "（系统未提供轨迹；native 崩溃只有信号与退出原因，属降级信息）" })
                },
                source = exit.source,
                // 退出记录按「原因码」分组：同一次故障重启后仍是同一组。
                signature = "${info.reasonName}|${exit.trace.ifBlank { exit.description }}",
                // R2：ANR 的看门狗上下文（若有）。
                breadcrumbs = if (isAnr) contextFor(exit.occurredAt, anr = true)?.breadcrumbs.orEmpty() else emptyList(),
            )
        }
        events.forEach { event ->
            // 模型提供方的内容审核拒绝：**不是应用缺陷**，单独归到 provider-policy（info 级）。
            // 与「参数写错」类 4xx（同一条流式路径抛出的 inference request is invalid）在问题清单里
            // 一眼可分：那条仍是 `kind=event / warning`，这条是「提供方策略拒绝（非应用故障）」。
            // 注意**不**把它混进 KIND_EVENT：生命周期过滤只作用于 KIND_EVENT，
            // 而这条既不该被当成故障，也绝不能因此被丢掉。
            if (event.category == LlmClient.CONTENT_REJECTED_CATEGORY) {
                problems += RawDiagnosticsProblem(
                    kind = DiagnosticsProblemTaxonomy.KIND_PROVIDER_POLICY,
                    severity = DiagnosticsProblemTaxonomy.SEVERITY_INFO,
                    occurredAt = event.ts,
                    title = contentRejectedTitle(event),
                    detail = event.details.entries.joinToString("\n") { "${it.key}=${it.value}" },
                    source = "diagnostics-event-log",
                    signature = "${event.category}|${event.message}",
                )
                return@forEach
            }
            problems += RawDiagnosticsProblem(
                kind = DiagnosticsProblemTaxonomy.KIND_EVENT,
                severity = DiagnosticsProblemTaxonomy.SEVERITY_WARNING,
                occurredAt = event.ts,
                title = "${event.category}：${event.message}",
                detail = event.details.entries.joinToString("\n") { "${it.key}=${it.value}" },
                source = "diagnostics-event-log",
                signature = "${event.category}|${event.message}",
            )
        }
        selfCheck.filter { (key, value) ->
            value.contains("不可写") || value.contains("不支持") && key != "on_device_runtime" ||
                key == "runtime_log_capture" && !capture.enabled
        }.forEach { (key, value) ->
            problems += RawDiagnosticsProblem(
                kind = DiagnosticsProblemTaxonomy.KIND_SELF_CHECK,
                severity = DiagnosticsProblemTaxonomy.SEVERITY_WARNING,
                occurredAt = "",
                title = "自检未通过：$key = $value",
                detail = "",
                source = "startup-self-check",
                signature = key,
            )
        }
        return problems
    }

    /**
     * 提供方内容审核拒绝的问题标题。
     *
     * 用户要求标题里必须同时读出「**提供方拒绝**」与「**非应用故障**」两层意思，
     * 因此这里对事件消息做一次前缀兜底：事件消息本身已由
     * [ContentRejectedException] 写成同一句话，缺前缀时才补。
     */
    private fun contentRejectedTitle(event: DiagnosticsEvent): String {
        val prefix = "内容被模型提供方拒绝（非应用故障）"
        val message = event.message.trim().ifBlank { "上游以内容审核为由拒收了本轮请求。" }
        return if (message.startsWith(prefix)) message else "$prefix：$message"
    }


    /** 一键复制用的短摘要：可直接发给开发者，不含密钥、提示词与正文。 */
    private fun buildCopySummary(
        environment: EnvironmentInfoDto,
        capture: RuntimeCaptureStateDto,
        selfCheck: Map<String, String>,
        problems: List<DiagnosticsProblemDto>,
        modelTests: List<ModelConnectivityRecordDto>,
        probes: FeatureProbeReportDto,
        featureEvents: List<FeatureEventStatDto> = emptyList(),
    ): String = buildString {
        appendLine("【造梦诊断摘要】")
        appendLine("版本：${environment.app.versionName}（${environment.app.versionCode}）")
        val device = environment.device
        appendLine(
            "设备：" + listOf(
                device.manufacturer,
                device.model,
                "${device.osName} ${device.osRelease} (API ${device.apiLevel})",
                device.abis.firstOrNull().orEmpty(),
                if (device.is64Bit) "64位" else "32位",
                "CPU ${device.cpuCount} 核",
            ).filter(String::isNotBlank).joinToString(" · "),
        )
        val memory = environment.memory
        appendLine(
            "内存：堆 ${memory.heapUsedBytes / MIB}/${memory.heapMaxBytes / MIB} MiB" +
                " · native ${memory.nativeHeapBytes / MIB} MiB" +
                if (memory.systemAvailableBytes > 0) " · 系统可用 ${memory.systemAvailableBytes / MIB} MiB" else "",
        )
        appendLine("采集：${if (capture.enabled) "采集中" else "已停止"}" + if (capture.supported) "" else "（常开）")
        if (environment.session.runId.isNotBlank() || environment.session.sessionId.isNotBlank()) {
            appendLine("现场：run=${environment.session.runId.ifBlank { "-" }} session=${environment.session.sessionId.ifBlank { "-" }}")
        }
        appendLine("自检：${selfCheck.entries.take(12).joinToString("；") { "${it.key}=${it.value}" }}")
        if (modelTests.isNotEmpty()) {
            val last = modelTests.last()
            appendLine("模型最近一次测试：${if (last.success) "成功" else "失败"}（${last.latencyMs}ms，${last.provider}/${last.model}）${last.message.take(120)}")
        }
        // 功能自检：只给结论 + 数字（FAIL 项单独列出），细节留给 JSON 导出。
        appendLine(
            "功能自检：已注册 ${probes.registeredCount} 项探针" +
                "（✅${probes.okCount} ⚠️${probes.warnCount} ❌${probes.failCount}" +
                " 无数据${probes.noDataCount} 无样本${probes.skippedCount}）" +
                " · ${probes.unregisteredCount}/${probes.knownFeatureCount} 个功能未注册",
        )
        probes.items.filter { it.status == "FAIL" }.take(6).forEach { probe ->
            appendLine("- ❌ ${probe.title}：${probe.summary.take(120)}")
        }
        if (probes.unregisteredCount > 0) {
            appendLine("- 未注册功能：${probes.unregisteredFeatures.take(8).joinToString("、") { it.title }}")
        }
        // R4：通用功能事件——即使某个功能没写任何诊断代码，只要走过统一入口就会在这里出现。
        if (featureEvents.isNotEmpty()) {
            val failing = featureEvents.filter { it.failedCount > 0 }
            appendLine(
                "功能事件：${featureEvents.size} 组" +
                    if (failing.isEmpty()) "（无失败）" else "，其中 ${failing.size} 组有失败",
            )
            failing.take(5).forEach { stat ->
                appendLine("- ❌ ${stat.feature}/${stat.action}：失败 ${stat.failedCount}/${stat.total}（最近 ${stat.lastAt}）${stat.lastDetail.take(80)}")
            }
        }
        appendLine("问题共 ${problems.size} 组（累计 ${problems.sumOf { it.count }} 次，最近 8 组）：")
        problems.take(8).forEach { problem ->
            appendLine(
                "- [${problem.lastAt.ifBlank { problem.occurredAt }}] ${problem.kind}/${problem.severity}" +
                    " ×${problem.count}：${problem.title.take(160)}",
            )
        }
        if (problems.isEmpty()) appendLine("- （无）")
        appendLine("完整诊断请在「运行诊断」页导出 JSON（精简 = 概览与结论；完整 = 含日志与完整堆栈）。")
    }.take(SUMMARY_LIMIT)

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * 版本与现场元信息（应用版本、机型/ABI、内存、当前 run/session）。
     *
     * 存在的理由：此前诊断里**没有 appVersion**，只能靠日志反推用户版本。
     */
    fun environmentInfo(runId: String = "", sessionId: String = ""): EnvironmentInfoDto =
        buildEnvironmentInfo().copy(
            session = SessionContextDto(
                runId = runId.trim().take(CONTEXT_ID_LIMIT),
                sessionId = sessionId.trim().take(CONTEXT_ID_LIMIT),
                // 明确来源：这是客户端「最近打开的会话」，不是「导出这一刻正在生成的会话」
                reportedBy = if (runId.isBlank() && sessionId.isBlank()) "" else "client-last-opened-session",
            ),
        )

    /** 当前采集开关状态（页面顶部展示「采集中/已停止」）。 */
    fun runtimeCaptureState(): RuntimeCaptureStateDto {
        val source = runtimeLogSource
            ?: return RuntimeCaptureStateDto(
                supported = false,
                enabled = true,
                note = "当前平台没有运行日志采集器，无法启停采集。",
            )
        return runCatching { source.captureState() }
            .getOrElse { RuntimeCaptureState(supported = false, enabled = true, note = "读取采集状态失败。") }
            .toDto()
    }

    /** 设置采集开关；实现必须幂等（重复/并发点击不会重复启停或写坏日志文件）。 */
    fun setRuntimeLogCapture(enabled: Boolean): RuntimeCaptureStateDto {
        val source = runtimeLogSource
            ?: return RuntimeCaptureStateDto(
                supported = false,
                enabled = true,
                note = "当前平台没有运行日志采集器，无法启停采集。",
            )
        return runCatching { source.setCaptureEnabled(enabled) }
            .getOrElse { source.captureState() }
            .toDto()
    }

    /**
     * 清除已采集的运行日志与崩溃/卡顿记录。
     *
     * 只动运行日志相关文件，绝不触碰书卷、会话、记忆、模型档案等业务数据。
     */
    fun clearRuntimeLog(): RuntimeLogClearResultDto {
        val capturedAt = nowIsoString()
        val source = runtimeLogSource
            ?: return RuntimeLogClearResultDto(
                cleared = false,
                supported = false,
                capturedAt = capturedAt,
                note = "当前平台没有运行日志采集器，无法清除。",
            )
        return runCatching { source.clearCaptured() }.fold(
            onSuccess = { result ->
                RuntimeLogClearResultDto(
                    cleared = true,
                    supported = result.supported,
                    clearedLines = result.clearedLines,
                    clearedCrashes = result.clearedCrashes,
                    captureEnabled = result.enabled,
                    capturedAt = capturedAt,
                    note = result.note,
                )
            },
            onFailure = { error ->
                RuntimeLogClearResultDto(
                    cleared = false,
                    supported = true,
                    captureEnabled = runCatching { source.captureState().enabled }.getOrDefault(true),
                    capturedAt = capturedAt,
                    note = "清除运行日志失败：${error.message.orEmpty().take(200)}",
                )
            },
        )
    }

    private fun RuntimeCaptureState.toDto(): RuntimeCaptureStateDto = RuntimeCaptureStateDto(
        supported = supported,
        enabled = enabled,
        updatedAt = updatedAt,
        note = note,
    )

    /** 平台层的进程退出记录 → 契约 DTO（脱敏在调用处统一做）。 */
    private fun ProcessExitRecord.toDto(): ProcessExitInfoDto {
        // 归类：补全 reason 映射（16 = 应用更新，此前被显示成 UNKNOWN(16)）；
        // description 里的 OEM 后台清理（o-stop）与覆盖安装升级为可读归因；
        // 严重度区分「正常退出」与「崩溃」，避免用户被一堆正常退出吓到。
        val info = classifyExitReason(reasonCode, description)
        return ProcessExitInfoDto(
            occurredAt = occurredAt,
            reason = info.reasonName,
            reasonCode = reasonCode,
            status = status,
            importance = importance,
            pssBytes = pssBytes,
            rssBytes = rssBytes,
            description = description,
            trace = trace,
            source = source,
            reasonLabel = info.reasonLabel,
            reasonMapped = info.knownReason,
            severity = info.severity.wireName(),
            attribution = info.attribution,
            attributionSource = when (info.attributionSource) {
                ExitAttributionSource.DESCRIPTION -> "description"
                ExitAttributionSource.REASON -> "reason"
            },
            problemKind = when (info.problemKind) {
                ExitProblemKind.CRASH -> "crash"
                ExitProblemKind.CRASH_NATIVE -> "crash-native"
                ExitProblemKind.ANR -> "anr"
                ExitProblemKind.PROCESS_EXIT -> "process-exit"
                ExitProblemKind.INTENTIONAL_EXIT -> "intentional-exit"
            },
            isCrash = info.isCrash,
        )
    }

    /**
     * 构建运行日志快照（脱敏 + 夹紧行数 + 补齐现场信息）。
     *
     * 诊断报告内嵌与「运行日志」页单独拉取共用这一个入口，保证两处口径一致：
     * 同样的来源标识、同样的密钥脱敏、同样的行数上限、同样的版本/现场元信息与进程退出记录。
     *
     * @param maxLines 期望行数，夹紧到 [MAX_STANDALONE_LOG_LINES]。
     */
    fun buildRuntimeLogSnapshot(
        maxLines: Int = MAX_REPORT_LOG_LINES,
        runId: String = "",
        sessionId: String = "",
    ): RuntimeLogDto {
        val bounded = maxLines.coerceIn(1, MAX_STANDALONE_LOG_LINES)
        val capture = runtimeCaptureState()
        val environment = runCatching { environmentInfo(runId, sessionId) }
            .getOrElse { EnvironmentInfoDto(capturedAt = nowIsoString()) }
        val source = runtimeLogSource
            ?: return RuntimeLogDto(
                available = false,
                capturedAt = nowIsoString(),
                note = "当前平台没有运行日志采集器。",
                capture = capture,
                environment = environment,
            )
        val snapshot = runCatching { source.snapshot(bounded) }
            .getOrElse {
                RuntimeLogDto(
                    source = source.source,
                    available = false,
                    capturedAt = nowIsoString(),
                    note = "运行日志采集失败。",
                )
            }
        val exits = runCatching { source.processExits() }.getOrDefault(emptyList())
        return snapshot.copy(
            lines = LogRedactor.redactLines(snapshot.lines.take(bounded)),
            crashes = LogRedactor.redactCrashes(snapshot.crashes.take(MAX_REPORT_CRASHES)),
            capture = capture,
            environment = environment,
            // 第 2 层补捕：进程退出记录同样过脱敏（轨迹里可能带回请求 URL 等凭据）
            processExits = LogRedactor.redactProcessExits(exits.take(MAX_REPORT_EXITS).map { it.toDto() }),
            processExitsNote = source.processExitsNote(),
        )
    }

    /**
     * 获取磁盘使用信息
     */
    private fun getDiskUsage(): DiskUsage {
        return try {
            val info = diskSpaceOf(storageRoot)
                ?: return DiskUsage(free = 0, total = 0)
            DiskUsage(free = info.freeBytes, total = info.totalBytes)
        } catch (e: Exception) {
            DiskUsage(free = 0, total = 0)
        }
    }

    /**
     * 构建运行摘要列表
     */
    private fun buildRunsSummary(): List<RunSummary> {
        val runIds = storageService.listRunIds()
        return runIds.mapNotNull { runId ->
            try {
                val manifest = storageService.readRunManifest(runId)
                if (manifest == null) {
                    RunSummary(
                        runId = runId,
                        status = "invalid_manifest",
                        stage = "",
                        characterCount = 0,
                        errorType = "",
                        errorMessage = "manifest 缺失或无法解析",
                        updatedAt = ""
                    )
                } else {
                    val error = manifest["error"] as? JsonObject
                    val artifactIndex = manifest["artifact_index"] as? JsonObject
                    val characterCount = (artifactIndex?.get("characters") as? JsonArray)?.size ?: 0
                    val progress = manifest["progress"] as? JsonObject

                    RunSummary(
                        runId = manifest.string("run_id").ifBlank { runId },
                        status = manifest.string("status"),
                        stage = progress?.string("stage").orEmpty(),
                        characterCount = characterCount,
                        errorType = error?.string("type").orEmpty(),
                        // 失败原因优先取结构化 error.message，回退蒸馏写入的 progress.error。
                        errorMessage = error?.string("message").orEmpty()
                            .ifBlank { progress?.string("error").orEmpty() },
                        updatedAt = manifest.string("updated_at")
                    )
                }
            } catch (e: Exception) {
                RunSummary(
                    runId = runId,
                    status = "error",
                    stage = "",
                    characterCount = 0,
                    errorType = e::class.simpleName ?: "unknown",
                    errorMessage = e.message.orEmpty(),
                    updatedAt = ""
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /**
     * 构建配置文件摘要
     */
    private fun buildProfilesSummary(modelSettings: ModelSettings?): List<ProfileSummary> {
        val profiles = modelSettings?.profiles ?: emptyList()
        return profiles.map { profile ->
            ProfileSummary(
                profileId = profile.profileId ?: "",
                provider = profile.provider ?: "",
                model = profile.model ?: "",
                baseUrl = safeEndpoint(profile.baseUrl ?: ""),
                apiKeyConfigured = resolveApiKeyConfigured(profile),
                // 同上：按实际字段算，不用存储里那个永远为 false 的标志位。
                configured = isProfileConfigured(profile),
                // 下面是**决定请求体形状**的三个开关。缺了它们，拿到一份导出也判断不出
                // 上游为什么拒收：`max_tokens` 与 `max_completion_tokens` 二选一发错、
                // `reasoning_effort` 超出该模型允许值、`response_format` 被下发 —— 三种
                // 都会得到 400，而此前导出里一个字段都看不到。
                // 三者都不是密钥，可以安全导出（与 SettingsManagementService.putProfileFields 同口径）。
                reasoningEffort = profile.reasoningEffort ?: "off",
                tokenParameter = profile.tokenParameter ?: "auto",
                responseFormatMode = profile.responseFormatMode ?: "auto",
            )
        }
    }

    /**
     * 该 profile 是否配置了 API Key。
     *
     * ModelProfile.apiKeyConfigured 本应由保存模型设置时写入，但保存路径从不填它，
     * 于是诊断报告永远报 false：用户明明配好 Key，导出里却显示"未配置"，排查会走错方向。
     * 这里以安全存储的实际结果为准（见构造参数 apiKeyConfiguredOf），查不到再回落该字段。
     */
    private fun resolveApiKeyConfigured(profile: ModelProfile): Boolean {
        val profileId = profile.profileId?.trim().orEmpty()
        if (profileId.isNotEmpty()) {
            apiKeyConfiguredOf?.invoke(profileId)?.let { return it }
        }
        return profile.apiKeyConfigured == true
    }

    /**
     * 该档案是否**可用**（provider / model 齐备，且需要 Key 时确实有 Key）。
     *
     * 与 [resolveApiKeyConfigured] 同一思路：**不读存储的 `configured` 标志位，而是从实际字段算**。
     *
     * 原因（与 apiKeyConfigured 那次的 bug 同类，但漏改了这一个）：
     * `ModelProfile.configured` 本应由保存路径写入，但 `SettingsManagementService` 保存时
     * 构造 `ModelProfile(...)` **从不传它**（见 `putProfileFields` 里那份**只在返回给客户端的
     * JSON 里**现算的同名表达式），因此存储字段恒为默认值 → 诊断里
     * `model_configured` 与每条 `model.profiles[].configured` **永远是 false**。
     * 用户真机导出实测：三个档案 provider/model/apiKey 齐全，却全部 `configured=false`。
     * 后果与 apiKey 那次一样——「用户明明配好了，导出说没配」，排查会被带偏。
     *
     * 口径必须与 [top.wkbin.zaomeng.ktor.services.SettingsManagementService] 的
     * `putProfileFields` 保持一致，否则界面与诊断还会各说各话。
     */
    private fun isProfileConfigured(profile: ModelProfile?): Boolean {
        if (profile == null) return false
        if (profile.provider.isNullOrBlank() || profile.model.isNullOrBlank()) return false
        val keyRequired = profile.apiKeyRequired != false
        return resolveApiKeyConfigured(profile) || !keyRequired
    }

    /**
     * 加载启动报告
     */
    private fun loadStartupReport(): Map<String, JsonElement> {
        val startupFile = storageRoot / "android_startup_report.json"
        val content = storageService.readTextOrNull(startupFile) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, JsonElement>>(content)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 安全地格式化端点 URL（移除查询参数和敏感信息）
     */
    private fun safeEndpoint(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""

        return try {
            val match = ENDPOINT_REGEX.matchEntire(text) ?: return "invalid"
            val scheme = match.groupValues[1]
            val host = match.groupValues[2]
            if (host.isEmpty()) return "invalid"
            val port = match.groupValues[3]
            val path = match.groupValues[4]
            buildString {
                append(scheme).append("://").append(host)
                if (port.isNotEmpty()) append(":").append(port)
                append(path)
            }
        } catch (e: Exception) {
            "invalid"
        }
    }

    /**
     * 获取当前 UTC 时间字符串
     */
    private fun utcNow(): String = nowIsoString()

    /**
     * 磁盘使用信息
     */
    private data class DiskUsage(val free: Long, val total: Long)

    private companion object {
        // scheme://host[:port]/path（去掉 query/fragment）
        val ENDPOINT_REGEX = Regex("^(https?)://([^/:?#]+)(?::(\\d+))?([^?#]*)")

        /** 诊断报告内嵌的日志行数上限：报告要能直接发给排查者，不能无界膨胀。 */
        const val MAX_REPORT_LOG_LINES = 1_200

        /** 「运行日志」页单独拉取时的行数上限。 */
        const val MAX_STANDALONE_LOG_LINES = 5_000

        /** 报告内嵌的崩溃记录条数上限。 */
        const val MAX_REPORT_CRASHES = 5

        /** 报告内嵌的进程退出记录条数上限（ApplicationExitInfo，含有限轨迹）。 */
        const val MAX_REPORT_EXITS = 5

        /** 客户端上报的 run/session 上下文长度上限。 */
        const val CONTEXT_ID_LIMIT = 120

        /** 概览页取崩溃记录时的最小快照（只需要崩溃段，不需要正文）。 */
        const val PROBLEM_SNAPSHOT_LINES = 1

        /** 事件统计里「最近样本」的长度上限。 */
        const val SAMPLE_LIMIT = 200

        /** 一键复制摘要的长度上限：够说清问题，又不至于变成一份报告。 */
        const val SUMMARY_LIMIT = 2_000

        /** 导出的操作轨迹条数上限（R6）：最近这些足够看清「问题发生前用户做了什么」。 */
        const val MAX_OPERATION_TRAIL = 120

        const val MIB = 1024L * 1024L
    }
}

/** 精简导出（默认）：概览 + 问题清单摘要 + 探针结论，不含日志正文与完整堆栈。 */
const val DIAGNOSTICS_TIER_COMPACT = "compact"

/** 完整导出：额外包含运行日志、完整堆栈、事件明细与完整载荷。体积可能很大。 */
const val DIAGNOSTICS_TIER_FULL = "full"

/**
 * 诊断报告
 */
@Serializable
data class DiagnosticsReport(
    val kind: String,
    val schemaVersion: Int,
    val generatedAt: String,
    val runtime: RuntimeInfo,
    val storage: StorageInfo,
    val model: ModelInfo,
    val startup: Map<String, JsonElement>,
    val runs: List<RunSummary>,
    /** 关键事件（对话解析失败/插件动作/端侧推理状态变迁），倒序无关、按时间正序。 */
    val recentEvents: List<DiagnosticsEvent> = emptyList(),
    /** 端侧推理快照：状态、模型、端口、llama-server 日志尾部（无端侧时为空）。 */
    val onDevice: Map<String, String> = emptyMap(),
    /**
     * 整个应用的运行时日志尾部（已脱敏）。
     *
     * Android 来自本进程 logcat 的持续采集，覆盖 native（llama.cpp）、第三方库
     * （OkHttp/Compose）与系统崩溃日志；JVM 来自进程内缓冲。schemaVersion 3 起加入；
     * 4 起同时带采集开关状态、版本/现场元信息与进程退出记录（ApplicationExitInfo）。
     */
    val runtimeLog: RuntimeLogDto = RuntimeLogDto(),
    /**
     * 版本与现场元信息（appVersion / 机型 / ABI / 内存 / 当前 run 与 session）。
     *
     * schemaVersion 4 起加入：此前诊断里没有 appVersion，定位用户版本只能靠日志反推。
     */
    val environment: EnvironmentInfoDto = EnvironmentInfoDto(),
    /**
     * 结构化事件统计（类别 + 次数 + 最近样本）。
     *
     * schemaVersion 4 起加入：原来只有流水（recentEvents），看不出「哪类失败反复发生」。
     */
    val eventStats: List<DiagnosticsEventStatDto> = emptyList(),
    /** 最近的模型连通性测试结果（服务端实际执行过的「测试连接」）。schemaVersion 4 起加入。 */
    val modelTests: List<ModelConnectivityRecordDto> = emptyList(),
    /**
     * 功能自检（探针）报告。
     *
     * schemaVersion 5 起加入：每个功能自己注册检查项，导出里 **自动包含所有已注册探针**，
     * 同时列出「未注册的功能」——新增功能忘了注册时，界面上会直接暴露。
     * 与运行日志采集开关无关（探针读清单/档案与独立事件缓冲）。
     */
    val featureProbes: FeatureProbeReportDto = FeatureProbeReportDto(),
    /**
     * 进程退出原因映射的覆盖情况。
     *
     * schemaVersion 5 起加入：此前只映射 API 30 就存在的常量，16（应用覆盖安装）
     * 会被显示成 `UNKNOWN(16)`；这一段给出已映射清单与本次实测未映射的原始码。
     */
    val processExitAttribution: ProcessExitAttributionDto = ProcessExitAttributionDto(),
    /**
     * 导出分级：`compact` / `full`。schemaVersion 6 起加入。
     *
     * 报告自己声明自己是哪一档——否则拿到一份「没有日志」的导出时，
     * 读者无法区分「日志为空」和「这是精简档，日志被有意省略」。
     */
    val tier: String = DIAGNOSTICS_TIER_COMPACT,
    /**
     * 去重后的可读问题清单（R2）。
     *
     * schemaVersion 6 起加入：与概览同源同口径。每条带 `count` / `firstAt` / `lastAt`
     * 与发生之前的面包屑；默认排序为
     * 崩溃 > ANR > 卡死/长任务 > 卡顿 > 视觉信号 > 结构化事件 > 提示，
     * 正常退出（被更新 / 被清理）权重为 0，排在最后。
     */
    val problems: List<DiagnosticsProblemDto> = emptyList(),
    /** 通用功能事件统计（R4）：走统一入口的操作自动出现在这里，不需要新功能写诊断代码。 */
    val featureEvents: List<FeatureEventStatDto> = emptyList(),
    /** 诊断自身状态（R3）：丢事件 / 写入失败 / 采样开销 / 当前占用。 */
    val selfStatus: DiagnosticsSelfStatusDto = DiagnosticsSelfStatusDto(),
    /** 操作轨迹（R6）：导航历史 + 关键操作序列。 */
    val operationTrail: List<OperationTrailEntryDto> = emptyList(),
    /** 覆盖范围（R6）：单进程/多进程、是否有 WebView、哪些面没覆盖。 */
    val coverage: DiagnosticsCoverageDto = DiagnosticsCoverageDto(),
    /** 可访问性审计（R6）。 */
    val accessibility: AccessibilityAuditDto = AccessibilityAuditDto(),
    /** 数据损坏审计（R6）。 */
    val dataIntegrity: DataIntegrityAuditDto = DataIntegrityAuditDto(),
    /** 画面与流畅度（R7）。 */
    val smoothness: SmoothnessReportDto = SmoothnessReportDto(),
)

@Serializable
data class RuntimeInfo(
    val kotlin: String,
    val platform: String,
    val machine: String
)

@Serializable
data class StorageInfo(
    val runCount: Int,
    val freeBytes: Long,
    val totalBytes: Long
)

@Serializable
data class ModelInfo(
    val activeProfileId: String,
    val profiles: List<ProfileSummary>
)

@Serializable
data class ProfileSummary(
    val profileId: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val apiKeyConfigured: Boolean,
    val configured: Boolean,
    /**
     * 思考力度设置（`off` / `auto` / `low` / `medium` / `high` / `max` / `xhigh` …）。
     *
     * 实测值会被 `LlmClient.resolveReasoningParams` 按**逐模型白名单**转换成线上值
     * （例如 `off` → `reasoning_effort=none`），转换后不再出现在导出里——
     * 因此这里给的是**设置值**，线上实际值见 4xx 留证事件 `llm.http_error_payload`。
     */
    val reasoningEffort: String = "",
    /** `max_tokens` / `max_completion_tokens` / `auto`（`auto` 由模型 id 推导）。 */
    val tokenParameter: String = "",
    /** `auto` / `json_object` / `prompt_only`。 */
    val responseFormatMode: String = "",
)

@Serializable
data class RunSummary(
    val runId: String,
    val status: String,
    val stage: String,
    val characterCount: Int,
    val errorType: String,
    /**
     * 失败原因（人可读）。
     *
     * 蒸馏失败把原因写在 `progress.error`（见 DistillExecutor.fail），而不是顶层
     * `error.type`；只读后者会让诊断导出里所有失败 run 的原因都是空的，
     * 排查时只能看到「失败了」而看不到「为什么」。
     */
    val errorMessage: String,
    val updatedAt: String
)
