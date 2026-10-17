package top.wkbin.zaomeng.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.ui.theme.AppSpacing
import top.wkbin.zaomeng.ui.theme.ZaomengTheme

/**
 * 设计系统与组件库的预览。
 *
 * 改造前全项目 `@Preview` 数量为 0，任何视觉调整都只能靠真机截图验证。
 * 这里覆盖「色板」与「组件库」两块基础设施：改 token 时先看这里，再逐屏看。
 */

@Preview(name = "色板 · 浅色", showBackground = true, widthDp = 380, heightDp = 260)
@Composable
private fun PalettePreview() {
    ZaomengTheme {
        val scheme = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .background(scheme.background)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Swatch("primary", scheme.primary)
                Swatch("secondary", scheme.secondary)
                Swatch("tertiary", scheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Swatch("primaryContainer", scheme.primaryContainer)
                Swatch("secondaryContainer", scheme.secondaryContainer)
                Swatch("tertiaryContainer", scheme.tertiaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Swatch("background", scheme.background)
                Swatch("surfaceContainer", scheme.surfaceContainer)
                Swatch("surfaceContainerHigh", scheme.surfaceContainerHigh)
            }
        }
    }
}

@Composable
private fun RowScope.Swatch(label: String, color: Color) {
    Column(Modifier.weight(1f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(color, MaterialTheme.shapes.small),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Preview(name = "组件库 · 浅色", showBackground = true, widthDp = 380, heightDp = 700)
@Composable
private fun ComponentLibraryPreview() {
    ZaomengTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            AppSectionHeader(title = "书卷", subtitle = "12 本 · 3 本连载中")

            AppCard(variant = AppCardVariant.Elevated, onClick = {}) {
                Text("Elevated 卡片", style = MaterialTheme.typography.titleMedium)
                Text(
                    "需要「浮起来」的关键卡片：书卷卡、统计卡",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppCard(variant = AppCardVariant.Outlined, contentPadding = PaddingValues(0.dp)) {
                AppListItem(
                    title = "长夜将明",
                    subtitle = "42 章 · 9 位角色",
                    leadingIcon = Icons.Outlined.CollectionsBookmark,
                    showChevron = true,
                    onClick = {},
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AppListItem(
                    title = "春山旧事",
                    subtitle = "28 章 · 6 位角色",
                    leadingIcon = Icons.Outlined.Edit,
                    value = "已完成",
                    showChevron = true,
                    onClick = {},
                )
            }

            AppEmptyState(
                title = "书架还是空的",
                description = "放入 TXT 正文开始蒸馏人物，或者导入以前导出的书卷包。",
                icon = Icons.Outlined.CollectionsBookmark,
                action = { Button(onClick = {}) { Text("导入第一本书") } },
            )
        }
    }
}

@Preview(name = "组件库 · 深色", showBackground = true, widthDp = 380, heightDp = 700)
@Composable
private fun ComponentLibraryDarkPreview() {
    ZaomengTheme(themeMode = top.wkbin.zaomeng.data.preferences.ThemeMode.DARK) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            AppSectionHeader(title = "书卷", subtitle = "夜墨配色 · 深色模式")
            AppCard(variant = AppCardVariant.Elevated) {
                Text("深色下的卡片层级", style = MaterialTheme.typography.titleMedium)
                Text(
                    "surfaceContainer 五阶负责在深色里分出层次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppCard(variant = AppCardVariant.Outlined, contentPadding = PaddingValues(0.dp)) {
                AppListItem(
                    title = "沈知遇",
                    subtitle = "第 12 章 · 雨夜",
                    leadingIcon = Icons.Outlined.CollectionsBookmark,
                    showChevron = true,
                    onClick = {},
                )
            }
        }
    }
}
