package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 设定集：世界观、人物小传、势力关系、冲突设计。 */
@Serializable
data class StoryBibleDto(
    val world: String = "",
    val characters: List<StoryCharacterDto> = emptyList(),
    val factions: List<StoryFactionDto> = emptyList(),
    val conflicts: List<StoryConflictDto> = emptyList(),
) {
    val isEmpty: Boolean
        get() = world.isBlank() && characters.isEmpty() && factions.isEmpty() && conflicts.isEmpty()
}

@Serializable
data class StoryCharacterDto(
    val name: String = "",
    val role: String = "",
    val desire: String = "",
    val secret: String = "",
    /** 说话方式：口癖、句长、称呼习惯。 */
    val voice: String = "",
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
)
