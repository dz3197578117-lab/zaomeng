package top.wkbin.zaomeng.data

import kotlinx.serialization.json.JsonObject
import okio.Path
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.GenerateNovelOutlineRequest
import top.wkbin.zaomeng.data.api.NovelOutlineResponse
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto

interface ChapterRepository {
    suspend fun listChapters(runId: String): List<ChapterDto>
    suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto>
    suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto
    suspend fun saveChapter(runId: String, chapterId: String = "", payload: SaveChapterRequest): ChapterDto

    /** AI 小说创作：一句设定生成分章大纲（不写入章节）。 */
    suspend fun generateNovelOutline(runId: String, request: GenerateNovelOutlineRequest): NovelOutlineResponse

    /** AI 小说创作：按大纲与前文写出本章正文。 */
    suspend fun draftChapter(runId: String, chapterId: String, instruction: String = ""): ChapterDto
    suspend fun archiveSessionAsChapter(runId: String, sessionId: String, title: String = ""): ChapterDto
    suspend fun convertSessionAsNovel(runId: String, sessionId: String, title: String = ""): ChapterDto
    suspend fun deleteChapter(runId: String, chapterId: String): JsonObject
    suspend fun continueChapter(runId: String, chapterId: String): DialogueSessionDto
    suspend fun syncChapterSession(runId: String, chapterId: String): ChapterDto
    suspend fun reorderChapter(runId: String, chapterId: String, targetOrder: Int): List<ChapterDto>

    suspend fun exportChapters(
        runId: String,
        format: String,
        cacheDirectory: Path,
    ): ExportedChapterManuscript
}

interface CardRepository {
    suspend fun listReusableCards(kind: ReusableCardKind): List<ReusableCardDto>
    suspend fun getReusableCard(kind: ReusableCardKind, cardId: String): ReusableCardDto
    suspend fun saveReusableCard(kind: ReusableCardKind, cardId: String, fields: JsonObject): ReusableCardDto
    suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String)
    suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto
    suspend fun recommendSceneCard(mode: String, participants: List<String>): String
}
