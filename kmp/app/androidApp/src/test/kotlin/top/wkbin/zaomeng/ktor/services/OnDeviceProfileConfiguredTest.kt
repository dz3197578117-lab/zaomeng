package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.ModelProfile
import top.wkbin.zaomeng.ktor.models.ModelSettings
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `configured` 对 `provider = "local"`（端侧内嵌推理）必须附带「端侧正在运行」这一条件。
 *
 * 事故形状：端侧模型是**本机 llama-server 端点**，端口每次启动都换，进程一停就没有可连的地址；
 * 但 provider / model / 免 Key 三个字段依然齐全，于是 `configured` 判成 true，
 * 导入页显示「模型已配置，可以在手机内开始蒸馏」、书卷架显示「当前模型可用」，
 * 用户点下去才拿到 `Connection refused`——排查方向还容易被带偏到网络或 API Key 上。
 *
 * 云端 / 自建端点不受影响：它们的可达性不取决于本进程里有没有子进程活着。
 */
class OnDeviceProfileConfiguredTest {

    /** 内存版安全存储：本用例只关心「Key 有没有」，不关心密钥怎么落盘（同 ModelSettingsTimeoutSecondsTest）。 */
    private class InMemorySecureStore : SecureKeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun entries(): Map<String, String> = values.toMap()
    }

    private class Harness(
        profile: ModelProfile,
        /** 端侧运行时的实时地址；空串表示「端侧已停止」（与 `OnDeviceModelService.liveBaseUrl` 一致）。 */
        private val liveBaseUrl: String,
        apiKey: String? = null,
    ) {
        val storage: StorageService
        val service: SettingsManagementService

        init {
            val directory: Path = createTempDirectory("zaomeng-ondevice-configured").toString().toPath()
            storage = StorageService(directory)
            val keys = ModelApiKeyService(InMemorySecureStore())
            val profileId = profile.profileId.orEmpty()
            if (apiKey != null && profileId.isNotBlank()) keys.saveApiKey(profileId, apiKey)
            storage.writeModelSettings(
                ModelSettings(activeProfileId = profileId, profiles = listOf(profile)),
            )
            val live = liveBaseUrl
            service = SettingsManagementService(
                storage,
                keys,
                onDeviceBaseUrl = { live },
            )
        }
    }

    private fun onDeviceProfile(
        profileId: String = "p-local",
        model: String = "qwen2.5-7b-instruct-q4_k_m",
        baseUrl: String = "http://127.0.0.1:34699/v1",
        apiKeyRequired: Boolean = false,
    ) = ModelProfile(
        profileId = profileId,
        profileName = "本机模型（端侧）",
        provider = "local",
        model = model,
        baseUrl = baseUrl,
        apiKeyRequired = apiKeyRequired,
    )

    private fun cloudProfile(
        profileId: String = "p-cloud",
        provider: String = "openai-compatible",
    ) = ModelProfile(
        profileId = profileId,
        profileName = "云端",
        provider = provider,
        model = "deepseek-v4-flash",
        baseUrl = "https://api.deepseek.com",
        apiKeyRequired = true,
    )

    /** 顶层 `configured`（导入页 / 书卷架读的就是这一个）。 */
    private fun JsonObject.activeConfigured(): Boolean = this["configured"]!!.jsonPrimitive.boolean

    /** `profiles[0].configured`（模型档案列表逐条读的那一个）。 */
    private fun JsonObject.firstProfileConfigured(): Boolean =
        this["profiles"]!!.jsonArray[0].jsonObject["configured"]!!.jsonPrimitive.boolean

    private fun JsonObject.activeBaseUrl(): String = this["base_url"]!!.jsonPrimitive.content

    @Test
    fun `stopped on-device endpoint is not reported as configured`() {
        val harness = Harness(
            profile = onDeviceProfile(baseUrl = "http://127.0.0.1:34699/v1"),
            // 端侧已停止：liveBaseUrl() 返回空串
            liveBaseUrl = "",
        )

        val settings = harness.service.getModelSettings()

        assertFalse(settings.activeConfigured(), "端侧已停止时本机档案不能算已配置")
        assertFalse(settings.firstProfileConfigured(), "档案列表里那一条同样不能算已配置")
        assertEquals(
            "http://127.0.0.1:34699/v1",
            settings.activeBaseUrl(),
            "端侧未运行时不要拿空串抹掉持久化的地址（那会把「没启动」伪装成「没配地址」）",
        )
    }

    @Test
    fun `running on-device endpoint is reported as configured with the live port`() {
        val harness = Harness(
            profile = onDeviceProfile(baseUrl = "http://127.0.0.1:34699/v1"),
            liveBaseUrl = "http://127.0.0.1:38001/v1",
        )

        val settings = harness.service.getModelSettings()

        assertTrue(settings.activeConfigured(), "端侧正在运行，本机档案应算已配置")
        assertTrue(settings.firstProfileConfigured())
        assertEquals(
            "http://127.0.0.1:38001/v1",
            settings.activeBaseUrl(),
            "端口以实时值为准，否则重启换端口后会指向死端口",
        )
    }

    @Test
    fun `cloud profile is unaffected by on-device state`() {
        val harness = Harness(profile = cloudProfile(), liveBaseUrl = "", apiKey = "sk-test")

        val settings = harness.service.getModelSettings()

        assertTrue(settings.activeConfigured(), "云端档案与端侧是否运行无关")
        assertTrue(settings.firstProfileConfigured())
    }

    @Test
    fun `cloud profile still needs its api key`() {
        val harness = Harness(profile = cloudProfile(), liveBaseUrl = "")

        val settings = harness.service.getModelSettings()

        assertFalse(settings.activeConfigured(), "缺 Key 的云端档案仍应算未配置（原有语义不变）")
        assertFalse(settings.firstProfileConfigured())
    }

    @Test
    fun `local profile that requires a key still needs it while running`() {
        val harness = Harness(
            profile = onDeviceProfile(apiKeyRequired = true),
            liveBaseUrl = "http://127.0.0.1:38001/v1",
        )

        val settings = harness.service.getModelSettings()

        assertFalse(settings.activeConfigured(), "需要 Key 却没配，端侧在跑也不算已配置")
    }

    @Test
    fun `service without an on-device probe treats local profiles as not ready`() {
        val directory: Path = createTempDirectory("zaomeng-ondevice-configured-noprobe").toString().toPath()
        val storage = StorageService(directory)
        storage.writeModelSettings(
            ModelSettings(activeProfileId = "p-local", profiles = listOf(onDeviceProfile())),
        )
        // 端侧探测 lambda 缺失（构造方不关心端侧）：不能因此把本机端点当成「已配置」——
        // 那种情况没有任何证据说明端口上真的有人在听。
        val service = SettingsManagementService(storage, ModelApiKeyService(InMemorySecureStore()))

        assertFalse(service.getModelSettings().activeConfigured())
    }

    @Test
    fun `provider matching ignores case and surrounding blanks`() {
        val harness = Harness(
            profile = onDeviceProfile().copy(provider = " LOCAL "),
            liveBaseUrl = "",
        )

        assertFalse(
            harness.service.getModelSettings().activeConfigured(),
            "provider 写成 \" LOCAL \" 同样指向本机端点，不能因为大小写/空白绕开判定",
        )
    }
}
