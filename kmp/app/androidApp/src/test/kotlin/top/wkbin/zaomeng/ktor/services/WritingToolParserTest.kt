package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.ForeshadowDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.StoryCoreDto
import top.wkbin.zaomeng.data.api.StoryDesireDto
import top.wkbin.zaomeng.data.api.StoryTurningPointDto
import top.wkbin.zaomeng.data.api.TimelineEventDto
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

class WritingWorkflowParserTest {
    @Test
    fun `story core parses through fences`() {
        val raw = """
            ```json
            {
              "theme": "人为了不被忘记，会主动编造自己的过去。",
              "desires": [{"name": "沈砚", "want": "找回妹妹的名字", "obstacle": "他改过自己的账"}],
              "turning_points": [{"title": "烧掉第一本账", "description": "他烧掉能换回妹妹的账本，从此不可逆。", "chapter": "第 5 章"}],
              "ending": "他把自己的名字写进账本，然后合上。"
            }
            ```
        """.trimIndent()

        val core = WritingToolParser.parseStoryCore(raw)

        assertTrue(core.theme.contains("编造"))
        assertEquals("沈砚", core.desires.first().name)
        assertEquals("烧掉第一本账", core.turningPoints.first().title)
        assertTrue(core.ending.contains("合上"))
    }

    @Test
    fun `scene cards accept bare array and keep all fields`() {
        val raw = """
            [{"title":"雨夜码头","place":"码头","time":"子时","characters":["沈砚","阿棠"],
              "goal":"换回账本","conflict":"港务司封了码头","turn":"账本落水","exit":"他手里只剩一页"}]
        """.trimIndent()

        val cards = WritingToolParser.parseSceneCards(raw)

        assertEquals(1, cards.size)
        assertEquals("码头", cards.first().place)
        assertEquals(listOf("沈砚", "阿棠"), cards.first().characters)
        assertEquals("他手里只剩一页", cards.first().exit)
    }

    @Test
    fun `draft versions skip empty content`() {
        val versions = WritingToolParser.parseDraftVersions(
            """{"items":[{"label":"A","approach":"贴内心","content":"正文 A"},{"label":"B","approach":"空","content":"  "}]}""",
        )

        assertEquals(1, versions.size)
        assertEquals("A", versions.first().label)
    }

    @Test
    fun `final review keeps the four groups separate`() {
        val review = WritingToolParser.parseFinalReview(
            """{"logic":[{"quote":"他突然原谅","detail":"缺少铺垫","advice":"补一场对话"}],
                "emotion":[],"sensitive":[],"originality":[{"quote":"失忆","detail":"常见桥段","advice":"换一个代价"}],
                "verdict":"能发，先补动机。"}""",
        )

        assertEquals(1, review.logic.size)
        assertTrue(review.emotion.isEmpty())
        assertEquals(1, review.originality.size)
        assertTrue(review.verdict.contains("能发"))
    }

    @Test
    fun `story core normalization trims and caps`() {
        val core = StoryCoreDto(
            theme = "  主题  ",
            desires = listOf(StoryDesireDto(name = " ", want = "x"), StoryDesireDto(name = " 沈砚 ", want = " 找回 ")),
            turningPoints = listOf(StoryTurningPointDto(title = "", description = "x"), StoryTurningPointDto(title = " 转折 ")),
            ending = "  结局  ",
        ).normalizedForStorage()

        assertEquals("主题", core.theme)
        assertEquals(1, core.desires.size)
        assertEquals("沈砚", core.desires.first().name)
        assertEquals(1, core.turningPoints.size)
        assertEquals("结局", core.ending)
    }

    @Test
    fun `timeline and foreshadowing survive bible normalization`() {
        val bible = StoryBibleDto(
            world = "港城",
            timeline = listOf(TimelineEventDto(time = "子时", place = "码头", event = "账本落水")),
            foreshadowings = listOf(ForeshadowDto(content = "缺页的账本", plantedIn = "第 2 章", status = "open")),
        ).normalizedForStorage()

        assertEquals(1, bible.timeline.size)
        assertEquals("账本落水", bible.timeline.first().event)
        assertEquals(1, bible.foreshadowings.size)
        assertEquals("open", bible.foreshadowings.first().status)
    }
}
