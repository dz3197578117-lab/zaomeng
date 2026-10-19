package top.wkbin.zaomeng.feature.crossover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.components.AppEmptyState
import top.wkbin.zaomeng.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CrossoverScreen(
    viewModel: CrossoverViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdRunId) {
        if (state.createdRunId.isNotBlank()) {
            val runId = state.createdRunId
            viewModel.consumeCreatedRun()
            onCreated(runId)
        }
    }
    Scaffold(topBar = { TopAppBar(
        title = {
            Column {
                Text("跨书卷共演", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Beta · 独立人物快照，不写回原书卷",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        },
    ) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentPadding = PaddingValues(AppSpacing.lg),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("测试功能 · 结果可能不稳定", style = MaterialTheme.typography.titleMedium)
                        Text("人物表现、世界设定和关系演化仍可能偏离预期。创建时只复制人物资料快照，共演产生的聊天、记忆、关系和修正不会写回任何原书卷。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { OutlinedTextField(state.title, viewModel::updateTitle, Modifier.fillMaxWidth(), label = { Text("共演空间名称") }, singleLine = true) }
            item { OutlinedTextField(state.worldSetting, viewModel::updateWorldSetting, Modifier.fillMaxWidth(), label = { Text("独立世界设定（可选）") }, minLines = 3, maxLines = 6) }
            item { Text("选择人物 ${state.selected.size}/$CROSSOVER_MAX_PARTICIPANTS", style = MaterialTheme.typography.titleMedium) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && state.runs.isEmpty()) {
                // 没有任何「已完成人物蒸馏」的书卷时，这个页面原本只有一排输入框和永远灰着的
                // 创建按钮——用户看不出是缺了什么。直接说清前置条件，并给一条回书卷架的路。
                item {
                    AppEmptyState(
                        title = "还没有可以共演的书卷",
                        description = "跨书卷共演要从不同书卷各取人物快照，因此至少需要两本已经完成人物蒸馏的书卷。先回书卷架导入正文并蒸馏出人物，再回来建空间。",
                        icon = Icons.Outlined.AutoAwesome,
                        action = {
                            Button(onClick = onBack) { Text("回书卷架") }
                        },
                    )
                }
            }
            items(state.runs, key = { it.runId }) { run ->
                AppCard(
                    variant = AppCardVariant.Elevated,
                    contentPadding = PaddingValues(AppSpacing.lg),
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text(run.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val itemWidth = (maxWidth - 16.dp) / 3
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = 3,
                            ) {
                                run.availableCharacters.forEach { character ->
                                    val choice = CrossoverChoice(run.runId, run.title, character)
                                    val checked = state.selected.any { it.runId == run.runId && it.character == character }
                                    val enabled = !state.creating && (checked || state.selected.size < CROSSOVER_MAX_PARTICIPANTS)
                                    Surface(
                                        modifier = Modifier
                                            .width(itemWidth)
                                            .height(48.dp)
                                            .clickable(enabled = enabled) { viewModel.toggle(choice) },
                                        shape = MaterialTheme.shapes.small,
                                        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            if (checked) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                            Text(
                                                character,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (state.runs.isNotEmpty()) item {
                Button(viewModel::create, Modifier.fillMaxWidth(), enabled = !state.creating && state.title.isNotBlank() && state.selected.size in 2..CROSSOVER_MAX_PARTICIPANTS && state.selected.map { it.runId }.distinct().size >= 2) {
                    if (state.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (state.creating) "正在创建独立空间..." else "创建共演空间", Modifier.padding(start = if (state.creating) 8.dp else 0.dp))
                }
            }
        }
    }
}
