package top.wkbin.zaomeng.data

import kotlinx.serialization.json.JsonObject
import okio.Path
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.ApplyChapterContentRequest
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.ConsistencyReportDto
import top.wkbin.zaomeng.data.api.DraftVersionDto
import top.wkbin.zaomeng.data.api.DraftVersionsRequest
import top.wkbin.zaomeng.data.api.FinalReviewDto
import top.wkbin.zaomeng.data.api.GenerateSceneCardsRequest
import top.wkbin.zaomeng.data.api.GenerateStoryCoreRequest
import top.wkbin.zaomeng.data.api.RewriteOptionDto
import top.wkbin.zaomeng.data.api.RewriteSelectionRequest
import top.wkbin.zaomeng.data.api.SceneCardDto
import top.wkbin.zaomeng.data.api.StoryCoreDto
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.GenerateNovelOutlineRequest
import top.wkbin.zaomeng.data.api.GenerateStoryBibleRequest
import top.wkbin.zaomeng.data.api.NovelOutlineResponse
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.RescueRequest
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.WritingProfileDto

interface ChapterRepository {
    suspend fun listChapters(runId: String): List<ChapterDto>
    suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto>
    suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto
    suspend fun saveChapter(runId: String, chapterId: String = "", payload: SaveChapterRequest): ChapterDto

    /** AI 小说创作：一句设定生成分章大纲（不写入章节）。 */
    suspend fun generateNovelOutline(runId: String, request: GenerateNovelOutlineRequest): NovelOutlineResponse

    /** AI 小说创作：按大纲与前文写出本章正文。 */
    suspend fun draftChapter(runId: String, chapterId: String, instruction: String = ""): ChapterDto

    // ===== 创作工具 =====

    suspend fun readStoryBible(runId: String): StoryBibleDto
    suspend fun generateStoryBible(runId: String, request: GenerateStoryBibleRequest): StoryBibleDto
    suspend fun saveStoryBible(runId: String, bible: StoryBibleDto): StoryBibleDto

    suspend fun readWritingProfile(runId: String): WritingProfileDto
    suspend fun saveWritingProfile(runId: String, profile: WritingProfileDto): WritingProfileDto

    /** 卡文救援：按卡点类型给多个可写方案，不落盘。 */
    suspend fun rescue(runId: String, request: RescueRequest): List<RescueOptionDto>

    /** 检查章节：语病、语气、矛盾、设定一致性与摘要。 */
    suspend fun reviewChapter(runId: String, chapterId: String): ChapterReviewDto

    /** 按检查建议改写并保存正文。 */
    suspend fun polishChapter(runId: String, chapterId: String, instruction: String = ""): ChapterDto

    // ===== 人定核心 =====

    suspend fun readStoryCore(runId: String): StoryCoreDto
    suspend fun generateStoryCore(runId: String, request: GenerateStoryCoreRequest): StoryCoreDto
    suspend fun saveStoryCore(runId: String, core: StoryCoreDto): StoryCoreDto

    // ===== 场景卡与多版本初稿 =====

    suspend fun generateSceneCards(runId: String, request: GenerateSceneCardsRequest): List<SceneCardDto>
    suspend fun saveSceneCards(runId: String, chapterId: String, cards: List<SceneCardDto>): List<SceneCardDto>
    suspend fun draftVersions(runId: String, request: DraftVersionsRequest): List<DraftVersionDto>
    suspend fun applyChapterContent(
        runId: String,
        chapterId: String,
        request: ApplyChapterContentRequest,
    ): ChapterDto

    // ===== 局部重写 =====

    suspend fun rewriteSelection(runId: String, request: RewriteSelectionRequest): List<RewriteOptionDto>

    // ===== 全书一致性与终审 =====

    suspend fun checkConsistency(runId: String): ConsistencyReportDto
    suspend fun finalReview(runId: String): FinalReviewDto
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
