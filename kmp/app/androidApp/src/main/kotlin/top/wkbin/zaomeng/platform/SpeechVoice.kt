package top.wkbin.zaomeng.platform

/**
 * 一个角色的「声线档案」：朗读时用哪套音高/语速/音量/系统音色。
 *
 * 存在的理由：在这之前**聊天页朗读没有传任何声线参数**，所有角色共用默认值，
 * 于是「每个人物声音一样」。这里把「角色 → 声线」抽成一个纯值类型，
 * 让系统 TTS 与（后续的）离线神经 TTS 共用同一条上层表达。
 */
data class VoiceProfile(
    /** 音调倍率，1.0 为标准。 */
    val pitch: Float = 1f,
    /** 语速倍率，1.0 为标准。 */
    val speed: Float = 1f,
    /** 音量倍率，1.0 为标准。低语等情绪会用到。 */
    val volume: Float = 1f,
    /** 系统音色名（`Voice.name` 片段）或神经 TTS 的说话人标识；空串表示用引擎默认。 */
    val voiceName: String = "",
) {
    /**
     * 收进安全区间。平台文档写的是 pitch 0.5~1.5、speed 0.5~2.0，这里**再收一点**：
     * 贴着边界走会明显失真（刺耳/闷），而且能听出是「加工」而不是角色的声音。
     */
    fun normalized(): VoiceProfile = copy(
        pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH),
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        volume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME),
    )

    companion object {
        const val MIN_PITCH = 0.6f
        const val MAX_PITCH = 1.6f
        const val MIN_SPEED = 0.6f
        const val MAX_SPEED = 1.8f
        const val MIN_VOLUME = 0.15f
        const val MAX_VOLUME = 1f
    }
}

/**
 * 作者（或用户）为该角色显式填写的声线。字段都可以缺省——缺省处回落到 [derivedVoiceProfile]。
 *
 * 对应人物包里的 `voice_name` / `voice_pitch` / `voice_speed`（`PersonaService.VOICE_FIELDS`）。
 * `voice_desc`（如「低沉沙哑」）是给人看的自由描述，本版不参与推导。
 */
data class AuthoredVoice(
    val voiceName: String = "",
    val pitch: Float? = null,
    val speed: Float? = null,
    val volume: Float? = null,
)

/**
 * 人物包字段 → [AuthoredVoice]。
 *
 * 对应服务端 `PersonaService.VOICE_FIELDS`（`voice_name` / `voice_pitch` / `voice_speed` / `voice_desc`）。
 *
 * **全部字段都是可选的，且解析必须容错**：人物包来自模型蒸馏与人工编辑，
 * 里面完全可能是空串、`"1.2 倍"`、`"稍快"` 这类不能直接转数字的内容。
 * 解析失败一律当「没填」处理，回落到按角色名派生——**绝不能因为一个字段格式不对就让朗读失败**。
 */
fun Map<String, String>.toAuthoredVoice(): AuthoredVoice = AuthoredVoice(
    voiceName = this["voice_name"].orEmpty().trim(),
    pitch = this["voice_pitch"].toVoiceNumberOrNull(),
    speed = this["voice_speed"].toVoiceNumberOrNull(),
    volume = this["voice_volume"].toVoiceNumberOrNull(),
)

/**
 * 宽松解析声线数值：允许首尾空白与常见的全角数字，其余一律返回 `null`。
 *
 * 刻意**不做**「从中文描述里猜数字」（如「稍快」→ 1.1）：猜错会让角色听起来不对劲，
 * 而且难以解释；宁可回落到派生值，让作者用明确的数字覆盖。
 */
private fun String?.toVoiceNumberOrNull(): Float? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val normalized = raw.map { char ->
        if (char in '０'..'９') '0' + (char - '０') else char
    }.joinToString("").replace('．', '.')
    return normalized.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
}

/**
 * 角色名 → 声线，**确定性派生**。
 *
 * 为什么必须确定性：同一条声线是角色身份的一部分，「林黛玉的声音」不该每次启动或换手机就变。
 * 所以不引入随机数、不依赖当前时间；用 `String.hashCode()`（Kotlin/JVM 对它有明确定义，
 * 各平台一致）取三段互不重叠的位，分别驱动音高、语速、音量——
 * 分开取位是为了让角色差异**不同向**：否则所有角色会一起变得又高又快，听起来反而更像同一个人。
 */
fun derivedVoiceProfile(characterName: String): VoiceProfile {
    val key = characterName.trim()
    if (key.isEmpty()) return VoiceProfile()
    val hash = avalanche(key.hashCode())
    // 每段取 10 位（1024 档）而不是 5~6 位：分辨率太粗会让两个角色拿到**完全相同**的声线。
    // 实测 5 位时，8 个常见名字里就撞了一对（李慕白 / 顾青）——而「每个人物声音不同」正是本功能的目的，
    // 撞车不是概率问题而是缺陷。三段位互不重叠，保证它们独立变化。
    val pitchBits = (hash ushr 2) and 0x3FF // 0..1023
    val speedBits = (hash ushr 12) and 0x3FF
    val volumeBits = (hash ushr 22) and 0x3FF
    return VoiceProfile(
        pitch = 0.80f + pitchBits * (0.62f / 1023f), // 0.80 ~ 1.42
        speed = 0.88f + speedBits * (0.47f / 1023f), // 0.88 ~ 1.35
        volume = 0.86f + volumeBits * (0.135f / 1023f), // 0.86 ~ 0.995
    ).normalized()
}

/**
 * 32 位雪崩混合（Murmur3 的 finalizer）。
 *
 * 为什么不能直接拿 `String.hashCode()` 取高位：中文角色名大多是 2~3 个字，散列值是
 * `s0*31^(n-1) + s1*31 + …` 这样的线性组合——**只差一两个字的两个名字，散列值也只差一点点**，
 * 高位几乎相同。于是按位段切出来的音高/语速会成对撞车（实测 8 个名字就撞了一对）。
 * 雪崩混合把低位的差异扩散到全部 32 位，撞车概率随之降到可忽略。
 *
 * 混合只依赖入参，所以**确定性不变**：同一名字在任何平台、任何会话都得到同一条声线。
 *
 * 同时被神经 TTS 的说话人分配复用（[neuralSpeakerId]），确保两条引擎路径对「谁是谁」的判断一致。
 */
internal fun avalanche(value: Int): Int {
    var h = value
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    h *= 0x846ca68b.toInt() // 该常量超过 Int.MAX_VALUE，必须显式转回 Int
    h = h xor (h ushr 16)
    return h
}

/**
 * 最终声线：**作者填的优先**，没填的用 [derivedVoiceProfile] 派生。
 *
 * 让「填了」是绝对覆盖而不是叠加：作者写 `voice_pitch = 1.2` 就是 1.2，
 * 不该再被派生值二次加减（否则同一个数字在不同角色身上效果不同，没法调）。
 */
fun resolveVoiceProfile(
    characterName: String,
    authored: AuthoredVoice = AuthoredVoice(),
): VoiceProfile {
    val derived = derivedVoiceProfile(characterName)
    return VoiceProfile(
        pitch = authored.pitch ?: derived.pitch,
        speed = authored.speed ?: derived.speed,
        volume = authored.volume ?: derived.volume,
        voiceName = authored.voiceName.ifBlank { derived.voiceName },
    ).normalized()
}

/**
 * 把「情绪 + 标点」作为韵律施加到声线上，得到这一句实际使用的参数。
 *
 * ## 情绪
 * 每条情绪给出方向明确的偏移：愤怒更快更响、悲伤更慢更低更轻、惊讶音高抬高……
 * 偏移量刻意**小**（0.02~0.15）——大幅摆动会听出「变声怪」，反而更假。
 *
 * ## 标点
 * 标点是文本里现成的韵律信息，用它能立刻摆脱「一口气平读完」：
 * 叹号提语速与音量、问号抬音高、省略号放慢收尾、句号轻微收束。
 * 连续叹号只按一次计（避免「！！！」被放大成喊叫）。
 *
 * ## 两者的关系
 * 情绪偏移与标点偏移**相加**后再统一收进安全区间。不做乘法：
 * 乘会让原本快的角色在被叠加后冲出区间，而收边距会把不同角色压成同一个值。
 */
fun VoiceProfile.deliver(
    text: String,
    emotion: SpeechEmotion? = null,
): VoiceProfile {
    var pitch = this.pitch
    var speed = this.speed
    var volume = this.volume

    when (emotion) {
        SpeechEmotion.JOY -> { pitch += 0.06f; speed += 0.08f; volume += 0.05f }
        SpeechEmotion.ANGER -> { pitch += 0.04f; speed += 0.10f; volume += 0.15f }
        SpeechEmotion.SORROW -> { pitch -= 0.06f; speed -= 0.12f; volume -= 0.10f }
        SpeechEmotion.SURPRISE -> { pitch += 0.10f; speed += 0.05f; volume += 0.05f }
        SpeechEmotion.TENSE -> { pitch += 0.03f; speed += 0.06f; volume -= 0.05f }
        SpeechEmotion.CALM -> { pitch -= 0.02f; speed -= 0.05f; volume -= 0.02f }
        null -> Unit
    }

    val trimmed = text.trimEnd()
    if (trimmed.any { it == '！' || it == '!' }) { speed += 0.04f; volume += 0.05f }
    if (trimmed.any { it == '？' || it == '?' }) pitch += 0.03f
    val ending = trimmed.takeLast(3)
    if (ending.contains('…') || ending.contains("...")) speed -= 0.08f
    else if (trimmed.endsWith('。') || trimmed.endsWith('.')) speed -= 0.03f

    return copy(pitch = pitch, speed = speed, volume = volume).normalized()
}
