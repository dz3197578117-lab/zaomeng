package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.DraftVersionDto
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.WritingGenres
import top.wkbin.zaomeng.data.api.WritingProfileDto
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.theme.AppSpacing

private val writingTabs = listOf("设定", "类型", "扩写", "救援", "检查")
private val rescueKinds = listOf(
    "plot" to "情节走向",
    "dialogue" to "对话",
    "scene" to "场景",
    "twist" to "反转",
)
private val chapterLengthOptions = listOf(1500, 2500, 3000, 3500)

/**
 * 写作助手：设定集、类型模版、卡文救援、润色检查。
 *
 * 四件事放在同一个弹层里，是因为它们服务于同一个动作——「把这一章写下去」。
 * 拆成四个入口会让作者在章节页和工具页之间来回跳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WritingToolSheet(
    state: ChaptersUiState,
    initialTab: Int = 0,
    onLoad: () -> Unit,
    onGenerateBible: (String, String) -> Unit,
    onSaveBible: () -> Unit,
    onSaveBibleNow: (StoryBibleDto) -> Unit,
    onSaveProfile: (WritingProfileDto) -> Unit,
    /**
     * 编辑副本写回 [ChaptersUiState.writingDrafts]。
     *
     * 必填、故意不给默认空实现：默认空实现会让「打字→写回 UiState」静默失效，
     * 表现为输入框打不进字，比丢字更难查。
     */
    onDraftsChange: (WritingDrafts) -> Unit,
    onRescueChapter: (String) -> Unit,
    onRescueProblem: (String) -> Unit,
    onRescueKind: (String) -> Unit,
    onRescue: () -> Unit,
    onGenerateCore: (String) -> Unit,
    onSaveCore: () -> Unit,
    onSelectExpandChapter: (String) -> Unit,
    onGenerateSceneCards: (String) -> Unit,
    onSaveSceneCards: (String) -> Unit,
    onDraftVersions: (String) -> Unit,
    onApplyVersion: (String, DraftVersionDto) -> Unit,
    onRewriteInput: (String) -> Unit,
    onRewriteInstruction: (String) -> Unit,
    onRewrite: () -> Unit,
    onCheckConsistency: () -> Unit,
    onRunFinalReview: () -> Unit,
    onMarkApproved: (Boolean) -> Unit,
    onSelectReviewChapter: (String) -> Unit,
    onReviewChapter: (String) -> Unit,
    onPolish: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 编辑副本以 UiState 为准：关掉弹层、旋屏、切后台回来都还在。
    // 还没播种（理论上一打开就会 onLoad 播种）时按已保存值兜底，保证输入框永远有值。
    val drafts = state.writingDrafts ?: writingDraftsSeededFrom(state.writingProfile)
    val unsaved = hasUnsavedWritingDrafts(state.writingDrafts, state.writingProfile)
    var confirmClose by remember { mutableStateOf(false) }
    val unsavedLatest = rememberUpdatedState(unsaved)
    val sheetScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // 有未保存的类型设置时，不许下滑/点空白把草稿扔掉；
        // 出口是标题行的「关闭」，那里会先问一句。保存成功后 unsaved 变 false，下滑恢复。
        confirmValueChange = { target -> target != SheetValue.Hidden || !unsavedLatest.value },
    )
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    LaunchedEffect(Unit) { onLoad() }

    val requestClose: () -> Unit = {
        if (unsaved) confirmClose = true else onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = requestClose,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item(key = "writing-tools-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "写作助手",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "设定 → 类型 → 卡文 → 检查，都在这本书里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unsaved) {
                        Text(
                            text = "未保存",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = AppSpacing.sm),
                        )
                    }
                    TextButton(onClick = requestClose, enabled = !state.savingProfile) { Text("关闭") }
                }
            }
            item(key = "writing-tools-tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    writingTabs.forEachIndexed { index, label ->
                        FilterChip(
                            selected = tab == index,
                            onClick = { tab = index },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (state.error.isNotBlank()) {
                item(key = "writing-tools-error") {
                    AppCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Text(state.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.message.isNotBlank()) {
                item(key = "writing-tools-message") {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            when (tab) {
                0 -> item(key = "writing-tools-bible") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        StoryCoreSection(state, drafts, onDraftsChange, onGenerateCore, onSaveCore)
                        HorizontalDivider()
                        StoryBibleSection(state, drafts, onDraftsChange, onGenerateBible, onSaveBible)
                        HorizontalDivider()
                        BibleExtraSection(state.storyBible)
                        HorizontalDivider()
                        BibleManageSection(state.storyBible, onSaveBibleNow)
                    }
                }

                1 -> item(key = "writing-tools-genre") {
                    GenreSection(state, drafts, onDraftsChange, onSaveProfile)
                }

                2 -> item(key = "writing-tools-expand") {
                    ExpandSection(
                        state = state,
                        onSelectChapter = onSelectExpandChapter,
                        onGenerateSceneCards = onGenerateSceneCards,
                        onSaveSceneCards = onSaveSceneCards,
                        onDraftVersions = onDraftVersions,
                        onApplyVersion = onApplyVersion,
                        onRewriteInput = onRewriteInput,
                        onRewriteInstruction = onRewriteInstruction,
                        onRewrite = onRewrite,
                    )
                }

                3 -> item(key = "writing-tools-rescue") {
                    RescueSection(
                        state = state,
                        onChapter = onRescueChapter,
                        onProblem = onRescueProblem,
                        onKind = onRescueKind,
                        onRescue = onRescue,
                    )
                }

                else -> item(key = "writing-tools-review") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        ReviewSection(
                            state = state,
                            onSelectChapter = onSelectReviewChapter,
                            onReviewChapter = onReviewChapter,
                            onPolish = onPolish,
                        )
                        HorizontalDivider()
                        BookReviewSection(
                            state = state,
                            onCheckConsistency = onCheckConsistency,
                            onRunFinalReview = onRunFinalReview,
                            onMarkApproved = onMarkApproved,
                        )
                    }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("类型设置还没保存") },
            text = {
                Text("现在的编辑只存在草稿里，生成大纲与正文仍按上次保存的设置走。丢弃草稿并关闭吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClose = false
                        // 丢掉编辑副本：下次打开回到已保存的设置。
                        onDraftsChange(writingDraftsSeededFrom(state.writingProfile))
                        onDismiss()
                    },
                ) { Text("丢弃草稿并关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmClose = false
                        // 某些系统返回键路径会先把弹层藏起来再回调 onDismissRequest，
                        // 选择继续编辑时把它请回来，免得停在一个「看不见也关不掉」的弹层上。
                        if (!sheetState.isVisible) sheetScope.launch { sheetState.show() }
                    },
                ) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun StoryCoreSection(
    state: ChaptersUiState,
    drafts: WritingDrafts,
    onDraftsChange: (WritingDrafts) -> Unit,
    onGenerate: (String) -> Unit,
    onSave: () -> Unit,
) {
    val core = state.storyCore
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text("故事核心（人定）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "主题、人物欲望、关键转折、结局。AI 只提案，确认后才保存；保存后大纲与正文都会按它来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = drafts.corePremise,
            onValueChange = { onDraftsChange(drafts.copy(corePremise = it.take(800))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("一句话设定（可留空，用书名与设定集）") },
            minLines = 2,
            maxLines = 4,
            enabled = !state.generatingCore,
        )
        Button(
            onClick = { onGenerate(drafts.corePremise) },
            enabled = !state.generatingCore,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generatingCore) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在想主题…")
            } else {
                Text(if (core.isEmpty) "生成核心提案" else "重新生成核心")
            }
        }
        if (!core.isEmpty) {
            if (!state.storyCoreSaved) {
                Text(
                    "这是提案，还没有保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (core.theme.isNotBlank()) {
                Text("主题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    variant = AppCardVariant.Filled,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    SelectionContainer { Text(core.theme, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (core.desires.isNotEmpty()) {
                Text("人物欲望", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                core.desires.forEach { desire ->
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(desire.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (desire.want.isNotBlank()) {
                                Text("想要：${desire.want}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (desire.obstacle.isNotBlank()) {
                                Text(
                                    "真正挡住他：${desire.obstacle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (core.turningPoints.isNotEmpty()) {
                Text("关键转折", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                core.turningPoints.forEachIndexed { index, point ->
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${index + 1}. ${point.title}" + if (point.chapter.isNotBlank()) " · ${point.chapter}" else "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (point.description.isNotBlank()) {
                                Text(point.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (core.ending.isNotBlank()) {
                Text("结局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    variant = AppCardVariant.Filled,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    SelectionContainer { Text(core.ending, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button(
                onClick = onSave,
                enabled = !state.storyCoreSaved && !state.savingCore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.savingCore) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.storyCoreSaved) "核心已保存" else "保存核心")
            }
        }
    }
}

@Composable
private fun BibleExtraSection(bible: StoryBibleDto) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Text("时间线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (bible.timeline.isEmpty()) {
            Text(
                "生成设定集时会一并产出时间线，用来查前后矛盾。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bible.timeline.forEach { event ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    variant = AppCardVariant.Filled,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val whenWhere = listOf(event.time, event.place)
                            .filter(String::isNotBlank).joinToString(" · ")
                        if (whenWhere.isNotBlank()) {
                            Text(whenWhere, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(event.event, style = MaterialTheme.typography.bodySmall)
                        if (event.chapter.isNotBlank()) {
                            Text(
                                event.chapter,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text("伏笔表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (bible.foreshadowings.isEmpty()) {
            Text(
                "伏笔会记录埋在哪、打算在哪回收，全书检查时会提示未回收的条目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bible.foreshadowings.forEach { item ->
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    variant = AppCardVariant.Filled,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.content, style = MaterialTheme.typography.bodySmall)
                        val route = listOf(
                            item.plantedIn.takeIf(String::isNotBlank)?.let { "埋于 $it" },
                            item.payoffIn.takeIf(String::isNotBlank)?.let { "回收于 $it" },
                            when (item.status) {
                                "paid" -> "已回收"
                                "dropped" -> "已放弃"
                                else -> "未回收"
                            },
                        ).filterNotNull().joinToString(" · ")
                        Text(route, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryBibleSection(
    state: ChaptersUiState,
    drafts: WritingDrafts,
    onDraftsChange: (WritingDrafts) -> Unit,
    onGenerate: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text(
            text = "生成一份可复用的设定集：世界观、人物小传、势力关系、冲突设计。保存后，大纲与正文都会带上它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = drafts.biblePremise,
            onValueChange = { onDraftsChange(drafts.copy(biblePremise = it.take(800))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("一句话设定（可留空，用书名与已有章节目标代替）") },
            minLines = 2,
            maxLines = 4,
            enabled = !state.generatingBible,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WritingGenres.all.forEach { genre ->
                FilterChip(
                    selected = drafts.bibleGenreId == genre.id,
                    onClick = {
                        val next = if (drafts.bibleGenreId == genre.id) "" else genre.id
                        onDraftsChange(drafts.copy(bibleGenreId = next))
                    },
                    label = { Text(genre.name) },
                )
            }
        }
        Button(
            onClick = { onGenerate(drafts.biblePremise, drafts.bibleGenreId) },
            enabled = !state.generatingBible,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generatingBible) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在生成…")
            } else {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.storyBible.isEmpty) "生成设定集" else "重新生成")
            }
        }

        if (!state.storyBible.isEmpty) {
            HorizontalDivider()
            if (!state.storyBibleSaved) {
                Text(
                    "这是提案，还没有保存；确认无误后点下面的「保存设定」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            BibleBlock("世界观", state.storyBible)
            val bible = state.storyBible
            if (bible.characters.isNotEmpty()) {
                Text("人物小传", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                bible.characters.forEach { character ->
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(character.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (character.role.isNotBlank()) Text(character.role, style = MaterialTheme.typography.bodySmall)
                            val detail = listOf(
                                character.desire.takeIf { it.isNotBlank() }?.let { "想要：$it" },
                                character.secret.takeIf { it.isNotBlank() }?.let { "秘密：$it" },
                                character.voice.takeIf { it.isNotBlank() }?.let { "说话：$it" },
                            ).filterNotNull()
                            detail.forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (bible.factions.isNotEmpty()) {
                Text("势力关系", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                bible.factions.forEach { faction ->
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(faction.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (faction.goal.isNotBlank()) Text(faction.goal, style = MaterialTheme.typography.bodySmall)
                            if (faction.relation.isNotBlank()) {
                                Text(
                                    faction.relation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (bible.conflicts.isNotEmpty()) {
                Text("冲突设计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                bible.conflicts.forEach { conflict ->
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(conflict.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (conflict.stake.isNotBlank()) Text(conflict.stake, style = MaterialTheme.typography.bodySmall)
                            if (conflict.escalation.isNotBlank()) {
                                Text(
                                    conflict.escalation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onSave,
                enabled = !state.storyBibleSaved && !state.savingBible,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.savingBible) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.storyBibleSaved) "已保存" else "保存设定")
            }
        }
    }
}

@Composable
private fun BibleBlock(title: String, bible: StoryBibleDto) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
        SelectionContainer {
            Text(
                text = bible.world.ifBlank { "（这一项为空）" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GenreSection(
    state: ChaptersUiState,
    drafts: WritingDrafts,
    onDraftsChange: (WritingDrafts) -> Unit,
    onSave: (WritingProfileDto) -> Unit,
) {
    val unsaved = hasUnsavedWritingDrafts(state.writingDrafts, state.writingProfile)
    val selected = WritingGenres.byId(drafts.genre)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text(
            text = "选一个类型，生成大纲和正文时会按它的节奏来；也可以只在备注里写自己的更新节奏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WritingGenres.all.forEach { genre ->
            val picked = genre.id == drafts.genre
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                variant = if (picked) AppCardVariant.Filled else AppCardVariant.Filled,
                onClick = {
                    onDraftsChange(drafts.copy(genre = if (picked) "" else genre.id))
                },
                contentPadding = PaddingValues(AppSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(genre.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            genre.tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (picked) {
                        Text("已选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (selected != null) {
            Text(
                "节奏：${selected.beats}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("每章目标字数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chapterLengthOptions.forEach { length ->
                FilterChip(
                    selected = drafts.chapterLength == length,
                    onClick = { onDraftsChange(drafts.copy(chapterLength = length)) },
                    label = { Text("$length 字") },
                )
            }
        }
        OutlinedTextField(
            value = drafts.notes,
            onValueChange = { onDraftsChange(drafts.copy(notes = it.take(500))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("额外节奏要求（可选）") },
            placeholder = { Text("例如：每三章一个小高潮；主角不许连续两章获胜") },
            minLines = 2,
            maxLines = 4,
        )
        OutlinedTextField(
            value = drafts.voiceSample,
            onValueChange = { onDraftsChange(drafts.copy(voiceSample = it.take(2000))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("文风样本（可选，贴一段你自己的文字）") },
            placeholder = { Text("AI 会模仿它的句长、用词与节奏，而不是模仿内容") },
            minLines = 3,
            maxLines = 6,
        )
        OutlinedTextField(
            value = drafts.avoid,
            onValueChange = { onDraftsChange(drafts.copy(avoid = it.take(400))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("不想出现的表达（可选）") },
            placeholder = { Text("例如：心中一凛、空气仿佛凝固、眼眶一热") },
            minLines = 2,
            maxLines = 4,
        )
        Button(
            onClick = { onSave(writingDraftsProfile(drafts)) },
            enabled = !state.savingProfile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.savingProfile) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (unsaved) "保存类型设置（未保存）" else "保存类型设置")
        }
    }
}

@Composable
private fun RescueSection(
    state: ChaptersUiState,
    onChapter: (String) -> Unit,
    onProblem: (String) -> Unit,
    onKind: (String) -> Unit,
    onRescue: () -> Unit,
) {
    val clipboard = rememberClipboardTextWriter()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text(
            text = "写到一半卡住时，给几个能立刻往下写的方案。方案只是提案，不会自动改正文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChapterPicker(state.chapters, state.rescueChapterId, onChapter)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rescueKinds.forEach { (id, label) ->
                FilterChip(
                    selected = state.rescueKind == id,
                    onClick = { onKind(id) },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = state.rescueProblem,
            onValueChange = onProblem,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("卡在哪里") },
            placeholder = { Text("例如：主角已经知道真相，但我不知道该让他揭穿还是继续忍") },
            minLines = 3,
            maxLines = 5,
            enabled = !state.rescuing,
        )
        Button(
            onClick = onRescue,
            enabled = !state.rescuing && state.rescueProblem.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.rescuing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在想…")
            } else {
                Text("给我几个方案")
            }
        }
        state.rescueOptions.forEachIndexed { index, option ->
            RescueOptionCard(
                index = index,
                option = option,
                onCopy = { scope.launch { clipboard("${option.title}\n${option.idea}\n\n${option.snippet}") } },
            )
        }
    }
}

@Composable
private fun RescueOptionCard(index: Int, option: RescueOptionDto, onCopy: () -> Unit) {
    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "方案 ${index + 1} · ${option.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (option.idea.isNotBlank()) {
                Text(option.idea, style = MaterialTheme.typography.bodySmall)
            }
            if (option.snippet.isNotBlank()) {
                SelectionContainer {
                    Text(
                        option.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onCopy, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制方案")
            }
        }
    }
}

@Composable
private fun ReviewSection(
    state: ChaptersUiState,
    onSelectChapter: (String) -> Unit,
    onReviewChapter: (String) -> Unit,
    onPolish: (String) -> Unit,
) {
    var confirmPolish by remember { mutableStateOf(false) }
    val review = state.review
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text(
            text = "检查语病、语气、前后矛盾与设定冲突，并生成本章摘要。润色只改表达，不改剧情。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChapterPicker(state.chapters, state.reviewChapterId, onSelectChapter)
        Button(
            onClick = {
                onReviewChapter(
                    state.reviewChapterId.ifBlank {
                        state.chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
                    },
                )
            },
            enabled = !state.reviewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.reviewing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在检查…")
            } else {
                Text("检查这一章")
            }
        }
        if (review != null) {
            ReviewReport(review)
            OutlinedButton(
                onClick = { confirmPolish = true },
                enabled = state.polishingChapterId.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.polishingChapterId.isNotBlank()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在润色…")
                } else {
                    Text("按建议润色这一章")
                }
            }
        }
    }
    if (confirmPolish && review != null) {
        AlertDialog(
            onDismissRequest = { confirmPolish = false },
            title = { Text("润色并覆盖正文？") },
            text = {
                Text("会按上面的检查结果改写本章正文并保存，原正文无法恢复。剧情不会改动。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPolish = false
                        onPolish(state.reviewChapterId)
                    },
                ) { Text("润色并覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPolish = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ReviewReport(review: ChapterReviewDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本章摘要", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        AppCard(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            contentPadding = PaddingValues(AppSpacing.md),
        ) {
            SelectionContainer {
                Text(review.summary.ifBlank { "（模型没有给摘要）" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            if (review.issues.isEmpty()) "没有发现明显问题。" else "发现 ${review.issues.size} 处可以改的地方",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        review.issues.forEach { issue ->
            AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppCardVariant.Filled,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        issue.kind.ifBlank { "建议" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (issue.quote.isNotBlank()) {
                        Text(
                            "「${issue.quote}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(issue.advice, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun ChapterPicker(chapters: List<ChapterDto>, selectedId: String, onSelect: (String) -> Unit) {
    val effective = selectedId.ifBlank {
        chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
    }
    if (chapters.isEmpty()) {
        Text(
            "还没有章节，先在章节页新建一章。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chapters, key = ChapterDto::chapterId) { chapter ->
            FilterChip(
                selected = chapter.chapterId == effective,
                onClick = { onSelect(chapter.chapterId) },
                label = {
                    Text(
                        text = chapter.title.ifBlank { "未命名章节" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
    val current = chapters.firstOrNull { it.chapterId == effective }
    if (current != null && current.content.isBlank()) {
        Text(
            "这一章还没有正文，检查与救援会缺少上下文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
