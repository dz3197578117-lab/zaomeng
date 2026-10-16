package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.data.api.VoiceModelStatusDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectionTestDto
import top.wkbin.zaomeng.data.api.ModelListResponseDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.OnDeviceLogDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.ktor.services.OnDeviceModelService
import top.wkbin.zaomeng.ktor.services.SettingsManagementService
import top.wkbin.zaomeng.ktor.services.VoiceModelDownloadService

/**
 * 模型设置 + 端侧推理：**进程内直连**（原 `KtorModelSettingsClient`）。
 *
 * 这一域的返回值分两类，处理方式不同：
 * - **模型档案**（get/save/activate/delete/test）服务端返回 `JsonObject` → 照原样解码；
 * - **能力检测 / 模型清单 / 端侧全部接口**服务端**本来就返回类型化 DTO** →
 *   连解码都不需要，直接返回。
 *
 * 保留了原客户端的两个语义：
 * 1. `test` 在 `ok == false` 时抛 [IllegalStateException]（原实现就是客户端判断后抛）；
 * 2. 端侧失败在 HTTP 层是 `{"error": "..."}`，客户端会单独解出中文提示。
 *    现在服务本来就抛带中文消息的异常，直接透传即可——比原来更准确（不再经过字符串拼装）。
 */
class ModelSettingsClient(
    private val settings: SettingsManagementService,
    private val onDevice: OnDeviceModelService,
    private val voice: VoiceModelDownloadService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(): ModelSettingsDto = decode(settings.getModelSettings())

    suspend fun save(payload: SaveModelSettingsRequest): ModelSettingsDto = decode(
        settings.saveModelSettings(
            provider = payload.provider,
            model = payload.model,
            baseUrl = payload.baseUrl,
            apiKey = payload.apiKey,
            maxTokens = payload.maxTokens,
            reasoningEffort = payload.reasoningEffort,
            tokenParameter = payload.tokenParameter,
            responseFormatMode = payload.responseFormatMode,
            profileId = payload.profileId,
            profileName = payload.profileName,
            createProfile = payload.createProfile,
            activateProfile = payload.activateProfile,
            apiKeyRequired = payload.apiKeyRequired,
        ),
    )

    suspend fun test(payload: TestModelSettingsRequest): ModelConnectionTestDto {
        val result: ModelConnectionTestDto = decode(
            settings.testModelSettings(
                provider = payload.provider,
                model = payload.model,
                baseUrl = payload.baseUrl,
                apiKey = payload.apiKey,
                maxTokens = payload.maxTokens,
                reasoningEffort = payload.reasoningEffort,
                tokenParameter = payload.tokenParameter,
                profileId = payload.profileId,
                apiKeyRequired = payload.apiKeyRequired,
            ),
        )
        // 与原客户端一致：连接测试失败由调用方感知为异常，而不是「成功了一个 ok=false 的结果」
        if (!result.ok) throw IllegalStateException(result.message.ifBlank { "Model connection failed" })
        return result
    }

    suspend fun detectCapabilities(payload: TestModelSettingsRequest): ModelCapabilityReportDto =
        settings.detectModelCapabilities(
            provider = payload.provider,
            model = payload.model,
            baseUrl = payload.baseUrl,
            apiKey = payload.apiKey,
            maxTokens = payload.maxTokens,
            reasoningEffort = payload.reasoningEffort,
            tokenParameter = payload.tokenParameter,
            profileId = payload.profileId,
            apiKeyRequired = payload.apiKeyRequired,
        )

    suspend fun fetchModels(payload: TestModelSettingsRequest): ModelListResponseDto =
        settings.listModels(
            provider = payload.provider,
            baseUrl = payload.baseUrl,
            apiKey = payload.apiKey,
            profileId = payload.profileId,
            apiKeyRequired = payload.apiKeyRequired,
        )

    suspend fun activate(profileId: String): ModelSettingsDto = decode(settings.activateProfile(profileId))

    suspend fun delete(profileId: String): ModelSettingsDto = decode(settings.deleteProfile(profileId))

    // ------------------------------------------------------------------
    // 端侧推理（本机 llama.cpp）—— 服务返回类型化 DTO，直接透传
    // ------------------------------------------------------------------

    suspend fun onDeviceStatus(): OnDeviceStatusDto = onDevice.status()

    suspend fun onDeviceLogs(maxChars: Int): OnDeviceLogDto = onDevice.logTail(maxChars)

    suspend fun onDeviceStart(payload: StartOnDeviceRequest): OnDeviceStatusDto = onDevice.start(payload)

    suspend fun onDeviceStop(): OnDeviceStatusDto = onDevice.stop()

    suspend fun onDeviceDownload(payload: DownloadOnDeviceModelRequest): OnDeviceStatusDto =
        onDevice.startDownload(payload)

    suspend fun onDeviceCancelDownload(): OnDeviceStatusDto = onDevice.cancelDownload()

    suspend fun onDeviceDeleteModel(fileName: String): OnDeviceStatusDto = onDevice.deleteModel(fileName)

    // ------------------------------------------------------------------
    // 语音模型（离线神经 TTS）—— 服务返回类型化 DTO，直接透传
    // ------------------------------------------------------------------

    suspend fun voiceModelStatus(modelId: String): VoiceModelStatusDto = voice.status(modelId)

    suspend fun voiceModelDownload(payload: DownloadVoiceModelRequest): VoiceModelStatusDto =
        voice.startDownload(payload)

    suspend fun voiceModelCancelDownload(): VoiceModelStatusDto = voice.cancelDownload()

    suspend fun voiceModelDelete(modelId: String): VoiceModelStatusDto = voice.deleteModel(modelId)

    private inline fun <reified T> decode(payload: JsonObject): T =
        json.decodeFromJsonElement(payload)
}
