@file:OptIn(ExperimentalTime::class)

package top.wkbin.zaomeng.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 进程内运行日志环形缓冲。
 *
 * 存在意义：logcat 是系统的共享缓冲，会被其他应用/系统日志挤掉，普通用户也读不到；
 * 崩溃后的进程重启更会让内存上下文全部消失。这里把 [PlatformLog] / [ClientLog] 的每一条
 * 写入（时间、级别、tag、消息、异常堆栈）留在进程内的有界环里，诊断导出与应用内
 * 「运行日志」页都能随时取到最近一段，且不会无界吃内存。
 *
 * - 有界：仅保留最近 [maxLines] 行，单条消息截断到 [MESSAGE_LIMIT] 字符，异常堆栈截断到
 *   [STACK_LIMIT] 行，防止一条故障把整环撑爆。
 * - 物理行存储：多行堆栈按物理行入环，`tail` 返回的就是真实行数，与 logcat 行数语义一致。
 * - 线程安全：日志可能来自任意线程（后端协程、UI、前台服务、崩溃线程）。
 * - 只存不写盘：日志路径不做阻塞 IO；落盘由平台侧（Android logcat 采集器）负责。
 */
object RuntimeLogBuffer {
    /** 默认保留行数：约 2000 行，足够覆盖一次典型会话/蒸馏的关键路径。 */
    const val DEFAULT_MAX_LINES = 2_000

    /** 单条消息截断上限（字符）。超长原文/模型输出不会整段进环。 */
    private const val MESSAGE_LIMIT = 2_000

    /** 异常堆栈保留行数上限：尾部最近的栈帧通常已足够定位。 */
    private const val STACK_LIMIT = 60

    private val mutex = Mutex()
    private val ring = ArrayDeque<String>()
    private var maxLines = DEFAULT_MAX_LINES
    private var totalAppended = 0L

    /**
     * 调整容量。仅在测试或启动期配置时调用；运行期改大不影响已有内容，改小会在下次写入时裁剪。
     */
    fun configure(maxLines: Int) {
        val bounded = maxLines.coerceAtLeast(1)
        runBlocking {
            mutex.withLock {
                this@RuntimeLogBuffer.maxLines = bounded
                while (ring.size > bounded) ring.removeFirst()
            }
        }
    }

    /**
     * 追加一条日志。级别用单个字符（D/I/W/E）以贴合 logcat threadtime 风格。
     */
    fun append(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val ts = Clock.System.now().toString()
        val cleanTag = tag.trim().take(48).ifBlank { "app" }
        val cleanMsg = message.take(MESSAGE_LIMIT)
        val header = "$ts ${level}/$cleanTag: $cleanMsg"
        val block = if (throwable != null) {
            val stack = throwable.stackTraceText().takeLastLines(STACK_LIMIT)
            if (stack.isBlank()) header else buildString {
                append(header)
                append('\n')
                append("E: ").append(throwable::class.simpleName ?: "Throwable")
                throwable.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(MESSAGE_LIMIT)) }
                append('\n')
                append(stack)
            }
        } else {
            header
        }
        val lines = block.split('\n')
        runBlocking {
            mutex.withLock {
                totalAppended += lines.size
                lines.forEach { line ->
                    ring.addLast(line)
                    while (ring.size > maxLines) ring.removeFirst()
                }
            }
        }
    }

    /** 取最近 [maxLines] 行（物理行，按时间正序）。 */
    fun tail(maxLines: Int = DEFAULT_MAX_LINES): List<String> {
        val bounded = maxLines.coerceAtLeast(1)
        return runBlocking {
            mutex.withLock { ring.toList().takeLast(bounded) }
        }
    }

    /** 取最近 [maxLines] 行并拼成单个文本（行间 \n，末尾不带多余换行）。 */
    fun text(maxLines: Int = DEFAULT_MAX_LINES): String = tail(maxLines).joinToString("\n")

    /** 累计写入的物理行数（含已被裁剪的），用于诊断「丢了多少」。 */
    fun totalAppended(): Long = runBlocking { mutex.withLock { totalAppended } }

    /** 当前环内行数。 */
    fun size(): Int = runBlocking { mutex.withLock { ring.size } }

    /** 清空（仅测试与诊断导出后归零使用）。 */
    fun clear() {
        runBlocking { mutex.withLock { ring.clear(); totalAppended = 0L } }
    }

    private fun Throwable.stackTraceText(): String = runCatching { stackTraceToString() }.getOrDefault(toString())

    private fun String.takeLastLines(limit: Int): String {
        val lines = split('\n')
        if (lines.size <= limit) return this
        return lines.takeLast(limit).joinToString("\n")
    }
}
