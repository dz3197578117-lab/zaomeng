package top.wkbin.zaomeng.feature.persona

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaVoiceCastTest {

    @Test
    fun `roster is normalized and current persona is always included`() {
        // 排序按 Kotlin String 的自然序（UTF-16 码位）：林 U+6797 < 薛 U+859B < 贾 U+8D3E。
        // 这里断言的是「稳定且去重」，不是语言学意义上的拼音顺序——
        // 顺序只用于让 assignNeuralSpeakers 的探测结果与出场顺序无关。
        assertEquals(
            listOf("林黛玉", "薛宝钗", "贾宝玉"),
            normalizeVoiceCastNames(listOf(" 林黛玉 ", "贾宝玉", "林黛玉", " "), "薛宝钗"),
        )
    }

    @Test
    fun `duplicate spellings collapse and blank names are dropped`() {
        val cast = normalizeVoiceCastNames(
            listOf("陆城", " 陆城 ", "陆城", "", "   ", "李慧英"),
            "陆城",
        )
        assertEquals(listOf("李慧英", "陆城"), cast)
        assertEquals(cast.size, cast.distinct().size, "名单里不该有重复")
    }

    @Test
    fun `failed or empty roster still gives current persona a stable cast`() {
        assertEquals(listOf("王熙凤"), normalizeVoiceCastNames(emptyList(), " 王熙凤 "))
    }
}