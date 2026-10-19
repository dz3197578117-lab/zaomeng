package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 一句话生成小说：先出方案，用户确认后才建书、写设定与章节。 */
@Serializable
data class NovelPlanRequest(
    val prompt: String,
    @SerialName("chapter_count") val chapterCount: Int = 8,
)

@Serializable
data class NovelPlanDto(
    val title: String = "",
    val lead: String = "",
    /** 对应 [WritingGenres] 的 id；模型可能给中文名，解析时会归一。 */
    @SerialName("genre_id") val genreId: String = "",
    val premise: String = "",
    val core: StoryCoreDto = StoryCoreDto(),
    val bible: StoryBibleDto = StoryBibleDto(),
    val outline: List<NovelOutlineChapterDto> = emptyList(),
)
