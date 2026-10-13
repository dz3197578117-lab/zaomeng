package top.wkbin.zaomeng.platform

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlin.concurrent.Volatile

/**
 * 应用级协程未捕获异常收口。
 *
 * 为什么需要：`CoroutineScope` 上的 `launch` 一旦抛出且没有 handler，在 Android 上会走
 * 线程默认处理器（多半直接杀进程），现场只剩 logcat 里那几行、重启后就没了；而「采集关闭」
 * 时连 logcat 都不写。这里把协程未捕获异常也纳入运行日志口径（截断、脱敏同源）。
 *
 * **绝不吞异常**：记录完成后仍旧交给当前线程原有的 uncaught 处理器，
 * 与「没有装这个 handler」时的崩溃行为一致（JVM 打栈、Android 杀进程）。
 *
 * 注册方式与 [DiagnosticsSink] 同理：由聚合服务图在装配时 [attach]，未注册时退化为
 * 直接写进程内缓冲（至少诊断导出里看得到）。
 */
object RuntimeLogCoroutineFailures {

    @Volatile
    private var sourceProvider: (() -> RuntimeLogSource?)? = null

    /** 由后端服务图装配时挂接；传 null 取消。 */
    fun attach(provider: (() -> RuntimeLogSource?)?) {
        sourceProvider = provider
    }

    /** 挂在长生命周期 scope 的 CoroutineContext 上即可。 */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        report(
            origin = context[CoroutineName]?.name ?: "unnamed",
            throwable = throwable,
        )
    }

    /**
     * 记录一次协程未捕获异常，然后**照旧把它交回线程处理器**。
     *
     * @param thread 允许注入，便于测试验证「记录之外确实还转发了」。
     */
    fun report(origin: String, throwable: Throwable, thread: Thread = Thread.currentThread()) {
        runCatching {
            val source = sourceProvider?.invoke()
            if (source != null) {
                source.recordUncaught("coroutine:$origin", throwable)
            } else {
                RuntimeLogBuffer.append(
                    level = 'E',
                    tag = "Coroutine",
                    message = "未捕获协程异常（$origin）",
                    throwable = throwable,
                )
            }
        }
        // 不改变崩溃行为：继续走线程原有处理器（与没有本 handler 时完全一致）
        runCatching {
            val target = thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler()
            target?.uncaughtException(thread, throwable)
        }
    }
}
