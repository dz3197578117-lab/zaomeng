package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 独立的「本机模型（端侧）」页。
 *
 * 存在的理由：端侧相关的入口原先只埋在「设置 → 模型设置 → 编辑某个档案」的最底部，
 * 路径四层深，还得先有一个档案、再滚到底才看得见。这个页面把
 * 「导入 / 下载模型 → 启动 → 启用本机推理」四步收在同一个顶层入口里，
 * 从「设置」首页一步可达，不需要先理解或创建模型档案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceModelsScreen(
    viewModel: OnDeviceModelsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 回到本页时刷一次：启动/下载可能在别的页面里被改过
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWhenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本机模型（端侧）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item { StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank()) }
            }
            item {
                Text(
                    text = "导入或下载 GGUF 模型，在本机跑推理，再一键把对话、摘要、关系抽取与蒸馏切到本机。" +
                        "全程不需要联网，也不需要 API Key。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OnDeviceModelContent(
                    status = state.onDevice,
                    busy = state.busy || state.applying,
                    selectedModel = state.selectedModel,
                    contextSize = state.contextSize,
                    threads = state.threads,
                    disableThinking = state.disableThinking,
                    downloadUrl = state.downloadUrl,
                    downloadFileName = state.downloadFileName,
                    importing = state.importing,
                    importFileName = state.importFileName,
                    importCopiedBytes = state.importCopiedBytes,
                    importTotalBytes = state.importTotalBytes,
                    logs = state.logs,
                    showLogs = state.showLogs,
                    applyButtonLabel = if (state.applying) "启用中…" else "启用本机推理",
                    applyHint = "「启用本机推理」会自动创建（或更新）一个指向本机端点的模型档案并设为当前模型，" +
                        "不需要手填地址，也不需要 API Key。之后想换回云端模型，去「模型设置」切换档案即可。",
                    onRefresh = viewModel::refresh,
                    onSelectModel = viewModel::selectModel,
                    onContextSizeChange = viewModel::updateContextSize,
                    onThreadsChange = viewModel::updateThreads,
                    onDisableThinkingChange = viewModel::updateDisableThinking,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onDownloadUrlChange = viewModel::updateDownloadUrl,
                    onDownloadFileNameChange = viewModel::updateDownloadFileName,
                    onDownload = viewModel::download,
                    onCancelDownload = viewModel::cancelDownload,
                    onImportStarted = viewModel::importStarted,
                    onImportProgress = viewModel::importProgress,
                    onImportSucceeded = viewModel::importSucceeded,
                    onImportFailed = viewModel::importFailed,
                    onImportCancelled = viewModel::importCancelled,
                    onDeleteModel = viewModel::deleteModel,
                    onApplyEndpoint = viewModel::enableOnDeviceInference,
                    onToggleLogs = viewModel::toggleLogs,
                )
            }
        }
    }
}
