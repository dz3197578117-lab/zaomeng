package top.wkbin.zaomeng.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.domain.chat.LoadChatSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import top.wkbin.zaomeng.client.platform.clientRandomUuid

/**
 * 从完整 transcript 中提取 baseline 之后「已提交」的新增条目。
 * 只有包含非用户内容才算提交成功（对齐旧 hasCommittedReply 语义，避免误判重复发送）。
 */
internal fun committedAppend(
    baselineSize: Int,
    currentTranscript: List<TranscriptItemDto>,
): List<TranscriptItemDto> {
    if (currentTranscript.size <= baselineSize) return emptyList()
    val appended = currentTranscript.drop(baselineSize)
    return appended.takeIf { items ->
        items.any { it.role != "user" && it.message.isNotBlank() }
    }.orEmpty()
}

/**
 * 按 turn_id 幂等合并 transcript：append 中已存在于 base 的条目替换而非重复追加。
 * 断连重试 / 失败恢复时，base 可能已经包含部分或全部本轮条目（本地已拼过、服务端已提交），
 * 直接 `base + append` 会把整段历史重复一遍；按 turn_id 去重后天然幂等。
 */
internal fun mergeTranscript(
    base: List<TranscriptItemDto>,
    append: List<TranscriptItemDto>,
): List<TranscriptItemDto> {
    if (append.isEmpty()) return base

    // append **自身**也要按 turnId 去重（同一 turnId 保留最后一条，保持原有顺序）。
    // 只做「base 与 append 之间」的去重挡不住 append 内部或 missing+appended 拼接处的重复，
    // 那种情况下同一轮会出现两条，全部渲染出来就是多个内容相同的气泡。
    val lastIndexByTurnId = append.withIndex()
        .filter { it.value.turnId.isNotBlank() }
        .associate { it.value.turnId to it.index }
    val dedupedAppend = append.filterIndexed { index, item ->
        item.turnId.isBlank() || lastIndexByTurnId[item.turnId] == index
    }

    val appendedTurnIds = dedupedAppend.map { it.turnId }.filter { it.isNotBlank() }.toSet()
    if (appendedTurnIds.isEmpty()) return base + dedupedAppend
    return base.filterNot { it.turnId in appendedTurnIds } + dedupedAppend
}

/**
 * 按 [TranscriptItemDto.transcriptKey] 去重，保留首次出现。
 *
 * 用于**直接采纳服务端返回的整份 transcript** 时兜底：服务端列表是权威来源，
 * 但一旦它含重复条目，客户端会照单全收，而重复条目会让 LazyColumn 的 key 撞车。
 */
internal fun dedupTranscript(items: List<TranscriptItemDto>): List<TranscriptItemDto> =
    if (items.size < 2) items else items.distinctBy { it.transcriptKey() }

/**
 * 把「更早的一页」并到当前 transcript **前面**（分页向上加载）。
 *
 * 分页的 offset 是**发请求前**用 `total - loaded` 算出来的；请求往返期间
 * transcript 可能已经变化（新一轮提交、流式完成、恢复刷新），于是这一页会和
 * 已有内容重叠。早前这里是直接 `page + transcript`，重叠就产生重复条目——
 * 而重复条目会让 LazyColumn 的 key 撞车，在主线程抛
 * `IllegalArgumentException: Key "…" was already used` 直接闪退（线上已发生）。
 *
 * 因此这里对页内按 turnId 去重（保留最后一次出现，保持顺序），
 * 并把已经在页里出现过的 turnId 从当前列表里移除，避免同一条消息出现两次。
 */
internal fun prependTranscript(
    earlier: List<TranscriptItemDto>,
    current: List<TranscriptItemDto>,
): List<TranscriptItemDto> {
    if (earlier.isEmpty()) return current
    if (current.isEmpty()) return earlier

    val lastIndexByTurnId = earlier.withIndex()
        .filter { it.value.turnId.isNotBlank() }
        .associate { it.value.turnId to it.index }
    val dedupedEarlier = earlier.filterIndexed { index, item ->
        item.turnId.isBlank() || lastIndexByTurnId[item.turnId] == index
    }

    val earlierTurnIds = dedupedEarlier.map { it.turnId }.filter { it.isNotBlank() }.toSet()
    val keptCurrent = if (earlierTurnIds.isEmpty()) {
        current
    } else {
        current.filterNot { it.turnId in earlierTurnIds }
    }
    return dedupedEarlier + keptCurrent
}

internal fun hasCommittedContent(items: List<TranscriptItemDto>): Boolean =
    items.any { it.role != "user" && it.message.isNotBlank() }

internal fun ChatUiState.matchesSession(runId: String, sessionId: String): Boolean =
    this.runId == runId && this.sessionId == sessionId

internal fun ChatUiState.matchesSend(snapshot: ChatUiState, operationId: String): Boolean =
    matchesSession(snapshot.runId, snapshot.sessionId) && failedOperationId == operationId

internal fun JsonObject.extractDirectorOptions(): List<ChatToolOption> = this["options"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val messageKind = stringValue("message_kind").ifBlank { "plot" }
        val title = item.stringValue("title")
        val beat = item.stringValue("beat")
        val direction = item.stringValue("direction")
        if (title.isBlank() || beat.isBlank() || direction.isBlank()) return@mapNotNull null
        val details = buildList {
            item.stringValue("focus").takeIf(String::isNotBlank)?.let { add("焦点：$it") }
            item.stringValue("expected_effect").takeIf(String::isNotBlank)?.let { add("效果：$it") }
            item.stringValue("risk").takeIf(String::isNotBlank)?.let { add("风险：$it") }
            item.stringValue("resistance").takeIf(String::isNotBlank)?.let { add("抵抗：$it") }
            item.stringValue("price").takeIf(String::isNotBlank)?.let { add("代价：$it") }
        }
        ChatToolOption(
            label = title,
            value = listOf(beat, direction).distinct().joinToString("；"),
            description = details.joinToString(" · "),
            messageKind = messageKind,
        )
    }
    .orEmpty()

internal fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

internal fun JsonObject.stringList(key: String): List<String> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }
    .orEmpty()

internal fun Throwable.readableMessage(fallback: String): String = message
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: fallback

internal class StreamReplyException(
    message: String,
    val retryable: Boolean,
) : Exception(message)
