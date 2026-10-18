package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 分章大纲里的一章。 */
data class NovelOutlineChapter(
    val title: String,
    val goal: String,
)

/**
 * 分章大纲解析器。
 *
 * ## 为什么单独抽出来
 *
 * 模型输出是**不可信文本**：它可能在 JSON 外面包 ```json 代码块、加一段「好的，以下是大纲」、
 * 把数组包进 `{"chapters": [...]}`，或者在 JSON 被截断时退化成编号列表。
 * 这些情况在真机上都会发生，不能靠「提示词写清楚」来保证。
 *
 * 因此解析策略是三层降级，且**每一层都是纯函数**，可以脱离 Android 单测：
 * 1. 从文本中抠出 JSON 数组（容忍代码块与前后缀）；
 * 2. 若是对象，取 `chapters` / `items` 字段；
 * 3. 都失败时，按「1. 标题：目标」的编号列表解析。
 *
 * 只有三层都拿不到任何一条有效章节时才抛错——错误信息会直接展示给用户。
 */
object NovelOutlineParser {

    private const val MAX_TITLE_CHARS = 60
    private const val MAX_GOAL_CHARS = 400

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(raw: String, limit: Int = 12): List<NovelOutlineChapter> {
        val text = raw.trim()
        require(text.isNotEmpty()) { "模型没有返回大纲内容。" }
        val max = limit.coerceIn(1, 30)

        val structured = runCatching { parseJson(text) }.getOrDefault(emptyList())
        val parsed = structured.ifEmpty { parseNumbered(text) }

        val cleaned = parsed
            .map { chapter ->
                NovelOutlineChapter(
                    title = chapter.title.trim().trim('"', '\'', '《', '》', '#', '-', ' ').take(MAX_TITLE_CHARS),
                    goal = chapter.goal.trim().take(MAX_GOAL_CHARS),
                )
            }
            .filter { it.title.isNotBlank() || it.goal.isNotBlank() }
            .take(max)
            .mapIndexed { index, chapter ->
                if (chapter.title.isBlank()) chapter.copy(title = "第 ${index + 1} 章") else chapter
            }

        require(cleaned.isNotEmpty()) {
            "大纲解析失败：模型返回的内容不是可识别的分章结构。可以重试一次，或改用更具体的设定。"
        }
        return cleaned
    }

    /** 第一层：从任意文本里抠出 JSON。 */
    private fun parseJson(text: String): List<NovelOutlineChapter> {
        val stripped = text
            .lineSequence()
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n")
            .trim()

        val arrayStart = stripped.indexOf('[')
        val arrayEnd = stripped.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val candidate = stripped.substring(arrayStart, arrayEnd + 1)
            val array = runCatching { json.parseToJsonElement(candidate).jsonArray }.getOrNull()
            if (array != null) return array.mapNotNull(::fromJsonObject)
        }

        // 第二层：对象包裹（{"chapters": [...]} / {"items": [...]}）
        val objectStart = stripped.indexOf('{')
        val objectEnd = stripped.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            val candidate = stripped.substring(objectStart, objectEnd + 1)
            val obj = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
            if (obj != null) {
                for (key in listOf("chapters", "items", "outline", "data")) {
                    val array = (obj[key] as? JsonArray) ?: continue
                    val items = array.mapNotNull(::fromJsonObject)
                    if (items.isNotEmpty()) return items
                }
            }
        }
        return emptyList()
    }

    private fun fromJsonObject(element: kotlinx.serialization.json.JsonElement): NovelOutlineChapter? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val title = firstString(obj, "title", "name", "chapter_title", "chapter")
        val goal = firstString(obj, "goal", "summary", "description", "plot", "content")
        if (title.isBlank() && goal.isBlank()) return null
        return NovelOutlineChapter(title = title, goal = goal)
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** 第三层：编号列表（`1. 标题：目标` / `1、标题：目标`）。 */
    private fun parseNumbered(text: String): List<NovelOutlineChapter> {
        val numberPattern = Regex("""^\s*(?:\d+[.、)）]|第[\d一二三四五六七八九十百]+章)\s*(.+)$""")
        return text.lineSequence()
            .mapNotNull { line -> numberPattern.find(line)?.groupValues?.getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() }
            .map { rest ->
                val split = rest.split('：', ':', limit = 2)
                if (split.size == 2) {
                    NovelOutlineChapter(title = split[0].trim(), goal = split[1].trim())
                } else {
                    NovelOutlineChapter(title = rest, goal = "")
                }
            }
            .toList()
    }
}
