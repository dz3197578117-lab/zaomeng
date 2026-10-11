package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.platform.rememberModelFileImporter
import kotlin.math.roundToInt

/**
 * 模型编辑页里的「本机模型（端侧）」区块。
 *
 * 这一块存在的意义：让用户不用手抄 `http://127.0.0.1:<随机端口>/v1`——
 * 端口每次启动都可能不同，手抄不现实。所以流程是「导入/下载模型 → 启动 → 一键回填端点」，
 * 回填之后这个档案就是一个普通的不需要 Key 的模型服务，所有现有功能自动走本机。
 *
 * 独立的「本机模型」页（`OnDeviceModelsScreen`）复用同一份 [OnDeviceModelContent]，
 * 区别只是那里的「一键」直接创建并激活档案，不需要用户理解「档案」这个概念。
 */
@Composable
internal fun OnDeviceModelSection(
    status: OnDeviceStatusDto?,
    busy: Boolean,
    selectedModel: String,
    contextSize: String,
    threads: String,
    disableThinking: Boolean,
    downloadUrl: String,
    downloadFileName: String,
    importing: Boolean,
    importFileName: String,
    importCopiedBytes: Long,
    importTotalBytes: Long,
    logs: String,
    showLogs: Boolean,
    onRefresh: () -> Unit,
    onSelectModel: (String) -> Unit,
    onContextSizeChange: (String) -> Unit,
    onThreadsChange: (String) -> Unit,
    onDisableThinkingChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDownloadUrlChange: (String) -> Unit,
    onDownloadFileNameChange: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportStarted: (fileName: String, totalBytes: Long) -> Unit,
    onImportProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    onImportSucceeded: (fileName: String, sizeBytes: Long) -> Unit,
    onImportFailed: (message: String) -> Unit,
    onImportCancelled: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onApplyEndpoint: () -> Unit,
    onToggleLogs: () -> Unit,
) {
    EditorSection("本机模型（端侧）") {
        OnDeviceModelContent(
            status = status,
            busy = busy,
            selectedModel = selectedModel,
            contextSize = contextSize,
            threads = threads,
            disableThinking = disableThinking,
            downloadUrl = downloadUrl,
            downloadFileName = downloadFileName,
            importing = importing,
            importFileName = importFileName,
            importCopiedBytes = importCopiedBytes,
            importTotalBytes = importTotalBytes,
            logs = logs,
            showLogs = showLogs,
            applyButtonLabel = "填入本机地址",
            applyHint = "「填入本机地址」会把接口地址、模型名填到上方表单，并关闭 API Key 要求；保存后启用该档案，对话/蒸馏就会全部跑在本机。",
            onRefresh = onRefresh,
            onSelectModel = onSelectModel,
            onContextSizeChange = onContextSizeChange,
            onThreadsChange = onThreadsChange,
            onDisableThinkingChange = onDisableThinkingChange,
            onStart = onStart,
            onStop = onStop,
            onDownloadUrlChange = onDownloadUrlChange,
            onDownloadFileNameChange = onDownloadFileNameChange,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onImportStarted = onImportStarted,
            onImportProgress = onImportProgress,
            onImportSucceeded = onImportSucceeded,
            onImportFailed = onImportFailed,
            onImportCancelled = onImportCancelled,
            onDeleteModel = onDeleteModel,
            onApplyEndpoint = onApplyEndpoint,
            onToggleLogs = onToggleLogs,
        )
    }
}

/**
 * 端侧区块的实际内容，模型编辑页与「本机模型」独立页共用。
 *
 * ## 为什么不再用 `supported && available` 一刀切
 * `available` 只看推理引擎在不在：Android 只打包了 arm64-v8a，非 arm64 设备上就是 false；
 * 桌面端没配 `LLAMA_SERVER_BIN` 时同样是 false。但**导入 / 下载 / 删除模型只需要一个可写目录**，
 * 跟引擎能不能跑无关。原先整块被这个条件挡掉，用户在这些环境下连「导入」按钮都看不到。
 * 现在把「模型管理」与「运行控制」拆成两个独立判断。
 */
@Composable
internal fun OnDeviceModelContent(
    status: OnDeviceStatusDto?,
    busy: Boolean,
    selectedModel: String,
    contextSize: String,
    threads: String,
    disableThinking: Boolean,
    downloadUrl: String,
    downloadFileName: String,
    importing: Boolean,
    importFileName: String,
    importCopiedBytes: Long,
    importTotalBytes: Long,
    logs: String,
    showLogs: Boolean,
    applyButtonLabel: String,
    applyHint: String,
    onRefresh: () -> Unit,
    onSelectModel: (String) -> Unit,
    onContextSizeChange: (String) -> Unit,
    onThreadsChange: (String) -> Unit,
    onDisableThinkingChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDownloadUrlChange: (String) -> Unit,
    onDownloadFileNameChange: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportStarted: (fileName: String, totalBytes: Long) -> Unit,
    onImportProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    onImportSucceeded: (fileName: String, sizeBytes: Long) -> Unit,
    onImportFailed: (message: String) -> Unit,
    onImportCancelled: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onApplyEndpoint: () -> Unit,
    onToggleLogs: () -> Unit,
) {
    // 选择器必须常驻组合：它内部持有 ActivityResultLauncher，放在条件分支里会在
    // 状态切换时被回收，导致「点了没反应」。所以提到最外层，目录空着也不会被触发。
    val modelImporter = rememberModelFileImporter(
        targetDir = status?.modelsDir.orEmpty(),
        onStarted = onImportStarted,
        onProgress = onImportProgress,
        onSucceeded = onImportSucceeded,
        onFailed = onImportFailed,
        onCancelled = onImportCancelled,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (status == null) {
            Text(
                text = "正在读取端侧推理状态…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val runnable = status.supported && status.available
        OnDeviceStatusLine(status)
        if (!runnable) UnavailableNotice(status)

        // 模型管理：只要模型目录可用就给，与推理引擎是否可用无关
        if (status.modelsDir.isNotBlank()) {
            OnDeviceModelPicker(
                status = status,
                selectedModel = selectedModel,
                enabled = !busy,
                onSelectModel = onSelectModel,
                onDeleteModel = onDeleteModel,
            )
            OnDeviceImportArea(
                status = status,
                busy = busy,
                importing = importing,
                fileName = importFileName,
                copiedBytes = importCopiedBytes,
                totalBytes = importTotalBytes,
                onPick = modelImporter::launch,
                onCancel = modelImporter::cancel,
            )
            OnDeviceDownloadArea(
                status = status,
                busy = busy,
                downloadUrl = downloadUrl,
                downloadFileName = downloadFileName,
                onDownloadUrlChange = onDownloadUrlChange,
                onDownloadFileNameChange = onDownloadFileNameChange,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
            )
        }

        // 运行控制：没有推理引擎就没有意义
        if (runnable) {
            OnDeviceRunControls(
                status = status,
                busy = busy,
                contextSize = contextSize,
                threads = threads,
                disableThinking = disableThinking,
                applyButtonLabel = applyButtonLabel,
                applyHint = applyHint,
                onContextSizeChange = onContextSizeChange,
                onThreadsChange = onThreadsChange,
                onDisableThinkingChange = onDisableThinkingChange,
                onStart = onStart,
                onStop = onStop,
                onApplyEndpoint = onApplyEndpoint,
            )
            OnDeviceLogArea(
                logs = logs,
                showLogs = showLogs,
                onToggleLogs = onToggleLogs,
            )
        }

        TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新状态") }
    }
}

@Composable
private fun UnavailableNotice(status: OnDeviceStatusDto) {
    Text(
        text = status.unavailableReason.ifBlank { "当前环境不支持端侧推理。" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = "端侧推理需要随 APK 一起打包的 aarch64 推理引擎。桌面端可通过环境变量 " +
            "LLAMA_SERVER_BIN 指向本机的 llama-server 来启用。" +
            "注意：下面的模型导入与下载不需要引擎，现在就能用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OnDeviceStatusLine(status: OnDeviceStatusDto) {
    val (label, color) = when (status.state) {
        OnDeviceStatusDto.STATE_RUNNING -> "运行中 · ${status.baseUrl}" to MaterialTheme.colorScheme.primary
        OnDeviceStatusDto.STATE_STARTING -> "正在加载模型…" to MaterialTheme.colorScheme.primary
        OnDeviceStatusDto.STATE_FAILED -> "启动失败" to MaterialTheme.colorScheme.error
        else -> "未运行" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
    if (status.contextSize > 0) {
        val details = buildString {
            append("上下文 ").append(status.contextSize)
            if (status.gpuLayers > 0) append(" · GPU 层 ").append(status.gpuLayers)
            append(" · PID ").append(status.pid)
        }
        Text(
            text = details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (status.message.isNotBlank()) {
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.state == OnDeviceStatusDto.STATE_FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    Text(
        text = "模型目录：${status.modelsDir}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OnDeviceModelPicker(
    status: OnDeviceStatusDto,
    selectedModel: String,
    enabled: Boolean,
    onSelectModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = status.models.firstOrNull { it.fileName == selectedModel }
    val label = current?.let { "${it.displayName} · ${formatBytes(it.sizeBytes)}" }
        ?: if (status.models.isEmpty()) "还没有模型" else "选择模型"

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && status.models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            status.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName)
                            Text(
                                text = listOfNotNull(
                                    formatBytes(model.sizeBytes),
                                    model.quantization.takeIf { it.isNotBlank() },
                                    model.architecture.takeIf { it.isNotBlank() },
                                    model.contextLength.takeIf { it > 0 }?.let { "ctx $it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!model.metadataOk) {
                                Text(
                                    text = "元数据读取失败，仍可尝试启动",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectModel(model.fileName)
                    },
                )
            }
        }
    }
    if (status.models.isEmpty()) {
        Text(
            text = "还没有模型：用下面的「选择 .gguf 文件」导入手机里已有的模型，或用「下载模型」从直链下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    current?.let { model ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = model.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onDeleteModel(model.fileName) },
                enabled = enabled && status.modelFile != model.fileName,
            ) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun OnDeviceImportArea(
    status: OnDeviceStatusDto,
    busy: Boolean,
    importing: Boolean,
    fileName: String,
    copiedBytes: Long,
    totalBytes: Long,
    onPick: () -> Unit,
    onCancel: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("从文件导入", style = MaterialTheme.typography.bodyLarge)
    Text(
        text = "手机里已经存好 .gguf 模型？直接选文件导入即可，原文件会保留一份。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (importing) {
        // 来源不上报大小时退化成不确定进度条——总比假装 0% 诚实
        if (totalBytes > 0) {
            LinearProgressIndicator(
                progress = { (copiedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = buildString {
                append(fileName.ifBlank { "正在导入…" })
                append(" · ").append(formatBytes(copiedBytes))
                if (totalBytes > 0) {
                    append(" / ").append(formatBytes(totalBytes))
                    val percent = ((copiedBytes * 100) / totalBytes).coerceIn(0L, 100L)
                    append("（").append(percent).append("%）")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text("取消导入") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // 下载/启动期间也不放开：避免几个大文件同时往手机存储里写
                onClick = onPick,
                enabled = !busy && status.modelsDir.isNotBlank(),
            ) {
                Text("选择 .gguf 文件")
            }
        }
        if (status.modelsDir.isBlank()) {
            Text(
                text = "还没拿到模型目录，请先点「刷新状态」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "导入后会复制到：${status.modelsDir}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnDeviceRunControls(
    status: OnDeviceStatusDto,
    busy: Boolean,
    contextSize: String,
    threads: String,
    disableThinking: Boolean,
    applyButtonLabel: String,
    applyHint: String,
    onContextSizeChange: (String) -> Unit,
    onThreadsChange: (String) -> Unit,
    onDisableThinkingChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApplyEndpoint: () -> Unit,
) {
    EditorField(
        value = contextSize,
        onValueChange = onContextSizeChange,
        label = "上下文长度",
        placeholder = "0 = 自动（默认 8192）",
        supportingText = "手机内存有限，不建议超过 8192；填 0 时按模型自带上限压到 8192。",
    )
    EditorField(
        value = threads,
        onValueChange = onThreadsChange,
        label = "线程数",
        placeholder = "0 = 由 llama.cpp 自动决定",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("关闭思考", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "端侧解码本来就慢，思考会成倍放大延迟、还容易把输出预算吃光。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = disableThinking, onCheckedChange = onDisableThinkingChange)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.running || status.starting) {
            OutlinedButton(onClick = onStop, enabled = !busy) { Text("停止") }
        } else {
            Button(
                onClick = onStart,
                enabled = !busy && status.models.isNotEmpty(),
            ) {
                Text(if (status.state == OnDeviceStatusDto.STATE_FAILED) "重试启动" else "启动")
            }
        }
        OutlinedButton(
            onClick = onApplyEndpoint,
            enabled = status.running && !busy,
        ) {
            Text(applyButtonLabel)
        }
    }
    Text(
        text = applyHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OnDeviceDownloadArea(
    status: OnDeviceStatusDto,
    busy: Boolean,
    downloadUrl: String,
    downloadFileName: String,
    onDownloadUrlChange: (String) -> Unit,
    onDownloadFileNameChange: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("下载模型", style = MaterialTheme.typography.bodyLarge)
    EditorField(
        value = downloadUrl,
        onValueChange = onDownloadUrlChange,
        label = "下载直链",
        placeholder = "https://huggingface.co/…/resolve/main/xxx-Q4_K_M.gguf",
        supportingText = "支持 HuggingFace 的 resolve 直链或任意 https 直链；会自动跟随跳转。",
    )
    EditorField(
        value = downloadFileName,
        onValueChange = onDownloadFileNameChange,
        label = "保存为（可留空）",
        placeholder = "留空则用链接里的文件名",
    )
    val download = status.download
    if (download != null && download.active) {
        LinearProgressIndicator(
            progress = { download.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = buildString {
                append(download.fileName).append(" · ")
                append(formatBytes(download.downloadedBytes))
                if (download.totalBytes > 0) {
                    append(" / ").append(formatBytes(download.totalBytes))
                    append("（").append(download.progressLabel).append("）")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancelDownload) { Text("取消下载") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDownload, enabled = !busy) { Text("开始下载") }
        }
        download?.let {
            when {
                it.error.isNotBlank() -> Text(
                    text = it.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                it.message.isNotBlank() -> Text(
                    text = it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnDeviceLogArea(
    logs: String,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
) {
    TextButton(onClick = onToggleLogs) {
        Text(if (showLogs) "隐藏运行日志" else "查看运行日志")
    }
    if (showLogs) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = logs.ifBlank { "（暂无日志）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "未知大小"
    bytes >= 1024L * 1024 * 1024 -> "${oneDecimal(bytes.toDouble() / (1024.0 * 1024 * 1024))} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun oneDecimal(value: Double): String = ((value * 10).roundToInt() / 10.0).toString()
