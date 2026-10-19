package top.wkbin.zaomeng.data

import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
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
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.local.CardsClient
import top.wkbin.zaomeng.client.platform.clientMonotonicNanos
import top.wkbin.zaomeng.data.local.ChapterClient
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.DraftChapterRequest
import top.wkbin.zaomeng.data.api.GenerateNovelOutlineRequest
import top.wkbin.zaomeng.data.api.GenerateStoryBibleRequest
import top.wkbin.zaomeng.data.api.NovelOutlineResponse
import top.wkbin.zaomeng.data.api.NovelPlanDto
import top.wkbin.zaomeng.data.api.NovelPlanRequest
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.PolishChapterRequest
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.RescueRequest
import top.wkbin.zaomeng.data.api.SaveStoryBibleRequest
import top.wkbin.zaomeng.data.api.SaveWritingProfileRequest
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.WritingProfileDto

class ChapterRepositoryImpl(
    private val chapters: ChapterClient,
) : ChapterRepository {
    override suspend fun listChapters(runId: String): List<ChapterDto> = repositoryRequest {
        chapters.list(runId).items
    }

    override suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto> = repositoryRequest {
        chapters.search(runId, query)
    }

    override suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto = repositoryRequest {
        chapters.ask(runId, question)
    }

    override suspend fun saveChapter(
        runId: String,
        chapterId: String,
        payload: SaveChapterRequest,
    ): ChapterDto = repositoryRequest {
        chapters.save(runId, chapterId, payload)
    }

    override suspend fun generateNovelOutline(
        runId: String,
        request: GenerateNovelOutlineRequest,
    ): NovelOutlineResponse = repositoryRequest {
        chapters.generateOutline(runId, request)
    }

    override suspend fun draftChapter(
        runId: String,
        chapterId: String,
        instruction: String,
    ): ChapterDto = repositoryRequest {
        chapters.draftChapter(runId, chapterId, DraftChapterRequest(instruction))
    }

    override suspend fun readStoryBible(runId: String): StoryBibleDto = repositoryRequest {
        chapters.readStoryBible(runId)
    }

    override suspend fun generateStoryBible(
        runId: String,
        request: GenerateStoryBibleRequest,
    ): StoryBibleDto = repositoryRequest {
        chapters.generateStoryBible(runId, request)
    }

    override suspend fun saveStoryBible(runId: String, bible: StoryBibleDto): StoryBibleDto =
        repositoryRequest {
            chapters.saveStoryBible(runId, SaveStoryBibleRequest(bible))
        }

    override suspend fun readWritingProfile(runId: String): WritingProfileDto = repositoryRequest {
        chapters.readWritingProfile(runId)
    }

    override suspend fun saveWritingProfile(
        runId: String,
        profile: WritingProfileDto,
    ): WritingProfileDto = repositoryRequest {
        chapters.saveWritingProfile(runId, SaveWritingProfileRequest(profile))
    }

    override suspend fun rescue(
        runId: String,
        request: RescueRequest,
    ): List<RescueOptionDto> = repositoryRequest {
        chapters.rescue(runId, request).options
    }

    override suspend fun reviewChapter(runId: String, chapterId: String): ChapterReviewDto =
        repositoryRequest {
            chapters.reviewChapter(runId, chapterId)
        }

    override suspend fun polishChapter(
        runId: String,
        chapterId: String,
        instruction: String,
    ): ChapterDto = repositoryRequest {
        chapters.polishChapter(runId, chapterId, PolishChapterRequest(instruction))
    }

    override suspend fun planNovel(request: NovelPlanRequest): NovelPlanDto = repositoryRequest {
        chapters.planNovel(request)
    }

    override suspend fun readStoryCore(runId: String): StoryCoreDto = repositoryRequest {
        chapters.readStoryCore(runId)
    }

    override suspend fun generateStoryCore(
        runId: String,
        request: GenerateStoryCoreRequest,
    ): StoryCoreDto = repositoryRequest {
        chapters.generateStoryCore(runId, request)
    }

    override suspend fun saveStoryCore(runId: String, core: StoryCoreDto): StoryCoreDto =
        repositoryRequest {
            chapters.saveStoryCore(runId, core)
        }

    override suspend fun generateSceneCards(
        runId: String,
        request: GenerateSceneCardsRequest,
    ): List<SceneCardDto> = repositoryRequest {
        chapters.generateSceneCards(runId, request).items
    }

    override suspend fun saveSceneCards(
        runId: String,
        chapterId: String,
        cards: List<SceneCardDto>,
    ): List<SceneCardDto> = repositoryRequest {
        chapters.saveSceneCards(runId, chapterId, cards)
    }

    override suspend fun draftVersions(
        runId: String,
        request: DraftVersionsRequest,
    ): List<DraftVersionDto> = repositoryRequest {
        chapters.draftVersions(runId, request).items
    }

    override suspend fun applyChapterContent(
        runId: String,
        chapterId: String,
        request: ApplyChapterContentRequest,
    ): ChapterDto = repositoryRequest {
        chapters.applyChapterContent(runId, chapterId, request)
    }

    override suspend fun rewriteSelection(
        runId: String,
        request: RewriteSelectionRequest,
    ): List<RewriteOptionDto> = repositoryRequest {
        chapters.rewriteSelection(runId, request).items
    }

    override suspend fun checkConsistency(runId: String): ConsistencyReportDto = repositoryRequest {
        chapters.checkConsistency(runId)
    }

    override suspend fun finalReview(runId: String): FinalReviewDto = repositoryRequest {
        chapters.finalReview(runId)
    }

    override suspend fun archiveSessionAsChapter(
        runId: String,
        sessionId: String,
        title: String,
    ): ChapterDto = repositoryRequest {
        chapters.archiveSession(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    override suspend fun convertSessionAsNovel(
        runId: String,
        sessionId: String,
        title: String,
    ): ChapterDto = repositoryRequest {
        chapters.convertSessionAsNovel(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    override suspend fun deleteChapter(runId: String, chapterId: String): JsonObject = repositoryRequest {
        chapters.delete(runId, chapterId)
    }

    override suspend fun continueChapter(runId: String, chapterId: String): DialogueSessionDto = repositoryRequest {
        chapters.continueWriting(runId, chapterId)
    }

    override suspend fun syncChapterSession(runId: String, chapterId: String): ChapterDto = repositoryRequest {
        chapters.syncSession(runId, chapterId)
    }

    override suspend fun reorderChapter(
        runId: String,
        chapterId: String,
        targetOrder: Int,
    ): List<ChapterDto> = repositoryRequest {
        chapters.reorder(runId, chapterId, targetOrder)
    }

    override suspend fun exportChapters(
        runId: String,
        format: String,
        cacheDirectory: Path,
    ): ExportedChapterManuscript = repositoryRequest {
        // 章节手稿由服务端直接渲染成文本（ChapterManagementService.render），
        // 进程内不再有「HTTP 字节流」，因此直接写文件即可——少了通道与两层拷贝。
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val extension = if (normalizedFormat == "text") "txt" else "md"
        val rendered = chapters.export(runId, format)
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(cacheDirectory)
        val file = cacheDirectory / "zaomeng-manuscript-${clientMonotonicNanos()}.$extension"
        fileSystem.write(file) { writeUtf8(rendered) }
        ExportedChapterManuscript(
            filename = "$runId-manuscript.$extension",
            file = file,
        )
    }
}

class CardRepositoryImpl(
    private val cards: CardsClient,
) : CardRepository {
    override suspend fun listReusableCards(kind: ReusableCardKind): List<ReusableCardDto> = repositoryRequest {
        cards.list(kindToSegment(kind))
    }

    override suspend fun getReusableCard(
        kind: ReusableCardKind,
        cardId: String,
    ): ReusableCardDto = repositoryRequest {
        cards.get(kindToSegment(kind), cardId)
    }

    override suspend fun saveReusableCard(
        kind: ReusableCardKind,
        cardId: String,
        fields: JsonObject,
    ): ReusableCardDto = repositoryRequest {
        cards.save(kindToSegment(kind), cardId, fields)
    }

    override suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String) = repositoryRequest {
        cards.delete(kindToSegment(kind), cardId)
    }

    override suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto = repositoryRequest {
        when (kind) {
            ReusableCardKind.Scene -> cards.generate("scene")
            ReusableCardKind.Self -> cards.generate("self")
            ReusableCardKind.Opening -> throw ApiRequestException("开场预设需要先选择人物和卡片后保存。")
        }
    }

    override suspend fun recommendSceneCard(
        mode: String,
        participants: List<String>,
    ): String = repositoryRequest {
        val response = cards.recommend(mode, participants)
        response["recommended_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
