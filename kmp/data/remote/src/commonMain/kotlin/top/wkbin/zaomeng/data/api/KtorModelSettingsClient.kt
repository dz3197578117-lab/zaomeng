package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorModelSettingsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun get(): ModelSettingsDto = request { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model") {
        }
    }.body()

    suspend fun save(payload: SaveModelSettingsRequest): ModelSettingsDto = request { endpoint ->
        http.client.put("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model") {
            setBody(payload)
        }
    }.body()

    suspend fun test(payload: TestModelSettingsRequest): ModelConnectionTestDto {
        val result: ModelConnectionTestDto = request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/test") {
                setBody(payload)
            }
        }.body()
        if (!result.ok) throw IllegalStateException(result.message.ifBlank { "Model connection failed" })
        return result
    }

    suspend fun detectCapabilities(payload: TestModelSettingsRequest): ModelCapabilityReportDto = request { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/detect-capabilities") {
            setBody(payload)
        }
    }.body()

    suspend fun fetchModels(payload: TestModelSettingsRequest): ModelListResponseDto = request { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/models") {
            setBody(payload)
        }
    }.body()

    suspend fun activate(profileId: String): ModelSettingsDto = request { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/profiles/$profileId/activate") {
        }
    }.body()

    suspend fun delete(profileId: String): ModelSettingsDto = request { endpoint ->
        http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/profiles/$profileId") {
        }
    }.body()

    // ------------------------------------------------------------------
    // 端侧推理（本机 llama.cpp）
    // ------------------------------------------------------------------

    suspend fun onDeviceStatus(): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/status")
    }.body()

    suspend fun onDeviceLogs(maxChars: Int): OnDeviceLogDto = onDeviceRequest { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/logs?max_chars=$maxChars")
    }.body()

    suspend fun onDeviceStart(payload: StartOnDeviceRequest): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/start") {
            setBody(payload)
        }
    }.body()

    suspend fun onDeviceStop(): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/stop") {
        }
    }.body()

    suspend fun onDeviceDownload(payload: DownloadOnDeviceModelRequest): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/download") {
            setBody(payload)
        }
    }.body()

    suspend fun onDeviceCancelDownload(): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/download/cancel") {
        }
    }.body()

    suspend fun onDeviceDeleteModel(fileName: String): OnDeviceStatusDto = onDeviceRequest { endpoint ->
        // 文件名可能含空格/中文，必须做路径段编码（encodeURLPathPart 是 String 的扩展函数）
        val encoded = fileName.encodeURLPathPart()
        http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/on-device/models/$encoded")
    }.body()

    // ------------------------------------------------------------------
    // 语音模型（离线神经 TTS，按需下载）。
    // 错误体形状与端侧接口一致（都是 {"error": "…"} 的中文提示），所以复用 onDeviceRequest。
    // ------------------------------------------------------------------

    /** 某语音模型目录的现状与下载进度。 */
    suspend fun voiceModelStatus(modelId: String): VoiceModelStatusDto = onDeviceRequest { endpoint ->
        val encoded = modelId.encodeURLPathPart()
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/voice/status?model_id=$encoded")
    }.body()

    /** 按清单下载语音模型。**立刻返回**，进度靠轮询 [voiceModelStatus]。 */
    suspend fun voiceModelDownload(
        payload: DownloadVoiceModelRequest,
    ): VoiceModelStatusDto = onDeviceRequest { endpoint ->
        // 不显式设 contentType：客户端已装 ContentNegotiation，与上面端侧那几个方法一致。
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/voice/download") {
            setBody(payload)
        }
    }.body()

    suspend fun voiceModelCancelDownload(): VoiceModelStatusDto = onDeviceRequest { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/voice/download/cancel")
    }.body()

    suspend fun voiceModelDelete(modelId: String): VoiceModelStatusDto = onDeviceRequest { endpoint ->
        val encoded = modelId.encodeURLPathPart()
        http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/voice/models/$encoded")
    }.body()

    /**
     * 端侧接口把失败原因放在 `{"error": "..."}`，且 400/404/409 都是给用户看的中文提示。
     * 通用 [request] 会把它包成 `Ktor request failed: 409 {...}`，所以这里单独解出 message。
     */
    private suspend fun onDeviceRequest(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            val text = response.bodyAsText()
            throw IllegalStateException(
                onDeviceErrorMessage(text) ?: "端侧操作失败（HTTP ${response.status.value}）",
            )
        }
        return response
    }

    private fun onDeviceErrorMessage(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

}
