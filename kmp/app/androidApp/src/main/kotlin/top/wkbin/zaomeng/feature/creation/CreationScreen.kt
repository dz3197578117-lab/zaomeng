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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.components.AppEmptyState
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 「创作」顶级页。
 *
 * 底栏的入口不该直接落在某个书卷上——用户还没选书。所以这里做两件事：
 * 列出所有可以写的书卷，以及直接新建一本原创书卷（不必先导入原文蒸馏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationScreen(
    onOpenRun: (String) -> Unit,
    onOpenBookshelf: () -> Unit,
    viewModel: CreationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewBook by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("创作", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.loading) {
                                "正在读取书卷…"
                            } else {
                                "${state.runs.size} 本书可以写"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearCreateError()
                            showNewBook = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建原创书卷")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.error.isNotBlank() -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                AppEmptyState(
                    title = "读取书卷失败",
                    description = state.error,
                    icon = Icons.Outlined.AutoStories,
                    action = { TextButton(onClick = viewModel::load) { Text("重试") } },
                )
            }

            state.runs.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                AppEmptyState(
                    title = "从第一本开始写",
                    description = "可以直接新建原创书卷，不必先导入原文；" +
                        "也可以在书卷架蒸馏一本，创作时带上人物与世界观。",
                    icon = Icons.Outlined.AutoStories,
                    action = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        ) {
                            Button(
                                onClick = {
                                    viewModel.clearCreateError()
                                    showNewBook = true
                                },
                            ) { Text("新建原创书卷") }
                            TextButton(onClick = onOpenBookshelf) { Text("去书卷架蒸馏") }
                        }
                    },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(
                    start = AppSpacing.lg,
                    top = AppSpacing.lg,
                    end = AppSpacing.lg,
                    bottom = AppSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                item(key = "creation-intro") { CreationIntroCard() }
                item(key = "creation-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "选择书卷",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "蒸馏过的会带上人物与世界观",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.runs, key = RunManifestDto::runId) { run ->
                    CreationRunCard(run = run, onClick = { onOpenRun(run.runId) })
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
private fun CreationIntroCard() {
    AppCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        contentPadding = PaddingValues(AppSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(AppSpacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "AI 小说创作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "一句设定 → 分章大纲 → 逐章成稿。大纲先预览、确认后才写入；" +
                        "正文按章生成，写坏一章只损失一章。没有蒸馏过的书也能写。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CreationRunCard(run: RunManifestDto, onClick: () -> Unit) {
    val hint = creationStatusHint(run.status, run.isOriginal)
    AppCard(
        variant = AppCardVariant.Outlined,
        onClick = onClick,
        contentPadding = PaddingValues(AppSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookSpine(title = run.title)
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = run.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint.isNotBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = creationStatusColor(run.status, run.isOriginal),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    text = buildString {
                        val count = run.artifactIndex.characters.size
                        append(if (count > 0) "$count 位人物" else "人物档案未生成")
                        if (run.updatedAt.isNotBlank()) {
                            append(" · 更新于 ").append(run.updatedAt.toLocalDateTimeDisplay(""))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                val lead = run.artifactIndex.characters.firstOrNull()?.preview?.coreIdentity.orEmpty()
                if (lead.isNotBlank()) {
                    Text(
                        text = lead,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** 书脊色块：按标题稳定取色，同一本书在书架与创作页永远同色。 */
@Composable
private fun BookSpine(title: String) {
    val palette = listOf(
        Color(0xFF4F6B4F),
        Color(0xFF6B4F6B),
        Color(0xFF4F5F7B),
        Color(0xFF7B6B4F),
        Color(0xFF6B5B4F),
        Color(0xFF5B6B7B),
    )
    val color = palette[((title.hashCode() % palette.size) + palette.size) % palette.size]
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(62.dp)
            .background(color, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).ifBlank { "书" },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
