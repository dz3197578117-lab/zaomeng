package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「书卷」字体层级。
 *
 * 两条原则：
 * 1. **标题用衬线**（[FontFamily.Serif]，Android 上映射到 Noto Serif CJK）：书名、页面标题、统计数字
 *    用衬线才有「书」的质感；正文保持无衬线以保证长文本可读性。
 * 2. **中文行高比 M3 默认更松**：M3 的 16/24 是按拉丁文调的，中文需要约 1.6 倍行距才不挤，
 *    因此 body 全部加到 1.62 左右；同时把 letterSpacing 归零——M3 默认的 0.5sp 会让中文「散架」。
 */
private fun serifStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

private fun sansStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val Typography = Typography(
    // 展示级：用于空状态主标题、封面上的大书名
    displayLarge = serifStyle(52, 62, FontWeight.Bold, -0.5),
    displayMedium = serifStyle(42, 52, FontWeight.Bold, -0.4),
    displaySmall = serifStyle(34, 44, FontWeight.Bold, -0.3),

    // 标题级：页面主标题、卡片大标题
    headlineLarge = serifStyle(30, 40),
    headlineMedium = serifStyle(26, 36),
    headlineSmall = serifStyle(22, 32),

    // 小标题：AppBar 标题、列表分组名
    titleLarge = serifStyle(20, 28),
    titleMedium = sansStyle(16, 24, FontWeight.SemiBold, 0.1),
    titleSmall = sansStyle(14, 20, FontWeight.Medium, 0.1),

    // 正文：对话、说明、长文本
    bodyLarge = sansStyle(16, 26),
    bodyMedium = sansStyle(14, 22),
    bodySmall = sansStyle(12, 18),

    // 标签：按钮、chip、角标
    labelLarge = sansStyle(14, 20, FontWeight.Medium, 0.1),
    labelMedium = sansStyle(12, 16, FontWeight.Medium, 0.2),
    labelSmall = sansStyle(11, 16, FontWeight.Medium, 0.3),
)
