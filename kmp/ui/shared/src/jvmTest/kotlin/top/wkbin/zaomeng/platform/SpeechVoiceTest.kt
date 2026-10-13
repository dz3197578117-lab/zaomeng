package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 声线派生与情绪韵律的回归测试。
 *
 * 起因：聊天页朗读**没有传任何声线参数**，所有角色共用默认值——「每个人物声音一样」。
 * 这些用例钉住三件事：① 同一角色任何时候都拿到同一条声线；② 不同角色确实拿到不同声线；
 * ③ 情绪与标点只在安全区间内做**小幅**调整（大幅摆动会听出「变声怪」，比不做更假）。
 */
class SpeechVoiceTest {

    // ------------------------------------------------------------------
    // 确定性：声线是角色身份的一部分，不能每次变
    // ------------------------------------------------------------------

    @Test
    fun `same character always gets the same voice`() {
        val first = derivedVoiceProfile("林黛玉")
        val second = derivedVoiceProfile("林黛玉")
        assertEquals(first, second)
        // 与「同一次运行内缓存」无关：这里连调多次也必须一致
        repeat(5) { assertEquals(first, derivedVoiceProfile("林黛玉")) }
    }

    @Test
    fun `character name is trimmed before derivation`() {
        assertEquals(derivedVoiceProfile("林黛玉"), derivedVoiceProfile("  林黛玉  "))
    }

    @Test
    fun `empty name falls back to the neutral profile`() {
        assertEquals(VoiceProfile(), derivedVoiceProfile(""))
        assertEquals(VoiceProfile(), derivedVoiceProfile("   "))
    }

    // ------------------------------------------------------------------
    // 不同角色必须真的不同
    // ------------------------------------------------------------------

    @Test
    fun `different characters get different voices`() {
        val names = listOf("林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "李慕白", "陆城", "苏晚", "顾青")
        val profiles = names.map { it to derivedVoiceProfile(it) }
        val distinct = profiles.map { it.second.pitch to it.second.speed }.toSet()
        assertEquals(
            names.size,
            distinct.size,
            "角色声线撞车了：$profiles —— 需要调整派生用的位段",
        )
    }

    @Test
    fun `the historically colliding pair now differs`() {
        // 定点回归：这两条声线曾经**完全相同**（pitch=1.16, speed=0.895, volume=0.878）。
        // 原因是中文短名字的 hashCode 高位区分度差，而当时只切 5 位。
        // 如果谁把 avalanche 混合去掉，这条会立刻失败。
        val first = derivedVoiceProfile("李慕白")
        val second = derivedVoiceProfile("顾青")
        assertNotEquals(first, second, "又撞回原样了：$first")
    }

    @Test
    fun `derived voice stays inside the safe band`() {
        val names = listOf("林黛玉", "贾宝玉", "A", "B", "很长很长的角色名字用来试探边界", "🙂", "1")
        for (name in names) {
            val profile = derivedVoiceProfile(name)
            assertTrue(profile.pitch in VoiceProfile.MIN_PITCH..VoiceProfile.MAX_PITCH, "$name pitch=${profile.pitch}")
            assertTrue(profile.speed in VoiceProfile.MIN_SPEED..VoiceProfile.MAX_SPEED, "$name speed=${profile.speed}")
            assertTrue(profile.volume in VoiceProfile.MIN_VOLUME..VoiceProfile.MAX_VOLUME, "$name volume=${profile.volume}")
        }
    }

    @Test
    fun `pitch and speed vary independently across characters`() {
        // 分开取位的目的：避免所有角色「一起变高变快」，那样反而更像同一个人。
        // 判据：存在一对角色，音高更高但语速更低（排序不同向）。
        val profiles = listOf("林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "李慕白", "陆城", "苏晚", "顾青")
            .map { derivedVoiceProfile(it) }
        val inverted = profiles.indices.any { i ->
            profiles.indices.any { j ->
                i < j && (profiles[i].pitch - profiles[j].pitch) * (profiles[i].speed - profiles[j].speed) < 0f
            }
        }
        assertTrue(inverted, "音高与语速完全同向，说明两段位取重了：$profiles")
    }

    // ------------------------------------------------------------------
    // 作者填的优先，且是绝对覆盖
    // ------------------------------------------------------------------

    @Test
    fun `authored values override derived ones absolutely`() {
        val resolved = resolveVoiceProfile("林黛玉", AuthoredVoice(pitch = 1.2f, speed = 0.9f))
        assertEquals(1.2f, resolved.pitch)
        assertEquals(0.9f, resolved.speed)
    }

    @Test
    fun `unspecified authored fields fall back to derived values`() {
        val derived = derivedVoiceProfile("林黛玉")
        val resolved = resolveVoiceProfile("林黛玉", AuthoredVoice(pitch = 1.2f))
        assertEquals(1.2f, resolved.pitch)
        assertEquals(derived.speed, resolved.speed)
        assertEquals(derived.volume, resolved.volume)
    }

    @Test
    fun `blank authored voice name keeps the derived one`() {
        assertEquals("", resolveVoiceProfile("林黛玉", AuthoredVoice(voiceName = "  ")).voiceName)
        assertEquals("zh-cn-x-y", resolveVoiceProfile("林黛玉", AuthoredVoice(voiceName = "zh-cn-x-y")).voiceName)
    }

    @Test
    fun `authored values are clamped into the safe band`() {
        val wild = resolveVoiceProfile(
            "林黛玉",
            AuthoredVoice(pitch = 9f, speed = 0.01f, volume = 5f),
        )
        assertEquals(VoiceProfile.MAX_PITCH, wild.pitch)
        assertEquals(VoiceProfile.MIN_SPEED, wild.speed)
        assertEquals(VoiceProfile.MAX_VOLUME, wild.volume)
    }

    // ------------------------------------------------------------------
    // 情绪 → 韵律（方向必须对）
    // ------------------------------------------------------------------

    @Test
    fun `no emotion and no punctuation leaves the voice untouched`() {
        val base = derivedVoiceProfile("林黛玉")
        assertEquals(base, base.deliver("今天天气不错"))
    }

    @Test
    fun `anger speeds up and gets louder`() {
        val base = derivedVoiceProfile("林黛玉")
        val angry = base.deliver("你再说一遍", SpeechEmotion.ANGER)
        assertTrue(angry.speed > base.speed, "愤怒应更快")
        assertTrue(angry.volume > base.volume, "愤怒应更响")
    }

    @Test
    fun `sorrow slows down and softens`() {
        val base = derivedVoiceProfile("林黛玉")
        val sad = base.deliver("我终究是明白了", SpeechEmotion.SORROW)
        assertTrue(sad.speed < base.speed, "悲伤应更慢")
        assertTrue(sad.pitch < base.pitch, "悲伤应更低")
        assertTrue(sad.volume < base.volume, "悲伤应更轻")
    }

    @Test
    fun `surprise raises the pitch`() {
        val base = derivedVoiceProfile("林黛玉")
        assertTrue(base.deliver("竟然是你", SpeechEmotion.SURPRISE).pitch > base.pitch)
    }

    @Test
    fun `calm is gentler than the base voice`() {
        val base = derivedVoiceProfile("林黛玉")
        val calm = base.deliver("都过去了", SpeechEmotion.CALM)
        assertTrue(calm.speed < base.speed)
        assertTrue(calm.pitch < base.pitch)
    }

    // ------------------------------------------------------------------
    // 标点 → 韵律
    // ------------------------------------------------------------------

    @Test
    fun `exclamation speeds up and gets louder`() {
        val base = derivedVoiceProfile("林黛玉")
        val shouted = base.deliver("快走！")
        assertTrue(shouted.speed > base.speed)
        assertTrue(shouted.volume > base.volume)
    }

    @Test
    fun `repeated exclamation marks are not amplified`() {
        val base = derivedVoiceProfile("林黛玉")
        assertEquals(base.deliver("快走！"), base.deliver("快走！！！"))
        // 与问号同时出现时，叹号仍只按一次计（问号另算音高）
        val combined = base.deliver("快走!?")
        assertEquals(base.deliver("快走！").speed, combined.speed)
        assertEquals(base.deliver("快走！").volume, combined.volume)
    }

    @Test
    fun `question mark raises the pitch`() {
        val base = derivedVoiceProfile("林黛玉")
        assertTrue(base.deliver("为什么？").pitch > base.pitch)
    }

    @Test
    fun `ellipsis slows down more than a full stop`() {
        val base = derivedVoiceProfile("林黛玉")
        val trailing = base.deliver("原来是这样……")
        val period = base.deliver("原来是这样。")
        assertTrue(trailing.speed < base.speed, "省略号应收得更慢")
        assertTrue(trailing.speed < period.speed, "省略号应比句号更慢")
        assertTrue(period.speed < base.speed, "句号应有轻微收束")
    }

    @Test
    fun `punctuation is only read from the tail for sentence endings`() {
        val base = derivedVoiceProfile("林黛玉")
        // 句末是句号时，即使中间有省略号也按句末算
        val mixed = base.deliver("他说……算了。")
        assertTrue(mixed.speed > base.deliver("他说……算了……").speed)
    }

    @Test
    fun `emotion and punctuation stack but stay in the safe band`() {
        // 挑一个本来又快又高的角色，再叠愤怒 + 叹号，必须仍在区间内
        val fast = VoiceProfile(pitch = 1.55f, speed = 1.75f, volume = 1f)
        val delivered = fast.deliver("你给我站住！", SpeechEmotion.ANGER)
        assertTrue(delivered.pitch <= VoiceProfile.MAX_PITCH, "pitch=${delivered.pitch}")
        assertTrue(delivered.speed <= VoiceProfile.MAX_SPEED, "speed=${delivered.speed}")
        assertTrue(delivered.volume <= VoiceProfile.MAX_VOLUME, "volume=${delivered.volume}")
        // 音量已经被压在 1.0，不该出现「更响」的假象
        assertEquals(VoiceProfile.MAX_VOLUME, delivered.volume)
    }

    @Test
    fun `voice name survives prosody`() {
        val profile = VoiceProfile(pitch = 1f, speed = 1f, voiceName = "zh-cn-x-y")
        assertEquals("zh-cn-x-y", profile.deliver("你好！", SpeechEmotion.JOY).voiceName)
    }

    // ------------------------------------------------------------------
    // 情绪识别：迁移自聊天页的旧实现，行为必须逐句一致
    // ------------------------------------------------------------------

    @Test
    fun `classification matches the previous chat implementation`() {
        // 这四句 + 两个空结果原样来自 EmotionIndicatorTest，迁移后不能变
        assertEquals(SpeechEmotion.JOY, classifySpeechEmotion("她忍不住开心地笑了"))
        assertEquals(SpeechEmotion.ANGER, classifySpeechEmotion("压住心里的愤怒"))
        assertEquals(SpeechEmotion.TENSE, classifySpeechEmotion("他感到有些紧张"))
        assertEquals(SpeechEmotion.CALM, classifySpeechEmotion("心情终于平静下来"))
        assertEquals(null, classifySpeechEmotion(""))
        assertEquals(null, classifySpeechEmotion("他想起了昨日的约定"))
    }

    @Test
    fun `classification is order sensitive and case insensitive`() {
        // 顺序即优先级：JOY 排在 SORROW 之前，所以两者都命中时取 JOY（与旧实现同序）
        assertEquals(SpeechEmotion.JOY, classifySpeechEmotion("笑着，却忍不住哭泣"))
        assertEquals(SpeechEmotion.SORROW, classifySpeechEmotion("SAD"))
        assertEquals(null, classifySpeechEmotion(null))
    }

    // ------------------------------------------------------------------
    // 人物包字段 → 声线（必须容错：字段来自模型蒸馏与人工编辑，格式不可控）
    // ------------------------------------------------------------------

    @Test
    fun `authored fields are read from the persona map`() {
        val voice = mapOf(
            "voice_name" to "zh-cn-x-y",
            "voice_pitch" to "1.2",
            "voice_speed" to "0.9",
            "voice_volume" to "0.8",
        ).toAuthoredVoice()
        assertEquals("zh-cn-x-y", voice.voiceName)
        assertEquals(1.2f, voice.pitch)
        assertEquals(0.9f, voice.speed)
        assertEquals(0.8f, voice.volume)
    }

    @Test
    fun `missing or blank fields fall back to derived values`() {
        val derived = derivedVoiceProfile("顾青")
        val resolved = mapOf(
            "voice_name" to "",
            "voice_pitch" to "   ",
        ).toAuthoredVoice().let { resolveVoiceProfile("顾青", it) }
        assertEquals(derived.pitch, resolved.pitch)
        assertEquals(derived.speed, resolved.speed)
        assertEquals(derived.volume, resolved.volume)
        assertEquals("", resolved.voiceName)
    }

    @Test
    fun `unparseable field values are treated as not set instead of breaking playback`() {
        // 「稍快」「1.2 倍」这类内容不能转数字。刻意不去猜它的意思：
        // 猜错会让角色声音不对劲且难以解释，回落到派生值反而稳定。
        for (raw in listOf("稍快", "1.2 倍", "fast", "很快", "--", "NaN")) {
            val voice = mapOf("voice_pitch" to raw).toAuthoredVoice()
            assertEquals(null, voice.pitch, "「$raw」不该被解析成数字")
        }
    }

    @Test
    fun `full width digits and surrounding spaces are accepted`() {
        val voice = mapOf("voice_pitch" to " １．２ ", "voice_speed" to " 1.15 ").toAuthoredVoice()
        assertEquals(1.2f, voice.pitch)
        assertEquals(1.15f, voice.speed)
    }

    @Test
    fun `non positive or infinite values are ignored`() {
        for (raw in listOf("0", "0.0", "-1", "Infinity")) {
            assertEquals(null, mapOf("voice_speed" to raw).toAuthoredVoice().speed, "「$raw」不该生效")
        }
    }

    @Test
    fun `authored voice from a persona map beats the derived voice`() {
        // 用 1.55：派生值的上界是 1.42，所以它**必然**不等于派生值（换成 1.3 会有 1/1024 的假失败）
        val derived = derivedVoiceProfile("顾青")
        val resolved = resolveVoiceProfile("顾青", mapOf("voice_pitch" to "1.55").toAuthoredVoice())
        assertEquals(1.55f, resolved.pitch)
        assertTrue(derived.pitch < 1.55f, "派生音高的上界应低于 1.55，实际 ${derived.pitch}")
        assertEquals(derived.speed, resolved.speed)
    }
}
