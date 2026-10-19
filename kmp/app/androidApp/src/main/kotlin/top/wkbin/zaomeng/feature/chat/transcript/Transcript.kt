package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.ui.components.ChatBackgroundImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.feature.chat.transcript.EmotionBadge
import top.wkbin.zaomeng.feature.chat.insights.consistencyInsight
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.platform.rememberPlatformImage
import top.wkbin.zaomeng.platform.SpeechEngine
import top.wkbin.zaomeng.platform.SpeechEngineSelection
import top.wkbin.zaomeng.platform.classifySpeechEmotion
import top.wkbin.zaomeng.platform.deliver
import top.wkbin.zaomeng.platform.rememberPlatformTts
import top.wkbin.zaomeng.platform.resolveVoiceProfile
import top.wkbin.zaomeng.platform.toAuthoredVoice
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun Transcript(
    session: DialogueSessionDto,
    avatarBytes: Map<String, ByteArray>,
    /**
     * 参演角色 → 人物包字段（声线的来源，见 [top.wkbin.zaomeng.platform.toAuthoredVoice]）。
     * 缺省为空表时每个角色仍会拿到「按角色名确定性派生」的声线，朗读不会因此失效。
     */
    personaFields: Map<String, Map<String, String>> = emptyMap(),
    sending: Boolean,
    loadingEarlier: Boolean,
    hasMoreHistory: Boolean,
    onLoadEarlier: () -> Unit,
    modelReasoning: String,
    streamingReplies: List<StreamingReplyPart>,
    pendingUserMessage: PendingUserMessage?,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    onRegenerate: () -> Unit,
    onBranch: (String) -> Unit,
    onPendingRetry: () -> Unit,
    onPendingEdit: () -> Unit,
    onPendingReconcile: () -> Unit,
    onPendingRecover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val clipboardWriter = rememberClipboardTextWriter()
    // 引擎选择由偏好传入（对话显示偏好里带着它）。神经引擎不可用时 `rememberPlatformTts`
    // 会降级到系统 TTS 并留下原因——界面负责把原因显示出来，不静默降级。
    val tts = rememberPlatformTts(
        SpeechEngineSelection(
            engine = if (displayPreferences.neuralSpeechEnabled) SpeechEngine.NEURAL else SpeechEngine.SYSTEM,
            neuralModelId = displayPreferences.ttsNeuralModelId,
            neuralModelDir = displayPreferences.ttsNeuralModelDir,
            systemEnginePackage = displayPreferences.systemTtsEnginePackage,
        ),
    )
    // 把本次会话的参演角色告诉引擎，供神经引擎分配**互不相同**的音色（系统 TTS 忽略此项）。
    // key 里必须带上 `tts`：切换引擎会重建实例，若只按 sessionId 作 key，
    // 新实例就收不到参演名单（虽然会回落到按角色名派生、不至于全同一个声音，但那份「同场不撞车」的保证就丢了）。
    LaunchedEffect(tts, session.sessionId, session.characterAvatars) {
        tts.setCast(session.characterAvatars.keys.toList())
    }
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val speakingId by tts.currentSpeakingId.collectAsStateWithLifecycle()
    val bottomThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    // 渲染前按 transcriptKey 去重：重复条目会让 LazyColumn 的 key 撞车，
    // 在主线程抛 IllegalArgumentException: Key "…" was already used 直接闪退（线上已发生）。
    // 放在渲染层是**最后一道防线**：上游任何写入路径漏掉的重复都不会再崩。
    // distinctBy 保留首次出现——同 key 意味着 turnId/说话人/时间戳/正文全同，保留哪条都一样。
    val transcript = remember(session.transcript) {
        dedupTranscript(session.transcript)
    }
    val latestAssistantIndex = transcript.indexOfLast { item ->
        item.role != "user" && item.role != "scene" && item.role != "director"
    }
    val latestUserIndex = transcript.indexOfLast { item -> item.role == "user" }
    var followNewMessages by remember(session.sessionId) { mutableStateOf(true) }
    var unseenMessages by remember(session.sessionId) { mutableIntStateOf(0) }
    var previousVisibleCount by remember(session.sessionId) {
        mutableIntStateOf(
            transcript.size + streamingReplies.size +
                    (if (modelReasoning.isBlank()) 0 else 1) +
                    if (pendingUserMessage == null) 0 else 1,
        )
    }
    var previousFirstKey by remember(session.sessionId) { mutableStateOf("") }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (
                    lastVisible?.index == layout.totalItemsCount - 1 &&
                            lastVisible.offset + lastVisible.size <=
                            layout.viewportEndOffset + bottomThresholdPx
                    )
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling) followNewMessages = atBottom
                if (atBottom) unseenMessages = 0
            }
    }
    // 滚动到顶部时自动加载更早消息（仅在主动滚动时触发，避免加载历史后连环拉取）
    val latestHasMoreHistory by rememberUpdatedState(hasMoreHistory)
    val latestLoadingEarlier by rememberUpdatedState(loadingEarlier)
    val latestOnLoadEarlier by rememberUpdatedState(onLoadEarlier)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (firstIndex, scrolling) ->
                if (scrolling && firstIndex <= 2 && latestHasMoreHistory && !latestLoadingEarlier) {
                    latestOnLoadEarlier()
                }
            }
    }

    val visibleCount = transcript.size + streamingReplies.size +
            (if (sending && modelReasoning.isNotBlank()) 1 else 0) +
            if (pendingUserMessage == null) 0 else 1
    // 列表头部在有历史可加载时会多插入一行「加载更早的消息」，
    // 少算这一行会让自动跟随永远停在「倒数第二条」：最新那条一旦长过一屏，
    // 流式输出的后半截就落到视口外，用户得手动再滑一次。
    val leadingRowCount = if (hasMoreHistory || loadingEarlier) 1 else 0
    val lastContentIndex = (visibleCount - 1 + leadingRowCount).coerceAtLeast(0)
    val contentRevision = (transcript.lastOrNull()?.message?.hashCode() ?: 0) * 31 +
            streamingReplies.sumOf { it.text.length + it.innerThought.length } * 17 +
            modelReasoning.length * 13 +
            (pendingUserMessage?.let { 31 * it.status.hashCode() + it.statusText.hashCode() } ?: 0)
    val firstKey = transcript.firstOrNull()?.transcriptKey().orEmpty()
    LaunchedEffect(visibleCount, contentRevision, sending, firstKey) {
        // 向上加载历史会整体前插：首条 key 变化且数量增加视为 prepend，不纳入“新消息”计数
        val prepended = firstKey.isNotBlank() && firstKey != previousFirstKey &&
            transcript.size != previousVisibleCount
        val added = if (prepended) {
            0
        } else {
            (visibleCount - previousVisibleCount).coerceAtLeast(0)
        }
        previousVisibleCount = visibleCount
        previousFirstKey = firstKey
        if (followNewMessages) {
            listState.scrollToBottom(lastContentIndex, animated = false)
            unseenMessages = 0
        } else if (added > 0) {
            unseenMessages = (unseenMessages + added).coerceAtMost(99)
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = if (displayPreferences.compactMode) 6.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (displayPreferences.compactMode) 3.dp else 8.dp,
            ),
        ) {
            if (hasMoreHistory || loadingEarlier) {
                item(key = "load-earlier") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loadingEarlier) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "正在加载更早的消息…",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(onClick = onLoadEarlier) {
                                Text("加载更早的消息")
                            }
                        }
                    }
                }
            }

            if (
                transcript.isEmpty() && streamingReplies.isEmpty() &&
                modelReasoning.isBlank() && pendingUserMessage == null
            ) {
                item {
                    Text(
                        "这一幕还没有留下台词。写下第一句话，让故事继续。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(
                items = transcript,
                key = { _, item -> item.transcriptKey() },
            ) { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptBubble(
                        item = item,
                        avatarBytes = avatarBytes,
                        displayPreferences = displayPreferences,
                        actionsEnabled = actionsEnabled,
                        includeInnerThoughts = includeInnerThoughts,
                        canRegenerate = index == latestAssistantIndex && !sending,
                        isSpeaking = isSpeaking,
                        speakingId = speakingId,
                        onSpeak = {
                            val key = item.transcriptKey()
                            if (isSpeaking && speakingId == key) {
                                tts.stop()
                            } else {
                                // 每个角色用自己的声线：作者在人物包里填过就用填的，
                                // 没填（或字段格式不可解析）就按角色名确定性派生——
                                // 所以「每个人物声音不同」是默认成立的，不依赖作者填得多完整。
                                // 再按这一句的情绪（内心独白优先，它比台词更能说明情绪）与标点调整韵律。
                                val authored = personaFields[item.speaker].orEmpty().toAuthoredVoice()
                                val delivery = resolveVoiceProfile(item.speaker, authored).deliver(
                                    text = item.message,
                                    emotion = classifySpeechEmotion(item.innerThought.ifBlank { item.message }),
                                )
                                tts.speak(
                                    id = key,
                                    text = item.message,
                                    pitch = delivery.pitch,
                                    speed = delivery.speed,
                                    volume = delivery.volume,
                                    speaker = item.speaker,
                                    voiceName = delivery.voiceName,
                                )
                            }
                        },
                        onCopy = {
                            scrollScope.launch {
                                clipboardWriter(item.message)
                            }
                        },
                        onRegenerate = onRegenerate,
                        onBranch = { onBranch(item.turnId) },
                    )
                    if (!sending && index == latestUserIndex && modelReasoning.isNotBlank()) {
                        ModelReasoningBlock(modelReasoning, streaming = false)
                    }
                }
            }

            pendingUserMessage?.let { pending ->
                item(key = "pending-${pending.operationId}") {
                    PendingUserMessageBubble(
                        pending = pending,
                        onRetry = onPendingRetry,
                        onEdit = onPendingEdit,
                        onReconcile = onPendingReconcile,
                        onRecover = onPendingRecover,
                        requiresRecovery = session.status != "ready",
                    )
                }
            }

            if (sending && modelReasoning.isNotBlank()) {
                item(key = "model-reasoning") {
                    ModelReasoningBlock(
                        modelReasoning,
                        streaming = streamingReplies.none { it.text.isNotBlank() },
                    )
                }
            }

            items(
                items = streamingReplies,
                key = { "stream-${it.index}" },
            ) { item ->
                TranscriptBubble(
                    item = TranscriptItemDto(
                        speaker = item.speaker.ifBlank { "生成中" },
                        message = item.text,
                        innerThought = item.innerThought,
                        role = item.role,
                    ),
                    avatarBytes = avatarBytes,
                    displayPreferences = displayPreferences,
                    actionsEnabled = false,
                    includeInnerThoughts = includeInnerThoughts,
                    streaming = true,
                    onCopy = {},
                    onRegenerate = {},
                    onBranch = {},
                )
            }

        }

        if (!isAtBottom || unseenMessages > 0) {
            ExtendedFloatingActionButton(
                onClick = {
                    followNewMessages = true
                    unseenMessages = 0
                    scrollScope.launch {
                        listState.scrollToBottom(lastContentIndex, animated = true)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                icon = {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                },
                text = {
                    Text(
                        if (unseenMessages > 0) "$unseenMessages 条新消息" else "回到底部",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
internal suspend fun LazyListState.scrollToBottom(itemIndex: Int, animated: Boolean) {
    // 以实际布局为准：调用方算出来的索引只要少一行（例如漏掉头部「加载更早」那一行），
    // 「回到底部 / 自动跟随」就会一直差一条，正在变长的最后一条会停在视口外。
    // layoutInfo 尚未布局时 totalItemsCount 为 0，此时回退到调用方给的索引。
    val targetIndex = maxOf(itemIndex.coerceAtLeast(0), layoutInfo.totalItemsCount - 1)
    var target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (target == null) {
        if (animated) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    }
    val overflow = target?.let {
        it.offset + it.size - layoutInfo.viewportEndOffset
    } ?: return
    if (overflow > 0) {
        if (animated) {
            animateScrollBy(overflow.toFloat())
        } else {
            scrollBy(overflow.toFloat())
        }
    }
}
