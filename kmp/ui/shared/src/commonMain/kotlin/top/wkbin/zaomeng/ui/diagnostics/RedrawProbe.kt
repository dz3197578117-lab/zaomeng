package top.wkbin.zaomeng.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import top.wkbin.zaomeng.platform.ClientDiagnostics
import kotlin.time.TimeSource

/**
 * 重绘探针（R7）：过度重组 / 重绘计数的**重绘那一半**。
 *
 * 已有的 `ClientDiagnostics.recordRecomposition` 只数**组合**（composition）次数，
 * 数不到「组合没变、但绘制每帧都在发生」的情况——而后者恰恰是闪烁与掉帧最常见的根因：
 * 一个被高频 invalidate 的区域会一直重绘，重组计数却纹丝不动。
 *
 * ### 口径（有意取舍，写清楚免得被误读）
 * 没有跨平台可读的「当前帧号」：`ClientDiagnostics` 的帧序号由平台帧回调内部推进，
 * 对 `commonMain` 不暴露读取接口（该文件归另一处维护，本探针不新增契约）。
 * 因此这里用**时间窗**计数：
 *
 * > 同一节点在 [REDRAW_WINDOW_MS] 内被绘制达到 [REDRAW_THRESHOLD] 次 → 记一条
 * > `feature=ui.redraw` 的结构化事件（`status=DEGRADED`，措辞只写「**疑似**过度重绘」）。
 *
 * 阈值取 30 次 / 1000ms：60Hz 下一帧 16.7ms，一秒约 60 帧；一个健康页面只在**内容真的变了**
 * 的时候重绘，30 次/秒意味着「超过一半的帧都在重画同一个区域」。再低（例如 10 次）会把
 * 流式输出、动画、列表滚动这些**本来就该重绘**的场景全算进去，指标会立刻没人看。
 *
 * ### 报告节流
 * 命中后同节点至少间隔 [REPORT_BACKOFF_MS] 才再记一条：持续闪烁不应该每秒灌一条事件
 * 把功能事件的环形缓冲冲掉（每条事件同时进 `feature × action` 累计计数，累计值不丢）。
 *
 * ### 三条纪律
 * - **只记录**：`drawWithContent { onDraw(); drawContent() }`，绘制内容一字不改；
 * - **不制造重组**：计数状态放在 `remember` 住的**普通对象**里（不是 `mutableStateOf`）。
 *   在绘制阶段写 Compose state 会触发下一帧失效——那等于诊断自己制造闪烁；
 * - **自身开销上报**：累计到 1ms 才调一次 `recordSamplingOverhead`，不每帧进锁。
 *
 * 未挂接诊断（桌面端/未安装采集器）时 `ClientDiagnostics.report` 仍然是纯内存写入，
 * 不会失败也不会阻断绘制。
 */
@Composable
fun Modifier.diagnosticsRedrawProbe(
    name: String,
    threshold: Int = REDRAW_THRESHOLD,
    windowMs: Long = REDRAW_WINDOW_MS,
): Modifier {
    val counter = remember(name, threshold, windowMs) { RedrawCounter(name, threshold, windowMs) }
    return this.drawWithContent {
        counter.onDraw()
        drawContent()
    }
}

/**
 * 页面级视觉探针：布局跳动 + 重绘，一次挂上。
 *
 * 存在的理由：接入点只需要改一个调用（把 `diagnosticsLayoutJitterProbe` 换成这个名字），
 * 不必在两个 Modifier 之间来回找。语义与分别调用两个探针完全一致。
 */
@Composable
fun Modifier.diagnosticsVisualProbe(name: String): Modifier =
    this.diagnosticsLayoutJitterProbe(name).diagnosticsRedrawProbe(name)

/**
 * 重绘计数器。
 *
 * 刻意**不是** Compose state：字段是普通 `var`，写在绘制阶段不会让任何快照失效。
 */
private class RedrawCounter(
    private val name: String,
    private val threshold: Int,
    private val windowMs: Long,
) {
    private var window = TimeSource.Monotonic.markNow()
    private var drawsInWindow = 0
    private var lastReportAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var overheadNanos = 0L

    fun onDraw() {
        val started = TimeSource.Monotonic.markNow()
        drawsInWindow++
        val elapsed = window.elapsedNow().inWholeMilliseconds
        if (elapsed >= windowMs) {
            if (drawsInWindow >= threshold && canReport()) {
                lastReportAt = TimeSource.Monotonic.markNow()
                ClientDiagnostics.report(
                    feature = FEATURE_REDRAW,
                    action = name,
                    status = ClientDiagnostics.STATUS_DEGRADED,
                    durationMs = elapsed,
                    detail = "疑似过度重绘：同一区域 ${elapsed}ms 内被绘制 $drawsInWindow 次" +
                        "（阈值 $threshold 次 / ${windowMs}ms；仅为观测到的现象，不代表已确认缺陷）",
                )
            }
            window = TimeSource.Monotonic.markNow()
            drawsInWindow = 0
        }
        // 自身开销：只在累计超过 1ms 时上报一次，避免每次绘制都去抢锁。
        overheadNanos += started.elapsedNow().inWholeNanoseconds
        if (overheadNanos >= OVERHEAD_FLUSH_NANOS) {
            ClientDiagnostics.recordSamplingOverhead(overheadNanos / 1_000_000L)
            overheadNanos = 0L
        }
    }

    private fun canReport(): Boolean {
        val previous = lastReportAt ?: return true
        return previous.elapsedNow().inWholeMilliseconds >= REPORT_BACKOFF_MS
    }
}

/** 功能事件里的功能名（R4 通用上报口径）：重绘观测共用。 */
private const val FEATURE_REDRAW = "ui.redraw"

/** 判定窗口：与布局跳动探针一致，便于并列阅读。 */
private const val REDRAW_WINDOW_MS = 1_000L

/** 窗口内绘制次数阈值：约 60Hz 下「超过一半的帧都在重画同一区域」。 */
private const val REDRAW_THRESHOLD = 30

/** 同一节点两条报告之间的最短间隔，防止持续闪烁刷屏功能事件。 */
private const val REPORT_BACKOFF_MS = 10_000L

/** 自身开销的刷新粒度：1ms。 */
private const val OVERHEAD_FLUSH_NANOS = 1_000_000L
