package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归：同一 turnId 重复提交必须幂等。
 *
 * 实机症状：角色同一句回复渲染成**多个内容相同的气泡**，且每重试一次多一份。
 *
 * turnId 就是客户端传入的 operationId（见 DialogueStreamService：
 * `turnId = operationId.ifBlank { randomUuid() }`），而「重试上一次发送」会**复用**
 * 同一个 operationId。于是当「发送后连接中断、但服务端其实已经提交」时，
 * 用户再点重试就会让同一轮被提交第二次：transcript 里出现两条 turn_id 相同的消息。
 * 客户端 mergeTranscript 只做 base 与 append 之间的 turnId 去重，挡不住 append 自身的重复，
 * 两份都会渲染出来。
 */
class DialogueCommitIdempotencyTest {

    private class Harness {
        val dir = createTempDirectory("zaomeng-commit-idempotent")
        val storage = StorageService(dir.toString().toPath())
        val dialogue = DialogueService(storage)
        val sessions = SessionManagementService(storage, dialogue)
        val sessionId: String

        init {
            storage.writeRunManifest(
                "run-1",
                buildJsonObject { put("run_id", "run-1"); put("title", "测试") },
            )
            val created = sessions.createDialogueSession(
                runId = "run-1",
                mode = "observe",
                participants = listOf("角色甲"),
            )
            sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        fun commit(turnId: String, text: String = "同一句话") = runBlocking {
            dialogue.commitTurn(
                runId = "run-1",
                sessionId = sessionId,
                turnId = turnId,
                message = "你好",
                messageKind = "dialogue",
                inputSpeaker = "我",
                responses = listOf(DialogueResponse(speaker = "角色甲", message = text)),
            )
        }

        fun close() = dir.toFile().deleteRecursively()
    }

    @Test
    fun `committing the same turn id twice appends only one copy`() {
        val h = Harness()
        try {
            h.commit("op-1")
            // 幂等重放：模拟客户端复用同一 operationId 重试
            h.commit("op-1")

            val manifest = h.storage.loadSessionManifest("run-1", h.sessionId)
            val turnItems = transcriptByTurnId(manifest, "op-1")

            assertEquals(
                2, turnItems.size,
                "一轮 = 用户 1 条 + 助手 1 条；同一 turnId 重复提交不应追加第二份：$turnItems",
            )
            assertEquals(
                1,
                turnItems.count { it["role"]?.jsonPrimitive?.contentOrNull != "user" },
                "助手回复只应有一条：$turnItems",
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `a different turn id still appends normally`() {
        val h = Harness()
        try {
            h.commit("op-1", text = "第一句")
            h.commit("op-2", text = "第二句")

            val manifest = h.storage.loadSessionManifest("run-1", h.sessionId)

            assertEquals(2, transcriptByTurnId(manifest, "op-1").size)
            assertEquals(2, transcriptByTurnId(manifest, "op-2").size)
        } finally {
            h.close()
        }
    }
}
