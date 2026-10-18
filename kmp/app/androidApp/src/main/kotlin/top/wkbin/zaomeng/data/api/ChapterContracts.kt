package top.wkbin.zaomeng.data.api

import okio.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class OriginalKnowledgeLocationDto(
    @SerialName("start_char") val startChar: Int = 0,
    @SerialName("end_char") val endChar: Int = 0,
)

data class ExportedRunPackage(
    val filename: String,
    val file: Path,
    val byteCount: Long,
)

@Serializable
data class ChaptersResponse(val items: List<ChapterDto> = emptyList())

@Serializable
data class ChapterDto(
    @SerialName("chapter_id") val chapterId: String = "",
    val order: Int = 0,
    val title: String = "",
    val goal: String = "",
    val participants: List<String> = emptyList(),
    val content: String = "",
    @SerialName("source_session_id") val sourceSessionId: String = "",
    @SerialName("last_session_id") val lastSessionId: String = "",
    /** 叙事视角（「第三人称限知」等）。AI 大纲创建章节时写入，成稿时沿用。 */
    val perspective: String = "",
    /** 场景卡：这一章拆出来的几场戏；为空表示还没拆。 */
    @SerialName("scene_cards") val sceneCards: List<SceneCardDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class GenerateNovelOutlineRequest(
    val premise: String,
    @SerialName("chapter_count") val chapterCount: Int = 6,
    val genre: String = "",
    val perspective: String = "第三人称限知",
    val style: String = "",
)

@Serializable
data class NovelOutlineChapterDto(
    val title: String = "",
    val goal: String = "",
)

@Serializable
data class NovelOutlineResponse(
    val items: List<NovelOutlineChapterDto> = emptyList(),
)

@Serializable
data class DraftChapterRequest(
    val instruction: String = "",
)

@Serializable
data class SaveChapterRequest(
    val title: String,
    val goal: String = "",
    val participants: List<String> = emptyList(),
    val content: String = "",
    /** 叙事视角；AI 大纲创建章节时写入，成稿时沿用。 */
    val perspective: String = "",
)

@Serializable
data class ArchiveDialogueChapterRequest(
    @SerialName("session_id") val sessionId: String,
    val title: String = "",
)

@Serializable
data class ReorderChapterRequest(
    @SerialName("target_order") val targetOrder: Int,
)

@Serializable
data class RewriteChapterRequest(
    val instruction: String = "",
    @SerialName("context_summary") val contextSummary: String = "",
)

data class ExportedChapterManuscript(
    val filename: String,
    val file: Path,
)

@Serializable
data class SearchResultsResponse(val items: List<SearchResultDto> = emptyList())

@Serializable
data class SearchResultDto(
    val kind: String = "",
    @SerialName("chapter_id") val chapterId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val character: String = "",
    val title: String = "",
    val preview: String = "",
)

@Serializable
data class AskBookQuestionRequest(val question: String)

@Serializable
data class AskBookResponseDto(
    val answer: String = "",
    val evidence: List<SearchResultDto> = emptyList(),
)
