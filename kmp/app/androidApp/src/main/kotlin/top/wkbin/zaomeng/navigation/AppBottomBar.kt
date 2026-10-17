package top.wkbin.zaomeng.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey

/**
 * 窄屏（手机）底部导航。
 *
 * 改造前手机端**没有底部导航**：书卷架 / 会话 / 卡库 三个入口挤在 TopAppBar 的三个图标里，
 * 跨书卷与在线书库则藏在二级页面。现在把最高频的 5 个目的地平铺到底栏。
 *
 * 「在线书库」不占底栏位（M3 建议 3–5 项），它仍可从「导入小说 → 在线书库」进入。
 */
@Composable
fun AppBottomBar(
    selectedDestination: NavKey?,
    onSelectDestination: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        topLevelRailItems
            .filterNot { it.destination == OnlineLibraryDestination }
            .forEach { item ->
                val selected = selectedDestination?.let { it::class == item.destination::class } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectDestination(item.destination) },
                    icon = {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                        )
                    },
                    label = {
                        Text(item.label, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
    }
}
