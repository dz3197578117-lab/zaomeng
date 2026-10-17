package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 音色库的纯逻辑测试。
 *
 * 不碰 `TextToSpeech`（单元测试里没有真实引擎），只验证「用户看到什么、按什么顺序看到」——
 * 这两件事决定了海量音色列表是否可用：顺序乱、搜不到，等于没有音色库。
 */
class SystemTtsVoicesTest {

    private val voices = listOf(
        SystemTtsVoice(name = "en-US-AriaNeural", locale = "en-US", quality = 400),
        SystemTtsVoice(name = "zh-CN-yunxi", locale = "zh-CN", quality = 400),
        SystemTtsVoice(name = "zh-CN-xiaobei", locale = "zh-CN", quality = 400),
        SystemTtsVoice(name = "ja-JP-Nanami", locale = "ja-JP", quality = 300),
        SystemTtsVoice(name = "zh-TW-HsiaoChen", locale = "zh-TW", quality = 300),
    )

    @Test
    fun `chinese voices come first and stay stably sorted`() {
        val ordered = orderSystemTtsVoices(voices)
        val locales = ordered.map { it.locale }
        assertEquals(listOf("zh-CN", "zh-CN", "zh-TW", "en-US", "ja-JP"), locales)
        assertEquals(listOf("zh-CN-xiaobei", "zh-CN-yunxi"), ordered.take(2).map { it.name })
    }

    @Test
    fun `search matches voice name label and locale`() {
        assertEquals(
            listOf("zh-CN-yunxi"),
            orderSystemTtsVoices(voices, "yunxi").map { it.name },
        )
        assertEquals(
            listOf("zh-CN-xiaobei", "zh-CN-yunxi", "zh-TW-HsiaoChen"),
            orderSystemTtsVoices(voices, "ZH").map { it.name },
        )
    }

    @Test
    fun `blank query keeps every voice`() {
        assertEquals(voices.size, orderSystemTtsVoices(voices, "   ").size)
    }

    @Test
    fun `label strips the engine prefix but keeps plain names intact`() {
        assertEquals("zh-CN-xiaobei", SystemTtsVoice("neko:zh-CN-xiaobei", "zh-CN").label)
        assertEquals("zh-CN-xiaobei", SystemTtsVoice("zh-CN-xiaobei", "zh-CN").label)
        // 名字本身就是「前缀:主体」时，只去掉前缀，主体必须完整保留。
        assertEquals("b", SystemTtsVoice("a:b", "zh-CN").label)
        // 没有分隔符时原样返回，不能因为「看起来像前缀」就被截断。
        assertEquals("yunjian", SystemTtsVoice("yunjian", "zh-CN").label)
    }

    @Test
    fun `chinese locale label distinguishes simplified and traditional`() {
        assertEquals("中文", SystemTtsVoice("a", "zh-CN").localeLabel)
        assertEquals("中文（繁体）", SystemTtsVoice("a", "zh-TW").localeLabel)
        assertEquals("英语", SystemTtsVoice("a", "en-GB").localeLabel)
    }

    @Test
    fun `network requirement is surfaced so offline users are warned`() {
        val online = SystemTtsVoice("cloud-voice", "zh-CN", requiresNetwork = true)
        assertTrue(online.requiresNetwork)
        // 排序不受影响：仍需出现在中文音色里。
        assertEquals(1, orderSystemTtsVoices(listOf(online, voices.first())).count { it.isChinese })
    }
}
