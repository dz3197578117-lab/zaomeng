package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.ForeshadowDto
import top.wkbin.zaomeng.data.api.StoryCharacterDto
import top.wkbin.zaomeng.data.api.TimelineEventDto
import top.wkbin.zaomeng.ui.theme.AppSpacing

/** 世界观人工编辑：生成之后总要改两笔，不能只让 AI 重写整份设定。 */
@Composable
internal fun WorldEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑世界观") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(4000) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                placeholder = { Text("只写会影响人物选择的规则、禁忌与代价") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CharacterEditDialog(
    initial: StoryCharacterDto,
    onDismiss: () -> Unit,
    onSave: (StoryCharacterDto) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var role by remember(initial) { mutableStateOf(initial.role) }
    var desire by remember(initial) { mutableStateOf(initial.desire) }
    var secret by remember(initial) { mutableStateOf(initial.secret) }
    var voice by remember(initial) { mutableStateOf(initial.voice) }
    var arcStart by remember(initial) { mutableStateOf(initial.arcStart) }
    var arcTurn by remember(initial) { mutableStateOf(initial.arcTurn) }
    var arcEnd by remember(initial) { mutableStateOf(initial.arcEnd) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "新增人物" else "编辑人物") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("姓名") }, singleLine = true)
                OutlinedTextField(role, { role = it.take(200) }, label = { Text("身份与处境") })
                OutlinedTextField(desire, { desire = it.take(200) }, label = { Text("当下最想要什么") })
                OutlinedTextField(secret, { secret = it.take(200) }, label = { Text("不愿被知道的秘密") })
                OutlinedTextField(voice, { voice = it.take(160) }, label = { Text("说话方式") })
                Text("人物弧光", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(arcStart, { arcStart = it.take(200) }, label = { Text("起点状态") })
                OutlinedTextField(arcTurn, { arcTurn = it.take(200) }, label = { Text("哪件事让他不能再原地不动") })
                OutlinedTextField(arcEnd, { arcEnd = it.take(200) }, label = { Text("终点状态") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        StoryCharacterDto(
                            name = name,
                            role = role,
                            desire = desire,
                            secret = secret,
                            voice = voice,
                            arcStart = arcStart,
                            arcTurn = arcTurn,
                            arcEnd = arcEnd,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun TimelineEditDialog(
    initial: TimelineEventDto,
    onDismiss: () -> Unit,
    onSave: (TimelineEventDto) -> Unit,
) {
    var time by remember(initial) { mutableStateOf(initial.time) }
    var place by remember(initial) { mutableStateOf(initial.place) }
    var event by remember(initial) { mutableStateOf(initial.event) }
    var chapter by remember(initial) { mutableStateOf(initial.chapter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.event.isBlank()) "新增事件" else "编辑事件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(time, { time = it.take(60) }, label = { Text("何时") }, singleLine = true)
                OutlinedTextField(place, { place = it.take(60) }, label = { Text("何地") }, singleLine = true)
                OutlinedTextField(event, { event = it.take(300) }, label = { Text("谁做了什么、导致什么") }, minLines = 2)
                OutlinedTextField(chapter, { chapter = it.take(60) }, label = { Text("对应章节（可留空）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(TimelineEventDto(time, place, event, chapter)) },
                enabled = event.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ForeshadowEditDialog(
    initial: ForeshadowDto,
    onDismiss: () -> Unit,
    onSave: (ForeshadowDto) -> Unit,
) {
    var content by remember(initial) { mutableStateOf(initial.content) }
    var plantedIn by remember(initial) { mutableStateOf(initial.plantedIn) }
    var payoffIn by remember(initial) { mutableStateOf(initial.payoffIn) }
    var status by remember(initial) { mutableStateOf(initial.status.ifBlank { "open" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.content.isBlank()) "新增伏笔" else "编辑伏笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(content, { content = it.take(200) }, label = { Text("伏笔内容") }, minLines = 2)
                OutlinedTextField(plantedIn, { plantedIn = it.take(60) }, label = { Text("埋在哪一章") }, singleLine = true)
                OutlinedTextField(payoffIn, { payoffIn = it.take(60) }, label = { Text("打算在哪一章回收") }, singleLine = true)
                Text("状态", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    listOf("open" to "未回收", "paid" to "已回收", "dropped" to "已放弃").forEach { (id, label) ->
                        FilterChip(
                            selected = status == id,
                            onClick = { status = id },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ForeshadowDto(content, plantedIn, payoffIn, status)) },
                enabled = content.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ConfirmDeleteDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除$label？") },
        text = { Text("删除后需要重新生成或手动补回，设定集本身不会自动恢复。") },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
