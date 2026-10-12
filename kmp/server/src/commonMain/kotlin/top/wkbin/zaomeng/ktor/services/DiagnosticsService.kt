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
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.RuntimeLogSource
import top.wkbin.zaomeng.platform.diskSpaceOf
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
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 构建诊断报告
     */
    fun buildDiagnosticsReport(): DiagnosticsReport {
        val modelSettings = runCatching { storageService.readModelSettings() }.getOrNull()
        val diskUsage = getDiskUsage()
        val runs = runCatching { buildRunsSummary() }.getOrDefault(emptyList())
        val profiles = runCatching { buildProfilesSummary(modelSettings) }.getOrDefault(emptyList())
        val startupReport = runCatching { loadStartupReport() }.getOrDefault(emptyMap())
        val recentEvents = runCatching { eventLog?.recent() ?: emptyList() }.getOrDefault(emptyList())
        val onDevice = runCatching { onDeviceSnapshot?.invoke().orEmpty() }.getOrDefault(emptyMap())
        val runtimeLog = runCatching { buildRuntimeLogSnapshot(MAX_REPORT_LOG_LINES) }
            .getOrElse { RuntimeLogDto(capturedAt = nowIsoString(), note = "运行日志采集失败，已跳过该节。") }

        return DiagnosticsReport(
            kind = "zaomeng_diagnostics",
            schemaVersion = 3,
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
            runtimeLog = runtimeLog
        )
    }

    /**
     * 构建运行日志快照（脱敏 + 夹紧行数）。
     *
     * 诊断报告内嵌与「运行日志」页单独拉取共用这一个入口，保证两处口径一致：
     * 同样的来源标识、同样的密钥脱敏、同样的行数上限。
     *
     * @param maxLines 期望行数，夹紧到 [MAX_STANDALONE_LOG_LINES]。
     */
    fun buildRuntimeLogSnapshot(maxLines: Int = MAX_REPORT_LOG_LINES): RuntimeLogDto {
        val bounded = maxLines.coerceIn(1, MAX_STANDALONE_LOG_LINES)
        val source = runtimeLogSource
            ?: return RuntimeLogDto(
                available = false,
                capturedAt = nowIsoString(),
                note = "当前平台没有运行日志采集器。",
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
        return snapshot.copy(
            lines = LogRedactor.redactLines(snapshot.lines.take(bounded)),
            crashes = LogRedactor.redactCrashes(snapshot.crashes.take(MAX_REPORT_CRASHES)),
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
     * （OkHttp/Compose）与系统崩溃日志；JVM 来自进程内缓冲。schemaVersion 3 起加入。
     */
    val runtimeLog: RuntimeLogDto = RuntimeLogDto(),
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
