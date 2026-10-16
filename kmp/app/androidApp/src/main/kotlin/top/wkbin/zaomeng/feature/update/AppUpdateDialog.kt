package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.app.shared.AppMetadata
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateInfo
import top.wkbin.zaomeng.data.update.formatAppUpdateBytes

@Composable
fun AppUpdateDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    /** 下载按钮文案（桌面端跳转下载页时用“去下载”）。 */
    downloadLabel: String = "下载更新",
) {
    val status = downloadState.status.takeIf { downloadState.version == update.version }
        ?: AppUpdateDownloadStatus.Idle
    AlertDialog(
        onDismissRequest = { if (status != AppUpdateDownloadStatus.Downloading) onDismiss() },
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前版本 ${AppMetadata.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == AppUpdateDownloadStatus.Downloading) {
                    DownloadProgress(downloadState)
                }
                ReleaseNotes(update.releaseNotes)
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = status != AppUpdateDownloadStatus.Downloading) {
                Text(
                    when (status) {
                        AppUpdateDownloadStatus.Downloading -> "下载中"
                        AppUpdateDownloadStatus.Downloaded -> "安装"
                        AppUpdateDownloadStatus.Failed -> "重新下载"
                        AppUpdateDownloadStatus.Idle -> downloadLabel
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = status != AppUpdateDownloadStatus.Downloading,
            ) { Text("稍后") }
        },
    )
}

@Composable
private fun DownloadProgress(downloadState: AppUpdateDownloadState) {
    val hasTotal = downloadState.totalBytes > 0L
    if (hasTotal) {
        LinearProgressIndicator(
            progress = { (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = if (hasTotal) {
            val percentage = ((downloadState.downloadedBytes * 100L) / downloadState.totalBytes).coerceIn(0L, 100L)
            "$percentage%  ${formatAppUpdateBytes(downloadState.downloadedBytes)} / ${formatAppUpdateBytes(downloadState.totalBytes)}"
        } else {
            "已下载 ${formatAppUpdateBytes(downloadState.downloadedBytes)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 更新说明的排版。
 *
 * 之前的实现把正文**逐行原样**丢进 `Text`，只剥掉行首的 `- `/`* `。于是发布说明里
 * 的 `**加粗**`、反引号、`### 标题`、表格竖线会**连标记一起显示出来**，看起来潦草。
 * 现在按行解析成一个很小的 Markdown 子集再排版：
 *
 * - `#` / `##` / `###` → 小标题（加粗 + 主题色 + 上下留白）
 * - `- ` / `* ` / `• ` → 圆点条目；`1.` / `1)` → 序号条目
 * - `> ` → 引用；`---` / `***` → 分隔线
 * - 行内 `**粗体**` 与 `` `代码` `` 按样式渲染，其余标记不再外泄
 * - 表格行去掉竖线后按 ` · ` 连接（不假装能画表格，但至少不显示一堆 `|`）
 *
 * 解析是纯函数，且对无法识别的行**退化为普通段落**——宁可不美化，也不能丢内容。
 */
@Composable
private fun ReleaseNotes(releaseNotes: String) {
    val blocks = remember(releaseNotes) { parseReleaseNotes(releaseNotes) }
    LazyColumn(
        modifier = Modifier.heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (blocks.isEmpty()) {
            item { Text("本次更新暂未提供详细说明。", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(blocks) { block -> ReleaseNoteBlock(block) }
        }
    }
}

@Composable
private fun ReleaseNoteBlock(block: NoteBlock) {
    when (block) {
        is NoteBlock.Heading -> {
            Text(
                text = inlineMarkdown(block.text),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }

        is NoteBlock.Bullet -> BulletRow(marker = "•", text = block.text)

        is NoteBlock.Numbered -> BulletRow(marker = block.index, text = block.text)

        is NoteBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "▎",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = inlineMarkdown(block.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        NoteBlock.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is NoteBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BulletRow(marker: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = inlineMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

internal sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Numbered(val index: String, val text: String) : NoteBlock
    data class Quote(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
    data object Divider : NoteBlock
}

private val NUMBERED_ITEM = Regex("^(\\d{1,3})[.)]\\s+(.*)$")

// 必须**以竖线开头**才算表格分隔行。否则裸的 `---` 也会被它吃掉，
// 而 `---` 在本渲染器里是分隔线（见 parseReleaseNotes 的分支顺序）。
private val TABLE_SEPARATOR = Regex("^\\|\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

internal fun parseReleaseNotes(raw: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var inCodeBlock = false
    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("```") -> inCodeBlock = !inCodeBlock
            inCodeBlock -> blocks += NoteBlock.Paragraph(line)
            TABLE_SEPARATOR.matches(line) -> Unit
            line.matches(Regex("^([-*_])\\1{2,}$")) -> blocks += NoteBlock.Divider
            line.startsWith("### ") -> blocks += NoteBlock.Heading(line.removePrefix("### ").trim())
            line.startsWith("## ") -> blocks += NoteBlock.Heading(line.removePrefix("## ").trim())
            line.startsWith("# ") -> blocks += NoteBlock.Heading(line.removePrefix("# ").trim())
            line.startsWith("> ") -> blocks += NoteBlock.Quote(line.removePrefix("> ").trim())
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") ->
                blocks += NoteBlock.Bullet(line.drop(2).trim())
            line.startsWith("|") -> blocks += NoteBlock.Paragraph(tableRowToText(line))
            else -> {
                val numbered = NUMBERED_ITEM.matchEntire(line)
                if (numbered != null) {
                    blocks += NoteBlock.Numbered("${numbered.groupValues[1]}.", numbered.groupValues[2].trim())
                } else {
                    blocks += NoteBlock.Paragraph(line)
                }
            }
        }
    }
    return blocks
}

private fun tableRowToText(line: String): String =
    line.trim().trim('|').split('|').map(String::trim).filter(String::isNotEmpty).joinToString("  ·  ")

/**
 * 渲染行内 Markdown 的一个子集：`**粗体**`、`__粗体__`、`` `代码` ``、`*斜体*`。
 * 未闭合的标记按普通文本处理（不吞字符）。
 */
@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val boldColor = MaterialTheme.colorScheme.onSurface
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, boldColor, codeColor, codeBackground) {
        buildAnnotatedString {
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("**", index) -> {
                        val end = text.indexOf("**", index + 2)
                        if (end > index) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                append(text.substring(index + 2, end))
                            }
                            index = end + 2
                        } else {
                            append(text[index]); index++
                        }
                    }

                    text.startsWith("`", index) -> {
                        val end = text.indexOf('`', index + 1)
                        if (end > index) {
                            withStyle(
                                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = codeColor),
                            ) { append(text.substring(index + 1, end)) }
                            index = end + 1
                        } else {
                            append(text[index]); index++
                        }
                    }

                    text[index] == '*' && text.getOrNull(index + 1) != ' ' && text.indexOf('*', index + 1) > index -> {
                        val end = text.indexOf('*', index + 1)
                        withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    }

                    else -> {
                        append(text[index]); index++
                    }
                }
            }
        }
    }
}
