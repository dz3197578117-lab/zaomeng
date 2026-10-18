package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
import top.wkbin.zaomeng.data.api.DraftChapterRequest
import top.wkbin.zaomeng.data.api.GenerateNovelOutlineRequest
import top.wkbin.zaomeng.data.api.NovelOutlineResponse
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.ChaptersResponse
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.ktor.services.ChapterManagementService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 章节：**进程内直连**（原 `KtorChapterClient`）。
 *
 * 三个需要说明的对应关系：
 *
 * 1. `list` 对应的端点在 **`RunsRoute`**（`GET /runs/{id}/chapters`），
 *    它先确认 run 存在，再把 `storage.listChapters()` 包成 `{"items":[...]}`。这里照做。
 * 2. `export` 以前返回的是 [io.ktor.client.statement.HttpResponse]，由仓库层
 *    `streamChannelToTempFile` 把响应体流式落到临时文件。但服务端其实是
 *    `ChapterManagementService.render()` **直接渲染出字符串**再编码成字节——
 *    进程内没有任何理由再走一次「字符串 → 字节 → 通道 → 文件」，
 *    因此这里直接返回字符串，由仓库层一次写入。少了两层拷贝与一个通道。
 * 3. `search` 原路由校验 `query` 长度 1..100，`limit` 默认 30（夹在 1..100）。
 *    校验与默认值都保留，避免行为漂移。
 */
class ChapterClient(
    private val chapterManagement: ChapterManagementService,
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun list(runId: String): ChaptersResponse {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found")
        return json.decodeFromJsonElement(
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray { storage.listChapters(runId).forEach { add(it) } },
                )
            },
        )
    }

    /** 创建（chapterId 为空）或更新章节。 */
    suspend fun save(runId: String, chapterId: String, payload: SaveChapterRequest): ChapterDto =
        decodeChapter(
            chapterManagement.save(
                runId = runId,
                chapterId = chapterId,
                title = payload.title,
                goal = payload.goal,
                participants = payload.participants,
                content = payload.content,
                perspective = payload.perspective,
            ),
        )

    suspend fun archiveSession(runId: String, request: ArchiveDialogueChapterRequest): ChapterDto =
        decodeChapter(chapterManagement.archive(runId, request.sessionId, request.title))

    suspend fun convertSessionAsNovel(runId: String, request: ArchiveDialogueChapterRequest): ChapterDto =
        decodeChapter(chapterManagement.convert(runId, request.sessionId, request.title))

    /** AI 小说创作：一句设定 → 分章大纲（不落盘）。 */
    suspend fun generateOutline(
        runId: String,
        request: GenerateNovelOutlineRequest,
    ): NovelOutlineResponse {
        val body = chapterManagement.generateOutline(
            runId = runId,
            premise = request.premise,
            chapterCount = request.chapterCount,
            genre = request.genre,
            perspective = request.perspective,
            style = request.style,
        )
        return json.decodeFromJsonElement(body)
    }

    /** AI 小说创作：按大纲与前文写出本章正文。 */
    suspend fun draftChapter(
        runId: String,
        chapterId: String,
        request: DraftChapterRequest,
    ): ChapterDto = decodeChapter(
        chapterManagement.draftChapter(runId, chapterId, request.instruction),
    )

    suspend fun delete(runId: String, chapterId: String): JsonObject =
        chapterManagement.delete(runId, chapterId)

    suspend fun continueWriting(runId: String, chapterId: String): DialogueSessionDto =
        decodeSession(chapterManagement.continueWriting(runId, chapterId))

    suspend fun syncSession(runId: String, chapterId: String): ChapterDto =
        decodeChapter(chapterManagement.sync(runId, chapterId))

    suspend fun reorder(runId: String, chapterId: String, targetOrder: Int): List<ChapterDto> {
        val body = chapterManagement.reorder(runId, chapterId, targetOrder)
        val items = body["items"]?.jsonArray ?: return emptyList()
        return json.decodeFromJsonElement(items)
    }

    /** 导出章节手稿：服务直接渲染成文本，仓库层据此写文件（见类注释 2）。 */
    suspend fun export(runId: String, format: String): String =
        chapterManagement.render(runId, format)

    suspend fun search(runId: String, query: String): List<SearchResultDto> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) {
            throw IllegalArgumentException("query 需为 1-100 字")
        }
        return json.decodeFromJsonElement(
            chapterManagement.search(runId, trimmed, DEFAULT_SEARCH_LIMIT),
        )
    }

    suspend fun ask(runId: String, question: String): AskBookResponseDto =
        decodeAsk(chapterManagement.ask(runId, question))

    private fun decodeChapter(payload: JsonObject): ChapterDto =
        json.decodeFromJsonElement(payload)

    private fun decodeSession(payload: JsonObject): DialogueSessionDto =
        json.decodeFromJsonElement(payload)

    private fun decodeAsk(payload: JsonObject): AskBookResponseDto =
        json.decodeFromJsonElement(payload)

    private companion object {
        /** 与原路由的 `limit` 默认值一致。 */
        const val DEFAULT_SEARCH_LIMIT = 30
    }
}
