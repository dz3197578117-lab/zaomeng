package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.data.api.ModelConnectivityRecordDto
import top.wkbin.zaomeng.platform.SimpleLock

/**
 * 模型连通性测试记录（最近若干次，JSONL 落盘）。
 *
 * 存在理由：「测试连接」的结果原本只回给 UI，弹个提示就没了；用户报「连不上」时
 * 诊断报告里只有 profile 摘要（provider/model/是否配了 Key），**没有连通性结论**，
 * 排查者无法区分「Key 没配」「网络不通」「模型名写错」。
 *
 * - 只存结果摘要（成功/失败、延迟、截断后的错误信息），不存 API Key、不存请求/响应正文。
 * - 内存环形 + 文件上限，避免无界增长。
 */
class ModelConnectivityLog(
    private val file: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxEntries: Int = 20,
) {
    private val lock = SimpleLock()
    private val buffer = ArrayDeque<ModelConnectivityRecordDto>()

    init {
        loadTail()
    }

    fun record(record: ModelConnectivityRecordDto) {
        val bounded = record.copy(
            profileId = record.profileId.take(80),
            provider = record.provider.take(80),
            model = record.model.take(120),
            baseUrl = record.baseUrl.take(200),
            // 再兜一层脱敏：供应商错误信息里若回显了带凭据的 URL/头，导出前必须已经变成 ***
            message = LogRedactor.redactLine(record.message).take(MESSAGE_LIMIT),
        )
        lock.withLock {
            buffer.addLast(bounded)
            while (buffer.size > maxEntries) buffer.removeFirst()
            appendLine(bounded)
            compactIfTooLarge()
        }
    }

    /** 最近的记录，时间正序（新的在后）。 */
    fun recent(limit: Int = maxEntries): List<ModelConnectivityRecordDto> = lock.withLock {
        buffer.toList().takeLast(limit.coerceIn(1, maxEntries))
    }

    private fun appendLine(record: ModelConnectivityRecordDto) {
        runCatching {
            file.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.appendingSink(file, mustExist = false).buffer().use { sink ->
                sink.writeUtf8(json.encodeToString(ModelConnectivityRecordDto.serializer(), record))
                sink.writeUtf8("\n")
            }
        }
    }

    private fun compactIfTooLarge() {
        val size = runCatching { fileSystem.metadataOrNull(file)?.size ?: 0L }.getOrDefault(0L)
        if (size <= MAX_FILE_BYTES) return
        runCatching {
            val content = buffer.joinToString("\n") {
                json.encodeToString(ModelConnectivityRecordDto.serializer(), it)
            } + "\n"
            fileSystem.write(file) { writeUtf8(content) }
        }
    }

    private fun loadTail() {
        val lines = runCatching {
            if (!fileSystem.exists(file)) emptyList()
            else fileSystem.read(file) { readUtf8() }.lineSequence().filter(String::isNotBlank).toList()
        }.getOrDefault(emptyList())
        lock.withLock {
            buffer.clear()
            lines.takeLast(maxEntries).forEach { line ->
                runCatching { json.decodeFromString(ModelConnectivityRecordDto.serializer(), line) }
                    .getOrNull()
                    ?.let { buffer.addLast(it) }
            }
        }
    }

    private companion object {
        const val MESSAGE_LIMIT = 400
        const val MAX_FILE_BYTES = 256L * 1024L
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
