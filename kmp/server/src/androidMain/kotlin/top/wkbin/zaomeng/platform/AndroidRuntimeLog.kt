package top.wkbin.zaomeng.platform

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.DiagnosticsBreadcrumbDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.ktor.services.PathSafety
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.time.Instant
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
 * ## 三层问题捕获
 * 1. **进程内**：持续 logcat + 全局 uncaught handler + 协程异常处理器，落盘 [logFile] 与
 *    `crashes/`（每次崩溃一个独立文件，同步写盘）；
 * 2. **进程外补捕**：Android 10+ 用 [ActivityManager.getHistoricalProcessExitInfos] 回捞
 *    「上次进程是怎么死的」——ANR / native 崩溃 / 被系统杀 / 启动期崩溃，四类第 1 层都抓不到。
 *    **这是降级信息**：native 崩溃只有信号与有限轨迹，不是完整 native 堆栈。
 * 3. **卡顿证据**：ANR 看门狗（见 [ANR_WATCHDOG_TIMEOUT_MS]）在主线程无响应时 dump 全线程栈。
 *
 * ## 采集开关
 * [setCaptureEnabled] 显式启停**持续写盘**，状态持久化在 `logs/capture-state.txt`，重启后保持。
 * 关闭采集**不清除**已采集内容，且**未捕获异常/ANR 仍然强制记录**（并在日志里标注「采集已关闭期间」），
 * 因为崩溃与卡顿正是最值钱、最不能漏的那部分证据。
 *
 * 兜底顺序（[snapshot]）：
 * 1. 落盘文件（跨进程重启保留，最完整）；
 * 2. 文件为空且采集开启 → 一次性 `logcat -d -v threadtime --pid=<pid> -t <n>`；
 * 3. 仍拿不到（logcat 不存在或被 SELinux 拒绝）→ 进程内 [RuntimeLogBuffer]；
 * 4. 都没有 → [RuntimeLogDto.SOURCE_UNAVAILABLE]，并在 [RuntimeLogDto.note] 说明。
 *
 * 容量：日志文件超过 [MAX_FILE_BYTES] 时滚动保留尾部 [KEEP_FILE_BYTES]，磁盘占用有界。
 */
object AndroidRuntimeLog : RuntimeLogSource {

    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val KEEP_FILE_BYTES = 1024L * 1024L
    private const val MAX_CRASH_FILES = 5
    private const val MAX_ANR_FILES = 5
    private const val MAX_SNAPSHOT_LINES = 5_000
    private const val MAX_CRASH_RECORDS = 8
    private const val MAX_CONTEXT_FILES = 5
    private const val MAX_CONTEXT_TRAIL_ENTRIES = 120
    private const val MAX_CONTEXT_FIELD_CHARS = 400
    private const val STACK_LIMIT_CHARS = 16_000
    private const val MAX_CAPTURE_FAILURES = 5
    private const val MAX_PROCESS_EXIT_RECORDS = 5
    private const val MAX_EXIT_TRACE_CHARS = 12_000
    private const val ANR_TRACE_CHAR_LIMIT = 12_000
    private const val ANR_LOG_FRAMES = 8

    /**
     * ANR 看门狗探测间隔：每 10s 一次「往主线程 post 一个空 Runnable」。
     *
     * 取值理由：一次 post 的开销可忽略，10s 粒度对「以秒计的主线程卡死」足够灵敏；
     * 再密也只是增加唤醒次数，不会更早发现 ANR。
     */
    const val ANR_WATCHDOG_INTERVAL_MS = 10_000L

    /**
     * ANR 看门狗判定阈值：主线程连续 5s 无法执行一个空 post，即视为卡死。
     *
     * 取值理由：系统 ANR 阈值里最严的一档是输入事件 5s（广播 10s、服务 20s）。
     * 对齐 5s 能在系统真正判 ANR / 用户强杀之前就留下现场；低于 5s 容易把
     * 正常的重布局/大 JSON 解析误判成卡死。
     */
    const val ANR_WATCHDOG_TIMEOUT_MS = 5_000L

    /** 同一段卡顿的 dump 冷却：避免主线程长时间卡死时每 10s 写一份全线程栈。 */
    private const val ANR_DUMP_COOLDOWN_MS = 60_000L

    private const val LOG_FILE_NAME = "runtime-log.txt"
    private const val CRASH_DIR_NAME = "crashes"
    private const val CONTEXT_DIR_NAME = "context"
    private const val ANR_DIR_NAME = "anr"
    private const val CAPTURE_STATE_FILE_NAME = "capture-state.txt"

    private val installed = AtomicBoolean(false)
    private val totalLines = AtomicLong(0)
    private val writeLock = Any()

    /** 串行化「启停采集 / 清除日志」这类会动文件与线程的操作。 */
    private val lifecycleLock = Any()
    private val capturing = AtomicBoolean(false)

    @Volatile
    private var captureEnabled = true

    @Volatile
    private var captureUpdatedAt: String = ""

    @Volatile
    private var activeSource: String = RuntimeLogDto.SOURCE_UNAVAILABLE

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var crashDir: File? = null

    @Volatile
    private var anrDir: File? = null

    /**
     * 面包屑上下文目录（R2）。
     *
     * 刻意**独立于** `crashes/` 与 `anr/`：上下文与崩溃堆栈各有独立配额，
     * 谁都不会把谁挤掉——「上下文被常规崩溃挤没了」正是这次要修的问题。
     */
    @Volatile
    private var contextDir: File? = null

    @Volatile
    private var stateFile: File? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var captureProcess: java.lang.Process? = null

    private var writer: OutputStreamWriter? = null
    private var writtenBytes = 0L

    override val source: String get() = activeSource

    /**
     * 进程启动时调用一次（幂等）。必须在 Application.onCreate 早期调用，否则会漏掉启动阶段日志。
     *
     * 这里同时做三件事：安装全局未捕获异常处理器、启动 ANR 看门狗、按持久化的开关决定是否
     * 启动持续采集。顺序有讲究——异常处理器必须最先装，启动期崩溃才抓得到。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        appContext = context.applicationContext
        runCatching {
            val root = validatedChild(context.applicationContext.filesDir, "zaomeng")
            val logsDir = validatedChild(root, "logs")
            logsDir.mkdirs()
            logFile = validatedChild(logsDir, LOG_FILE_NAME)
            crashDir = validatedChild(logsDir, CRASH_DIR_NAME)
            contextDir = validatedChild(logsDir, CONTEXT_DIR_NAME)
            anrDir = validatedChild(logsDir, ANR_DIR_NAME)
            stateFile = validatedChild(logsDir, CAPTURE_STATE_FILE_NAME)
            readPersistedCaptureState()
            installCrashHandler()
            installAnrWatchdog()
            // 上次进程遗留的崩溃文件：启动时点一句，避免「崩了就重启」后看不到上一次现场
            val leftover = readCrashes(MAX_CRASH_RECORDS)
            if (leftover.isNotEmpty()) {
                appendLine(
                    "--- 检测到上次运行遗留的崩溃记录 ${leftover.size} 条（已纳入诊断导出） ---",
                    force = true,
                )
            }
            if (captureEnabled) startCapture()
        }.onFailure {
            activeSource = RuntimeLogDto.SOURCE_PROCESS_BUFFER
        }
    }

    override fun snapshot(maxLines: Int): RuntimeLogDto {
        val bounded = maxLines.coerceIn(1, MAX_SNAPSHOT_LINES)
        val capturedAt = nowIsoString()
        val capture = captureState().toDto()
        val pausedNote = if (captureEnabled) "" else "采集已关闭：以下为已采集到的内容，不会继续增长。"

        val fromFile = runCatching { logFile?.let { readTailLines(it, bounded) }.orEmpty() }.getOrDefault(emptyList())
        if (fromFile.isNotEmpty()) {
            return RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
                available = true,
                capturedAt = capturedAt,
                totalLines = totalLines.get(),
                truncated = fromFile.size >= bounded,
                note = pausedNote,
                lines = fromFile,
                crashes = readCrashes(MAX_CRASH_RECORDS),
                capture = capture,
            )
        }

        // 采集关闭时不主动抓 logcat：用户明确关了采集，再去读系统缓冲只会让人以为没关掉。
        if (!captureEnabled) {
            val crashes = readCrashes(MAX_CRASH_RECORDS)
            return RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_UNAVAILABLE,
                available = false,
                capturedAt = capturedAt,
                note = "采集已关闭，且日志文件中还没有已采集的内容。开启采集后才会写入（未捕获异常与卡顿仍会记录）。",
                crashes = crashes,
                capture = capture,
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
                capture = capture,
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
                capture = capture,
            )
        }

        return RuntimeLogDto(
            source = RuntimeLogDto.SOURCE_UNAVAILABLE,
            available = false,
            capturedAt = capturedAt,
            note = "无法执行 logcat，且进程内日志缓冲为空。",
            crashes = readCrashes(MAX_CRASH_RECORDS),
            capture = capture,
        )
    }

    // ------------------------------------------------------------------
    // 采集开关（持久化）
    // ------------------------------------------------------------------

    /** 平台状态 → 契约 DTO（快照里要带出去给页面/导出）。 */
    private fun RuntimeCaptureState.toDto(): RuntimeCaptureStateDto = RuntimeCaptureStateDto(
        supported = supported,
        enabled = enabled,
        updatedAt = updatedAt,
        note = note,
    )

    override fun captureState(): RuntimeCaptureState = RuntimeCaptureState(
        supported = true,
        enabled = captureEnabled,
        updatedAt = captureUpdatedAt,
        note = if (captureEnabled) {
            "正在持续把本进程日志写入应用私有目录。"
        } else {
            "采集已关闭：已采集内容仍可查看；普通日志不再新增，但未捕获异常与卡顿仍会记录并标注「采集已关闭期间」。"
        },
    )

    /**
     * 启停持续采集。幂等：重复点同一个状态不会重复起线程，也不会写坏文件。
     *
     * 关闭时先写一条标记行再停线程（顺序反了标记就写不进去了），并保留已有内容。
     */
    override fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureState = synchronized(lifecycleLock) {
        if (captureEnabled == enabled) return captureState()
        captureEnabled = enabled
        captureUpdatedAt = nowIsoString()
        persistCaptureState(enabled, captureUpdatedAt)
        if (enabled) {
            startCapture()
            appendLine("--- 已开启运行日志持续采集（$captureUpdatedAt） ---", force = true)
        } else {
            appendLine(
                "--- 已关闭运行日志持续采集（$captureUpdatedAt）：此后的普通日志不再记录；未捕获异常与卡顿仍会记录 ---",
                force = true,
            )
            stopCapture()
        }
        captureState()
    }

    /** 读一次持久化的开关（进程启动时用）。文件缺失/损坏时回落默认「开启」。 */
    private fun readPersistedCaptureState() {
        val file = stateFile ?: return
        if (!file.exists()) return
        val values = runCatching { file.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.take(index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        captureEnabled = values["enabled"]?.equals("true", ignoreCase = true) ?: true
        captureUpdatedAt = values["updated_at"].orEmpty()
    }

    /** 持久化开关：先写临时文件再改名，避免写一半被杀导致状态文件损坏、下次读到错值。 */
    private fun persistCaptureState(enabled: Boolean, updatedAt: String) {
        val file = stateFile ?: return
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText("enabled=$enabled\nupdated_at=$updatedAt\n")
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    // ------------------------------------------------------------------
    // 清除已采集内容（只动运行日志，不碰任何业务数据）
    // ------------------------------------------------------------------

    /**
     * 清空日志文件与崩溃/卡顿记录。幂等：空文件、重复调用、采集中途调用都安全，
     * 且**不改变采集开关**——关闭采集与清除内容是两件事。
     */
    override fun clearCaptured(): RuntimeCaptureClearResult = synchronized(lifecycleLock) {
        val file = logFile
        var lines = 0L
        synchronized(writeLock) {
            // 先断开写入器，保证截断期间没有并发写；下次 appendLine 会自动重开
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
            if (file != null && file.exists() && file.length() > 0) {
                lines = runCatching { file.bufferedReader().useLines { it.count().toLong() } }.getOrDefault(0L)
                runCatching { RandomAccessFile(file, "rw").use { it.setLength(0L) } }
            }
            writtenBytes = 0L
            totalLines.set(0L)
        }
        val crashes = deleteFiles(crashDir, "crash-")
        val anrs = deleteFiles(anrDir, "anr-")
        RuntimeCaptureClearResult(
            supported = true,
            clearedLines = lines,
            clearedCrashes = crashes + anrs,
            enabled = captureEnabled,
            note = buildString {
                append("已清除运行日志与崩溃/卡顿记录；采集开关不变（当前")
                append(if (captureEnabled) "采集中" else "已停止")
                append("）。")
            },
        )
    }

    private fun deleteFiles(dir: File?, prefix: String): Int {
        val target = dir ?: return 0
        val files = runCatching {
            target.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.toList().orEmpty()
        }.getOrDefault(emptyList())
        var removed = 0
        files.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    // ------------------------------------------------------------------
    // 第 2 层：进程外补捕（ApplicationExitInfo）
    // ------------------------------------------------------------------

    override fun processExits(limit: Int): List<ProcessExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val context = appContext ?: return emptyList()
        return readProcessExits(context, limit.coerceIn(1, MAX_PROCESS_EXIT_RECORDS))
    }

    override fun processExitsNote(): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        "本机 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT} < 30）不提供 ApplicationExitInfo，" +
            "ANR/native 崩溃/被系统杀只能靠第 1 层（进程内 handler + logcat）与 ANR 看门狗。"
    } else {
        "来源：Android ApplicationExitInfo（系统侧记录）。native 崩溃只有信号、退出原因与有限轨迹，" +
            "**不是完整 native 堆栈**（本版不引入 Crashpad）。"
    }

    /**
     * 保留策略（R2 / R3）：崩溃与常规日志**分别设限**。
     *
     * 常规日志：单文件 [MAX_FILE_BYTES]（2 MiB）上限，超限裁剪到尾部 [KEEP_FILE_BYTES]；
     * 崩溃 / ANR：各自独立的目录与文件数配额（各 [MAX_CRASH_FILES] / [MAX_ANR_FILES] 个），
     * 因此**一次日志风暴不会把崩溃记录顶掉**——这正是「崩溃不见了」最常见的成因。
     */
    override fun retention(): RuntimeLogRetention {
        val log = logFile
        val crash = crashDir
        val anr = anrDir
        return RuntimeLogRetention(
            logBytes = runCatching { log?.takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L),
            logMaxBytes = MAX_FILE_BYTES,
            crashFiles = runCatching {
                crash?.listFiles { file -> file.isFile && file.name.startsWith("crash-") }?.size ?: 0
            }.getOrDefault(0),
            crashMaxFiles = MAX_CRASH_FILES,
            anrFiles = runCatching {
                anr?.listFiles { file -> file.isFile && file.name.startsWith("anr-") }?.size ?: 0
            }.getOrDefault(0),
            anrMaxFiles = MAX_ANR_FILES,
            crashRecordLimit = MAX_CRASH_RECORDS,
            note = "崩溃 / ANR 各占独立目录与文件配额（各 $MAX_CRASH_FILES / $MAX_ANR_FILES 个），" +
                "常规日志 $MAX_FILE_BYTES 字节上限、超限裁到尾部 $KEEP_FILE_BYTES 字节。" +
                "**崩溃与常规事件分别设限**，日志量再大也不会顶掉崩溃记录。",
        )
    }

    /**
     * 进程与 WebView 覆盖范围（R6），**如实标注**。
     *
     * 进程模型取自系统给的运行中进程列表（只匹配本应用包名前缀），
     * 不猜、不写死；取不到时返回 `unknown` —— 宁可写「未知」也不要假装单进程。
     */
    override fun processSnapshot(): ProcessSnapshot {
        val context = appContext
        val packageName = context?.packageName.orEmpty()
        val names = runCatching {
            if (context == null) {
                emptyList()
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses
                    ?.map { it.processName }
                    ?.filter { it == packageName || it.startsWith("$packageName:") }
                    ?.distinct()
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
        val model = when {
            names.isEmpty() -> "unknown"
            names.size == 1 -> "single-process"
            else -> "multi-process"
        }
        return ProcessSnapshot(
            processModel = model,
            processNames = names,
            // 本仓库**不使用 WebView**（已 grep android.webkit.WebView / WebViewClient /
            // onRenderProcessGone：源码、依赖与 assets 里都没有）。系统里 WebView 类始终存在，
            // 因此这里判定的是「应用是否真的用了 WebView」，而不是「设备有没有 WebView」。
            webViewPresent = false,
            webViewNote = "本仓库源码中不存在 WebView 使用（grep android.webkit.WebView / WebViewClient / " +
                "onRenderProcessGone 均无命中），因此不需要处理 onRenderProcessGone；" +
                "此判定是「应用不用 WebView」而非「设备没有 WebView」。",
            note = "进程模型来自 ActivityManager.runningAppProcesses 过滤本包名；" +
                "取不到时返回 unknown（不假装单进程）。",
        )
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun readProcessExits(context: Context, limit: Int): List<ProcessExitRecord> = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        // 系统 API 名是 getHistoricalProcessExitReasons(processName, pid, maxNum)：
        // null + 0 表示「本进程、按系统默认条数」，再用 maxNum 夹紧。
        manager.getHistoricalProcessExitReasons(null, 0, limit).orEmpty()
            .take(limit)
            .map { it.toRecord() }
    }.getOrDefault(emptyList())

    @TargetApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.toRecord(): ProcessExitRecord {
        val reasonName = exitReasonName(reason)
        return ProcessExitRecord(
            occurredAt = runCatching { Instant.ofEpochMilli(timestamp).toString() }.getOrDefault(""),
            reason = reasonName,
            reasonCode = reason,
            status = status,
            importance = importance,
            pssBytes = pss,
            rssBytes = rss,
            description = description.orEmpty().take(200),
            trace = readExitTrace(this),
            source = "android-application-exit-info:" + reasonName,
        )
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun readExitTrace(info: ApplicationExitInfo): String = runCatching {
        val stream = info.traceInputStream ?: return ""
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (output.size() < MAX_EXIT_TRACE_CHARS) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray().toString(Charsets.UTF_8).take(MAX_EXIT_TRACE_CHARS)
        }
    }.getOrDefault("")

    /**
     * 退出原因名。
     *
     * 映射表放在 commonMain（[exitReasonName]），**不再依赖编译期 SDK 里有没有对应常量**：
     * 之前只 `when` 了 API 30 就存在的常量，系统给 16（`REASON_PACKAGE_UPDATED`，应用被覆盖安装）
     * 时直接掉进 `UNKNOWN(16)`，一次正常的升级看起来像故障。
     */
    private fun exitReasonName(reason: Int): String = top.wkbin.zaomeng.platform.exitReasonName(reason)

    // ------------------------------------------------------------------
    // logcat 持续采集
    // ------------------------------------------------------------------

    private fun startCapture() {
        if (!capturing.compareAndSet(false, true)) return
        val thread = Thread(::captureLoop, "zaomeng-logcat")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        captureThread = thread
        thread.start()
    }

    private fun stopCapture() {
        capturing.set(false)
        runCatching { captureThread?.interrupt() }
        runCatching { captureProcess?.destroy() }
        captureThread = null
        captureProcess = null
        synchronized(writeLock) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun captureLoop() {
        var failures = 0
        var gaveUp = false
        while (captureEnabled && failures < MAX_CAPTURE_FAILURES) {
            var linesThisRun = 0L
            try {
                val process = ProcessBuilder(logcatCommand())
                    .redirectErrorStream(true)
                    .start()
                captureProcess = process
                if (!captureEnabled) {
                    runCatching { process.destroy() }
                    break
                }
                activeSource = RuntimeLogDto.SOURCE_LOGCAT_FILE
                appendLine("--- 运行日志采集启动（pid=${Process.myPid()}） ---")
                process.inputStream.bufferedReader().use { reader ->
                    while (captureEnabled) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                        linesThisRun++
                    }
                }
                runCatching { process.waitFor() }
            } catch (_: Throwable) {
                // 统一在下面按失败次数退避；这里不区分异常类型，采集不允许把进程带崩
            } finally {
                captureProcess = null
            }
            if (!captureEnabled) break
            // 本次跑出过日志说明 logcat 本来是好的（例如被系统回收），重新计数立即重连；
            // 一次都没跑出来才累计失败，避免无 logcat 设备上无限重启子进程。
            failures = if (linesThisRun > 0) 0 else failures + 1
            if (failures >= MAX_CAPTURE_FAILURES) {
                gaveUp = true
                break
            }
            runCatching { Thread.sleep(1_000L * failures) }
        }
        capturing.set(false)
        // 采集彻底失败（不是用户关闭）：降级到进程内缓冲，诊断里会如实标注来源
        if (gaveUp && activeSource == RuntimeLogDto.SOURCE_LOGCAT_FILE) {
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

    /**
     * 追加一行日志。
     *
     * @param force 绕过采集开关：未捕获异常与卡顿记录必须落盘（最值钱的证据，
     *   不能因为用户关了「持续采集」就丢）。
     */
    private fun appendLine(line: String, force: Boolean = false) {
        if (!force && !captureEnabled) return
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
    // 崩溃记录（独立文件 + 同步写盘）
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
            // 同步写盘（不启协程、不走缓冲）：进程马上就要死了，异步写一定丢
            runCatching { writeUncaught("uncaught-handler", thread.name, throwable) }
            // 必须继续交给系统原 handler：否则崩溃对话框/系统统计与默认退出行为都会改变
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 记录一次未捕获异常（全局 handler 或协程异常处理器）。
     *
     * 完整堆栈写**独立崩溃文件**（`crashes/crash-<时间戳>.log`，同步写盘），运行日志里只留
     * 一行标记——避免把长堆栈混进常规日志、把 2MB 的有界文件迅速冲掉。
     * 采集关闭期间同样记录，并在标记里写明「上下文不完整」。
     */
    private fun writeUncaught(origin: String, threadName: String, throwable: Throwable) {
        val captureWasEnabled = captureEnabled
        val occurredAt = nowIsoString()
        val summary = buildString {
            append(throwable::class.simpleName ?: "Throwable")
            throwable.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(300)) }
        }
        val stackTrace = runCatching { throwable.stackTraceToString() }
            .getOrDefault(throwable.toString())
            .take(STACK_LIMIT_CHARS)

        runCatching {
            val dir = crashDir ?: return@runCatching
            dir.mkdirs()
            val target = validatedChild(dir, "crash-${stampOf(occurredAt)}.log")
            target.writeText(
                buildString {
                    appendLine("occurred_at: $occurredAt")
                    appendLine("origin: $origin")
                    appendLine("thread: $threadName")
                    appendLine("capture_enabled: $captureWasEnabled")
                    appendLine("summary: $summary")
                    appendLine("stack_trace:")
                    appendLine(stackTrace)
                },
            )
            trimFiles(dir, "crash-", MAX_CRASH_FILES)
        }

        // R2：先把「崩溃之前发生了什么」同步冲刷到独立文件，再写运行日志标记。
        // 必须同步（不启协程、不走 buffered writer 的延迟 flush）：进程马上就死。
        runCatching { flushCrashContext(occurredAt = occurredAt, source = "crash:$origin") }

        val gap = if (captureWasEnabled) "" else "（采集已关闭期间，普通日志缺失，上下文不完整）"
        appendLine("--- 未捕获异常$gap：$summary（来源=$origin，线程=$threadName） ---", force = true)
    }

    /** 供协程异常处理器等非崩溃路径复用同一套落盘逻辑。 */
    override fun recordUncaught(origin: String, throwable: Throwable) {
        runCatching { writeUncaught(origin, Thread.currentThread().name, throwable) }
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
        val origin = value("origin:")
        return CrashRecordDto(
            occurredAt = value("occurred_at:"),
            thread = value("thread:").let { if (origin.isBlank()) it else "$it ($origin)" },
            summary = value("summary:").ifBlank { file.name },
            stackTrace = stackTrace,
        )
    }

    // ------------------------------------------------------------------
    // ANR 看门狗（API < 30 时最关键的替代证据）
    // ------------------------------------------------------------------

    /**
     * 后台线程按 [ANR_WATCHDOG_INTERVAL_MS] 往主线程 post 一个空 Runnable；
     * 超过 [ANR_WATCHDOG_TIMEOUT_MS] 未被执行即判定主线程卡死，dump 全线程栈。
     *
     * 为什么需要它：ApplicationExitInfo 只有 Android 10+ 才有；10 以下一旦主线程卡死，
     * 用户强杀后什么证据都不剩。这个看门狗把「卡死那一刻」的线程现场先落到盘上。
     */
    private fun installAnrWatchdog() {
        val thread = Thread({
            val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: return@Thread
            val ticked = AtomicBoolean(false)
            val probe = Runnable { ticked.set(true) }
            var lastDumpAt = 0L
            while (true) {
                runCatching {
                    ticked.set(false)
                    handler.removeCallbacks(probe)
                    handler.post(probe)
                    Thread.sleep(ANR_WATCHDOG_TIMEOUT_MS)
                    if (!ticked.get()) {
                        val now = System.currentTimeMillis()
                        // 冷却：主线程长时间卡死时不重复写整份线程栈
                        if (now - lastDumpAt >= ANR_DUMP_COOLDOWN_MS) {
                            lastDumpAt = now
                            dumpAnrTrace()
                        }
                    }
                    val remaining = ANR_WATCHDOG_INTERVAL_MS - ANR_WATCHDOG_TIMEOUT_MS
                    if (remaining > 0) Thread.sleep(remaining)
                }
            }
        }, "zaomeng-anr-watchdog")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /** dump 全线程栈：完整版写 `anr/anr-<时间戳>.log`，运行日志只留标记 + 主线程头部若干帧。 */
    private fun dumpAnrTrace() {
        val stacks = runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap())
        if (stacks.isEmpty()) return
        val occurredAt = nowIsoString()
        val mainFrames = stacks.entries.firstOrNull { it.key.name == "main" }?.value.orEmpty()
        val dump = buildString {
            appendLine("captured_at: $occurredAt")
            appendLine(
                "reason: main thread did not execute a posted probe within " +
                    "${ANR_WATCHDOG_TIMEOUT_MS}ms (watchdog interval ${ANR_WATCHDOG_INTERVAL_MS}ms)",
            )
            appendLine("thread_count: ${stacks.size}")
            appendLine("source: in-process ANR watchdog (降级证据：非系统 ANR trace)")
            appendLine("main_thread:")
            if (mainFrames.isEmpty()) appendLine("(main thread not found)")
            else mainFrames.forEach { appendLine("  $it") }
            appendLine("all_threads:")
            stacks.entries
                .filter { it.key.name != "main" }
                .sortedBy { it.key.name }
                .forEach { (thread, frames) ->
                    appendLine("--- \"${thread.name}\" state=${thread.state} priority=${thread.priority}")
                    frames.forEach { appendLine("  $it") }
                }
        }.take(ANR_TRACE_CHAR_LIMIT)

        runCatching {
            val dir = anrDir ?: return@runCatching
            dir.mkdirs()
            validatedChild(dir, "anr-${stampOf(occurredAt)}.log").writeText(dump)
            trimFiles(dir, "anr-", MAX_ANR_FILES)
        }

        // R2：卡死与崩溃同理——都需要「之前发生了什么」。同样同步写盘。
        runCatching { flushCrashContext(occurredAt = occurredAt, source = "anr:watchdog") }

        val gap = if (captureEnabled) "" else "（采集已关闭期间，普通日志缺失，上下文不完整）"
        appendLine(
            "--- 主线程卡顿$gap：watchdog ${ANR_WATCHDOG_TIMEOUT_MS}ms 内未响应，完整线程栈见 anr/ 目录 ---",
            force = true,
        )
        mainFrames.take(ANR_LOG_FRAMES).forEach { appendLine("    at $it", force = true) }
    }

    // ------------------------------------------------------------------
    // 面包屑上下文（R2）：崩溃 / ANR 时同步冲刷
    // ------------------------------------------------------------------

    /**
     * 把 `ClientDiagnostics` 里的面包屑**同步**写进独立上下文文件。
     *
     * 只做一次，不做定期落盘：面包屑是为崩溃服务的，高频写盘会为了「观测卡顿」制造卡顿。
     *
     * 文件格式是**行式结构化**的（`trail` 段是 TAB 分隔的四元组），
     * 这样诊断侧能把它还原成结构化面包屑挂到对应崩溃条目上，而不是一段没法对齐的自由文本。
     */
    private fun flushCrashContext(occurredAt: String, source: String) {
        val dir = contextDir ?: return
        val flushedAt = nowIsoString()
        val dropped = runCatching { ClientDiagnostics.droppedCount() }.getOrDefault(0L)
        val text = buildString {
            appendLine("stamp: ${stampOf(occurredAt)}")
            appendLine("occurred_at: $occurredAt")
            appendLine("flushed_at: $flushedAt")
            appendLine("source: $source")
            appendLine("capture_enabled: $captureEnabled")
            appendLine("dropped_events: $dropped")
            appendLine("trail:")
            runCatching { ClientDiagnostics.trailEntries() }
                .getOrDefault(emptyList())
                .takeLast(MAX_CONTEXT_TRAIL_ENTRIES)
                .forEach { entry ->
                    appendLine(
                        listOf(
                            entry.at,
                            entry.kind,
                            entry.label.replace('\t', ' ').take(MAX_CONTEXT_FIELD_CHARS),
                            entry.detail.replace('\t', ' ').replace('\n', ' ').take(MAX_CONTEXT_FIELD_CHARS),
                        ).joinToString("\t"),
                    )
                }
            appendLine("summary:")
            runCatching { ClientDiagnostics.featureAggregates() }
                .getOrDefault(emptyList())
                .take(20)
                .forEach { item ->
                    appendLine(
                        "feature\t${item.feature}/${item.action}\ttotal=${item.total}\tok=${item.okCount}" +
                            "\tdegraded=${item.degradedCount}\tfailed=${item.failedCount}" +
                            "\tmax=${item.maxDurationMs}ms\tlast=${item.lastAt}",
                    )
                }
            runCatching { ClientDiagnostics.recompositionStats() }
                .getOrDefault(emptyList())
                .take(10)
                .forEach { item ->
                    appendLine("recomposition\t${item.name}\tcount=${item.count}\tmax_per_frame=${item.maxPerFrame}")
                }
            runCatching { ClientDiagnostics.imageRequestStats() }
                .getOrDefault(emptyList())
                .filter { it.duplicateRequests > 0 }
                .take(10)
                .forEach { item ->
                    appendLine("image_duplicate\t${item.url}\trequests=${item.requests}\tduplicates=${item.duplicateRequests}")
                }
        }
        dir.mkdirs()
        validatedChild(dir, "context-${stampOf(occurredAt)}.log").writeText(text)
        trimFiles(dir, "context-", MAX_CONTEXT_FILES)
    }

    /** 读取历史冲刷下来的上下文（最近一次在前），供诊断把它们挂到对应崩溃/ANR 条目上。 */
    override fun crashContexts(limit: Int): List<CrashContextRecord> {
        val dir = contextDir ?: return emptyList()
        val files = runCatching {
            dir.listFiles { file -> file.isFile && file.name.startsWith("context-") }
                ?.sortedByDescending { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
        return files.take(limit.coerceIn(1, MAX_CONTEXT_FILES)).mapNotNull { file ->
            runCatching { parseContext(file) }.getOrNull()
        }
    }

    private fun parseContext(file: File): CrashContextRecord {
        val lines = file.readText().lineSequence().toList()
        fun value(prefix: String): String =
            lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()

        val trailStart = lines.indexOfFirst { it.trim() == "trail:" }
        val summaryStart = lines.indexOfFirst { it.trim() == "summary:" }
        val trailLines = if (trailStart >= 0) {
            lines.drop(trailStart + 1).takeWhile { it.trim() != "summary:" }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val summaryLines = if (summaryStart >= 0) {
            lines.drop(summaryStart + 1).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val breadcrumbs = trailLines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            DiagnosticsBreadcrumbDto(
                at = parts[0].trim(),
                source = parts[1].trim().ifBlank { "crash-context" },
                label = parts[2],
                detail = parts.getOrNull(3).orEmpty(),
            )
        }
        return CrashContextRecord(
            occurredAt = value("occurred_at:"),
            stamp = value("stamp:"),
            source = value("source:"),
            droppedEvents = value("dropped_events:").toLongOrNull() ?: 0L,
            captureEnabled = value("capture_enabled:").equals("true", ignoreCase = true),
            breadcrumbs = breadcrumbs,
            summaryLines = summaryLines,
        )
    }

    // ------------------------------------------------------------------
    // 文件工具
    // ------------------------------------------------------------------

    /** 新路径统一走 [PathSafety] 校验，避免任何外部可控片段进入文件路径。 */
    private fun validatedChild(parent: File, name: String): File =
        File(parent, PathSafety.sanitizePathComponent(name, "runtimeLogFileName"))

    /** 时间戳转文件名片段：ISO 字符串里的 `:`/`.` 在部分文件系统上是非法字符。 */
    /** 与诊断侧的 [crashContextStampOf] 同源：一处定义、两处使用，避免漂移。 */
    private fun stampOf(occurredAt: String): String = crashContextStampOf(occurredAt)

    private fun trimFiles(dir: File, prefix: String, keep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sortedBy { it.name }.orEmpty()
        if (files.size <= keep) return
        files.dropLast(keep).forEach { runCatching { it.delete() } }
    }

    private val CANDIDATE_LOGCAT_BINARIES = listOf("/system/bin/logcat", "/system/xbin/logcat")
}
