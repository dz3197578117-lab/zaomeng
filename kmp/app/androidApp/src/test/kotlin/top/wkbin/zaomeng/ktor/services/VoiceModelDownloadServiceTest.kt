package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.data.api.VoiceModelFileDto
import top.wkbin.zaomeng.platform.NativeProcessHandle
import top.wkbin.zaomeng.platform.OnDeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 语音模型下载服务的回归测试。
 *
 * 请求里的模型 id / 文件名 / 地址**全部来自客户端**，其中前两者会参与拼路径——
 * 所以这里重点覆盖目录穿越、不受信主机与用户信息绕过，以及「已下好的文件不重下」。
 */
class VoiceModelDownloadServiceTest {

    // ------------------------------------------------------------------
    // 校验器（纯逻辑）
    // ------------------------------------------------------------------

    @Test
    fun `valid values pass and are trimmed`() {
        assertEquals("vits-zh-hf-theresa", VoiceModelRequestValidator.requireModelId("  vits-zh-hf-theresa  "))
        assertEquals("theresa.onnx", VoiceModelRequestValidator.requireFileName(" theresa.onnx "))
        assertEquals(
            "https://hf-mirror.com/a/b/resolve/main/c.onnx",
            VoiceModelRequestValidator.requireDownloadUrl("https://hf-mirror.com/a/b/resolve/main/c.onnx "),
        )
    }

    @Test
    fun `model id and file name reject path traversal`() {
        for (bad in listOf("..", "../x", "a/../b", "/abs", "a/b", "a\\b", ".hidden", "", "   ", "a b")) {
            assertFailsWith<IllegalArgumentException>("模型 id 应拒绝「$bad」") {
                VoiceModelRequestValidator.requireModelId(bad)
            }
            assertFailsWith<IllegalArgumentException>("文件名应拒绝「$bad」") {
                VoiceModelRequestValidator.requireFileName(bad)
            }
        }
    }

    @Test
    fun `model id and file name enforce length limits`() {
        assertEquals(64, VoiceModelRequestValidator.requireModelId("a".repeat(64)).length)
        assertFailsWith<IllegalArgumentException> { VoiceModelRequestValidator.requireModelId("a".repeat(65)) }
        assertEquals(200, VoiceModelRequestValidator.requireFileName("a".repeat(200)).length)
        assertFailsWith<IllegalArgumentException> { VoiceModelRequestValidator.requireFileName("a".repeat(201)) }
    }

    @Test
    fun `download url must be https on an allowed host with a path`() {
        // 允许
        for (good in listOf(
            "https://huggingface.co/csukuangfj/x/resolve/main/a.onnx",
            "https://hf-mirror.com/csukuangfj/x/resolve/main/a.onnx",
            "https://HF-Mirror.com/csukuangfj/x/resolve/main/a.onnx",
            "https://hf-mirror.com:443/csukuangfj/x/resolve/main/a.onnx",
        )) {
            assertEquals(good, VoiceModelRequestValidator.requireDownloadUrl(good))
        }
        // 拒绝：明文、未知主机、主机名伪装、缺路径
        for (bad in listOf(
            "http://hf-mirror.com/a",
            "https://evil.com/csukuangfj/x/resolve/main/a.onnx",
            "https://huggingface.co.evil.com/a",
            "https://evil.com/huggingface.co/a",
            "https://hf-mirror.com",
            "https://hf-mirror.com/",
            "",
        )) {
            assertFailsWith<IllegalArgumentException>("地址应拒绝「$bad」") {
                VoiceModelRequestValidator.requireDownloadUrl(bad)
            }
        }
    }

    @Test
    fun `userinfo cannot smuggle another host`() {
        // `https://huggingface.co@evil.com/x` 的真正主机是 evil.com，必须拒绝
        assertFailsWith<IllegalArgumentException> {
            VoiceModelRequestValidator.requireDownloadUrl("https://huggingface.co@evil.com/x")
        }
        // 反过来：userinfo 是白名单主机、真实主机也在白名单，则允许
        assertEquals(
            "https://hf-mirror.com@huggingface.co/a/b",
            VoiceModelRequestValidator.requireDownloadUrl("https://hf-mirror.com@huggingface.co/a/b"),
        )
    }

    // ------------------------------------------------------------------
    // 服务（用假 runtime + 临时目录）
    // ------------------------------------------------------------------

    @Test
    fun `status on an empty model directory reports nothing present`() {
        val fixture = Fixture()
        val status = fixture.service.status("vits-zh-hf-theresa")
        assertEquals("vits-zh-hf-theresa", status.modelId)
        assertEquals(emptyList(), status.presentFiles)
        assertEquals(0, status.presentBytes)
        assertEquals(false, status.download.active)
    }

    @Test
    fun `status lists existing files and their total size`() {
        val fixture = Fixture()
        val dir = fixture.modelDir("vits-zh-hf-theresa")
        fixture.fileSystem.createDirectories(dir)
        fixture.fileSystem.write(dir / "tokens.txt") { write(ByteArray(10)) }
        fixture.fileSystem.write(dir / "lexicon.txt") { write(ByteArray(20)) }

        val status = fixture.service.status("vits-zh-hf-theresa")
        assertEquals(listOf("lexicon.txt", "tokens.txt"), status.presentFiles)
        assertEquals(30, status.presentBytes)
    }

    @Test
    fun `download writes every file and reports completion`() = runBlocking {
        val fixture = Fixture()
        val status = fixture.service.startDownload(
            DownloadVoiceModelRequest(
                modelId = "vits-zh-hf-theresa",
                files = listOf(
                    VoiceModelFileDto("theresa.onnx", URL_T, 16),
                    VoiceModelFileDto("lexicon.txt", URL_L, 16),
                ),
            ),
        )
        assertEquals(true, status.download.active)
        fixture.awaitIdle()

        val dir = fixture.modelDir("vits-zh-hf-theresa")
        assertEquals(
            listOf("lexicon.txt", "theresa.onnx"),
            fixture.service.status("vits-zh-hf-theresa").presentFiles,
        )
        assertEquals(2, fixture.requestedUrls.size, "两个文件都应被请求：${fixture.requestedUrls}")
        assertTrue(fixture.fileSystem.exists(dir / "theresa.onnx"))
        assertEquals(false, fixture.service.status("vits-zh-hf-theresa").download.active)
    }

    @Test
    fun `already downloaded files with matching size are skipped`() = runBlocking {
        val fixture = Fixture()
        val dir = fixture.modelDir("vits-zh-hf-theresa")
        fixture.fileSystem.createDirectories(dir)
        // 大小与清单一致 → 应跳过；清单说 16，实际写 16
        fixture.fileSystem.write(dir / "theresa.onnx") { write(ByteArray(16)) }

        fixture.service.startDownload(
            DownloadVoiceModelRequest(
                modelId = "vits-zh-hf-theresa",
                files = listOf(
                    VoiceModelFileDto("theresa.onnx", URL_T, 16),
                    VoiceModelFileDto("lexicon.txt", URL_L, 16),
                ),
            ),
        )
        fixture.awaitIdle()

        assertEquals(listOf(URL_L), fixture.requestedUrls, "已存在且大小一致的文件不该重下")
        val download = fixture.service.status("vits-zh-hf-theresa").download
        assertTrue("theresa.onnx" in download.skippedFiles, "跳过的文件应被记录：${download.skippedFiles}")
    }

    @Test
    fun `overwrite forces re-download`() = runBlocking {
        val fixture = Fixture()
        val dir = fixture.modelDir("vits-zh-hf-theresa")
        fixture.fileSystem.createDirectories(dir)
        fixture.fileSystem.write(dir / "theresa.onnx") { write(ByteArray(16)) }

        fixture.service.startDownload(
            DownloadVoiceModelRequest(
                modelId = "vits-zh-hf-theresa",
                files = listOf(VoiceModelFileDto("theresa.onnx", URL_T, 16)),
                overwrite = true,
            ),
        )
        fixture.awaitIdle()

        assertEquals(listOf(URL_T), fixture.requestedUrls)
    }

    @Test
    fun `duplicate file names in one request are rejected`() {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.service.startDownload(
                DownloadVoiceModelRequest(
                    modelId = "vits-zh-hf-theresa",
                    files = listOf(
                        VoiceModelFileDto("lexicon.txt", URL_L, 16),
                        VoiceModelFileDto("lexicon.txt", URL_L, 16),
                    ),
                ),
            )
        }
    }

    @Test
    fun `empty file list is rejected`() {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.service.startDownload(DownloadVoiceModelRequest(modelId = "vits-zh-hf-theresa"))
        }
    }

    @Test
    fun `untrusted url is rejected before any download starts`() {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.service.startDownload(
                DownloadVoiceModelRequest(
                    modelId = "vits-zh-hf-theresa",
                    files = listOf(VoiceModelFileDto("theresa.onnx", "https://evil.com/x", 16)),
                ),
            )
        }
        assertEquals(emptyList(), fixture.requestedUrls)
    }

    @Test
    fun `delete removes the whole model directory`() {
        val fixture = Fixture()
        val dir = fixture.modelDir("vits-zh-hf-theresa")
        fixture.fileSystem.createDirectories(dir)
        fixture.fileSystem.write(dir / "theresa.onnx") { write(ByteArray(16)) }

        val status = fixture.service.deleteModel("vits-zh-hf-theresa")
        assertEquals(emptyList(), status.presentFiles)
        assertEquals(false, fixture.fileSystem.exists(dir))
    }

    @Test
    fun `platform without a runtime reports conflict instead of crashing`() {
        val service = VoiceModelDownloadService(null)
        assertFailsWith<IllegalStateException> { service.status("vits-zh-hf-theresa") }
    }

    // ------------------------------------------------------------------

    private class Fixture {
        val fileSystem: FileSystem = FileSystem.SYSTEM
        val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "voice-tts-test-${kotlin.random.Random.nextLong().toString(16)}"
        val requestedUrls = mutableListOf<String>()
        val runtime = FakeRuntime(fileSystem, root, requestedUrls)
        val service = VoiceModelDownloadService(runtime)

        fun modelDir(modelId: String): Path = root / "tts" / modelId

        /** 轮询等下载任务结束（服务内部用协程，测试里不接受 sleep 到「大概好了」）。 */
        suspend fun awaitIdle(timeoutMs: Long = 5_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!service.status(MODEL_ID).download.active) return
                delay(10)
            }
            error("下载任务在 ${timeoutMs}ms 内没有结束")
        }
    }

    private class FakeRuntime(
        private val fileSystem: FileSystem,
        override val runtimeDir: Path,
        private val requestedUrls: MutableList<String>,
    ) : OnDeviceRuntime {
        override val supported: Boolean = true
        override val serverExecutable: String? = null
        override val modelsDir: Path = runtimeDir / "models"
        override fun spawn(command: List<String>, logFile: Path): NativeProcessHandle? = null
        override fun findFreePort(): Int = 0
        override fun isPortListening(port: Int): Boolean = false

        override fun download(
            url: String,
            target: Path,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
            shouldStop: () -> Boolean,
        ) {
            requestedUrls += url
            onProgress(0, 16)
            fileSystem.createDirectories(target.parent!!)
            fileSystem.write(target, mustCreate = true) { write(ByteArray(16)) }
            onProgress(16, 16)
        }
    }

    private companion object {
        const val MODEL_ID = "vits-zh-hf-theresa"
        const val URL_T = "https://hf-mirror.com/csukuangfj/vits-zh-hf-theresa/resolve/main/theresa.onnx"
        const val URL_L = "https://hf-mirror.com/csukuangfj/vits-zh-hf-theresa/resolve/main/lexicon.txt"
    }
}
