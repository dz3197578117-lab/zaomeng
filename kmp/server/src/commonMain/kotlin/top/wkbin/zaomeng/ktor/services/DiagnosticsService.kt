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
import top.wkbin.zaomeng.ktor.models.ModelSettings
import top.wkbin.zaomeng.platform.diskSpaceOf
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.systemProperty

/**
 * 诊断服务
 *
 * 生成系统诊断报告，对应 Python 的 DiagnosticsServiceMixin。
 *
 * 导出内容是自包含的：既有环境/存储/模型配置摘要，也有 [DiagnosticsEventLog] 里
 * 记录的关键事件（对话解析失败含模型输出摘录、插件动作失败、端侧推理状态变迁），
 * 以及端侧 llama-server 的日志尾部——排查时不需要再去掏 logcat。
 *
 * 每一节都独立容错：单节失败只降级为空，绝不让整个导出 500。
 */
class DiagnosticsService(
    private val storageRoot: Path,
    private val storageService: StorageService,
    private val eventLog: DiagnosticsEventLog? = null,
    private val onDeviceSnapshot: (() -> Map<String, String>)? = null,
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

        return DiagnosticsReport(
            kind = "zaomeng_diagnostics",
            schemaVersion = 2,
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
            onDevice = onDevice
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
                apiKeyConfigured = profile.apiKeyConfigured ?: false,
                configured = profile.configured ?: false
            )
        }
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
    val updatedAt: String
)
