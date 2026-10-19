package top.wkbin.zaomeng.feature.creation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.components.AppEmptyState
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 「创作」顶级页：工作台布局。
 *
 * 首屏回答三件事：有几本在写、人物档案齐不齐、从哪一本继续。
 * 书卡不再是一行文字 + 箭头，而是封面、人物完成度与“继续写”并列，
 * 让用户不用点进去就知道这本书现在是什么状态。
 */
@Composable
fun CreationScreen(
    onOpenRun: (String) -> Unit,
    onOpenBookshelf: () -> Unit,
    viewModel: CreationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewBook by rememberSaveable { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = AppSpacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item(key = "creation-header") {
                CreationHeader(
                    onCreate = {
                        viewModel.clearCreateError()
                        showNewBook = true
                    },
                )
            }

            when {
                state.loading -> item(key = "creation-loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                state.error.isNotBlank() -> item(key = "creation-error") {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        AppEmptyState(
                            title = "读取书卷失败",
                            description = state.error,
                            icon = Icons.Outlined.AutoStories,
                            action = { TextButton(onClick = viewModel::load) { Text("重试") } },
                        )
                    }
                }

                state.runs.isEmpty() -> item(key = "creation-empty") {
                    AppEmptyState(
                        title = "从第一本开始写",
                        description = "直接新建原创书卷，不必先导入原文；也可以在书卷架蒸馏一本，" +
                            "创作时带上人物与世界观。",
                        icon = Icons.Outlined.AutoStories,
                        action = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                            ) {
                                Button(onClick = {
                                    viewModel.clearCreateError()
                                    showNewBook = true
                                }) { Text("新建原创书卷") }
                                TextButton(onClick = onOpenBookshelf) { Text("去书卷架蒸馏") }
                            }
                        },
                    )
                }

                else -> {
                    item(key = "creation-stats") { CreationStats(state.runs) }
                    item(key = "creation-section") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xl),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "继续写",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${state.runs.size} 本",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.runs, key = RunManifestDto::runId) { run ->
                        CreationRunCard(run = run, onClick = { onOpenRun(run.runId) })
                    }
                    item(key = "creation-new") {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearCreateError()
                                showNewBook = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sm),
                        ) { Text("＋ 新建原创书卷") }
                    }
                }
            }
        }
    }

    if (showNewBook) {
        NewBookDialog(
            creating = state.creating,
            error = state.createError,
            onDismiss = {
                showNewBook = false
                viewModel.clearCreateError()
            },
            onCreate = { title, lead ->
                viewModel.createOriginalBook(title, lead) { runId ->
                    showNewBook = false
                    onOpenRun(runId)
                }
            },
        )
    }
}

@Composable
private fun CreationHeader(onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.xl, end = AppSpacing.xl, top = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("创作", style = MaterialTheme.typography.headlineLarge)
            Text(
                "今天想推进哪一本？",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Surface(
            onClick = onCreate,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "新建原创书卷", modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun CreationStats(runs: List<RunManifestDto>) {
    val characters = remember(runs) { runs.sumOf { it.artifactIndex.characters.size } }
    val pending = remember(runs) { runs.count { it.status != "ready" } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xl),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        StatCard(value = runs.size.toString(), label = "本书", modifier = Modifier.weight(1f))
        StatCard(value = characters.toString(), label = "位人物", modifier = Modifier.weight(1f))
        StatCard(value = pending.toString(), label = "待蒸馏", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CreationRunCard(run: RunManifestDto, onClick: () -> Unit) {
    val hint = creationStatusHint(run.status, run.isOriginal)
    val shape = MaterialTheme.shapes.large
    val total = run.progress.totalCharacters.takeIf { it > 0 } ?: run.artifactIndex.characters.size
    val completed = run.progress.completedCount.takeIf { it > 0 } ?: run.artifactIndex.characters.size
    val ratio = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.xl)
            .shadow(3.dp, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(AppSpacing.lg), verticalAlignment = Alignment.Top) {
            BookCover(title = run.title)
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = run.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (hint.isNotBlank()) append(hint).append(" · ")
                        if (run.updatedAt.isNotBlank()) {
                            append("更新于 ").append(run.updatedAt.toLocalDateTimeDisplay(""))
                        } else {
                            append("还没有正文")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hint.isNotBlank()) {
                        creationStatusColor(run.status, run.isOriginal)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.md)
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (total > 0) "人物档案 $completed/$total" else "人物档案未生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "继续写",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private val coverPalettes = listOf(
    listOf(Color(0xFF8C5A44), Color(0xFF5A3A2C)),
    listOf(Color(0xFF4F5F7B), Color(0xFF32405A)),
    listOf(Color(0xFF6B5B4F), Color(0xFF463A30)),
    listOf(Color(0xFF4F6B4F), Color(0xFF30452F)),
    listOf(Color(0xFF6B4F6B), Color(0xFF433043)),
    listOf(Color(0xFF4F5B6B), Color(0xFF303946)),
)

@Composable
private fun BookCover(title: String) {
    val palette = coverPalettes[((title.hashCode() % coverPalettes.size) + coverPalettes.size) % coverPalettes.size]
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(74.dp)
            .background(Brush.verticalGradient(palette), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).ifBlank { "书" },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun NewBookDialog(
    creating: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var lead by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("新建原创书卷") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(
                    text = "不用导入原文，也不用等蒸馏。建好后直接进章节工作台，" +
                        "写一句设定就能生成大纲。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("书名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = lead,
                    onValueChange = { lead = it.take(20) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("主角") },
                    singleLine = true,
                )
                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, lead) },
                enabled = !creating && title.isNotBlank() && lead.isNotBlank(),
            ) { Text(if (creating) "创建中…" else "开始创作") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") }
        },
    )
}

/** 未蒸馏的书卷也能写，这里只说明「上下文差在哪」，不做准入限制。 */
internal fun creationStatusHint(status: String, isOriginal: Boolean = false): String {
    if (isOriginal) return "原创 · 不需要蒸馏"
    return when (status) {
        "ready" -> ""
        "running" -> "蒸馏中 · 人物档案稍后补上"
        "pending", "draft" -> "未蒸馏 · 仅凭设定与大纲写"
        "failed" -> "蒸馏失败 · 仍可创作"
        "stopped" -> "蒸馏已停止 · 仍可创作"
        else -> ""
    }
}

@Composable
private fun creationStatusColor(status: String, isOriginal: Boolean): Color = when {
    isOriginal -> MaterialTheme.colorScheme.primary
    status == "running" -> MaterialTheme.colorScheme.primary
    status == "failed" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
