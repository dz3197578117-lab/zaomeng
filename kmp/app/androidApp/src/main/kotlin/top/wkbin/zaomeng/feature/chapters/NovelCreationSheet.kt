package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.NovelOutlineChapterDto
import top.wkbin.zaomeng.ui.theme.AppSpacing

private val novelGenres = listOf("都市", "古风", "悬疑", "科幻", "奇幻", "言情")
private val novelPerspectives = listOf("第三人称限知", "第一人称")
private val novelChapterCounts = listOf(4, 6, 8, 10, 12)

/**
 * AI 小说创作：设定 → 分章大纲 → 写入章节。
 *
 * 分成两步而不是「一键生成整本书」，是刻意的：
 * - 大纲是**唯一需要人判断**的环节（人物动机、章节钩子），确认成本低；
 * - 正文生成慢（每章 2000+ 字），失败或风格不合时，不该已经写了十章。
 *
 * 大纲只在确认后才写入；写正文则由章节卡上的「AI 成稿」逐章触发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelCreationSheet(
    state: ChaptersUiState,
    onGenerate: (premise: String, chapterCount: Int, genre: String, perspective: String, style: String) -> Unit,
    onCreateChapters: (perspective: String) -> Unit,
    onClearOutline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var premise by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var perspective by rememberSaveable { mutableStateOf(novelPerspectives.first()) }
    var style by rememberSaveable { mutableStateOf("") }
    var chapterCount by rememberSaveable { mutableIntStateOf(6) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item(key = "novel-header") {
                Column {
                    Text(
                        text = "AI 小说创作",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "一句设定 → 分章大纲 → 逐章成稿",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.outline.isEmpty()) {
                item(key = "novel-premise") {
                    OutlinedTextField(
                        value = premise,
                        onValueChange = { premise = it.take(800) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("故事设定") },
                        placeholder = { Text("例如：一个能听见旧书里亡者低语的修补匠，接到一本会自己增页的账本") },
                        supportingText = { Text("${premise.length}/800 · 越具体，大纲越可用") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.generatingOutline,
                    )
                }
                item(key = "novel-genre") {
                    ChoiceGroup(
                        title = "题材",
                        options = novelGenres,
                        selected = genre,
                        onSelect = { genre = if (genre == it) "" else it },
                    )
                }
                item(key = "novel-perspective") {
                    ChoiceGroup(
                        title = "叙事视角",
                        options = novelPerspectives,
                        selected = perspective,
                        onSelect = { perspective = it },
                    )
                }
                item(key = "novel-count") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("章节数", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            novelChapterCounts.forEach { count ->
                                FilterChip(
                                    selected = chapterCount == count,
                                    onClick = { chapterCount = count },
                                    label = { Text("$count 章") },
                                )
                            }
                        }
                    }
                }
                item(key = "novel-style") {
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文风补充（可选）") },
                        placeholder = { Text("如：冷峻、留白多、对话密集") },
                        singleLine = true,
                        enabled = !state.generatingOutline,
                    )
                }
                item(key = "novel-generate") {
                    Button(
                        onClick = { onGenerate(premise, chapterCount, genre, perspective, style) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.generatingOutline && premise.trim().length >= 4,
                    ) {
                        if (state.generatingOutline) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在设计分章…")
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("生成分章大纲")
                        }
                    }
                }
                item(key = "novel-hint") {
                    Text(
                        text = "大纲不会直接写入：确认后才会创建章节，避免不满意的结果留在书卷里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item(key = "outline-title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "分章大纲",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${state.outline.size} 章 · 确认后写入章节列表",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onClearOutline, enabled = !state.creatingFromOutline) {
                            Text("重新设定")
                        }
                    }
                }
                itemsIndexed(state.outline, key = { index, _ -> "outline-$index" }) { index, chapter ->
                    OutlineRow(index = index, chapter = chapter)
                }
                item(key = "outline-actions") {
                    Button(
                        onClick = { onCreateChapters(perspective) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.creatingFromOutline,
                    ) {
                        if (state.creatingFromOutline) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在写入…")
                        } else {
                            Text("写入 ${state.outline.size} 章大纲")
                        }
                    }
                }
                item(key = "outline-hint") {
                    Text(
                        text = "写入后每章是空白草稿，点章节卡上的「AI 成稿」逐章生成正文。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceGroup(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

@Composable
private fun OutlineRow(index: Int, chapter: NovelOutlineChapterDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.title.ifBlank { "第 ${index + 1} 章" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapter.goal.isNotBlank()) {
                Text(
                    text = chapter.goal,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
