package top.wkbin.zaomeng.domain.chat

import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse

/** Narrow data boundary for opening a chat. */
interface ChatSessionGateway {
    suspend fun listSessions(runId: String? = null): List<DialogueSessionDto>

    suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean = true,
    ): DialogueSessionDto

    suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int = 0,
        limit: Int = 100,
        order: String = "asc",
    ): MessagesResponse

    suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean = false,
    ): DialogueSessionDto

    suspend fun getPersonaAvatar(runId: String, character: String, version: String): ByteArray?

    /**
     * 读取某角色的人物包字段（声线的来源：`voice_name` / `voice_pitch` / `voice_speed`）。
     *
     * 返回**完整字段**而不是只挑声线项：「哪些键属于声线」这个知识只应存在于
     * `ui:shared` 的 `toAuthoredVoice()` 一处，不在模块之间复制一份可能漂移的清单。
     */
    suspend fun getPersonaFields(runId: String, character: String): Map<String, String>

    suspend fun getDialogueMemoryQuality(runId: String, sessionId: String): MemoryQualityReportDto
}

data class LoadedChatSession(
    val session: DialogueSessionDto,
    val runSessions: List<DialogueSessionDto>,
    val avatarBytes: Map<String, ByteArray>,
    /**
     * 参演角色 → 人物包字段。朗读据此拿到作者为该角色填写的声线；
     * 缺项或取不到时由朗读侧回落到「按角色名确定性派生」，所以这里**允许为空**。
     */
    val personaFields: Map<String, Map<String, String>>,
    val memoryQuality: MemoryQualityReportDto,
)

/**
 * Opens a chat as one business operation: recover an unfinished session, hydrate the
 * bounded transcript tail, and load optional side data without failing the main chat.
 */
class LoadChatSessionUseCase(
    private val gateway: ChatSessionGateway,
) {
    suspend operator fun invoke(
        runId: String,
        sessionId: String,
        transcriptPageSize: Int = DEFAULT_TRANSCRIPT_PAGE_SIZE,
    ): LoadedChatSession {
        val runSessions = runCatching { gateway.listSessions(runId) }.getOrDefault(emptyList())
        val loadedSession = gateway.getSession(runId, sessionId, includeTranscript = false)
        val session = when {
            loadedSession.status != "ready" -> gateway.recoverSession(runId, sessionId, force = true)
            loadedSession.transcript.isEmpty() && loadedSession.transcriptCount > 0 -> {
                val tail = gateway.listSessionMessages(
                    runId = runId,
                    sessionId = sessionId,
                    offset = 0,
                    limit = transcriptPageSize.coerceAtLeast(1),
                    order = "desc",
                )
                loadedSession.copy(
                    transcript = tail.items.asReversed(),
                    transcriptCount = tail.total,
                )
            }
            else -> loadedSession
        }
        val avatars = session.characterAvatars.mapNotNull { (character, version) ->
            runCatching { gateway.getPersonaAvatar(runId, character, version) }
                .getOrNull()
                ?.let { character to it }
        }.toMap()
        val memoryQuality = runCatching { gateway.getDialogueMemoryQuality(runId, sessionId) }
            .getOrDefault(MemoryQualityReportDto())
        // 人物包字段：逐角色 best-effort 读取。任何一个角色读不到都不影响打开会话——
        // 声线在该角色上回落到按名字派生，其余角色照常。
        val personaFields = session.characterAvatars.keys.associateWith { character ->
            runCatching { gateway.getPersonaFields(runId, character) }.getOrDefault(emptyMap())
        }
        return LoadedChatSession(
            session = session,
            runSessions = runSessions,
            avatarBytes = avatars,
            personaFields = personaFields,
            memoryQuality = memoryQuality,
        )
    }

    private companion object {
        const val DEFAULT_TRANSCRIPT_PAGE_SIZE = 100
    }
}
