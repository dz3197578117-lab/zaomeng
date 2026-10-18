package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.ForeshadowDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.StoryCharacterDto
import top.wkbin.zaomeng.data.api.TimelineEventDto
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 人工管理设定条目：人物、时间线、伏笔的增删改。
 *
 * AI 生成的设定只是初稿；长篇里人物会改主意、伏笔会改回收方式，
 * 没有人工增删改，设定圣经就会和正文脱节。
 */
@Composable
internal fun BibleManageSection(bible: StoryBibleDto, onUpdate: (StoryBibleDto) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingWorld by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<Pair<Int, StoryCharacterDto>?>(null) }
    var editingEvent by remember { mutableStateOf<Pair<Int, TimelineEventDto>?>(null) }
    var editingForeshadow by remember { mutableStateOf<Pair<Int, ForeshadowDto>?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "收起设定管理" else "人工管理设定条目",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "人物 / 时间线 / 伏笔",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!expanded) return@Column

        Text("世界观", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { editingWorld = true }, modifier = Modifier.fillMaxWidth()) {
            Text("编辑世界观")
        }

        Text("人物", style = MaterialTheme.typography.labelLarge)
        if (bible.characters.isEmpty()) {
            Text(
                "还没有人物条目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bible.characters.forEachIndexed { index, character ->
            ManageRow(
                text = character.name + if (character.role.isNotBlank()) " · ${character.role}" else "",
                onEdit = { editingCharacter = index to character },
                onDelete = {
                    deleting = "人物「${character.name}」" to {
                        onUpdate(bible.copy(characters = bible.characters.filterIndexed { i, _ -> i != index }))
                    }
                },
            )
        }
        OutlinedButton(
            onClick = { editingCharacter = -1 to StoryCharacterDto() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("新增人物") }

        Text("时间线", style = MaterialTheme.typography.labelLarge)
        if (bible.timeline.isEmpty()) {
            Text(
                "还没有时间线事件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bible.timeline.forEachIndexed { index, event ->
            ManageRow(
                text = listOf(event.time, event.event).filter(String::isNotBlank).joinToString(" · "),
                onEdit = { editingEvent = index to event },
                onDelete = {
                    deleting = "这条时间线事件" to {
                        onUpdate(bible.copy(timeline = bible.timeline.filterIndexed { i, _ -> i != index }))
                    }
                },
            )
        }
        OutlinedButton(
            onClick = { editingEvent = -1 to TimelineEventDto() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("新增事件") }

        Text("伏笔", style = MaterialTheme.typography.labelLarge)
        if (bible.foreshadowings.isEmpty()) {
            Text(
                "还没有伏笔条目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bible.foreshadowings.forEachIndexed { index, item ->
            ManageRow(
                text = item.content + " · " + foreshadowStatusLabel(item.status),
                onEdit = { editingForeshadow = index to item },
                onDelete = {
                    deleting = "这条伏笔" to {
                        onUpdate(
                            bible.copy(
                                foreshadowings = bible.foreshadowings.filterIndexed { i, _ -> i != index },
                            ),
                        )
                    }
                },
            )
        }
        OutlinedButton(
            onClick = { editingForeshadow = -1 to ForeshadowDto() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("新增伏笔") }
    }

    if (editingWorld) {
        WorldEditDialog(
            initial = bible.world,
            onDismiss = { editingWorld = false },
            onSave = { world ->
                onUpdate(bible.copy(world = world))
                editingWorld = false
            },
        )
    }
    editingCharacter?.let { (index, character) ->
        CharacterEditDialog(
            initial = character,
            onDismiss = { editingCharacter = null },
            onSave = { updated ->
                val list = if (index < 0) {
                    bible.characters + updated
                } else {
                    bible.characters.toMutableList().apply { this[index] = updated }
                }
                onUpdate(bible.copy(characters = list.take(12)))
                editingCharacter = null
            },
        )
    }
    editingEvent?.let { (index, event) ->
        TimelineEditDialog(
            initial = event,
            onDismiss = { editingEvent = null },
            onSave = { updated ->
                val list = if (index < 0) {
                    bible.timeline + updated
                } else {
                    bible.timeline.toMutableList().apply { this[index] = updated }
                }
                onUpdate(bible.copy(timeline = list.take(60)))
                editingEvent = null
            },
        )
    }
    editingForeshadow?.let { (index, item) ->
        ForeshadowEditDialog(
            initial = item,
            onDismiss = { editingForeshadow = null },
            onSave = { updated ->
                val list = if (index < 0) {
                    bible.foreshadowings + updated
                } else {
                    bible.foreshadowings.toMutableList().apply { this[index] = updated }
                }
                onUpdate(bible.copy(foreshadowings = list.take(40)))
                editingForeshadow = null
            },
        )
    }
    deleting?.let { (label, action) ->
        ConfirmDeleteDialog(
            label = label,
            onDismiss = { deleting = null },
            onConfirm = action,
        )
    }
}

@Composable
private fun ManageRow(text: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.ifBlank { "（空）" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun foreshadowStatusLabel(status: String): String = when (status) {
    "paid" -> "已回收"
    "dropped" -> "已放弃"
    else -> "未回收"
}
