package top.wkbin.zaomeng.platform

import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM / 桌面端侧推理运行时。
 *
 * 桌面端不能把 ELF/AArch64 产物拿来跑，所以这里不改用 `jniLibs` 那套，而是按顺序查找
 * 本机可用的 `llama-server`：
 * 1. 环境变量 `LLAMA_SERVER_BIN`（显式指定，优先级最高）；
 * 2. 仓库根下 `native-build/**/bin/llama-server[.exe]`（自行编译的桌面版）；
 * 3. 系统 `PATH`。
 *
 * 找不到时 [serverExecutable] 为 null，UI 显示「未检测到 llama-server」并给出安装提示，
 * 不影响其它功能。这条路径的主要用途是：在电脑上先把「端侧模型管理 + 本地端点」整条链路跑通，
 * 再回到手机上验证。
 */
class JvmOnDeviceRuntime(dataRoot: Path) : OnDeviceRuntime {

    private val rootDir: Path = (dataRoot.parent ?: dataRoot) / "zaomeng-ondevice"

    override val supported: Boolean = true

    override val modelsDir: Path = rootDir / "models"

    override val runtimeDir: Path = rootDir

    override val serverExecutable: String? by lazy { discoverExecutable() }

    override val unavailableReason: String?
        get() = if (serverExecutable != null) {
            null
        } else {
            "未在本机找到 llama-server。请设置环境变量 LLAMA_SERVER_BIN 指向可执行文件，" +
                "或把它放进 PATH（可从 llama.cpp 官方 Release 下载）。"
        }

    override fun spawn(command: List<String>, logFile: Path): NativeProcessHandle? {
        if (command.isEmpty()) return null
        return runCatching {
            val log = File(logFile.toString())
            log.parentFile?.mkdirs()
            val workDir = File(runtimeDir.toString()).apply { mkdirs() }
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            runCatching { process.outputStream.close() }
            pumpOutputToLog(process, log)
            JvmNativeProcess(process)
        }.getOrNull()
    }

    override fun findFreePort(): Int? = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrNull()

    override fun isPortListening(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun download(
        url: String,
        target: Path,
        onProgress: (Long, Long) -> Unit,
        shouldStop: () -> Boolean,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败：HTTP $code ${connection.responseMessage.orEmpty()}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val part = File("${target}.part")
            part.parentFile?.mkdirs()
            var downloaded = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (shouldStop()) throw IllegalStateException("已取消")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - reported >= PROGRESS_STEP_BYTES) {
                            reported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    out.flush()
                }
            }
            onProgress(downloaded, total)
            val destination = File(target.toString())
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun discoverExecutable(): String? {
        // 1. 显式指定
        System.getenv("LLAMA_SERVER_BIN")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { return it.absolutePath }

        // 2. 仓库内自编译产物
        findRepoRoot(File(System.getProperty("user.dir")))?.let { repoRoot ->
            val nativeBuild = File(repoRoot, "native-build")
            val dirs = buildList {
                add(nativeBuild)
                nativeBuild.listFiles()?.forEach { add(it) }
            }
            dirs.forEach { dir ->
                LLAMA_SERVER_NAMES.forEach { name ->
                    listOf(File(dir, name), File(dir, "bin/$name"))
                        .firstOrNull { it.isFile }
                        ?.let { return it.absolutePath }
                }
            }
        }

        // 3. 系统 PATH
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .forEach { entry ->
                LLAMA_SERVER_NAMES.forEach { name ->
                    File(entry, name).takeIf { it.isFile }?.let { return it.absolutePath }
                }
            }

        return null
    }

    private fun pumpOutputToLog(process: Process, log: File) {
        val input = process.inputStream
        val thread = Thread({
            runCatching {
                FileOutputStream(log, true).use { out ->
                    val buffer = ByteArray(LOG_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
            }
        }, "zaomeng-ondevice-log")
        thread.isDaemon = true
        thread.start()
    }

    private companion object {
        val LLAMA_SERVER_NAMES = listOf("llama-server")
        const val PORT_PROBE_TIMEOUT_MS = 300
        const val LOG_BUFFER_BYTES = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val PROGRESS_STEP_BYTES = 1024L * 1024L
        const val USER_AGENT = "zaomeng-ondevice/1.0 (+llama.cpp)"
    }
}

private class JvmNativeProcess(private val process: Process) : NativeProcessHandle {
    private val killed = AtomicBoolean(false)

    override val pid: Long
        get() = runCatching { process.pid() }.getOrDefault(-1L)

    override val isAlive: Boolean
        get() = runCatching { process.isAlive }.getOrDefault(false)

    override fun destroy() {
        if (!killed.compareAndSet(false, true)) return
        runCatching { process.destroy() }
        val reaper = Thread {
            val exited = runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)
            if (!exited) runCatching { process.destroyForcibly() }
        }
        reaper.isDaemon = true
        reaper.start()
    }
}
