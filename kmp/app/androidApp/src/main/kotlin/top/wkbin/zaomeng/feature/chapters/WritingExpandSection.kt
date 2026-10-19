package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.DraftVersionDto
import top.wkbin.zaomeng.data.api.SceneCardDto
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.theme.AppSpacing
import kotlinx.coroutines.launch

/**
 * 扩写：场景卡 → 两版初稿 → 采用一版，再对某一段做局部重写。
 *
 * 刻意不做「一键成章」：初稿是给人改的，多一版比多十章有用。
 */
@Composable
internal fun ExpandSection(
    state: ChaptersUiState,
    onSelectChapter: (String) -> Unit,
    onGenerateSceneCards: (String) -> Unit,
    onSaveSceneCards: (String) -> Unit,
    onDraftVersions: (String) -> Unit,
    onApplyVersion: (String, DraftVersionDto) -> Unit,
    onRewriteInput: (String) -> Unit,
    onRewriteInstruction: (String) -> Unit,
    onRewrite: () -> Unit,
) {
    var pendingVersion by remember { mutableStateOf<DraftVersionDto?>(null) }
    // 已经拆出来但还没落盘的场景卡：重新拆之前必须先问一句，
    // 否则 generateSceneCards 一开头就把它们清掉，攒到满意的四张卡白费。
    var confirmRegenerate by remember { mutableStateOf(false) }
    val effective = state.expandChapterId.ifBlank {
        state.chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
    }
    val chapter = state.chapters.firstOrNull { it.chapterId == effective }
    val savedCards = chapter?.sceneCards.orEmpty()
    val cards = state.sceneCards.ifEmpty { savedCards }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text(
            text = "先把这一章拆成几场戏，再按场景卡写两版初稿。挑一版采用后，再逐段重写。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChapterPicker(state.chapters, state.expandChapterId) { onSelectChapter(it) }

        Text("1. 场景卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Button(
            onClick = {
                if (state.sceneCards.isNotEmpty()) {
                    confirmRegenerate = true
                } else {
                    onGenerateSceneCards(effective)
                }
            },
            enabled = !state.generatingSceneCards && !state.savingSceneCards && effective.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generatingSceneCards) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在拆场景…")
            } else {
                Text(if (savedCards.isEmpty()) "拆成场景卡" else "重新拆场景卡")
            }
        }
        if (cards.isNotEmpty()) {
            cards.forEachIndexed { index, card -> SceneCardItem(index, card) }
            if (state.sceneCards.isNotEmpty()) {
                Button(
                    onClick = { onSaveSceneCards(effective) },
                    enabled = !state.savingSceneCards,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.savingSceneCards) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("保存这组场景卡")
                }
            }
        }

        HorizontalDivider()
        Text("2. 两版初稿", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = if (savedCards.isEmpty()) {
                "先保存场景卡，初稿才有骨架。"
            } else {
                "两版写法不同：一版贴着内心，一版贴着动作。挑一版用，或两版都不满意再生成。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onDraftVersions(effective) },
            enabled = savedCards.isNotEmpty() && !state.draftingVersions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.draftingVersions) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在写两版…")
            } else {
                Text("写两版初稿")
            }
        }
        state.draftVersions.forEach { version ->
            DraftVersionItem(
                version = version,
                applying = state.applyingVersion == version.label,
                onApply = { pendingVersion = version },
            )
        }

        HorizontalDivider()
        Text("3. 重写这一段", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "把一段「合理但无聊」的文字改成有性格、有细节的表达。只改这一段，不动剧情。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.rewriteInput,
            onValueChange = onRewriteInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("要改的段落") },
            placeholder = { Text("粘贴一段正文，最多 4000 字") },
            minLines = 4,
            maxLines = 8,
            enabled = !state.rewriting,
        )
        OutlinedTextField(
            value = state.rewriteInstruction,
            onValueChange = onRewriteInstruction,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("改写要求（可选）") },
            placeholder = { Text("例如：更克制，少用形容词；让他说话更冲") },
            singleLine = true,
            enabled = !state.rewriting,
        )
        Button(
            onClick = onRewrite,
            enabled = !state.rewriting && state.rewriteInput.trim().length >= 10,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.rewriting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在改…")
            } else {
                Text("给我几个改写版本")
            }
        }
        state.rewriteOptions.forEach { option -> RewriteOptionItem(option.label, option.content) }
    }

    pendingVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingVersion = null },
            title = { Text("采用版本 ${version.label}？") },
            text = { Text("会用这一版覆盖本章正文，原有正文无法恢复。标题、目标与场景卡保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingVersion = null
                        onApplyVersion(effective, version)
                    },
                ) { Text("覆盖并采用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVersion = null }) { Text("取消") }
            },
        )
    }

    if (confirmRegenerate) {
        val pendingCards = state.sceneCards.size
        val savedNote = if (savedCards.isEmpty()) "。" else "；已保存的那组不受影响。"
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("丢弃未保存的场景卡并重新拆？") },
            text = {
                Text("现在这 $pendingCards 张场景卡还没保存，重新拆会直接丢掉它们$savedNote")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRegenerate = false
                        onGenerateSceneCards(effective)
                    },
                ) { Text("丢弃并重新拆") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SceneCardItem(index: Int, card: SceneCardDto) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        variant = AppCardVariant.Filled,
        contentPadding = PaddingValues(AppSpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "场景 ${index + 1} · ${card.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val whenWhere = listOf(card.time, card.place)
                .filter(String::isNotBlank).joinToString(" · ")
            if (whenWhere.isNotBlank()) {
                Text(whenWhere, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (card.characters.isNotEmpty()) {
                Text(
                    card.characters.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.goal.isNotBlank()) Text("目标：${card.goal}", style = MaterialTheme.typography.bodySmall)
            if (card.conflict.isNotBlank()) Text("阻力：${card.conflict}", style = MaterialTheme.typography.bodySmall)
            if (card.turn.isNotBlank()) Text("变化：${card.turn}", style = MaterialTheme.typography.bodySmall)
            if (card.exit.isNotBlank()) {
                Text(
                    "离场：${card.exit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DraftVersionItem(version: DraftVersionDto, applying: Boolean, onApply: () -> Unit) {
    var expanded by remember(version.label) { mutableStateOf(false) }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        variant = AppCardVariant.Filled,
        contentPadding = PaddingValues(AppSpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "版本 ${version.label}" + if (version.approach.isNotBlank()) " · ${version.approach}" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    text = if (expanded) version.content else version.content.take(240) + if (version.content.length > 240) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开全文（${version.content.length} 字）")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onApply, enabled = !applying) {
                    if (applying) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("采用这一版")
                }
            }
        }
    }
}

@Composable
private fun RewriteOptionItem(label: String, content: String) {
    val clipboard = rememberClipboardTextWriter()
    val scope = rememberCoroutineScope()
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        variant = AppCardVariant.Filled,
        contentPadding = PaddingValues(AppSpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label.ifBlank { "改写" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(content, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { scope.launch { clipboard(content) } },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制")
            }
        }
    }
}
