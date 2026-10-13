package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.app.shared.AppMetadata
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import top.wkbin.zaomeng.platform.rememberToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSupportSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    /** 打开独立的「运行诊断」页（问题捕获 / 自检 / 悬浮窗开关 / 导出都在那里）。 */
    onOpenRuntimeDiagnostics: () -> Unit = {},
    onOpenRuntimeLog: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openExternalUrl = rememberOpenExternalUrl()
    val toast = rememberToast()
    // 每次进入本页刷新一次问题计数：进入设置页时顺手更新，不额外增加轮询。
    LaunchedEffect(Unit) { viewModel.refreshDiagnosticsSummary() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用与支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            SettingsSupportGroup(
                modifier = Modifier.fillMaxWidth(),
                state = state,
                onOpenRuntimeDiagnostics = onOpenRuntimeDiagnostics,
                onOpenRuntimeLog = onOpenRuntimeLog,
                onOpenOfficialWebsite = { openExternalUrl("https://dz3197578117-lab.github.io/zaomeng/") },
                onOpenProject = { openExternalUrl("https://github.com/dz3197578117-lab/zaomeng") },
                onOpenPackageLibrary = { openExternalUrl("https://github.com/wkbin/zaomeng-library") },
            )
        }
    }
}

@Composable
private fun SettingsSupportGroup(
    modifier: Modifier,
    state: SettingsUiState,
    onOpenRuntimeDiagnostics: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onOpenOfficialWebsite: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPackageLibrary: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        androidx.compose.foundation.layout.Column {
            SettingsRow(
                title = "运行诊断",
                subtitle = "问题捕获（崩溃 / ANR / 进程死因）、启动自检、现场元信息与悬浮窗采集开关；可导出脱敏诊断。" +
                    diagnosticsBadge(state),
                // R3：入口行直接显示问题计数与探针 FAIL 数（并列，不混算——
                // 「被更新/被系统清理」是 info，不该被计入崩溃数）。
                value = when {
                    state.diagnosticsProblemGroups == null -> "查看"
                    (state.diagnosticsProblemGroups ?: 0) == 0 && (state.diagnosticsProbeFails ?: 0) == 0 -> "无问题"
                    else -> "问题 ${state.diagnosticsProblemGroups} 组" +
                        if ((state.diagnosticsProbeFails ?: 0) > 0) " · ❌${state.diagnosticsProbeFails}" else ""
                },
                onClick = onOpenRuntimeDiagnostics,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "运行日志",
                subtitle = "查看整个应用的运行时日志（含 native、第三方库与崩溃堆栈）。可启动/关闭采集、清除旧日志；关闭采集期间崩溃仍会被记录。",
                value = "查看",
                onClick = onOpenRuntimeLog,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "官方网站",
                subtitle = "dz3197578117-lab.github.io/zaomeng",
                onClick = onOpenOfficialWebsite,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "项目地址",
                subtitle = "github.com/dz3197578117-lab/zaomeng",
                onClick = onOpenProject,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "书卷包地址",
                subtitle = "github.com/wkbin/zaomeng-library",
                onClick = onOpenPackageLibrary,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "版本信息",
                subtitle = "v${AppMetadata.VERSION_NAME}（${AppMetadata.VERSION_CODE}）",
            )
        }
    }
}

/**
 * 入口行副标题后缀：把「组数 / 累计次数 / 探针失败数」摊开。
 *
 * 读不到时**不加任何后缀**（显示「查看」），不显示 0——
 * 「没读到」与「没有问题」在入口行必须能区分，否则用户会误以为诊断正常。
 */
private fun diagnosticsBadge(state: SettingsUiState): String {
    val groups = state.diagnosticsProblemGroups ?: return ""
    if (groups == 0 && (state.diagnosticsProbeFails ?: 0) == 0) return " 当前无问题。"
    return buildString {
        append(" 当前 ${groups} 组问题")
        state.diagnosticsProblemTotal?.let { append("（累计 $it 次）") }
        state.diagnosticsProbeFails?.takeIf { it > 0 }?.let { append(" · 功能自检 ❌ $it") }
        append("。")
    }
}
