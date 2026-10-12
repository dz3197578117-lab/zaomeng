package top.wkbin.zaomeng.ktor.services

import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * 极简 GGUF 头部读取器。
 *
 * 只为了解决「用户导入一个 .gguf 之后，界面得显示得像个东西」：拿不到架构 / 上下文长度 / 层数，
 * 用户就只能看到一串文件名，没法判断这个模型能不能用、该给多大上下文。
 *
 * 因此这里**不解析张量、不读词表**，只扫元数据 KV 段，并且一旦凑齐关心的键就立刻收工——
 * `general.*` 与 `{arch}.context_length` 都在文件最前面几项，所以实际只读几 KB。
 * 唯一需要小心的是 `tokenizer.ggml.tokens` 这类上百万项的数组：必须按类型整块 `skip`，
 * 逐项读会让「导入大模型」卡死。
 *
 * 任何异常（文件被截断、不是 GGUF、未知值类型）都返回 null，绝不向上抛——
 * 元数据只影响展示，不该让导入失败。
 */
object GgufMetadataReader {

    /** GGUF 魔数："GGUF" 按小端读成的 UInt32。 */
    private const val MAGIC = 0x46554747

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    /** GGUF 头部定长部分：magic(4) + version(4) + tensor_count(8) + kv_count(8)。 */
    private const val HEADER_BYTES = 24L

    /** 最多扫多少个 KV（防御：畸形文件里 kv_count 可能是天文数字）。 */
    private const val MAX_KV_SCAN = 4_096L

    /** 最多扫多少字节（防御：keys 之后紧跟着巨大的数组）。 */
    private const val MAX_SCAN_BYTES = 16L * 1024 * 1024

    /** 数组元素数上限。 */
    private const val MAX_ARRAY_ELEMENTS = 8L * 1024 * 1024

    data class GgufMetadata(
        val version: Int,
        /** 例如 `qwen2`、`llama`、`gemma3`。 */
        val architecture: String?,
        /** 模型自报名（`general.name`）。 */
        val name: String?,
        /** 训练时的最大上下文长度。 */
        val contextLength: Int?,
        /** Transformer 层数。 */
        val blockCount: Int?,
        /** 隐藏层维度。 */
        val embeddingLength: Int?,
    )

    fun read(path: Path, fileSystem: FileSystem = FileSystem.SYSTEM): GgufMetadata? =
        runCatching { parseFile(path, fileSystem) }.getOrNull()

    private fun parseFile(path: Path, fileSystem: FileSystem): GgufMetadata? {
        val totalSize = fileSystem.metadataOrNull(path)?.size ?: return null
        if (totalSize < HEADER_BYTES) return null
        // 不用 kotlin.io 的 use{}：它要求的接收者上界是 AutoCloseable，而 okio 的 Source 仅保证实现
        // 自身的 close() 接口方法；这里在 finally 中显式 close()，Android/JVM 行为一致。
        val source = fileSystem.source(path).buffer()
        return try {
            parse(source, totalSize)
        } finally {
            source.close()
        }
    }

    private fun parse(source: BufferedSource, totalSize: Long): GgufMetadata? {
        val cursor = Cursor(source, totalSize)
        if (cursor.readInt() != MAGIC) return null
        val version = cursor.readInt()
        cursor.readLong() // tensor_count：不需要
        val kvCount = cursor.readLong()
        if (kvCount < 0) return null

        var architecture: String? = null
        var name: String? = null
        var contextLength: Int? = null
        var blockCount: Int? = null
        var embeddingLength: Int? = null

        val scanLimit = minOf(kvCount, MAX_KV_SCAN)
        var scanned = 0L
        while (scanned < scanLimit && cursor.remaining() > 0 && cursor.offset < MAX_SCAN_BYTES) {
            val key = cursor.readString()
            val type = cursor.readInt()
            scanned++
            when {
                key == "general.architecture" && type == TYPE_STRING ->
                    architecture = cursor.readString().ifBlank { null }

                key == "general.name" && type == TYPE_STRING ->
                    name = cursor.readString().ifBlank { null }

                key == "general.file_type" -> {
                    // 只是为了让「不认识的数字键」不要落到下面的分支；取值本身不需要
                    val value = cursor.readIntegerOrNull(type)
                    if (value == null) cursor.skipValue(type)
                }

                architecture != null && cursor.isIntegerType(type) -> {
                    val value = cursor.readIntegerOrNull(type)
                    when (key) {
                        "$architecture.context_length" -> contextLength = value?.toInt()
                        "$architecture.block_count" -> blockCount = value?.toInt()
                        "$architecture.embedding_length" -> embeddingLength = value?.toInt()
                    }
                }

                else -> cursor.skipValue(type)
            }
            // 三个关键值凑齐就收工：后面的 KV 里还躺着百万级的分词表
            if (architecture != null && contextLength != null && blockCount != null) break
        }

        return GgufMetadata(
            version = version,
            architecture = architecture,
            name = name,
            contextLength = contextLength?.takeIf { it > 0 },
            blockCount = blockCount?.takeIf { it > 0 },
            embeddingLength = embeddingLength?.takeIf { it > 0 },
        )
    }

    /** 读取游标：自己维护偏移量，不依赖 source 内部状态判断越界。 */
    private class Cursor(
        private val source: BufferedSource,
        private val totalSize: Long,
    ) {
        var offset = 0L
            private set

        fun remaining(): Long = totalSize - offset

        fun isIntegerType(type: Int): Boolean = when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_UINT16, TYPE_INT16,
            TYPE_UINT32, TYPE_INT32, TYPE_UINT64, TYPE_INT64,
            -> true

            else -> false
        }

        fun readInt(): Int {
            need(4)
            val value = source.readIntLe()
            offset += 4
            return value
        }

        fun readLong(): Long {
            need(8)
            val value = source.readLongLe()
            offset += 8
            return value
        }

        fun readString(): String {
            val length = readLong()
            need(length)
            val value = source.readUtf8(length)
            offset += length
            return value
        }

        /** 读整型标量；type 不是整型时返回 null 且**不移动游标**。 */
        fun readIntegerOrNull(type: Int): Long? = when (type) {
            TYPE_UINT8 -> readFixed(1) { source.readByte().toLong() and 0xFF }
            TYPE_INT8 -> readFixed(1) { source.readByte().toLong() }
            TYPE_UINT16 -> readFixed(2) { source.readShortLe().toLong() and 0xFFFF }
            TYPE_INT16 -> readFixed(2) { source.readShortLe().toLong() }
            TYPE_UINT32 -> readFixed(4) { source.readIntLe().toLong() and 0xFFFFFFFFL }
            TYPE_INT32 -> readFixed(4) { source.readIntLe().toLong() }
            TYPE_UINT64, TYPE_INT64 -> readFixed(8) { source.readLongLe() }
            else -> null
        }

        fun skipValue(type: Int) {
            val fixed = fixedSizeOf(type)
            if (fixed > 0) {
                skipBytes(fixed.toLong())
                return
            }
            when (type) {
                TYPE_STRING -> skipBytes(readLong())
                TYPE_ARRAY -> skipArray()
                else -> throw IllegalArgumentException("unknown gguf value type: $type")
            }
        }

        private fun skipArray() {
            val elementType = readInt()
            val count = readLong()
            if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
                throw IllegalArgumentException("unreasonable array count $count")
            }
            if (count == 0L) return
            val fixed = fixedSizeOf(elementType)
            if (fixed > 0) {
                val total = fixed.toLong() * count
                need(total)
                skipBytes(total)
                return
            }
            when (elementType) {
                // 字符串数组（分词表 / merges）：只跳过长度字段，不分配字符串
                TYPE_STRING -> {
                    var index = 0L
                    while (index < count) {
                        skipBytes(readLong())
                        index++
                    }
                }
                // 嵌套数组在真实模型里不存在，放弃解析
                else -> throw IllegalArgumentException("unsupported array element type $elementType")
            }
        }

        private fun skipBytes(count: Long) {
            if (count < 0) throw IllegalArgumentException("negative skip $count")
            // need() 已经用文件大小兜住了越界，这里不会再跑到 EOF 之外
            need(count)
            if (count == 0L) return
            source.skip(count)
            offset += count
        }

        private fun readFixed(bytes: Int, read: () -> Long): Long {
            need(bytes.toLong())
            val value = read()
            offset += bytes
            return value
        }

        private fun need(bytes: Long) {
            if (bytes < 0) throw IllegalArgumentException("negative length $bytes")
            if (bytes > remaining()) {
                throw IllegalArgumentException("unexpected EOF: need $bytes, have ${remaining()}")
            }
        }
    }

    private fun fixedSizeOf(type: Int): Int = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
        TYPE_UINT16, TYPE_INT16 -> 2
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
        else -> 0
    }
}
