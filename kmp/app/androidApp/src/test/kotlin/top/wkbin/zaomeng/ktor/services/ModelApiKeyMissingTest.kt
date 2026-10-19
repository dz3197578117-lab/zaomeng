package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 三条「要打模型接口」的路径在**缺 API Key** 时都必须报「缺凭据」，而且报的是能照着做的话。
 *
 * 事故形状：
 * - `listModels` 把缺 Key 折叠成空清单 → 界面显示「接口未返回可用模型」，用户去查网络/地址；
 * - `detectCapabilities` 直接把 `require(...) { "API key not found for profile: …" }` 的英文断言
 *   透到界面，看起来像服务内部错误；
 * - `testModelSettings` 同样返回英文 message，还会进「模型连通性」诊断记录。
 *
 * 这三种情况都不应该发起网络请求：缺凭据是配置问题，先让用户把 Key 填上。
 */
class ModelApiKeyMissingTest {

    private class InMemorySecureStore : SecureKeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun entries(): Map<String, String> = values.toMap()
    }

    private fun service(): SettingsManagementService {
        val directory: Path = createTempDirectory("zaomeng-apikey-missing").toString().toPath()
        return SettingsManagementService(
            StorageService(directory),
            ModelApiKeyService(InMemorySecureStore()),
        )
    }

    private fun assertActionableChinese(message: String) {
        assertTrue(message.contains("API Key"), "必须点名该填的输入框：$message")
        assertFalse(message.contains("not found"), "不许把英文断言透到界面：$message")
        assertFalse(message.contains("cannot be blank"), "不许把英文断言透到界面：$message")
        assertFalse(message.contains("接口未返回"), "缺凭据不是「接口返回空」：$message")
    }

    @Test
    fun `pulling the model list without a key reports the missing key`() = runTest {
        val error = assertFailsWith<ModelApiKeyMissingException> {
            service().listModels(
                provider = "openai-compatible",
                // 故意用一个不存在的域名：如果真的发请求，这个用例会超时而不是走到断言
                baseUrl = "https://example.invalid/v1",
                apiKey = "",
                apiKeyRequired = true,
            )
        }
        assertActionableChinese(error.message.orEmpty())
    }

    @Test
    fun `capability detection without a key reports the missing key`() = runTest {
        val error = assertFailsWith<ModelApiKeyMissingException> {
            service().detectModelCapabilities(
                provider = "openai-compatible",
                model = "some-model",
                baseUrl = "https://example.invalid/v1",
                apiKey = "",
                apiKeyRequired = true,
            )
        }
        assertActionableChinese(error.message.orEmpty())
    }

    @Test
    fun `connection test without a key returns a chinese actionable message instead of an english assertion`() = runTest {
        val result = service().testModelSettings(
            provider = "openai-compatible",
            model = "some-model",
            baseUrl = "https://example.invalid/v1",
            apiKey = "",
            apiKeyRequired = true,
        )

        assertFalse(result["ok"]!!.jsonPrimitive.boolean, "缺 Key 的测试结果必须是失败")
        assertActionableChinese(result["message"]!!.jsonPrimitive.content)
    }
}
