package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 线上问题回归：诊断报告里 `allowed_responders` 是 `"李慧英"、"陆城"、旁白、场景提示`，
 * 而 `forbidden_responders` 是 `陆城、作者`——同一个「陆城」同时出现在两个名单里
 * （带引号那条被允许，干净那条被禁止），模型输出的干净名字被判无效，反复解析失败重试。
 *
 * 这里锁住两件事：
 * 1. 名单拼装按「身份」归一化，允许与禁止名单绝不相交；
 * 2. 展示侧读到的会话里，参与者和说话人名字不再带包裹引号。
 */
class DialogueResponderListNormalizationTest {
    private fun identities(names: List<String>): List<String> =
        names.map(CharacterNameNormalizer::identityKey)

    @Test
    fun `quoted controlled character never lands in both responder lists`() = runBlocking {
        val directory = createTempDirectory("responder-normalization")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runManifest = buildJsonObject {
                put("run_id", "run")
                put(
                    "artifact_index",
                    buildJsonObject {
                        put(
                            "characters",
                            buildJsonArray {
                                add(buildJsonObject { put("name", "\"李慧英\"") })
                                add(buildJsonObject { put("name", "\"陆城\"") })
                            },
                        )
                    },
                )
            }
            val session = buildJsonObject {
                put("session_id", "session")
                put("mode", "act")
                put("controlled_character", "陆城")
                put(
                    "participants",
                    buildJsonArray {
                        add(JsonPrimitive("\"李慧英\""))
                        add(JsonPrimitive("\"陆城\""))
                    },
                )
            }

            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(
                runManifest = runManifest,
                session = session,
                turnId = "turn",
                message = "这一场要怎么收？",
                messageKind = "fourth_wall",
            )
            val input = payload["input"] as Map<*, *>
            val allowed = (input["allowed_responders"] as List<*>).map { it.toString() }
            val forbidden = (input["forbidden_responders"] as List<*>).map { it.toString() }

            assertEquals(listOf("李慧英"), allowed)
            assertEquals(listOf("作者", "陆城"), forbidden)
            assertTrue(
                identities(allowed).none { it in identities(forbidden).toSet() },
                "允许名单与禁止名单不能包含同一个身份：allowed=$allowed forbidden=$forbidden",
            )
            // 归一化不能把人物档案弄丢：artifact_index 里还是带引号的旧名字。
            val personaNames = (payload["persona_contexts"] as List<*>).mapNotNull {
                (it as? Map<*, *>)?.get("name")?.toString()
            }
            assertTrue("李慧英" in personaNames, "persona_contexts=$personaNames")
            assertTrue("陆城" in personaNames, "persona_contexts=$personaNames")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quoted participants are normalized and deduped in responder lists`() = runBlocking {
        val directory = createTempDirectory("responder-dedupe")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runManifest = buildJsonObject {
                put("run_id", "run")
                put(
                    "artifact_index",
                    buildJsonObject {
                        put(
                            "characters",
                            buildJsonArray {
                                add(buildJsonObject { put("name", "\"陆城\"") })
                                add(buildJsonObject { put("name", "李慧英") })
                            },
                        )
                    },
                )
            }
            val session = buildJsonObject {
                put("session_id", "session")
                put("mode", "observe")
                put(
                    "participants",
                    buildJsonArray {
                        add(JsonPrimitive("\"陆城\""))
                        add(JsonPrimitive("陆城"))
                        add(JsonPrimitive("「李慧英」"))
                        add(JsonPrimitive(" 李慧英 "))
                    },
                )
            }

            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(
                runManifest = runManifest,
                session = session,
                turnId = "turn",
                message = "你们怎么看？",
            )
            val input = payload["input"] as Map<*, *>
            val allowed = (input["allowed_responders"] as List<*>).map { it.toString() }

            assertEquals(listOf("陆城", "李慧英"), allowed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `active responder selection treats quoted controlled character as the same person`() {
        val active = selectDialogueActiveParticipants(
            participants = listOf("李慧英", "\"陆城\""),
            presentParticipants = listOf("\"陆城\"", "李慧英"),
            mode = "act",
            inputSpeaker = "陆城",
            controlledCharacter = "\"陆城\"",
        )
        val eligible = eligibleDialogueResponders(
            activeParticipants = listOf("李慧英", "「陆城」"),
            mode = "act",
            inputSpeaker = "陆城",
            controlledCharacter = "\"陆城\"",
        )

        assertEquals(listOf("李慧英"), active)
        assertEquals(listOf("李慧英"), eligible)
    }

    @Test
    fun `session reads normalize quoted participants and speaker names`() {
        val directory = createTempDirectory("session-name-normalization")
        try {
            val storage = StorageService(directory.toString().toPath())
            val manifestFile = storage.getDialogueSessionManifestFile("run", "session")
            storage.writeTextAtomically(
                manifestFile,
                buildJsonObject {
                    put("session_id", "session")
                    put("run_id", "run")
                    put("mode", "act")
                    put("controlled_character", "\"陆城\"")
                    put(
                        "participants",
                        buildJsonArray {
                            add(JsonPrimitive("\"李慧英\""))
                            add(JsonPrimitive("\"陆城\""))
                        },
                    )
                    put("muted_characters", buildJsonArray { add(JsonPrimitive("「陆城」")) })
                    put(
                        "transcript",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("speaker", "\"李慧英\"")
                                    put("message", "批文的事，你帮不帮？")
                                    put("role", "character")
                                },
                            )
                        },
                    )
                }.toString(),
            )

            val session = storage.getDialogueSession("run", "session")
            assertEquals(
                listOf("李慧英", "陆城"),
                session["participants"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("陆城", session["controlled_character"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("陆城"),
                session["muted_characters"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                "李慧英",
                session["transcript"]!!.jsonArray.first().jsonObject["speaker"]!!.jsonPrimitive.content,
            )
            // 展示侧读到的头像键也要跟着归一化，否则客户端按干净名字取不到头像。
            assertTrue(
                session["character_avatars"]!!.jsonObject.containsKey("李慧英"),
                "character_avatars=${session["character_avatars"]}",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
