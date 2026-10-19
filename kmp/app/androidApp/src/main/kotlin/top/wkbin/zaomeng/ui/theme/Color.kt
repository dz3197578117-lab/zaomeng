package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 「工作台 · 冷墨」色板。
 *
 * 取意：冷灰纸、墨字、靛蓝为操作色。
 * - primary  靛蓝：主操作、进度、选中态；
 * - secondary 灰蓝：次级信息与辅助状态；
 * - tertiary 紫：第三层级标签与 AI 相关提示。
 *
 * 与旧「宣纸 · 朱砂」的区别：去掉暖黄底与高饱和朱砂，
 * 背景更中性，卡片用纯白 + 极轻阴影分层，长时间写作不刺眼。
 * 所有容器色仍按 Material 3 的 tonal 语义成对给出，明暗两套独立。
 */

// ---------- 浅色（冷灰纸） ----------
private val WorkbenchBackground = Color(0xFFF3F5F8)
private val WorkbenchSurface = Color(0xFFFFFFFF)
private val Ink = Color(0xFF12161C)
private val MutedInk = Color(0xFF667085)
private val Indigo = Color(0xFF3D5AFE)
private val IndigoContainer = Color(0xFFE8ECFF)
private val OnIndigoContainer = Color(0xFF1B2A6B)
private val Slate = Color(0xFF5A6B8C)
private val SlateContainer = Color(0xFFEDF0F6)
private val OnSlateContainer = Color(0xFF2A3446)
private val Violet = Color(0xFF7C5CFF)
private val VioletContainer = Color(0xFFF0ECFF)
private val OnVioletContainer = Color(0xFF2E1B66)

val ZaomengLightColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,
    inversePrimary = Color(0xFFB8C3FF),

    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = OnSlateContainer,

    tertiary = Violet,
    onTertiary = Color.White,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = OnVioletContainer,

    background = WorkbenchBackground,
    onBackground = Ink,
    surface = WorkbenchSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = MutedInk,
    surfaceTint = Indigo,
    inverseSurface = Color(0xFF1B212A),
    inverseOnSurface = Color(0xFFF3F5F8),

    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),

    outline = Color(0xFF98A2B3),
    outlineVariant = Color(0xFFE4E7EC),
    scrim = Color.Black,

    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE3EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFEEF2F7),
    surfaceContainerHighest = Color(0xFFE7ECF3),
)

// ---------- 深色（夜墨） ----------
val ZaomengDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FA2FF),
    onPrimary = Color(0xFF101A4A),
    primaryContainer = Color(0xFF27327A),
    onPrimaryContainer = Color(0xFFDCE1FF),
    inversePrimary = Indigo,

    secondary = Color(0xFFA9B7D0),
    onSecondary = Color(0xFF1C2533),
    secondaryContainer = Color(0xFF2A3446),
    onSecondaryContainer = Color(0xFFDCE5F5),

    tertiary = Color(0xFFC3B2FF),
    onTertiary = Color(0xFF2A1A5E),
    tertiaryContainer = Color(0xFF3B2C7A),
    onTertiaryContainer = Color(0xFFE9E2FF),

    background = Color(0xFF0E1116),
    onBackground = Color(0xFFECF1F8),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFECF1F8),
    surfaceVariant = Color(0xFF2A313B),
    onSurfaceVariant = Color(0xFF98A2B3),
    surfaceTint = Color(0xFF8FA2FF),
    inverseSurface = Color(0xFFECF1F8),
    inverseOnSurface = Color(0xFF1B212A),

    error = Color(0xFFFDA29B),
    onError = Color(0xFF5A1710),
    errorContainer = Color(0xFF8A2318),
    onErrorContainer = Color(0xFFFEE4E2),

    outline = Color(0xFF6B7686),
    outlineVariant = Color(0xFF2A313B),
    scrim = Color.Black,

    surfaceBright = Color(0xFF323C49),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = Color(0xFF151A21),
    surfaceContainer = Color(0xFF1B212A),
    surfaceContainerHigh = Color(0xFF232B36),
    surfaceContainerHighest = Color(0xFF2C3542),
)
