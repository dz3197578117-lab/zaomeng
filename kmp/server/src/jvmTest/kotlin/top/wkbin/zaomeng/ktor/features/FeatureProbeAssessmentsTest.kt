package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纯逻辑判定的回归测试。
 *
 * 这些函数是功能自检的「大脑」：探针只负责读数据，结论全在这里。
 * 用**构造出来的 manifest/JsonObject** 做输入，不依赖真机数据。
 */
class FeatureProbeAssessmentsTest {

    // ------------------------------------------------------------------
    // 人设弧线：覆盖率与注入判定
    // ------------------------------------------------------------------

    private fun arcEntry(chapter: Int, status: String, state: Boolean, truncated: Boolean = false) =
        buildJsonObject {
            put("chapter", chapter)
            put("status", status)
            put("source_truncated", truncated)
            put("state", if (state) buildJsonObject { put("goal", "目标") } else kotlinx.serialization.json.JsonNull)
        }

    private fun arcOf(vararg entries: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("chapters", JsonArray(entries.toList()))
        put("chapter_count", entries.size)
    }

    @Test
    fun `arc with no entries is no-data not failure`() {
        val assessment = assessPersonaArc(declaredChapterCount = 0, entries = emptyList())
        assertEquals(ProbeStatus.NO_DATA, assessment.status)
    }

    @Test
    fun `arc coverage and chapter count mismatch is a warning`() {
        val arc = arcOf(arcEntry(1, "ok", true), arcEntry(2, "ok", true))
        val entries = extractArcChapters(arc)
        // 索引里计划 5 章，但弧线只收了 2 章，且 declared 与实际条目数不符
        val assessment = assessPersonaArc(
            declaredChapterCount = 3,
            entries = entries,
            plannedChapters = setOf(1, 2, 3, 4, 5),
            personaChapter = 0,
        )
        assertEquals(ProbeStatus.WARN, assessment.status)
        assertEquals("2/5", assessment.evidence["ok"])
        assertEquals("40%", assessment.evidence["coverage"])
    }

    @Test
    fun `arc with all ok entries and full coverage is ok`() {
        val arc = arcOf(arcEntry(1, "ok", true), arcEntry(2, "ok", true), arcEntry(3, "ok", true))
        val assessment = assessPersonaArc(
            declaredChapterCount = 3,
            entries = extractArcChapters(arc),
            plannedChapters = setOf(1, 2, 3),
            personaChapter = 2,
        )
        assertEquals(ProbeStatus.OK, assessment.status)
        assertEquals("yes", assessment.evidence["arc_state_injected"])
    }

    @Test
    fun `arc injection fails when covering segment has empty state`() {
        // 当前时间点落在第 3 章，而第 3 章是 empty（没有 state）→ 弧线实际不会被注入
        val arc = arcOf(arcEntry(1, "ok", true), arcEntry(3, "empty", false))
        val assessment = assessPersonaArc(
            declaredChapterCount = 2,
            entries = extractArcChapters(arc),
            plannedChapters = setOf(1, 2, 3),
            personaChapter = 3,
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("no", assessment.evidence["arc_state_injected"])
    }

    @Test
    fun `failed dominated arc is a failure`() {
        val arc = arcOf(
            arcEntry(1, "failed", false),
            arcEntry(2, "failed", false),
            arcEntry(3, "ok", true),
        )
        val assessment = assessPersonaArc(
            declaredChapterCount = 3,
            entries = extractArcChapters(arc),
            plannedChapters = setOf(1, 2, 3),
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("2", assessment.evidence["failed"])
    }

    @Test
    fun `truncated source chapters are counted as evidence`() {
        val arc = arcOf(arcEntry(1, "ok", true, truncated = true), arcEntry(2, "ok", true))
        val assessment = assessPersonaArc(
            declaredChapterCount = 2,
            entries = extractArcChapters(arc),
            plannedChapters = setOf(1, 2),
        )
        assertEquals("1", assessment.evidence["truncated"])
    }

    // ------------------------------------------------------------------
    // 弧线索引一致性
    // ------------------------------------------------------------------

    @Test
    fun `arc index mismatch is detected`() {
        val indexed = listOf(
            IndexedCharacter("甲", hasArc = true, arcCount = 12),
            IndexedCharacter("乙", hasArc = false, arcCount = 0),
        )
        val actual = mapOf("甲" to 12, "乙" to 3)
        val assessment = assessArcIndexConsistency(indexed, actual)
        assertEquals(ProbeStatus.WARN, assessment.status)
        assertEquals("1", assessment.evidence["mismatched"])
        assertTrue(assessment.details.any { it.contains("乙") })
    }

    @Test
    fun `arc index fully consistent is ok`() {
        val indexed = listOf(IndexedCharacter("甲", hasArc = true, arcCount = 4))
        val assessment = assessArcIndexConsistency(indexed, mapOf("甲" to 4))
        assertEquals(ProbeStatus.OK, assessment.status)
    }

    @Test
    fun `empty arc index is no-data`() {
        assertEquals(ProbeStatus.NO_DATA, assessArcIndexConsistency(emptyList(), emptyMap()).status)
    }

    // ------------------------------------------------------------------
    // 会话完整性：transcript 重复 key
    // ------------------------------------------------------------------

    private fun key(turnId: String, speaker: String, ts: String, message: String) =
        TranscriptEntryKey(turnId, speaker, ts, message)

    @Test
    fun `duplicate transcript keys are a failure`() {
        val transcript = listOf(
            key("t1", "甲", "2026-01-01", "你好"),
            key("t1", "甲", "2026-01-01", "你好"),
            key("t2", "乙", "2026-01-01", "嗯"),
        )
        val assessment = assessSessionIntegrity(transcript, declaredTranscriptCount = 3, transcriptStart = 0)
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("1", assessment.evidence["duplicate_keys"])
    }

    @Test
    fun `different speakers with same text are not duplicates`() {
        val transcript = listOf(
            key("t1", "甲", "2026-01-01", "你好"),
            key("t1", "乙", "2026-01-01", "你好"),
        )
        assertEquals(ProbeStatus.OK, assessSessionIntegrity(transcript, 2, 0).status)
    }

    @Test
    fun `transcript count mismatch is a warning`() {
        val transcript = listOf(key("t1", "甲", "ts", "a"), key("t2", "甲", "ts", "b"))
        val assessment = assessSessionIntegrity(transcript, declaredTranscriptCount = 9, transcriptStart = 0)
        assertEquals(ProbeStatus.WARN, assessment.status)
        assertEquals("9", assessment.evidence["declared_count"])
    }

    @Test
    fun `archived transcript does not false-positive count mismatch`() {
        // transcript_start > 0 时，manifest 里只保留最近 N 条，总量由 transcript_count 表达
        val transcript = listOf(key("t9", "甲", "ts", "a"), key("t10", "甲", "ts", "b"))
        val assessment = assessSessionIntegrity(transcript, declaredTranscriptCount = 42, transcriptStart = 40)
        assertEquals(ProbeStatus.OK, assessment.status)
    }

    // ------------------------------------------------------------------
    // 时间线推进
    // ------------------------------------------------------------------

    @Test
    fun `timeline expected value follows turns per chapter`() {
        // start=3, turns=9, 每 4 轮推进一章 → 3 + 9/4 = 5
        val ok = assessTimelineAdvance(startChapter = 3, turnCount = 9, personaChapter = 5)
        assertEquals(ProbeStatus.OK, ok.status)
        assertEquals("5", ok.evidence["expected"])

        val drifted = assessTimelineAdvance(startChapter = 3, turnCount = 9, personaChapter = 3)
        assertEquals(ProbeStatus.FAIL, drifted.status)
        assertEquals("-2", drifted.evidence["drift"])
    }

    @Test
    fun `timeline without turns stays at start chapter`() {
        val assessment = assessTimelineAdvance(startChapter = 7, turnCount = 0, personaChapter = 7)
        assertEquals(ProbeStatus.OK, assessment.status)
        assertEquals("7", assessment.evidence["expected"])
    }

    @Test
    fun `timeline falls back to chapter one when nothing set`() {
        val assessment = assessTimelineAdvance(startChapter = 0, turnCount = 0, personaChapter = 0)
        assertEquals(ProbeStatus.OK, assessment.status)
        assertEquals("1", assessment.evidence["expected"])
    }

    // ------------------------------------------------------------------
    // 提示词规则键
    // ------------------------------------------------------------------

    @Test
    fun `all prompt rule keys present is ok`() {
        val resolved = REQUIRED_PROMPT_RULES.associate { "${it.source}|${it.key}" to "文本内容" }
        val assessment = assessPromptRules(resolved)
        assertEquals(ProbeStatus.OK, assessment.status)
        assertEquals("4", assessment.evidence["loaded"])
    }

    @Test
    fun `missing prompt rule key fails and points at sync script`() {
        val resolved = mutableMapOf<String, String>()
        REQUIRED_PROMPT_RULES.dropLast(1).forEach { resolved["${it.source}|${it.key}"] = "ok" }
        // persona_arc_output_format 缺失
        val assessment = assessPromptRules(resolved)
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("1", assessment.evidence["missing"])
        assertTrue(assessment.summary.contains("check-prompt-sync.sh"), assessment.summary)
    }

    @Test
    fun `blank prompt rule text counts as missing`() {
        val resolved = REQUIRED_PROMPT_RULES.associate { "${it.source}|${it.key}" to "   " }
        assertEquals(ProbeStatus.FAIL, assessPromptRules(resolved).status)
    }

    // ------------------------------------------------------------------
    // 关系图悬空引用
    // ------------------------------------------------------------------

    @Test
    fun `dangling relation references are reported`() {
        val relations = listOf(
            RelationPair("甲", "乙"),
            RelationPair("甲", "丙"), // 丙 不在已知角色里
        )
        val assessment = assessRelationGraph(relations, knownCharacters = setOf("甲", "乙"))
        assertEquals(ProbeStatus.WARN, assessment.status)
        assertEquals("1", assessment.evidence["dangling"])
        assertTrue(assessment.details.any { it.contains("丙") })
    }

    @Test
    fun `relations fully inside known characters is ok`() {
        val relations = listOf(RelationPair("甲", "乙"))
        assertEquals(ProbeStatus.OK, assessRelationGraph(relations, setOf("甲", "乙")).status)
    }

    @Test
    fun `mostly dangling relations escalate to failure`() {
        val relations = listOf(RelationPair("甲", "乙"), RelationPair("丙", "丁"))
        val assessment = assessRelationGraph(relations, knownCharacters = setOf("甲"))
        assertEquals(ProbeStatus.FAIL, assessment.status)
    }

    @Test
    fun `empty relation graph is no-data`() {
        assertEquals(ProbeStatus.NO_DATA, assessRelationGraph(emptyList(), setOf("甲")).status)
    }

    // ------------------------------------------------------------------
    // 章节索引
    // ------------------------------------------------------------------

    @Test
    fun `invalid chapter intervals fail`() {
        val assessment = assessChapterIndex(
            chapterCount = 2,
            intervals = listOf(Triple(1, 0, 100), Triple(2, 100, 100)),
            sourceCharCount = 200,
            cacheStale = false,
            declaredTruncated = false,
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("1", assessment.evidence["invalid"])
    }

    @Test
    fun `out of range chapter interval fails`() {
        val assessment = assessChapterIndex(
            chapterCount = 1,
            intervals = listOf(Triple(1, 0, 500)),
            sourceCharCount = 300,
            cacheStale = false,
            declaredTruncated = false,
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("1", assessment.evidence["out_of_range"])
    }

    @Test
    fun `stale chapter cache is a warning`() {
        val assessment = assessChapterIndex(
            chapterCount = 1,
            intervals = listOf(Triple(1, 0, 100)),
            sourceCharCount = 100,
            cacheStale = true,
            declaredTruncated = false,
        )
        assertEquals(ProbeStatus.WARN, assessment.status)
    }

    // ------------------------------------------------------------------
    // 事件驱动的「最近一次成败」
    // ------------------------------------------------------------------

    @Test
    fun `latest outcome without samples is no-data not failure`() {
        val assessment = assessLatestOutcome(
            events = emptyList(),
            successCategories = setOf("plugin.action"),
            failureCategories = setOf("plugin.action_failed"),
            label = "插件动作",
        )
        assertEquals(ProbeStatus.NO_DATA, assessment.status)
    }

    @Test
    fun `later failure after earlier success is a failure`() {
        val assessment = assessLatestOutcome(
            events = listOf("plugin.action" to "成功", "plugin.action_failed" to "超时"),
            successCategories = setOf("plugin.action"),
            failureCategories = setOf("plugin.action_failed"),
            label = "插件动作",
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertEquals("true", assessment.evidence["failure_is_latest"])
    }

    @Test
    fun `recovery after failure is ok`() {
        val assessment = assessLatestOutcome(
            events = listOf("plugin.action_failed" to "超时", "plugin.action" to "成功"),
            successCategories = setOf("plugin.action"),
            failureCategories = setOf("plugin.action_failed"),
            label = "插件动作",
        )
        assertEquals(ProbeStatus.OK, assessment.status)
    }

    // ------------------------------------------------------------------
    // 档案完整性
    // ------------------------------------------------------------------

    @Test
    fun `profile missing required fields fails`() {
        val text = "---\nname: 甲\nidentity: 学生\n---\n"
        val frontMatter = extractFrontMatter(text)!!
        val assessment = assessProfileCompleteness(
            relativePath = "artifacts/characters/n/甲/PROFILE.md",
            text = text,
            hasFrontMatter = true,
            parsedKeys = extractFrontMatterKeys(frontMatter),
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
        assertTrue(assessment.summary.contains("personality"))
    }

    @Test
    fun `profile with placeholder markers warns`() {
        val text = "---\nname: 甲\nidentity: 学生\npersonality: 未知\nspeech_style: 简短\n---\n"
        val frontMatter = extractFrontMatter(text)!!
        val assessment = assessProfileCompleteness(
            relativePath = "PROFILE.md",
            text = text,
            hasFrontMatter = true,
            parsedKeys = extractFrontMatterKeys(frontMatter),
        )
        assertEquals(ProbeStatus.WARN, assessment.status)
        assertEquals("1", assessment.evidence["placeholders"])
    }

    @Test
    fun `profile without front matter fails`() {
        val assessment = assessProfileCompleteness(
            relativePath = "PROFILE.md",
            text = "# 甲\n随便写点",
            hasFrontMatter = false,
            parsedKeys = emptySet(),
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
    }

    @Test
    fun `complete profile is ok`() {
        val text = "---\nname: 甲\nidentity: 学生\npersonality: 冷静\nspeech_style: 简短\n---\n正文"
        val frontMatter = extractFrontMatter(text)!!
        val assessment = assessProfileCompleteness(
            relativePath = "PROFILE.md",
            text = text,
            hasFrontMatter = true,
            parsedKeys = extractFrontMatterKeys(frontMatter),
        )
        assertEquals(ProbeStatus.OK, assessment.status)
    }

    // ------------------------------------------------------------------
    // 派生状态存在性
    // ------------------------------------------------------------------

    @Test
    fun `derived state absent despite turns is a failure`() {
        val assessment = assessDerivedStatePresence(
            label = "场景进度",
            sessionsChecked = 3,
            withTurns = 3,
            present = 0,
            absentSamples = listOf("r/s 无 scene_progress"),
        )
        assertEquals(ProbeStatus.FAIL, assessment.status)
    }

    @Test
    fun `derived state partially present is a warning`() {
        val assessment = assessDerivedStatePresence(
            label = "场景进度",
            sessionsChecked = 3,
            withTurns = 3,
            present = 2,
            absentSamples = listOf("r/s 无 scene_progress"),
        )
        assertEquals(ProbeStatus.WARN, assessment.status)
    }

    @Test
    fun `derived state with no turns is no-data`() {
        val assessment = assessDerivedStatePresence(
            label = "场景进度",
            sessionsChecked = 0,
            withTurns = 0,
            present = 0,
            absentSamples = emptyList(),
        )
        assertEquals(ProbeStatus.NO_DATA, assessment.status)
    }
}
