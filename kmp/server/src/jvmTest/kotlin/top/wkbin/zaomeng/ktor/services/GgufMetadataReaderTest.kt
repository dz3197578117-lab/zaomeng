package top.wkbin.zaomeng.ktor.services

import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GGUF 头部解析测试。
 *
 * 重点不在「能不能读到值」，而在「遇到不该读的东西时能不能整块跳过」：
 * 真实模型的元数据段里塞着动辄上百万项的 `tokenizer.ggml.tokens` 字符串数组，
 * 逐项读会让导入直接卡死。所以这里刻意把大数组放在待读字段**前面**，
 * 只有跳过逻辑正确才可能拿到后面的 context_length / block_count。
 */
class GgufMetadataReaderTest {

    private class Fixture {
        val dir = Files.createTempDirectory("gguf-reader-test")
        val fs = FileSystem.SYSTEM

        fun writeFile(name: String, build: Buffer.() -> Unit) = Buffer().apply(build).let { buffer ->
            val path = dir.resolve(name).toString().toPath()
            fs.write(path) { writeAll(buffer) }
            path
        }
    }

    @Test
    fun `reads architecture name and dimensions`() {
        val fixture = Fixture()
        val path = fixture.writeFile("basic.gguf") {
            writeGgufHeader(kvCount = 4)
            writeStringKv("general.architecture", "llama")
            writeStringKv("general.name", "测试模型")
            writeIntKv("llama.context_length", 4096)
            writeIntKv("llama.block_count", 32)
        }

        val metadata = assertNotNull(GgufMetadataReader.read(path, fixture.fs))

        assertEquals(3, metadata.version)
        assertEquals("llama", metadata.architecture)
        assertEquals("测试模型", metadata.name)
        assertEquals(4096, metadata.contextLength)
        assertEquals(32, metadata.blockCount)
    }

    @Test
    fun `skips huge string array placed before the wanted keys`() {
        val fixture = Fixture()
        val tokens = List(5_000) { "token-$it" }
        val path = fixture.writeFile("with-tokens.gguf") {
            writeGgufHeader(kvCount = 5)
            writeStringKv("general.architecture", "qwen2")
            // 故意放在前面：只有按类型整块跳过，后面的键才读得到
            writeGgufString("tokenizer.ggml.tokens")
            writeIntLe(TYPE_ARRAY)
            writeIntLe(TYPE_STRING)
            writeLongLe(tokens.size.toLong())
            tokens.forEach { writeGgufString(it) }
            // 数字数组同样要能跳过
            writeGgufString("tokenizer.ggml.token_type")
            writeIntLe(TYPE_ARRAY)
            writeIntLe(TYPE_INT32)
            writeLongLe(64L)
            repeat(64) { writeIntLe(1) }
            writeIntKv("qwen2.context_length", 32_768)
            writeIntKv("qwen2.block_count", 28)
        }

        val metadata = assertNotNull(GgufMetadataReader.read(path, fixture.fs))

        assertEquals("qwen2", metadata.architecture)
        assertEquals(32_768, metadata.contextLength)
        assertEquals(28, metadata.blockCount)
    }

    @Test
    fun `accepts unsigned long context length`() {
        val fixture = Fixture()
        val path = fixture.writeFile("long-ctx.gguf") {
            writeGgufHeader(kvCount = 3)
            writeStringKv("general.architecture", "gemma3")
            writeGgufString("gemma3.context_length")
            writeIntLe(TYPE_UINT64)
            writeLongLe(131_072L)
            writeIntKv("gemma3.block_count", 26)
        }

        val metadata = assertNotNull(GgufMetadataReader.read(path, fixture.fs))

        assertEquals(131_072, metadata.contextLength)
    }

    @Test
    fun `returns null for non gguf content`() {
        val fixture = Fixture()
        val path = fixture.writeFile("not-gguf.bin") {
            write("this is definitely not a gguf file, it is long enough to pass the header check".encodeToByteArray())
        }

        assertNull(GgufMetadataReader.read(path, fixture.fs))
    }

    @Test
    fun `returns null for truncated metadata section`() {
        val fixture = Fixture()
        val path = fixture.writeFile("truncated.gguf") {
            writeGgufHeader(kvCount = 3)
            writeStringKv("general.architecture", "llama")
            // 声明一个长度远超文件实际内容的字符串，解析必须失败而不是抛出去
            writeGgufString("general.name")
            writeIntLe(TYPE_STRING)
            writeLongLe(10_000L)
            write("short".encodeToByteArray())
        }

        assertNull(GgufMetadataReader.read(path, fixture.fs))
    }

    @Test
    fun `returns null for missing file`() {
        val fixture = Fixture()
        val path = fixture.dir.resolve("does-not-exist.gguf").toString().toPath()

        assertNull(GgufMetadataReader.read(path, fixture.fs))
    }

    // ------------------------------------------------------------------
    // GGUF 写入辅助（小端序，仅够构造测试样本）
    // ------------------------------------------------------------------

    private fun Buffer.writeGgufHeader(kvCount: Int) {
        writeIntLe(MAGIC)
        writeIntLe(3) // version
        writeLongLe(0L) // tensor_count
        writeLongLe(kvCount.toLong())
    }

    // 名字刻意避开 okio 自带的 Buffer.writeString(string, charset)，否则调用会飘到内置重载上
    private fun Buffer.writeGgufString(value: String) {
        val bytes = value.encodeToByteArray()
        writeLongLe(bytes.size.toLong())
        write(bytes)
    }

    private fun Buffer.writeStringKv(key: String, value: String) {
        writeGgufString(key)
        writeIntLe(TYPE_STRING)
        writeGgufString(value)
    }

    private fun Buffer.writeIntKv(key: String, value: Int) {
        writeGgufString(key)
        writeIntLe(TYPE_UINT32)
        writeIntLe(value)
    }

    private companion object {
        const val MAGIC = 0x46554747
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
    }
}
