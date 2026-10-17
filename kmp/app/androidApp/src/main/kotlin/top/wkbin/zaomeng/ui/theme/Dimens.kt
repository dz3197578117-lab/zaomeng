package top.wkbin.zaomeng.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/**
 * 间距阶梯（4dp 基准栅格）。
 *
 * 改造前项目有 1120 处 `.dp` 字面量、`AppDimens` 只被引用 23 次。新增布局一律用这里的档位，
 * 不要再写 `padding(13.dp)` 这类只此一处的数字。
 */
object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** 高度层级。与 M3 的 `CardDefaults.cardElevation` 对应使用。 */
object AppElevation {
    val none = 0.dp
    val low = 1.dp
    val medium = 3.dp
    val high = 6.dp
}

/** 图标与头像尺寸。 */
object AppSizes {
    val iconSm = 18.dp
    val iconMd = 24.dp
    val iconLg = 32.dp

    val avatarSm = 28.dp
    val avatarMd = 40.dp
    val avatarLg = 56.dp

    /** 最小可点击区域（Material 无障碍要求 48dp）。 */
    val touchTarget = 48.dp
}

/**
 * 旧字段保留：已有 23 处调用继续可用，值映射到新阶梯，避免一次性大改。
 * 新代码请直接用 [AppSpacing] / [AppSizes]。
 */
object AppDimens {
    val screenPadding = AppSpacing.lg
    val cardPadding = AppSpacing.lg
    val itemSpacing = AppSpacing.md
    val sectionSpacing = AppSpacing.xl
    val iconTextGap = AppSpacing.sm
    val inlineIndicator = 18.dp
    val pageIndicator = 24.dp
}

/**
 * 统一动效时长与缓动。
 *
 * 改造前各处直接写 `tween(260)`、`tween(180)`，同一个转场在不同页面速度不一致。
 * 交互反馈用 [Fast]，页面进出用 [Base]，强调动效用 [EmphasizedEasing]。
 */
object AppMotion {
    const val Instant = 90
    const val Fast = 150
    const val Base = 220
    const val Slow = 320
    const val Emphasized = 480

    /** 标准曲线：快出慢入，用于进出场。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 减速曲线：用于「出现」，收尾更柔。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** 强调曲线：用于共享元素、Hero 转场。 */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
