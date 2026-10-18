package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.StoryCharacterDto
import top.wkbin.zaomeng.data.api.StoryConflictDto
import top.wkbin.zaomeng.data.api.StoryFactionDto
import top.wkbin.zaomeng.data.api.WritingGenres
import top.wkbin.zaomeng.data.api.normalizedForStorage

class WritingToolParserTest {
    @Test
    fun `story bible survives markdown fences and small talk`() {
        val raw = """
            好的，这是设定集：
            ```json
            {
              "world": "海雾常年不散的港城，名字写进账本就会成真。",
              "characters": [
                {"name": "沈砚", "role": "修补旧账本的匠人", "desire": "找回妹妹的名字", "secret": "他改过自己的账", "voice": "句子很短，先答后想"}
              ],
              "factions": [{"name": "港务司", "goal": "封住会成真的账本", "relation": "与商会互相利用"}],
              "conflicts": [{"name": "名字争夺", "stake": "写错一个字就有人消失", "escalation": "账本开始自己增页"}]
            }
            ```
            希望有帮助。
        """.trimIndent()

        val bible = WritingToolParser.parseStoryBible(raw)

        assertTrue(bible.world.contains("港城"))
        assertEquals(1, bible.characters.size)
        assertEquals("沈砚", bible.characters.first().name)
        assertEquals("港务司", bible.factions.first().name)
        assertEquals("名字争夺", bible.conflicts.first().name)
    }

    @Test
    fun `missing fields fall back to empty instead of failing`() {
        val bible = WritingToolParser.parseStoryBible("""{"world":"只有世界观"}""")

        assertEquals("只有世界观", bible.world)
        assertTrue(bible.characters.isEmpty())
        assertTrue(bible.factions.isEmpty())
    }

    @Test
    fun `braces inside strings do not cut the json short`() {
        val bible = WritingToolParser.parseStoryBible(
            """{"world":"他总说「{」是开门的暗号","characters":[]}""",
        )

        assertTrue(bible.world.contains("开门的暗号"))
    }

    @Test
    fun `rescue accepts both wrapped options and a bare array`() {
        val wrapped = WritingToolParser.parseRescue(
            """{"options":[{"title":"让他先开口","idea":"用对话逼他做选择","snippet":"他把账本推过去。"}]}""",
        )
        val bare = WritingToolParser.parseRescue(
            """[{"title":"下雨","idea":"用天气打断对峙","snippet":"雨突然大了。"}]""",
        )

        assertEquals("让他先开口", wrapped.first().title)
        assertEquals("下雨", bare.first().title)
    }

    @Test
    fun `review keeps issue kinds and empty list`() {
        val review = WritingToolParser.parseReview(
            """{"summary":"本章交代了账本的来历。","issues":[{"kind":"语病","quote":"他把他自己的账本","advice":"删掉一个他"}]}""",
        )
        val clean = WritingToolParser.parseReview("""{"summary":"没什么问题","issues":[]}""")

        assertEquals("语病", review.issues.first().kind)
        assertTrue(clean.issues.isEmpty())
    }

    @Test
    fun `genre ids are unique and resolvable`() {
        val ids = WritingGenres.all.map { it.id }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals("网文日更", WritingGenres.byId("web_serial")?.name)
        assertTrue(WritingGenres.byId("nope") == null)
        assertTrue(WritingGenres.all.all { it.beats.isNotBlank() && it.defaultChapterLength > 0 })
    }

    @Test
    fun `storage normalization trims and drops nameless entries`() {
        val bible = StoryBibleDto(
            world = "  海雾港城  ",
            characters = listOf(
                StoryCharacterDto(name = "   "),
                StoryCharacterDto(name = " 沈砚 ", role = " 匠人 "),
            ),
            factions = listOf(StoryFactionDto(name = "")),
            conflicts = listOf(StoryConflictDto(name = " 名字争夺 ")),
        ).normalizedForStorage()

        assertEquals("海雾港城", bible.world)
        assertEquals(1, bible.characters.size)
        assertEquals("沈砚", bible.characters.first().name)
        assertEquals("匠人", bible.characters.first().role)
        assertTrue(bible.factions.isEmpty())
        assertEquals("名字争夺", bible.conflicts.first().name)
    }

    @Test
    fun `whitespace only bible counts as empty`() {
        assertTrue(StoryBibleDto(world = "   ").normalizedForStorage().isEmpty)
    }
}
