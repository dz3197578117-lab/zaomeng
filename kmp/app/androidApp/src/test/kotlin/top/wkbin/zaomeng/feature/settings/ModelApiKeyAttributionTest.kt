package top.wkbin.zaomeng.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「缺 API Key」与「接口真的返回空」必须能被区分开。
 *
 * 事故形状：新建档案、还没填 Key 时点「从接口拉取模型」，服务端把缺凭据折叠成空清单，
 * 界面于是显示「接口未返回可用模型」——用户去查接口地址、查网络、查模型名，
 * 而真正该改的那一行（API Key 输入框）一个字都没被提到。检测能力那条路更糟：
 * 直接抛英文 `API key not found for profile: …`，看起来像服务内部错误。
 */
class ModelApiKeyAttributionTest {

    private fun state(
        apiKeyRequired: Boolean = true,
        apiKey: String = "",
        apiKeyConfigured: Boolean = false,
    ) = ModelProfileEditorUiState(
        apiKeyRequired = apiKeyRequired,
        apiKey = apiKey,
        apiKeyConfigured = apiKeyConfigured,
    )

    @Test
    fun `required key that is neither typed nor stored is attributed to the api key field`() {
        val hint = missingApiKeyHint(state())
        val message = hint ?: error("缺 Key 应给出可操作提示")
        assertTrue(message.contains("API Key"), "提示必须点名该填的那个输入框：$message")
        assertFalse(message.contains("接口未返回"), "不能把缺凭据报成接口问题：$message")
        assertFalse(
            message.contains("not found for profile") ||
                message.contains("cannot be blank"),
            "界面文案里不允许出现服务端英文断言：$message",
        )
    }

    @Test
    fun `pasted but unsaved key counts as present`() {
        assertNull(
            missingApiKeyHint(state(apiKey = "sk-typed")),
            "用户先粘贴 Key 再点拉取是常见顺序，此时不该拦",
        )
    }

    @Test
    fun `previously saved key counts as present`() {
        assertNull(missingApiKeyHint(state(apiKeyConfigured = true)))
    }

    @Test
    fun `blank or whitespace typed key is still missing`() {
        assertTrue(missingApiKeyHint(state(apiKey = "   ")) != null)
    }

    @Test
    fun `endpoints that do not need a key never prompt for one`() {
        assertNull(
            missingApiKeyHint(state(apiKeyRequired = false)),
            "本机 / 自建端点（Ollama、llama-server、端侧）不需要 Key，不该被拦",
        )
    }

    @Test
    fun `missing key exception is detected through the repository wrapper`() {
        // repositoryRequest 会把服务端异常包进 ApiRequestException，只看最外层会漏判
        val wrapped = IllegalStateException(
            "还没有可用的 API Key",
            top.wkbin.zaomeng.ktor.services.ModelApiKeyMissingException("还没有可用的 API Key"),
        )
        assertTrue(wrapped.isApiKeyMissing())
        assertFalse(IllegalStateException("连接被拒绝").isApiKeyMissing())
        assertFalse(IllegalStateException("API Key 无效（401）").isApiKeyMissing(), "普通失败不该被当成缺 Key")
    }

    @Test
    fun `attention flag is off by default`() {
        // 「缺 Key」提示只应活在「上一次操作失败」之后；默认不该挂在编辑页上
        assertFalse(ModelProfileEditorUiState().apiKeyAttention)
        assertEquals("", missingApiKeyHint(state(apiKey = "sk-1")).orEmpty())
    }
}
