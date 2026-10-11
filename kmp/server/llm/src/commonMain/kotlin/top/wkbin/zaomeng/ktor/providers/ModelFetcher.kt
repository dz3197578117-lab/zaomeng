package top.wkbin.zaomeng.ktor.providers

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按 Provider 接口动态拉取可用模型列表。
 *
 * 设计动机：
 * - OpenAI 兼容端点大多暴露 GET {baseUrl}/models ，返回真实模型清单；
 *   动态拉取可避免硬编码的模型 ID 过期 / 漏发（厂商每周都在上新）。
 * - 预设（ProviderPresets）里的 models 仅作为「下拉快速选项 + 拉取失败时的兜底」，
 *   不再承担"全量真实清单"的职责。
 *
 * 失败策略：任何异常（401/403/404/网络/解析失败）都返回 emptyList() ，
 * 由上层回退到 preset.models ，UI 永远有得选。
 *
 * 接入位置建议：kmp/server/llm 内嵌 Ktor 已持有 HttpClient 实例，直接复用即可。
 * 调用点：模型配置页「选择 Provider / 刷新模型」按钮、以及保存 Profile 前的连通性自检。
 *
 * 现状：本文件独立可编译，但**尚未接入 UI**。界面上「从接口拉取模型」目前走
 * `LlmClient.listModels()`（见 ktor/services/LlmClient.kt），后者覆盖同样的四种协议
 * 且多了类型化响应与 supportsTextOutput() 过滤；若要改用本实现，需要一笔接线改动。
 */

object ModelFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉取某 Provider 的模型列表。
     * @return 成功返回按 id 排序的 ModelEntry；失败返回空列表（上层用 preset.models 兜底）。
     */
    suspend fun fetch(preset: ProviderPreset, apiKey: String?, client: HttpClient): List<ModelEntry> {
        return runCatching {
            when (preset.kind) {
                ProviderKind.OPENAI_COMPATIBLE -> fetchOpenAiCompatible(preset, apiKey, client)
                ProviderKind.ANTHROPIC -> fetchAnthropic(preset, apiKey, client)
                ProviderKind.GEMINI -> fetchGemini(preset, apiKey, client)
                ProviderKind.AZURE -> fetchAzure(preset, apiKey, client)
            }
        }.getOrElse { emptyList() }
            .takeIf { it.isNotEmpty() } ?: emptyList()
    }

    // -------- OpenAI 兼容：GET {baseUrl}/models -> { data: [ { id, owned_by, ... } ] } --------
    // 适用：DeepSeek / 硅基流动 / 通义 / Kimi / 智谱 / 阶跃 / MiniMax / 商汤兼容模式 / OpenRouter / Ollama 等。
    private suspend fun fetchOpenAiCompatible(
        preset: ProviderPreset, apiKey: String?, client: HttpClient
    ): List<ModelEntry> {
        val url = "${preset.defaultBaseUrl.trimEnd('/')}/models"
        val resp = client.get(url) {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        }
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val ownedBy = obj["owned_by"]?.jsonPrimitive?.content
            ModelEntry(id = id, label = id, note = ownedBy?.let { "by $it" } ?: "")
        }.sortedBy { it.id }
    }

    // -------- Anthropic：GET {baseUrl}/models （需 x-api-key + anthropic-version 头） --------
    private suspend fun fetchAnthropic(
        preset: ProviderPreset, apiKey: String?, client: HttpClient
    ): List<ModelEntry> {
        val url = "${preset.defaultBaseUrl.trimEnd('/')}/models"
        val resp = client.get(url) {
            header("x-api-key", apiKey ?: "")
            header("anthropic-version", "2023-06-01")
        }
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val display = obj["display_name"]?.jsonPrimitive?.content
            ModelEntry(id = id, label = display ?: id)
        }.sortedBy { it.id }
    }

    // -------- Gemini：GET {baseUrl}/models?key=API_KEY -> { models: [ { name, displayName } ] } --------
    // 注意 name 形如 "models/gemini-1.5-pro"，需取末段作为模型 id。
    private suspend fun fetchGemini(
        preset: ProviderPreset, apiKey: String?, client: HttpClient
    ): List<ModelEntry> {
        val url = "${preset.defaultBaseUrl.trimEnd('/')}/models?key=${apiKey ?: ""}"
        val resp = client.get(url)
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val arr = root["models"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val full = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = full.substringAfterLast('/')
            val display = obj["displayName"]?.jsonPrimitive?.content
            ModelEntry(id = id, label = display ?: id)
        }.sortedBy { it.id }
    }

    // -------- Azure OpenAI：GET {base}/openai/deployments?api-version=... -> { data: [ { id } ] } --------
    // Azure 用 api-key 头鉴权；baseUrl 形如 https://<res>.openai.azure.com/openai 。
    private suspend fun fetchAzure(
        preset: ProviderPreset, apiKey: String?, client: HttpClient
    ): List<ModelEntry> {
        val base = preset.defaultBaseUrl.trimEnd('/').removeSuffix("/openai")
        val url = "$base/openai/deployments?api-version=2024-02-15-preview"
        val resp = client.get(url) {
            if (!apiKey.isNullOrBlank()) header("api-key", apiKey)
        }
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ModelEntry(id = id, label = id, note = "deployment")
        }.sortedBy { it.id }
    }
}

/**
 * UI / Repository 侧建议用法：
 *
 * ```kotlin
 * // 先尝试接口动态拉取，失败回退到预设清单
 * suspend fun resolveModels(preset: ProviderPreset, apiKey: String?, client: HttpClient): List<ModelEntry> {
 *     val live = ModelFetcher.fetch(preset, apiKey, client)
 *     return live.ifEmpty { preset.models }
 * }
 *
 * // 保存 Profile 前做一次连通性 + 模型可见性自检
 * suspend fun validateProfile(preset: ProviderPreset, apiKey: String?, client: HttpClient): Boolean {
 *     return ModelFetcher.fetch(preset, apiKey, client).isNotEmpty()
 * }
 * ```
 */
