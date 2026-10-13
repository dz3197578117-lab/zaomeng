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
 * 端侧配置**只**在这里：模型档案编辑页不再嵌入端侧区块，所以新增/编辑 API 档案时看不到
 * 导入 .gguf、下载模型、上下文长度这些本机专属设置。
 *
 * 入口有两个，都是顶层入口：设置首页的「本机模型（端侧）」，以及模型档案列表页顶部的同一张卡片。
 * 页面把「导入 / 下载模型 → 启动 → 启用本机推理」四步收在一起，不需要先理解或创建模型档案。
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
                item(key = "status-message") {
                    StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank())
                }
            }
            item(key = "intro") {
                Text(
                    text = "导入或下载 GGUF 模型，在本机跑推理，再一键把对话、摘要、关系抽取与蒸馏切到本机。" +
                        "全程不需要联网，也不需要 API Key。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "on-device-content") {
                OnDeviceModelContent(
                    status = state.onDevice,
                    busy = state.busy || state.applying,
                    applying = state.applying,
                    selectedModel = state.selectedModel,
                    contextSize = state.contextSize,
                    threads = state.threads,
                    gpuLayers = state.gpuLayers,
                    disableThinking = state.disableThinking,
                    downloadUrl = state.downloadUrl,
                    downloadFileName = state.downloadFileName,
                    downloadEndpointId = state.downloadEndpointId,
                    importing = state.importing,
                    importFileName = state.importFileName,
                    importCopiedBytes = state.importCopiedBytes,
                    importTotalBytes = state.importTotalBytes,
                    logs = state.logs,
                    showLogs = state.showLogs,
                    onRefresh = viewModel::refresh,
                    onSelectModel = viewModel::selectModel,
                    onContextSizeChange = viewModel::updateContextSize,
                    onThreadsChange = viewModel::updateThreads,
                    onGpuLayersChange = viewModel::updateGpuLayers,
                    onDisableThinkingChange = viewModel::updateDisableThinking,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onDownloadUrlChange = viewModel::updateDownloadUrl,
                    onDownloadFileNameChange = viewModel::updateDownloadFileName,
                    onDownloadEndpointChange = viewModel::updateDownloadEndpoint,
                    onPickPreset = viewModel::downloadPreset,
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
