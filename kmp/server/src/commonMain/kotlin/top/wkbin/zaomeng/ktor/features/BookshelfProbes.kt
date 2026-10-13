package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.KnownFeature
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.parseYaml

/**
 * 书卷与整理域探针。
 *
 * 数据来源都是**已有产物**：run manifest、`artifacts/characters/<novel>/<角色>/`、`arc.json`、
 * `artifacts/relations/<book>_relations.md`、`novel_chapters.json`、结构化诊断事件。
 * 全部只读；不新增埋点（做不到的项在报告里明确列出）。
 */

/** 探针基类：只做常量承载，避免每个探针重复写四个字段。 */
internal abstract class BaseProbe(
    final override val id: String,
    final override val title: String,
    final override val domain: String,
    final override val features: List<String>,
) : FeatureProbe

/** 读取焦点书卷的章节索引缓存（`novel_chapters.json`）。 */
internal fun readChapterIndexCache(ctx: FeatureProbeContext, runId: String): JsonObject? =
    ctx.parseJsonObject(ctx.readRunTextLimited(runId, CHAPTER_INDEX_READ_LIMIT, "novel_chapters.json"))

private const val CHAPTER_INDEX_READ_LIMIT = 400_000

/** 书卷导入（最近一次成败、ZIP 校验/路径安全失败）。 */
internal class BookshelfImportProbe : BaseProbe(
    id = "bookshelf.import",
    title = "书卷导入",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.import"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val events = ctx.recentEvents().map { it.category to it.message }
        // 导入/打包复制失败在 library.import_copy；冷启动读原文失败在 distill.source_read
        // （ZIP 被裁掉、路径逃逸、文件被删都会在这里留下一条）。
        val outcome = assessLatestOutcome(
            events = events,
            successCategories = setOf("backend.ready"),
            failureCategories = setOf("library.import_copy", "distill.source_read"),
            label = "书卷导入",
            sampleNote = "本进程还没有导入/读取原文的记录",
        )
        // 有失败记录时，把失败的类别计数一并带上（判断是「偶发」还是「反复」）。
        val failures = countFailures(
            events,
            setOf("library.import_copy", "distill.source_read"),
        )
        return outcome.toResult()
            .withEvidence(evidenceMap("import_failures" to failures.toString()))
            .normalized()
    }
}

/** 书卷列表与详情可加载、必需字段完整。 */
internal class BookshelfListProbe : BaseProbe(
    id = "bookshelf.list",
    title = "书卷列表与详情",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.list"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runIds = ctx.runIds()
        if (runIds.isEmpty()) {
            return ProbeResult.noData("还没有书卷。", ProbeEvidence.build { number("runs", 0) })
        }
        var unreadable = 0
        var missingTitle = 0
        val details = mutableListOf<String>()
        runIds.forEach { runId ->
            val manifest = ctx.readRunManifest(runId)
            if (manifest == null) {
                unreadable++
                details += "manifest 缺失或无法解析：$runId"
                return@forEach
            }
            // 标题字段的真实键名是 `novel_name`（RunManagementService/RunOperationsService 都写它，
            // 列表接口 RunContracts 也读它）；`novel_title` / `title` 只是旧写法。
            // 2.1.13 的探针只查后两个，于是每本书都被报成「缺标题」——探针判据错。
            if (manifest.str("novel_name").isBlank() &&
                manifest.str("novel_title").isBlank() &&
                manifest.str("title").isBlank() &&
                manifest.arr("novel_sources").objects().all { it.str("source_name").isBlank() }
            ) {
                missingTitle++
                // 把「manifest 里到底有哪些键」写进明细：只报「缺标题」无法区分
                // 「旧数据的 manifest 结构不同」与「探针判据过严」，下一份导出要能直接看出结论。
                details += "manifest 缺标题字段：$runId（现有顶层键：" +
                    manifest.keys.sorted().take(24).joinToString(",") + "）"
            }
        }
        val base = evidenceMap(
            "runs" to runIds.size.toString(),
            "unreadable" to unreadable.toString(),
            "missing_title" to missingTitle.toString(),
        )
        return when {
            unreadable > 0 -> ProbeResult.fail(
                "有 $unreadable 本书卷的 manifest 读不到或解析失败（列表里会缺项）。",
                base.toProbeEvidence(),
                details.take(20),
            )
            missingTitle > 0 -> ProbeResult.warn(
                "有 $missingTitle 本书卷缺标题字段（列表显示为空）。",
                base.toProbeEvidence(),
                details.take(20),
            )
            else -> ProbeResult.ok(
                "${runIds.size} 本书卷的列表与详情清单都可读。",
                base.toProbeEvidence(),
            )
        }
    }
}

/** 蒸馏完成度（completed/planned 角色数、是否有 error）。 */
internal class BookshelfDistillProbe : BaseProbe(
    id = "bookshelf.distill",
    title = "人物蒸馏",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.distill", "bookshelf.redistill"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runIds = ctx.runIdsForProbe()
        if (runIds.isEmpty()) return ProbeResult.noData("还没有书卷。")

        var running = 0
        var failed = 0
        var complete = 0
        var duplicateRisks = 0
        var totalPlanned = 0
        var totalCompleted = 0
        val details = mutableListOf<String>()

        runIds.forEach { runId ->
            val manifest = ctx.readRunManifest(runId) ?: return@forEach
            val progress = manifest.obj("progress")
            val status = manifest.str("status")
            val summary = manifest.obj("summary")
            val planned = progress?.intOrNull("total_characters")
                ?: summary?.intOrNull("characters_total")
                ?: 0
            val completedNames = progress?.arr("completed_characters")?.strings().orEmpty()
            val completed = progress?.intOrNull("completed_count")
                ?: summary?.intOrNull("characters_completed")
                ?: completedNames.size
            totalPlanned += planned
            totalCompleted += completed
            when (status) {
                "complete" -> complete++
                "running" -> running++
                "failed" -> {
                    failed++
                    val reason = progress?.str("error").orEmpty()
                        .ifBlank { manifest.obj("error")?.str("message").orEmpty() }
                    details += "$runId：蒸馏失败（${reason.take(160)}）"
                }
            }
            // 重复角色：completed_characters 自身有重复，或 completed 数超出计划数
            val duplicates = completedNames.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (duplicates.isNotEmpty() || (planned > 0 && completed > planned)) {
                duplicateRisks++
                details += "$runId：completed_characters 有重复/超出计划（planned=$planned completed=$completed 重复=${duplicates.keys.take(5)}）"
            }
        }
        val evidence = evidenceMap(
            "runs" to runIds.size.toString(),
            "complete" to complete.toString(),
            "running" to running.toString(),
            "failed" to failed.toString(),
            "completed" to "$totalCompleted/$totalPlanned",
            "duplicate_risk" to duplicateRisks.toString(),
        )
        return when {
            failed > 0 -> ProbeResult.fail(
                "有 $failed 本书卷蒸馏失败。",
                evidence.toProbeEvidence(),
                details.take(20),
            )
            duplicateRisks > 0 -> ProbeResult.warn(
                "有 $duplicateRisks 本书卷的完成角色清单出现重复（重跑可能覆盖/重复完成）。",
                evidence.toProbeEvidence(),
                details.take(20),
            )
            running > 0 -> ProbeResult.ok(
                "蒸馏进行中（$running 本），已完成 $totalCompleted/$totalPlanned 个角色。",
                evidence.toProbeEvidence(),
            )
            else -> ProbeResult.ok(
                "蒸馏完成度正常：$totalCompleted/$totalPlanned 个角色。",
                evidence.toProbeEvidence(),
            )
        }
    }
}

/**
 * 人物档案完整性 + 人设弧线。
 *
 * 两条分开注册（档案 / 弧线），因为它们是用户能分别感知的两件事：
 * 档案缺字段会让角色「说话不对」，弧线缺失只是回到「全书形态」。
 */
internal class BookshelfProfileProbe : BaseProbe(
    id = "bookshelf.profile",
    title = "人物档案",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.persona_profile"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        val characters = extractIndexedCharacters(manifest)
        if (characters.isEmpty()) return ProbeResult.noData("这本书卷还没有人物档案。")
        val novelId = manifest.str("novel_id").ifBlank { runId }

        var checked = 0
        var missingFields = 0
        var placeholders = 0
        var unparsable = 0
        var unreadable = 0
        val details = mutableListOf<String>()
        characters.take(MAX_PROFILE_CHECKS).forEach { character ->
            if (character.name.isBlank()) return@forEach
            val relative = "artifacts/characters/$novelId/${character.name}/PROFILE.md"
            val text = ctx.readRunTextLimited(runId, PROFILE_READ_LIMIT, relative)
            val frontMatter = text?.let(::extractFrontMatter)
            val assessment = assessProfileCompleteness(
                relativePath = relative,
                text = text,
                hasFrontMatter = frontMatter != null,
                parsedKeys = frontMatter?.let(::extractFrontMatterKeys).orEmpty(),
            )
            checked++
            when (assessment.status) {
                ProbeStatus.FAIL -> {
                    if (text == null) unreadable++ else if (frontMatter == null) unparsable++ else missingFields++
                    details += assessment.summary
                }
                ProbeStatus.WARN -> {
                    placeholders++
                    details += assessment.summary
                }
                else -> Unit
            }
        }
        val evidence = evidenceMap(
            "characters" to characters.size.toString(),
            "checked" to checked.toString(),
            "missing_fields" to missingFields.toString(),
            "unparsable" to unparsable.toString(),
            "unreadable" to unreadable.toString(),
            "placeholders" to placeholders.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            unreadable > 0 || unparsable > 0 -> ProbeResult.fail(
                "档案读不到/无法解析：unreadable=$unreadable unparsable=$unparsable。",
                built(),
                details.take(20),
            )
            missingFields > 0 -> ProbeResult.fail(
                "有 $missingFields 份档案缺必需字段。",
                built(),
                details.take(20),
            )
            placeholders > 0 -> ProbeResult.warn(
                "有 $placeholders 份档案出现「未知/证据不足」一类占位词。",
                built(),
                details.take(20),
            )
            else -> ProbeResult.ok(
                "${characters.size} 份人物档案字段完整、无占位词。",
                built(),
            )
        }
    }
}

/** 人设弧线：文件存在、章数吻合、ok 覆盖率、failed/empty 章数、当前时间点是否有非空弧线段。 */
internal class BookshelfArcProbe : BaseProbe(
    id = "bookshelf.arc",
    title = "人设弧线",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.persona_arc"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        val novelId = manifest.str("novel_id").ifBlank { runId }
        val characters = extractIndexedCharacters(manifest)

        // 章节索引（缓存）里的章号：用于判断 chapter_count 是否与索引吻合。
        val chapterIndex = readChapterIndexCache(ctx, runId)
        val plannedChapters = chapterIndex?.arr("chapters")?.objects().orEmpty()
            .mapNotNull { it.intOrNull("index") }
            .toSet()

        // 当前时间点：取这本书卷所有会话里最大的 persona_chapter。
        val personaChapter = ctx.listSessionIds(runId).take(ctx.maxSessionsPerProbe).maxOfOrNull { sessionId ->
            ctx.loadSessionManifest(runId, sessionId)?.intOrNull("persona_chapter") ?: 0
        } ?: 0

        var withArc = 0
        var arcCounts = mapOf<String, Int>()
        val perCharacter = mutableListOf<String>()
        val actualArcCounts = mutableMapOf<String, Int>()
        characters.take(MAX_ARC_CHECKS).forEach { character ->
            if (character.name.isBlank()) return@forEach
            val arc = ctx.parseJsonObject(
                ctx.readRunTextLimited(runId, ARC_READ_LIMIT, "artifacts/characters/$novelId/${character.name}/arc.json"),
            )
            val entries = extractArcChapters(arc)
            actualArcCounts[character.name] = arc?.intOrNull("chapter_count") ?: entries.size
            if (entries.isNotEmpty()) withArc++
            val assessment = assessPersonaArc(
                declaredChapterCount = arc?.intOrNull("chapter_count") ?: entries.size,
                entries = entries,
                plannedChapters = plannedChapters,
                personaChapter = personaChapter,
            )
            arcCounts = arcCounts + (character.name to entries.size)
            if (assessment.status != ProbeStatus.OK) perCharacter += "${character.name}：${assessment.summary}"
        }

        if (withArc == 0) {
            return ProbeResult.noData(
                "这本书卷还没有弧线（${characters.size} 个角色，弧线条目 0）。",
                ProbeEvidence.build {
                    number("characters", characters.size)
                    number("with_arc", 0)
                    if (plannedChapters.isNotEmpty()) number("planned_chapters", plannedChapters.size)
                },
            )
        }

        // 截断计数单独再扫一遍已解析的弧线（只读，且已被上一步读入缓存）。
        var truncatedTotal = 0
        actualArcCounts.keys.forEach { name ->
            val arc = ctx.parseJsonObject(
                ctx.readRunTextLimited(runId, ARC_READ_LIMIT, "artifacts/characters/$novelId/$name/arc.json"),
            )
            truncatedTotal += extractArcChapters(arc).count { it.truncated }
        }

        // 汇总：覆盖章数最少/最多的角色，作为主要证据
        val minCount = arcCounts.values.minOrNull() ?: 0
        val maxCount = arcCounts.values.maxOrNull() ?: 0
        val evidence = evidenceMapOf(
            "characters" to characters.size.toString(),
            "with_arc" to withArc.toString(),
            "arc_chapters_min" to minCount.toString(),
            "arc_chapters_max" to maxCount.toString(),
            "planned_chapters" to plannedChapters.size.takeIf { it > 0 }?.toString(),
            "persona_chapter" to personaChapter.takeIf { it > 0 }?.toString(),
            "source_truncated" to truncatedTotal.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return if (perCharacter.isEmpty()) {
            ProbeResult.ok("弧线可用：$withArc/${characters.size} 个角色有弧线（$minCount~$maxCount 章）。", built())
        } else {
            ProbeResult.warn(
                "弧线有可疑项：${perCharacter.size} 个角色（${perCharacter.first().take(120)}）。",
                built(),
                perCharacter.take(20),
            )
        }
    }
}

/** `artifact_index.characters[].has_arc/arc_count` 与实际文件是否一致。 */
internal class BookshelfArcIndexProbe : BaseProbe(
    id = "bookshelf.arc_index",
    title = "弧线索引一致性",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.persona_arc"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        val indexed = extractIndexedCharacters(manifest)
        val novelId = manifest.str("novel_id").ifBlank { runId }
        val actual = indexed.take(MAX_ARC_CHECKS).associate { character ->
            val arc = ctx.parseJsonObject(
                ctx.readRunTextLimited(runId, ARC_READ_LIMIT, "artifacts/characters/$novelId/${character.name}/arc.json"),
            )
            character.name to (arc?.intOrNull("chapter_count") ?: extractArcChapters(arc).size)
        }
        val assessment = assessArcIndexConsistency(indexed, actual)
        return assessment.toResult()
    }
}

/** 关系图：关系数、悬空引用。 */
internal class BookshelfRelationsProbe : BaseProbe(
    id = "bookshelf.relations",
    title = "人物关系图",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.relations"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        val graph = manifest.obj("artifact_index")?.obj("relation_graph")
        val relationsFile = graph?.str("relations_file").orEmpty()
        val relative = if (relationsFile.isNotBlank()) {
            relationsFile.substringAfter("runs/$runId/", relationsFile)
        } else {
            "artifacts/relations"
        }
        val text = if (relative.endsWith(".md")) ctx.readRunTextLimited(runId, RELATIONS_READ_LIMIT, relative) else null
        if (text == null) {
            val declared = graph?.intOrNull("relation_count") ?: 0
            return if (declared > 0) {
                ProbeResult.fail(
                    "manifest 声明有 $declared 条关系，但关系文件读不到（$relative）。",
                    ProbeEvidence.build {
                        number("relations", declared)
                        text("relations_file", relative)
                    },
                )
            } else {
                ProbeResult.noData(
                    "这本书卷还没有关系图。",
                    ProbeEvidence.build { number("relations", 0) },
                )
            }
        }
        // 关系文件的 front-matter 是 **YAML**（生成侧 dumpYaml；读取侧 RelationsService 用 parseYaml）。
        // 2.1.13 的探针把它当 JSON 解析，于是每一份正常文件都报「front-matter 解析失败」。
        // 这里两种格式都试：YAML 是正解，JSON 兼容历史上可能的写法。
        val frontMatter = extractFrontMatter(text)
        val parsed = frontMatter?.let { parseFrontMatterObject(it) }
        val payload = parsed?.payload
        // 正文 = front-matter 之后的行（首行 `---` + front-matter 行 + 结束 `---`）。
        // 用行数切，而不是字符串替换：front-matter 里出现 `---` 一类内容时也不会切错。
        val body = if (frontMatter == null) {
            text
        } else {
            text.lines().drop(1 + frontMatter.lines().size + 1).joinToString("\n")
        }
        val bodyPairs = if (payload == null) relationPairsFromBody(body) else emptyList()
        if (frontMatter != null && payload == null) {
            // 真解析失败才留痕（探针只读，但**诊断记录**是排查的唯一线索：静默降级最致命）。
            DiagnosticsSink.recordFailure(
                stage = "relations.front_matter_parse",
                error = IllegalStateException(parsed?.error ?: "关系文件 front-matter 解析失败"),
                retrying = false,
                details = mapOf(
                    "run_id" to runId,
                    "relations_file" to relative,
                    "front_matter_chars" to frontMatter.length.toString(),
                    "body_fallback_relations" to bodyPairs.size.toString(),
                ),
            )
        }
        val known = extractIndexedCharacters(manifest).map { it.name }.filter { it.isNotBlank() }.toSet()
        val assessment = assessRelationsDocument(
            frontMatterPresent = frontMatter != null,
            frontMatterPayload = payload,
            bodyPairs = bodyPairs,
            knownCharacters = known,
            frontMatterFormat = parsed?.format.orEmpty(),
        )
        return assessment.toResult().withEvidence(
            evidenceMap("relations_file" to relative),
        )
    }
}

/** front-matter 解析结果：成功给 payload 与格式名，失败给原因（原因里不含正文，避免把用户内容写进诊断）。 */
private data class FrontMatterParse(
    val payload: JsonObject?,
    val format: String,
    val error: String?,
)

/**
 * 解析 front-matter：先按 JSON，再按 YAML（真实格式）。
 *
 * 两种都失败时返回错误摘要——**只含异常类型与消息**，不带正文片段。
 */
private fun parseFrontMatterObject(frontMatter: String): FrontMatterParse {
    val jsonError = runCatching { Json.parseToJsonElement(frontMatter).jsonObject }
    jsonError.getOrNull()?.let { return FrontMatterParse(it, "json", null) }
    val yamlError = runCatching { yamlToJsonObject(parseYaml(frontMatter)) }
    yamlError.getOrNull()?.let { return FrontMatterParse(it, "yaml", null) }
    val reason = yamlError.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
        ?: jsonError.exceptionOrNull()?.let { "JSON ${it::class.simpleName}: ${it.message}" }
        ?: "unknown"
    return FrontMatterParse(null, "", reason)
}

/** YAML（parseYaml）结果 → JsonObject；只处理 Map/List/基本类型。 */
@Suppress("UNCHECKED_CAST")
private fun yamlToJsonObject(value: Any?): JsonObject = when (value) {
    is Map<*, *> -> buildJsonObject {
        value.forEach { (key, item) -> put(key.toString(), yamlToJsonElement(item)) }
    }
    else -> throw IllegalArgumentException("front-matter 顶层不是映射（${value?.let { it::class.simpleName } ?: "null"}）")
}

private fun yamlToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> buildJsonObject { value.forEach { (key, item) -> put(key.toString(), yamlToJsonElement(item)) } }
    is List<*> -> buildJsonArray { value.forEach { add(yamlToJsonElement(it)) } }
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

/** 章节索引：分章成功、章数、字符区间合法、缓存是否过期。 */
internal class BookshelfChaptersProbe : BaseProbe(
    id = "bookshelf.chapters",
    title = "原文分章与章节索引",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.chapters"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        val cache = readChapterIndexCache(ctx, runId)
            ?: return ProbeResult.noData(
                "这本书卷还没有章节索引（可直接从第 1 章开始的设备不需要它）。",
                ProbeEvidence.build { text("run_id", runId) },
            )
        val chapters = cache.arr("chapters").objects()
        val intervals = chapters.mapNotNull { entry ->
            val index = entry.intOrNull("index") ?: return@mapNotNull null
            val start = entry.intOrNull("start_char") ?: return@mapNotNull null
            val end = entry.intOrNull("end_char") ?: return@mapNotNull null
            Triple(index, start, end)
        }
        val sourcePath = cache.obj("source")?.str("path").orEmpty()
        val sourceSize = cache.obj("source")?.str("byte_size")?.toLongOrNull()
        val currentSize = sourcePath.takeIf { it.isNotBlank() }?.let { ctx.fileSize(it.toPath()) }
        val cacheStale = sourceSize != null && currentSize != null && sourceSize != currentSize
        val sourceCharCount = cache.obj("source")?.intOrNull("char_count")
        val assessment = assessChapterIndex(
            chapterCount = cache.intOrNull("chapter_count") ?: chapters.size,
            intervals = intervals,
            sourceCharCount = sourceCharCount,
            cacheStale = cacheStale,
            declaredTruncated = cache.str("truncated").equals("true", ignoreCase = true),
        )
        return assessment.toResult()
    }
}

/** 原文证据 / 知识库：索引可用 + 检索最近一次是否命中。 */
internal class BookshelfKnowledgeProbe : BaseProbe(
    id = "bookshelf.knowledge",
    title = "原文证据 / 知识库",
    domain = KnownFeature.DOMAIN_BOOKS,
    features = listOf("bookshelf.original_knowledge"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val manifest = ctx.readRunManifest(runId) ?: return ProbeResult.fail("焦点书卷的 manifest 读不到：$runId")
        // 原文是否还在：知识库检索依赖原文/索引文件。
        val novelPath = manifest.str("novel_path").ifBlank {
            manifest.arr("novel_sources").objects().lastOrNull()?.str("source_path").orEmpty()
        }
        val novelExists = novelPath.isNotBlank() && ctx.isFile(novelPath.toPath())
        val events = ctx.recentEvents().map { it.category to it.message }
        // 检索失败（索引损坏/读取失败）会落一条 dialogue.original_index。
        val retrievalFailures = countFailures(events, setOf("dialogue.original_index"))
        val indexUsable = novelExists || retrievalFailures == 0
        val evidence = evidenceMap(
            "novel_file" to if (novelExists) "yes" else "no",
            "retrieval_failures" to retrievalFailures.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            retrievalFailures > 0 -> ProbeResult.fail(
                "原文检索最近出现过 $retrievalFailures 次失败（知识库索引可能不可用）。",
                built(),
            )
            !novelExists -> ProbeResult.noData(
                "这本书卷的原文文件已不在（未启用原文证据）。",
                built(),
            )
            else -> ProbeResult.ok("原文存在、检索链路无失败记录。", built())
        }
    }
}

// ----------------------------------------------------------------------
// 读取上限：探针必须廉价，宁可少查几个角色也不能拖慢诊断页
// ----------------------------------------------------------------------

private const val MAX_PROFILE_CHECKS = 12
private const val MAX_ARC_CHECKS = 12
private const val PROFILE_READ_LIMIT = 24_000
private const val ARC_READ_LIMIT = 200_000
private const val RELATIONS_READ_LIMIT = 200_000

/** 注册书卷与整理域的全部探针。 */
fun registerBookshelfProbes(registry: FeatureProbeRegistry) {
    registry.registerAll(
        BookshelfImportProbe(),
        BookshelfListProbe(),
        BookshelfDistillProbe(),
        BookshelfProfileProbe(),
        BookshelfArcProbe(),
        BookshelfArcIndexProbe(),
        BookshelfRelationsProbe(),
        BookshelfChaptersProbe(),
        BookshelfKnowledgeProbe(),
    )
}
