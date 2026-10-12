package top.wkbin.zaomeng.ktor.services

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.ModelProfile
import top.wkbin.zaomeng.ktor.models.ModelSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断报告的模型档案摘要必须如实反映"是否配置了 API Key"。
 *
 * 保存模型设置的路径从不写 ModelProfile.apiKeyConfigured，所以单读该字段永远是 false：
 * 用户明明配好 Key，导出的诊断却显示"未配置"，排查时会直接走错方向。
 * 现在以安全存储的实际结果为准（构造参数 apiKeyConfiguredOf），查不到再回落该字段。
 */
class DiagnosticsApiKeyConfiguredTest {

    private fun profile(apiKeyConfigured: Boolean? = null): ModelProfile = ModelProfile(
        profileId = "p1",
        provider = "openai",
        model = "sensenova-6.8-flash-lite",
        baseUrl = "https://token.sensenova.cn/v1",
        apiKeyConfigured = apiKeyConfigured,
    )

    private fun newService(
        apiKeyConfigured: Boolean? = null,
        apiKeyConfiguredOf: ((String) -> Boolean?)? = null,
    ): DiagnosticsService {
        val directory = createTempDirectory("zaomeng-diag-apikey").toString().toPath()
        val storage = StorageService(directory)
        storage.writeModelSettings(
            ModelSettings(activeProfileId = "p1", profiles = listOf(profile(apiKeyConfigured))),
        )
        return DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            apiKeyConfiguredOf = apiKeyConfiguredOf,
        )
    }

    private fun DiagnosticsReport.firstProfileSummary(): ProfileSummary {
        assertTrue(model.profiles.isNotEmpty(), "报告必须包含模型档案摘要：$model")
        return model.profiles.first()
    }

    @Test
    fun `secure store result wins over the never written profile flag`() {
        val summary = newService(apiKeyConfigured = null, apiKeyConfiguredOf = { true })
            .buildDiagnosticsReport()
            .firstProfileSummary()

        assertTrue(summary.apiKeyConfigured, "安全存储里有 Key，报告不该报未配置")
    }

    @Test
    fun `empty secure store is reported as not configured`() {
        val summary = newService(apiKeyConfigured = null, apiKeyConfiguredOf = { false })
            .buildDiagnosticsReport()
            .firstProfileSummary()

        assertFalse(summary.apiKeyConfigured)
    }

    @Test
    fun `secure store lookup is called with the profile id`() {
        var seen = ""
        newService(apiKeyConfiguredOf = { seen = it; false }).buildDiagnosticsReport()

        assertEquals("p1", seen)
    }

    @Test
    fun `falls back to the profile flag when no store lookup is wired`() {
        val configured = newService(apiKeyConfigured = true).buildDiagnosticsReport().firstProfileSummary()
        val unset = newService(apiKeyConfigured = null).buildDiagnosticsReport().firstProfileSummary()

        assertTrue(configured.apiKeyConfigured)
        assertFalse(unset.apiKeyConfigured)
    }

    @Test
    fun `profile without an id cannot be looked up and stays not configured`() {
        val directory: Path = createTempDirectory("zaomeng-diag-no-id").toString().toPath()
        val storage = StorageService(directory)
        storage.writeModelSettings(
            ModelSettings(profiles = listOf(profile().copy(profileId = null))),
        )
        val service = DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            apiKeyConfiguredOf = { error("无 profile_id 时不应去查安全存储：$it") },
        )

        assertFalse(service.buildDiagnosticsReport().firstProfileSummary().apiKeyConfigured)
    }
}
