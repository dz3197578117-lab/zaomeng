package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 内容审核拒绝的判据回归测试。
 *
 * 起因（用户真机 `zaomeng-diagnostics_2.json`，2.1.14 / realme GT7 Pro）：
 * 16 条 400 的上游错误体都是
 * `{"error":{"message":"inference request is invalid","type":"invalid_request_error",
 *            "code":"data_inspection_failed"}}`
 * 而判据当时**只看 message 措辞**，并把 `inference request is invalid` 明确列为
 * 「必须不命中」的反例 —— 于是「内容被提供方审核拒绝」被判成普通请求错误，
 * 表现为：聊天页显示成应用故障、给出注定无效的「重试」按钮、
 * 「功能自检」的对话生成探针报 ❌。
 *
 * 这些用例把两类必须区分的输入钉住：
 * - **审核拒绝**：认机器可读 `code`（`data_inspection*`），也认原有措辞；
 * - **参数/资源错误**：既不带审核 code、也不含审核措辞，必须继续走普通错误路径。
 */
class LlmContentModerationTest {

    @Test
    fun `sense nova data inspection code is a moderation rejection`() {
        // 真机原文：message 读起来像参数错误，只有 code 说明它是审核拒绝
        val body =
            """{"error":{"message":"inference request is invalid",""" +
                """"type":"invalid_request_error","code":"data_inspection_failed"}}"""

        assertTrue(
            LlmClient.isContentModerationRejection(body),
            "code=data_inspection_failed 必须判为内容审核拒绝（这是本测试存在的理由）",
        )
    }

    @Test
    fun `data inspection code matches future variants and is case insensitive`() {
        assertTrue(
            LlmClient.isContentModerationRejection("""{"error":{"code":"DATA_INSPECTION_BLOCKED"}}"""),
            "code 判据应小写化并用品干匹配，覆盖 data_inspection_* 变体",
        )
    }

    @Test
    fun `plain parameter error without code or wording is not a moderation rejection`() {
        assertFalse(
            LlmClient.isContentModerationRejection(
                """{"error":{"message":"inference request is invalid","type":"invalid_request_error"}}""",
            ),
            "没有 code、也没有审核措辞时，不得凭 message 猜测是审核拒绝",
        )
        assertFalse(
            LlmClient.isContentModerationRejection("""{"error":{"message":"max_tokens must be greater than 0"}}"""),
            "参数错误必须继续走普通错误路径",
        )
        assertFalse(
            LlmClient.isContentModerationRejection("""{"error":{"message":"model not found"}}"""),
            "资源错误必须继续走普通错误路径",
        )
    }

    @Test
    fun `existing wording markers still match`() {
        assertTrue(
            LlmClient.isContentModerationRejection(
                "Inappropriate input/output rejected for security reasons",
            ),
        )
        assertTrue(LlmClient.isContentModerationRejection("""{"error":{"code":"content_policy_violation"}}"""))
        assertTrue(LlmClient.isContentModerationRejection("""{"error":{"message":"内容安全审核未通过"}}"""))
    }

    @Test
    fun `parameter names are not mistaken for moderation wording`() {
        // 单词按词边界：safety_settings 是参数名，不是审核拒绝
        assertFalse(
            LlmClient.isContentModerationRejection("""{"error":{"message":"unknown field safety_settings"}}"""),
        )
    }

    @Test
    fun `blank body is not a moderation rejection`() {
        assertFalse(LlmClient.isContentModerationRejection(""))
        assertFalse(LlmClient.isContentModerationRejection("   "))
    }
}
