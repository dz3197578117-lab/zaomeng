package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 原文分章索引：把书卷原文（novel.txt）按常见中文章节标题切分，供「从原文某一章开始剧情」使用。
 *
 * 设计要点
 * - 纯本地计算，不调用模型：切分 → 落盘 novel_chapters.json（只存 start_char/end_char 与预览，
 *   不复制正文），因此索引文件很小，正文永远以 novel.txt 为准。
 * - 缓存按源文件签名（大小 + 修改时间）失效，重复读取不会重新切分。
 * - 原文没有章节标题时回退为定长分块（约 [FALLBACK_CHUNK_CHARS] 字，按标点对齐），
 *   保证「选起始位置」能力在任何文本上都可用；此时 `detected=false`。
 */
class NovelChapterIndexService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** 章节切片元信息（不含正文）。 */
    data class ChapterMeta(
        val index: Int,
        val title: String,
        val startChar: Int,
        val endChar: Int,
    )

    /** 供会话开场注入的章节原文片段。 */
    data class ChapterExcerpt(
        val index: Int,
        val title: String,
        val text: String,
    )

    fun list(runManifest: JsonObject, force: Boolean = false): JsonObject {
        val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(runId.isNotBlank()) { "Run id is required" }
        return lockFor(runId).withLock {
            val source = resolveSource(runManifest)
                ?: throw NoSuchElementException("这本书卷没有可用的原文，无法分章。")
            val signature = sourceSignature(source)
            val cached = readCache(runId)
            if (!force && cached != null && matches(cached, source, signature)) return@withLock cached

            val text = normalize(storage.readTextOrNull(source).orEmpty())
            require(text.isNotBlank()) { "这本书卷的原文是空的，无法分章。" }
            val split = splitChapters(text)
            val kept = split.chapters.take(MAX_CHAPTERS).map { chapter ->
                buildJsonObject {
                    put("index", chapter.index)
                    put("title", chapter.title)
                    put("start_char", chapter.startChar)
                    put("end_char", chapter.endChar)
                    put("char_count", chapter.endChar - chapter.startChar)
                    put("preview", preview(text, chapter.startChar, chapter.endChar))
                }
            }
            val payload = buildJsonObject {
                put("version", 1)
                put("run_id", runId)
                put("source", buildJsonObject {
                    put("name", source.name)
                    put("path", source.toString())
                    put("byte_size", signature.byteSize)
                    put("modified_at", signature.modifiedAt)
                    put("char_count", text.length)
                })
                put("detected", split.detected)
                put("chapters", buildJsonArray { kept.forEach(::add) })
                put("chapter_count", kept.size)
                put("truncated", split.chapters.size > kept.size)
                put("updated_at", nowIsoString())
            }
            writeCache(runId, payload)
            payload
        }
    }

    /** 单章正文（供前端预览章节开头，默认最多 [MAX_DETAIL_CHARS] 字）。 */
    fun chapterDetail(runManifest: JsonObject, index: Int, maxChars: Int = MAX_DETAIL_CHARS): JsonObject? {
        val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (index <= 0) return null
        val payload = list(runManifest)
        val meta = chapterMeta(payload, index) ?: return null
        val source = resolveSource(runManifest) ?: return null
        val text = normalize(storage.readTextOrNull(source).orEmpty())
        if (meta.startChar >= text.length) return null
        val limit = maxChars.coerceIn(1, MAX_DETAIL_CHARS)
        val sliceEnd = minOf(meta.endChar, text.length, meta.startChar + limit)
        return buildJsonObject {
            put("run_id", runId)
            put("index", meta.index)
            put("title", meta.title)
            put("char_count", meta.endChar - meta.startChar)
            put("content", text.substring(meta.startChar, sliceEnd).trim())
            put("truncated", sliceEnd < meta.endChar)
        }
    }

    /**
     * 取出某一章开头的原文片段，用于会话开场注入。
     * [index] 为 0 或不存在时返回 null（表示「从开头开始」或索引失配）。
     */
    fun excerpt(
        runManifest: JsonObject,
        index: Int,
        maxChars: Int = DEFAULT_EXCERPT_CHARS,
    ): ChapterExcerpt? {
        if (index <= 0) return null
        val payload = runCatching { list(runManifest) }.getOrNull() ?: return null
        val meta = chapterMeta(payload, index) ?: return null
        val source = resolveSource(runManifest) ?: return null
        val text = normalize(storage.readTextOrNull(source).orEmpty())
        if (meta.startChar >= text.length) return null
        val limit = maxChars.coerceIn(1, MAX_DETAIL_CHARS)
        val sliceEnd = minOf(meta.endChar, text.length, meta.startChar + limit)
        val slice = text.substring(meta.startChar, sliceEnd).trim()
        if (slice.isBlank()) return null
        return ChapterExcerpt(index = meta.index, title = meta.title, text = slice)
    }

    /** 供内部（如开场注入）直接按 runId 取片段。 */
    fun excerptForRun(runId: String, index: Int, maxChars: Int = DEFAULT_EXCERPT_CHARS): ChapterExcerpt? {
        val manifest = storage.readRunManifest(runId) ?: return null
        return excerpt(manifest, index, maxChars)
    }

    private fun chapterMeta(payload: JsonObject, index: Int): ChapterMeta? =
        payload["chapters"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.firstOrNull { it["index"]?.jsonPrimitive?.intOrNull == index }
            ?.let { entry ->
                val start = entry["start_char"]?.jsonPrimitive?.intOrNull ?: return null
                val end = entry["end_char"]?.jsonPrimitive?.intOrNull ?: return null
                if (end <= start) return null
                ChapterMeta(
                    index = index,
                    title = entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startChar = start,
                    endChar = end,
                )
            }

    private fun readCache(runId: String): JsonObject? {
        val file = cacheFile(runId)
        if (!storage.isFile(file)) return null
        return runCatching { json.parseToJsonElement(storage.readText(file)).jsonObject }.getOrNull()
    }

    private fun writeCache(runId: String, payload: JsonObject) {
        storage.writeTextAtomically(cacheFile(runId), json.encodeToString(JsonObject.serializer(), payload))
    }

    private fun cacheFile(runId: String): Path = storage.getRunDirectory(runId) / "novel_chapters.json"

    private fun matches(cached: JsonObject, source: Path, signature: SourceSignature): Boolean {
        val cachedSource = cached["source"]?.jsonObject ?: return false
        if (cached["chapters"]?.jsonArray?.isEmpty() != false) return false
        return cachedSource["path"]?.jsonPrimitive?.contentOrNull == source.toString() &&
            cachedSource["byte_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == signature.byteSize &&
            cachedSource["modified_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == signature.modifiedAt
    }

    private fun resolveSource(manifest: JsonObject): Path? {
        val runId = manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val runDir = storage.getRunDirectory(runId).normalized()
        val candidates = buildList {
            manifest["novel_sources"]?.jsonArray?.lastOrNull()?.jsonObject
                ?.get("source_path")?.jsonPrimitive?.contentOrNull?.let { add(it.toPath()) }
            manifest["novel_path"]?.jsonPrimitive?.contentOrNull?.let { add(it.toPath()) }
            add(runDir / "novel.txt")
        }
        return candidates.map { it.normalized() }.firstOrNull { candidate ->
            storage.isFile(candidate) && runCatching { candidate.relativeTo(runDir); true }.getOrDefault(false)
        }
    }

    private fun sourceSignature(source: Path): SourceSignature =
        SourceSignature(storage.fileSize(source), storage.lastModifiedMillis(source))

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private data class RawChapter(val index: Int, val title: String, val startChar: Int, val endChar: Int)

    private data class SplitResult(val chapters: List<RawChapter>, val detected: Boolean)

    private fun splitChapters(text: String): SplitResult {
        val headings = findHeadings(text)
        if (headings.size >= 2) {
            val chapters = mutableListOf<RawChapter>()
            val leadingEnd = headings.first().second
            if (leadingEnd >= LEADING_MIN_CHARS) {
                // 第一章标题之前还有成段正文（楔子/序章），单独成章，避免被丢弃。
                chapters += RawChapter(index = 1, title = LEADING_TITLE, startChar = 0, endChar = leadingEnd)
            }
            headings.forEachIndexed { position, (title, start) ->
                val end = headings.getOrNull(position + 1)?.second ?: text.length
                chapters += RawChapter(
                    index = chapters.size + 1,
                    title = title.ifBlank { "第 ${chapters.size + 1} 章" },
                    startChar = start,
                    endChar = end,
                )
            }
            val usable = chapters.filter { it.endChar - it.startChar >= MIN_CHAPTER_CHARS }
            if (usable.size >= 2) {
                return SplitResult(
                    chapters = usable.mapIndexed { position, chapter -> chapter.copy(index = position + 1) },
                    detected = true,
                )
            }
        }
        return SplitResult(chapters = fallbackChunks(text), detected = false)
    }

    /**
     * 找出所有章节标题行。
     *
     * 采用「贪心间隔」策略过滤目录页：只有距离上一个保留标题 ≥ [MIN_CHAPTER_CHARS] 字才算新章，
     * 这样目录页里连续十几行的「第 X 章」只会命中第一行，随后被正文段落推开，不会污染章节结构。
     */
    private fun findHeadings(text: String): List<Pair<String, Int>> {
        val kept = mutableListOf<Pair<String, Int>>()
        var lastKeptStart = Int.MIN_VALUE
        var offset = 0
        for (rawLine in text.split('\n')) {
            val lineStart = offset
            offset += rawLine.length + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.length > HEADING_MAX_LENGTH) continue
            if (!HEADING_START.containsMatchIn(line)) continue
            if (line.count { it in SENTENCE_ENDINGS } > 1) continue
            if (lastKeptStart != Int.MIN_VALUE && lineStart - lastKeptStart < MIN_CHAPTER_CHARS) continue
            kept += line to lineStart
            lastKeptStart = lineStart
        }
        return kept
    }

    private fun fallbackChunks(text: String): List<RawChapter> {
        val chunks = mutableListOf<RawChapter>()
        var start = 0
        while (start < text.length && chunks.size < MAX_CHAPTERS) {
            val target = (start + FALLBACK_CHUNK_CHARS).coerceAtMost(text.length)
            val end = if (target >= text.length) text.length else alignToBoundary(text, start, target)
            val safeEnd = end.coerceAtLeast((start + 1).coerceAtMost(text.length))
            chunks += RawChapter(
                index = chunks.size + 1,
                title = "第 ${chunks.size + 1} 段",
                startChar = start,
                endChar = safeEnd,
            )
            start = safeEnd
        }
        return chunks
    }

    /** 在目标位置附近向前寻找段落/句读边界，避免把一句话从中间截断。 */
    private fun alignToBoundary(text: String, start: Int, target: Int): Int {
        val lower = maxOf(start + FALLBACK_CHUNK_CHARS / 2, target - BOUNDARY_SEARCH)
        val newline = text.lastIndexOf('\n', target)
        if (newline in (lower + 1) until target) return newline + 1
        for (probe in target downTo (lower + 1)) {
            if (text[probe - 1] in SENTENCE_BOUNDARIES) return probe
        }
        return target
    }

    private fun preview(text: String, start: Int, end: Int): String {
        val slice = text.substring(start, end.coerceAtMost(text.length))
            .replace(WHITESPACE, " ")
            .trim()
        return if (slice.length <= PREVIEW_CHARS) slice else slice.take(PREVIEW_CHARS) + "…"
    }

    private fun lockFor(runId: String): SimpleLock = locksLock.withLock {
        locks.getOrPut(runId) { SimpleLock() }
    }

    private data class SourceSignature(val byteSize: Long, val modifiedAt: Long)

    companion object {
        private val locks = HashMap<String, SimpleLock>()
        private val locksLock = SimpleLock()

        private const val MAX_CHAPTERS = 1_500
        private const val PREVIEW_CHARS = 80
        private const val DEFAULT_EXCERPT_CHARS = 1_200
        private const val MAX_DETAIL_CHARS = 8_000
        private const val MIN_CHAPTER_CHARS = 300
        private const val HEADING_MAX_LENGTH = 60
        private const val LEADING_MIN_CHARS = 200
        private const val LEADING_TITLE = "开篇"
        private const val FALLBACK_CHUNK_CHARS = 3_000
        private const val BOUNDARY_SEARCH = 400

        private val CHAPTER_NUMERAL = "0-9０-９零〇一二三四五六七八九十百千两"

        /** 章节标题行：第X章/回/节/卷/篇/集/幕、卷X、Chapter N，允许「正文」前缀。 */
        private val HEADING_START = Regex(
            "^(?:正文[\\s:：.、]*)?(?:" +
                "第\\s*[$CHAPTER_NUMERAL]{1,8}\\s*[章回节卷篇集幕]" +
                "|卷\\s*[$CHAPTER_NUMERAL]{1,8}" +
                "|Chapter\\s*\\d{1,4}" +
                ")",
            RegexOption.IGNORE_CASE,
        )

        private val WHITESPACE = Regex("[\\s\\u3000]+")
        private val SENTENCE_ENDINGS = charArrayOf('。', '！', '？')
        private val SENTENCE_BOUNDARIES = charArrayOf('。', '！', '？', '；', '!', '?', ';')
    }
}
