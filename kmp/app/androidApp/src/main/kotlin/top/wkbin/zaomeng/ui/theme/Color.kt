package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 「书卷 · 暖墨」色板。
 *
 * 取意：宣纸为底、墨为字、朱砂为印。
 * - primary  朱砂：用于主操作、强调、进度；
 * - secondary 黛青：用于次级信息与辅助状态，压住暖色的燥；
 * - tertiary 赭石：用于第三层级标签与徽标。
 *
 * 所有「容器色」（*Container）都按 Material 3 的 tonal 语义配对：
 * 容器为浅色底、on* 为深色字，明暗两套各自独立，不做简单反色。
 */

// ---------- 浅色（宣纸） ----------
private val PaperBackground = Color(0xFFFBF7F0)
private val PaperSurface = Color(0xFFFBF7F0)
private val InkOnPaper = Color(0xFF1F1B16)
private val Vermilion = Color(0xFFA03B2E)
private val VermilionContainer = Color(0xFFFFDAD3)
private val OnVermilionContainer = Color(0xFF3F0A04)
private val DaiQing = Color(0xFF5B6B67)
private val DaiQingContainer = Color(0xFFDCE8E4)
private val OnDaiQingContainer = Color(0xFF172622)
private val ZheShi = Color(0xFF7A5B33)
private val ZheShiContainer = Color(0xFFF3DFC4)
private val OnZheShiContainer = Color(0xFF2A1800)

val ZaomengLightColorScheme = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color.White,
    primaryContainer = VermilionContainer,
    onPrimaryContainer = OnVermilionContainer,
    inversePrimary = Color(0xFFFFB4A4),

    secondary = DaiQing,
    onSecondary = Color.White,
    secondaryContainer = DaiQingContainer,
    onSecondaryContainer = OnDaiQingContainer,

    tertiary = ZheShi,
    onTertiary = Color.White,
    tertiaryContainer = ZheShiContainer,
    onTertiaryContainer = OnZheShiContainer,

    background = PaperBackground,
    onBackground = InkOnPaper,
    surface = PaperSurface,
    onSurface = InkOnPaper,
    surfaceVariant = Color(0xFFEFE5D8),
    onSurfaceVariant = Color(0xFF6C6157),
    surfaceTint = Vermilion,
    inverseSurface = Color(0xFF34302A),
    inverseOnSurface = Color(0xFFF8EFE5),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    outline = Color(0xFF8C8076),
    outlineVariant = Color(0xFFD8CCBE),
    scrim = Color.Black,

    surfaceBright = Color(0xFFFBF7F0),
    surfaceDim = Color(0xFFDBD4C9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F1E7),
    surfaceContainer = Color(0xFFF3ECE0),
    surfaceContainerHigh = Color(0xFFEDE6D9),
    surfaceContainerHighest = Color(0xFFE8E0D3),
)

// ---------- 深色（夜墨） ----------
val ZaomengDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4A4),
    onPrimary = Color(0xFF5C160D),
    primaryContainer = Color(0xFF7E2A1F),
    onPrimaryContainer = Color(0xFFFFDAD3),
    inversePrimary = Vermilion,

    secondary = Color(0xFFB9CBC6),
    onSecondary = Color(0xFF24332F),
    secondaryContainer = Color(0xFF3A4A45),
    onSecondaryContainer = Color(0xFFD5E5E0),

    tertiary = Color(0xFFE4C08D),
    onTertiary = Color(0xFF432C07),
    tertiaryContainer = Color(0xFF5F4218),
    onTertiaryContainer = Color(0xFFFFDEB0),

    background = Color(0xFF16120E),
    onBackground = Color(0xFFEAE1D6),
    surface = Color(0xFF16120E),
    onSurface = Color(0xFFEAE1D6),
    surfaceVariant = Color(0xFF4E453C),
    onSurfaceVariant = Color(0xFFD2C4B5),
    surfaceTint = Color(0xFFFFB4A4),
    inverseSurface = Color(0xFFEAE1D6),
    inverseOnSurface = Color(0xFF34302A),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF9B8F84),
    outlineVariant = Color(0xFF4E453C),
    scrim = Color.Black,

    surfaceBright = Color(0xFF3D3832),
    surfaceDim = Color(0xFF16120E),
    surfaceContainerLowest = Color(0xFF110D09),
    surfaceContainerLow = Color(0xFF1F1A15),
    surfaceContainer = Color(0xFF231E19),
    surfaceContainerHigh = Color(0xFF2E2823),
    surfaceContainerHighest = Color(0xFF39332D),
)
