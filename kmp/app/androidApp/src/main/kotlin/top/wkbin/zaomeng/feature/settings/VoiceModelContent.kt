package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.theme.AppSpacing
import top.wkbin.zaomeng.platform.NeuralVoiceCatalog

/**
 * 「离线神经朗读」区块：选模型 → 下载 → 开关。
 *
 * ## 界面必须说清的两件事
 *
 * 1. **神经引擎不支持音高**（引擎 API 只有说话人编号与语速）。不写清楚，用户会觉得
 *    「角色差异不够明显」，而实际上是这一项根本没生效——把限制讲明白比让人猜要好。
 * 2. **模型体积**要写在按钮上（119 MB）。等用户点下去才发现要下 100 多 MB 是最差的体验。
 */
@Composable
internal fun VoiceModelContent(
    state: VoiceModelUiState,
    onSelectModel: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    // 删除会同时关掉离线神经朗读（VoiceModelSettingsViewModel 里 persistSelection(enabled = false)），
    // 所以必须先确认一次，并把「会关掉朗读」写进确认文案。
    var pendingDelete by remember { mutableStateOf(false) }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        contentPadding = PaddingValues(AppSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text(
                text = "离线神经朗读（每个角色不同音色）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "用内置的多说话人中文模型朗读，**每个角色分到不同音色**，完全离线。" +
                    "支持语速与音量；**不支持音高**（引擎没有该参数），情绪主要由语速与模型自身表现力体现。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 把「实际生效的是哪条引擎」明写出来。用户反馈「所有人都是一个声音」时，
            // 第一个要排除的就是「其实根本没走神经引擎」——不显示的话只能靠猜或翻日志。
            val activeEngine = when {
                !state.enabled -> "当前生效：系统朗读（TTS）。"
                state.selectedReady ->
                    "当前生效：离线神经朗读 · ${state.selectedModel.displayName}" +
                        "（音色 ${state.selectedModel.speakerCount} 个）。"
                else ->
                    "当前生效：仍是系统朗读 —— 所选模型还没下载完，" +
                        "此时所有角色共用系统 TTS 的同一个音色。"
            }
            Text(
                text = activeEngine,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (state.enabled && state.selectedReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            state.selectedMissingFileWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.selectedFileSizeWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用神经朗读", style = MaterialTheme.typography.bodyMedium)
                    if (state.enabled && !state.selectedReady) {
                        Text(
                            text = "选中的模型还没下载完，此时会自动用系统朗读代替。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(checked = state.enabled, onCheckedChange = onToggleEnabled)
            }

            Text("选择模型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            for (model in NeuralVoiceCatalog.models) {
                val ready = model.id in state.readyModelIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.selectedModelId == model.id,
                        onClick = { onSelectModel(model.id) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${model.summary}  体积 ${formatMegabytes(model.totalBytes)}" +
                                if (ready) "  ·  已下载" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ready) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // 刻意**不让用户选下载源**：官方站在国内经常连不上，镜像站本来就是它的后路——
            // 「选错就没得下」这种事不该交给用户。下载会自动依次尝试两个源（见 ViewModel）。
            Text(
                text = "下载会自动尝试官方源，连不上时改用镜像源，不需要你选择。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isDownloading) {
                val download = state.download
                val fileTotal = download.fileTotalBytes
                val fileDone = download.fileDownloadedBytes
                val progress = if (fileTotal > 0) (fileDone.toFloat() / fileTotal).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = buildString {
                        append("第 ${download.fileIndex}/${download.fileCount} 个文件")
                        append("  ·  整个模型 ${formatMegabytes(download.completedBytes)} / ")
                        append(formatMegabytes(download.totalBytes))
                        download.skippedFiles.takeIf { it.isNotEmpty() }?.let {
                            append("  ·  已跳过 ${it.size} 个")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.message.isNotBlank() || state.error.isNotBlank()) {
                StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank())
                TextButton(onClick = onDismissMessage) { Text("知道了") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !state.busy && !state.isDownloading,
                ) {
                    Text(
                        if (state.selectedReady) {
                            "重新下载（${formatMegabytes(state.selectedModel.totalBytes)}）"
                        } else {
                            "下载模型（${formatMegabytes(state.selectedModel.totalBytes)}）"
                        },
                    )
                }
                if (state.isDownloading) {
                    TextButton(onClick = onCancelDownload) { Text("取消下载") }
                }
                if (state.selectedReady) {
                    TextButton(onClick = { pendingDelete = true }) { Text("删除本地文件") }
                }
            }
        }
    }
    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("删除本地语音模型？") },
            text = {
                Text(
                    "将删除「${state.selectedModel.displayName}」的本地文件" +
                        "（${formatMegabytes(state.selectedModel.totalBytes)}），" +
                        "并同时关闭离线神经朗读——删完后朗读会退回系统 TTS，" +
                        "要重新下载模型才能再用。此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 字节 → MB（下载体积要给用户看具体数字，不能只写「较大」）。 */
internal fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val megabytes = bytes / 1024.0 / 1024.0
    return "%.1f MB".format(megabytes)
}
