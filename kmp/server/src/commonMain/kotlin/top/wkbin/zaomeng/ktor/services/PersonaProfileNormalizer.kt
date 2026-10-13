package top.wkbin.zaomeng.ktor.services

internal data class PersonaProfileNormalization(
    val markdown: String,
    val clearedFields: Set<String>,
    val removedEvidenceCount: Int,
)

/** Deterministically enforces profile output rules that models occasionally ignore. */
internal object PersonaProfileNormalizer {
    private val EVIDENCE_ID = Regex("S\\d{6}")
    private val PLACEHOLDERS = setOf(
        "留空", "空白", "不详", "信息不足", "暂无", "未知", "资料不足", "证据不足", "待补充",
        "无证据", "原文未提及", "未提及", "无相关信息", "null", "none", "n/a", "na", "insufficient",
        "-", "—", "…", "...",
    )

    fun normalize(
        markdown: String,
        allowedEvidenceIds: Set<String>,
        maxEvidenceIds: Int,
    ): PersonaProfileNormalization {
        val clearedFields = linkedSetOf<String>()
        var removedEvidenceCount = 0
        val normalized = markdown.lines().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("- ") || !line.contains(":")) return@joinToString rawLine
            val (rawKey, rawValue) = line.removePrefix("- ").split(":", limit = 2)
            val key = rawKey.trim()
            if (key.isEmpty()) return@joinToString rawLine
            val value = rawValue.trim()
            val normalizedValue = when {
                key == "evidence_source" -> {
                    val referenced = EVIDENCE_ID.findAll(value).map { it.value }.toList()
                    val retained = referenced.asSequence()
                        .filter(allowedEvidenceIds::contains)
                        .distinct()
                        .take(maxEvidenceIds)
                        .toList()
                    removedEvidenceCount += referenced.size - retained.size
                    retained.joinToString("；")
                }
                isPlaceholder(value) -> {
                    clearedFields += key
                    ""
                }
                else -> value
            }
            val indentation = rawLine.takeWhile(Char::isWhitespace)
            "$indentation- $key:${if (normalizedValue.isEmpty()) "" else " $normalizedValue"}"
        }.trim()
        return PersonaProfileNormalization(normalized, clearedFields, removedEvidenceCount)
    }

    fun normalizeFieldValue(value: String): String = value.trim().takeUnless(::isPlaceholder).orEmpty()

    /**
     * 清洗人物名：剥掉**首尾成对**的包裹引号/括号、去掉「1. 」这类序号前缀。
     *
     * 所有「人物名进入系统」的入口都要过这里：模型提取主角时经常返回
     * `["\"李慧英\"", "\"陆城\""]` 这种字符串里再套引号的 JSON 元素，
     * 名字带引号存进 run manifest 后，会话参与者也就带引号，
     * 而模型输出的是干净名字 → speaker 匹配失败 → 整轮回复被过滤成空。
     *
     * 规则收敛在 [CharacterNameNormalizer]：只剥首尾成对的引号，单侧引号与名字中间的引号保留。
     */
    fun normalizeCharacterName(raw: String): String = CharacterNameNormalizer.normalize(raw)

    /** 名字是否可作为角色标识：非空、长度合理、不含解释性文字。 */
    fun isPlausibleCharacterName(name: String): Boolean {
        val clean = normalizeCharacterName(name)
        return clean.isNotEmpty() && clean.length in 1..20 &&
            !clean.contains("：") && !clean.contains(":") &&
            !clean.contains("主角") && !clean.contains("人物") && !clean.contains("角色")
    }

    /** 批量清洗 + 去重 + 限量，供人物名单入口统一使用。 */
    fun normalizeCharacterNames(names: List<String>, limit: Int = Int.MAX_VALUE): List<String> =
        CharacterNameNormalizer.normalizeNames(names)
            .filter { it.length <= 20 }
            .take(limit)

    fun isPlaceholder(value: String): Boolean {
        val normalized = value.trim()
            .trim('`', '"', '\'', '“', '”', '‘', '’', '[', ']', '（', '）', '(', ')')
            .trim()
            .lowercase()
        return normalized in PLACEHOLDERS ||
            (normalized.startsWith("留空") && normalized.length <= 12)
    }
}
