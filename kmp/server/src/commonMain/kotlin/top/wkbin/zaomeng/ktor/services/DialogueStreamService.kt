package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.ktor.utils.DialogueStreamParser
import top.wkbin.zaomeng.ktor.utils.StreamEvent
import top.wkbin.zaomeng.platform.DiagnosticsPayloadSink
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 对话流式服务
 *
 * 处理流式对话响应，使用 SSE (Server-Sent Events)。
 * 流式结束后从完整输出解析 responses 并提交 turn（保存 + 更新会话 manifest）。
 */
class DialogueStreamService(
    private val storageService: StorageService,
    private val llmClient: LlmClient,
    private val promptLoader: PromptLoader,
    private val dialogue: DialogueService,
    private val eventLog: DiagnosticsEventLog? = null,
) {
    companion object {
        private const val TAG = "DialogueStreamService"
        private const val OUTPUT_EXCERPT_CHARS = 800
    }

    /**
     * 流式回复对话轮次
     *
     * @param runId 运行 ID
     * @param sessionId 会话 ID
     * @param message 用户输入消息
     * @param messageKind 消息类型
     * @param includeInnerThoughts 是否包含内心想法
     * @param operationId 操作 ID（作为 turn_id，供幂等/恢复）
     * @return Flow<StreamEvent> 流式事件
     */
    suspend fun replyDialogueTurnStream(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        pacing: String = "normal",
        speakerOverride: String = "",
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeModelReasoning: Boolean = false,
    ): Flow<StreamEvent> = flow {
        val startedAt = nowEpochMillis()
        PlatformLog.d(TAG, "Starting streaming dialogue reply: run=$runId, session=$sessionId")

        // 1. 加载模型设置
        val modelSettings = storageService.loadModelSettings()
        val modelName = modelSettings["model"] as? String ?: "gpt-4"
        val settingsLoadedAt = nowEpochMillis()

        // 2. 构建提示词（对齐 Python build_dialogue_llm_messages 完整管道）
        val payloadBuilder = DialoguePayloadBuilder(storageService)
        val promptBuilder = DialoguePromptBuilder(promptLoader)
        val runManifest = storageService.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val turnId = operationId.ifBlank { randomUuid() }
        val sessionManifestJson = dialogue.prepareSessionForGeneration(runId, sessionId, turnId, message)
        val manifestsLoadedAt = nowEpochMillis()
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = sessionManifestJson,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            speakerOverride = speakerOverride,
            includeInnerThoughts = includeInnerThoughts || DialogueService.isInnerThoughtsEnhancerActive(sessionManifestJson),
        )
        val payloadBuiltAt = nowEpochMillis()

        // 3. 构建对话历史
        val conversationHistory = DialogueService.applyPacingInstruction(
            messages = promptBuilder.buildDialogueLlmMessages(payload = payload, retryOnEmpty = false),
            pacing = pacing,
        )
        val promptBuiltAt = nowEpochMillis()
        val promptChars = conversationHistory.sumOf { it.content?.length ?: 0 }

        // 4. 计算 max_tokens（对齐 Python：推理模型 reasoning_content 计入预算，默认至少 8192）
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 0
        val maxTokens = DialogueService.resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = modelSettings["reasoning_effort"] as? String ?: "auto",
            pacing = pacing,
        )

        // Apply the same speaker contract while streaming that is used for the final parse.
        // Otherwise a model response for the user-controlled character is briefly rendered and
        // then disappears when the committed transcript correctly filters it out.
        val inputMap = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        // 与最终解析共用同一份身份归一化：历史会话里存的 `"陆城"` 要当成 `陆城`，
        // 否则同一角色会同时出现在允许与禁止名单，模型输出干净名字会被整轮过滤。
        val participants = CharacterNameNormalizer.normalizeNames(
            ((inputMap["allowed_responders"] as? List<*>) ?: emptyList<Any?>())
                .mapNotNull { it?.toString() },
        ) + listOf("旁白", "场景提示")
        val forbidden = listOf(
            inputMap["controlled_character"]?.toString().orEmpty(),
            inputMap["speaker"]?.toString().orEmpty(),
        ).map(CharacterNameNormalizer::normalize).filter(String::isNotEmpty)

        // 5. 创建流式解析器（密钥由 LlmClient 从活动模型配置与 Keystore 解析，无需在此获取）
        val parser = DialogueStreamParser(chunkSize = 24)
        val modelStartedAt = nowEpochMillis()
        var firstContentAt: Long? = null

        // 6. 调用 LLM 流式 API
        try {
            llmClient.chatCompletionStream(
                messages = conversationHistory,
                model = modelName,
                temperature = 0.7,
                maxTokens = maxTokens,
                // 请求参数（thinking / reasoning_effort）由模型设置的 reasoning_effort 决定（对齐 Python _apply_reasoning_controls）；
                // enableReasoning 仅控制下方 onReasoning 是否把推理过程透传为 model_reasoning delta，不影响模型是否思考
                enableReasoning = includeModelReasoning,
                requireJsonObject = false,
                onReasoning = { reasoning ->
                    // 推理过程透传为 model_reasoning delta（对齐 Python on_reasoning → SSE model_reasoning）
                    if (includeModelReasoning) {
                        emit(StreamEvent(index = 0, speaker = "", role = "reasoning", field = "model_reasoning", text = reasoning))
                    }
                },
            ).collect { contentDelta ->
                if (firstContentAt == null) {
                    val firstAt = nowEpochMillis()
                    firstContentAt = firstAt
                    PlatformLog.d(
                        TAG,
                        "Dialogue first content: preprocess_ms=${modelStartedAt - startedAt}, " +
                            "ttft_ms=${firstAt - modelStartedAt}, prompt_chars=$promptChars",
                    )
                }
                filterDialogueStreamEvents(parser.feed(contentDelta), participants, forbidden).forEach { emit(it) }
            }
            val firstModelCompletedAt = nowEpochMillis()

            // 7. 流结束：从完整输出解析 responses 并提交 turn（对齐 Python generate_and_commit）
            val full = parser.fullContent()
            // 解析失败重试一次（对齐 Python generate_dialogue_responses 的 0..1 重试循环：
            // retryOnEmpty 提示词 + maxTokens 翻倍到上限；第二次仍失败则抛错 → SSE error）
            var responses: List<DialogueResponse>
            try {
                responses = DialogueResponseParser.parse(full, participants, forbidden)
            } catch (e: IllegalArgumentException) {
                recordDialogueParseFailure(
                    attempt = 1,
                    error = e,
                    output = full,
                    participants = participants,
                    forbidden = forbidden,
                    modelSettings = modelSettings,
                )
                val plainTextFallback = DialogueResponseParser.parseSingleSpeakerPlainText(
                    full,
                    participants,
                    forbidden,
                )
                if (plainTextFallback != null) {
                    responses = plainTextFallback
                    PlatformLog.w(
                        TAG,
                        "Streaming reply used single-speaker plain-text fallback after format error: ${e.message}",
                    )
                } else {
                    PlatformLog.e(
                        TAG,
                        "Streaming reply parse failed (attempt 1/2): ${e.message}" +
                            "\n--- full output (first 800 chars) ---\n${full.take(800)}" +
                            "\n--- participants: $participants / forbidden: $forbidden ---",
                    )
                    val retryHistory = DialogueService.applyPacingInstruction(
                        promptBuilder.buildDialogueLlmMessages(payload, retryOnEmpty = true),
                        pacing,
                    )
                    val retryMaxTokens = minOf(maxTokens * 2, DialogueService.DIALOGUE_RESPONSE_MAX_MAX_TOKENS)
                    emit(
                        StreamEvent(
                            index = 0,
                            speaker = "",
                            role = "status",
                            field = "message",
                            text = "正在重新整理回复…",
                            kind = "reset",
                        ),
                    )
                    val retryParser = DialogueStreamParser(chunkSize = 24)
                    val retryStartedAt = nowEpochMillis()
                    var retryFirstContentAt: Long? = null
                    llmClient.chatCompletionStream(
                        messages = retryHistory,
                        model = modelName,
                        temperature = 0.7,
                        maxTokens = retryMaxTokens,
                        enableReasoning = includeModelReasoning,
                        requireJsonObject = false,
                        onReasoning = { reasoning ->
                            if (includeModelReasoning) {
                                emit(
                                    StreamEvent(
                                        index = 0,
                                        speaker = "",
                                        role = "reasoning",
                                        field = "model_reasoning",
                                        text = reasoning,
                                    ),
                                )
                            }
                        },
                    ).collect { contentDelta ->
                        if (retryFirstContentAt == null) retryFirstContentAt = nowEpochMillis()
                        filterDialogueStreamEvents(
                            retryParser.feed(contentDelta),
                            participants,
                            forbidden,
                        ).forEach { emit(it) }
                    }
                    val retryFull = retryParser.fullContent().trim()
                    PlatformLog.d(
                        TAG,
                        "Streaming reply retry (attempt 2/2) returned in ${nowEpochMillis() - retryStartedAt}ms " +
                            "(ttft_ms=${retryFirstContentAt?.minus(retryStartedAt) ?: -1}): ${retryFull.take(200)}",
                    )
                    responses = try {
                        DialogueResponseParser.parse(retryFull, participants, forbidden).also {
                            eventLog?.record(
                                category = "dialogue.parse_retry_ok",
                                message = "第一次解析失败，重试后成功。",
                                details = dialogueFailureDetails(
                                    attempt = 2,
                                    output = retryFull,
                                    participants = participants,
                                    forbidden = forbidden,
                                    modelSettings = modelSettings,
                                ),
                            )
                        }
                    } catch (e: IllegalArgumentException) {
                        recordDialogueParseFailure(
                            attempt = 2,
                            error = e,
                            output = retryFull,
                            participants = participants,
                            forbidden = forbidden,
                            modelSettings = modelSettings,
                        )
                        // R5：**完整**提示词与**完整**模型输出。
                        //
                        // 此前这两样只留 800 字符摘录，多人对话的结构错误恰恰出在摘录之后
                        // （第 N 个 speaker 的名字被写错、JSON 围栏在第 3000 字符才闭合）。
                        // 现在只在**最终失败**时全量记录：每次都记会让日志量随轮数线性膨胀，
                        // 而「解析失败且重试也失败」才是真正需要完整原文的时刻。
                        //
                        // 上限是**工程上限**（DiagnosticsSink.MAX_PAYLOAD_CHARS），不是隐私截断；
                        // 写入侧仍会过一遍 LogRedactor，API Key / 签名口令不会落盘。
                        DiagnosticsPayloadSink.record(
                            category = "dialogue.parse_failed.payload",
                            label = "对话解析最终失败：完整提示词与完整模型输出",
                            payload = buildMap {
                                put("run_id", runId)
                                put("session_id", sessionId)
                                put("attempt", "2")
                                put("participants", participants.joinToString("、"))
                                put("forbidden", forbidden.joinToString("、"))
                                (modelSettings["provider"] as? String)?.let { put("provider", it) }
                                (modelSettings["model"] as? String)?.let { put("model", it) }
                                put("model_output", retryFull)
                                put(
                                    "prompt",
                                    retryHistory.joinToString("\n---\n") { message ->
                                        "${message.role}: ${message.content.orEmpty()}"
                                    },
                                )
                            },
                        )
                        throw e
                    }
                }
            }
            val parsedAt = nowEpochMillis()
            dialogue.commitTurn(
                runId = runId,
                sessionId = sessionId,
                turnId = turnId,
                message = message,
                messageKind = messageKind,
                inputSpeaker = inputMap["speaker"]?.toString().orEmpty(),
                inputRole = if (speakerOverride.isNotBlank()) "character" else "user",
                responses = responses,
                suppressTranscriptMessage = suppressTranscriptMessage,
                existingSession = sessionManifestJson,
                evidence = dialogue.extractOriginalEvidence(payload),
            )
            val committedAt = nowEpochMillis()
            PlatformLog.d(
                TAG,
                "Streaming dialogue reply completed and committed: total_ms=${committedAt - startedAt}, " +
                    "settings_ms=${settingsLoadedAt - startedAt}, manifests_ms=${manifestsLoadedAt - settingsLoadedAt}, " +
                    "payload_ms=${payloadBuiltAt - manifestsLoadedAt}, prompt_ms=${promptBuiltAt - payloadBuiltAt}, " +
                    "first_model_ms=${firstModelCompletedAt - modelStartedAt}, parse_retry_ms=${parsedAt - firstModelCompletedAt}, " +
                    "commit_ms=${committedAt - parsedAt}, prompt_chars=$promptChars",
            )
        } catch (e: ContentRejectedException) {
            // 提供方内容审核拒绝：**不是** `dialogue.stream_error`。
            //
            // 用户真机上两类 400（`inference request is invalid` 与
            // `Inappropriate input/output rejected for security reasons`）此前都落成 stream_error，
            // 用户因此以为应用坏了。审核拒绝单独记一条更准确的类别：
            // 「运行诊断 → 事件统计 / 问题清单」把它算作 info 级的**提供方策略拒绝**（独立分组），
            // 而不是应用缺陷。**只记这一条**，不重复记 stream_error，否则两类又会混在一起。
            eventLog?.record(
                category = LlmClient.CONTENT_REJECTED_CATEGORY,
                message = e.message ?: "内容被模型提供方拒绝（非应用故障）",
                details = buildMap {
                    put("error_type", e::class.simpleName.orEmpty())
                    put("status_code", e.statusCode.toString())
                    put("provider_kind", e.providerKind)
                    if (e.model.isNotBlank()) put("model", e.model)
                    // endpoint 在 LlmClient 里已去掉 query（Gemini 的 key 不会进来）；
                    // 写入侧仍有 LogRedactor 兜底。
                    if (e.endpoint.isNotBlank()) put("endpoint", e.endpoint)
                    if (e.upstreamMessage.isNotBlank()) put("upstream_message", e.upstreamMessage)
                    put("retryable", "false")
                    put("run_id", runId)
                    put("session_id", sessionId)
                    put("message_kind", messageKind)
                },
            )
            PlatformLog.w(
                TAG,
                "Dialogue turn rejected by provider content moderation " +
                    "(model=${e.model.ifBlank { "?" }}, endpoint=${e.endpoint}, http=${e.statusCode}); not retried",
            )
            throw e
        } catch (e: Exception) {
            eventLog?.record(
                category = "dialogue.stream_error",
                message = e.message ?: e::class.simpleName.orEmpty(),
                details = buildMap {
                    put("error_type", e::class.simpleName.orEmpty())
                    put("run_id", runId)
                    put("session_id", sessionId)
                    put("message_kind", messageKind)
                },
            )
            PlatformLog.e(TAG, "Error during streaming dialogue reply after ${nowEpochMillis() - startedAt}ms", e)
            throw e
        }
    }

    /** 记录一次对话解析失败：模型输出摘录 + 允许/禁言名单 + 脱敏后的模型信息。 */
    private fun recordDialogueParseFailure(
        attempt: Int,
        error: IllegalArgumentException,
        output: String,
        participants: List<String>,
        forbidden: List<String>,
        modelSettings: Map<String, Any?>,
    ) {
        eventLog?.record(
            category = "dialogue.parse_failed",
            message = error.message ?: "parse failed",
            details = dialogueFailureDetails(
                attempt = attempt,
                output = output,
                participants = participants,
                forbidden = forbidden,
                modelSettings = modelSettings,
            ),
        )
    }

    private fun dialogueFailureDetails(
        attempt: Int,
        output: String,
        participants: List<String>,
        forbidden: List<String>,
        modelSettings: Map<String, Any?>,
    ): Map<String, String> = buildMap {
        put("attempt", attempt.toString())
        (modelSettings["provider"] as? String)?.let { put("provider", it) }
        (modelSettings["model"] as? String)?.let { put("model", it) }
        (modelSettings["base_url"] as? String)?.let { put("base_url", sanitizeEndpoint(it)) }
        (modelSettings["reasoning_effort"] as? String)?.let { put("reasoning_effort", it) }
        put("allowed_responders", participants.joinToString("、").ifBlank { "(空)" })
        put("forbidden_responders", forbidden.joinToString("、").ifBlank { "(空)" })
        put("output_chars", output.length.toString())
        put("output_excerpt", output.take(OUTPUT_EXCERPT_CHARS).ifBlank { "(空输出)" })
    }

    /** 去掉 query/fragment，避免把可能带 key 的 URL 原文写进诊断文件。 */
    private fun sanitizeEndpoint(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.substringBefore('?').substringBefore('#')
        // 权威段从 "://" 之后开始找分隔符——不能从整串找第一个 '/'，那会落在 "https://" 里。
        val authorityStart = schemeEnd + 3
        var authorityEnd = trimmed.length
        for (index in authorityStart until trimmed.length) {
            val char = trimmed[index]
            if (char == '/' || char == '?' || char == '#') {
                authorityEnd = index
                break
            }
        }
        return trimmed.substring(0, authorityEnd)
    }
}

internal fun filterDialogueStreamEvents(
    events: List<StreamEvent>,
    allowedSpeakers: List<String>,
    forbiddenSpeakers: List<String>,
): List<StreamEvent> {
    val allowedByNormalized = allowedSpeakers
        .map(String::trim)
        .filter(String::isNotEmpty)
        .associateBy(::normalizeDialogueSpeaker)
    val forbidden = forbiddenSpeakers
        .map(::normalizeDialogueSpeaker)
        .filter(String::isNotEmpty)
        .toSet()
    return events.mapNotNull { event ->
        val normalized = normalizeDialogueSpeaker(event.speaker)
        if (normalized.isEmpty() || normalized in forbidden) return@mapNotNull null
        val canonical = allowedByNormalized[normalized] ?: return@mapNotNull null
        if (event.speaker == canonical) event else event.copy(speaker = canonical)
    }
}

private fun normalizeDialogueSpeaker(value: String): String =
    // 与 DialogueResponseParser.normalizeSpeakerName 共用同一个身份键：
    // 忽略空白与首尾成对引号，人物名带引号时也能匹配上。
    CharacterNameNormalizer.identityKey(value)
