package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 设定集：世界观、人物小传、势力关系、冲突设计、时间线与伏笔表。 */
@Serializable
data class StoryBibleDto(
    val world: String = "",
    val characters: List<StoryCharacterDto> = emptyList(),
    val factions: List<StoryFactionDto> = emptyList(),
    val conflicts: List<StoryConflictDto> = emptyList(),
    val timeline: List<TimelineEventDto> = emptyList(),
    val foreshadowings: List<ForeshadowDto> = emptyList(),
) {
    val isEmpty: Boolean
        get() = world.isBlank() && characters.isEmpty() && factions.isEmpty() &&
            conflicts.isEmpty() && timeline.isEmpty() && foreshadowings.isEmpty()
}

/** 时间线：一条只记「何时、何地、谁做了什么、导致什么」的事件。 */
@Serializable
data class TimelineEventDto(
    val time: String = "",
    val place: String = "",
    val event: String = "",
    /** 对应章节标题；尚未写到的可以留空。 */
    val chapter: String = "",
)

/** 伏笔表：埋在哪里、打算在哪回收、现在什么状态。 */
@Serializable
data class ForeshadowDto(
    val content: String = "",
    @SerialName("planted_in") val plantedIn: String = "",
    @SerialName("payoff_in") val payoffIn: String = "",
    /** open 未回收 / paid 已回收 / dropped 已放弃。 */
    val status: String = "open",
)

/** 人定的故事核心：主题、人物欲望、关键转折、结局。 */
@Serializable
data class StoryCoreDto(
    val theme: String = "",
    val desires: List<StoryDesireDto> = emptyList(),
    @SerialName("turning_points") val turningPoints: List<StoryTurningPointDto> = emptyList(),
    val ending: String = "",
) {
    val isEmpty: Boolean
        get() = theme.isBlank() && desires.isEmpty() && turningPoints.isEmpty() && ending.isBlank()
}

@Serializable
data class StoryDesireDto(
    val name: String = "",
    /** 他以为自己想要的。 */
    val want: String = "",
    /** 真正挡住他的东西。 */
    val obstacle: String = "",
)

@Serializable
data class StoryTurningPointDto(
    val title: String = "",
    val description: String = "",
    /** 大致发生在哪一章；可以是「第 3 章」或章节标题。 */
    val chapter: String = "",
)

@Serializable
data class GenerateStoryCoreRequest(val premise: String = "")

@Serializable
data class StoryCharacterDto(
    val name: String = "",
    val role: String = "",
    val desire: String = "",
    val secret: String = "",
    /** 说话方式：口癖、句长、称呼习惯。 */
    val voice: String = "",
    /** 弧光起点：故事开始时他是什么状态。 */
    @SerialName("arc_start") val arcStart: String = "",
    /** 转变的触发事件：哪件事让他不能再原地不动。 */
    @SerialName("arc_turn") val arcTurn: String = "",
    /** 弧光终点：故事结束时他变成了什么样。 */
    @SerialName("arc_end") val arcEnd: String = "",
)

@Serializable
data class StoryFactionDto(
    val name: String = "",
    val goal: String = "",
    /** 与其他势力的关系（合作 / 敌对 / 利用）。 */
    val relation: String = "",
)

@Serializable
data class StoryConflictDto(
    val name: String = "",
    val stake: String = "",
    /** 冲突如何升级，以及可能引爆的节点。 */
    val escalation: String = "",
)

@Serializable
data class GenerateStoryBibleRequest(
    /** 用户的一句话设定；为空时由书名与已有章节目标代替。 */
    val premise: String = "",
    val genre: String = "",
    val perspective: String = "第三人称限知",
)

@Serializable
data class SaveStoryBibleRequest(val bible: StoryBibleDto)

/** 写作偏好：类型模版 + 目标章长，生成大纲与正文时注入。 */
@Serializable
data class WritingProfileDto(
    val genre: String = "",
    @SerialName("chapter_length") val chapterLength: Int = 2500,
    /** 额外节奏要求，例如「每三章一个小高潮」。 */
    val notes: String = "",
    /** 作者自己的文风样本，用来抑制「正确但平庸」。 */
    @SerialName("voice_sample") val voiceSample: String = "",
    /** 不想出现的表达（套话、金句、口头禅），逗号或换行分隔。 */
    val avoid: String = "",
)

@Serializable
data class SaveWritingProfileRequest(val profile: WritingProfileDto)

@Serializable
data class WritingGenreDto(
    val id: String = "",
    val name: String = "",
    val tagline: String = "",
    /** 给模型看的结构说明：开篇、推进、高潮、收束。 */
    val beats: String = "",
    @SerialName("default_chapter_length") val defaultChapterLength: Int = 2500,
)

@Serializable
data class WritingGenresResponse(val items: List<WritingGenreDto> = emptyList())

/** 卡文救援：plot 情节走向 / dialogue 对话 / scene 场景 / twist 反转。 */
@Serializable
data class RescueRequest(
    @SerialName("chapter_id") val chapterId: String = "",
    val problem: String,
    val kind: String = "plot",
    val count: Int = 3,
)

@Serializable
data class RescueOptionDto(
    val title: String = "",
    val idea: String = "",
    /** 可直接粘进正文的一小段示例。 */
    val snippet: String = "",
)

@Serializable
data class RescueResponse(val options: List<RescueOptionDto> = emptyList())

@Serializable
data class ReviewIssueDto(
    /** 语病 / 语气 / 矛盾 / 设定。 */
    val kind: String = "",
    val quote: String = "",
    val advice: String = "",
)

@Serializable
data class ChapterReviewDto(
    /** 本章摘要，可直接作为下一章的上下文。 */
    val summary: String = "",
    val issues: List<ReviewIssueDto> = emptyList(),
)

@Serializable
data class PolishChapterRequest(
    /** 为空时按「语病 + 语气 + 前后一致」全面润色。 */
    val instruction: String = "",
)

/**
 * 六种类型模版。前后端共用同一份定义，避免「设置里叫网文日更、提示词里叫 web_serial」的漂移。
 */
object WritingGenres {
    val all: List<WritingGenreDto> = listOf(        WritingGenreDto(
            id = "web_serial",
            name = "网文日更",
            tagline = "每章一个钩子，章末留悬念",
            beats = "开篇 300 字内进入冲突；每章至少一次小反转；章末用未解问题收束；“爽点”与“代价”交替出现，避免连续两章同一种胜利。",
            defaultChapterLength = 3000,
        ),
        WritingGenreDto(
            id = "short_story",
            name = "短篇",
            tagline = "一个场景、一次转折、一个余味",
            beats = "开头直接落在一个具体动作上；中段只保留一条主线；结尾用一个意象或动作回收开头，不解释主题。",
            defaultChapterLength = 4000,
        ),
        WritingGenreDto(
            id = "fanfic",
            name = "同人",
            tagline = "保留角色口吻，换一个新处境",
            beats = "先确认原作人物的说话方式与底线；新处境必须逼他们做一次原作里没做过的选择；避免复述原作剧情。",
            defaultChapterLength = 3000,
        ),
        WritingGenreDto(
            id = "children",
            name = "儿童故事",
            tagline = "短句、具体、情绪安全",
            beats = "主角是一个明确年龄的孩子或拟人角色；问题要具体可感；每 300 字有一次小行动；结尾解决眼前问题，不留下恐惧。",
            defaultChapterLength = 1200,
        ),
        WritingGenreDto(
            id = "murder_mystery",
            name = "剧本杀",
            tagline = "每个人都有秘密，线索可推理",
            beats = "先给每位角色一条不可告人的动机；线索必须能指向两个以上嫌疑人；每幕结束让玩家做一次判断；真相要能由已给线索推出。",
            defaultChapterLength = 2500,
        ),
        WritingGenreDto(
            id = "brand_story",
            name = "营销故事",
            tagline = "先让人物遇困，再让产品入场",
            beats = "前 2/3 只讲故事，不出现产品名；转折点由产品的具体功能解决一个具体麻烦；结尾给一个可复述的句子，不用形容词堆卖点。",
            defaultChapterLength = 1500,
        ),
    )

    fun byId(id: String): WritingGenreDto? = all.firstOrNull { it.id == id }
}

/** 落盘前的清洗：截断长度、丢掉没有名字的条目，避免把模型的长篇废话原样存下来。 */
internal fun StoryBibleDto.normalizedForStorage(): StoryBibleDto = StoryBibleDto(
    world = world.trim().take(4000),
    characters = characters.map {
        StoryCharacterDto(
            name = it.name.trim().take(40),
            role = it.role.trim().take(200),
            desire = it.desire.trim().take(200),
            secret = it.secret.trim().take(200),
            voice = it.voice.trim().take(160),
            arcStart = it.arcStart.trim().take(200),
            arcTurn = it.arcTurn.trim().take(200),
            arcEnd = it.arcEnd.trim().take(200),
        )
    }.filter { it.name.isNotBlank() }.take(12),
    factions = factions.map {
        StoryFactionDto(
            name = it.name.trim().take(40),
            goal = it.goal.trim().take(200),
            relation = it.relation.trim().take(200),
        )
    }.filter { it.name.isNotBlank() }.take(8),
    conflicts = conflicts.map {
        StoryConflictDto(
            name = it.name.trim().take(60),
            stake = it.stake.trim().take(240),
            escalation = it.escalation.trim().take(240),
        )
    }.filter { it.name.isNotBlank() }.take(8),
    timeline = timeline.map {
        TimelineEventDto(
            time = it.time.trim().take(60),
            place = it.place.trim().take(60),
            event = it.event.trim().take(300),
            chapter = it.chapter.trim().take(60),
        )
    }.filter { it.event.isNotBlank() }.take(60),
    foreshadowings = foreshadowings.map {
        ForeshadowDto(
            content = it.content.trim().take(200),
            plantedIn = it.plantedIn.trim().take(60),
            payoffIn = it.payoffIn.trim().take(60),
            status = it.status.trim().take(20).ifBlank { "open" },
        )
    }.filter { it.content.isNotBlank() }.take(40),
)

/** 故事核心的落盘清洗：去空、限量，避免模型一次塞进几十条转折。 */
internal fun StoryCoreDto.normalizedForStorage(): StoryCoreDto = StoryCoreDto(
    theme = theme.trim().take(600),
    desires = desires.map {
        StoryDesireDto(
            name = it.name.trim().take(40),
            want = it.want.trim().take(200),
            obstacle = it.obstacle.trim().take(200),
        )
    }.filter { it.name.isNotBlank() }.take(12),
    turningPoints = turningPoints.map {
        StoryTurningPointDto(
            title = it.title.trim().take(60),
            description = it.description.trim().take(300),
            chapter = it.chapter.trim().take(60),
        )
    }.filter { it.title.isNotBlank() }.take(12),
    ending = ending.trim().take(800),
)

/** 场景卡：一场戏的最小骨架，用来把「这一章要发生什么」拆成可写的几步。 */
@Serializable
data class SceneCardDto(
    val title: String = "",
    val place: String = "",
    val time: String = "",
    val characters: List<String> = emptyList(),
    /** 这场戏里谁想要什么。 */
    val goal: String = "",
    /** 谁/什么挡住了他。 */
    val conflict: String = "",
    /** 这场戏结束时局面发生的变化。 */
    val turn: String = "",
    /** 离场时的状态，供下一场接上。 */
    val exit: String = "",
)

@Serializable
data class SceneCardsResponse(val items: List<SceneCardDto> = emptyList())

@Serializable
data class GenerateSceneCardsRequest(
    @SerialName("chapter_id") val chapterId: String,
    val count: Int = 4,
)

@Serializable
data class DraftVersionsRequest(
    @SerialName("chapter_id") val chapterId: String,
    val count: Int = 2,
    /** 可指定取向，例如「克制」「更激烈」；为空则由模型给出差异明显的两种写法。 */
    val directions: List<String> = emptyList(),
    val instruction: String = "",
)

@Serializable
data class DraftVersionDto(
    /** A / B，便于用户指代。 */
    val label: String = "",
    /** 这一版与另一版差在哪。 */
    val approach: String = "",
    val content: String = "",
)

@Serializable
data class DraftVersionsResponse(val items: List<DraftVersionDto> = emptyList())

@Serializable
data class ApplyChapterContentRequest(
    val content: String,
    @SerialName("context_summary") val contextSummary: String = "",
)

/** 局部重写：只改选中的一段，不动整章。 */
@Serializable
data class RewriteSelectionRequest(
    val text: String,
    val instruction: String = "",
    val count: Int = 3,
)

@Serializable
data class RewriteOptionDto(
    val label: String = "",
    val content: String = "",
)

@Serializable
data class RewriteSelectionResponse(val items: List<RewriteOptionDto> = emptyList())

/** 全书一致性：跨章节的矛盾、时间线冲突、未回收伏笔。 */
@Serializable
data class ConsistencyIssueDto(
    /** 时间线 / 人物 / 伏笔 / 设定。 */
    val kind: String = "",
    val chapters: List<String> = emptyList(),
    val detail: String = "",
    val advice: String = "",
)

@Serializable
data class ConsistencyReportDto(
    val summary: String = "",
    val issues: List<ConsistencyIssueDto> = emptyList(),
)

/** 人工终审：逻辑、情感、敏感内容、原创性四项，由人决定是否放行。 */
@Serializable
data class FinalReviewIssueDto(
    val quote: String = "",
    val detail: String = "",
    val advice: String = "",
)

@Serializable
data class FinalReviewDto(
    val logic: List<FinalReviewIssueDto> = emptyList(),
    val emotion: List<FinalReviewIssueDto> = emptyList(),
    val sensitive: List<FinalReviewIssueDto> = emptyList(),
    /** 只提示套路化与高度相似的情节，不做抄袭判定。 */
    val originality: List<FinalReviewIssueDto> = emptyList(),
    val verdict: String = "",
)
