package top.wkbin.zaomeng.platform

/**
 * 朗读情绪。**单一来源**：界面上的情绪徽章与朗读韵律都看它，避免两处各写一套关键词而漂移。
 *
 * 识别是**纯本地关键词匹配**（[classifySpeechEmotion]）：离线、确定性、零延迟，
 * 不需要模型也不联网——这一点对本项目很重要，端侧模式必须能完整工作。
 */
enum class SpeechEmotion(val emoji: String, val label: String) {
    JOY("😄", "愉快"),
    ANGER("😠", "愤怒"),
    SORROW("😢", "悲伤"),
    SURPRISE("😮", "惊讶"),
    TENSE("😰", "紧张"),
    CALM("😌", "平静"),
}

/**
 * 关键词表。**顺序即优先级**（先命中者胜）：原实现就是这个次序，迁移时保持不变，
 * 否则同一句话的徽章会变。
 */
private val emotionKeywords: List<Pair<SpeechEmotion, List<String>>> = listOf(
    SpeechEmotion.JOY to listOf("开心", "高兴", "喜悦", "欣喜", "愉快", "笑", "happy", "joy"),
    SpeechEmotion.ANGER to listOf("愤怒", "生气", "恼火", "怒", "气愤", "angry", "anger"),
    SpeechEmotion.SORROW to listOf("悲伤", "难过", "伤心", "哀伤", "哭", "sad", "sorrow"),
    SpeechEmotion.SURPRISE to listOf("惊讶", "震惊", "诧异", "意外", "surprised", "shock"),
    SpeechEmotion.TENSE to listOf("紧张", "害怕", "不安", "焦虑", "担心", "恐惧", "nervous", "afraid"),
    SpeechEmotion.CALM to listOf("平静", "冷静", "安心", "释然", "从容", "calm", "peaceful"),
)

/**
 * 从文本里识别情绪；识别不出返回 `null`（此时朗读用角色本来的声线，不额外加工）。
 *
 * 调用方通常传**内心独白**（`inner_thought`），它比台词本身更能说明情绪；
 * 没有内心独白时退回台词。
 */
fun classifySpeechEmotion(text: String?): SpeechEmotion? {
    val normalized = text?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) return null
    return emotionKeywords.firstOrNull { (_, keywords) ->
        keywords.any(normalized::contains)
    }?.first
}
