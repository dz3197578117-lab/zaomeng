package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.ModelProfile
import top.wkbin.zaomeng.ktor.models.ModelSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断里的 `model_configured`（启动自检）与 `model.profiles[].configured` 必须**从实际字段算**，
 * 不能读存储里的 `ModelProfile.configured` 标志位。
 *
 * 起因（用户真机 `zaomeng-diagnostics_2.json`，2.1.14 / realme GT7 Pro）：
 * 三个档案 provider / model / apiKey 都齐全，导出里却**全部** `configured=false`，
 * `startup.checks.model_configured` 也是 false。原因是保存路径
 * （`SettingsManagementService.saveModelSettings` 构造 `ModelProfile(...)`）**从不传 `configured`**，
 * 所以存储字段恒为默认值；而 `SettingsManagementService.putProfileFields` 只在**返回给客户端的
 * JSON** 里现算同名表达式 —— 于是界面说「已配置」，诊断说「未配置」。
 *
 * 这与仓库此前为 `apiKeyConfigured` 记录并修好的 bug **完全同类**
 * （见 [DiagnosticsApiKeyConfiguredTest] 的类注释），本文件把 `configured` 那一半也钉住。
 */
class DiagnosticsModelConfiguredTest {

    private fun profile(
        provider: String? = "openai-compatible",
        model: String? = "sensenova-6.8-flash-lite",
        apiKeyRequired: Boolean? = true,
        storedConfigured: Boolean? = null,
    ): ModelProfile = ModelProfile(
        profileId = "p1",
        provider = provider,
        model = model,
        baseUrl = "https://token.sensenova.cn/v1",
        apiKeyRequired = apiKeyRequired,
        configured = storedConfigured,
    )

    private fun report(
        profile: ModelProfile,
        apiKeyConfigured: Boolean = true,
    ): DiagnosticsReport {
        val directory = createTempDirectory("zaomeng-diag-configured").toString().toPath()
        val storage = StorageService(directory)
        storage.writeModelSettings(
            ModelSettings(activeProfileId = "p1", profiles = listOf(profile)),
        )
        return DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            apiKeyConfiguredOf = { apiKeyConfigured },
        ).buildDiagnosticsReport()
    }

    private fun DiagnosticsReport.modelConfigured(): String =
        startup["model_configured"]?.jsonPrimitive?.content.orEmpty()

    @Test
    fun `fields present and key available is reported as configured`() {
        val report = report(profile(), apiKeyConfigured = true)

        assertEquals("true", report.modelConfigured(), "provider/model/key 都齐，model_configured 不该是 false")
        assertTrue(report.model.profiles.first().configured, "每条档案摘要同样要如实报已配置")
    }

    @Test
    fun `stored configured flag is not trusted when the model field is blank`() {
        // 关键回归：存储说 true、字段却缺 model —— 必须以字段为准。
        val report = report(profile(model = null, storedConfigured = true))

        assertEquals("false", report.modelConfigured(), "字段不齐时不得因为存储标志位就说已配置")
        assertFalse(report.model.profiles.first().configured)
    }

    @Test
    fun `stored configured flag is not needed when the fields are present`() {
        // 反向：存储是 false（保存路径从不写它，真机就是这个状态）、字段齐全 → 必须报已配置。
        val report = report(profile(storedConfigured = false), apiKeyConfigured = true)

        assertEquals("true", report.modelConfigured())
        assertTrue(report.model.profiles.first().configured)
    }

    @Test
    fun `key required but missing is not configured`() {
        val report = report(profile(apiKeyRequired = true, storedConfigured = true), apiKeyConfigured = false)

        assertEquals("false", report.modelConfigured())
    }

    @Test
    fun `key not required is configured without one`() {
        val report = report(profile(apiKeyRequired = false), apiKeyConfigured = false)

        assertEquals("true", report.modelConfigured(), "本机/自建端点不需要 Key，应当算已配置")
    }

    @Test
    fun `blank provider is not configured`() {
        val report = report(profile(provider = "", storedConfigured = true))

        assertEquals("false", report.modelConfigured())
    }
}
