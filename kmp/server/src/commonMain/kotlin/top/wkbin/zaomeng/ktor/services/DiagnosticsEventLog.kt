package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 一条诊断事件。仅用于排查，不承载任何密钥（调用方负责脱敏，写入侧再兜底一次）。
 */
@Serializable
data class DiagnosticsEvent(
    val ts: String,
    val category: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * 诊断事件日志：内存环形缓冲 + JSONL 持久化。
 *
 * 存在意义：把「对话解析失败（含模型输出摘录）/ 插件动作失败 / 端侧推理状态变迁 /
 * 后端重启」等关键时刻记录下来，随诊断导出一起交给排查者——否则这些信息只进
 * logcat，普通用户拿不到，线上问题只能靠猜。
 *
 * ### R2 保留策略
 * - **崩溃类**（`uncaught` / `crash` / `anr` / `process-exit`）与**常规事件**分别设限：
 *   常规事件的高频写入不允许把崩溃记录挤掉——这正是「一次日志风暴之后崩溃不见了」的成因。
 * - 环形缓冲 + **字节上限** + 超限自动裁剪；裁剪次数与丢弃条数都写进「诊断自身状态」。
 *
 * ### R5 完整载荷
 * - 允许记录**完整提示词 / 完整模型输出 / 完整原文**（[recordPayload]），
 *   只保留工程上限（单字段 [MAX_PAYLOAD_CHARS]，超限时值尾写明「已截断」）。
 * - **凭证仍然绝不记录**：写入侧统一过一遍 [LogRedactor]，把 API Key / 令牌 / 口令
 *   替换为 `***`；即使调用方误传，也不会落盘。
 */
class DiagnosticsEventLog(
    private val file: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 崩溃类事件的独立配额（与常规事件分开计数）。 */
    private val maxCrashEntries: Int = DEFAULT_MAX_CRASH_ENTRIES,
    /** 文件字节上限：超过即裁剪，保证磁盘占用有界。 */
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val lock = SimpleLock()
    private val regular = ArrayDeque<DiagnosticsEvent>()
    private val crashy = ArrayDeque<DiagnosticsEvent>()

    /**
     * 常规事件配额就是调用方给的 [maxEntries]，不再向上改写。
     *
     * 这里曾经写作 `maxEntries.coerceAtLeast(maxCrashEntries)`，于是 `maxEntries`
     * 小于崩溃配额时会被反向抬到崩溃配额（传 5 实际生效 120），参数名不再成立。
     * 改为把**崩溃配额**压到不超过常规配额：既保住「崩溃记录不被常规事件挤掉」的
     * 初衷，又让 `maxEntries` 真的是上限。默认值下（400 ≥ 120）两者完全等价。
     */
    private val maxRegularEntries: Int = maxEntries
    private val crashQuota: Int = maxCrashEntries.coerceAtMost(maxEntries)

    private var dropped = 0L
    private var writeFailures = 0L
    private var readFailures = 0L
    private var trimmed = false

    init {
        loadTail()
    }

    /** 记一条常规事件（短文本；高频路径用这个）。 */
    fun record(category: String, message: String, details: Map<String, String> = emptyMap()) {
        append(
            category = category,
            message = message,
            details = details,
            messageLimit = MESSAGE_LIMIT,
            detailLimit = DETAIL_LIMIT,
            detailEntries = MAX_DETAIL_ENTRIES,
        )
    }

    /**
     * 记一条**完整载荷**（R5）：完整提示词 / 完整模型输出 / 完整原文。
     *
     * 只应在出错或需要留证的路径上调用（解析失败、重试耗尽），不是每轮都记。
     * 上限是工程上限（[MAX_PAYLOAD_CHARS]），不是隐私截断。
     */
    fun recordPayload(category: String, label: String, payload: Map<String, String>) {
        append(
            category = category,
            message = label,
            details = payload,
            messageLimit = PAYLOAD_MESSAGE_LIMIT,
            detailLimit = MAX_PAYLOAD_CHARS,
            detailEntries = MAX_PAYLOAD_ENTRIES,
        )
    }

    private fun append(
        category: String,
        message: String,
        details: Map<String, String>,
        messageLimit: Int,
        detailLimit: Int,
        detailEntries: Int,
    ) {
        val cleanCategory = category.trim().take(CATEGORY_LIMIT).ifBlank { "event" }
        val event = DiagnosticsEvent(
            ts = nowIsoString(),
            category = cleanCategory,
            message = clip(message.trim(), messageLimit).ifBlank { "(no message)" },
            details = details.entries
                .filter { it.key.isNotBlank() }
                .take(detailEntries)
                .associate { (key, value) ->
                    // LogRedactor 在写入侧兜底：即使调用方把密钥塞进详情，也不会落盘。
                    key.trim().take(CATEGORY_LIMIT) to clip(LogRedactor.redactLine(value), detailLimit)
                },
        )
        val isCrashClass = isCrashCategory(cleanCategory)
        lock.withLock {
            val target = if (isCrashClass) crashy else regular
            target.addLast(event)
            val quota = if (isCrashClass) crashQuota else maxRegularEntries
            while (target.size > quota) {
                target.removeFirst()
                dropped++
                trimmed = true
            }
            appendLine(event)
            compactIfTooLarge()
        }
    }

    /** 取最近 [limit] 条（崩溃与常规事件合并后按时间排序）。 */
    fun recent(limit: Int = 100): List<DiagnosticsEvent> = lock.withLock {
        val bounded = limit.coerceIn(1, maxRegularEntries + crashQuota)
        (crashy + regular).sortedBy { it.ts }.takeLast(bounded)
    }

    fun size(): Int = lock.withLock { regular.size + crashy.size }

    /** 当前占用与上限（供「诊断自身状态」与「保留策略」展示）。 */
    fun usage(): EventLogUsage = lock.withLock {
        EventLogUsage(
            regularEntries = regular.size,
            regularMaxEntries = maxRegularEntries,
            crashEntries = crashy.size,
            crashMaxEntries = crashQuota,
            bytes = runCatching { fileSystem.metadataOrNull(file)?.size ?: 0L }.getOrDefault(0L),
            maxBytes = maxFileBytes,
            dropped = dropped,
            writeFailures = writeFailures,
            readFailures = readFailures,
            trimmed = trimmed,
        )
    }

    private fun appendLine(event: DiagnosticsEvent) {
        val written = runCatching {
            // okio 不会自动建父目录：数据根目录在首次写入时可能还不存在，
            // 缺这一步会静默丢日志（导出仍正常，但重启后历史全没）。
            file.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.appendingSink(file, mustExist = false).buffer().use { sink ->
                sink.writeUtf8(json.encodeToString(DiagnosticsEvent.serializer(), event))
                sink.writeUtf8("\n")
            }
            true
        }.getOrDefault(false)
        if (!written) writeFailures++
    }

    private fun compactIfTooLarge() {
        val size = runCatching { fileSystem.metadataOrNull(file)?.size ?: 0L }.getOrDefault(0L)
        if (size <= maxFileBytes) return
        trimmed = true
        runCatching {
            // 裁剪时**先写崩溃类**：即使常规事件把配额吃满，崩溃记录也不能被丢掉。
            val keep = (crashy + regular).takeLast(KEEP_ENTRIES_AFTER_TRIM)
            val content = keep.joinToString("\n") { json.encodeToString(DiagnosticsEvent.serializer(), it) } + "\n"
            fileSystem.write(file) { writeUtf8(content) }
        }.onFailure { writeFailures++ }
    }

    private fun loadTail() {
        val lines = runCatching {
            if (!fileSystem.exists(file)) emptyList()
            else fileSystem.read(file) { readUtf8() }
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        }.getOrElse {
            readFailures++
            emptyList()
        }
        lock.withLock {
            regular.clear()
            crashy.clear()
            lines.forEach { line ->
                runCatching { json.decodeFromString(DiagnosticsEvent.serializer(), line) }
                    .onFailure { readFailures++ }
                    .getOrNull()
                    ?.let { event ->
                        if (isCrashCategory(event.category)) crashy.addLast(event) else regular.addLast(event)
                    }
            }
            while (regular.size > maxRegularEntries) {
                regular.removeFirst()
                dropped++
                trimmed = true
            }
            while (crashy.size > crashQuota) {
                crashy.removeFirst()
                dropped++
                trimmed = true
            }
        }
    }

    /**
     * 按 [limit] 截断，并把截断标记算在限额**之内**。
     *
     * 这些上限是「字段有界、磁盘占用有界」的保证（见类注释 R2/R5）。把标记追加在
     * `limit` 之后会让字段实际长度超出它自己的上限——一条 2000 字的 message 会落盘
     * 412 字，上限因此形同虚设。
     */
    private fun clip(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val keep = (limit - TRUNCATED_MARK.length).coerceAtLeast(0)
        return value.take(keep) + TRUNCATED_MARK
    }

    companion object {
        /** 常规事件的默认配额。 */
        const val DEFAULT_MAX_ENTRIES = 400

        /** 崩溃类事件的独立配额（小得多，但**不会被常规事件挤掉**）。 */
        const val DEFAULT_MAX_CRASH_ENTRIES = 120

        /** 文件字节上限：8 MiB（R5 起放宽，因为允许完整提示词/输出）。 */
        const val DEFAULT_MAX_FILE_BYTES = 8L * 1024L * 1024L

        /** 常规事件单字段字符数上限（短事件，保持诊断页可读）。 */
        const val MESSAGE_LIMIT = 400
        const val DETAIL_LIMIT = 800
        const val MAX_DETAIL_ENTRIES = 12

        /**
         * 完整载荷的单字段字符数上限（R5，**工程上限而非隐私截断**）。
         *
         * 与 `DiagnosticsSink.MAX_PAYLOAD_CHARS` 保持同值：两处口径必须一致，
         * 否则「收口允许写、落盘又砍掉」会在排查时产生假象。
         */
        const val MAX_PAYLOAD_CHARS = 200_000
        const val PAYLOAD_MESSAGE_LIMIT = 400
        const val MAX_PAYLOAD_ENTRIES = 16

        private const val CATEGORY_LIMIT = 40
        private const val KEEP_ENTRIES_AFTER_TRIM = 300
        private const val TRUNCATED_MARK = "…[已按工程上限截断]"

        /** 崩溃类类别：这些类别走独立配额。 */
        private val CRASH_CATEGORIES = setOf("uncaught", "crash", "crash-native", "anr", "process-exit")

        fun isCrashCategory(category: String): Boolean =
            category.trim().lowercase() in CRASH_CATEGORIES

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** 事件日志的占用与自身健康度。 */
data class EventLogUsage(
    val regularEntries: Int = 0,
    val regularMaxEntries: Int = 0,
    val crashEntries: Int = 0,
    val crashMaxEntries: Int = 0,
    val bytes: Long = 0,
    val maxBytes: Long = 0,
    val dropped: Long = 0,
    val writeFailures: Long = 0,
    val readFailures: Long = 0,
    val trimmed: Boolean = false,
)
