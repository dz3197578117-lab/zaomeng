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
 * 一条诊断事件。仅用于排查，不承载任何密钥（调用方负责脱敏）。
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
 * - 写入是 append-only JSONL，单行一个完整事件；读取时跳过损坏行。
 * - 内存缓冲封顶 [maxEntries]，文件超过 [MAX_FILE_BYTES] 时整体重压为缓冲内容，
 *   保证任何情况下文件有界、不会慢慢吃光用户磁盘。
 * - 所有字段在入口处截断（message 400 / 单个 detail 800 / detail 12 条），
 *   防止一条故障把整个文件撑爆。
 */
class DiagnosticsEventLog(
    private val file: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxEntries: Int = 400,
) {
    private val lock = SimpleLock()
    private val buffer = ArrayDeque<DiagnosticsEvent>()

    init {
        loadTail()
    }

    fun record(category: String, message: String, details: Map<String, String> = emptyMap()) {
        val event = DiagnosticsEvent(
            ts = nowIsoString(),
            category = category.trim().take(40).ifBlank { "event" },
            message = message.trim().take(MESSAGE_LIMIT).ifBlank { "(no message)" },
            details = details.entries
                .filter { it.key.isNotBlank() }
                .take(MAX_DETAIL_ENTRIES)
                .associate { (key, value) -> key.trim().take(40) to value.take(DETAIL_LIMIT) },
        )
        lock.withLock {
            buffer.addLast(event)
            while (buffer.size > maxEntries) buffer.removeFirst()
            appendLine(event)
            compactIfTooLarge()
        }
    }

    fun recent(limit: Int = 100): List<DiagnosticsEvent> = lock.withLock {
        val bounded = limit.coerceIn(1, maxEntries)
        buffer.toList().takeLast(bounded)
    }

    fun size(): Int = lock.withLock { buffer.size }

    private fun appendLine(event: DiagnosticsEvent) {
        runCatching {
            // okio 不会自动建父目录：数据根目录在首次写入时可能还不存在，
            // 缺这一步会静默丢日志（导出仍正常，但重启后历史全没）。
            file.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.appendingSink(file, mustExist = false).buffer().use { sink ->
                sink.writeUtf8(json.encodeToString(DiagnosticsEvent.serializer(), event))
                sink.writeUtf8("\n")
            }
        }
    }

    private fun compactIfTooLarge() {
        val size = runCatching { fileSystem.metadataOrNull(file)?.size ?: 0L }.getOrDefault(0L)
        if (size <= MAX_FILE_BYTES) return
        runCatching {
            val content = buffer.joinToString("\n") { json.encodeToString(DiagnosticsEvent.serializer(), it) } + "\n"
            fileSystem.write(file) { writeUtf8(content) }
        }
    }

    private fun loadTail() {
        val lines = runCatching {
            if (!fileSystem.exists(file)) emptyList()
            else fileSystem.read(file) { readUtf8() }
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        }.getOrDefault(emptyList())
        lock.withLock {
            buffer.clear()
            lines.takeLast(maxEntries).forEach { line ->
                runCatching { json.decodeFromString(DiagnosticsEvent.serializer(), line) }
                    .getOrNull()
                    ?.let { buffer.addLast(it) }
            }
        }
    }

    private companion object {
        const val MESSAGE_LIMIT = 400
        const val DETAIL_LIMIT = 800
        const val MAX_DETAIL_ENTRIES = 12
        const val MAX_FILE_BYTES = 1024L * 1024L
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
