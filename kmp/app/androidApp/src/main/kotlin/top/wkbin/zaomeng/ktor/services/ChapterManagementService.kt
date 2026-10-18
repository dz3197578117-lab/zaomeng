package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import top.wkbin.zaomeng.data.api.ApplyChapterContentRequest
import top.wkbin.zaomeng.data.api.ChapterReviewDto
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
import top.wkbin.zaomeng.data.api.GenerateStoryBibleRequest
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.RescueRequest
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.normalizedForStorage
import top.wkbin.zaomeng.data.api.WritingGenres
import top.wkbin.zaomeng.data.api.WritingProfileDto
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 章节管理服务
 *
 * 对应 Python src/web/service_facades/chapters.py 的 ChapterServiceMixin。
 * 存储保持 Ktor server 既有布局 chapters/{chapter_id}.json（单章一文件），
 * 但对外行为与 Python 一致：创建/更新/归档/转换/继续/同步/重排/导出。
 */
class ChapterManagementService(
    private val storage: StorageService,
    private val sessionManagement: SessionManagementService,
    private val llm: LlmClient?,
    private val prompts: PromptLoader?,
) {
    companion object {
        private const val TAG = "ChapterManagement"
        private const val MIN_DIALOGUE_TURNS_FOR_CHAPTER = 6
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_CONTENT_LENGTH = 300_000
        private const val NOVEL_CHAPTER_MAX_CHARS = 3500
        private const val NOVEL_CONTEXT_PREVIOUS_CHARS = 1000
        private const val STORY_BIBLE_FILE = "story_bible.json"
        private const val STORY_CORE_FILE = "story_core.json"
        private const val WRITING_PROFILE_FILE = "writing_profile.json"
        private const val NOVEL_REVIEW_MAX_CHARS = 12000
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** 列出章节（按 order 排序，重排 order 连续）。 */
    fun list(runId: String): List<JsonObject> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = storage.listChapters(runId)
            .sortedBy { it["order"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
        return normalize(chapters)
    }

    /** 搜索书卷内容：章节 + 人物 + 会话。对应 Python search_run_content。 */
    fun search(runId: String, query: String, limit: Int): JsonArray {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return JsonArray(emptyList())
        val results = mutableListOf<JsonObject>()
        for (chapter in list(runId)) {
            val haystack = listOfNotNull(
                chapter["title"]?.jsonPrimitive?.contentOrNull,
                chapter["goal"]?.jsonPrimitive?.contentOrNull,
                chapter["content"]?.jsonPrimitive?.contentOrNull,
            ).joinToString("\n")
            if (!haystack.lowercase().contains(needle)) continue
            results.add(
                buildJsonObject {
                    put("kind", "chapter")
                    put("chapter_id", chapter["chapter_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("session_id", "")
                    put("title", "第 ${chapter["order"]?.jsonPrimitive?.intOrNull ?: 0} 章 · ${chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
                    put("preview", searchPreview(haystack, needle))
                },
            )
            if (results.size >= limit) return buildJsonArray { results.forEach(::add) }
        }
        val manifest = storage.readRunManifest(runId)
        val personas = manifest?.get("artifact_index")?.jsonObject?.get("characters")?.jsonArray
        personas?.forEach { raw ->
            val persona = runCatching { raw.jsonObject }.getOrNull() ?: return@forEach
            val character = persona["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (character.isEmpty()) return@forEach
            val preview = persona["preview"]?.jsonObject ?: JsonObject(emptyMap())
            val previewValues = preview.mapNotNull { (_, value) -> value.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            val haystack = listOf(character) + previewValues
            if (!haystack.joinToString("\n").lowercase().contains(needle)) return@forEach
            results.add(
                buildJsonObject {
                    put("kind", "persona")
                    put("chapter_id", "")
                    put("session_id", "")
                    put("character", character)
                    put("title", "人物 · $character")
                    put("preview", searchPreview(haystack.joinToString("\n"), needle))
                },
            )
            if (results.size >= limit) return buildJsonArray { results.forEach(::add) }
        }
        for (session in storage.listDialogueSessions(runId).take(100)) {
            val sessionId = session["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sessionId.isEmpty()) continue
            val transcript = transcriptText(session)
            if (transcript.isEmpty()) continue
            if (!transcript.lowercase().contains(needle)) continue
            results.add(
                buildJsonObject {
                    put("kind", "session")
                    put("chapter_id", "")
                    put("session_id", sessionId)
                    put("title", session["last_entry_preview"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: "会话 ${sessionId.takeLast(6)}")
                    put("preview", searchPreview(transcript, needle))
                },
            )
            if (results.size >= limit) break
        }
        return buildJsonArray { results.forEach(::add) }
    }

    /** 问书卷。对应 Python answer_book_question。 */
    suspend fun ask(runId: String, question: String): JsonObject {
        val normalized = question.trim()
        if (normalized.isEmpty()) throw IllegalArgumentException("问题不能为空。")
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val evidence = mutableListOf<JsonObject>()
        val queries = mutableListOf<String>()
        val manifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val characterNames = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.filter { it.isNotEmpty() && normalized.contains(it) }.orEmpty()
        queries.addAll(if (characterNames.isNotEmpty()) characterNames else listOf(normalized))
        for (query in queries) {
            search(runId, query, 8).forEach { element ->
                runCatching { element.jsonObject }.getOrNull()?.let { item ->
                    if (!evidence.contains(item)) evidence.add(item)
                }
            }
        }
        if (evidence.isEmpty()) {
            return buildJsonObject {
                put("answer", "没有在当前书卷中找到可引用的证据。请换用角色名、章节标题或更具体的关键词。")
                put("evidence", JsonArray(emptyList()))
            }
        }
        val sourceText = evidence.take(12).mapIndexed { index, item ->
            "[${index + 1}] ${item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n${item["preview"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }.joinToString("\n\n")
        val userPrompt = requireNotNull(prompts) { "Prompt loader is unavailable" }
            .getAskBookTemplate()
            .replace("{question}", normalized)
            .replace("{evidence}", sourceText)
        val content = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage(
                    "user",
                    userPrompt,
                ),
            ),
            temperature = 0.2,
            maxTokens = 900,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        return buildJsonObject {
            put("answer", content)
            put("evidence", buildJsonArray { evidence.take(12).forEach(::add) })
        }
    }

    /** 创建或更新章节。对应 Python save_chapter。 */
    fun save(
        runId: String,
        chapterId: String,
        title: String,
        goal: String,
        participants: List<String>,
        content: String,
        sourceSessionId: String = "",
        contextSummary: String = "",
        perspective: String = "",
    ): JsonObject {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) throw IllegalArgumentException("章节标题不能为空。")
        if (normalizedTitle.length > MAX_TITLE_LENGTH) throw IllegalArgumentException("章节标题不能超过 $MAX_TITLE_LENGTH 个字符。")
        val normalizedContent = content.trim()
        if (normalizedContent.length > MAX_CONTENT_LENGTH) throw IllegalArgumentException("章节草稿不能超过 30 万个字符。")
        val normalizedParticipants = participants.map(String::trim).filter(String::isNotBlank).distinct().take(40)
        val now = nowIsoString()
        val chapters = list(runId)
        val targetId = chapterId.trim()
        val existingIndex = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == targetId }
        if (targetId.isNotEmpty() && existingIndex < 0) throw NoSuchElementException("Chapter not found: $targetId")
        val chapter: JsonObject
        val updatedChapters: List<JsonObject>
        if (existingIndex >= 0) {
            val old = chapters[existingIndex]
            chapter = buildJsonObject {
                old.forEach { (key, value) -> put(key, value) }
                put("title", normalizedTitle)
                put("goal", goal.trim())
                put("participants", buildJsonArray { normalizedParticipants.forEach { add(JsonPrimitive(it)) } })
                put("content", normalizedContent)
                if (perspective.isNotBlank()) put("perspective", perspective.trim())
                if (sourceSessionId.isNotEmpty()) put("source_session_id", sourceSessionId.trim())
                if (contextSummary.isNotEmpty()) put("context_summary", contextSummary.trim())
                put("updated_at", now)
            }
            updatedChapters = chapters.toMutableList().apply { this[existingIndex] = chapter }
        } else {
            chapter = buildJsonObject {
                put("chapter_id", "chapter-" + randomUuid().replace("-", "").take(12))
                put("order", chapters.size + 1)
                put("title", normalizedTitle)
                put("goal", goal.trim())
                put("participants", buildJsonArray { normalizedParticipants.forEach { add(JsonPrimitive(it)) } })
                put("content", normalizedContent)
                put("perspective", perspective.trim())
                put("source_session_id", sourceSessionId.trim())
                put("context_summary", contextSummary.trim())
                put("last_session_id", "")
                put("synced_transcript_count", 0)
                put("created_at", now)
                put("updated_at", now)
            }
            updatedChapters = chapters + chapter
        }
        writeAll(runId, normalize(updatedChapters))
        return chapter
    }

    /** 归档会话为章节。对应 Python archive_dialogue_session_as_chapter。 */
    fun archive(runId: String, sessionId: String, title: String = ""): JsonObject {
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        if (transcript.isEmpty()) throw IllegalArgumentException("这个会话还没有可归档的内容。")
        val dialogueTurns = meaningfulTurnCount(transcript)
        if (dialogueTurns < MIN_DIALOGUE_TURNS_FOR_CHAPTER) {
            throw IllegalArgumentException("当前只有 $dialogueTurns 轮有效对话，至少需要 $MIN_DIALOGUE_TURNS_FOR_CHAPTER 轮才能转为小说章节。")
        }
        val content = transcript.joinToString("\n\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "旁白"}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }.trim()
        if (content.isEmpty()) throw IllegalArgumentException("这个会话还没有可归档的内容。")
        val chapters = list(runId)
        val now = nowIsoString()
        val chapterTitle = title.trim().ifEmpty {
            session["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: "第 ${chapters.size + 1} 章"
        }
        val chapter = buildJsonObject {
            put("chapter_id", "chapter-" + randomUuid().replace("-", "").take(12))
            put("order", chapters.size + 1)
            put("title", chapterTitle)
            put("goal", session["scene_progress"]?.jsonObject?.get("current_goal")?.jsonPrimitive?.contentOrNull.orEmpty())
            put("participants", session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            put("content", content)
            put("source_session_id", session["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: sessionId)
            put("context_summary", "")
            put("last_session_id", "")
            put("synced_transcript_count", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        writeAll(runId, normalize(chapters + chapter))
        return chapter
    }

    /** 对话转小说。对应 Python convert_dialogue_session_to_novel。 */
    suspend fun convert(runId: String, sessionId: String, title: String = ""): JsonObject {
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val dialogueTurns = meaningfulTurnCount(transcript)
        if (dialogueTurns < MIN_DIALOGUE_TURNS_FOR_CHAPTER) {
            throw IllegalArgumentException("当前只有 $dialogueTurns 轮有效对话，至少需要 $MIN_DIALOGUE_TURNS_FOR_CHAPTER 轮才能转为小说章节。")
        }
        val chapters = list(runId)
        val previous = chapters.lastOrNull()
        val previousContent = previous?.get("content")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val storyRecap = session["story_recap"]?.jsonObject ?: JsonObject(emptyMap())
        val contextParts = mutableListOf<String>()
        if (previous != null && previousContent.isNotEmpty()) {
            val summary = previous["context_summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (summary.isNotEmpty()) contextParts.add("上一章摘要：$summary")
            contextParts.add("上一章《${previous["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}》结尾：\n${previousContent.takeLast(NOVEL_CONTEXT_PREVIOUS_CHARS)}")
        }
        val summary = storyRecap["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (summary.isNotEmpty()) contextParts.add("本段剧情摘要：$summary")
        val scene = listOfNotNull(
            storyRecap["time_hint"]?.jsonPrimitive?.contentOrNull,
            storyRecap["location"]?.jsonPrimitive?.contentOrNull,
            storyRecap["atmosphere"]?.jsonPrimitive?.contentOrNull,
            session["scene_card"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull,
        ).joinToString("，")
        val dialogues = transcript.mapNotNull { item ->
            val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (role in setOf("scene", "director", "loading")) return@mapNotNull null
            val speaker = item["speaker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = item["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (speaker.isEmpty() || message.isEmpty()) return@mapNotNull null
            val dialogue = buildJsonObject {
                put("speaker", speaker)
                put("message", message)
                item["inner_thought"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("inner_thought", it) }
            }
            dialogue
        }
        if (dialogues.isEmpty()) throw IllegalArgumentException("这个会话还没有可转成小说的有效对话。")
        val system = loader.getNovelRewritePrompt()
        val llmInput = buildJsonObject {
            put("context", contextParts.joinToString("\n\n").trim())
            put("scene", scene)
            put("point_of_view", "第三人称限知")
            put("style", "自然、克制、有画面感")
            put("chapter_length", "目标约 2500 字，最多不超过 $NOVEL_CHAPTER_MAX_CHARS 字。")
            put("dialogues", buildJsonArray { dialogues.forEach(::add) })
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", system),
                LlmClient.ChatMessage("user", llmInput.toString()),
            ),
            temperature = 0.55,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (raw.isEmpty()) throw IllegalStateException("模型没有返回小说正文，请稍后重试。")
        val rawLines = raw.lines()
        val generatedTitle = rawLines.firstOrNull { it.isNotBlank() }?.trim(' ', '#', '《', '》', '「', '」', '"', '\'') ?: ""
        var body = rawLines.drop(1).joinToString("\n").trim()
        if (body.isEmpty()) body = raw
        if (body.length > NOVEL_CHAPTER_MAX_CHARS) body = trimChapterContent(body, NOVEL_CHAPTER_MAX_CHARS)
        val chapterTitle = title.trim().ifEmpty {
            generatedTitle.ifEmpty {
                storyRecap["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { "第 ${chapters.size + 1} 章" }
            }
        }
        // 对齐 Python：context_summary = summary or body[:180]
        val contextSummary = summary.ifBlank { body.take(180) }
        val participants = session["participants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return save(
            runId,
            chapterId = "",
            title = chapterTitle,
            goal = "由对话改写成小说正文。",
            participants = participants,
            content = body,
            sourceSessionId = session["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: sessionId,
            contextSummary = contextSummary,
        )
    }

    // ===== 创作工具：设定集 / 类型模版 / 卡文救援 / 润色检查 =====

    /**
     * 读取设定集。文件不存在或损坏时返回空设定，不阻塞创作。
     */
    fun readStoryBible(runId: String): StoryBibleDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val raw = storage.readTextOrNull(storyBibleFile(runId)) ?: return StoryBibleDto()
        return runCatching { json.decodeFromString<StoryBibleDto>(raw) }.getOrDefault(StoryBibleDto())
    }

    /** 保存设定集（先清洗长度与空人物，避免把模型的长篇废话原样落盘）。 */
    fun saveStoryBible(runId: String, bible: StoryBibleDto): StoryBibleDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val clean = bible.normalizedForStorage()
        require(!clean.isEmpty) { "设定还是空的，先生成或填写一些内容。" }
        storage.writeBytesAtomically(storyBibleFile(runId), json.encodeToString(clean).encodeToByteArray())
        return clean
    }

    /**
     * 依据一句设定（或已有章节目标）生成设定集提案。**不落盘**，等用户确认后调 [saveStoryBible]。
     */
    suspend fun generateStoryBible(
        runId: String,
        request: GenerateStoryBibleRequest,
    ): StoryBibleDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val chapters = list(runId)
        val seed = request.premise.trim().ifBlank {
            buildString {
                append("书名：").append(bookTitleOf(runId, manifest)).append('\n')
                val goals = chapters.take(8).mapNotNull { item ->
                    item["goal"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                }
                if (goals.isNotEmpty()) {
                    append("已有章节目标：\n").append(goals.joinToString("\n") { "· $it" })
                }
            }
        }
        require(seed.trim().length >= 4) { "先写一句故事设定，或先建几章大纲。" }
        val genre = WritingGenres.byId(request.genre)
        val input = buildJsonObject {
            put("premise", seed.trim().take(2000))
            put("book_title", bookTitleOf(runId, manifest))
            put("perspective", request.perspective.trim().ifBlank { "第三人称限知" })
            if (genre != null) {
                put("genre", buildJsonObject {
                    put("name", genre.name)
                    put("beats", genre.beats)
                })
            }
            val known = characterBriefs(manifest)
            if (known.isNotEmpty()) {
                put("known_characters", buildJsonArray {
                    known.forEach { (name, brief) ->
                        add(buildJsonObject {
                            put("name", name)
                            if (brief.isNotBlank()) put("brief", brief)
                        })
                    }
                })
            }
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getStoryBiblePrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.7,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回设定，请稍后重试。")
        return WritingToolParser.parseStoryBible(raw)
    }

    fun readWritingProfile(runId: String): WritingProfileDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val raw = storage.readTextOrNull(writingProfileFile(runId)) ?: return WritingProfileDto()
        return runCatching { json.decodeFromString<WritingProfileDto>(raw) }.getOrDefault(WritingProfileDto())
    }

    fun saveWritingProfile(runId: String, profile: WritingProfileDto): WritingProfileDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val genre = profile.genre.trim()
        require(genre.isEmpty() || WritingGenres.byId(genre) != null) { "未知的类型：$genre" }
        val clean = WritingProfileDto(
            genre = genre,
            chapterLength = profile.chapterLength.coerceIn(800, 6000),
            notes = profile.notes.trim().take(500),
            voiceSample = profile.voiceSample.trim().take(2000),
            avoid = profile.avoid.trim().take(400),
        )
        storage.writeBytesAtomically(writingProfileFile(runId), json.encodeToString(clean).encodeToByteArray())
        return clean
    }

    /**
     * 卡文救援：针对当前章节的卡点，给出多个可写的方案（不落盘）。
     */
    suspend fun rescue(runId: String, request: RescueRequest): List<RescueOptionDto> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val problem = request.problem.trim().take(500)
        require(problem.isNotEmpty()) { "先写一下卡在哪里。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val chapters = list(runId)
        val chapter = request.chapterId.trim().takeIf(String::isNotBlank)?.let { id ->
            chapters.firstOrNull { it["chapter_id"]?.jsonPrimitive?.contentOrNull == id }
                ?: throw NoSuchElementException("Chapter not found: $id")
        }
        val count = request.count.coerceIn(1, 5)
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("kind", rescueKindLabel(request.kind))
            put("count", count)
            put("problem", problem)
            if (chapter != null) {
                put("chapter_title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("chapter_goal", chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val tail = chapter["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(2500)
                if (tail.isNotBlank()) put("chapter_tail", tail)
            }
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getStuckRescuePrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.9,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回方案，请稍后重试。")
        val options = WritingToolParser.parseRescue(raw)
        if (options.isEmpty()) throw IllegalStateException("模型返回的方案无法解析，请重试。")
        return options.take(count)
    }

    /**
     * 检查章节：语病、语气、前后矛盾、设定一致性，并生成摘要。不落盘。
     */
    suspend fun reviewChapter(runId: String, chapterId: String): ChapterReviewDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = chapters[index]
        val content = chapter["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(content.length >= 50) { "这一章还没有足够的正文，先写一段再检查。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val previous = chapters.take(index).takeLast(2).joinToString("\n\n") { item ->
            val title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val summary = item["context_summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val tail = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(600)
            buildString {
                append("《").append(title).append("》")
                if (summary.isNotEmpty()) append("\n摘要：").append(summary)
                if (tail.isNotEmpty()) append("\n结尾：\n").append(tail)
            }
        }
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapter_title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("chapter_goal", chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("chapter_content", content.take(NOVEL_REVIEW_MAX_CHARS))
            if (content.length > NOVEL_REVIEW_MAX_CHARS) {
                put("truncated_note", "正文过长，只检查了前 $NOVEL_REVIEW_MAX_CHARS 字。")
            }
            if (previous.isNotBlank()) put("previous_chapters", previous)
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getPolishReviewPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.3,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回检查结果，请稍后重试。")
        return WritingToolParser.parseReview(raw)
    }

    /**
     * 按检查建议改写正文并保存。保留标题、目标、人物与视角，只覆盖正文与摘要。
     */
    suspend fun polishChapter(
        runId: String,
        chapterId: String,
        instruction: String = "",
    ): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = chapters[index]
        val content = chapter["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(content.length >= 50) { "这一章还没有足够的正文，先写一段再润色。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val title = chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val goal = chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapter_title", title)
            put("chapter_goal", goal)
            put("chapter_content", content)
            if (instruction.isNotBlank()) put("extra_instruction", instruction.trim().take(300))
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getPolishRewritePrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.4,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        var body = stripLeadingChapterHeading(raw, title, index + 1)
        if (body.isBlank()) throw IllegalStateException("模型没有返回润色后的正文，请稍后重试。")
        if (body.length > NOVEL_CHAPTER_MAX_CHARS) body = trimChapterContent(body, NOVEL_CHAPTER_MAX_CHARS)
        val participants = chapter["participants"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val perspective = chapter["perspective"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return save(
            runId = runId,
            chapterId = chapterId,
            title = title,
            goal = goal,
            participants = participants,
            content = body,
            contextSummary = body.take(180),
            perspective = perspective,
        )
    }

    // ===== 人定核心 =====

    fun readStoryCore(runId: String): StoryCoreDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val raw = storage.readTextOrNull(storyCoreFile(runId)) ?: return StoryCoreDto()
        return runCatching { json.decodeFromString<StoryCoreDto>(raw) }.getOrDefault(StoryCoreDto())
    }

    fun saveStoryCore(runId: String, core: StoryCoreDto): StoryCoreDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val clean = core.normalizedForStorage()
        require(!clean.isEmpty) { "故事核心还是空的，先生成或填写一些内容。" }
        storage.writeBytesAtomically(storyCoreFile(runId), json.encodeToString(clean).encodeToByteArray())
        return clean
    }

    /** 依据设定与已有章节提出故事核心，**不落盘**，等人确认。 */
    suspend fun generateStoryCore(
        runId: String,
        request: GenerateStoryCoreRequest,
    ): StoryCoreDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val chapters = list(runId)
        val bible = readStoryBible(runId)
        val seed = request.premise.trim().ifBlank {
            buildString {
                append("书名：").append(bookTitleOf(runId, manifest)).append('\n')
                if (!bible.world.isBlank()) append("世界观：").append(bible.world.take(600)).append('\n')
                val goals = chapters.take(10).mapNotNull { item ->
                    item["goal"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                }
                if (goals.isNotEmpty()) append("已有章节目标：\n").append(goals.joinToString("\n") { "· $it" })
            }
        }
        require(seed.trim().length >= 4) { "先写一句故事设定，或先生成设定集。" }
        val input = buildJsonObject {
            put("premise", seed.trim().take(2000))
            put("book_title", bookTitleOf(runId, manifest))
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getStoryCorePrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.7,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回故事核心，请稍后重试。")
        return WritingToolParser.parseStoryCore(raw)
    }

    // ===== 场景卡与多版本初稿 =====

    suspend fun generateSceneCards(runId: String, request: GenerateSceneCardsRequest): List<SceneCardDto> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val chapters = list(runId)
        val chapter = chapters.firstOrNull {
            it["chapter_id"]?.jsonPrimitive?.contentOrNull == request.chapterId
        } ?: throw NoSuchElementException("Chapter not found: ${request.chapterId}")
        val count = request.count.coerceIn(2, 5)
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapter_title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("chapter_goal", chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("count", count)
            val tail = chapter["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(2000)
            if (tail.isNotBlank()) put("previous_text", tail)
            put("outline", chapterOutline(chapters))
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getSceneCardsPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.75,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回场景卡，请稍后重试。")
        val cards = WritingToolParser.parseSceneCards(raw)
        if (cards.isEmpty()) throw IllegalStateException("模型返回的场景卡无法解析，请重试。")
        return cards.take(count)
    }

    fun saveSceneCards(runId: String, chapterId: String, cards: List<SceneCardDto>): List<SceneCardDto> {
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val clean = cards.take(8).map {
            it.copy(
                title = it.title.trim().take(60),
                place = it.place.trim().take(60),
                time = it.time.trim().take(60),
                characters = it.characters.map(String::trim).filter(String::isNotBlank).take(12),
                goal = it.goal.trim().take(240),
                conflict = it.conflict.trim().take(240),
                turn = it.turn.trim().take(240),
                exit = it.exit.trim().take(240),
            )
        }.filter { it.title.isNotBlank() || it.goal.isNotBlank() }
        require(clean.isNotEmpty()) { "场景卡还是空的。" }
        val updated = buildJsonObject {
            chapters[index].forEach { (key, value) -> put(key, value) }
            put("scene_cards", json.encodeToJsonElement(clean))
            put("updated_at", nowIsoString())
        }
        writeAll(runId, chapters.toMutableList().apply { this[index] = updated })
        return clean
    }

    /** 同一章写两个差异明显的版本，**不落盘**，等人挑一版再写入。 */
    suspend fun draftVersions(runId: String, request: DraftVersionsRequest): List<DraftVersionDto> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == request.chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: ${request.chapterId}")
        val chapter = chapters[index]
        val cards = chapter["scene_cards"]?.jsonArray?.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement<SceneCardDto>(entry) }.getOrNull()
        }.orEmpty()
        require(cards.isNotEmpty()) { "这一章还没有场景卡，先生成或保存场景卡再写初稿。" }
        val count = request.count.coerceIn(1, 3)
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapter_number", index + 1)
            put("chapter_title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("chapter_goal", chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("count", count)
            put("scene_cards", json.encodeToJsonElement(cards))
            if (request.directions.isNotEmpty()) {
                put("directions", buildJsonArray { request.directions.take(3).forEach { add(JsonPrimitive(it)) } })
            }
            if (request.instruction.isNotBlank()) put("extra_instruction", request.instruction.trim().take(300))
            put("outline", chapterOutline(chapters))
            val previous = chapters.take(index).takeLast(2).joinToString("\n\n") { item ->
                val summary = item["context_summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val tail = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(600)
                buildString {
                    append("《").append(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()).append("》")
                    if (summary.isNotEmpty()) append("\n摘要：").append(summary)
                    if (tail.isNotEmpty()) append("\n结尾：\n").append(tail)
                }
            }
            if (previous.isNotBlank()) put("previous_chapters", previous)
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getChapterVersionsPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.85,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回初稿，请稍后重试。")
        val versions = WritingToolParser.parseDraftVersions(raw)
        if (versions.isEmpty()) throw IllegalStateException("模型返回的初稿无法解析，请重试。")
        return versions.take(count)
    }

    /** 采用某一版初稿：覆盖正文，保留标题、目标、人物、视角与场景卡。 */
    fun applyChapterContent(
        runId: String,
        chapterId: String,
        request: ApplyChapterContentRequest,
    ): JsonObject {
        val content = request.content.trim()
        require(content.length >= 50) { "这一版正文太短，换一版或继续生成。" }
        val chapters = list(runId)
        val chapter = chapters.firstOrNull {
            it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId
        } ?: throw NoSuchElementException("Chapter not found: $chapterId")
        return save(
            runId = runId,
            chapterId = chapterId,
            title = chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            goal = chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            participants = chapter["participants"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            content = content,
            contextSummary = request.contextSummary.trim().ifBlank { content.take(180) },
            perspective = chapter["perspective"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    // ===== 局部重写 =====

    /** 只改一段：把「合理但无聊」改成有性格、有细节的表达。不落盘。 */
    suspend fun rewriteSelection(
        runId: String,
        request: RewriteSelectionRequest,
    ): List<RewriteOptionDto> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val text = request.text.trim()
        require(text.length >= 10) { "先贴一段要改的正文（至少 10 个字）。" }
        require(text.length <= 4000) { "一次最多改 4000 字，长段落请分两次。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val count = request.count.coerceIn(1, 4)
        val input = buildJsonObject {
            put("text", text)
            put("count", count)
            put(
                "instruction",
                request.instruction.trim().ifBlank { "把这段改得更有性格、更有细节，别改剧情。" }.take(300),
            )
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getRewriteSelectionPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.9,
            maxTokens = 2048,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回改写，请稍后重试。")
        val options = WritingToolParser.parseRewriteOptions(raw)
        if (options.isEmpty()) throw IllegalStateException("模型返回的改写无法解析，请重试。")
        return options.take(count)
    }

    // ===== 全书一致性与终审 =====

    suspend fun checkConsistency(runId: String): ConsistencyReportDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = list(runId).filter { it["content"]?.jsonPrimitive?.contentOrNull.orEmpty().isNotBlank() }
        require(chapters.size >= 2) { "至少要有两章正文才能做全书一致性检查。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapters", buildJsonArray {
                chapters.forEach { item ->
                    add(buildJsonObject {
                        put("title", item["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("goal", item["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put(
                            "summary",
                            item["context_summary"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?: item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200),
                        )
                        val tail = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(300)
                        if (tail.isNotBlank()) put("ending", tail)
                    })
                }
            })
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getConsistencyCheckPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.2,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回一致性报告，请稍后重试。")
        return WritingToolParser.parseConsistency(raw)
    }

    /** 人工终审：只给检查结果，是否放行由人决定。 */
    suspend fun finalReview(runId: String): FinalReviewDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = list(runId).filter { it["content"]?.jsonPrimitive?.contentOrNull.orEmpty().isNotBlank() }
        require(chapters.isNotEmpty()) { "还没有正文可以终审。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapters", buildJsonArray {
                chapters.forEach { item ->
                    add(buildJsonObject {
                        put("title", item["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put(
                            "summary",
                            item["context_summary"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?: item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300),
                        )
                        val tail = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(400)
                        if (tail.isNotBlank()) put("ending", tail)
                    })
                }
            })
            putNovelContext(runId, manifest, this)
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getFinalReviewPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.3,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回终审结果，请稍后重试。")
        return WritingToolParser.parseFinalReview(raw)
    }

    private fun chapterOutline(chapters: List<JsonObject>): String =
        chapters.mapIndexed { index, item ->
            val title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val goal = item["goal"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "第 ${index + 1} 章 $title：$goal"
        }.joinToString("\n")

    private fun storyCoreFile(runId: String): Path =
        storage.getRunDirectory(runId) / STORY_CORE_FILE

    private fun storyBibleFile(runId: String): Path =
        storage.getRunDirectory(runId) / STORY_BIBLE_FILE

    private fun writingProfileFile(runId: String): Path =
        storage.getRunDirectory(runId) / WRITING_PROFILE_FILE

    private fun rescueKindLabel(kind: String): String = when (kind.trim().lowercase()) {
        "dialogue" -> "对话写不下去"
        "scene" -> "场景太空"
        "twist" -> "需要一个反转"
        else -> "情节该往哪走"
    }

    /** 把设定集与类型模版塞进请求；两者都可能不存在，调用方无需判断。 */
    private fun putNovelContext(
        runId: String,
        manifest: JsonObject?,
        input: kotlinx.serialization.json.JsonObjectBuilder,
    ) {
        val core = readStoryCore(runId)
        if (!core.isEmpty) {
            input.put("story_core", json.encodeToJsonElement(core))
        }
        val bible = readStoryBible(runId)
        if (!bible.isEmpty) {
            input.put("story_bible", json.encodeToJsonElement(bible))
        }
        val profile = readWritingProfile(runId)
        if (profile.genre.isNotBlank() || profile.notes.isNotBlank()) {
            val genre = WritingGenres.byId(profile.genre)
            input.put("writing_profile", buildJsonObject {
                if (genre != null) {
                    put("genre", genre.name)
                    put("beats", genre.beats)
                }
                put("chapter_length", profile.chapterLength)
                if (profile.notes.isNotBlank()) put("notes", profile.notes)
                if (profile.voiceSample.isNotBlank()) put("voice_sample", profile.voiceSample.take(1200))
                if (profile.avoid.isNotBlank()) put("avoid", profile.avoid)
            })
        }
        if (bible.characters.isEmpty()) {
            val known = characterBriefs(manifest)
            if (known.isNotEmpty()) {
                input.put("known_characters", buildJsonArray {
                    known.forEach { (name, brief) ->
                        add(buildJsonObject {
                            put("name", name)
                            if (brief.isNotBlank()) put("brief", brief)
                        })
                    }
                })
            }
        }
    }

    /**
     * AI 小说创作第一步：一句设定 → 分章大纲。
     *
     * **不落盘**：大纲只是提案，用户在界面上确认后才由客户端逐章调用 [save] 写入。
     * 这样「生成不满意」不会在书卷里留下垃圾章节。
     */    suspend fun generateOutline(
        runId: String,
        premise: String,
        chapterCount: Int,
        genre: String,
        perspective: String,
        style: String,
    ): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val idea = premise.trim()
        require(idea.length >= 4) { "先用一句话写下故事设定（至少 4 个字）。" }
        require(idea.length <= 800) { "设定请控制在 800 字以内。" }
        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val count = chapterCount.coerceIn(1, 20)
        val manifest = storage.readRunManifest(runId)
        val characters = characterBriefs(manifest)

        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("premise", idea)
            put("genre", genre.trim().ifBlank { "不限" })
            put("perspective", perspective.trim().ifBlank { "第三人称限知" })
            put("style", style.trim().ifBlank { "自然、克制、有画面感" })
            put("chapter_count", count)
            putNovelContext(runId, manifest, this)
            if (characters.isNotEmpty()) {
                put("known_characters", buildJsonArray {
                    characters.forEach { (name, brief) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                if (brief.isNotBlank()) put("brief", brief)
                            },
                        )
                    }
                })
            }
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getNovelOutlinePrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.8,
            maxTokens = 3072,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        if (raw.isBlank()) throw IllegalStateException("模型没有返回大纲，请稍后重试。")
        val outline = NovelOutlineParser.parse(raw, count)
        return buildJsonObject {
            put("items", buildJsonArray {
                outline.forEach { chapter ->
                    add(
                        buildJsonObject {
                            put("title", chapter.title)
                            put("goal", chapter.goal)
                        },
                    )
                }
            })
        }
    }

    /**
     * AI 小说创作第二步：依据大纲与前文写出本章正文。
     *
     * 上下文包含：全书分章大纲、前两章的摘要与结尾、人物简报、本章目标。
     * 只覆盖 `content` 与 `context_summary`，不动标题、目标、出场人物。
     */
    suspend fun draftChapter(
        runId: String,
        chapterId: String,
        instruction: String = "",
    ): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = chapters[index]
        val title = chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val goal = chapter["goal"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(title.isNotBlank() || goal.isNotBlank()) { "这一章还没有标题或目标，先补一个章节目标再生成。" }

        val client = requireNotNull(llm) { "还没有配置模型，请先到设置里填写模型与密钥。" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置。" }
        val manifest = storage.readRunManifest(runId)
        val perspective = chapter["perspective"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: "第三人称限知"
        val outline = chapters.mapIndexed { chapterIndex, item ->
            val itemTitle = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itemGoal = item["goal"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "第 ${chapterIndex + 1} 章 ${itemTitle}：${itemGoal}"
        }.joinToString("\n")
        val previous = chapters.take(index).takeLast(2).joinToString("\n\n") { item ->
            val itemTitle = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val summary = item["context_summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val tail = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeLast(NOVEL_CONTEXT_PREVIOUS_CHARS)
            buildString {
                append("《").append(itemTitle).append("》")
                if (summary.isNotEmpty()) append("\n摘要：").append(summary)
                if (tail.isNotEmpty()) append("\n结尾：\n").append(tail)
            }
        }
        val characters = characterBriefs(manifest)

        val input = buildJsonObject {
            put("book_title", bookTitleOf(runId, manifest))
            put("chapter_number", index + 1)
            put("chapter_title", title)
            put("chapter_goal", goal)
            put("perspective", perspective)
            put(
                "target_length",
                "约 ${readWritingProfile(runId).chapterLength.coerceIn(800, NOVEL_CHAPTER_MAX_CHARS)} 字，" +
                    "不超过 $NOVEL_CHAPTER_MAX_CHARS 字",
            )
            put("outline", outline)
            putNovelContext(runId, manifest, this)
            if (previous.isNotBlank()) put("previous_chapters", previous)
            if (instruction.isNotBlank()) put("extra_instruction", instruction.trim().take(300))
            if (characters.isNotEmpty()) {
                put("characters", buildJsonArray {
                    characters.forEach { (name, brief) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                if (brief.isNotBlank()) put("brief", brief)
                            },
                        )
                    }
                })
            }
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", loader.getNovelChapterPrompt()),
                LlmClient.ChatMessage("user", input.toString()),
            ),
            temperature = 0.75,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content.orEmpty()
        var body = stripLeadingChapterHeading(raw, title, index + 1)
        if (body.isBlank()) throw IllegalStateException("模型没有返回正文，请稍后重试。")
        if (body.length > NOVEL_CHAPTER_MAX_CHARS) body = trimChapterContent(body, NOVEL_CHAPTER_MAX_CHARS)
        val participants = chapter["participants"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return save(
            runId = runId,
            chapterId = chapterId,
            title = title,
            goal = goal,
            participants = participants,
            content = body,
            contextSummary = body.take(180),
            perspective = perspective,
        )
    }

    /** 模型常会把「第 3 章 标题」再抄一遍，正文里不该出现这行。 */
    private fun stripLeadingChapterHeading(raw: String, title: String, number: Int): String {
        val lines = raw.trim().lines().toMutableList()
        if (lines.isEmpty()) return ""
        val first = lines.first().trim().trim('#', '*', '《', '》').trim()
        val looksLikeHeading = (first.startsWith("第") && first.contains("章") && first.length <= 40) ||
            (title.isNotBlank() && first == title)
        if (looksLikeHeading) lines.removeAt(0)
        return lines.joinToString("\n").trim()
    }

    private fun bookTitleOf(runId: String, manifest: JsonObject?): String =
        manifest?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: manifest?.get("novel_id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: runId

    /**
     * 人物简报：只取能影响「怎么说话、会做什么」的字段，避免把整份档案塞进提示词。
     * 未蒸馏的书卷没有人物档案，退回创建时记录的人物名单——只有名字，
     * 一致性会弱一些，但大纲与正文照常生成。
     */
    private fun characterBriefs(manifest: JsonObject?): List<Pair<String, String>> {
        val briefs = manifest?.get("artifact_index")?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { raw ->
                val obj = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val preview = obj["preview"]?.jsonObject ?: JsonObject(emptyMap())
                val brief = listOf("core_identity", "story_role", "speech_style", "temperament_type")
                    .mapNotNull { key -> preview[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                    .joinToString("；")
                name to brief
            }
            ?.take(8)
            .orEmpty()
        if (briefs.isNotEmpty()) return briefs
        return manifest?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()?.trim() }
            ?.filter(String::isNotBlank)
            ?.take(8)
            ?.map { it to "" }
            .orEmpty()
    }

    /** 删除章节。对应 Python delete_chapter。 */
    fun delete(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val remaining = chapters.filterNot { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (remaining.size == chapters.size) throw NoSuchElementException("Chapter not found: $chapterId")
        writeAll(runId, normalize(remaining))
        return buildJsonObject {
            put("status", "deleted")
            put("chapter_id", chapterId)
        }
    }

    /** 从章节继续写作：创建续写会话。对应 Python continue_chapter_writing。 */
    fun continueWriting(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val chapter = chapters.firstOrNull { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
            ?: throw NoSuchElementException("Chapter not found: $chapterId")
        var participants = chapter["participants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter(String::isNotBlank).orEmpty()
        if (participants.isEmpty()) {
            val manifest = storage.readRunManifest(runId)
            participants = (manifest?.get("artifact_index")?.jsonObject?.get("characters")?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ?.filter(String::isNotBlank)).orEmpty().take(4)
        }
        if (participants.isEmpty()) throw IllegalArgumentException("请先为章节填写至少一位出场人物。")
        val draftExcerpt = chapter["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().takeLast(4_000)
        val sceneProfile = buildJsonObject {
            put("title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put(
                "opening_situation",
                if (draftExcerpt.isNotEmpty()) "正在续写本章。已有草稿如下：\n$draftExcerpt" else "正在续写一个新章节。",
            )
            put("scene_drive", chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "推进本章剧情，并保持人物一致。")
            put("expected_rhythm", "延续已有草稿，自然推进。")
        }
        val session = sessionManagement.createDialogueSession(
            runId = runId,
            mode = "observe",
            participants = participants,
            sceneProfile = sceneProfile,
        )
        val sessionId = session["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val updated = buildJsonObject {
            chapter.forEach { (key, value) -> put(key, value) }
            put("last_session_id", sessionId)
            put("updated_at", nowIsoString())
        }
        val updatedChapters = chapters.toMutableList().apply {
            val index = indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
            if (index >= 0) this[index] = updated
        }
        writeAll(runId, normalize(updatedChapters))
        return session
    }

    /** 同步会话最新轮次到章节。对应 Python sync_latest_session_to_chapter。 */
    fun sync(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = chapters[index]
        val sessionId = chapter["last_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) throw IllegalArgumentException("请先从这个章节进入一次继续写作。")
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session).filter { it["message"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
        val syncedCount = (chapter["synced_transcript_count"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val freshEntries = transcript.drop(syncedCount)
        if (freshEntries.isEmpty()) return chapter
        val addition = freshEntries.joinToString("\n\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "旁白"}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }
        val existing = chapter["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val updated = buildJsonObject {
            chapter.forEach { (key, value) -> put(key, value) }
            put("content", listOf(existing, addition).filter(String::isNotBlank).joinToString("\n\n"))
            put("synced_transcript_count", transcript.size)
            put("updated_at", nowIsoString())
        }
        val updatedChapters = chapters.toMutableList().apply { this[index] = updated }
        writeAll(runId, normalize(updatedChapters))
        return updated
    }

    /** 重排章节顺序。对应 Python reorder_chapter，返回 items。 */
    fun reorder(runId: String, chapterId: String, targetOrder: Int): JsonObject {
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val targetIndex = targetOrder.coerceIn(1, chapters.size) - 1
        val mutable = chapters.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(targetIndex, item)
        val normalized = normalize(mutable)
        writeAll(runId, normalized)
        return buildJsonObject {
            put("items", buildJsonArray { normalized.forEach(::add) })
        }
    }

    /** 导出章节手稿。对应 Python render_chapter_manuscript。 */
    fun render(runId: String, format: String): String {
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val manifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val title = (manifest["title"]?.jsonPrimitive?.contentOrNull ?: manifest["novel_id"]?.jsonPrimitive?.contentOrNull)
            ?.takeIf(String::isNotBlank) ?: "未命名书卷"
        val chapters = list(runId)
        val lines = mutableListOf<String>()
        if (normalizedFormat == "markdown") {
            lines.add("# $title")
            lines.add("")
            for (chapter in chapters) {
                lines.add("## ${chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
                lines.add("")
                chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { goal ->
                    lines.add("> 本章目标：$goal")
                    lines.add("")
                }
                chapter["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { content ->
                    lines.add(content.trim())
                    lines.add("")
                }
            }
        } else {
            lines.add(title)
            lines.add("")
            for (chapter in chapters) {
                lines.add(chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                lines.add("")
                chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { goal ->
                    lines.add("本章目标：$goal")
                    lines.add("")
                }
                chapter["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { content ->
                    lines.add(content.trim())
                    lines.add("")
                }
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun requireSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val read = runCatching { storage.getDialogueSession(runId, sessionId) }
        val session = read.getOrNull()
        if (session == null) {
            // 会话读不出来（文件损坏 / 半写）以前只表现为 404，诊断里查不到原因。
            DiagnosticsSink.recordFailure(
                stage = "session.read",
                error = read.exceptionOrNull(),
                retrying = false,
                details = mapOf("run_id" to runId, "session_id" to sessionId),
            )
            throw NoSuchElementException("Session not found: $sessionId")
        }
        return session
    }

    private fun transcriptOf(session: JsonObject): List<JsonObject> =
        session["transcript"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    private fun transcriptText(session: JsonObject): String =
        transcriptOf(session).joinToString("\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }

    private fun meaningfulTurnCount(transcript: List<JsonObject>): Int {
        val meaningful = transcript.filter { item ->
            item["message"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                (item["role"]?.jsonPrimitive?.contentOrNull ?: "").let { it !in setOf("scene", "director", "loading") }
        }
        val turnIds = meaningful.mapNotNull { it["turn_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }.distinct()
        return if (turnIds.isNotEmpty()) turnIds.size else meaningful.size
    }

    private fun normalize(chapters: List<JsonObject>): List<JsonObject> {
        val normalized = mutableListOf<JsonObject>()
        chapters.forEachIndexed { index, raw ->
            if (raw["chapter_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return@forEachIndexed
            normalized.add(
                buildJsonObject {
                    raw.forEach { (key, value) -> put(key, value) }
                    put("order", index + 1)
                    put("title", raw["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank) ?: "第 ${index + 1} 章")
                    put("goal", raw["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("participants", raw["participants"]?.jsonArray ?: JsonArray(emptyList()))
                    put("content", raw["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("source_session_id", raw["source_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("context_summary", raw["context_summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("last_session_id", raw["last_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("synced_transcript_count", (raw["synced_transcript_count"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
                },
            )
        }
        return normalized
    }

    private fun writeAll(runId: String, chapters: List<JsonObject>) {
        // 删除不再存在的章节文件，写入/更新现有文件
        val existingIds = storage.listChapterIds(runId).toSet()
        val newIds = chapters.mapNotNull { it["chapter_id"]?.jsonPrimitive?.contentOrNull }.toSet()
        existingIds.filterNot { it in newIds }.forEach { orphan ->
            storage.getChapterFile(runId, orphan).takeIf { storage.exists(it) }?.let { storage.deleteFile(it) }
        }
        for (chapter in chapters) {
            val chapterId = chapter["chapter_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val file = storage.getChapterFile(runId, chapterId)
            storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), chapter))
        }
    }

    private fun searchPreview(text: String, needle: String): String {
        val normalized = text.trim()
        val index = normalized.lowercase().indexOf(needle)
        if (index < 0) return normalized.take(180)
        val start = (index - 56).coerceAtLeast(0)
        val end = (index + needle.length + 100).coerceAtMost(normalized.length)
        return (if (start > 0) "…" else "") + normalized.substring(start, end).replace("\n", " ") + (if (end < normalized.length) "…" else "")
    }

    private fun trimChapterContent(content: String, limit: Int): String {
        val text = content.trim()
        if (text.length <= limit) return text
        val candidate = text.take((limit - 1).coerceAtLeast(1))
        val boundary = maxOf(
            candidate.lastIndexOf("。"),
            candidate.lastIndexOf("！"),
            candidate.lastIndexOf("？"),
            candidate.lastIndexOf("”"),
            candidate.lastIndexOf("\n"),
        )
        val trimmed = if (boundary > limit * 0.6) candidate.take(boundary + 1) else candidate
        return trimmed.trimEnd() + "…"
    }
}
