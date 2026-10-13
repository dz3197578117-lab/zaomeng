package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.wkbin.zaomeng.ktor.services.TURNS_PER_PERSONA_CHAPTER

/**
 * 纯逻辑判定：功能自检里所有**不需要 IO** 的结论都集中在这里。
 *
 * 分成独立函数的理由：
 * - 单测可以直接喂构造出来的 JsonObject/manifest，不依赖真机数据；
 * - 探针类只剩「读数据 → 调用判定 → 返回结果」，判定口径不会散落在十几个地方；
 * - 判定函数**只读入参**，没有任何写路径，天然满足「探针只读」。
 *
 * 约定：`assessXxx(...)` 返回 [ProbeAssessment]（状态 + 一句话结论 + 证据 + 细节）。
 */

/** 判定结果。 */
data class ProbeAssessment(
    val status: ProbeStatus,
    val summary: String,
    val evidence: Map<String, String> = emptyMap(),
    val details: List<String> = emptyList(),
) {
    fun toResult(): ProbeResult = ProbeResult(
        status = status,
        summary = summary,
        evidence = ProbeEvidence.build {
            evidence.forEach { (key, value) -> text(key, value) }
        },
        details = details,
    )
}

/** 构造证据表（键值都是短文本）。 */
internal fun evidenceMap(vararg pairs: Pair<String, String>): Map<String, String> = linkedMapOf(*pairs)

/** 构造证据表，值可为 null（null 会被跳过）。 */
internal fun evidenceMapOf(vararg pairs: Pair<String, String?>): Map<String, String> {
    val map = linkedMapOf<String, String>()
    pairs.forEach { (key, value) -> value?.let { map[key] = it } }
    return map
}

/** 概率用的百分比渲染（0..1 → "82%"）。 */
internal fun ratioText(numerator: Int, denominator: Int): String =
    if (denominator <= 0) "n/a" else "${(numerator * 100 / denominator)}%"

// ======================================================================
// 人设弧线
// ======================================================================

/** 弧线里的一条章条目（从 arc.json 的 `chapters[]` 抽出来的最小信息）。 */
data class ArcChapterEntry(
    val chapter: Int,
    val status: String,
    val hasState: Boolean,
    val truncated: Boolean,
)

/** 从 arc.json 抽条目；非法项跳过。 */
fun extractArcChapters(arc: JsonObject?): List<ArcChapterEntry> =
    arc?.arr("chapters")?.objects().orEmpty().mapNotNull { entry ->
        val chapter = entry.intOrNull("chapter") ?: return@mapNotNull null
        val state = entry.obj("state")
        ArcChapterEntry(
            chapter = chapter,
            status = entry.str("status"),
            hasState = state != null && state.isNotEmpty(),
            truncated = entry.str("source_truncated").equals("true", ignoreCase = true),
        )
    }

/**
 * 弧线覆盖率与注入判定（探针 6、7 的纯逻辑）。
 *
 * @param declaredChapterCount arc.json 里的 `chapter_count`（用于与条目数对账）
 * @param entries 弧线条目
 * @param plannedChapters 章节索引里实际存在的章号（用于判断 `chapter_count` 是否与索引吻合）
 * @param personaChapter 当前会话时间点（`persona_chapter`）；<=0 表示没有会话上下文
 *
 * 判定规则：
 * - 弧线缺失（[entries] 为空）→ NO_DATA（这本书还没生成弧线，不是坏了）
 * - `chapter_count` 与条目数不一致 → WARN（索引/落盘对不上）
 * - 有 `status == "failed"` → WARN；`failed` 占多数 → FAIL
 * - 覆盖率 = ok 条目 / 计划章数；< 50% → WARN
 * - 给定 [personaChapter] 时，检查该时间点**覆盖到的那一段**是否真有 state（真的会被注入）；
 *   覆盖段是 empty/failed（没有 state）→ FAIL：运行时只会退回更早的一段，
 *   等于这个时间点的人设没被注入，却看起来一切正常。
 * - `source_truncated` 计数一并作为证据（截断会让弧线基于半章正文）
 */
fun assessPersonaArc(
    declaredChapterCount: Int,
    entries: List<ArcChapterEntry>,
    plannedChapters: Set<Int> = emptySet(),
    personaChapter: Int = 0,
): ProbeAssessment {
    if (entries.isEmpty()) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "这本书卷还没有弧线（尚未生成或旧书卷）。",
            evidence = evidenceMap("entries" to "0", "declared" to declaredChapterCount.toString()),
        )
    }
    val ok = entries.count { it.status == "ok" }
    val failed = entries.count { it.status == "failed" }
    val empty = entries.count { it.status == "empty" }
    val truncated = entries.count { it.truncated }
    val planned = if (plannedChapters.isNotEmpty()) plannedChapters.size else entries.size
    val coverage = if (planned <= 0) 0 else ok * 100 / planned

    // 注入判定：当前时间点**覆盖到的那一段**弧线（章号 <= personaChapter 的最后一条）。
    //
    // 这里刻意**不预先过滤 status / hasState**：运行时 arcStateForChapter() 只认 status == "ok"
    // 的条目，当前时间点那一段若是 empty/failed，它会静默退回更早的一段甚至最早的一段——
    // 角色于是带着早期章节的人设继续说话，而用户看到的探针却是 ✅。
    // 「这个时间点的人设」因此实际没有注入成功，必须报 FAIL 而不是 WARN，
    // 并把覆盖到的是第几章、该段有没有 state 作为证据写出来。
    val covering = entries.filter { it.chapter <= personaChapter }.maxByOrNull { it.chapter }
        ?: entries.minByOrNull { it.chapter }
    val injectionOk = if (personaChapter <= 0) null else covering?.hasState

    val details = buildList {
        add("planned_chapters=$planned ok=$ok failed=$failed empty=$empty truncated=$truncated")
        if (declaredChapterCount != entries.size) {
            add("chapter_count=$declaredChapterCount 与条目数 ${entries.size} 不一致")
        }
        if (plannedChapters.isNotEmpty()) {
            val missing = plannedChapters.filterNot { planned -> entries.any { it.chapter == planned } }.sorted()
            if (missing.isNotEmpty()) add("章节索引里有、弧线里缺的章：${missing.take(20).joinToString(",")}")
        }
        if (personaChapter > 0) {
            add("当前时间点 chapter=$personaChapter，覆盖段=${covering?.chapter ?: "无"}，state=${covering?.hasState}")
        }
    }

    val baseEvidence = evidenceMapOf(
        "ok" to "$ok/$planned",
        "coverage" to "$coverage%",
        "failed" to failed.toString(),
        "empty" to empty.toString(),
        "truncated" to truncated.toString(),
        "chapter_count" to declaredChapterCount.toString(),
        "persona_chapter" to personaChapter.takeIf { it > 0 }?.toString(),
        "covering_chapter" to covering?.chapter?.toString(),
        "arc_state_injected" to injectionOk?.let { if (it) "yes" else "no" },
    )

    return when {
        injectionOk == false -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "当前时间点（第 $personaChapter 章）覆盖到第 ${covering?.chapter} 章，" +
                "但该段 arc_state 为空（status=${covering?.status}" +
                "${if (covering?.truncated == true) "、source_truncated=true" else ""}），" +
                "时间点人设实际没有被注入。",
            evidence = baseEvidence,
            details = details,
        )
        failed > 0 && failed >= ok -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "弧线多数章节生成失败（failed=$failed / ok=$ok）。",
            evidence = baseEvidence,
            details = details,
        )
        declaredChapterCount != entries.size -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "chapter_count=$declaredChapterCount 与弧线条目数 ${entries.size} 不一致（索引可能过期）。",
            evidence = baseEvidence,
            details = details,
        )
        failed > 0 || coverage < 50 -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "弧线覆盖率偏低（ok $ok/$planned，failed=$failed）。",
            evidence = baseEvidence,
            details = details,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "弧线覆盖 ok $ok/$planned，当前时间点有可用弧线段。",
            evidence = baseEvidence,
            details = details,
        )
    }
}

// ======================================================================
// 索引一致性（artifact_index vs 实际文件）
// ======================================================================

/** 索引里的一名角色。 */
data class IndexedCharacter(
    val name: String,
    val hasArc: Boolean,
    val arcCount: Int,
)

/** 从 run manifest 的 `artifact_index.characters[]` 抽索引项。 */
fun extractIndexedCharacters(manifest: JsonObject?): List<IndexedCharacter> =
    manifest?.obj("artifact_index")?.arr("characters")?.objects().orEmpty().map { entry ->
        IndexedCharacter(
            name = entry.str("name"),
            hasArc = entry.str("has_arc").equals("true", ignoreCase = true),
            arcCount = entry.intOrNull("arc_count") ?: 0,
        )
    }

/**
 * `artifact_index.characters[].has_arc/arc_count` 与实际 `arc.json` 是否一致。
 *
 * @param actualArcCounts 角色名 → 实际弧线章数（0 表示没有弧线文件）
 *
 * 不一致＝索引过期：界面上会看到「有弧线」但对话里取不到，或反过来。
 */
fun assessArcIndexConsistency(
    indexed: List<IndexedCharacter>,
    actualArcCounts: Map<String, Int>,
): ProbeAssessment {
    if (indexed.isEmpty()) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "书卷清单里没有 artifact_index.characters，无法对账（尚未整理完成？）。",
        )
    }
    val mismatches = indexed.filter { indexed ->
        val actual = actualArcCounts[indexed.name] ?: 0
        indexed.hasArc != (actual > 0) || indexed.arcCount != actual
    }
    val details = mismatches.take(20).map { character ->
        val actual = actualArcCounts[character.name] ?: 0
        "${character.name}：索引 has_arc=${character.hasArc} arc_count=${character.arcCount}，实际 arc_count=$actual"
    }
    val base = evidenceMap(
        "characters" to indexed.size.toString(),
        "mismatched" to mismatches.size.toString(),
    )
    return when {
        mismatches.isEmpty() -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "artifact_index 的弧线字段与磁盘一致（${indexed.size} 个角色）。",
            evidence = base,
        )
        mismatches.size == indexed.size && indexed.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "所有角色的 has_arc/arc_count 都与实际文件不一致，索引明显过期。",
            evidence = base,
            details = details,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "${mismatches.size}/${indexed.size} 个角色的弧线索引与实际文件不一致（索引过期）。",
            evidence = base,
            details = details,
        )
    }
}

// ======================================================================
// 人物档案完整性
// ======================================================================

/** 人物档案里常见的「占位/无证据」词。 */
val PLACEHOLDER_MARKERS: List<String> = listOf("未知", "证据不足", "暂无", "无法确定", "不确定", "待补充", "N/A", "n/a", "none")

/**
 * 一项「必需字段」：同一件事可以有多个可接受的键名。
 *
 * [keys] 里同时列**真实 schema 键**与历史别名，理由：
 * - 真实产物（`prompts/distill/output_schema.md` → `DistillExecutor.parseGeneratedMarkdown`）
 *   写出的键是 `core_identity` / `core_traits` / `temperament_type` / `speech_style`；
 * - 早期版本或手工编辑过的档案可能写成 `identity` / `personality`。
 *
 * 之前这里只认 `identity` / `personality`，于是**每一份真实档案都被报成「缺必需字段」**——
 * 探针误报比漏报更糟：用户被假警报折腾，真问题反而被淹没。
 */
data class ProfileRequiredField(
    /** 展示用的字段名（保持稳定，报告与既有测试都按它匹配）。 */
    val label: String,
    /** 可接受的键名（命中任意一个即视为有该字段）。 */
    val keys: Set<String>,
)

/** 档案完整性的最小必需字段（PROFILE.md front-matter 的键，含历史别名）。 */
val PROFILE_REQUIRED_FIELD_GROUPS: List<ProfileRequiredField> = listOf(
    ProfileRequiredField("name", setOf("name")),
    // 身份：真实键是 core_identity；identity_anchor 是「自我定位」，也足以撑住身份描述。
    ProfileRequiredField("identity", setOf("core_identity", "identity", "identity_anchor")),
    // 性格：真实键是 core_traits / temperament_type；personality 是旧写法。
    ProfileRequiredField("personality", setOf("core_traits", "temperament_type", "personality")),
    // 说话方式：真实键是 speech_style（output_schema 的「语言表达」一节）。
    ProfileRequiredField("speech_style", setOf("speech_style")),
)

/** 必需字段的展示名列表（既有调用方/测试仍按这个口径读）。 */
val PROFILE_REQUIRED_FIELDS: List<String> = PROFILE_REQUIRED_FIELD_GROUPS.map { it.label }

/** 缺失的必需字段标签（键名命中任意一个即视为存在）。 */
fun missingProfileFields(parsedKeys: Set<String>): List<String> =
    PROFILE_REQUIRED_FIELD_GROUPS.filterNot { field -> field.keys.any { it in parsedKeys } }.map { it.label }

/**
 * 判定一份 PROFILE.md 的完整性。
 *
 * @param relativePath 档案相对路径（只用于展示）
 * @param text 档案正文；null 表示文件读不到
 * @param parsedKeys 解析出的 front-matter 键（解析失败/无 front-matter 时为空且 [parsed] 为 false）
 */
fun assessProfileCompleteness(
    relativePath: String,
    text: String?,
    hasFrontMatter: Boolean,
    parsedKeys: Set<String>,
    warnings: List<String> = emptyList(),
): ProbeAssessment {
    if (text == null) {
        return ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "人物档案读不到：$relativePath",
            evidence = evidenceMap("file" to relativePath),
        )
    }
    if (!hasFrontMatter) {
        return ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "人物档案缺少 YAML front-matter，无法解析：$relativePath",
            evidence = evidenceMap("file" to relativePath, "chars" to text.length.toString()),
        )
    }
    val missing = missingProfileFields(parsedKeys)
    val placeholders = PLACEHOLDER_MARKERS.filter { text.contains(it) }
    val details = buildList {
        if (missing.isNotEmpty()) add("缺失字段：${missing.joinToString(", ")}")
        if (placeholders.isNotEmpty()) add("出现占位词：${placeholders.joinToString(", ")}")
        addAll(warnings)
    }
    val base = evidenceMapOf(
        "file" to relativePath,
        "fields" to "${PROFILE_REQUIRED_FIELDS.size - missing.size}/${PROFILE_REQUIRED_FIELDS.size}",
        "placeholders" to placeholders.size.toString(),
        "chars" to text.length.toString(),
    )
    return when {
        missing.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "人物档案缺必需字段：${missing.joinToString("/")}（$relativePath）",
            evidence = base,
            details = details,
        )
        placeholders.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "人物档案存在占位词（${placeholders.joinToString("/")}）：$relativePath",
            evidence = base,
            details = details,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "人物档案字段完整、无占位词：$relativePath",
            evidence = base,
        )
    }
}

/** 从 Markdown 文本里抽出 YAML front-matter（`---` 包裹的第一段）；没有返回 null。 */
fun extractFrontMatter(text: String): String? {
    val lines = text.lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return null
    return lines.drop(1).take(end).joinToString("\n")
}

/** 从 front-matter 文本里抽出顶层键名（只做缩进为 0 的 `key:` 行）。 */
fun extractFrontMatterKeys(frontMatter: String): Set<String> =
    frontMatter.lines()
        .mapNotNull { line ->
            if (line.isEmpty() || line.first() == ' ' || line.first() == '\t' || line.first() == '-') return@mapNotNull null
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            line.take(colon).trim().lowercase().takeIf { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '_' || char == '-' } }
        }
        .toSet()

// ======================================================================
// 章节索引
// ======================================================================

/**
 * 章节索引合法性：`start_char < end_char`、不越界、章数、是否与源文件签名一致。
 *
 * @param chapterCount 索引里的章数
 * @param intervals 每章的 (index, startChar, endChar)
 * @param sourceCharCount 原文长度；null 表示源文件读不到（不做越界判定）
 * @param cacheStale 索引缓存的源签名与当前源文件是否不一致
 */
fun assessChapterIndex(
    chapterCount: Int,
    intervals: List<Triple<Int, Int, Int>>,
    sourceCharCount: Int?,
    cacheStale: Boolean,
    declaredTruncated: Boolean,
): ProbeAssessment {
    if (chapterCount <= 0 || intervals.isEmpty()) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "这本书卷还没有章节索引（尚未分章）。",
        )
    }
    val invalid = intervals.filter { (_, start, end) -> start < 0 || end <= start }
    val outOfRange = sourceCharCount?.let { length ->
        intervals.filter { (_, _, end) -> end > length }
    }.orEmpty()
    val base = evidenceMapOf(
        "chapters" to chapterCount.toString(),
        "intervals" to intervals.size.toString(),
        "invalid" to invalid.size.toString(),
        "out_of_range" to outOfRange.size.toString(),
        "source_chars" to sourceCharCount?.toString(),
        "cache_stale" to cacheStale.toString(),
        "truncated" to declaredTruncated.toString(),
    )
    val details = buildList {
        invalid.take(10).forEach { (index, start, end) -> add("非法区间：#$index start=$start end=$end") }
        outOfRange.take(10).forEach { (index, _, end) -> add("越界区间：#$index end=$end > $sourceCharCount") }
    }
    return when {
        invalid.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "章节索引有 ${invalid.size} 段非法区间（start_char >= end_char）。",
            evidence = base,
            details = details,
        )
        outOfRange.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "章节索引有 ${outOfRange.size} 段越界（end_char 超过原文长度）。",
            evidence = base,
            details = details,
        )
        cacheStale -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "章节索引缓存与当前原文不一致（下次分章会重建）。",
            evidence = base,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "章节索引可用：$chapterCount 章，区间合法。",
            evidence = base,
        )
    }
}

// ======================================================================
// 关系图
// ======================================================================

/** 关系图里的一条关系（主体、对象）。 */
data class RelationPair(val left: String, val right: String)

/**
 * 从已解析的关系图 payload 里抽关系对。
 *
 * 键名形如 `甲_乙` / `甲→乙` / `甲|乙`（`parseRelationsMarkdown` 用 `## 甲_乙` 作小标题）。
 */
fun relationPairsFromPayload(payload: JsonObject?): List<RelationPair> {
    val relations = runCatching { payload?.get("relations")?.jsonObject }.getOrNull() ?: return emptyList()
    return relations.keys.mapNotNull { pairKey ->
        val parts = pairKey.split("|", "→", "-", "×", "—", "_").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) RelationPair(parts[0], parts[1]) else null
    }
}

/** 正文里可能出现的关系对分隔符（`## 甲 × 乙`、`- 甲 → 乙`）。 */
private val RELATION_BODY_SEPARATORS = listOf("×", "→", "|", "—", "·", " - ", "_")

/**
 * **兜底**：front-matter 读不出时，从正文的标题/列表行里尽力提取关系对。
 *
 * 真实产物把关系全放在 front-matter、正文只有 `# RELATION_GRAPH`，所以这里通常提不到东西；
 * 它的价值在于「旧格式/手工编辑过的文件」——那时至少还能救回一部分关系，
 * 而不是让对话彻底失去关系上下文（见 `relations.front_matter_parse` 诊断记录）。
 */
fun relationPairsFromBody(body: String): List<RelationPair> {
    if (body.isBlank()) return emptyList()
    val pairs = mutableListOf<RelationPair>()
    body.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        val text = when {
            line.startsWith("#") -> line.trimStart('#').trim()
            line.startsWith("- ") || line.startsWith("* ") -> line.drop(2).trim()
            else -> return@forEach
        }
        if (text.isEmpty()) return@forEach
        val separator = RELATION_BODY_SEPARATORS.firstOrNull { it in text } ?: return@forEach
        val parts = text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return@forEach
        val left = parts[0].takeIf { it.length in 1..24 } ?: return@forEach
        val right = parts[1].substringBefore(' ').takeIf { it.length in 1..24 } ?: return@forEach
        // 「- trust: 5」这类键值行会被上面的分隔符误判，按冒号过滤掉。
        if (left.endsWith(":") || right.endsWith(":")) return@forEach
        pairs += RelationPair(left, right)
    }
    return pairs.distinct()
}

/**
 * 关系文件的可读性判定。
 *
 * **格式事实**（两边必须一致）：生成侧 `DistillExecutor.processRelationGraph` 与
 * `RelationsService.writePayload` 写出的都是 **YAML** front-matter（`dumpYaml`），
 * 读取侧 `RelationsService.loadPayload` 也按 YAML 解析。
 * 2.1.13 的探针把 front-matter 当 **JSON** 解析，于是每一份正常的关系文件都被报成
 * 「front-matter 解析失败」——那是探针判据错，不是产物坏。
 *
 * @param frontMatterPresent 文件是否有 `---` 包裹的 front-matter
 * @param frontMatterPayload front-matter 解析结果（YAML/JSON 任一方式成功即可）；失败为 null
 * @param bodyPairs 正文兜底提取出的关系对（front-matter 成功时忽略）
 * @param knownCharacters 已知角色名（用于悬空引用判定）
 * @param frontMatterFormat 解析成功时用的格式（`yaml` / `json`），只用于报告
 */
fun assessRelationsDocument(
    frontMatterPresent: Boolean,
    frontMatterPayload: JsonObject?,
    bodyPairs: List<RelationPair>,
    knownCharacters: Set<String>,
    frontMatterFormat: String = "",
): ProbeAssessment {
    if (frontMatterPayload != null) {
        val pairs = relationPairsFromPayload(frontMatterPayload)
        val base = assessRelationGraph(pairs, knownCharacters)
        return base.copy(
            evidence = base.evidence + mapOf(
                "front_matter" to "ok",
                "front_matter_format" to frontMatterFormat.ifBlank { "yaml" },
            ),
        )
    }
    if (bodyPairs.isNotEmpty()) {
        val base = assessRelationGraph(bodyPairs, knownCharacters)
        return ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "关系文件 front-matter 解析失败，已降级为正文兜底提取（${bodyPairs.size} 条，可能有遗漏）。",
            evidence = base.evidence + mapOf("front_matter" to "failed", "body_fallback" to bodyPairs.size.toString()),
            details = listOf(
                "front_matter=${if (frontMatterPresent) "存在但解析失败" else "不存在"}",
                "正文兜底提取 ${bodyPairs.size} 条关系；front-matter 里的其余关系在对话里看不到。",
            ) + base.details,
        )
    }
    return ProbeAssessment(
        status = ProbeStatus.FAIL,
        summary = if (frontMatterPresent) {
            "关系文件存在但 front-matter 解析失败，且正文兜底没有提取到任何关系（对话里的关系上下文会消失）。"
        } else {
            "关系文件没有 front-matter，正文也没有可提取的关系（对话里的关系上下文会消失）。"
        },
        evidence = evidenceMap(
            "front_matter" to if (frontMatterPresent) "failed" else "absent",
            "body_fallback" to "0",
        ),
    )
}

/**
 * 关系图悬空引用：关系主体/对象必须都在已知角色里。
 *
 * 「悬空」＝关系里出现了一个既不在人物档案、也不在关系图自身节点集合里的名字——
 * 这通常意味着角色被改名/删除而关系图没跟着更新。
 */
fun assessRelationGraph(
    relations: List<RelationPair>,
    knownCharacters: Set<String>,
): ProbeAssessment {
    if (relations.isEmpty()) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "这本书卷还没有关系图（尚未生成或旧书卷）。",
        )
    }
    val referenced = buildSet {
        relations.forEach { pair ->
            if (pair.left.isNotBlank()) add(pair.left)
            if (pair.right.isNotBlank()) add(pair.right)
        }
    }
    val dangling = referenced.filterNot { it in knownCharacters }.sorted()
    val base = evidenceMapOf(
        "relations" to relations.size.toString(),
        "characters" to referenced.size.toString(),
        "dangling" to dangling.size.toString(),
    )
    val details = dangling.take(20).map { "悬空引用：$it" }
    return when {
        dangling.isEmpty() -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "关系图 ${relations.size} 条，主体都在已知角色里。",
            evidence = base,
        )
        dangling.size * 2 >= referenced.size -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "关系图有 ${dangling.size} 个悬空角色引用（多半是改名/删除后没重建）。",
            evidence = base,
            details = details,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "关系图有 ${dangling.size} 个悬空角色引用。",
            evidence = base,
            details = details,
        )
    }
}

// ======================================================================
// 每轮派生状态（一致性监控 / 知识台账 / 标题 / 回顾 / 卡片）
// ======================================================================

/**
 * 派生状态的事实表（探针只填事实，判定在这里）。
 *
 * @param withConsistencyMonitor 有非空 `consistency_monitor` 的会话数
 * @param withReviewedLatest 其中带 `latest`（深度审校真的跑过）的会话数
 * @param withKnowledgeLedger 其中 `knowledge_ledger` 非空的会话数
 * @param disclosureCandidates 会话里出现「披露句」模式的消息条数
 *   （与 `DialogueService.updateKnowledgeLedger` 同一口径；0 表示本轮本来就没有可登记的事实）
 */
data class DerivedArtifactFacts(
    val sessions: Int,
    val titled: Int,
    val withConsistencyMonitor: Int,
    val withReviewedLatest: Int,
    val withKnowledgeLedger: Int,
    val withStoryRecap: Int,
    val cardFiles: Int,
    val postTurnFailures: Int,
    val disclosureCandidates: Int,
)

/**
 * 一致性监控 / 知识台账的判定（**如实口径**，2.1.14）。
 *
 * 两个功能的实现程度**不一样**，报告必须分开说，不能混成一句「可能没生效」：
 * - **知识台账**：写入路径存在且每轮被调用（`DialogueService.updateKnowledgeLedger`），
 *   但它按「披露句」模式过滤（「秘密是…」「只告诉你…」）——没有这类句子时台账就是空的，
 *   那是**正常**，不是故障；
 * - **每轮自动一致性审校**：当前版本**没有实现**（Python 的 `update_monitor_state`
 *   没有移植过来），只有用户显式触发「深度审校」才会写 `consistency_monitor.latest`。
 *   这里保持 WARN 并写明「未实现」，不为了让探针变绿而伪造写入。
 */
fun assessDerivedArtifacts(facts: DerivedArtifactFacts): ProbeAssessment {
    val evidence = evidenceMap(
        "sessions" to facts.sessions.toString(),
        "titled" to facts.titled.toString(),
        "consistency_monitor" to facts.withConsistencyMonitor.toString(),
        "monitor_reviewed" to facts.withReviewedLatest.toString(),
        "knowledge_ledger" to facts.withKnowledgeLedger.toString(),
        "disclosure_candidates" to facts.disclosureCandidates.toString(),
        "story_recap" to facts.withStoryRecap.toString(),
        "card_files" to facts.cardFiles.toString(),
        "post_turn_failures" to facts.postTurnFailures.toString(),
    )
    if (facts.sessions == 0) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "还没有会话，一致性/标题/回顾/卡片均无样本。",
            evidence = evidence,
        )
    }
    if (facts.postTurnFailures > 0) {
        return ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "每轮派生状态（一致性/场景/世界记忆）最近失败过 ${facts.postTurnFailures} 次。",
            evidence = evidence,
        )
    }
    // 有披露句但台账没写 → 写入路径真的坏了。
    if (facts.disclosureCandidates > 0 && facts.withKnowledgeLedger == 0) {
        return ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "会话里有 ${facts.disclosureCandidates} 条披露句，但知识台账一条都没写（写入路径失效）。",
            evidence = evidence,
        )
    }
    if (facts.withConsistencyMonitor == 0) {
        val ledgerNote = if (facts.disclosureCandidates == 0) {
            "知识台账为空属正常（这轮没有「秘密是…/只告诉你…」这类可登记的事实）。"
        } else {
            "知识台账 ${facts.withKnowledgeLedger} 个会话有内容。"
        }
        return ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "每轮自动一致性审校在本版本尚未实现（只有手动「深度审校」会写 consistency_monitor）。$ledgerNote",
            evidence = evidence,
            details = listOf(
                "知识台账写入路径存在且每轮执行：DialogueService.updateKnowledgeLedger（按披露句模式过滤）。",
                "自动审校（Python update_monitor_state）未移植：$facts.withReviewedLatest 个会话有手动审校结果 latest。",
                "不伪造写入：本探针只报告事实，不会替业务补数据。",
            ),
        )
    }
    return ProbeAssessment(
        status = ProbeStatus.OK,
        summary = "一致性监控 ${facts.withConsistencyMonitor}/${facts.sessions}、知识台账 ${facts.withKnowledgeLedger}、" +
            "披露句 ${facts.disclosureCandidates}、标题 ${facts.titled}、回顾 ${facts.withStoryRecap}、卡片文件 ${facts.cardFiles}。",
        evidence = evidence,
    )
}

// ======================================================================
// 模型配置：模型名 ↔ 端点是否般配
// ======================================================================

/** 模型名里的厂商标识（前缀 → 厂商）。 */
private val MODEL_VENDOR_PREFIXES: List<Pair<String, String>> = listOf(
    "chatglm" to "智谱 GLM",
    "glm" to "智谱 GLM",
    "claude" to "Anthropic",
    "gemini" to "Google Gemini",
    "sensenova" to "商汤 SenseNova",
    "deepseek" to "DeepSeek",
    "qwen" to "通义千问",
    "qwq" to "通义千问",
    "doubao" to "字节豆包",
    "moonshot" to "月之暗面",
    "kimi" to "月之暗面",
    "ernie" to "百度文心",
    "hunyuan" to "腾讯混元",
    "minimax" to "MiniMax",
    "abab" to "MiniMax",
    "step-" to "阶跃星辰",
    "yi-" to "零一万物",
    "gpt-" to "OpenAI",
    "o1-" to "OpenAI",
    "o3-" to "OpenAI",
    "o4-" to "OpenAI",
)

/** 端点主机里的厂商标识（主机子串 → 厂商）。 */
private val ENDPOINT_VENDOR_HOSTS: List<Pair<String, String>> = listOf(
    "sensenova" to "商汤 SenseNova",
    "bigmodel" to "智谱 GLM",
    "zhipu" to "智谱 GLM",
    "anthropic" to "Anthropic",
    "generativelanguage.googleapis" to "Google Gemini",
    "openai.com" to "OpenAI",
    "dashscope" to "通义千问",
    "aliyuncs" to "阿里云",
    "deepseek" to "DeepSeek",
    "moonshot" to "月之暗面",
    "volces" to "火山方舟",
    "baidubce" to "百度文心",
    "hunyuan" to "腾讯混元",
    "minimaxi" to "MiniMax",
)

/** 本机/局域网端点：模型名不必与「云端厂商」对齐（Ollama / llama.cpp / 自建网关）。 */
private val LOCAL_ENDPOINT_MARKERS = listOf(
    "127.0.0.1", "localhost", "0.0.0.0", "[::1]", "10.0.2.2", "host.docker.internal", ".local", "192.168.", "10.0.",
)

/** 端侧权重文件名特征（GGUF 量化名）。 */
private val ON_DEVICE_MODEL_MARKERS = listOf(".gguf", "q4_k_m", "q5_k_m", "q8_0", "iq4_xs")

/**
 * 「模型名与端点是否般配」——**不看 `configured` 标志**，只看字段本身。
 *
 * 为什么需要这条判定：`configured` 是别处算出来的布尔值，实测在配置完全可用时也可能是
 * false（用户真机导出里 `api_key_configured=true` 而 `configured=false`）。
 * 拿它当判据只会制造假警报；真正会让用户「一句话都发不出去」的是
 * **模型名与端点不属同一家**——请求会被对端直接 400/404 拒掉。
 *
 * 返回 null 表示没发现风险（不是「已确认可用」，连通性仍以「测试连接」为准）。
 *
 * @param provider 档案里的 provider（`openai-compatible` / `local` 等）
 * @param model 模型名（如 `glm-5.2`、`sensenova-6.8-flash-lite`）
 * @param baseUrl 端点（如 `https://token.sensenova.cn/v1`）
 */
fun assessModelEndpointFit(provider: String, model: String, baseUrl: String): ProbeAssessment? {
    val modelText = model.trim().lowercase()
    val urlText = baseUrl.trim().lowercase()
    if (modelText.isEmpty() || urlText.isEmpty()) return null
    val local = LOCAL_ENDPOINT_MARKERS.any { it in urlText }
    val evidence = evidenceMap(
        "provider" to provider.ifBlank { "(空)" },
        "model" to model.trim(),
        "base_url" to baseUrl.trim(),
    )
    if (!local && ON_DEVICE_MODEL_MARKERS.any { it in modelText }) {
        return ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "模型名看起来是端侧权重文件（$model），但端点是云端地址：请求多半会失败。",
            evidence = evidence,
            details = listOf(
                "端侧权重应由本地推理（llama-server / provider=local）承载，云端端点不认识这个模型名。",
                "判定只看字段本身，不依赖 configured 标志。",
            ),
        )
    }
    if (!local) {
        val modelVendor = MODEL_VENDOR_PREFIXES.firstOrNull { modelText.startsWith(it.first) }?.second
        val endpointVendor = ENDPOINT_VENDOR_HOSTS.firstOrNull { it.first in urlText }?.second
        if (modelVendor != null && endpointVendor != null && modelVendor != endpointVendor) {
            return ProbeAssessment(
                status = ProbeStatus.WARN,
                summary = "模型名与端点不般配：模型「${model.trim()}」属于 $modelVendor，端点却是 $endpointVendor。",
                evidence = evidence,
                details = listOf(
                    "这种组合通常被对端直接拒绝（400 / 404），表现为「对话发不出去」。",
                    "要么把模型名改成端点提供的型号，要么把 base_url 换成该模型厂商的端点。",
                    "判定只看字段本身，不依赖 configured 标志。",
                ),
            )
        }
    }
    if (provider.equals("local", ignoreCase = true) && !local) {
        return ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "provider=local 但 base_url 不是本机地址（$baseUrl）。",
            evidence = evidence,
            details = listOf("local 走的是设备内推理进程；云端地址应当配 openai-compatible 一类 provider。"),
        )
    }
    return null
}

// ======================================================================
// 会话完整性 / 时间线
// ======================================================================

/** transcript 里的一条消息（只取 key 需要的字段 + 是否缺 key 成分）。 */
data class TranscriptEntryKey(
    val turnId: String,
    val speaker: String,
    val timestamp: String,
    val message: String,
) {
    /**
     * 与客户端 `TranscriptItemDto.transcriptKey()` **同一算法**：
     * `turnId|speaker|timestamp|messageHash`。
     *
     * `String.hashCode()` 是 Kotlin 对所有平台统一的算法（JVM 上等于 Java 的字符串哈希），
     * 因此服务端能算出与 Compose 列表相同的 key。
     */
    fun key(): String = "$turnId|$speaker|$timestamp|${message.hashCode()}"
}

/** 从 transcript 数组抽 key 组成。 */
fun extractTranscriptKeys(transcript: JsonArray?): List<TranscriptEntryKey> =
    transcript?.objects().orEmpty().map { entry ->
        TranscriptEntryKey(
            turnId = entry.str("turn_id"),
            speaker = entry.str("speaker"),
            timestamp = entry.str("timestamp").ifBlank { entry.str("created_at") },
            message = entry.str("message"),
        )
    }

/**
 * 会话完整性：transcript 是否有重复 key + `transcript_count` 是否与实际条数一致。
 *
 * 重复 key 正是 2.1.10 那次闪退的根因（LazyColumn 的 key 撞车），
 * 做成运行时校验可以在用户设备上提前发现回归，而不是等崩。
 */
fun assessSessionIntegrity(
    transcript: List<TranscriptEntryKey>,
    declaredTranscriptCount: Int?,
    transcriptStart: Int?,
): ProbeAssessment {
    val keys = transcript.map { it.key() }
    val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }
    val actualCount = if (transcriptStart != null && transcriptStart > 0) null else transcript.size
    val countMismatch = actualCount != null && declaredTranscriptCount != null && declaredTranscriptCount != actualCount
    val details = buildList {
        duplicates.keys.take(10).forEach { key -> add("重复 key ×${duplicates[key]}：${key.take(160)}") }
        if (countMismatch) {
            add("transcript_count=$declaredTranscriptCount 与实际条数 $actualCount 不一致（transcript_start=${transcriptStart ?: 0}）")
        } else if (transcriptStart != null && transcriptStart > 0) {
            add("transcript 已归档：start=$transcriptStart，本轮只校验最近 ${transcript.size} 条（总量 $declaredTranscriptCount）")
        }
    }
    val base = evidenceMapOf(
        "entries" to transcript.size.toString(),
        "duplicate_keys" to duplicates.size.toString(),
        "declared_count" to declaredTranscriptCount?.toString(),
        "transcript_start" to (transcriptStart ?: 0).toString(),
    )
    return when {
        duplicates.isNotEmpty() -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "transcript 有 ${duplicates.size} 组重复 key，会导致列表 key 撞车（历史闪退根因）。",
            evidence = base,
            details = details,
        )
        countMismatch -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "transcript_count=$declaredTranscriptCount 与实际条数 $actualCount 不一致。",
            evidence = base,
            details = details,
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "会话 transcript 无重复 key，计数一致（${transcript.size} 条）。",
            evidence = base,
        )
    }
}

/**
 * 人物时间线推进：期望值 `start_chapter + turn_count / TURNS_PER_PERSONA_CHAPTER`
 * 与 `persona_chapter` 实际值是否一致。
 *
 * 写入路径（`DialogueService.applyPersonaTimePoint`）声明「值没变就不写」，
 * 所以「长期不变但已跨过推进倍数」是真实异常信号（后台任务覆盖了 manifest 等）。
 */
fun assessTimelineAdvance(
    startChapter: Int,
    turnCount: Int,
    personaChapter: Int,
    turnsPerChapter: Int = TURNS_PER_PERSONA_CHAPTER,
    transcriptCount: Int? = null,
): ProbeAssessment {
    val base = if (startChapter > 0) startChapter else 1
    val expected = if (turnCount <= 0) base else base + turnCount / turnsPerChapter.coerceAtLeast(1)
    val actual = if (personaChapter > 0) personaChapter else base
    val drift = actual - expected
    val evidence = evidenceMapOf(
        "start_chapter" to base.toString(),
        "turn_count" to turnCount.toString(),
        "expected" to expected.toString(),
        "actual" to actual.toString(),
        "drift" to drift.toString(),
        "turns_per_chapter" to turnsPerChapter.toString(),
        "transcript_count" to transcriptCount?.toString(),
    )
    return when {
        drift != 0 -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "时间线实际值 $actual 与期望 $expected 不一致（差 $drift）。",
            evidence = evidence,
            details = listOf(
                "期望 = start_chapter($base) + turn_count($turnCount) / $turnsPerChapter = $expected",
                "实际 persona_chapter = $actual（写入路径声明「值没变就不写」，持续不一致说明被覆盖或未推进）",
            ),
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "时间线推进一致：persona_chapter=$actual（期望 $expected）。",
            evidence = evidence,
        )
    }
}

// ======================================================================
// 提示词规则键
// ======================================================================

/** 运行时必须能读到的关键规则键。 */
data class RequiredPromptRule(
    /** 展示名（对应哪次修复）。 */
    val label: String,
    /** 读取方式：`turn_system` / `distill_guidance` / `director`。 */
    val source: String,
    /** 键名。 */
    val key: String,
)

/** 2.1.7 / 2.1.8 三处修复对应的关键规则键。 */
val REQUIRED_PROMPT_RULES: List<RequiredPromptRule> = listOf(
    RequiredPromptRule("强制执行（2.1.7）", "director", "force_execute_instruction"),
    RequiredPromptRule("时间点人设规则（2.1.8）", "turn_system", "time_point_persona_rule"),
    RequiredPromptRule("人设弧线指令（2.1.8）", "distill_guidance", "persona_arc_instruction"),
    RequiredPromptRule("人设弧线输出格式（2.1.8）", "distill_guidance", "persona_arc_output_format"),
)

/**
 * 提示词加载完整性：运行时能否读到关键规则键。
 *
 * @param resolved 键（`source|key`）→ 运行时读到的文本；缺失的键不在表里
 *
 * 读不到就 FAIL 并提示「提示词源与打包副本可能不同步，跑 scripts/check-prompt-sync.sh」——
 * 这条能把「改了 prompts/ 却忘了同步 assets」在用户设备上直接暴露出来。
 */
fun assessPromptRules(resolved: Map<String, String>): ProbeAssessment {
    val missing = REQUIRED_PROMPT_RULES.filter { resolved["${it.source}|${it.key}"].isNullOrBlank() }
    val evidence = evidenceMapOf(
        "required" to REQUIRED_PROMPT_RULES.size.toString(),
        "loaded" to (REQUIRED_PROMPT_RULES.size - missing.size).toString(),
        "missing" to missing.size.toString(),
    )
    if (missing.isEmpty()) {
        return ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "提示词关键规则键齐全（${REQUIRED_PROMPT_RULES.size}/${REQUIRED_PROMPT_RULES.size}）。",
            evidence = evidence,
        )
    }
    return ProbeAssessment(
        status = ProbeStatus.FAIL,
        summary = "运行时读不到 ${missing.size} 个关键规则键；提示词源与打包副本可能不同步，跑 scripts/check-prompt-sync.sh。",
        evidence = evidence,
        details = missing.map { "缺失：${it.label} → ${it.source}.${it.key}" } +
            "提示词三处副本必须一致：prompts/**、kmp/server/src/androidMain/assets/**、" +
            "kmp/app/shared/src/commonMain/composeResources/files/prompts/**",
    )
}

// ======================================================================
// 事件日志驱动的判定（不依赖运行日志采集开关）
// ======================================================================

/** 事件类别计数（升序类别名，便于稳定输出）。 */
fun countEventsByCategory(events: List<Pair<String, String>>): Map<String, Int> =
    events.groupingBy { it.first }.eachCount().toSortedMap()

/**
 * 「最近一次成败」判定：从结构化事件里找某个动作的最后一条记录。
 *
 * @param successCategories 代表成功的类别（出现即成功）
 * @param failureCategories 代表失败的类别（出现即失败）
 * @param sampleNote 无样本时的说明（会作为 summary 的一部分）
 *
 * **采集关闭不影响它**：事件日志走 `DiagnosticsSink` → `DiagnosticsEventLog` 独立文件，
 * 与常规 logcat 采集开关无关。真正没有样本时返回 NO_DATA，而不是 FAIL。
 */
fun assessLatestOutcome(
    events: List<Pair<String, String>>,
    successCategories: Set<String>,
    failureCategories: Set<String>,
    label: String,
    sampleNote: String = "该动作还没有使用记录",
): ProbeAssessment {
    val ordered = events.mapIndexed { index, pair -> Triple(index, pair.first, pair.second) }
    val lastFailure = ordered.lastOrNull { it.second in failureCategories }
    val lastSuccess = ordered.lastOrNull { it.second in successCategories }
    if (lastFailure == null && lastSuccess == null) {
        return ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "$label：$sampleNote。",
            evidence = evidenceMap("samples" to events.size.toString()),
        )
    }
    val failureIsLater = lastFailure != null && (lastSuccess == null || lastFailure.first > lastSuccess.first)
    val evidence = evidenceMapOf(
        "samples" to events.size.toString(),
        "last_success" to lastSuccess?.second,
        "last_failure" to lastFailure?.second,
        "failure_is_latest" to failureIsLater.toString(),
    )
    return if (failureIsLater) {
        ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "$label：最近一次失败（${lastFailure!!.third.take(120)}）。",
            evidence = evidence,
            details = listOfNotNull(
                lastFailure.third.take(200),
                lastSuccess?.third?.take(200)?.let { "上一次成功：$it" },
            ),
        )
    } else {
        ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "$label：最近一次成功。",
            evidence = evidence,
            details = listOfNotNull(lastSuccess?.third?.take(200)),
        )
    }
}

/**
 * 一组事件里失败的**类别计数**，用于「失败反复发生」这类判定。
 */
fun countFailures(events: List<Pair<String, String>>, failureCategories: Set<String>): Int =
    events.count { it.first in failureCategories }
