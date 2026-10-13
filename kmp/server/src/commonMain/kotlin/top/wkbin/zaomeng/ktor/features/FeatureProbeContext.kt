package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import top.wkbin.zaomeng.ktor.services.DiagnosticsEvent
import top.wkbin.zaomeng.ktor.services.DiagnosticsEventLog
import top.wkbin.zaomeng.ktor.services.ModelConnectivityLog
import top.wkbin.zaomeng.ktor.services.PathSafety
import top.wkbin.zaomeng.ktor.services.PromptLoader
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 探针运行上下文：**任何探针都只能通过这些只读入口访问数据**。
 *
 * 刻意收窄能力面：
 * - 没有「写」的口子 —— 探针只能读，杜绝「顺手修复」业务数据；
 * - 没有采集开关（`RuntimeCaptureState`）的口子 —— 探针的结论**在运行日志采集关闭时也必须照常给出**
 *   （见 `docs/server-api.md` 的功能自检一节）。采集开关只控制常规 logcat 落盘，
 *   与这里的结构化事件环形缓冲、清单/档案文件读取完全无关；
 * - 需要日志样本的探针只能读 [eventLog]（内存环形缓冲 + JSONL，独立于采集开关），
 *   没有样本时返回 [ProbeStatus.NO_DATA] / [ProbeStatus.SKIPPED]，**不得**返回 FAIL。
 *
 * @param storage 只读数据访问（清单、档案、原文索引）
 * @param promptLoader 提示词加载器（运行时真正读到的那一份）
 * @param eventLog 结构化诊断事件（不依赖采集开关）
 * @param connectivityLog 模型连通性测试记录
 * @param onDeviceSnapshot 端侧推理快照（状态、端口、模型名、截断计数）
 * @param apiKeyConfiguredOf 某 profile 是否配了 Key（只回答「有没有」，不返回内容）
 * @param keystoreHealth 安全存储健康度：**只返回成败与条目数，绝不返回内容**
 * @param maxRunsPerProbe 单个探针最多检查多少个 run（防止「一百本书卷」把诊断拖慢）
 * @param maxSessionsPerProbe 单个探针最多检查多少个会话
 */
class FeatureProbeContext(
    val storage: StorageService,
    val promptLoader: PromptLoader,
    val eventLog: DiagnosticsEventLog?,
    val connectivityLog: ModelConnectivityLog?,
    val onDeviceSnapshot: (() -> Map<String, String>)? = null,
    val apiKeyConfiguredOf: ((String) -> Boolean?)? = null,
    /** 安全存储健康度探测（只读；**只返回成败与条目数，绝不返回内容**）。 */
    val keystoreHealth: (() -> KeystoreHealth)? = null,
    val maxRunsPerProbe: Int = DEFAULT_MAX_RUNS,
    val maxSessionsPerProbe: Int = DEFAULT_MAX_SESSIONS,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // 只读数据入口
    // ------------------------------------------------------------------

    /** 所有书卷 id（已排序；读取走 Room/文档存储，不触碰文件系统之外的东西）。 */
    fun runIds(): List<String> = runCatching { storage.listRunIds() }.getOrDefault(emptyList())

    /** 探针关心的 run 列表：按「最近改动优先」取有限个，避免逐本全扫。 */
    fun runIdsForProbe(): List<String> =
        runIds()
            .sortedByDescending { runCatching { storage.lastModifiedMillis(storage.getRunManifestPath(it)) }.getOrDefault(0L) }
            .take(maxRunsPerProbe)

    /**
     * 探针的「焦点书卷」：最近改动的那一本。
     *
     * 多数明细探针（弧线、档案、章节索引）只对焦点书卷做深查，
     * 否则 N 本书卷 × 每本 M 个档案会把诊断页拖慢——而探针必须廉价。
     */
    fun focusRunId(): String? = runIdsForProbe().firstOrNull()

    fun readRunManifest(runId: String): JsonObject? = runCatching { storage.readRunManifest(runId) }.getOrNull()

    fun listSessionIds(runId: String): List<String> =
        runCatching { storage.listDialogueSessionIds(runId) }.getOrDefault(emptyList())

    fun loadSessionManifest(runId: String, sessionId: String): JsonObject? =
        runCatching { storage.loadSessionManifest(runId, sessionId) }.getOrNull()

    /** 会话里**只读**的子文档（长期记忆等）。路径经 [PathSafety] 校验。 */
    fun readSessionFile(runId: String, sessionId: String, fileName: String): String? = runCatching {
        val dir = storage.getDialogueSessionManifestFile(runId, sessionId).parent ?: return@runCatching null
        storage.readTextOrNull(dir / PathSafety.sanitizePathComponent(fileName, "file"))
    }.getOrNull()

    /** `runs/{runId}` 下的相对路径文本（只读，相对路径逐段校验，不允许逃逸）。 */
    fun readRunText(runId: String, vararg relativePath: String): String? = runCatching {
        storage.readTextOrNull(runFile(runId, *relativePath))
    }.getOrNull()

    /**
     * 同 [readRunText]，但只取前 [maxChars] 个字符。
     *
     * 探针只需要判断字段完整性与占位词，不需要整份档案：
     * 封顶读取能保证「人物档案很多、很有钱」时探针依然是廉价操作。
     */
    fun readRunTextLimited(runId: String, maxChars: Int, vararg relativePath: String): String? =
        readRunText(runId, *relativePath)?.take(maxChars)

    /** `runs/{runId}` 下的相对路径（只读；每段都过 PathSafety）。 */
    fun runFile(runId: String, vararg relativePath: String): Path {
        var path = storage.getRunDirectory(PathSafety.validateStorageId(runId, "run_id"))
        relativePath.forEach { segment ->
            val parts = segment.split('/').filter { it.isNotBlank() }
            parts.forEach { part ->
                path = path / PathSafety.sanitizePathComponent(part, "path")
            }
        }
        return path
    }

    fun exists(path: Path): Boolean = runCatching { storage.exists(path) }.getOrDefault(false)

    fun isFile(path: Path): Boolean = runCatching { storage.isFile(path) }.getOrDefault(false)

    fun isDirectory(path: Path): Boolean = runCatching { storage.isDirectory(path) }.getOrDefault(false)

    fun listFiles(path: Path): List<Path> = runCatching { storage.listFiles(path) }.getOrDefault(emptyList())

    fun fileSize(path: Path): Long = runCatching { storage.fileSize(path) }.getOrDefault(0L)

    fun lastModifiedMillis(path: Path): Long = runCatching { storage.lastModifiedMillis(path) }.getOrDefault(0L)

    /** 解析 JSON 文本；失败返回 null（探针自行决定是 WARN 还是 FAIL）。 */
    fun parseJsonObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 结构化事件（**独立于运行日志采集开关**）
    // ------------------------------------------------------------------

    /**
     * 最近的结构化事件。
     *
     * 来源是 `DiagnosticsEventLog` 的进程内环形缓冲 + 独立 JSONL 文件，
     * 由 [top.wkbin.zaomeng.platform.DiagnosticsSink] 写入，**不受常规日志采集开关控制**：
     * 采集关闭时这里依然有数据。没有采集器（[eventLog] 为 null）时返回空列表。
     */
    fun recentEvents(limit: Int = 200): List<DiagnosticsEvent> =
        runCatching { eventLog?.recent(limit) }.getOrNull().orEmpty()

    // ------------------------------------------------------------------
    // 端侧 / 密钥
    // ------------------------------------------------------------------

    /** 端侧推理快照；没有端侧能力时为空表。 */
    fun onDevice(): Map<String, String> = runCatching { onDeviceSnapshot?.invoke() }.getOrNull().orEmpty()

    /** 该 profile 是否配了 Key（三态：true/false/null=查不到）。**永远不返回 Key 内容**。 */
    fun apiKeyConfigured(profileId: String): Boolean? =
        runCatching { apiKeyConfiguredOf?.invoke(profileId) }.getOrNull()

    companion object {
        const val DEFAULT_MAX_RUNS = 8
        const val DEFAULT_MAX_SESSIONS = 12
    }
}

/**
 * 安全存储（Keystore / JVM 本地）健康度探针结果。
 *
 * **硬线：这里只有成败与条目数，任何时候都不携带密钥内容。**
 *
 * @param readable 存储本身可读（entries 调用成功）
 * @param entryCount 已存在的密钥条目数
 * @param sampleReadOk 对已存在条目做一次读取是否成功；没有条目时为 null（没样本）
 * @param errorSummary 失败摘要（异常类型 + 消息，**不含值**）
 */
data class KeystoreHealth(
    val readable: Boolean,
    val entryCount: Int,
    val sampleReadOk: Boolean?,
    val errorSummary: String = "",
    /** 存储来源说明（Android Keystore / JVM 本地文件等），供报告标注。 */
    val source: String = "",
)

// ----------------------------------------------------------------------
// 探针共用的 JSON 小工具（纯函数，便于单测）
// ----------------------------------------------------------------------

/** 安全取字符串（非字符串/缺失都返回 ""）。 */
internal fun JsonObject.str(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

/** 安全取整数（缺失/非法返回 null）。 */
internal fun JsonObject.intOrNull(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

/** 安全取对象（缺失/非法返回 null）。 */
internal fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()

/** 安全取数组（缺失/非法返回空）。 */
internal fun JsonObject.arr(key: String): JsonArray = runCatching { this[key]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

/** 数组里的对象项（跳过非法项）。 */
internal fun JsonArray.objects(): List<JsonObject> =
    this.mapNotNull { runCatching { it.jsonObject }.getOrNull() }

/** 数组里的字符串项（跳过非字符串项）。 */
internal fun JsonArray.strings(): List<String> =
    this.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
