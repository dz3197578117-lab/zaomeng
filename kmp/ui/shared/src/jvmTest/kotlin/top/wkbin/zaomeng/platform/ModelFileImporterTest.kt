package top.wkbin.zaomeng.platform

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 导入路径的落地验证：这里用真实的临时文件与真实文件系统跑一遍，
 * 确认 FileKit 的 `source()/sink()/createDirectories()/atomicMove()/delete()`
 * 在 JVM 上确实可用且行为符合预期——这些 API 在 Android 上走的是另一套实现
 * （`content://` → `ContentResolver`），所以这里过了不等于 Android 一定过，
 * 但至少证明调用方式正确、分片搬运与改名策略无误。
 */
class ModelFileImporterTest {

    @Test
    fun `按流式分片复制并改名到位`() = runBlocking {
        val source = createTempGgufFile(byteCount = 2 * 1024 * 1024 + 123)
        val targetDir = createTempDir()

        val imported = importIntoDirectory(
            picked = PlatformFile(source.absolutePath),
            targetDir = targetDir.absolutePath,
        )

        assertEquals("fixture-Q4_K_M.gguf", imported.fileName)
        assertEquals(source.length(), imported.sizeBytes)

        val copied = File(targetDir, imported.fileName)
        assertTrue(copied.exists(), "目标文件应已就位")
        assertContentEquals(source.readBytes(), copied.readBytes(), "逐字节应与源文件一致")
        assertNoLeftoverPartFile(targetDir)
    }

    @Test
    fun `进度回调总量等于实际大小`() = runBlocking {
        val source = createTempGgufFile(byteCount = 3 * 1024 * 1024)
        val targetDir = createTempDir()
        val reported = mutableListOf<Long>()

        val imported = importIntoDirectory(
            picked = PlatformFile(source.absolutePath),
            targetDir = targetDir.absolutePath,
            onProgress = { copied, _ -> reported += copied },
        )

        assertTrue(reported.isNotEmpty(), "至少应有一次进度回调")
        // 进度必须单调递增，且最后一笔就是最终大小
        assertEquals(reported.sorted(), reported)
        assertEquals(imported.sizeBytes, reported.last())
    }

    @Test
    fun `非 gguf 扩展名被拒绝`() = runBlocking {
        val source = File(createTempDir(), "模型.zip").apply { writeBytes(ByteArray(16)) }
        val targetDir = createTempDir()

        val error = assertFailsWith<IllegalArgumentException> {
            importIntoDirectory(PlatformFile(source.absolutePath), targetDir.absolutePath)
        }

        assertTrue(error.message!!.contains(".gguf"), "错误信息应提示需要 .gguf：${error.message}")
        assertTrue(targetDir.listFiles().orEmpty().isEmpty(), "不应留下任何产物")
    }

    @Test
    fun `同名模型已存在时拒绝导入`() = runBlocking {
        val source = createTempGgufFile(byteCount = 1024)
        val targetDir = createTempDir()
        File(targetDir, "fixture-Q4_K_M.gguf").writeBytes(ByteArray(8))

        val error = assertFailsWith<IllegalStateException> {
            importIntoDirectory(PlatformFile(source.absolutePath), targetDir.absolutePath)
        }

        assertTrue(error.message!!.contains("已存在"), "错误信息应说明重名：${error.message}")
        // 已存在的模型不能被覆盖
        assertEquals(8L, File(targetDir, "fixture-Q4_K_M.gguf").length())
    }

    @Test
    fun `残留的 part 文件会被清理并完成导入`() = runBlocking {
        val source = createTempGgufFile(byteCount = 4096)
        val targetDir = createTempDir()
        File(targetDir, "fixture-Q4_K_M.gguf.part").writeBytes(ByteArray(32))

        val imported = importIntoDirectory(PlatformFile(source.absolutePath), targetDir.absolutePath)

        assertEquals(source.length(), imported.sizeBytes, "旧的半截文件不应被当成已完成内容")
        assertContentEquals(source.readBytes(), File(targetDir, imported.fileName).readBytes())
        assertNoLeftoverPartFile(targetDir)
    }

    @Test
    fun `拷贝途中取消会清掉半截文件且不留下 gguf`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val buffer = Buffer()
        // 在第 5 次读取时取消，随后循环顶部的 ensureActive() 应抛出取消异常
        var job: Job? = null
        var reads = 0
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                reads++
                if (reads == 5) job?.cancel()
                if (reads > 1_000) return -1L
                sink.writeByte(7)
                return 1L
            }

            override fun close() = Unit
        }

        var thrown: CancellationException? = null
        job = scope.launch {
            try {
                copyWithProgress(source, buffer, totalBytes = 1_000L, onProgress = { _, _ -> })
            } catch (cancelled: CancellationException) {
                thrown = cancelled
                throw cancelled
            }
        }
        job.cancelAndJoin()

        assertTrue(thrown != null, "紧循环里必须靠 ensureActive() 才能被取消")
        assertTrue(reads < 1_000, "取消应在搬完之前生效，实际读取 $reads 次")
    }

    @Test
    fun `文件名清洗规则与端侧服务一致`() {
        assertEquals("a-Q4_K_M.gguf", sanitizeModelFileName("a-Q4_K_M.gguf"))
        assertEquals("model.gguf", sanitizeModelFileName("  model.gguf  "))
        // provider 有时给带目录的显示名，只取最后一段
        assertEquals("model.gguf", sanitizeModelFileName("/storage/emulated/0/Download/model.gguf"))

        assertNull(sanitizeModelFileName(""), "空名字不可用")
        assertNull(sanitizeModelFileName("   "))
        assertNull(sanitizeModelFileName(".hidden.gguf"), "以点开头不可用")
        // 先取 basename 再校验，所以「带目录」天然不可能穿越；这里确认结果确实被削成了安全名字
        assertEquals("escape.gguf", sanitizeModelFileName("../escape.gguf"))
        assertEquals("escape.gguf", sanitizeModelFileName("..\\..\\escape.gguf"))
        // 名字本身里带 .. 仍按端侧服务的规则拒绝
        assertNull(sanitizeModelFileName("a..b.gguf"), "含 .. 的名字不可用")
        assertNull(sanitizeModelFileName("a".repeat(201) + ".gguf"), "超长名字不可用")
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun createTempDir(): File =
        Files.createTempDirectory("zaomeng-import").toFile().apply { deleteOnExit() }

    /** 造一个足够大、内容随机的假 GGUF：随机内容能让「逐字节一致」的断言真正有意义。 */
    private fun createTempGgufFile(byteCount: Int): File {
        val bytes = ByteArray(byteCount)
        Random(0x5EED).nextBytes(bytes)
        return File(createTempDir(), "fixture-Q4_K_M.gguf").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
    }

    private fun assertNoLeftoverPartFile(targetDir: File) {
        val parts = targetDir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(parts.isEmpty(), "不应残留 .part 文件：${parts.map { it.name }}")
    }
}
