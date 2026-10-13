package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 人物名清洗回归测试。
 *
 * 线上问题：模型提取主角时返回 `["\"李慧英\"", "\"陆城\""]`（JSON 字符串里再套一层引号），
 * 名字带引号存进 run manifest → 会话参与者带引号 → 模型输出的干净名字匹配不上
 * → 整轮回复被过滤成「没有可用回复」。这里锁住清洗规则。
 */
class CharacterNameNormalizationTest {
    @Test
    fun `wrapping quotes and brackets are stripped`() {
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("\"李慧英\""))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("\"李慧英\""))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("「李慧英」"))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("『李慧英』"))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("【李慧英】"))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("《李慧英》"))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("'李慧英'"))
        assertEquals("李慧英", PersonaProfileNormalizer.normalizeCharacterName("`李慧英`"))
    }

    @Test
    fun `list numbering prefix is removed`() {
        assertEquals("林黛玉", PersonaProfileNormalizer.normalizeCharacterName("1. 林黛玉"))
        assertEquals("林黛玉", PersonaProfileNormalizer.normalizeCharacterName("2、林黛玉"))
        assertEquals("林黛玉", PersonaProfileNormalizer.normalizeCharacterName("3) 林黛玉"))
    }

    @Test
    fun `batch normalization dedupes quoted and clean spellings`() {
        val normalized = PersonaProfileNormalizer.normalizeCharacterNames(
            listOf("\"李慧英\"", "李慧英", "「陆城」", "  ", "\"\"", "陆城")
        )
        assertEquals(listOf("李慧英", "陆城"), normalized)
    }

    @Test
    fun `batch normalization keeps inner characters intact`() {
        assertEquals(
            listOf("欧阳雪", "AI·小助手"),
            PersonaProfileNormalizer.normalizeCharacterNames(listOf("欧阳雪", "\"AI·小助手\"")),
        )
    }

    @Test
    fun `implausible names are rejected`() {
        assertFalse(PersonaProfileNormalizer.isPlausibleCharacterName("\"\""))
        assertFalse(PersonaProfileNormalizer.isPlausibleCharacterName("主角：林黛玉"))
        assertFalse(PersonaProfileNormalizer.isPlausibleCharacterName("人物"))
        assertFalse(PersonaProfileNormalizer.isPlausibleCharacterName("这个角色的名字特别长特别长特别长特别长"))
        assertTrue(PersonaProfileNormalizer.isPlausibleCharacterName("\"李慧英\""))
    }

    @Test
    fun `paired chinese quotes are stripped`() {
        assertEquals("李慧英", CharacterNameNormalizer.normalize("“李慧英”"))
        assertEquals("李慧英", CharacterNameNormalizer.normalize("‘李慧英’"))
        assertEquals("李慧英", CharacterNameNormalizer.normalize("「李慧英」"))
        assertEquals("李慧英", CharacterNameNormalizer.normalize("『李慧英』"))
    }

    @Test
    fun `paired quotes keep inner quotes untouched`() {
        assertEquals("陆\"城", CharacterNameNormalizer.normalize("\"陆\"城\""))
        assertEquals("陆‘城", CharacterNameNormalizer.normalize("“陆‘城”"))
        assertEquals("O'Brien", CharacterNameNormalizer.normalize("'O'Brien'"))
    }

    @Test
    fun `single sided quotes are not stripped`() {
        assertEquals("\"陆城", CharacterNameNormalizer.normalize("\"陆城"))
        assertEquals("陆城\"", CharacterNameNormalizer.normalize("陆城\""))
        assertEquals("“陆城", CharacterNameNormalizer.normalize("“陆城"))
    }

    @Test
    fun `blank and quote only names normalize to empty`() {
        assertEquals("", CharacterNameNormalizer.normalize("   "))
        assertEquals("", CharacterNameNormalizer.normalize(""))
        assertEquals("", CharacterNameNormalizer.normalize("\"\""))
        assertEquals("", CharacterNameNormalizer.normalize("  \"\"  "))
        assertEquals("", CharacterNameNormalizer.normalize("「」"))
        assertTrue(CharacterNameNormalizer.normalizeNames(listOf(" ", "\"\"", "陆城")).contains("陆城"))
    }

    @Test
    fun `quoted and clean spelling share one identity`() {
        assertEquals(
            CharacterNameNormalizer.identityKey("陆城"),
            CharacterNameNormalizer.identityKey("\"陆城\""),
        )
        assertEquals(
            CharacterNameNormalizer.identityKey(" 陆 城 "),
            CharacterNameNormalizer.identityKey("陆城"),
        )
        assertFalse(
            CharacterNameNormalizer.identityKey("陆城") == CharacterNameNormalizer.identityKey("李慧英"),
        )
    }

    @Test
    fun `list normalization dedupes quoted and clean spellings`() {
        assertEquals(
            listOf("陆城", "李慧英"),
            CharacterNameNormalizer.normalizeNames(listOf("\"陆城\"", "陆城", " 李慧英 ", "「」", "")),
        )
    }

    @Test
    fun `list fragments from comma split json arrays are cleaned`() {
        // 模型把 JSON 数组当字符串输出、代码按逗号切分后的碎片
        assertEquals("李慧英", CharacterNameNormalizer.normalizeListFragment("[\"李慧英\""))
        assertEquals("陆城", CharacterNameNormalizer.normalizeListFragment("\"陆城\"]"))
        assertEquals("李慧英", CharacterNameNormalizer.normalizeListFragment("『李慧英』"))
        // 注意：通用归一化仍然只剥成对引号，单侧引号不会被额外剥掉
        assertEquals("\"陆城", CharacterNameNormalizer.normalize("\"陆城"))
        assertEquals("陆城", CharacterNameNormalizer.normalizeListFragment("\"陆城"))
    }
}
