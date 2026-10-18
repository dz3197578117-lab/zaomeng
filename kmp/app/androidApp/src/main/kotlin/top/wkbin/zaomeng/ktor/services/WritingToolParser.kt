package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.StoryBibleDto

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
