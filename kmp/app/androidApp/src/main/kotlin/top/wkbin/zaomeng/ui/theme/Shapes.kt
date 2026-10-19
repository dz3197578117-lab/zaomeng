package top.wkbin.zaomeng.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局圆角阶梯。
 *
 * 改造前项目里有 10 种圆角值（12/10/8/999/6/16/14/24/20/0）散落在 97 处，
 * 视觉上「每个卡片都不一样」。这里收敛为 5 档，页面一律通过 `MaterialTheme.shapes.*` 取用。
 *
 * - extraSmall 6  ：输入框内的小标签、迷你徽标
 * - small     10  ：小按钮、tag
 * - medium    14  ：默认卡片、列表项
 * - large     20  ：大卡片、底部弹层
 * - extraLarge 28 ：Hero 卡、对话框
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 全圆角（头像、胶囊按钮）。M3 没有对应档位，单独提供。 */
val AppPillShape = RoundedCornerShape(percent = 50)

/**
 * 对话气泡：三个角用 `large`(20)，靠近说话人的那个角收成 `extraSmall`(6)，
 * 形成轻微的「指向感」而不画真正的三角尾巴（长文本下三角尾巴会显得廉价）。
 */
fun appBubbleShape(isMine: Boolean): RoundedCornerShape = if (isMine) {
    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
} else {
    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
}
