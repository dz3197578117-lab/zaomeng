package top.wkbin.zaomeng.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.ui.theme.AppElevation
import top.wkbin.zaomeng.ui.theme.AppSpacing

/** 卡片的三档层级。改造前项目 92 处 Card 全部是基础 `Card`，没有层级差异。 */
enum class AppCardVariant {
    /** 无阴影、浅容器底：用于页面内的大块分区。 */
    Filled,

    /** 轻阴影 + 浅容器底：用于需要「浮起来」的关键卡片（书卷卡、统计卡）。 */
    Elevated,

    /** 纯描边、无阴影：用于列表型密集信息，避免阴影噪声。 */
    Outlined,
}

/**
 * 统一卡片。所有页面都从这里取用，以保证圆角、内边距、层级一致。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    variant: AppCardVariant = AppCardVariant.Filled,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.lg),
    /** 覆盖默认容器色（如列表多选态）。不传则按 [variant] 取默认层级色。 */
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }

    when (variant) {
        AppCardVariant.Filled -> {
            val colors = CardDefaults.cardColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            val elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.none)
            if (onClick != null) {
                Card(onClick, modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
            } else {
                Card(modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
            }
        }

        AppCardVariant.Elevated -> {
            val colors = CardDefaults.elevatedCardColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            val elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.low)
            if (onClick != null) {
                ElevatedCard(onClick, modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
            } else {
                ElevatedCard(modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
            }
        }

        AppCardVariant.Outlined -> {
            val colors = CardDefaults.outlinedCardColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            val elevation = CardDefaults.outlinedCardElevation(defaultElevation = AppElevation.none)
            if (onClick != null) {
                OutlinedCard(
                    onClick,
                    modifier,
                    shape = shape,
                    colors = colors,
                    elevation = elevation,
                    border = border,
                    content = inner,
                )
            } else {
                OutlinedCard(
                    modifier,
                    shape = shape,
                    colors = colors,
                    elevation = elevation,
                    border = border,
                    content = inner,
                )
            }
        }
    }
}

/**
 * 分组标题。用于「设置」「书卷详情」这类需要把长列表切成若干块的页面。
 */
@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.sm))
            trailing()
        }
    }
}
