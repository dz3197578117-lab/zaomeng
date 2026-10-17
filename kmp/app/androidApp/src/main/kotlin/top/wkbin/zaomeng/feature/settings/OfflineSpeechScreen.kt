package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.platform.SystemTtsEngine
import top.wkbin.zaomeng.platform.listSystemTtsEngines
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 「离线神经语音」独立页（设置首页里的独立入口）。
 *
 * ## 为什么从「本机模型（端侧）」里拆出来
 *
 * 那一页是 **llama.cpp / GGUF 的文本推理**；这里是**朗读（TTS）**。两者互不依赖：
 * 各有各的引擎（`llama-server` vs `sherpa-onnx`）、各有各的模型文件、也可以只用一个不用另一个。
 * 塞进同一页会让人以为「得先搞好端侧模型才能朗读」，也会让「下载 119 MB 语音模型」
 * 看起来像端侧推理的一部分。
 *
 * ## 三种可选的朗读方案
 *
 * 1. 系统 TTS（跟随系统）；
 * 2. 系统 TTS + 指定引擎（NekoSpeak 等，带克隆音色 / 名人音色）；
 * 3. 离线神经 TTS（VITS 多音色 / Kokoro 高音质）。
 *
 * 第 2 项在本页顶部选择，第 3 项由下方的模型列表驱动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSpeechScreen(
    viewModel: VoiceModelSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 只查一次：引擎列表在页面存活期间不会变（装了新引擎需要重进本页）。
    val engines = remember { listSystemTtsEngines(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朗读与语音") },
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
            SystemTtsEngineSection(
                engines = engines,
                selectedPackage = state.systemEnginePackage,
                onSelect = viewModel::selectSystemEngine,
            )
            Spacer(Modifier.height(AppSpacing.xl))
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

/**
 * 系统朗读引擎选择。
 *
 * 造梦自己不实现语音克隆——那是另一个量级的工作。但 Android 允许把朗读交给第三方 TTS 引擎，
 * NekoSpeak（`com.nekospeak.tts`）就通过标准 API 暴露了 Pocket-TTS 零样本克隆、名人音色与
 * 100+ Piper 社区音色。选中它之后，造梦既有的「按角色分配 voice」逻辑会直接作用在它的音色上。
 */
@Composable
private fun SystemTtsEngineSection(
    engines: List<SystemTtsEngine>,
    selectedPackage: String,
    onSelect: (String) -> Unit,
) {
    AppCard(variant = AppCardVariant.Outlined, contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                "系统朗读引擎",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "关闭「离线神经语音」时使用。安装 NekoSpeak 等第三方引擎后可在这里选中它，" +
                    "让角色分配用上它的克隆音色与名人音色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EngineOption(
                title = "跟随系统",
                subtitle = "使用系统设置里的默认 TTS 引擎",
                selected = selectedPackage.isBlank(),
                onClick = { onSelect("") },
            )
            engines.forEach { engine ->
                EngineOption(
                    title = engine.label,
                    subtitle = if (engine.isNekoSpeak) {
                        "NekoSpeak · 零样本克隆 / 名人音色 / 100+ 社区音色"
                    } else {
                        engine.packageName
                    },
                    selected = selectedPackage == engine.packageName,
                    onClick = { onSelect(engine.packageName) },
                )
            }
            if (engines.none { it.isNekoSpeak }) {
                Text(
                    "未检测到 NekoSpeak。安装后（包名 com.nekospeak.tts）这里会多出一个可选项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null：点击由整行负责，避免 RadioButton 与 Row 双触发。
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
