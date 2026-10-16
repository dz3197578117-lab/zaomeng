package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 「离线神经语音」独立页（设置首页里的独立入口）。
 *
 * ## 为什么从「本机模型（端侧）」里拆出来
 *
 * 那一页是 **llama.cpp / GGUF 的文本推理**；这里是**朗读（TTS）**。两者互不依赖：
 * 各有各的引擎（`llama-server` vs `sherpa-onnx`）、各有各的模型文件、也可以只用一个不用另一个。
 * 塞进同一页会让人以为「得先搞好端侧模型才能朗读」，也会让「下载 119 MB 语音模型」
 * 看起来像端侧推理的一部分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSpeechScreen(
    viewModel: VoiceModelSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线神经语音") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            VoiceModelContent(
                state = state,
                onSelectModel = viewModel::selectModel,
                onToggleEnabled = viewModel::setEnabled,
                onDownload = viewModel::download,
                onCancelDownload = viewModel::cancelDownload,
                onDelete = viewModel::deleteSelected,
                onDismissMessage = viewModel::dismissMessage,
            )
        }
    }
}
