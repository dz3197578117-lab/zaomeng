package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.ConsistencyReportDto
import top.wkbin.zaomeng.data.api.DraftVersionDto
import top.wkbin.zaomeng.data.api.FinalReviewDto
import top.wkbin.zaomeng.data.api.NovelPlanDto
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.RewriteOptionDto
import top.wkbin.zaomeng.data.api.SceneCardDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.StoryCoreDto

/**
 * 创作工具的结构化输出解析。
 *
 * 模型经常把 JSON 包进 ```json 代码块，或在前後加一句“以下是设定”。
 * 这里先做括号配对切出第一段完整 JSON，再交给 kotlinx.serialization；
 * 字段缺失有默认值，不会因为少一个 key 就整份丢弃。
 */
internal object WritingToolParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseStoryBible(raw: String): StoryBibleDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的设定，请重试。")
        return json.decodeFromJsonElement(element)
    }

    fun parseRescue(raw: String): List<RescueOptionDto> {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的方案，请重试。")
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["options"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement<RescueOptionDto>(item) }.getOrNull()
        }.filter { it.title.isNotBlank() || it.idea.isNotBlank() || it.snippet.isNotBlank() }
    }

    fun parseReview(raw: String): ChapterReviewDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的检查结果，请重试。")
        return json.decodeFromJsonElement(element)
    }

    fun parseStoryCore(raw: String): StoryCoreDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的故事核心，请重试。")
        return json.decodeFromJsonElement(element)
    }

    fun parseSceneCards(raw: String): List<SceneCardDto> {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的场景卡，请重试。")
        return decodeItems<SceneCardDto>(element, "items")
            .filter { it.title.isNotBlank() || it.goal.isNotBlank() }
    }

    fun parseDraftVersions(raw: String): List<DraftVersionDto> {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的初稿，请重试。")
        return decodeItems<DraftVersionDto>(element, "items")
            .filter { it.content.isNotBlank() }
    }

    fun parseRewriteOptions(raw: String): List<RewriteOptionDto> {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的改写，请重试。")
        return decodeItems<RewriteOptionDto>(element, "items")
            .filter { it.content.isNotBlank() }
    }

    fun parseConsistency(raw: String): ConsistencyReportDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的一致性报告，请重试。")
        return json.decodeFromJsonElement(element)
    }

    fun parseNovelPlan(raw: String): NovelPlanDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的方案，请重试。")
        return json.decodeFromJsonElement(element)
    }

    fun parseFinalReview(raw: String): FinalReviewDto {
        val element = extractJson(raw) ?: throw IllegalStateException("模型没有返回可解析的终审结果，请重试。")
        return json.decodeFromJsonElement(element)
    }

    /** 兼容 `{items:[...]}` 与裸数组两种输出。 */
    private inline fun <reified T> decodeItems(element: JsonElement, key: String): List<T> {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element[key]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return runCatching { json.decodeFromJsonElement<List<T>>(array) }.getOrDefault(emptyList())
    }

    /** 取最先出现的 JSON 对象或数组，忽略代码块与前后说明。 */
    internal fun extractJson(raw: String): JsonElement? {
        val text = raw.trim()
        val objectStart = text.indexOf('{')
        val arrayStart = text.indexOf('[')
        return when {
            objectStart < 0 -> if (arrayStart < 0) null else extractAt(text, '[')
            arrayStart < 0 -> extractAt(text, '{')
            arrayStart < objectStart -> extractAt(text, '[')
            else -> extractAt(text, '{')
        }
    }

    private fun extractAt(text: String, open: Char): JsonElement? {
        val start = text.indexOf(open)
        if (start < 0) return null
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        val slice = text.substring(start, index + 1)
                        return runCatching { json.parseToJsonElement(slice) }.getOrNull()
                    }
                }
            }
        }
        return null
    }
}
