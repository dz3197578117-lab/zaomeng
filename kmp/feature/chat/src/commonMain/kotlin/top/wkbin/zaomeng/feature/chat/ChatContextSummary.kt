package top.wkbin.zaomeng.feature.chat

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.DialogueSessionDto

internal data class ChatContextSummary(
    val mode: String,
    val scene: String,
    val participants: String,
    /** 人物时间线位置，例如「时间点：第 5 章」；没有章节信息时为空串。 */
    val timePoint: String = "",
)

internal fun chatContextSummary(session: DialogueSessionDto): ChatContextSummary {
    val scene = session.sceneCard["title"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: session.sceneCard["location"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: session.sceneHistory.lastOrNull()?.get("title")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: "未设置场景"
    val participants = session.participants.filter(String::isNotBlank).distinct()
    // 人物时间线位置：服务端推导出的当前时间点（persona_chapter）优先，
    // 尚未推导过时退回会话起始章。两者都是 0 表示这本书不分章，不展示。
    val chapter = session.personaChapter.takeIf { it > 0 } ?: session.startChapter
    val chapterTitle = session.startChapterTitle.trim()
    val timePoint = when {
        chapter <= 0 -> ""
        chapter == session.startChapter && chapterTitle.isNotBlank() -> "时间点：第 $chapter 章（$chapterTitle）"
        else -> "时间点：第 $chapter 章"
    }
    return ChatContextSummary(
        mode = session.modeDisplay.ifBlank { session.mode.contextModeLabel() },
        scene = scene,
        participants = participants.take(3).joinToString("、").ifBlank { "未指定参与角色" } +
            if (participants.size > 3) " 等 ${participants.size} 人" else "",
        timePoint = timePoint,
    )
}

private fun String.contextModeLabel(): String = when (this) {
    "act" -> "扮演角色"
    "insert" -> "以我入场"
    "observe" -> "旁观"
    else -> "人物会话"
}
