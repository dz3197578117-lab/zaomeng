package top.wkbin.zaomeng.platform

import android.content.Context
import android.os.Build
import okio.Path
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
 * Android 端侧推理运行时。
 *
 * 可执行文件必须位于 `applicationInfo.nativeLibraryDir`（即 `/data/app/<pkg>/lib/<abi>/`）：
 * Android 从 API 29 起只允许从这里 `execve`，把可执行文件放进 `filesDir` 会直接 `EACCES`。
 * 因此构建脚本把交叉编译出来的 `llama-server` 重命名为 `libllama-server.so` 放进
 * `jniLibs/arm64-v8a/`，并在 app 模块开启 `packaging.jniLibs.useLegacyPackaging = true`
 * 让 AGP 在安装期把它解压到 native lib 目录。
 */
class AndroidOnDeviceRuntime(context: Context) : OnDeviceRuntime {

    private val appContext = context.applicationContext

    override val supported: Boolean = true

    override val modelsDir: Path =
        File(appContext.filesDir, "$ROOT_DIR_NAME/models").toOkioPath()

    override val runtimeDir: Path =
        File(appContext.filesDir, ROOT_DIR_NAME).toOkioPath()

    override val serverExecutable: String? by lazy {
        val dir = appContext.applicationInfo?.nativeLibraryDir ?: return@lazy null
        val candidate = File(dir, EXECUTABLE_NAME)
        if (!candidate.isFile) return@lazy null
        runCatching { candidate.setExecutable(true, false) }
        candidate.absolutePath
    }

    override val unavailableReason: String?
        get() {
            if (serverExecutable != null) return null
            val supportedAbis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
            return if (supportedAbis.none { it == REQUIRED_ABI }) {
                "本机 CPU 架构为 ${supportedAbis.joinToString("/").ifBlank { "未知" }}，" +
                    "端侧推理目前只打包了 $REQUIRED_ABI。"
            } else {
                "APK 内没有找到 $EXECUTABLE_NAME，可能是这份安装包未包含端侧推理产物。"
            }
        }

    override fun spawn(command: List<String>, logFile: Path): NativeProcessHandle? {
        if (command.isEmpty()) return null
        return runCatching {
            val log = File(logFile.toString())
            log.parentFile?.mkdirs()
            val workDir = File(runtimeDir.toString()).apply { mkdirs() }
            val builder = ProcessBuilder(command)
                .directory(workDir)
                // stderr 并入 stdout，日志泵只需读一路
                .redirectErrorStream(true)
            // 让 linker 能找到「与可执行文件同目录」的非系统依赖（例如 libOpenCL.so）。
            //
            // 为什么必须显式设：Android 的 linker **不搜索可执行文件所在目录**，而应用的 linker
            // namespace 只对 dlopen 生效；子进程继承的 LD_LIBRARY_PATH 里并没有原生库目录。
            // 于是 NEEDED 里只要出现一个非系统库，整个可执行文件就加载失败（2.1.16/2.1.17 的真机实测）：
            //   CANNOT LINK EXECUTABLE ".../lib/arm64/libllama-server.so":
            //   library "libOpenCL.so" not found: needed by main executable
            // 2.1.15 没暴露这个问题，只是因为那版产物 NEEDED 只有 libm/libdl/libc（都是系统库）。
            // 注意：库确实被解压到了 nativeLibraryDir（androidApp 开了 useLegacyPackaging），
            // 缺的只是搜索路径。
            builder.environment()["LD_LIBRARY_PATH"] = librarySearchPath()
            val process = builder.start()
            // llama-server 不读 stdin，但保持管道打开会让某些实现阻塞在 read 上，主动关闭更安全
            runCatching { process.outputStream.close() }
            pumpOutputToLog(process, log)
            AndroidNativeProcess(process)
        }.getOrNull()
    }

    /**
     * 子进程的动态库搜索路径：原生库目录在前，保留调用方原有的 `LD_LIBRARY_PATH`。
     *
     * 目录不存在时返回原有的值（可能是空串），不制造一个指向空目录的搜索项。
     */
    private fun librarySearchPath(): String {
        val nativeDir = appContext.applicationInfo?.nativeLibraryDir?.takeIf { it.isNotBlank() }
        val inherited = System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }
        return listOfNotNull(nativeDir, inherited).joinToString(":")
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
            // HuggingFace 的 /resolve/ 直链是 302 → CDN，必须跟随
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
                        // 阻塞式读没法靠协程取消打断，只能每轮自查
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

    /**
     * 把子进程输出持续追加到日志文件。
     *
     * 必须一直读到 EOF：管道缓冲（约 64KB）写满后子进程会阻塞在写日志上，
     * 表现为「模型加载到 60% 就不动了」。守护线程随进程结束自然退出。
     */
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
        const val ROOT_DIR_NAME = "zaomeng-ondevice"
        const val EXECUTABLE_NAME = "libllama-server.so"
        const val REQUIRED_ABI = "arm64-v8a"
        const val PORT_PROBE_TIMEOUT_MS = 300
        const val LOG_BUFFER_BYTES = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val PROGRESS_STEP_BYTES = 1024L * 1024L
        const val USER_AGENT = "zaomeng-ondevice/1.0 (+llama.cpp)"
    }
}

private class AndroidNativeProcess(private val process: Process) : NativeProcessHandle {
    private val killed = AtomicBoolean(false)

    /**
     * `Process.pid()`（Java 9 引入）**没有**出现在 android.jar 里，编译期直接引用会报 unresolved，
     * 只能反射取。取不到就返回 -1：PID 只用于界面展示，不影响启停。
     */
    override val pid: Long
        get() = runCatching {
            process.javaClass.getMethod("pid").invoke(process) as? Long ?: -1L
        }.getOrDefault(-1L)

    override val isAlive: Boolean
        get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.isAlive
            } else {
                // API 24/25 没有 isAlive()：exitValue() 抛异常说明进程还在跑
                runCatching { process.exitValue() }.isFailure
            }
        }.getOrDefault(false)

    override fun destroy() {
        if (!killed.compareAndSet(false, true)) return
        runCatching { process.destroy() }
        // 给 2 秒优雅退出时间，仍在则强杀，避免残留进程占着内存和端口
        val reaper = Thread {
            if (awaitExit(GRACEFUL_EXIT_MS)) return@Thread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { process.destroyForcibly() }
            } else {
                // API 24/25 没有 destroyForcibly()，退化为再次 destroy
                runCatching { process.destroy() }
            }
        }
        reaper.isDaemon = true
        reaper.start()
    }

    private fun awaitExit(timeoutMs: Long): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(true)
        } else {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { process.exitValue() }.isSuccess) return true
                Thread.sleep(POLL_INTERVAL_MS)
            }
            false
        }

    private companion object {
        const val GRACEFUL_EXIT_MS = 2_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
