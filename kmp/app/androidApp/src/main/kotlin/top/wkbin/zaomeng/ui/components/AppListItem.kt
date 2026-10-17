package top.wkbin.zaomeng.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import top.wkbin.zaomeng.ui.theme.AppAlpha
import top.wkbin.zaomeng.ui.theme.AppSizes
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 统一列表行。
 *
 * 改造前「设置」模块 8200 行里存在大量结构相同的 Row（图标 + 标题 + 副标题 + 右侧值 + 箭头），
 * 每处内边距和字号都略有差别。所有新列表从这里取用。
 */
@Composable
fun AppListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    supportingColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    val contentAlpha = if (enabled) AppAlpha.Full else AppAlpha.Disabled

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSizes.touchTarget)
            .then(clickableModifier)
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        when {
            leadingContent != null -> leadingContent()

            leadingIcon != null -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = AppAlpha.Strong),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(Modifier.size(AppSizes.avatarSm), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(AppSizes.iconSm),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = contentAlpha),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor
                        ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
            )
        }
        trailingContent?.invoke()
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(AppSizes.iconMd),
            )
        }
    }
}
