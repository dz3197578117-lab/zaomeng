package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onSelectEndpoint: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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

            Text("下载源", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (endpoint in OnDeviceModelPresets.endpoints) {
                    FilterChip(
                        selected = state.downloadEndpointId == endpoint.id,
                        onClick = { onSelectEndpoint(endpoint.id) },
                        label = { Text(endpoint.label, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }

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
                    TextButton(onClick = onDelete) { Text("删除本地文件") }
                }
            }
        }
    }
}

/** 字节 → MB（下载体积要给用户看具体数字，不能只写「较大」）。 */
internal fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val megabytes = bytes / 1024.0 / 1024.0
    return "%.1f MB".format(megabytes)
}
