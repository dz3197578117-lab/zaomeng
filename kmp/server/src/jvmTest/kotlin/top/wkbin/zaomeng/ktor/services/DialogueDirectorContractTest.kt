package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.PromptSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导演指令强度回归测试。
 *
 * 线上症状：导演给出的方案，角色没有完全按指令落实。
 * 根因有两处：
 * 1) 契约 `plot_progression_contract` 之前只对 messageKind=plot 生效，
 *    fourth_wall / narration 直接返回空串 → 导演模式零约束；
 * 2) 契约没有一条要求「导演选中的方案必须本轮落实」，且导演指令在 payload 里
 *    只是 message 字段，和「某人说了句话」混同。
 */
class DialogueDirectorContractTest {
    /** 用仓库里真实的 prompts/ 资源，避免假 source 缺 key 触发 PromptLoader 的严格校验。 */
    private class RepoPromptSource : PromptSource {
        private val root: java.io.File = locatePromptsRoot()

        override fun read(relativePath: String): Pair<String, Long>? {
            val file = java.io.File(root, relativePath)
            return if (file.isFile) file.readText() to file.lastModified() else null
        }

        override fun lastModified(relativePath: String): Long? =
            java.io.File(root, relativePath).takeIf { it.isFile }?.lastModified()
    }

    private fun newStorage(): StorageService =
        StorageService(createTempDirectory("zaomeng-director").toString().toPath())

    private fun runManifest() = buildJsonObject {
        put("run_id", "run")
        put("artifact_index", buildJsonObject {
            put("characters", buildJsonArray {
                add(buildJsonObject { put("name", "李慧英") })
                add(buildJsonObject { put("name", "陆城") })
            })
        })
    }

    private fun session() = buildJsonObject {
        put("session_id", "session")
        put("mode", "observe")
        put("participants", buildJsonArray {
            add(JsonPrimitive("李慧英"))
            add(JsonPrimitive("陆城"))
        })
        put("transcript", buildJsonArray {
            add(buildJsonObject {
                put("speaker", "陆城")
                put("message", "这事我不掺和。")
                put("role", "character")
            })
        })
    }

    @Test
    fun `contract covers every director input kind`() {
        listOf("plot", "fourth_wall", "narration").forEach { kind ->
            val contract = DialoguePromptRules.plotProgressionContract(kind, emptyMap())
            assertTrue(contract.isNotBlank(), "$kind 必须有导演指令契约")
            assertTrue(contract.contains("DIRECTOR_DIRECTIVE"), "$kind 契约需包含 DIRECTOR_DIRECTIVE")
            assertTrue(contract.contains("be realized on screen now"), "$kind 契约需强调本轮必须落实")
            assertTrue(contract.contains("never defer it to a later turn"), "$kind 契约需禁止推迟")
        }
    }

    @Test
    fun `plain dialogue is not forced to carry a director contract`() {
        // 普通对话不该被导演契约污染，否则角色会莫名开始「推进剧情」
        assertEquals("", DialoguePromptRules.plotProgressionContract("dialogue", emptyMap()))
    }

    @Test
    fun `fourth wall contract keeps character agency but forbids ignoring the directive`() {
        val contract = DialoguePromptRules.plotProgressionContract("fourth_wall", emptyMap())
        assertTrue(contract.contains("resistance is allowed"), "应保留人物性格化抗拒")
        assertTrue(contract.contains("may never ignore the directive"), "但不得无视导演指令")
    }

    @Test
    fun `mature beat adds turn-the-scene advice`() {
        val contract = DialoguePromptRules.plotProgressionContract("plot", mapOf("beat_maturity" to 85))
        assertTrue(contract.contains("already mature"))
    }

    @Test
    fun `fourth wall turn ships the contract and an explicit director directive field`() = runBlocking {
        val storage = newStorage()
        val directive = "她直视作者，表示这个安排违背她的底线；让角色拒绝作者的和好安排"
        val payload = DialoguePayloadBuilder(storage).buildTurnPayload(
            runManifest = runManifest(),
            session = session(),
            turnId = "turn-1",
            message = directive,
            messageKind = "fourth_wall",
        )

        val instructions = payload["instructions"] as Map<*, *>
        assertTrue(
            instructions["plot_progression_contract"].toString().contains("DIRECTOR_DIRECTIVE"),
            "payload 的指令契约不应为空",
        )

        val messages = DialoguePromptBuilder(PromptLoader(RepoPromptSource())).buildDialogueLlmMessages(payload)
        val system = messages.first { it.role == "system" }.content.orEmpty()
        val user = messages.first { it.role == "user" }.content.orEmpty()

        assertTrue(system.contains("DIRECTOR_DIRECTIVE"), "system 必须携带导演指令契约")
        assertTrue(user.contains("director_directive"), "user payload 必须显式标注导演指令")
        assertTrue(user.contains("director_directive_binding"), "user payload 必须声明指令是强制的")
        assertTrue(user.contains(directive.take(12)), "指令原文要出现在 payload 里")
    }

    @Test
    fun `dialogue turn does not add director directive fields`() = runBlocking {
        val storage = newStorage()
        val payload = DialoguePayloadBuilder(storage).buildTurnPayload(
            runManifest = runManifest(),
            session = session(),
            turnId = "turn-2",
            message = "你到底想说什么？",
            messageKind = "dialogue",
        )

        val messages = DialoguePromptBuilder(PromptLoader(RepoPromptSource())).buildDialogueLlmMessages(payload)
        val system = messages.first { it.role == "system" }.content.orEmpty()
        val user = messages.first { it.role == "user" }.content.orEmpty()

        assertFalse(system.contains("DIRECTOR_DIRECTIVE"), "普通对话不应带导演契约")
        assertFalse(user.contains("director_directive"), "普通对话不应带导演指令字段")
    }
}

/** 从测试工作目录向上找到仓库的 prompts/ 目录（PromptLoader 传入的是 "dialogue/xxx.yaml" 相对路径）。 */
private fun locatePromptsRoot(): java.io.File {
    var dir: java.io.File? = java.io.File("").absoluteFile
    while (dir != null) {
        val candidate = java.io.File(dir, "prompts")
        if (java.io.File(candidate, "dialogue/turn_system.yaml").isFile) return candidate
        dir = dir.parentFile
    }
    error("未找到 prompts/dialogue/turn_system.yaml，无法加载真实提示词资源")
}
