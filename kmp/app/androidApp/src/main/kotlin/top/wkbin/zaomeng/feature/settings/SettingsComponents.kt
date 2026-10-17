package top.wkbin.zaomeng.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import top.wkbin.zaomeng.ui.components.AppListItem

/**
 * 设置列表行。
 *
 * 改造前这里是手写 Row（64dp 高、16dp 水平内边距、图标直接裸放在文字旁）。
 * 现在委托给全局 [AppListItem]：图标带圆形底色、间距走 token、点击区符合 48dp 无障碍下限，
 * 且与书卷详情、会话等页面的列表行保持同一套视觉规则。
 *
 * 保持原函数签名不变，因此设置模块内所有既有调用点零改动即可升级。
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    AppListItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        value = value,
        showChevron = onClick != null,
        enabled = enabled,
        onClick = onClick,
    )
}
