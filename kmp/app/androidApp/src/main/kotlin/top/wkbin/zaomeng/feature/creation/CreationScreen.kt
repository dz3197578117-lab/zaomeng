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
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * 底栏的入口不该直接落在某个书卷上——用户还没选书。所以这里先做一件事：
 * 把「可以写的书」列出来，选一本再进入它的创作工作台（章节页会自动展开 AI 创作弹层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationScreen(
    onOpenRun: (String) -> Unit,
    onOpenBookshelf: () -> Unit,
    viewModel: CreationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
                    title = "还没有可以创作的书",
                    description = "AI 创作需要人物档案做上下文。先去书卷架蒸馏一本，完成后这里就会出现。",
                    icon = Icons.Outlined.AutoStories,
                    action = { TextButton(onClick = onOpenBookshelf) { Text("去书卷架") } },
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
                            text = "人物与世界观会带进创作",
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
                        "正文按章生成，写坏一章只损失一章。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CreationRunCard(run: RunManifestDto, onClick: () -> Unit) {
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
                Text(
                    text = buildString {
                        append("${run.artifactIndex.characters.size} 位人物")
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
