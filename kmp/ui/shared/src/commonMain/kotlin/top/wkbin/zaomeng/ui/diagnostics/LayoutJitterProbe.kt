package top.wkbin.zaomeng.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import top.wkbin.zaomeng.platform.ClientDiagnostics
import kotlin.time.TimeSource

/**
 * 布局跳动探针（R7）。
 *
 * 判定口径：**同一节点在 [WINDOW_MS] 窗口内连续两次尺寸变化**（size 变了、又很快变了一次）
 * 记为一次跳动，并记录最大位移（dp）。
 *
 * 为什么只看尺寸、不看位置：
 * - 页面的**位置**在导航切换与列表滚动时本来就会变，把它算成「跳动」会产生大量假阳性，
 *   最后没人会看这个指标；
 * - 页面的**尺寸**在短时间内反复变化才是真正的「布局跳动」（图片加载完成、文字折行、
 *   异步内容到达把上面的元素顶下去）。
 *
 * 这是**有意的取舍**，不是遗漏：位置型跳动需要「排除滚动与导航」的上下文，
 * 成本远高于尺寸型，本版不做。
 *
 * 只记录，不改布局：它只挂一个 `onGloballyPositioned` 回调。
 */
@Composable
fun Modifier.diagnosticsLayoutJitterProbe(name: String): Modifier {
    var lastSize by remember { mutableStateOf<IntSize?>(null) }
    var lastChangeAt by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var lastShiftDp by remember { mutableStateOf(0) }
    return this.onGloballyPositioned { coordinates ->
        val size = coordinates.size
        val previous = lastSize
        if (previous != null && previous != size) {
            val shiftDp = maxOf(
                kotlin.math.abs(size.width - previous.width),
                kotlin.math.abs(size.height - previous.height),
            )
            val now = TimeSource.Monotonic.markNow()
            val previousAt = lastChangeAt
            val withinWindow = previousAt != null && previousAt.elapsedNow().inWholeMilliseconds <= WINDOW_MS
            if (withinWindow) {
                // 窗口内第二次变化才算跳动：单次变化（例如首次内容到达）是正常的。
                ClientDiagnostics.recordLayoutJitter(
                    name = name,
                    shiftDp = maxOf(shiftDp, lastShiftDp),
                    windowMs = WINDOW_MS,
                )
            }
            lastChangeAt = now
            lastShiftDp = shiftDp
        }
        lastSize = size
    }
}

/** 判定窗口：两次尺寸变化间隔小于它才算「短时位移」。 */
private const val WINDOW_MS = 300L
