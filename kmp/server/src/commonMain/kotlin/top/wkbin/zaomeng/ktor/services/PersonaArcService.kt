package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 逐章人设弧线（persona arc）。
 *
 * **为什么需要它**：人物档案 [PROFILE.md] 是全书蒸馏出来的**单一最终形态**，
 * 无论对话从第几章开始，模型看到的都是「全书之后」的身份与认知。
 * 于是从第一章开局时，角色会提前带着后期才形成的立场、关系与认知（剧透）。
 *
 * 弧线把「会随时间改变」的状态按章存下来，供对话按时间点取用；
 * 说话风格、外貌、习惯等**稳定特征不进弧线**，仍取 `PROFILE.md`。
 *
 * **产物**：`artifacts/characters/<novelId>/<角色>/arc.json`
 *
 * **逐章幂等**：每完成一章就原子落盘，续跑时已存在的章直接跳过，
 * 因此可中断、可续跑、可单章重试；全部失败也只留空弧线，不影响主档案可用。
 * 旧书卷没有 `arc.json` 时，读侧一律退化为既有行为（零迁移、零报错）。
 */
class PersonaArcService(
    private val storage: StorageService,
    private val llm: LlmClient?,
    private val chapterIndex: NovelChapterIndexService,
) {
    companion object {
        private const val TAG = "PersonaArcService"

        const val ARC_VERSION = 1

        /**
         * 单章送入模型的正文上限。
         * 超长章按上限截断并在弧线条目里标注 `source_truncated`，
         * 避免模型对着半章正文编出「整章」的状态。
         */
        const val MAX_CHAPTER_CHARS = 6_000

        private const val ARC_MAX_TOKENS = 900

        /** 弧线只保留**会随时间改变**的字段；稳定特征留在 PROFILE.md。 */
        val MUTABLE_FIELDS = listOf(
            "scene_identity",
            "age_stage",
            "goal",
            "belief_anchor",
            "stress_response",
            "key_bonds",
            "preference_like",
            "dislike_hate",
            "known_facts",
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    }

    data class ArcSummary(
        val charactersProcessed: Int,
        val chaptersPerCharacter: Int,
        val generated: Int,
        val skipped: Int,
        val failed: Int,
    ) {
        val hasArc: Boolean get() = generated > 0 || skipped > 0
    }

    fun arcFile(runId: String, novelId: String, character: String): Path =
        personaArcFile(storage, runId, novelId, character)

    /** 读取弧线；不存在或损坏都返回 null（调用方退化为既有行为）。 */
    fun readArc(runId: String, novelId: String, character: String): JsonObject? =
        readPersonaArc(storage, runId, novelId, character)

    /**
     * 为一批角色逐章生成弧线。
     *
     * @param retryChapters 指定章号强制重做（单章重试）；为空时只补缺失的章。
     */
    suspend fun generate(
        runId: String,
        runManifest: JsonObject,
        novelId: String,
        characters: List<String>,
        guidance: Map<String, String>,
        retryChapters: Set<Int> = emptySet(),
        onProgress: (stage: String, details: Map<String, Any?>) -> Unit = { _, _ -> },
    ): ArcSummary {
        val client = llm ?: return ArcSummary(0, 0, 0, 0, 0)
        if (characters.isEmpty()) return ArcSummary(0, 0, 0, 0, 0)

        // 章节索引不可用（没有可读原文等）时静默跳过：蒸馏主流程不能因此失败。
        val chapters = chapterList(runManifest)
        if (chapters.isEmpty()) return ArcSummary(0, 0, 0, 0, 0)

        var generated = 0
        var skipped = 0
        var failed = 0
        var processedCharacters = 0

        for (character in characters) {
            val existing = readArc(runId, novelId, character)
            val done = existingChapterIndices(existing)
            val pending = chapters.filter { (index, _) -> index !in done || index in retryChapters }
            if (pending.isEmpty()) {
                skipped += chapters.size
                continue
            }
            processedCharacters++
            var arc = existing ?: newArc(runId, novelId, character)
            if (retryChapters.isNotEmpty()) arc = removeArcChapters(arc, retryChapters)

            for ((chapterNo, title) in pending) {
                onProgress(
                    "persona_arc",
                    mapOf(
                        "character" to character,
                        "chapter_index" to chapterNo,
                        "chapter_total" to chapters.size,
                    ),
                )
                val excerpt = runCatching {
                    chapterIndex.excerpt(runManifest, chapterNo, MAX_CHAPTER_CHARS)
                }.getOrNull()
                if (excerpt == null || excerpt.text.isBlank()) {
                    // 记一条 empty 占位，续跑时不再反复重试；单章重试可显式指定。
                    arc = mergeArcEntry(
                        arc,
                        arcEntry(chapterNo, title, state = null, status = "empty", sourceChars = 0, truncated = false),
                    )
                    writeArc(runId, novelId, character, arc)
                    skipped++
                    continue
                }
                val truncated = excerpt.text.length >= MAX_CHAPTER_CHARS
                val state = runCatching {
                    extractState(client, guidance, character, chapterNo, title, excerpt.text)
                }.getOrNull()
                if (state == null) {
                    failed++
                    arc = mergeArcEntry(
                        arc,
                        arcEntry(chapterNo, title, state = null, status = "failed", sourceChars = excerpt.text.length, truncated = truncated),
                    )
                } else {
                    generated++
                    arc = mergeArcEntry(
                        arc,
                        arcEntry(chapterNo, title, state = state, status = "ok", sourceChars = excerpt.text.length, truncated = truncated),
                    )
                }
                // 每章落盘一次，保证中断后已完成的章不丢。
                writeArc(runId, novelId, character, arc)
            }

            PlatformLog.i(
                TAG,
                "Persona arc for $character: ${existingChapterIndices(arc).size}/${chapters.size} chapters",
            )
        }

        return ArcSummary(
            charactersProcessed = processedCharacters,
            chaptersPerCharacter = chapters.size,
            generated = generated,
            skipped = skipped,
            failed = failed,
        )
    }

    private suspend fun extractState(
        client: LlmClient,
        guidance: Map<String, String>,
        character: String,
        chapterNo: Int,
        title: String,
        text: String,
    ): Map<String, Any?>? {
        val messages = DistillPromptBuilder.buildPersonaArcMessages(
            guidance = guidance,
            character = character,
            chapterIndex = chapterNo,
            chapterTitle = title,
            chapterText = text,
        )
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.2,
            maxTokens = ARC_MAX_TOKENS,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        return parseArcState(content)
    }

    private fun chapterList(runManifest: JsonObject): List<Pair<Int, String>> =
        runCatching { chapterIndex.list(runManifest) }
            .getOrNull()
            ?.get("chapters")
            ?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.mapNotNull { entry ->
                val index = entry["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (index <= 0) return@mapNotNull null
                index to entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            .orEmpty()

    private fun writeArc(runId: String, novelId: String, character: String, arc: JsonObject) {
        runCatching {
            storage.writeTextAtomically(
                arcFile(runId, novelId, character),
                json.encodeToString(JsonObject.serializer(), arc),
            )
        }.onFailure { PlatformLog.w(TAG, "Failed to write persona arc for $character: ${it.message}") }
    }
}

/** 新建一个空弧线骨架。 */
internal fun newArc(runId: String, novelId: String, character: String): JsonObject = buildJsonObject {    put("version", PersonaArcService.ARC_VERSION)
    put("run_id", runId)
    put("novel_id", novelId)
    put("character", character)
    put("generated_at", nowIsoString())
    put("chapters", buildJsonArray { })
    put("chapter_count", 0)
}

/** 弧线里已收录（无论成功与否）的章号，用于续跑跳过。 */
internal fun existingChapterIndices(arc: JsonObject?): Set<Int> =
    (arc?.get("chapters") as? JsonArray)
        ?.mapNotNull { raw ->
            runCatching { raw.jsonObject["chapter"]?.jsonPrimitive?.intOrNull }.getOrNull()
        }
        ?.toSet()
        .orEmpty()

/** 去掉指定章号的条目（单章重试前先清旧的）。 */
internal fun removeArcChapters(arc: JsonObject, chapters: Set<Int>): JsonObject {
    if (chapters.isEmpty()) return arc
    val kept = (arc["chapters"] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        ?.filter { entry -> entry["chapter"]?.jsonPrimitive?.intOrNull !in chapters }
        .orEmpty()
    return arcWithChapters(arc, kept)
}

/** 插入/替换一章的弧线条目，按章号升序排列。 */
internal fun mergeArcEntry(arc: JsonObject, entry: JsonObject): JsonObject {
    val chapterNo = entry["chapter"]?.jsonPrimitive?.intOrNull
        ?: return arc
    val kept = (arc["chapters"] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        ?.filter { it["chapter"]?.jsonPrimitive?.intOrNull != chapterNo }
        .orEmpty()
    return arcWithChapters(arc, kept + entry)
}

private fun arcWithChapters(arc: JsonObject, chapters: List<JsonObject>): JsonObject {
    val sorted = chapters.sortedBy { it["chapter"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
    return buildJsonObject {
        arc.forEach { (key, value) -> if (key != "chapters" && key != "chapter_count") put(key, value) }
        put("chapters", buildJsonArray { sorted.forEach(::add) })
        put("chapter_count", sorted.size)
    }
}

/** 构造一条弧线条目；[state] 为 null 表示该章没有可用状态（empty / failed）。 */
internal fun arcEntry(
    chapterNo: Int,
    title: String,
    state: Map<String, Any?>?,
    status: String,
    sourceChars: Int,
    truncated: Boolean,
): JsonObject = buildJsonObject {
    put("chapter", chapterNo)
    put("title", title)
    put("status", status)
    put("source_chars", sourceChars)
    put("source_truncated", truncated)
    put("state", if (state == null) JsonNull else stateToJson(state))
}

private fun stateToJson(state: Map<String, Any?>): JsonObject = buildJsonObject {
    state.forEach { (key, value) ->
        when (value) {
            null -> put(key, JsonNull)
            is String -> put(key, value)
            is List<*> -> put(
                key,
                buildJsonArray {
                    value.forEach { item ->
                        when (item) {
                            is String -> add(JsonPrimitive(item))
                            is Map<*, *> -> add(
                                buildJsonObject {
                                    item.forEach { (k, v) -> put(k.toString(), v?.toString() ?: "") }
                                },
                            )
                            else -> Unit
                        }
                    }
                },
            )
            else -> put(key, value.toString())
        }
    }
}

/**
 * 解析模型返回的弧线状态。
 *
 * 只接受 [PersonaArcService.MUTABLE_FIELDS] 里声明的字段（白名单），
 * 空值直接丢弃；容忍 Markdown 围栏与前后缀文字。完全无法解析时返回 null。
 */
internal fun parseArcState(content: String): Map<String, Any?>? {
    val cleaned = content
        .removePrefix("\uFEFF")
        .trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = runCatching {
        Json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
    }.getOrNull() ?: return null

    val state = linkedMapOf<String, Any?>()
    for (field in PersonaArcService.MUTABLE_FIELDS) {
        val value = obj[field] ?: continue
        when (field) {
            "key_bonds" -> {
                val bonds = (value as? JsonArray)?.mapNotNull { raw ->
                    val item = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    mapOf("name" to name, "stage" to item["stage"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
                }.orEmpty()
                if (bonds.isNotEmpty()) state[field] = bonds
            }
            "preference_like", "dislike_hate", "known_facts" -> {
                val items = (value as? JsonArray)?.mapNotNull { raw ->
                    runCatching { raw.jsonPrimitive.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (items.isNotEmpty()) state[field] = items
            }
            else -> {
                val text = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) state[field] = text
            }
        }
    }
    return state.ifEmpty { null }
}

// ---------------------------------------------------------------------------
// 读取侧：对话按时间点注入时使用（不依赖 PersonaArcService 实例，避免为了读一个
// 文件就要构造带 llm 的服务）。
// ---------------------------------------------------------------------------

/** 弧线文件的落盘路径（与 PROFILE.md 同目录）。 */
internal fun personaArcFile(
    storage: StorageService,
    runId: String,
    novelId: String,
    character: String,
): Path = storage.getRunDirectory(runId) /
    "artifacts/characters/${PathSafety.sanitizePathComponent(novelId, "novelId")}" /
    PathSafety.sanitizePathComponent(character, "character") /
    "arc.json"

/** 读取弧线；不存在或损坏返回 null，调用方退化为既有行为。 */
internal fun readPersonaArc(
    storage: StorageService,
    runId: String,
    novelId: String,
    character: String,
): JsonObject? {
    val file = personaArcFile(storage, runId, novelId, character)
    if (!storage.isFile(file)) return null
    return runCatching { Json.parseToJsonElement(storage.readText(file)).jsonObject }.getOrNull()
}

/**
 * 每经过多少轮对话，时间点往后推进一章。
 *
 * 会话里没有「当前第几章」的显式信号，因此推进只能**推导**：
 * 见 [derivePersonaChapter]。改成推导而非累加，是为了让幂等成为结构性保证——
 * 同一 `turn_count` 永远算出同一个值，重复提交（同一 turnId）不会多推进一次。
 */
internal const val TURNS_PER_PERSONA_CHAPTER = 4

/**
 * 推导会话当前的时间点（章）。
 *
 * `start_chapter` 为起点，每 [TURNS_PER_PERSONA_CHAPTER] 轮推进一章。
 * 不在这里夹到弧线长度：读取侧 [arcStateForChapter] 超出末尾时会取最后一段，
 * 所以就算推导值超出弧线也不会出错。
 *
 * 纯推导（不读旧值、不累加），因此对同一份 manifest 反复调用结果一致。
 */
internal fun derivePersonaChapter(session: JsonObject?): Int {
    val start = session?.get("start_chapter")?.jsonPrimitive?.intOrNull ?: 0
    val base = if (start > 0) start else 1
    val turnCount = session?.get("turn_count")?.jsonPrimitive?.intOrNull ?: 0
    if (turnCount <= 0) return base
    return base + turnCount / TURNS_PER_PERSONA_CHAPTER
}

/**
 * 解析会话的「当前时间点（章）」。
 *
 * 优先用随剧情推进写回的 `persona_chapter`（B-3），否则回退到会话起始章
 * `start_chapter`，都没有时按第 1 章处理——**宁可从早期状态开始，也不要剧透**。
 */
internal fun resolvePersonaChapter(session: JsonObject?): Int {
    val explicit = session?.get("persona_chapter")?.jsonPrimitive?.intOrNull ?: 0
    if (explicit > 0) return explicit
    val start = session?.get("start_chapter")?.jsonPrimitive?.intOrNull ?: 0
    if (start > 0) return start
    return 1
}

/**
 * 取覆盖 [chapter] 的弧线段：优先该章，其次最近一条更早的章，再都没有则最早的一条。
 * 只认 `status == "ok"` 的条目（empty / failed 没有状态可用）。没有弧线返回 null。
 */
internal fun arcStateForChapter(arc: JsonObject?, chapter: Int): JsonObject? {
    val entries = (arc?.get("chapters") as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        ?.filter { it["status"]?.jsonPrimitive?.contentOrNull == "ok" }
        ?.sortedBy { it["chapter"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
        .orEmpty()
    if (entries.isEmpty()) return null
    return entries.lastOrNull { (it["chapter"]?.jsonPrimitive?.intOrNull ?: 0) <= chapter }
        ?: entries.first()
}
