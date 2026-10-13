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
import top.wkbin.zaomeng.data.api.ModelConnectivityRecordDto
import top.wkbin.zaomeng.data.api.ProcessExitInfoDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.data.api.SessionContextDto
import top.wkbin.zaomeng.platform.ProcessExitRecord
import top.wkbin.zaomeng.platform.RuntimeCaptureState
import top.wkbin.zaomeng.platform.RuntimeLogSource
import top.wkbin.zaomeng.platform.buildEnvironmentInfo
import top.wkbin.zaomeng.platform.diskSpaceOf
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
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 构建诊断报告
     *
     * @param runId / @param sessionId 客户端从「最近打开的会话」带上的现场上下文；
     *   服务端不知道 UI 状态，因此这两个值是可选的，且会在报告里标注来源。
     */
    fun buildDiagnosticsReport(runId: String = "", sessionId: String = ""): DiagnosticsReport {
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
        val runtimeLog = runCatching { buildRuntimeLogSnapshot(MAX_REPORT_LOG_LINES, runId, sessionId) }
            .getOrElse { RuntimeLogDto(capturedAt = nowIsoString(), note = "运行日志采集失败，已跳过该节。") }
        val environment = runCatching { environmentInfo(runId, sessionId) }
            .getOrElse { EnvironmentInfoDto(capturedAt = nowIsoString()) }

        return DiagnosticsReport(
            kind = "zaomeng_diagnostics",
            schemaVersion = 4,
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
            recentEvents = recentEvents,
            onDevice = onDevice,
            runtimeLog = runtimeLog,
            environment = environment,
            eventStats = eventStats,
            modelTests = modelTests,
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
        val problems = runCatching {
            buildProblems(crashes, rawExits, events, selfCheck, capture)
        }.getOrDefault(emptyList())

        return DiagnosticsOverviewDto(
            generatedAt = nowIsoString(),
            environment = environment,
            capture = capture,
            startup = selfCheck,
            problems = problems,
            eventStats = eventStats,
            modelTests = modelTests,
            copySummary = runCatching {
                buildCopySummary(environment, capture, selfCheck, problems, modelTests)
            }.getOrDefault(""),
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
        checks["model_configured"] = (activeProfile?.configured == true).toString()
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
            if (last == null) "无记录（或本机不支持 ApplicationExitInfo）"
            else "${last.reason} @ ${last.occurredAt}"
        }.getOrDefault("读取失败")
        val uptime = runCatching { processUptimeMillis?.invoke() ?: -1L }.getOrDefault(-1L)
        checks["process_uptime"] = if (uptime >= 0) formatDuration(uptime) else "未知"
        checks["app_version"] = buildEnvironmentInfo().app.let { "${it.versionName}（${it.versionCode}）" }
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
     * 可读问题清单：崩溃 / ANR / 进程退出 / 结构化事件 / 自检，按时间倒序混排。
     *
     * 用户可以只把这一页截图或复制出去，不必再扔一个几十万字节的 JSON。
     */
    private fun buildProblems(
        crashes: List<CrashRecordDto>,
        exits: List<ProcessExitRecord>,
        events: List<DiagnosticsEvent>,
        selfCheck: Map<String, String>,
        capture: RuntimeCaptureStateDto,
    ): List<DiagnosticsProblemDto> {
        val problems = mutableListOf<DiagnosticsProblemDto>()
        crashes.forEach { crash ->
            problems += DiagnosticsProblemDto(
                kind = "crash",
                severity = "fatal",
                occurredAt = crash.occurredAt,
                title = "未捕获异常：${crash.summary}（thread=${crash.thread}）",
                detail = crash.stackTrace,
                source = "uncaught-handler",
            )
        }
        exits.forEach { exit ->
            val isAnr = exit.reason.contains("ANR", ignoreCase = true)
            problems += DiagnosticsProblemDto(
                kind = if (isAnr) "anr" else "process-exit",
                severity = if (isAnr || exit.reason.contains("CRASH", ignoreCase = true)) "error" else "warning",
                occurredAt = exit.occurredAt,
                title = "上次进程退出：${exit.reason}（status=${exit.status}）" +
                    exit.description.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                detail = exit.trace.ifBlank { "（系统未提供轨迹；native 崩溃只有信号与退出原因，属降级信息）" },
                source = exit.source,
            )
        }
        events.forEach { event ->
            problems += DiagnosticsProblemDto(
                kind = "event",
                severity = "warning",
                occurredAt = event.ts,
                title = "${event.category}：${event.message}",
                detail = event.details.entries.joinToString("\n") { "${it.key}=${it.value}" },
                source = "diagnostics-event-log",
            )
        }
        selfCheck.filter { (key, value) ->
            value.contains("不可写") || value.contains("不支持") && key != "on_device_runtime" ||
                key == "runtime_log_capture" && !capture.enabled
        }.forEach { (key, value) ->
            problems += DiagnosticsProblemDto(
                kind = "self-check",
                severity = "warning",
                occurredAt = "",
                title = "自检未通过：$key = $value",
                detail = "",
                source = "startup-self-check",
            )
        }
        // 时间倒序：新问题在最上面；没有时间的（自检项）排最后
        return problems.sortedByDescending { it.occurredAt }
    }

    /** 一键复制用的短摘要：可直接发给开发者，不含密钥、提示词与正文。 */
    private fun buildCopySummary(
        environment: EnvironmentInfoDto,
        capture: RuntimeCaptureStateDto,
        selfCheck: Map<String, String>,
        problems: List<DiagnosticsProblemDto>,
        modelTests: List<ModelConnectivityRecordDto>,
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
        appendLine("问题共 ${problems.size} 条（最近 8 条）：")
        problems.take(8).forEach { problem ->
            appendLine("- [${problem.occurredAt}] ${problem.kind}/${problem.severity}：${problem.title.take(160)}")
        }
        if (problems.isEmpty()) appendLine("- （无）")
        appendLine("完整诊断请在「运行诊断」页导出 JSON。")
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
    private fun ProcessExitRecord.toDto(): ProcessExitInfoDto =
        ProcessExitInfoDto(
            occurredAt = occurredAt,
            reason = reason,
            reasonCode = reasonCode,
            status = status,
            importance = importance,
            pssBytes = pssBytes,
            rssBytes = rssBytes,
            description = description,
            trace = trace,
            source = source,
        )

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
                configured = profile.configured ?: false
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

        const val MIB = 1024L * 1024L
    }
}

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
    val configured: Boolean
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
