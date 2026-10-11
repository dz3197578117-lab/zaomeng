package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 原文分章条目：只描述某一章在原文中的位置与开头预览，正文不随列表下发。
 */
@Serializable
data class NovelChapterDto(
    /** 1 起的章节序号。 */
    val index: Int = 0,
    val title: String = "",
    @SerialName("start_char") val startChar: Int = 0,
    @SerialName("end_char") val endChar: Int = 0,
    @SerialName("char_count") val charCount: Int = 0,
    /** 章节开头的纯文本预览（已折叠空白）。 */
    val preview: String = "",
) {
    /** 供选择器展示的一行标题，例如「第 3 章 · 风雪夜」。 */
    val displayTitle: String
        get() = title.trim().ifBlank { "第 $index 章" }
}

/**
 * 原文分章列表响应。
 *
 * [detected] 为 false 表示原文没有识别到章节标题，服务端已回退为定长分块，
 * 此时 [NovelChapterDto.title] 形如「第 N 段」，UI 应改用「起始位置」措辞。
 */
@Serializable
data class NovelChaptersResponse(
    @SerialName("run_id") val runId: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val detected: Boolean = false,
    @SerialName("chapter_count") val chapterCount: Int = 0,
    val chapters: List<NovelChapterDto> = emptyList(),
) {
    val hasChapters: Boolean get() = chapters.isNotEmpty()
}

/** 单章正文（截断片段）。 */
@Serializable
data class NovelChapterDetailDto(
    @SerialName("run_id") val runId: String = "",
    val index: Int = 0,
    val title: String = "",
    @SerialName("char_count") val charCount: Int = 0,
    val content: String = "",
    val truncated: Boolean = false,
)
