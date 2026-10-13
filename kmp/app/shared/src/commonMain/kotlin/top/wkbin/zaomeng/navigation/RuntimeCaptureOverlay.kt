package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.wkbin.zaomeng.data.RuntimeCaptureStatus

/**
 * 应用内悬浮窗：浮在**本应用自己的界面之上**（不是系统悬浮窗）。
 *
 * - 只是「状态显示 + 快捷入口」，不做任何采集：它与「持续采集」开关解耦；
 * - 采集关闭时**明确显示「未采集」**——静默失效（以为在录、其实没录）比没有这个功能更坑；
 * - 点击默认行为：跳到「运行日志」页，让用户能立刻查看/导出；
 * - 可拖动（在窗口范围内），不申请 SYSTEM_ALERT_WINDOW，也不用 TYPE_APPLICATION_OVERLAY。
 *
 * 挂在导航宿主的最外层 [Box] 里，因此在书卷架/会话/设置之间切换时都可见。
 */
@Composable
internal fun RuntimeCaptureOverlay(
    status: RuntimeCaptureStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var chipSize by remember { mutableStateOf(IntSize.Zero) }

    // 悬浮元素靠右下角对齐，offset 只允许往左上走（负方向），并夹在窗口内
    fun clamp(value: Offset, container: IntSize, chip: IntSize): Offset {
        val maxX = (container.width - chip.width).toFloat().coerceAtLeast(0f)
        val maxY = (container.height - chip.height).toFloat().coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, 0f), value.y.coerceIn(-maxY, 0f))
    }

    val label = overlayLabel(status)
    val containerColor = when {
        !status.supported -> MaterialTheme.colorScheme.surfaceVariant
        status.known && !status.enabled -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        !status.supported -> MaterialTheme.colorScheme.onSurfaceVariant
        status.known && !status.enabled -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val dotColor = if (status.known && status.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { containerSize = it }) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 96.dp)
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .onSizeChanged { size ->
                    chipSize = size
                    offset = clamp(offset, containerSize, size)
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = clamp(offset + dragAmount, containerSize, chipSize)
                    }
                }
                .semantics {
                    contentDescription = "运行日志采集状态：$label。点击打开运行日志页。"
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 悬浮窗文案。
 *
 * 读不到状态（[RuntimeCaptureStatus.known] 为假）时只显示功能名，**绝不能显示「未采集」**：
 * 那会把「后端还没起来」误报成「没在采集」。
 */
private fun overlayLabel(status: RuntimeCaptureStatus): String = when {
    !status.supported -> "运行日志 · 常开"
    !status.known -> "运行日志"
    status.enabled -> "运行日志 · 采集中"
    else -> "运行日志 · 未采集"
}
