package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.components.AppEmptyState
import top.wkbin.zaomeng.ui.theme.AppSpacing
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersScreen(
    viewModel: ChaptersViewModel,
    onBack: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenPersona: (String, String) -> Unit,
    /** 从「创作」页进来时直接展开 AI 创作弹层。 */
    openCreation: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChapterDto?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var pendingNovelConversion by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNovelCreation by remember { mutableStateOf(false) }
    var showWritingTools by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var writingToolTab by remember { mutableIntStateOf(0) }
    var pendingDraftChapter by remember { mutableStateOf<ChapterDto?>(null) }
    // 章节删除是不可逆的（服务端硬删除、没有回收站），必须先确认再动手。
    var pendingDeleteChapter by remember { mutableStateOf<ChapterDto?>(null) }
    // 编辑弹层只在**保存成功**后才关闭：失败时留在原地显示原因，
    // 否则刚敲进去的正文会随着弹层一起消失（此前就是这样丢的）。
    var editorSaveInFlight by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        pendingNovelConversion?.let { (sessionId, title) ->
            if (granted) {
                viewModel.convertSession(sessionId, title)
                showArchive = false
            } else {
                showArchive = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("未授予通知权限，无法在后台生成完成后提醒你。")
                }
            }
            pendingNovelConversion = null
        }
    }
    var launchedRequestId by remember { mutableStateOf(0L) }
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.saveExport(sink) },
        onCancelled = { viewModel.discardExport() },
    )

    LaunchedEffect(state.exportRequestId, state.exported?.filename) {
        val exported = state.exported ?: return@LaunchedEffect
        if (state.exportRequestId != launchedRequestId) {
            launchedRequestId = state.exportRequestId
            fileExporter(
                exported.filename,
                if (exported.filename.endsWith(".md")) "text/markdown" else "text/plain",
            )
        }
    }
    LaunchedEffect(openCreation, state.loading, state.chapters.size) {
        // 只有「刚建好、还是空的」书卷才自动摊开创作弹层。
        // 此前从创作页打开任何一本书都会先盖一层空白表单——尤其是刚用「就按这个写」
        // 建完书、大纲已经写进章节的情况，落地又是同一张空表单，很容易被当成没成功再来一次。
        if (openCreation && !state.loading && state.chapters.isEmpty()) showNovelCreation = true
    }
    LaunchedEffect(state.outlineCreationCount) {
        // 大纲写入完成后收起弹层：继续停在「大纲」页会让用户以为还要再确认一次。
        if (state.outlineCreationCount > 0) showNovelCreation = false
    }
    // 失败提示此前只写进列表中部那张错误卡：它固定排在搜索卡与问书卷卡之后，
    // 用户当前滚到哪儿都看不见，表现就是「按钮转圈停下，什么也没发生」。
    LaunchedEffect(state.error) {
        val error = state.error
        if (error.isNotBlank()) snackbarHostState.showSnackbar(error)
    }
    LaunchedEffect(state.navigationSessionId) {        val sessionId = state.navigationSessionId
        if (sessionId.isNotBlank()) {
            viewModel.consumeNavigationSession()
            onOpenChat(viewModel.runId, sessionId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("章节工作台", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = if (state.chapters.isEmpty()) {
                                "把会话沉淀为草稿"
                            } else {
                                "${state.chapters.size} 章 · 约 ${state.chapters.sumOf { it.content.length }} 字"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showNovelCreation = true }, enabled = !state.saving) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = "AI 小说创作")
                    }
                    IconButton(onClick = { showCreate = true }, enabled = !state.saving) {
                        Icon(Icons.Default.Add, "新建章节")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("把会话转为小说") },
                                enabled = state.sessions.isNotEmpty() && !state.saving,
                                onClick = {
                                    showMoreMenu = false
                                    showArchive = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 Markdown") },
                                enabled = !state.exporting,
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.export("markdown")
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                top = AppSpacing.lg,
                end = AppSpacing.lg,
                bottom = AppSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item(key = "chapter-progress") {
                ChapterProgressCard(state.chapters)
            }
            item(key = "writing-tools") {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        ToolCard(
                            title = "设定",
                            subtitle = "核心 · 人物 · 伏笔",
                            icon = Icons.Outlined.Tune,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                writingToolTab = 0
                                showWritingTools = true
                            },
                        )
                        ToolCard(
                            title = "扩写",
                            subtitle = "场景卡 · 两版初稿",
                            icon = Icons.Outlined.ViewAgenda,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                writingToolTab = 2
                                showWritingTools = true
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        ToolCard(
                            title = "卡文救援",
                            subtitle = "三个可写方案",
                            icon = Icons.Outlined.Lightbulb,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                writingToolTab = 3
                                showWritingTools = true
                            },
                        )
                        ToolCard(
                            title = "检查终审",
                            subtitle = "一致性 · 四项",
                            icon = Icons.Outlined.FactCheck,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                writingToolTab = 4
                                showWritingTools = true
                            },
                        )
                    }
                }
            }
            item {
                AppCard(contentPadding = PaddingValues(AppSpacing.lg)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text("把会话沉淀成章节草稿，再统一导出全书。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            label = { Text("搜索章节和会话") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        )
                        Button(
                            onClick = viewModel::search,
                            enabled = !state.searching && state.searchQuery.isNotBlank(),
                        ) {
                            Text(if (state.searching) "…" else "搜索")
                        }
                    }
                    val suggestions = remember(state.chapters, state.sessions) {
                        chapterSearchSuggestions(state.chapters, state.sessions)
                    }
                    if (state.searchQuery.isBlank() && suggestions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions, key = { it }) { character ->
                                AssistChip(
                                    onClick = { viewModel.searchFor(character) },
                                    label = { Text(character) },
                                )
                            }
                        }
                    }
                    }
                }
            }
            item {
                AppCard(contentPadding = PaddingValues(AppSpacing.lg)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text("问书卷", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = state.bookQuestion,
                            onValueChange = viewModel::updateBookQuestion,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("例如：宝玉和黛玉最近一次冲突是什么？") },
                            minLines = 2,
                            maxLines = 4,
                        )
                        Button(
                            onClick = viewModel::askBook,
                            enabled = state.bookQuestion.isNotBlank() && !state.askingBook,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.askingBook) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("根据书卷证据回答")
                        }
                    }
                }
            }
            state.bookAnswer?.let { answer ->
                item {
                    AppCard(contentPadding = PaddingValues(AppSpacing.lg)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("回答", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(answer.answer)
                            if (answer.evidence.isNotEmpty()) {
                                Text("引用证据", style = MaterialTheme.typography.labelLarge)
                                answer.evidence.forEach { evidence ->
                                    Text("${searchResultKindLabel(evidence.kind)} · ${evidence.title}", style = MaterialTheme.typography.labelMedium)
                                    Text(evidence.preview, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (evidence.kind == "persona" && evidence.character.isNotBlank()) {
                                        TextButton(onClick = { onOpenPersona(viewModel.runId, evidence.character) }) {
                                            Text("打开人物档案")
                                        }
                                    }
                                    if (evidence.kind == "session" && evidence.sessionId.isNotBlank()) {
                                        TextButton(onClick = { onOpenChat(viewModel.runId, evidence.sessionId) }) {
                                            Text("打开原始会话")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentPadding = PaddingValues(AppSpacing.lg),
                ) {
                    Text(
                        state.error,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (state.message.isNotBlank()) item {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentPadding = PaddingValues(AppSpacing.lg),
                ) {
                    Text(
                        state.message,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (state.searchResults.isNotEmpty()) {
                item {
                    val kinds = state.searchResults.groupingBy { it.kind }.eachCount()
                    Text(
                        "搜索结果 · 章节 ${kinds["chapter"] ?: 0} · 人物 ${kinds["persona"] ?: 0} · 会话 ${kinds["session"] ?: 0}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.searchResults, key = { "${it.kind}:${it.chapterId}:${it.sessionId}:${it.character}" }) { result ->
                    AppCard(
                        variant = AppCardVariant.Outlined,
                        contentPadding = PaddingValues(AppSpacing.md),
                    ) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(searchResultKindLabel(result.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(if (result.kind == "session") "会话 · ${result.title}" else result.title, fontWeight = FontWeight.SemiBold)
                        Text(result.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (result.kind == "persona" && result.character.isNotBlank()) {
                            TextButton(onClick = { onOpenPersona(viewModel.runId, result.character) }) { Text("校对人物") }
                        }
                        if (result.kind == "session" && result.sessionId.isNotBlank()) {
                            TextButton(onClick = { onOpenChat(viewModel.runId, result.sessionId) }) { Text("打开会话") }
                        }
                    } }
                }
            }
            if (!state.searching && state.searchQuery.isNotBlank() && state.searchResults.isEmpty()) item {
                Text("没有找到相关证据。可换一个角色名、章节标题或会话中的关键词再试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (!state.loading && state.chapters.isEmpty()) item {
                AppEmptyState(
                    title = "还没有章节",
                    description = "新建一个空白章节，或把现有聊天会话转为小说草稿。",
                    icon = Icons.Outlined.MenuBook,
                    action = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Button(onClick = { showNovelCreation = true }) { Text("AI 生成大纲") }
                            OutlinedButton(onClick = { showCreate = true }) { Text("新建章节") }
                            OutlinedButton(
                                onClick = { showArchive = true },
                                enabled = state.sessions.isNotEmpty(),
                            ) { Text("转为小说") }
                        }
                    },
                )
            }
            items(state.chapters, key = ChapterDto::chapterId) { chapter ->
                ChapterCard(
                    chapter = chapter,
                    opening = state.openingChapterId == chapter.chapterId,
                    syncing = state.syncingChapterId == chapter.chapterId,
                    drafting = state.draftingChapterId == chapter.chapterId,
                    canMoveUp = chapter.order > 1,
                    canMoveDown = chapter.order < state.chapters.size,
                    onContinue = { viewModel.continueWriting(chapter.chapterId) },
                    onSync = { viewModel.syncLatestSession(chapter.chapterId) },
                    onDraft = {
                        if (chapter.content.isBlank()) {
                            viewModel.draftChapter(chapter.chapterId)
                        } else {
                            pendingDraftChapter = chapter
                        }
                    },
                    onMoveUp = { viewModel.move(chapter.chapterId, chapter.order - 1) },
                    onMoveDown = { viewModel.move(chapter.chapterId, chapter.order + 1) },
                    onEdit = { editing = chapter },
                    onDelete = { pendingDeleteChapter = chapter },
                )
            }
            if (state.chapters.isNotEmpty()) item {
                OutlinedButton(onClick = { viewModel.export("text") }, enabled = !state.exporting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.exporting) "正在准备导出…" else "导出全书 TXT")
                }
            }
        }
    }
    if (showCreate) ChapterEditorDialog(
        onDismiss = { showCreate = false },
        saveEnabled = !state.saving,
        error = editorError,
    ) { title, goal, participants, content ->
        editorSaveInFlight = true
        editorError = ""
        viewModel.save(title = title, goal = goal, participants = participants, content = content)
    }
    editing?.let { chapter -> ChapterEditorDialog(
        chapter = chapter,
        onDismiss = { editing = null },
        saveEnabled = !state.saving,
        error = editorError,
    ) { title, goal, participants, content ->
        editorSaveInFlight = true
        editorError = ""
        viewModel.save(chapter.chapterId, title, goal, participants, content)
    } }

    // 保存结果回来后才决定关不关弹层：成功关闭，失败留在原地并显示原因。
    LaunchedEffect(state.saving) {
        if (editorSaveInFlight && !state.saving) {
            editorSaveInFlight = false
            if (state.error.isBlank()) {
                showCreate = false
                editing = null
            } else {
                editorError = state.error
            }
        }
    }
    if (showNovelCreation) {
        NovelCreationSheet(
            state = state,
            onGenerate = viewModel::generateOutline,
            onCreateChapters = viewModel::createChaptersFromOutline,
            onClearOutline = viewModel::clearOutline,
            onDismiss = { showNovelCreation = false },
        )
    }
    if (showWritingTools) {
        WritingToolSheet(
            state = state,
            initialTab = writingToolTab,
            onLoad = viewModel::loadWritingTools,
            onGenerateBible = viewModel::generateStoryBible,
            onSaveBible = viewModel::saveStoryBible,
            onSaveBibleNow = viewModel::saveStoryBibleNow,
            onSaveProfile = viewModel::saveWritingProfile,
            // 弹层里的编辑要写回 UiState：否则关掉弹层/旋屏就整段消失（见 #23）。
            onDraftsChange = viewModel::updateWritingDrafts,
            onRescueChapter = viewModel::updateRescueChapter,
            onRescueProblem = viewModel::updateRescueProblem,
            onRescueKind = viewModel::updateRescueKind,
            onRescue = viewModel::rescue,
            onGenerateCore = viewModel::generateStoryCore,
            onSaveCore = viewModel::saveStoryCore,
            onSelectExpandChapter = viewModel::selectExpandChapter,
            onGenerateSceneCards = viewModel::generateSceneCards,
            onSaveSceneCards = viewModel::saveSceneCards,
            onDraftVersions = viewModel::draftVersions,
            onApplyVersion = viewModel::applyVersion,
            onRewriteInput = viewModel::updateRewriteInput,
            onRewriteInstruction = viewModel::updateRewriteInstruction,
            onRewrite = viewModel::rewriteSelection,
            onCheckConsistency = viewModel::checkConsistency,
            onRunFinalReview = viewModel::runFinalReview,
            onMarkApproved = viewModel::markFinalApproved,
            onSelectReviewChapter = viewModel::updateReviewChapter,
            onReviewChapter = viewModel::reviewChapter,
            onPolish = viewModel::polishChapter,
            onDismiss = { showWritingTools = false },
        )
    }
    pendingDraftChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingDraftChapter = null },
            title = { Text("覆盖《${chapter.title}》的正文？") },
            text = {
                Text("这一章已有 ${chapter.content.length} 字草稿。AI 成稿会覆盖现有正文，且无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chapterId = chapter.chapterId
                        pendingDraftChapter = null
                        viewModel.draftChapter(chapterId)
                    },
                ) { Text("覆盖并生成") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDraftChapter = null }) { Text("取消") }
            },
        )
    }
    pendingDeleteChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChapter = null },
            title = { Text("删除《${chapter.title}》？") },
            text = {
                Text(
                    "这一章有 ${chapter.content.length} 字正文。删除后无法撤销，也没有回收站；" +
                        "如果你只是想重写，可以改用「AI 重写」或「编辑」。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = {
                        val chapterId = chapter.chapterId
                        pendingDeleteChapter = null
                        viewModel.delete(chapterId)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChapter = null }) { Text("取消") }
            },
        )
    }
    if (showArchive) ArchiveSessionDialog(
        sessions = state.sessions,
        onDismiss = { showArchive = false },
        onArchive = { sessionId, title ->
            if (viewModel.hasNotificationPermission()) {
                viewModel.convertSession(sessionId, title)
                showArchive = false
            } else {
                pendingNovelConversion = sessionId to title
                requestNotificationPermission()
            }
        },
    )
}

private fun searchResultKindLabel(kind: String): String = when (kind) {
    "chapter" -> "章节草稿"
    "persona" -> "人物档案"
    "session" -> "会话记录"
    else -> "书卷内容"
}

@Composable
private fun ChapterCard(
    chapter: ChapterDto,
    opening: Boolean,
    syncing: Boolean,
    drafting: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onContinue: () -> Unit,
    onSync: () -> Unit,
    onDraft: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(
        variant = AppCardVariant.Outlined,
        contentPadding = PaddingValues(AppSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.Top) {
                // 章号做成小牌：顺序是这类稿件的核心结构，标题前一行文字反而会被略过。
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = chapter.order.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(Modifier.weight(1f).padding(start = AppSpacing.md)) {
                    Text(
                        text = chapter.title.ifBlank { "无题" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (chapter.goal.isNotBlank()) {
                        Text(
                            text = "目标：${chapter.goal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (chapter.participants.isNotEmpty()) {
                Text(
                    text = chapter.participants.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = chapter.content.ifBlank { "空白草稿" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (chapter.content.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onContinue, enabled = !opening) { Text(if (opening) "正在入场…" else "继续写作") }
                OutlinedButton(onClick = onDraft, enabled = !drafting) {
                    if (drafting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("正在写…")
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (chapter.content.isBlank()) "AI 写这一章" else "AI 重写")
                    }
                }
                if (chapter.lastSessionId.isNotBlank()) {
                    OutlinedButton(onClick = onSync, enabled = !syncing) { Text(if (syncing) "正在收录…" else "收录会话") }
                }
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("上移") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("下移") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ChapterEditorDialog(
    chapter: ChapterDto? = null,
    onDismiss: () -> Unit,
    /** 另有保存/删除正在进行时禁用保存：`save()` 会静默 return，用户却会以为存上了。 */
    saveEnabled: Boolean = true,
    /** 上一次保存失败的原因；非空时显示在弹层里，弹层保持打开。 */
    error: String = "",
    onSave: (String, String, String, String) -> Unit,
) {
    // 用 rememberSaveable：旋转屏幕/重建后不能把正在写的整章正文清空。
    var title by rememberSaveable(chapter?.chapterId) { mutableStateOf(chapter?.title.orEmpty()) }
    var goal by rememberSaveable(chapter?.chapterId) { mutableStateOf(chapter?.goal.orEmpty()) }
    var participants by rememberSaveable(chapter?.chapterId) {
        mutableStateOf(chapter?.participants?.joinToString("、").orEmpty())
    }
    var content by rememberSaveable(chapter?.chapterId) { mutableStateOf(chapter?.content.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chapter == null) "新建章节" else "编辑章节") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            OutlinedTextField(title, { title = it }, label = { Text("章节标题") }, singleLine = true)
            OutlinedTextField(goal, { goal = it }, label = { Text("本章目标") })
            OutlinedTextField(participants, { participants = it }, label = { Text("出场人物（用顿号或逗号分隔）") })
            OutlinedTextField(content, { content = it }, label = { Text("草稿内容") }, minLines = 6)
            if (error.isNotBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, goal, participants, content) },
                enabled = saveEnabled && title.isNotBlank(),
            ) { Text(if (saveEnabled) "保存" else "保存中…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ArchiveSessionDialog(sessions: List<top.wkbin.zaomeng.data.api.DialogueSessionDto>, onDismiss: () -> Unit, onArchive: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转为小说章节") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("章节标题（可选）") },
                    singleLine = true,
                )
            }
            item {
                Text(
                    "至少需要 6 轮有效对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sessions.isEmpty()) {
                item { Text("还没有可归档的会话。") }
            }
            items(sessions, key = { it.sessionId }) { session ->
                OutlinedButton(onClick = { onArchive(session.sessionId, title) }, modifier = Modifier.fillMaxWidth()) {
                    Text("转为小说 · " + session.lastEntryPreview.ifBlank { "会话 ${session.sessionId.takeLast(6)}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChapterProgressCard(chapters: List<ChapterDto>) {
    val total = chapters.size
    val written = chapters.count { it.content.isNotBlank() }
    val words = chapters.sumOf { it.content.length }
    val ratio = if (total > 0) written.toFloat() / total else 0f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已写 $written / $total 章",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (words > 0) "约 $words 字" else "还没有正文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Text(
                text = if (total == 0) {
                    "先新建一章，或者让 AI 生成大纲。"
                } else {
                    "${total - written} 章待写 · 选中章节可拆场景卡、写两版初稿。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
