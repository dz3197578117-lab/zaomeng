package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.KnownFeature
import top.wkbin.zaomeng.ktor.services.KNOWLEDGE_LEDGER_DISCLOSURE_PATTERNS

/**
 * 会话与对话域探针。
 *
 * 这一域里多数「高级动作」（导演方案 / 续写建议 / 场景切换 / 分支 / 纠错 / 重写）
 * **没有各自的事件埋点**，因此只能：
 * - 对能读到的持久化状态做一致性检查（场景进度、张力、长期记忆、世界记忆、回顾）；
 * - 对对话主链路用结构化事件（`dialogue.*`）判断最近一次成败。
 * 做不到的项在报告里逐条说明，绝不假装 OK。
 */

/** 会话焦点：只挑有对话记录的会话，避免把「刚建还没说话」的会话算成异常。 */
private class SessionFocus(
    val runId: String,
    val sessionId: String,
    val manifest: JsonObject,
) {
    val turnCount: Int get() = manifest.intOrNull("turn_count") ?: 0
}

private fun focusSessions(ctx: FeatureProbeContext, onlyWithTurns: Boolean = true): List<SessionFocus> {
    val result = mutableListOf<SessionFocus>()
    ctx.runIdsForProbe().forEach { runId ->
        ctx.listSessionIds(runId).take(ctx.maxSessionsPerProbe).forEach { sessionId ->
            val manifest = ctx.loadSessionManifest(runId, sessionId) ?: return@forEach
            val focus = SessionFocus(runId, sessionId, manifest)
            if (!onlyWithTurns || focus.turnCount > 0) result += focus
        }
    }
    return result
}

/**
 * 通用「派生状态是否真的在更新」判定。
 *
 * 这是「状态永远不变」这类缺陷的唯一廉价检测手段：**只看该字段有没有被写过**，
 * 不去猜内容对不对（内容对不对要靠模型评审，代价太高）。
 */
internal fun assessDerivedStatePresence(
    label: String,
    sessionsChecked: Int,
    withTurns: Int,
    present: Int,
    absentSamples: List<String>,
    extraEvidence: Map<String, String> = emptyMap(),
): ProbeAssessment {
    val evidence = linkedMapOf(
        "sessions_with_turns" to withTurns.toString(),
        "present" to present.toString(),
        "absent" to (withTurns - present).toString(),
        "sessions_checked" to sessionsChecked.toString(),
    )
    evidence.putAll(extraEvidence)
    return when {
        withTurns == 0 -> ProbeAssessment(
            status = ProbeStatus.NO_DATA,
            summary = "$label：还没有产生过对话轮次的会话，无法判断。",
            evidence = evidence,
        )
        present == 0 -> ProbeAssessment(
            status = ProbeStatus.FAIL,
            summary = "$label：有 $withTurns 个会话已产生轮次，但状态字段一个都没写入（功能没生效）。",
            evidence = evidence,
            details = absentSamples.take(10),
        )
        present < withTurns -> ProbeAssessment(
            status = ProbeStatus.WARN,
            summary = "$label：$present/$withTurns 个有轮次的会话写入了状态（其余没有）。",
            evidence = evidence,
            details = absentSamples.take(10),
        )
        else -> ProbeAssessment(
            status = ProbeStatus.OK,
            summary = "$label：$present/$withTurns 个有轮次的会话都已写入状态。",
            evidence = evidence,
        )
    }
}

/** 人物时间线推进：期望值 vs `persona_chapter` 实际值。 */
internal class ChatTimelineProbe : BaseProbe(
    id = "chat.timeline",
    title = "人物时间线推进",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.timeline"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val sessions = focusSessions(ctx)
        if (sessions.isEmpty()) {
            return ProbeResult.noData("还没有产生过对话轮次的会话。")
        }
        var checked = 0
        var drifted = 0
        var withExplicit = 0
        var turnsCrossingBoundary = 0
        val details = mutableListOf<String>()
        sessions.forEach { focus ->
            val start = focus.manifest.intOrNull("start_chapter") ?: 0
            val persona = focus.manifest.intOrNull("persona_chapter") ?: 0
            if (persona > 0) withExplicit++
            val assessment = assessTimelineAdvance(
                startChapter = start,
                turnCount = focus.turnCount,
                personaChapter = persona,
                transcriptCount = focus.manifest.intOrNull("transcript_count"),
            )
            checked++
            val expected = assessment.evidence["expected"]?.toIntOrNull() ?: persona
            if (focus.turnCount >= TURNS_PER_CHAPTER_FOR_PROBE && start + focus.turnCount / TURNS_PER_CHAPTER_FOR_PROBE != start) {
                turnsCrossingBoundary++
            }
            if (assessment.status != ProbeStatus.OK) {
                drifted++
                details += "${focus.runId}/${focus.sessionId}：${assessment.summary}"
            } else if (expected != persona) {
                drifted++
                details += "${focus.runId}/${focus.sessionId}：期望 $expected 实际 $persona"
            }
        }
        val evidence = evidenceMap(
            "sessions" to checked.toString(),
            "drifted" to drifted.toString(),
            "with_persona_chapter" to withExplicit.toString(),
            "crossing_boundary" to turnsCrossingBoundary.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            drifted > 0 -> ProbeResult.fail(
                "有 $drifted/$checked 个会话的时间线推进与期望不一致。",
                built(),
                details.take(20),
            )
            withExplicit == 0 -> ProbeResult.warn(
                "所有会话都没有写回 persona_chapter（可能一直用起始章，弧线不会被按时间点注入）。",
                built(),
            )
            else -> ProbeResult.ok(
                "时间线推进一致：$checked 个会话，期望值与实际值相符。",
                built(),
            )
        }
    }

    private companion object {
        /** 与 `PersonaArcService.TURNS_PER_PERSONA_CHAPTER` 同值；这里只用于证据统计。 */
        const val TURNS_PER_CHAPTER_FOR_PROBE = 4
    }
}

/**
 * 会话完整性：transcript 重复 key + `transcript_count` 一致性 + 新增会话的必需字段。
 *
 * 重复 key 正是 2.1.10 那次闪退的根因（LazyColumn key 撞车）；把它做成运行时校验，
 * 下次回归能在用户设备上先看到 ❌，而不是先收到崩溃。
 */
internal class ChatTranscriptIntegrityProbe : BaseProbe(
    id = "chat.transcript",
    title = "会话完整性",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.transcript_integrity", "chat.session_create"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val sessions = focusSessions(ctx, onlyWithTurns = false)
        if (sessions.isEmpty()) return ProbeResult.noData("还没有任何会话。")

        var checked = 0
        var duplicateSessions = 0
        var mismatchSessions = 0
        var invalidParticipants = 0
        val details = mutableListOf<String>()
        sessions.forEach { focus ->
            val transcript = focus.manifest.arr("transcript")
            val keys = extractTranscriptKeys(transcript)
            val assessment = assessSessionIntegrity(
                transcript = keys,
                declaredTranscriptCount = focus.manifest.intOrNull("transcript_count"),
                transcriptStart = focus.manifest.intOrNull("transcript_start"),
            )
            checked++
            when (assessment.status) {
                ProbeStatus.FAIL -> {
                    duplicateSessions++
                    details += "${focus.runId}/${focus.sessionId}：${assessment.summary}"
                }
                ProbeStatus.WARN -> {
                    mismatchSessions++
                    details += "${focus.runId}/${focus.sessionId}：${assessment.summary}"
                }
                else -> Unit
            }
            // 新增会话校验：participants 必须非空，controlled_character 必须在名单里。
            val participants = focus.manifest.arr("participants").strings().filter { it.isNotBlank() }
            val controlled = focus.manifest.str("controlled_character")
            if (participants.isEmpty() || (controlled.isNotBlank() && controlled !in participants)) {
                invalidParticipants++
                details += "${focus.runId}/${focus.sessionId}：participants=${participants.size} controlled=$controlled"
            }
        }
        val evidence = evidenceMap(
            "sessions" to checked.toString(),
            "duplicate_key_sessions" to duplicateSessions.toString(),
            "count_mismatch_sessions" to mismatchSessions.toString(),
            "invalid_participants" to invalidParticipants.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            duplicateSessions > 0 -> ProbeResult.fail(
                "有 $duplicateSessions 个会话的 transcript 存在重复 key（列表 key 撞车会导致闪退）。",
                built(),
                details.take(20),
            )
            invalidParticipants > 0 -> ProbeResult.warn(
                "有 $invalidParticipants 个会话的参与者/受控角色字段不完整。",
                built(),
                details.take(20),
            )
            mismatchSessions > 0 -> ProbeResult.warn(
                "有 $mismatchSessions 个会话的 transcript_count 与实际条数不一致。",
                built(),
                details.take(20),
            )
            else -> ProbeResult.ok(
                "$checked 个会话：transcript 无重复 key、计数一致、参与者完整。",
                built(),
            )
        }
    }
}

/** 对话生成：最近一轮是否成功、重试次数、是否 stream_error。 */
internal class ChatGenerationProbe : BaseProbe(
    id = "chat.generate",
    title = "对话生成",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.generate"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val events = ctx.recentEvents().map { it.category to it.message }
        val outcome = assessLatestOutcome(
            events = events,
            successCategories = setOf("dialogue.parse_retry_ok"),
            failureCategories = setOf("dialogue.stream_error", "dialogue.parse_failed"),
            label = "对话生成",
            sampleNote = "本进程还没有对话生成记录",
        )
        // 首字延迟（TTFT）目前只进 logcat，不进结构化事件 → 交互成功时单独说明「无数据」。
        val retries = events.count { it.first == "dialogue.parse_retry_ok" }
        val parseFailures = events.count { it.first == "dialogue.parse_failed" }
        val streamErrors = events.count { it.first == "dialogue.stream_error" }
        return outcome.toResult().withEvidence(
            evidenceMap(
                "parse_retries" to retries.toString(),
                "parse_failures" to parseFailures.toString(),
                "stream_errors" to streamErrors.toString(),
            ),
        ).normalized()
    }
}

/** 首字延迟：当前只在运行日志里，结构化事件没有记录 → NO_DATA（不假装 OK）。 */
internal class ChatFirstTokenLatencyProbe : BaseProbe(
    id = "chat.first_token_latency",
    title = "首字延迟",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.first_token_latency"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult = ProbeResult.noData(
        "首字延迟（TTFT）只在运行日志里逐次打印，结构化事件没有记录，无法给出可靠数字。",
        ProbeEvidence.build { number("structured_samples", 0) },
        listOf(
            "现状：DialogueStreamService 把 ttft_ms 写进 PlatformLog，没有落结构化事件。",
            "替代：需要新埋点（把 ttft_ms 写进 dialogue 事件详情）才能在这里给数字；本版不做，避免改变业务行为。",
            "临时手段：运行日志采集开启时，可在「运行日志」页搜索 ttft_ms。",
        ),
    )
}

/** 长期记忆：写入与检索是否成功、条目是否在增长。 */
internal class ChatLongTermMemoryProbe : BaseProbe(
    id = "chat.long_term_memory",
    title = "长期记忆",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.long_term_memory"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val sessions = focusSessions(ctx)
        val events = ctx.recentEvents().map { it.category to it.message }
        val failures = events.count { it.first == "dialogue.long_term_memory" }
        if (sessions.isEmpty()) {
            return if (failures > 0) {
                ProbeResult.fail(
                    "长期记忆写入最近失败过 $failures 次。",
                    ProbeEvidence.build { number("write_failures", failures) },
                )
            } else {
                ProbeResult.noData("还没有产生过轮次的会话，长期记忆无样本。")
            }
        }
        var filesFound = 0
        var totalEntries = 0
        var hitEntries = 0
        var hitSum = 0
        val details = mutableListOf<String>()
        sessions.forEach { focus ->
            val text = ctx.readSessionFile(focus.runId, focus.sessionId, "long_term_memory.json")
            val payload = ctx.parseJsonObject(text)
            if (payload == null) {
                details += "${focus.runId}/${focus.sessionId}：有轮次但没有 long_term_memory.json"
                return@forEach
            }
            filesFound++
            val entries = payload.arr("entries").objects()
            totalEntries += entries.size
            entries.forEach { entry ->
                val hits = entry.intOrNull("hit_count") ?: 0
                if (hits > 0) hitEntries++
                hitSum += hits
            }
        }
        val evidence = evidenceMap(
            "sessions" to sessions.size.toString(),
            "memory_files" to filesFound.toString(),
            "entries" to totalEntries.toString(),
            "retrieved_entries" to hitEntries.toString(),
            "hit_total" to hitSum.toString(),
            "write_failures" to failures.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            failures > 0 -> ProbeResult.fail(
                "长期记忆写入最近失败过 $failures 次（后台派生任务）。",
                built(),
                details.take(20),
            )
            filesFound == 0 -> ProbeResult.fail(
                "有 ${sessions.size} 个会话产生过轮次，但一个长期记忆文件都没有（写入没生效）。",
                built(),
                details.take(20),
            )
            totalEntries == 0 -> ProbeResult.warn(
                "长期记忆文件存在但条目为 0（尚未写入内容）。",
                built(),
                details.take(20),
            )
            hitEntries == 0 -> ProbeResult.warn(
                "长期记忆有 $totalEntries 条，但从未有条目被检索命中（召回可能没生效）。",
                built(),
            )
            else -> ProbeResult.ok(
                "长期记忆可用：$totalEntries 条，其中 $hitEntries 条被检索命中过（累计 $hitSum 次）。",
                built(),
            )
        }
    }
}

/** 世界记忆：读写是否成功。 */
internal class ChatWorldMemoryProbe : BaseProbe(
    id = "chat.world_memory",
    title = "世界记忆",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.world_memory"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val runId = ctx.focusRunId() ?: return ProbeResult.noData("还没有书卷。")
        val events = ctx.recentEvents().map { it.category to it.message }
        val parseFailures = events.count { it.first == "dialogue.payload_parse" }
        val readFailures = events.count { it.first == "dialogue.payload_read" }
        val text = ctx.readRunTextLimited(runId, WORLD_MEMORY_READ_LIMIT, "world_memory.json")
        val payload = ctx.parseJsonObject(text)
        val facts = payload?.arr("facts")?.objects().orEmpty()
        val evidence = evidenceMap(
            "file_present" to (text != null).toString(),
            "facts" to facts.size.toString(),
            "parse_failures" to parseFailures.toString(),
            "read_failures" to readFailures.toString(),
        )
        val built = { evidence.toProbeEvidence() }
        return when {
            parseFailures > 0 -> ProbeResult.fail(
                "世界记忆解析失败过 $parseFailures 次（世界事实整段消失）。",
                built(),
            )
            readFailures > 0 -> ProbeResult.fail(
                "世界记忆读取失败过 $readFailures 次。",
                built(),
            )
            text == null -> ProbeResult.noData("这本书卷还没有 world_memory.json（尚未写过世界事实）。", built())
            payload == null -> ProbeResult.fail("world_memory.json 存在但解析失败。", built())
            else -> ProbeResult.ok("世界记忆可读：${facts.size} 条事实。", built())
        }
    }
}

/**
 * 场景进度与张力：状态是否真的更新（不是永远不变）。
 *
 * **存储结构事实**（2.1.14 修正）：每轮提交后 `DialogueService.refreshDerivedSessionState`
 * 调用 `SceneProgressState.deriveSceneProgressState`，写入会话 manifest 的 **`state`** 字段：
 * `state.scene{location,time_hint,atmosphere_summary,progression_note,updated_at}`、
 * `state.presence{present_participants,offstage_participants}`、
 * `state.progression{should_offer_scene_shift,scene_shift_reason,turns_in_current_scene,beat_maturity,world_tension_summary}`、
 * 以及 `state.signals` / `state.relations` / `state.characters` / `state.memory`。
 *
 * 2.1.13 的探针只在**顶层**找 `scene_progress`、只在 `state.tension` 找张力，于是出现
 * 「`state_written=1` 却 `present=0`」的自相矛盾证据——矛盾来自判据，不是写入路径。
 * 这里按真实结构判定，并容忍旧版/手工写的顶层 `scene_progress` 快照。
 */
internal class ChatSceneProgressProbe : BaseProbe(
    id = "chat.scene_progress",
    title = "场景进度与张力",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf("chat.scene_progress"),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val sessions = focusSessions(ctx)
        val withTurns = sessions.size
        var scenePresent = 0
        var tensionPresent = 0
        var statePresent = 0
        var legacySnapshotOnly = 0
        var emptyStateDespiteTurns = 0
        val absentSamples = mutableListOf<String>()
        val details = mutableListOf<String>()
        sessions.forEach { focus ->
            val state = focus.manifest.obj("state")
            val scene = state?.obj("scene")
            val progression = state?.obj("progression")
            val legacy = focus.manifest.obj("scene_progress")
            // 场景帧：真实结构 state.scene；顶层 scene_progress 只在 state 缺失时兜底。
            val hasScene = (scene != null && scene.isNotEmpty()) || (legacy != null && legacy.isNotEmpty())
            if (hasScene) {
                scenePresent++
                if ((scene == null || scene.isEmpty()) && legacy != null) legacySnapshotOnly++
            } else {
                absentSamples += "${focus.runId}/${focus.sessionId}：无 state.scene 也没有 scene_progress"
            }
            // 张力：真实落点是 state.progression.world_tension_summary（对话提示词注入的就是它）。
            val tension = progression?.str("world_tension_summary").orEmpty()
            val hasTension = tension.isNotBlank() ||
                (state?.obj("tension")?.isNotEmpty() == true) ||
                (focus.manifest.obj("scene_tension")?.isNotEmpty() == true)
            if (hasTension) {
                tensionPresent++
            } else if (hasScene) {
                details += "${focus.runId}/${focus.sessionId}：有场景状态但没有张力摘要（world_tension_summary 为空）"
            }
            if (state != null && state.isNotEmpty()) statePresent++ else emptyStateDespiteTurns++
        }
        val assessment = assessDerivedStatePresence(
            label = "场景进度",
            sessionsChecked = withTurns,
            withTurns = withTurns,
            present = scenePresent,
            absentSamples = absentSamples,
            extraEvidence = evidenceMap(
                "state_written" to statePresent.toString(),
                "tension_written" to tensionPresent.toString(),
                "empty_state" to emptyStateDespiteTurns.toString(),
                "legacy_scene_snapshot" to legacySnapshotOnly.toString(),
            ),
        )
        val result = assessment.toResult()
        return if (details.isEmpty()) {
            result
        } else {
            result.copy(details = (result.details + details).take(ProbeResult.MAX_DETAIL_LINES))
        }
    }
}

/** 一致性监控 / 会话标题 / 故事回顾 / 卡片：状态是否存在（生成质量不做模型评审）。 */
internal class ChatDerivedArtifactsProbe : BaseProbe(
    id = "chat.derived_artifacts",
    title = "一致性 / 标题 / 回顾 / 卡片",
    domain = KnownFeature.DOMAIN_CHAT,
    features = listOf(
        "chat.consistency",
        "chat.session_title",
        "chat.story_recap",
        "chat.cards",
        "chat.crossovers",
    ),
) {
    override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
        val sessions = focusSessions(ctx)
        val runId = ctx.focusRunId()
        var titles = 0
        var consistency = 0
        var reviewed = 0
        var recap = 0
        var ledger = 0
        var disclosureCandidates = 0
        sessions.forEach { focus ->
            if (focus.manifest.str("title").isNotBlank()) titles++
            val monitor = focus.manifest.obj("consistency_monitor")
            if (monitor != null && monitor.isNotEmpty()) consistency++
            if (monitor?.obj("latest")?.isNotEmpty() == true) reviewed++
            if (monitor?.arr("knowledge_ledger")?.isNotEmpty() == true) ledger++
            val storyState = focus.manifest.obj("story_state")
            val history = focus.manifest.arr("story_history")
            if ((storyState != null && storyState.isNotEmpty()) || history.isNotEmpty()) recap++
            // 「台账为空」到底正不正常？用与写入侧**同一口径**的模式数一下可登记的披露句。
            // 只扫最近 120 条，保证探针依然廉价。
            disclosureCandidates += focus.manifest.arr("transcript").objects()
                .takeLast(DISCLOSURE_SCAN_ENTRIES)
                .count { entry ->
                    val text = entry.str("message")
                    text.isNotEmpty() && KNOWLEDGE_LEDGER_DISCLOSURE_PATTERNS.any { it.containsMatchIn(text) }
                }
        }
        // 卡片目录：场景卡/自设卡是全局目录
        val cardsDir = runId?.let { ctx.storage.getStorageRoot() / "scene-cards" }
        val cardCount = if (cardsDir != null && ctx.isDirectory(cardsDir)) ctx.listFiles(cardsDir).size else 0
        // 每轮派生任务失败会落 dialogue.post_turn_state
        val postTurnFailures = ctx.recentEvents().count { it.category == "dialogue.post_turn_state" }

        val assessment = assessDerivedArtifacts(
            DerivedArtifactFacts(
                sessions = sessions.size,
                titled = titles,
                withConsistencyMonitor = consistency,
                withReviewedLatest = reviewed,
                withKnowledgeLedger = ledger,
                withStoryRecap = recap,
                cardFiles = cardCount,
                postTurnFailures = postTurnFailures,
                disclosureCandidates = disclosureCandidates,
            ),
        )
        return assessment.toResult()
    }
}

/** 披露句扫描的条目上限：探针必须廉价（只说明「有没有可登记的事实」，不做全文分析）。 */
private const val DISCLOSURE_SCAN_ENTRIES = 120

private const val WORLD_MEMORY_READ_LIMIT = 400_000

/** 注册会话与对话域的全部探针。 */
fun registerChatProbes(registry: FeatureProbeRegistry) {
    registry.registerAll(
        ChatTimelineProbe(),
        ChatTranscriptIntegrityProbe(),
        ChatGenerationProbe(),
        ChatFirstTokenLatencyProbe(),
        ChatLongTermMemoryProbe(),
        ChatWorldMemoryProbe(),
        ChatSceneProgressProbe(),
        ChatDerivedArtifactsProbe(),
    )
}
