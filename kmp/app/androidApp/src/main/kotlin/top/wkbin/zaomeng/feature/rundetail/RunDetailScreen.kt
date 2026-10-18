package top.wkbin.zaomeng.feature.rundetail

import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.domain.run.RunReviewOverview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import top.wkbin.zaomeng.ui.theme.AppAlpha
import top.wkbin.zaomeng.ui.theme.AppDimens
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import androidx.window.core.layout.WindowSizeClass
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.OnlineLibrarySourceDto
import top.wkbin.zaomeng.data.api.BetaFeatureDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.PersonaQualityOverviewDto
import top.wkbin.zaomeng.data.api.PersonaQualityOverviewItemDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay

private const val AVATAR_CROP_FRAME_FRACTION = 0.8f
private const val AVATAR_ZOOM_MIN = 1f
private const val AVATAR_ZOOM_MAX = 4f
private const val AVATAR_ZOOM_STEP = 1.25f
private const val AVATAR_ZOOM_WHEEL_STEP = 1.1f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RunDetailScreen(
    viewModel: RunDetailViewModel,
    refreshEpoch: Int = 0,
    onBack: () -> Unit,
    onOpenPersona: (runId: String, character: String) -> Unit,
    onOpenSessions: (runId: String) -> Unit,
    onOpenChapters: (runId: String) -> Unit,
    onOpenRelations: (runId: String) -> Unit,
    onOpenWorldTimeline: (runId: String) -> Unit,
    onOpenOriginalKnowledge: (runId: String) -> Unit,
    onOpenRedistill: (runId: String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val distillationForeground: DistillationForeground = koinInject()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val wideLayout = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmRedistill by rememberSaveable { mutableStateOf(false) }
    var confirmResume by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var selectedAvatarPersona by remember { mutableStateOf<PersonaIndexDto?>(null) }
    var avatarCropBytes by remember { mutableStateOf<ByteArray?>(null) }
    val avatarPicker = rememberDocumentPicker { name, bytes ->
        val persona = selectedAvatarPersona
        if (persona != null) avatarCropBytes = bytes
    }
    var resumeAfterPermission by rememberSaveable { mutableStateOf(false) }
    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        if (granted) {
            if (resumeAfterPermission) viewModel.resumeUnfinishedCharacters()
            else viewModel.redistillOriginalCharacters()
        }
        resumeAfterPermission = false
    }
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.saveExportedPackage(sink) },
        onCancelled = { viewModel.cancelExportDestination() },
    )
    fun startOriginalRedistill() {
        resumeAfterPermission = false
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.redistillOriginalCharacters()
        } else {
            requestNotificationPermission()
        }
    }

    fun startResumeDistill() {
        resumeAfterPermission = true
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.resumeUnfinishedCharacters()
            resumeAfterPermission = false
        } else {
            requestNotificationPermission()
        }
    }

    LaunchedEffect(state.exportRequestId, state.exportDestinationPending) {
        val exported = state.exportedPackage ?: return@LaunchedEffect
        if (!state.exportDestinationPending) return@LaunchedEffect
        viewModel.consumeExportDestinationRequest(state.exportRequestId)
        fileExporter(exported.filename, "application/zip")
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    LaunchedEffect(refreshEpoch) {
        if (refreshEpoch > 0) viewModel.refresh()
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止这次蒸馏？") },
            text = { Text("已经完成的人物资料会保留，当前步骤结束后停止。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stop()
                }) { Text("停止蒸馏") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续等待") } },
        )
    }

    if (confirmRedistill) {
        val characterCount = state.run
            ?.let { it.lockedCharacters.ifEmpty { it.availableCharacters }.size }
            ?: 0
        AlertDialog(
            onDismissRequest = { confirmRedistill = false },
            title = { Text("按原人物重新蒸馏？") },
            text = { Text("将沿用当前正文和 $characterCount 位原人物，重新生成资料与关系。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRedistill = false
                    startOriginalRedistill()
                }) { Text("重新开始") }
            },
            dismissButton = { TextButton(onClick = { confirmRedistill = false }) { Text("取消") } },
        )
    }

    if (confirmResume) {
        val characterCount = state.run
            ?.let { run -> run.lockedCharacters.count { character -> character !in run.progress.completedCharacters } }
            ?: 0
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text("继续未完成人物") },
            text = { Text("将保留已完成人物，只继续蒸馏剩余 $characterCount 位人物。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResume = false
                    startResumeDistill()
                }) { Text("继续蒸馏") }
            },
            dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("取消") } },
        )
    }

    if (confirmExport) {
        ExportRunPackageDialog(
            onDismiss = { confirmExport = false },
            onConfirm = { includeDialogue ->
                confirmExport = false
                viewModel.exportRun(includeDialogue)
            },
        )
    }


    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.deleting) confirmDelete = false },
            title = { Text("删除整本书卷？") },
            text = { Text("人物资料、关系图和这本书的全部会话都会一起删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteRun()
                    },
                    enabled = !state.deleting,
                ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !state.deleting) { Text("取消") }
            },
        )
    }

    selectedAvatarPersona?.let { persona ->
        AlertDialog(
            onDismissRequest = { if (state.updatingAvatar.isBlank()) selectedAvatarPersona = null },
            title = { Text(persona.name) },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PersonaAvatar(
                        bytes = state.avatarBytes[persona.name],
                        name = persona.name,
                        modifier = Modifier.size(144.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { avatarPicker() },
                    enabled = state.updatingAvatar.isBlank(),
                ) { Text(if (state.updatingAvatar == persona.name) "正在保存" else "修改头像") }
            },
            dismissButton = {
                TextButton(onClick = { selectedAvatarPersona = null }, enabled = state.updatingAvatar.isBlank()) {
                    Text("取消")
                }
            },
        )
    }

    avatarCropBytes?.let { bytes ->
        AvatarCropDialog(
            bytes = bytes,
            onDismiss = { avatarCropBytes = null },
            onConfirm = { crop ->
                selectedAvatarPersona?.let { persona ->
                    avatarCropBytes = null
                    viewModel.updatePersonaAvatar(persona.name, bytes, crop)
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.run?.title ?: "书卷详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.refreshing && !state.loading,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新详情")
                        }
                    }
                    IconButton(onClick = { confirmExport = true }, enabled = state.run != null && !state.exporting) {
                        Icon(Icons.Default.Download, contentDescription = "导出书卷")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> DetailLoading(Modifier.padding(innerPadding))
            state.run == null -> DetailFailure(
                message = state.error.ifBlank { "没有找到这份书卷。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            else -> RunDetailContent(
                state = state,
                wideLayout = wideLayout,
                onDismissNotice = viewModel::dismissNotice,
                onRetryExportDestination = viewModel::retryExportDestination,
                onStop = { confirmStop = true },
                onResume = { confirmResume = true },
                onRedistill = { confirmRedistill = true },
                onExport = { confirmExport = true },
                onOpenPersona = { character -> onOpenPersona(viewModel.runId, character) },
                onAvatarClick = { selectedAvatarPersona = it },
                onOpenSessions = { onOpenSessions(viewModel.runId) },
                onOpenChapters = { onOpenChapters(viewModel.runId) },
                onOpenRelations = { onOpenRelations(viewModel.runId) },
                onOpenWorldTimeline = { onOpenWorldTimeline(viewModel.runId) },
                onOpenOriginalKnowledge = { onOpenOriginalKnowledge(viewModel.runId) },
                onOpenRedistill = { onOpenRedistill(viewModel.runId) },
                onDelete = { confirmDelete = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun RunDetailContent(
    state: RunDetailUiState,
    wideLayout: Boolean,
    onDismissNotice: () -> Unit,
    onRetryExportDestination: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onAvatarClick: (PersonaIndexDto) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    onOpenOriginalKnowledge: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val run = checkNotNull(state.run)
    var showAllSources by rememberSaveable(run.runId) { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 380.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { RunHero(run) }

        run.betaFeature?.takeIf { it.kind == "cross_book_crossover" }?.let { beta ->
            item(span = { GridItemSpan(maxLineSpan) }) { CrossoverBetaCard(beta) }
        }

        run.importedFrom.onlineLibrary?.takeIf { it.id.isNotBlank() }?.let { source ->
            item(span = { GridItemSpan(maxLineSpan) }) { OnlinePackageSourceCard(source) }
        }

        if (state.error.isNotBlank() || state.message.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NoticeCard(
                    message = state.error.ifBlank { state.message },
                    error = state.error.isNotBlank(),
                    onDismiss = onDismissNotice,
                    actionLabel = if (state.error.isNotBlank() && state.exportedPackage != null) {
                        "重新选择位置"
                    } else {
                        ""
                    },
                    onAction = onRetryExportDestination,
                )
            }
        }

        val reviewAvailable = state.reviewLoading || state.reviewOverview != null
        if (wideLayout && reviewAvailable) {
            // 宽屏：推荐下一步 + 作品体检 左右并排
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                ) {
                    NextActionCard(
                        run = run,
                        onResume = onResume,
                        onOpenRedistill = onOpenRedistill,
                        onOpenPersona = onOpenPersona,
                        onOpenRelations = onOpenRelations,
                        onOpenSessions = onOpenSessions,
                        onOpenChapters = onOpenChapters,
                        modifier = Modifier.weight(1f),
                    )
                    BookReviewCard(
                        loading = state.reviewLoading,
                        overview = state.reviewOverview,
                        onOpenPersona = onOpenPersona,
                        onOpenRelations = onOpenRelations,
                        onOpenWorldTimeline = onOpenWorldTimeline,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NextActionCard(
                    run = run,
                    onResume = onResume,
                    onOpenRedistill = onOpenRedistill,
                    onOpenPersona = onOpenPersona,
                    onOpenRelations = onOpenRelations,
                    onOpenSessions = onOpenSessions,
                    onOpenChapters = onOpenChapters,
                )
            }
            if (reviewAvailable) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BookReviewCard(
                        loading = state.reviewLoading,
                        overview = state.reviewOverview,
                        onOpenPersona = onOpenPersona,
                        onOpenRelations = onOpenRelations,
                        onOpenWorldTimeline = onOpenWorldTimeline,
                    )
                }
            }
        }

        if (state.reviewLoading || state.qualityOverview != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PersonaQualityOverviewCard(
                    loading = state.reviewLoading,
                    overview = state.qualityOverview,
                    onOpenPersona = onOpenPersona,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ActionCard(
                run = run,
                wideLayout = wideLayout,
                stopping = state.stopping,
                redistilling = state.redistilling,
                exporting = state.exporting,
                deleting = state.deleting,
                onStop = onStop,
                onResume = onResume,
                onRedistill = onRedistill,
                onExport = onExport,
                onOpenSessions = onOpenSessions,
                onOpenChapters = onOpenChapters,
                onOpenRelations = onOpenRelations,
                onOpenWorldTimeline = onOpenWorldTimeline,
                onOpenOriginalKnowledge = onOpenOriginalKnowledge,
                onOpenRedistill = onOpenRedistill,
                onDelete = onDelete,
            )
        }

        if (run.novelSources.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SourceHistoryCard(
                    run = run,
                    expanded = showAllSources,
                    onToggleExpanded = { showAllSources = !showAllSources },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("可用人物", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (run.availableCharacters.isEmpty()) {
                        if (run.status == "running") "人物资料完成后会陆续出现在这里。" else "目前没有可用的人物资料。"
                    } else {
                        "${run.availableCharacters.size} 位人物可以查看和校对"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (run.artifactIndex.characters.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AppCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = PaddingValues(0.dp),
    ) {
                    Text(
                        text = if (run.status == "running") "正在等待第一位人物完成…" else "还没有人物档案。",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(run.artifactIndex.characters, key = PersonaIndexDto::name) { persona ->
                PersonaCard(
                    persona = persona,
                    avatarBytes = state.avatarBytes[persona.name],
                    onAvatarClick = { onAvatarClick(persona) },
                    onClick = { onOpenPersona(persona.name) },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PersonaQualityOverviewCard(
    loading: Boolean,
    overview: PersonaQualityOverviewDto?,
    onOpenPersona: (String) -> Unit,
) {
    AppCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("人物校对总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        loading && overview == null -> "正在汇总每位人物的档案质量…"
                        overview == null || overview.characterCount == 0 -> "还没有可校对的档案。"
                        overview.needsReviewCount == 0 -> "共 ${overview.characterCount} 位人物，档案都比较完整。"
                        else -> "共 ${overview.characterCount} 位人物，${overview.needsReviewCount} 位建议复核"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loading && overview == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "正在计算档案质量…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (overview != null && overview.characterCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityMetric("待复核", "${overview.needsReviewCount}/${overview.characterCount}", Modifier.weight(1f))
                    QualityMetric("待修复项", overview.pendingRepairTotal.toString(), Modifier.weight(1f))
                    QualityMetric("缺字段", overview.missingFieldTotal.toString(), Modifier.weight(1f))
                }

                val focus = overview.items.filter { it.needsReview }.ifEmpty { overview.items }.take(6)
                if (focus.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        focus.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenPersona(item.character) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                GradeBadge(item.grade, item.score)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.character,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        qualityHint(item),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "校对 ›",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                overview.lowestScoreItem?.takeIf { it.needsReview }?.let { lowest ->
                    Button(onClick = { onOpenPersona(lowest.character) }, modifier = Modifier.fillMaxWidth()) {
                        Text("先校对 ${lowest.character}（${lowest.score} 分）")
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun GradeBadge(grade: String, score: Int) {
    val container = when (grade) {
        "A" -> MaterialTheme.colorScheme.primaryContainer
        "B" -> MaterialTheme.colorScheme.secondaryContainer
        "C" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (grade) {
        "A" -> MaterialTheme.colorScheme.onPrimaryContainer
        "B" -> MaterialTheme.colorScheme.onSecondaryContainer
        "C" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = grade.ifBlank { score.toString() },
            modifier = Modifier.width(30.dp).padding(vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun qualityHint(item: PersonaQualityOverviewItemDto): String {
    val parts = buildList {
        if (item.pendingRepairCount > 0) add("${item.pendingRepairCount} 项待修复")
        if (item.highIssueCount > 0) add("${item.highIssueCount} 项高优先")
        if (item.missingFields.isNotEmpty()) {
            add("缺 ${item.missingFields.joinToString("、").take(20)}")
        }
    }
    return if (parts.isEmpty()) "${item.score} 分 · 档案完整" else "${item.score} 分 · ${parts.joinToString(" · ")}"
}

@Composable
private fun NextActionCard(
    run: RunManifestDto,
    onResume: () -> Unit,
    onOpenRedistill: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenRelations: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChapters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = nextActionFor(run)
    AppCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("推荐下一步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                action.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = {
                    when (action.target) {
                        RunNextActionTarget.ResumeDistillation -> onResume()
                        RunNextActionTarget.OpenRedistill -> onOpenRedistill()
                        RunNextActionTarget.OpenPersona -> onOpenPersona(action.character)
                        RunNextActionTarget.OpenRelations -> onOpenRelations()
                        RunNextActionTarget.OpenSessions -> onOpenSessions()
                        RunNextActionTarget.OpenChapters -> onOpenChapters()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(action.label)
            }
        }
    }
}

@Composable
private fun BookReviewCard(
    loading: Boolean,
    overview: RunReviewOverview?,
    onOpenPersona: (String) -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("作品体检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在汇总人物、关系与时间线的待校对项…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val report = checkNotNull(overview)
                val hasIssues = report.relationConflictCount > 0 || report.timelineWarningCount > 0 ||
                    report.charactersNeedingReview.isNotEmpty()
                if (!hasIssues) {
                    Text("当前没有发现需要优先处理的关系冲突或一致性提醒。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (report.charactersNeedingReview.isNotEmpty()) {
                        ReviewActionRow(
                            text = "${report.charactersNeedingReview.size} 位人物资料建议校对",
                            label = "校对 ${report.charactersNeedingReview.first()}",
                            onClick = { onOpenPersona(report.charactersNeedingReview.first()) },
                        )
                    }
                    if (report.relationConflictCount > 0) {
                        ReviewActionRow(
                            text = "${report.relationConflictCount} 组人物关系需要留意",
                            label = "校对关系",
                            onClick = onOpenRelations,
                        )
                    }
                    if (report.timelineWarningCount > 0) {
                        ReviewActionRow(
                            text = "${report.timelineWarningCount} 条故事时间线提醒",
                            label = "查看时间线",
                            onClick = onOpenWorldTimeline,
                        )
                    }
                }
                if (report.checkedCharacterCount > 0) {
                    Text(
                        "已检查 ${report.checkedCharacterCount} 位人物的资料质量。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewActionRow(text: String, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onClick) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CrossoverBetaCard(beta: BetaFeatureDto) {
    AppCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("跨书卷共演 · Beta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("这是独立人物快照空间。这里产生的关系、记忆、修正和会话不会写回来源书卷；测试功能的角色表现可能不稳定。", style = MaterialTheme.typography.bodySmall)
            if (beta.worldSetting.isNotBlank()) {
                Text("世界设定：${beta.worldSetting}", style = MaterialTheme.typography.bodySmall)
            }
            if (beta.sourceSnapshots.isNotEmpty()) {
                Text("人物来源：${beta.sourceSnapshots.joinToString(" · ") { it.character }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OnlinePackageSourceCard(source: OnlineLibrarySourceDto) {
    val openExternalUrl = rememberOpenExternalUrl()
    AppCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("在线书卷包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildList {
                    if (source.title.isNotBlank()) add(source.title)
                    if (source.version.isNotBlank()) add("v${source.version}")
                    if (source.id.isNotBlank()) add(source.id)
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (source.downloadUrl.isNotBlank()) {
                TextButton(onClick = {
                    openExternalUrl(source.downloadUrl)
                }) { Text("打开书卷包来源") }
            }
        }
    }
}

@Composable
private fun SourceHistoryCard(
    run: RunManifestDto,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val sortedSources = run.novelSources.sortedByDescending(NovelSourceDto::timestamp)
    val visibleSources = if (expanded) sortedSources else sortedSources.take(3)
    val currentPath = run.novelPath.trim()

    AppCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("正文来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "初始正文和后续增量书段都会保留在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            visibleSources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                val isCurrent = currentPath.isNotBlank() && source.sourcePath == currentPath
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.sourceName.ifBlank { "未命名书页" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    "当前",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Text(
                        sourceMeta(source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sortedSources.size > 3) {
                TextButton(onClick = onToggleExpanded, modifier = Modifier.align(Alignment.End)) {
                    Text(if (expanded) "收起" else "查看全部 ${sortedSources.size} 份")
                }
            }
        }
    }
}

@Composable
private fun RunHero(run: RunManifestDto) {
    val total = maxOf(run.progress.totalCharacters, run.lockedCharacters.size)
    val completed = maxOf(run.progress.completedCount, run.availableCharacters.size)
    val progress = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    val shape = MaterialTheme.shapes.extraLarge
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                // 从主色容器渐变到纸面：给「书卷」一个封面感，同时右下方自然过渡到列表底色，
                // 避免整块大色卡造成视觉压迫。
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = AppAlpha.Strong),
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            ),
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        run.novelId.ifBlank { run.runId },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = AppAlpha.Muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                RunStatus(run)
            }

            if (run.status == "running") {
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = run.progress.message.ifBlank { run.summary.statusText.ifBlank { statusCopy(run) } },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("人物", if (total > 0) "$completed / $total" else "未开始", Modifier.weight(1f))
                Metric(
                    "关系图",
                    graphLabel(run.progress.graphStatus.ifBlank { run.summary.graphStatus }),
                    Modifier.weight(1f),
                )
                Metric("用时", run.timing.elapsedText.ifBlank { "—" }, Modifier.weight(1f))
            }
            if (run.progress.currentCharacter.isNotBlank()) {
                Text(
                    "正在处理：${run.progress.currentCharacter}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = AppAlpha.Muted),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExportRunPackageDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var includeDialogue by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出书卷包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择是否把已有聊天会话一起写入小说包。")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = includeDialogue,
                        onCheckedChange = { includeDialogue = it },
                    )
                    Text("携带会话记录")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeDialogue) }) {
                Text("生成分享包")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ActionCard(
    run: RunManifestDto,
    wideLayout: Boolean,
    stopping: Boolean,
    redistilling: Boolean,
    exporting: Boolean,
    deleting: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    onOpenOriginalKnowledge: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("书卷操作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val showAdvancedManagement = rememberSaveable(run.runId) { mutableStateOf(false) }
            if (wideLayout) {
                // 宽屏：操作按钮左右两列排布
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Forum, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("查看会话与开始聊天")
                        }
                        OutlinedButton(onClick = onOpenChapters, modifier = Modifier.fillMaxWidth()) {
                            Text("章节工作台与全书导出")
                        }
                        Text(
                            "故事资料",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenRelations, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.People, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("查看与校对人物关系")
                        }
                        OutlinedButton(onClick = onOpenWorldTimeline, modifier = Modifier.fillMaxWidth()) {
                            Text("查看故事时间线与剧情事实")
                        }
                        OutlinedButton(onClick = onOpenOriginalKnowledge, modifier = Modifier.fillMaxWidth()) {
                            Text("搜索与校对原文证据")
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "书卷管理",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ManagementActions(
                            run = run,
                            stopping = stopping,
                            redistilling = redistilling,
                            exporting = exporting,
                            deleting = deleting,
                            onStop = onStop,
                            onResume = onResume,
                            onRedistill = onRedistill,
                            onExport = onExport,
                            onOpenRedistill = onOpenRedistill,
                            onDelete = onDelete,
                            showAdvanced = showAdvancedManagement.value,
                            onToggleAdvanced = { showAdvancedManagement.value = !showAdvancedManagement.value },
                        )
                    }
                }
            } else {
                Button(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Forum, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看会话与开始聊天")
                }
                OutlinedButton(onClick = onOpenChapters, modifier = Modifier.fillMaxWidth()) {
                    Text("章节工作台与全书导出")
                }
                HorizontalDivider()
                Text(
                    "故事资料",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenRelations, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.People, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看与校对人物关系")
                }
                OutlinedButton(onClick = onOpenWorldTimeline, modifier = Modifier.fillMaxWidth()) {
                    Text("查看故事时间线与剧情事实")
                }
                OutlinedButton(onClick = onOpenOriginalKnowledge, modifier = Modifier.fillMaxWidth()) {
                    Text("搜索与校对原文证据")
                }
                HorizontalDivider()
                Text(
                    "书卷管理",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ManagementActions(
                    run = run,
                    stopping = stopping,
                    redistilling = redistilling,
                    exporting = exporting,
                    deleting = deleting,
                    onStop = onStop,
                    onResume = onResume,
                    onRedistill = onRedistill,
                    onExport = onExport,
                    onOpenRedistill = onOpenRedistill,
                    onDelete = onDelete,
                    showAdvanced = showAdvancedManagement.value,
                    onToggleAdvanced = { showAdvancedManagement.value = !showAdvancedManagement.value },
                )
            }
        }
    }
}

/** 书卷管理区：导出 / 更多操作（高级操作、停止、删除）。窄宽两种布局共用。 */
@Composable
private fun ManagementActions(
    run: RunManifestDto,
    stopping: Boolean,
    redistilling: Boolean,
    exporting: Boolean,
    deleting: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    TextButton(
        onClick = onExport,
        modifier = Modifier.fillMaxWidth(),
        enabled = !exporting,
    ) {
        if (exporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (exporting) "正在打包…" else "导出书卷包")
    }

    TextButton(
        onClick = onToggleAdvanced,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (showAdvanced) "收起高级操作" else "更多书卷操作")
    }
    if (showAdvanced) {
        TextButton(
            onClick = onOpenRedistill,
            modifier = Modifier.fillMaxWidth(),
            enabled = !redistilling && run.status != "running" &&
                (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
        ) {
            if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("继续蒸馏或换入新书段")
        }

        val unfinishedCount = run.lockedCharacters.count { it !in run.progress.completedCharacters }
        if (unfinishedCount > 0 && run.status != "running") {
            TextButton(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
                enabled = !redistilling,
            ) {
                if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("继续未完成人物（$unfinishedCount 人）")
            }
        }

        TextButton(
            onClick = onRedistill,
            modifier = Modifier.fillMaxWidth(),
            enabled = !redistilling && run.status != "running" &&
                (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
        ) {
            Text(if (redistilling) "正在重新开始…" else "直接沿用原人物与正文重跑")
        }
    }

    if (run.status == "running") {
        HorizontalDivider()
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = !stopping && !run.control.stopRequested,
        ) {
            if (stopping) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.StopCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    run.control.stopRequested -> "正在停止…"
                    stopping -> "正在发送请求…"
                    else -> "停止蒸馏"
                },
            )
        }
    } else {
        HorizontalDivider()
        TextButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = !deleting,
        ) {
            if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (deleting) "正在删除…" else "删除这本书及其会话",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaIndexDto,
    avatarBytes: ByteArray?,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
) {
    val preview = persona.preview
    val summary = listOf(preview.coreIdentity, preview.storyRole, preview.soulGoal)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        variant = AppCardVariant.Elevated,
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAvatarClick, modifier = Modifier.size(44.dp)) {
                PersonaAvatar(bytes = avatarBytes, name = persona.name, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary.ifBlank { "资料已生成，点此查看和校对。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PersonaAvatar(
    bytes: ByteArray?,
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        val bitmap = bytes?.let { decodeImageBitmap(it) }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().take(1)
                if (initial.isBlank()) {
                    Icon(Icons.Outlined.Person, contentDescription = "人物头像")
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            androidx.compose.foundation.Image(
                bitmap = checkNotNull(bitmap),
                contentDescription = "人物头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AvatarCropDialog(
    bytes: ByteArray,
    onDismiss: () -> Unit,
    onConfirm: (AvatarCrop) -> Unit,
) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) }
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("无法读取图片") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        return
    }

    var zoom by remember(bytes) { mutableStateOf(1f) }
    var offsetX by remember(bytes) { mutableStateOf(0f) }
    var offsetY by remember(bytes) { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 注意参数形态：CMP 1.11 带的基础库里 rememberTransformableState 是 4 参
    // （centroid, zoomChange, panChange, rotationChange），而 AndroidX BOM 锁定的是 3 参
    // （zoomChange, panChange, rotationChange）。这里两端都忽略 centroid 与 rotation，
    // 因此直接写成 3 参形式，语义完全一致（本文件原就未使用归一化中心点/旋转量）。
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(AVATAR_ZOOM_MIN, AVATAR_ZOOM_MAX)
        val (clampedX, clampedY) = clampCropOffsets(
            viewport = viewport,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            zoom = zoom,
            offsetX = offsetX + panChange.x,
            offsetY = offsetY + panChange.y,
        )
        offsetX = clampedX
        offsetY = clampedY
    }
    // 桌面端没有双指手势，提供按钮与鼠标滚轮缩放（触屏仍可双指缩放）。
    fun zoomBy(factor: Float) {
        val newZoom = (zoom * factor).coerceIn(AVATAR_ZOOM_MIN, AVATAR_ZOOM_MAX)
        if (newZoom == zoom) return
        zoom = newZoom
        val (clampedX, clampedY) = clampCropOffsets(
            viewport = viewport,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
        )
        offsetX = clampedX
        offsetY = clampedY
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整头像取景") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clipToBounds()
                        .transformable(transformableState)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                                        zoomBy(
                                            // AWT：滚轮向上为负（away），向下为正；CMP 直接映射为 scrollDelta.y。
                                            if (deltaY > 0) 1f / AVATAR_ZOOM_WHEEL_STEP
                                            else AVATAR_ZOOM_WHEEL_STEP,
                                        )
                                    }
                                }
                            }
                        }
                        .onSizeChanged { viewport = it },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = checkNotNull(bitmap),
                        contentDescription = "头像取景预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.92f),
                            radius = size.minDimension * AVATAR_CROP_FRAME_FRACTION / 2f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { zoomBy(1f / AVATAR_ZOOM_STEP) },
                        enabled = zoom > AVATAR_ZOOM_MIN,
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "缩小")
                    }
                    Text(
                        text = "${(zoom * 100).toInt()}%",
                        modifier = Modifier.width(56.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        onClick = { zoomBy(AVATAR_ZOOM_STEP) },
                        enabled = zoom < AVATAR_ZOOM_MAX,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "放大")
                    }
                }
                Text(
                    text = "双指/滚轮缩放 · 拖拽调整位置",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cropDiameter = minOf(viewport.width, viewport.height) * AVATAR_CROP_FRAME_FRACTION
                    val baseScale = minOf(
                        viewport.width.toFloat() / bitmap.width,
                        viewport.height.toFloat() / bitmap.height,
                    )
                    val imageScale = baseScale * zoom
                    val selectedSide = (cropDiameter / imageScale).toInt().coerceAtLeast(1)
                    val left = ((bitmap.width - selectedSide) / 2f - offsetX / imageScale).toInt()
                    val top = ((bitmap.height - selectedSide) / 2f - offsetY / imageScale).toInt()
                    onConfirm(AvatarCrop(left, top, selectedSide))
                },
                enabled = viewport != IntSize.Zero,
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 按当前缩放与取景框尺寸重新夹取偏移量，保证裁剪框始终落在图片范围内。 */
private fun clampCropOffsets(
    viewport: IntSize,
    bitmapWidth: Int,
    bitmapHeight: Int,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float> {
    if (viewport == IntSize.Zero || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return offsetX to offsetY
    }
    val baseScale = minOf(
        viewport.width.toFloat() / bitmapWidth,
        viewport.height.toFloat() / bitmapHeight,
    )
    val cropDiameter = minOf(viewport.width, viewport.height) * AVATAR_CROP_FRAME_FRACTION
    val scaledWidth = bitmapWidth * baseScale * zoom
    val scaledHeight = bitmapHeight * baseScale * zoom
    val maxX = ((scaledWidth - cropDiameter) / 2f).coerceAtLeast(0f)
    val maxY = ((scaledHeight - cropDiameter) / 2f).coerceAtLeast(0f)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}

@Composable
private fun RunStatus(run: RunManifestDto) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = statusLabel(run),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NoticeCard(
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
    actionLabel: String = "",
    onAction: () -> Unit = {},
) {
    AppCard(
        containerColor = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                message,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (actionLabel.isNotBlank()) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun DetailLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在打开书卷…")
        }
    }
}

@Composable
private fun DetailFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        AppCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("书卷没有打开", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

private fun statusLabel(run: RunManifestDto): String {
    if (run.isOriginal) return "原创"
    return when (run.status) {
        "ready" -> "可使用"
        "running" -> "蒸馏中"
        "pending", "draft" -> "待蒸馏"
        "failed" -> "失败"
        "stopped" -> "已停止"
        else -> run.status.ifBlank { "未知" }
    }
}

private fun statusCopy(run: RunManifestDto): String {
    if (run.isOriginal) return "原创书卷，不需要蒸馏。直接去章节工作台写大纲和正文。"
    return when (run.status) {
        "ready" -> "人物资料和关系已经整理完成。"
        "running" -> "人物和关系正在这台手机上逐步浮现。"
        "pending", "draft" -> "正文已经导入，尚未调用模型。"
        "failed" -> "这次蒸馏没有完成，已有结果仍会保留。"
        "stopped" -> "蒸馏已停止，可以按原人物重新开始。"
        else -> "书卷状态正在更新。"
    }
}

private fun graphLabel(status: String): String = when (status) {
    "ready", "completed", "complete" -> "已完成"
    "running" -> "生成中"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status.ifBlank { "未生成" }
}

private fun sourceMeta(source: NovelSourceDto): String = buildList {
    add(if (source.kind == "incremental_update") "增量书段" else "初始正文")
    when {
        source.charCount >= 10_000 -> add("约 ${source.charCount / 10_000.0f} 万字".replace(".0 ", " "))
        source.charCount > 0 -> add("约 ${source.charCount} 字")
        source.byteSize >= 1024 * 1024 -> add("%.1f MB".format(source.byteSize / (1024.0 * 1024.0)))
        source.byteSize >= 1024 -> add("%.1f KB".format(source.byteSize / 1024.0))
        source.byteSize > 0 -> add("${source.byteSize} B")
    }
    if (source.timestamp.isNotBlank()) {
        add(source.timestamp.toLocalDateTimeDisplay())
    }
}.joinToString(" · ")
