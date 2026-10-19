package top.wkbin.zaomeng.feature.creation

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.NovelPlanDto
import top.wkbin.zaomeng.data.api.WritingGenres
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.theme.AppSpacing

private val planChapterCounts = listOf(6, 8, 10)

/**
 * 一句话生成小说：说个想法 → AI 给方案 → 确认后才建书。
 *
 * 方案包含书名、类型、核心、设定与分章大纲；不确认就不会在书架上留下半成品。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelPlanSheet(
    state: CreationUiState,
    onPromptChange: (String) -> Unit,
    onPlan: (Int) -> Unit,
    onClear: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chapterCount by rememberSaveable { mutableIntStateOf(8) }

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
            item(key = "plan-header") {
                Column {
                    Text(
                        "一句话生成一本小说",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "说个想法，AI 把书名、人物、核心和分章大纲都搭好；确认后才会建书。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            if (state.plan == null) {
                item(key = "plan-input") {
                    OutlinedTextField(
                        value = state.planPrompt,
                        onValueChange = onPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("你想写什么") },
                        placeholder = {
                            Text("例如：一个能听见旧书里亡者低语的修补匠，接到一本会自己增页的账本")
                        },
                        supportingText = { Text("${state.planPrompt.length}/1200 · 越具体，方案越可用") },
                        minLines = 4,
                        maxLines = 8,
                        enabled = !state.planning,
                    )
                }
                item(key = "plan-count") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        Text(
                            "先搭几章",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            planChapterCounts.forEach { count ->
                                FilterChip(
                                    selected = chapterCount == count,
                                    onClick = { chapterCount = count },
                                    label = { Text("$count 章") },
                                )
                            }
                        }
                    }
                }
                item(key = "plan-submit") {
                    Button(
                        onClick = { onPlan(chapterCount) },
                        enabled = !state.planning && state.planPrompt.trim().length >= 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.planning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在定人物、排章节…")
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("生成方案")
                        }
                    }
                }
            }

            if (state.planError.isNotBlank()) {
                item(key = "plan-error") {
                    AppCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Text(state.planError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            state.plan?.let { plan ->
                item(key = "plan-preview") { PlanPreview(plan) }
                item(key = "plan-actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Button(
                            onClick = onCreate,
                            enabled = !state.creatingFromPlan,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.creatingFromPlan) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在建书、写大纲…")
                            } else {
                                Text("就按这个写")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            OutlinedButton(
                                onClick = onClear,
                                enabled = !state.creatingFromPlan,
                                modifier = Modifier.weight(1f),
                            ) { Text("改描述，重新生成") }
                            TextButton(
                                onClick = onDismiss,
                                enabled = !state.creatingFromPlan,
                                modifier = Modifier.weight(1f),
                            ) { Text("先不写") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanPreview(plan: NovelPlanDto) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        AppCard(
            variant = AppCardVariant.Filled,
            contentPadding = PaddingValues(AppSpacing.lg),
        ) {
            Text(
                plan.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val genre = WritingGenres.byId(plan.genreId)
                if (genre != null) {
                    Text(
                        genre.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "主角 ${plan.lead} · ${plan.outline.size} 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.premise.isNotBlank()) {
                SelectionContainer {
                    Text(
                        plan.premise,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = AppSpacing.md),
                    )
                }
            }
        }

        if (plan.core.theme.isNotBlank()) {
            AppCard(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                contentPadding = PaddingValues(AppSpacing.md),
            ) {
                Text("主题", style = MaterialTheme.typography.labelSmall)
                Text(
                    plan.core.theme,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (plan.bible.characters.isNotEmpty()) {
            Text("人物", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            plan.bible.characters.take(4).forEach { character ->
                AppCard(
                    variant = AppCardVariant.Filled,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                character.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (character.role.isNotBlank()) {
                                Text(
                                    character.role,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (character.desire.isNotBlank()) {
                                Text(
                                    "想要：${character.desire}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text("分章大纲", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        plan.outline.forEachIndexed { index, chapter ->
            AppCard(
                variant = AppCardVariant.Filled,
                contentPadding = PaddingValues(AppSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            chapter.title.ifBlank { "第 ${index + 1} 章" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chapter.goal.isNotBlank()) {
                            Text(
                                chapter.goal,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
