package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android 运行日志采集器。
 *
 * 为什么不是「导出时读一次 logcat」：`logcat -d` 只能拿到系统缓冲里还留着的那点内容，
 * 应用被杀/重启、或系统缓冲被其他应用挤掉之后，出事前后的上下文就没了。这里在进程启动
 * 时就挂一条 `logcat -v threadtime --pid=<本进程>` 的流，把**本应用全部 tag**
 * （包括 native llama.cpp、OkHttp、Compose、AndroidRuntime 崩溃）持续落到应用私有目录的
 * 有界文件里；应用内「运行日志」页与诊断导出都读它。
 *
 * 读取自己的日志不需要 READ_LOGS：Android 4.1 起应用只能读本 uid 的日志，`--pid` 正是本进程。
 *
 * 兜底顺序（[snapshot]）：
 * 1. 落盘文件（跨进程重启保留，最完整）；
 * 2. 文件为空 → 一次性 `logcat -d -v threadtime --pid=<pid> -t <n>`；
 * 3. 仍拿不到（logcat 不存在或被 SELinux 拒绝）→ 进程内 [RuntimeLogBuffer]；
 * 4. 都没有 → [RuntimeLogDto.SOURCE_UNAVAILABLE]，并在 [RuntimeLogDto.note] 说明。
 *
 * 崩溃：安装 uncaught exception handler，把堆栈单独写成崩溃文件（保留最近 [MAX_CRASH_FILES] 个），
 * 并在运行日志里留一条标记。写完仍然调用系统原 handler，不改变崩溃后的默认行为。
 *
 * 容量：日志文件超过 [MAX_FILE_BYTES] 时滚动保留尾部 [KEEP_FILE_BYTES]，磁盘占用有界。
 */
object AndroidRuntimeLog : RuntimeLogSource {

    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val KEEP_FILE_BYTES = 1024L * 1024L
    private const val MAX_CRASH_FILES = 5
    private const val MAX_SNAPSHOT_LINES = 5_000
    private const val MAX_CRASH_RECORDS = 8
    private const val STACK_LIMIT_CHARS = 16_000
    private const val MAX_CAPTURE_FAILURES = 5

    private val installed = AtomicBoolean(false)
    private val totalLines = AtomicLong(0)
    private val writeLock = Any()

    @Volatile
    private var activeSource: String = RuntimeLogDto.SOURCE_UNAVAILABLE

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var crashDir: File? = null

    private var writer: OutputStreamWriter? = null
    private var writtenBytes = 0L

    override val source: String get() = activeSource

    /**
     * 进程启动时调用一次（幂等）。必须在 Application.onCreate 早期调用，否则会漏掉启动阶段日志。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val root = File(context.applicationContext.filesDir, "zaomeng/logs")
            root.mkdirs()
            logFile = File(root, "runtime-log.txt")
            crashDir = File(root, "crashes")
            installCrashHandler()
            startCapture()
        }.onFailure {
            activeSource = RuntimeLogDto.SOURCE_PROCESS_BUFFER
        }
    }

    override fun snapshot(maxLines: Int): RuntimeLogDto {
        val bounded = maxLines.coerceIn(1, MAX_SNAPSHOT_LINES)
        val capturedAt = nowIsoString()

        val fromFile = runCatching { logFile?.let { readTailLines(it, bounded) }.orEmpty() }.getOrDefault(emptyList())
        if (fromFile.isNotEmpty()) {
            return RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
                available = true,
                capturedAt = capturedAt,
                totalLines = totalLines.get(),
                truncated = fromFile.size >= bounded,
                lines = fromFile,
                crashes = readCrashes(MAX_CRASH_RECORDS),
            )
        }

        val dumped = runCatching { dumpOnce(bounded) }.getOrDefault(emptyList())
        if (dumped.isNotEmpty()) {
            return RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_DUMP,
                available = true,
                capturedAt = capturedAt,
                totalLines = dumped.size.toLong(),
                truncated = dumped.size >= bounded,
                note = "落盘文件为空，改用一次性 logcat 抓取（进程重启前的日志已不在系统缓冲中）。",
                lines = dumped,
                crashes = readCrashes(MAX_CRASH_RECORDS),
            )
        }

        val buffered = runCatching { RuntimeLogBuffer.tail(bounded) }.getOrDefault(emptyList())
        if (buffered.isNotEmpty()) {
            return RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_PROCESS_BUFFER,
                available = true,
                capturedAt = capturedAt,
                totalLines = RuntimeLogBuffer.totalAppended(),
                truncated = buffered.size >= bounded,
                note = "logcat 不可用，仅返回进程内日志缓冲（不含第三方库与 native 输出）。",
                lines = buffered,
                crashes = readCrashes(MAX_CRASH_RECORDS),
            )
        }

        return RuntimeLogDto(
            source = RuntimeLogDto.SOURCE_UNAVAILABLE,
            available = false,
            capturedAt = capturedAt,
            note = "无法执行 logcat，且进程内日志缓冲为空。",
            crashes = readCrashes(MAX_CRASH_RECORDS),
        )
    }

    // ------------------------------------------------------------------
    // logcat 持续采集
    // ------------------------------------------------------------------

    private fun startCapture() {
        val thread = Thread(::captureLoop, "zaomeng-logcat")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    private fun captureLoop() {
        var failures = 0
        while (failures < MAX_CAPTURE_FAILURES) {
            var linesThisRun = 0L
            try {
                val process = ProcessBuilder(logcatCommand())
                    .redirectErrorStream(true)
                    .start()
                activeSource = RuntimeLogDto.SOURCE_LOGCAT_FILE
                appendLine("--- 运行日志采集启动（pid=${Process.myPid()}） ---")
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                        linesThisRun++
                    }
                }
                runCatching { process.waitFor() }
            } catch (_: Throwable) {
                // 统一在下面按失败次数退避；这里不区分异常类型，采集不允许把进程带崩
            }
            // 本次跑出过日志说明 logcat 本来是好的（例如被系统回收），重新计数立即重连；
            // 一次都没跑出来才累计失败，避免无 logcat 设备上无限重启子进程。
            failures = if (linesThisRun > 0) 0 else failures + 1
            if (failures >= MAX_CAPTURE_FAILURES) break
            runCatching { Thread.sleep(1_000L * failures) }
        }
        // 采集彻底失败：降级到进程内缓冲，诊断里会如实标注来源
        if (activeSource == RuntimeLogDto.SOURCE_LOGCAT_FILE) {
            activeSource = RuntimeLogDto.SOURCE_PROCESS_BUFFER
        }
    }

    private fun logcatCommand(): List<String> {
        val binary = CANDIDATE_LOGCAT_BINARIES.firstOrNull { File(it).exists() } ?: "logcat"
        return listOf(binary, "-v", "threadtime", "--pid=${Process.myPid()}")
    }

    private fun dumpOnce(maxLines: Int): List<String> {
        val process = ProcessBuilder(logcatCommand() + listOf("-d", "-t", maxLines.toString()))
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.toList()
        }
        runCatching { process.waitFor() }
        return lines.takeLast(maxLines)
    }

    // ------------------------------------------------------------------
    // 落盘与滚动
    // ------------------------------------------------------------------

    private fun appendLine(line: String) {
        val file = logFile ?: return
        synchronized(writeLock) {
            runCatching {
                var w = writer
                if (w == null) {
                    w = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
                    writer = w
                    writtenBytes = file.length()
                }
                w.write(line)
                w.write("\n")
                // 每行 flush：崩溃/被杀时最后几行不会丢在缓冲区里——那正是最需要的那几行
                w.flush()
                writtenBytes += line.length + 1L
                totalLines.incrementAndGet()
                if (writtenBytes > MAX_FILE_BYTES) rotateLocked(file)
            }.onFailure {
                writer = null
            }
        }
    }

    private fun rotateLocked(file: File) {
        runCatching {
            writer?.close()
            writer = null
            if (!file.exists()) return
            val keep = tailBytes(file, KEEP_FILE_BYTES)
            file.writeBytes(keep)
            writtenBytes = keep.size.toLong()
        }
    }

    private fun readTailLines(file: File, maxLines: Int): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        // 行可能很长：按「行数 × 单行预算」估算要回读的字节，再对齐到行首
        val budget = (maxLines.toLong() * 256L).coerceAtLeast(64L * 1024L)
        val bytes = tailBytes(file, budget)
        if (bytes.isEmpty()) return emptyList()
        return bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(maxLines)
    }

    /** 读文件尾部 [maxBytes]，并对齐到行首，避免从半行开始。 */
    private fun tailBytes(file: File, maxBytes: Long): ByteArray {
        val length = file.length()
        if (length <= 0L) return ByteArray(0)
        if (length <= maxBytes) return file.readBytes()
        RandomAccessFile(file, "r").use { raf ->
            val offset = length - maxBytes
            raf.seek(offset)
            val bytes = ByteArray(maxBytes.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = raf.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            val slice = if (read == bytes.size) bytes else bytes.copyOf(read)
            val firstNewline = slice.indexOf('\n'.code.toByte())
            return if (firstNewline in 0 until slice.size - 1) slice.copyOfRange(firstNewline + 1, slice.size) else slice
        }
    }

    // ------------------------------------------------------------------
    // 崩溃记录
    // ------------------------------------------------------------------

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashForwardingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashForwardingHandler(previous))
    }

    private class CrashForwardingHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching { writeCrash(thread, throwable) }
            // 必须继续交给系统原 handler：否则崩溃对话框/系统统计与默认退出行为都会改变
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val dir = crashDir ?: return
        val occurredAt = nowIsoString()
        val summary = buildString {
            append(throwable::class.simpleName ?: "Throwable")
            throwable.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(300)) }
        }
        val stackTrace = runCatching { throwable.stackTraceToString() }
            .getOrDefault(throwable.toString())
            .take(STACK_LIMIT_CHARS)

        runCatching {
            dir.mkdirs()
            val stamp = occurredAt.replace(':', '-').replace('.', '-')
            File(dir, "crash-$stamp.txt").writeText(
                buildString {
                    appendLine("occurred_at: $occurredAt")
                    appendLine("thread: ${thread.name}")
                    appendLine("summary: $summary")
                    appendLine("stack_trace:")
                    appendLine(stackTrace)
                },
            )
            trimCrashFiles(dir)
        }
        appendLine("--- 未捕获异常：$summary（thread=${thread.name}） ---")
    }

    private fun trimCrashFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.sortedBy { it.name }.orEmpty()
        if (files.size <= MAX_CRASH_FILES) return
        files.dropLast(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }

    private fun readCrashes(limit: Int): List<CrashRecordDto> {
        val dir = crashDir ?: return emptyList()
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()
        }.getOrDefault(emptyList())
        return files.take(limit).mapNotNull { file ->
            runCatching { parseCrash(file) }.getOrNull()
        }
    }

    private fun parseCrash(file: File): CrashRecordDto {
        val text = file.readText()
        val lines = text.lineSequence().toList()
        fun value(prefix: String): String =
            lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
        val stackStart = lines.indexOfFirst { it.trim() == "stack_trace:" }
        val stackTrace = if (stackStart >= 0) lines.drop(stackStart + 1).joinToString("\n") else text
        return CrashRecordDto(
            occurredAt = value("occurred_at:"),
            thread = value("thread:"),
            summary = value("summary:").ifBlank { file.name },
            stackTrace = stackTrace,
        )
    }

    private val CANDIDATE_LOGCAT_BINARIES = listOf("/system/bin/logcat", "/system/xbin/logcat")
}
