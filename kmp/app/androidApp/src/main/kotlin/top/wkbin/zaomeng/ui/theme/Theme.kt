package top.wkbin.zaomeng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import top.wkbin.zaomeng.data.preferences.ThemeMode

/**
 * 应用主题。
 *
 * 配色优先级（保持改造前行为）：
 * 用户选的种子色（material-kolor 生成） > Android 12+ 壁纸取色 > 本仓库静态「书卷 · 暖墨」色板。
 *
 * 与改造前的差别：接入了 [AppShapes]（此前没有 `shapes`，圆角全靠页面手写）
 * 和完整的 15 档 [Typography]。
 */
@Composable
fun ZaomengTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColorEnabled: Boolean = false,
    themeSeedColorArgb: Long = 0L,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = platformColorScheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColorEnabled,
        seedColorArgb = themeSeedColorArgb,
    )
        ?: if (darkTheme) ZaomengDarkColorScheme else ZaomengLightColorScheme
    applySystemBars(darkTheme, colorScheme.background)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
