package top.wkbin.zaomeng.platform

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程最早期安装的崩溃兜底记录器。
 *
 * ## 为什么需要它
 *
 * 2.4.0 在真机（realme RMX5010 / Android 17）覆盖安装后连续崩了 3 次，但导出的诊断包里
 * `crashEntries=0`、`crashBytes=0`，全文一条 `FATAL EXCEPTION` 都没有——**崩溃现场完全丢失**。
 *
 * 完整的捕获链在 [AndroidRuntimeLog.install] 里（北京时间线：它装在 `Application.onCreate`），
 * 但那几次崩溃发生在更早的阶段（Application 类初始化 / attachBaseContext），handler 还没装上。
 *
 * 这里只依赖 `filesDir`，**不碰 Koin、协程、日志框架、SharedPreferences**，保证「Application
 * 刚起来就崩」也能留下一份最小现场（时间、线程、异常摘要、堆栈）。
 *
 * ## 与完整链的协作
 *
 * 完整链装好后会调用 [handOverToFullHandler]，本记录器随即让位，避免同一次崩溃写两份。
 * 文件名同样以 `crash-` 开头，因此 `AndroidRuntimeLog.readCrashes()` 能直接解析并纳入诊断导出。
 *
 * 格式与 `AndroidRuntimeLog.parseCrash` 严格对齐：`occurred_at / origin / thread / summary / stack_trace`。
 */
object EarlyCrashRecorder {

    private const val CRASH_DIR = "zaomeng/logs/crashes"
    private const val MAX_CRASH_FILES = 5
    private const val STACK_LIMIT_CHARS = 64_000

    private val fullHandlerInstalled = AtomicBoolean(false)

    /** 在 `Application.attachBaseContext()` 中调用。幂等。 */
    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
    }

    /** 完整捕获链（含崩溃前上下文冲刷）装好后调用，本记录器不再重复写盘。 */
    internal fun handOverToFullHandler() {
        fullHandlerInstalled.set(true)
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (!fullHandlerInstalled.get()) {
                // 同步写盘：进程马上就要死了，绝不能走协程或缓冲
                runCatching { write(context, thread, throwable) }
            }
            // 必须继续交给系统原 handler：否则崩溃对话框与系统的崩溃统计都会改变
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory || !dir.canWrite()) return

        val now = Date()
        val occurredAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(now)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)

        val summary = buildString {
            append(throwable::class.java.simpleName.ifBlank { "Throwable" })
            throwable.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(300)) }
        }
        val stackTrace = runCatching { throwable.stackTraceToString() }
            .getOrDefault(throwable.toString())
            .take(STACK_LIMIT_CHARS)

        File(dir, "crash-$stamp-early.log").writeText(
            buildString {
                appendLine("occurred_at: $occurredAt")
                appendLine("origin: early-handler")
                appendLine("thread: ${thread.name}")
                appendLine("capture_enabled: unknown")
                appendLine("summary: $summary")
                appendLine("stack_trace:")
                appendLine(stackTrace)
            },
        )
        trim(dir)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }
}
