package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.DialogueSessionDto

/**
 * 回归：用户点「停止生成」之后的状态收敛。
 *
 * 线上问题形状：生成期间所有出口都被禁用，模型卡住时只能退出会话，而退出连同刚打的字一起丢。
 * 加入「停止」后必须钉住三件事：这一轮要真的收尾、原话要回到输入框、不能伪装成「已发出」。
 */
class ChatStopGeneratingStateTest {
    private fun sendingState(pendingMessage: String = "让他去开门") = ChatUiState(
        runId = "run-1",
        sessionId = "session-1",
        loading = false,
        sending = true,
        session = DialogueSessionDto(sessionId = "session-1", status = "ready"),
        streamingReplies = listOf(
            StreamingReplyPart(index = 0, speaker = "陆城", text = "半截台词"),
        ),
        pendingUserMessage = PendingUserMessage(
            operationId = "op-1",
            message = pendingMessage,
            messageKind = "dialogue",
            status = PendingUserMessageStatus.Sending,
        ),
        draft = "",
    )

    @Test
    fun stopClosesTheTurnAndRestoresDraft() {
        val after = sendingState().afterStopGenerating()

        assertFalse(after.sending, "停止后不能仍处于发送中")
        assertNull(after.pendingUserMessage, "停止后不应残留待发送气泡")
        assertEquals(emptyList<StreamingReplyPart>(), after.streamingReplies, "未收录的流式内容必须清掉")
        assertEquals("让他去开门", after.draft, "原话要回到输入框，避免用户重打")
    }

    @Test
    fun stopDoesNotPretendToBeARetryableFailure() {
        val before = sendingState().copy(
            failedOperationId = "op-1",
            failedMessage = "让他去开门",
            sendOutcomeUnknown = true,
        )

        val after = before.afterStopGenerating()

        assertEquals("", after.failedOperationId, "停止不是失败，不能留下可重试的失败态")
        assertEquals("", after.failedMessage)
        assertFalse(after.sendOutcomeUnknown, "停止发生在提交之前，结果并不未知")
    }

    @Test
    fun stopExplainsItselfAndAllowsResending() {
        val after = sendingState().afterStopGenerating()

        assertTrue(after.notice.isNotBlank(), "必须告诉用户这一轮没有写进会话")
        assertTrue(after.canSend, "停止后应当可以立即重发")
        assertEquals("", after.error, "停止不是错误，不要染红")
    }

    @Test
    fun stopKeepsDraftWhenThereWasNoPendingMessage() {
        val after = sendingState()
            .copy(pendingUserMessage = null, draft = "手边还没发的一句")
            .afterStopGenerating()

        assertEquals("手边还没发的一句", after.draft)
    }
}
