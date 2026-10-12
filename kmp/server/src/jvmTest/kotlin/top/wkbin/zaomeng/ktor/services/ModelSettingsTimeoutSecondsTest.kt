package top.wkbin.zaomeng.ktor.services

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.ModelProfile
import top.wkbin.zaomeng.ktor.models.ModelSettings
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `timeout_seconds` 是排查/调优用的逃生口：它不经过 UI，只能手工编辑设置文件写入。
 *
 * 保存模型设置会整体重建 ModelProfile，若不显式保留，用户每次在界面上改一下模型，
 * 手工配置的生成超时就会静默消失——而且不会有任何提示。
 */
class ModelSettingsTimeoutSecondsTest {

    private class Harness {
        val storage: StorageService
        val service: SettingsManagementService

        constructor() {
            val directory: Path = createTempDirectory("zaomeng-model-settings").toString().toPath()
            storage = StorageService(directory)
            service = SettingsManagementService(
                storage,
                ModelApiKeyService(FileSecureKeyValueStore(directory / "secure-store.json")),
            )
        }
    }

    private fun Harness.seedProfile(profileId: String, model: String, timeoutSeconds: Int?) {
        storage.writeModelSettings(
            ModelSettings(
                activeProfileId = profileId,
                profiles = listOf(
                    ModelProfile(
                        profileId = profileId,
                        provider = "openai",
                        model = model,
                        timeoutSeconds = timeoutSeconds,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `saving model settings keeps a manually configured timeout`() {
        val harness = Harness()
        harness.seedProfile("p1", model = "old-model", timeoutSeconds = 900)

        harness.service.saveModelSettings(
            provider = "openai",
            model = "new-model",
            profileId = "p1",
        )

        val saved = harness.storage.readModelSettings()!!.profiles.single()
        assertEquals("new-model", saved.model, "常规保存仍应更新模型名")
        assertEquals(900, saved.timeoutSeconds, "常规保存不该抹掉手工配置的生成超时")
    }

    @Test
    fun `a profile without a timeout stays unset`() {
        val harness = Harness()
        harness.seedProfile("p1", model = "m", timeoutSeconds = null)

        harness.service.saveModelSettings(provider = "openai", model = "m2", profileId = "p1")

        assertNull(harness.storage.readModelSettings()!!.profiles.single().timeoutSeconds)
    }

    @Test
    fun `creating a new profile does not inherit another profile's timeout`() {
        val harness = Harness()
        harness.seedProfile("p1", model = "m", timeoutSeconds = 600)

        // createProfile=true 时服务端会生成新的 profile_id，忽略传入值。
        harness.service.saveModelSettings(
            provider = "openai",
            model = "m",
            profileId = "",
            profileName = "第二个档案",
            createProfile = true,
        )

        val profiles = harness.storage.readModelSettings()!!.profiles
        assertEquals(2, profiles.size, "应新建一个档案而不是覆盖 p1")
        val created = profiles.single { it.profileId != "p1" }
        assertNull(created.timeoutSeconds, "新档案应走默认超时，而不是继承别的档案")
    }
}
