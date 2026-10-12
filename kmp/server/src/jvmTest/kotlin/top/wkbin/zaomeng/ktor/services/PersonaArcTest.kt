package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐章人设弧线（B-1）回归。
 *
 * 覆盖三件容易出错的事：
 * 1. 模型输出解析：只接受声明字段（白名单），容忍围栏与前后缀，无法解析时返回 null；
 * 2. 逐章幂等：同一章重复写入必须**替换**而不是追加，且按章号排序；
 * 3. 续跑语义：已收录的章要能算出「跳过」，单章重试要能只清掉指定章。
 */
class PersonaArcTest {

    private fun arcOf(vararg entries: JsonObject): JsonObject =
        newArc("run-1", "novel-1", "林七夜").let { base ->
            entries.fold(base) { acc, entry -> mergeArcEntry(acc, entry) }
        }

    private fun entry(chapter: Int, status: String = "ok") =
        arcEntry(
            chapterNo = chapter,
            title = "第${chapter}章",
            state = mapOf("goal" to "目标$chapter"),
            status = status,
            sourceChars = 100,
            truncated = false,
        )

    // ---------- 解析 ----------

    @Test
    fun `parse keeps only declared fields and drops empty values`() {
        val content = """
            {"scene_identity":"刚入队的见习守夜人","goal":"证明自己",
             "known_facts":["队里死了人"],
             "未声明字段":"不该保留",
             "belief_anchor":"","preference_like":[],
             "key_bonds":[{"name":"沈青竹","stage":"初识"},{"name":"","stage":"无效"}]}
        """.trimIndent()

        val state = parseArcState(content)

        assertEquals("刚入队的见习守夜人", state?.get("scene_identity"))
        assertEquals("证明自己", state?.get("goal"))
        assertEquals(listOf("队里死了人"), state?.get("known_facts"))
        assertFalse(state!!.containsKey("未声明字段"), "未声明字段必须丢弃：$state")
        assertFalse(state.containsKey("belief_anchor"), "空字符串必须丢弃：$state")
        assertFalse(state.containsKey("preference_like"), "空数组必须丢弃：$state")
        @Suppress("UNCHECKED_CAST")
        val bonds = state["key_bonds"] as List<Map<String, String>>
        assertEquals(1, bonds.size, "没有 name 的关系项必须丢弃：$bonds")
        assertEquals("沈青竹", bonds.first()["name"])
    }

    @Test
    fun `parse tolerates markdown fences and surrounding text`() {
        val content = """
            好的，这是结果：
            ```json
            {"scene_identity":"掌门","goal":"稳住宗门"}
            ```
            希望对你有帮助。
        """.trimIndent()

        val state = parseArcState(content)

        assertEquals("掌门", state?.get("scene_identity"))
        assertEquals("稳住宗门", state?.get("goal"))
    }

    @Test
    fun `parse returns null when nothing usable`() {
        assertNull(parseArcState(""))
        assertNull(parseArcState("模型什么都没说"))
        assertNull(parseArcState("{}"), "空对象应视为无状态")
        assertNull(parseArcState("""{"未声明字段":"x"}"""), "只有未声明字段也应视为无状态")
    }

    // ---------- 逐章幂等 ----------

    @Test
    fun `merging same chapter replaces instead of appending`() {
        val first = arcOf(entry(1), entry(2))
        val replaced = mergeArcEntry(
            first,
            arcEntry(2, "第2章", mapOf("goal" to "改过的目标"), "ok", 200, truncated = true),
        )

        assertEquals(2, replaced["chapter_count"]?.jsonPrimitive?.intOrNull)
        val chapters = replaced["chapters"]!!.jsonArray
        assertEquals(2, chapters.size, "同一章必须替换而不是追加：$chapters")
        val second = chapters.first { it.jsonObject["chapter"]?.jsonPrimitive?.intOrNull == 2 }
        assertEquals(true, second.jsonObject["source_truncated"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(200, second.jsonObject["source_chars"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `chapters stay sorted by chapter number`() {
        val arc = arcOf(entry(5), entry(1), entry(3))

        val order = arc["chapters"]!!.jsonArray.map { it.jsonObject["chapter"]?.jsonPrimitive?.intOrNull }
        assertEquals(listOf(1, 3, 5), order)
    }

    @Test
    fun `failed chapter is recorded so it is not retried forever`() {
        val arc = arcOf(
            arcEntry(1, "第1章", state = null, status = "failed", sourceChars = 10, truncated = false),
        )

        assertTrue(existingChapterIndices(arc).contains(1), "失败的章也要记账，否则每次续跑都重试")
        val status = arc["chapters"]!!.jsonArray.first().jsonObject["status"]?.jsonPrimitive?.content
        assertEquals("failed", status)
    }

    // ---------- 续跑 ----------

    @Test
    fun `existing indices drive resume skip`() {
        val arc = arcOf(entry(1), entry(2))

        assertEquals(setOf(1, 2), existingChapterIndices(arc))
        assertTrue(existingChapterIndices(null).isEmpty(), "没有弧线时应视为全部待做")
    }

    @Test
    fun `remove clears only the retried chapters`() {
        val arc = arcOf(entry(1), entry(2), entry(3))

        val after = removeArcChapters(arc, setOf(2))

        assertEquals(setOf(1, 3), existingChapterIndices(after))
        assertEquals(2, after["chapter_count"]?.jsonPrimitive?.intOrNull)
    }

    // ---------- 提示词构建 ----------

    @Test
    fun `arc prompt carries this chapter only and the output contract`() {
        val guidance = mapOf(
            "persona_arc_instruction" to "### CHAPTER\n第 {chapter_index} 章 {chapter_title}\n{chapter_text}",
            "persona_arc_output_format" to "ARC_OUTPUT_CONTRACT",
        )

        val messages = DistillPromptBuilder.buildPersonaArcMessages(
            guidance = guidance,
            character = "林七夜",
            chapterIndex = 7,
            chapterTitle = "第七章",
            chapterText = "本章正文标记",
        )

        assertEquals(2, messages.size)
        val system = messages.first { it.role == "system" }.content.orEmpty()
        val user = messages.first { it.role == "user" }.content.orEmpty()
        assertTrue(system.contains("ARC_OUTPUT_CONTRACT"), "输出契约应放 system：$system")
        assertTrue(system.contains("不得使用其他章节的信息"), "system 必须约束只看本章：$system")
        assertTrue(user.contains("第 7 章"), "user 应带章号：$user")
        assertTrue(user.contains("本章正文标记"), "user 应带本章正文：$user")
    }

    // ---------- 时间点解析（B-2） ----------

    @Test
    fun `persona chapter prefers progressed value then start chapter then 1`() {
        assertEquals(
            12,
            resolvePersonaChapter(buildJsonObject { put("persona_chapter", 12); put("start_chapter", 3) }),
            "推进后的 persona_chapter 优先",
        )
        assertEquals(
            3,
            resolvePersonaChapter(buildJsonObject { put("start_chapter", 3) }),
            "没有推进值时用会话起始章",
        )
        assertEquals(
            1,
            resolvePersonaChapter(buildJsonObject { put("start_chapter", 0) }),
            "起始章为 0（未指定）时按第 1 章 —— 宁可从早期状态开始，也不要剧透",
        )
        assertEquals(1, resolvePersonaChapter(null), "没有会话时按第 1 章")
    }

    @Test
    fun `arc state for chapter falls back to the nearest earlier chapter`() {
        val arc = arcOf(entry(1), entry(5), entry(9))

        assertEquals(1, arcStateForChapter(arc, 1)!!["chapter"]!!.jsonPrimitive.intOrNull)
        assertEquals(1, arcStateForChapter(arc, 3)!!["chapter"]!!.jsonPrimitive.intOrNull, "章号落在 1 和 5 之间应取更早的 1")
        assertEquals(5, arcStateForChapter(arc, 5)!!["chapter"]!!.jsonPrimitive.intOrNull)
        assertEquals(5, arcStateForChapter(arc, 8)!!["chapter"]!!.jsonPrimitive.intOrNull)
        assertEquals(9, arcStateForChapter(arc, 99)!!["chapter"]!!.jsonPrimitive.intOrNull, "超出末尾时取最后一段")
        assertEquals(
            1,
            arcStateForChapter(arc, 0)!!["chapter"]!!.jsonPrimitive.intOrNull,
            "章号早于所有条目时取最早一段",
        )
    }

    @Test
    fun `arc state ignores entries without usable state`() {
        val arc = arcOf(
            arcEntry(1, "第1章", state = null, status = "failed", sourceChars = 0, truncated = false),
            arcEntry(2, "第2章", state = null, status = "empty", sourceChars = 0, truncated = false),
        )

        assertNull(arcStateForChapter(arc, 2), "failed/empty 的条目没有状态可用，不能被选中")
        assertNull(arcStateForChapter(null, 3), "没有弧线时返回 null（调用方退化为既有行为）")
        assertNull(arcStateForChapter(newArc("run-1", "novel-1", "甲"), 1), "空弧线返回 null")
        assertNull(arcStateForChapter(JsonObject(emptyMap()), 1), "损坏的弧线返回 null")
    }

    // ---------- 时间点推进（B-3） ----------

    private fun sessionOf(turnCount: Int, start: Int = 0) = buildJsonObject {
        if (start > 0) put("start_chapter", start)
        put("turn_count", turnCount)
    }

    @Test
    fun `derived chapter advances every N turns from the start chapter`() {
        assertEquals(1, derivePersonaChapter(sessionOf(0)), "没有起始章时从第 1 章开始")
        assertEquals(1, derivePersonaChapter(sessionOf(1)))
        assertEquals(
            1,
            derivePersonaChapter(sessionOf(TURNS_PER_PERSONA_CHAPTER - 1)),
            "未满一轮不推进",
        )
        assertEquals(2, derivePersonaChapter(sessionOf(TURNS_PER_PERSONA_CHAPTER)))
        assertEquals(3, derivePersonaChapter(sessionOf(TURNS_PER_PERSONA_CHAPTER * 2)))
        assertEquals(
            6,
            derivePersonaChapter(sessionOf(TURNS_PER_PERSONA_CHAPTER * 2, start = 4)),
            "起始章是推进的起点",
        )
    }

    @Test
    fun `derived chapter is stable so repeated commits cannot advance twice`() {
        // 幂等在这里是**结构性**保证：不读旧值、不累加，只由 turn_count 推导。
        // 因此同一轮重复提交（turn_count 不变）算出的时间点必然相同。
        val manifest = sessionOf(turnCount = 9, start = 2)

        val first = derivePersonaChapter(manifest)
        repeat(5) { assertEquals(first, derivePersonaChapter(manifest), "同一 turn_count 必须得到同一个值") }
        assertEquals(2 + 9 / TURNS_PER_PERSONA_CHAPTER, first)
    }

    @Test
    fun `derived chapter tolerates a missing session`() {
        assertEquals(1, derivePersonaChapter(null), "没有会话时按第 1 章")
    }
}
