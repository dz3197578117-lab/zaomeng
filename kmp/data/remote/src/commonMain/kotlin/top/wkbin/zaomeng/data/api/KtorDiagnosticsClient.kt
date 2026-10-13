package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorDiagnosticsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    /**
     * 拉取诊断报告并写入 [destination]。
     *
     * 用 bodyAsBytes() 而不是泛型 body<ByteArray>()：前者明确是「读原始字节」，
     * 不受 ContentNegotiation 影响。读到空 body 时直接失败——否则用户只会拿到
     * 一个空白 JSON 文件，完全看不出哪里出了问题。
     *
     * [runId] / [sessionId] 是「最近打开的会话」，作为现场上下文带上；服务端不知道 UI 状态，
     * 报告里会标注这是客户端上报而不是当前正在生成的会话。
     */
    suspend fun export(
        destination: Sink,
        runId: String = "",
        sessionId: String = "",
        tier: String = "compact",
    ): Long {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/export") {
                if (runId.isNotBlank()) parameter("run_id", runId)
                if (sessionId.isNotBlank()) parameter("session_id", sessionId)
                // R3：导出分级由查询参数表达；服务端对未知值一律退回精简档。
                parameter("tier", tier)
            }
        }
        val bytes = response.bodyAsBytes()
        if (bytes.isEmpty()) {
            throw IllegalStateException("诊断报告为空（响应体 0 字节），导出未完成。")
        }
        destination.buffer().use { it.write(bytes) }
        return bytes.size.toLong()
    }

    /**
     * 拉取运行日志快照（服务端已脱敏）。
     *
     * 行数上限由服务端夹紧，这里只表达诉求；返回的标准 JSON 契约走 ContentNegotiation 解码。
     * 快照里同时带采集开关状态与版本/现场元信息，页面一次调用即可拿到全部。
     */
    suspend fun runtimeLog(maxLines: Int, runId: String = "", sessionId: String = ""): RuntimeLogDto {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/runtime-log") {
                parameter("lines", maxLines)
                if (runId.isNotBlank()) parameter("run_id", runId)
                if (sessionId.isNotBlank()) parameter("session_id", sessionId)
            }
        }
        return response.body()
    }

    /**
     * 「运行诊断」概览：自检 + 可读问题清单 + 事件统计 + 模型连通性。
     *
     * 与完整 JSON 导出同源同口径，但**不带日志正文**，适合页面直接展示。
     */
    suspend fun overview(runId: String = "", sessionId: String = ""): DiagnosticsOverviewDto {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/overview") {
                if (runId.isNotBlank()) parameter("run_id", runId)
                if (sessionId.isNotBlank()) parameter("session_id", sessionId)
            }
        }
        return response.body()
    }

    /**
     * 重新执行功能自检探针（显式「重新自检」）。
     *
     * 概览接口读的是**上一轮快照**（不被慢探针拖住）；用户点刷新时走这个端点真正重跑。
     */
    suspend fun refreshFeatureProbes(): FeatureProbeReportDto {
        val response = request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/feature-probes/refresh") {
            }
        }
        return response.body()
    }

    /** 采集开关状态（轻量端点，供应用内悬浮窗与「运行日志」页共用）。 */
    suspend fun captureState(): RuntimeCaptureStateDto {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/runtime-log/capture") {
            }
        }
        return response.body()
    }

    /** 启停持续采集（服务端侧幂等，状态由平台持久化）。 */
    suspend fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureStateDto {
        val response = request { endpoint ->
            http.client.put("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/runtime-log/capture") {
                contentType(ContentType.Application.Json)
                setBody(RuntimeCaptureRequestDto(enabled))
            }
        }
        return response.body()
    }

    /**
     * 清除已采集的运行日志与崩溃/卡顿记录。
     *
     * 服务端在清除失败时返回非 2xx + 同一个 DTO；这里必须把 DTO 读出来，
     * 否则用户只能看到一句「请求失败」，看不到「为什么没清掉」。
     */
    suspend fun clearRuntimeLog(): RuntimeLogClearResultDto {
        val endpoint = endpointProvider.requireKtorEndpoint()
        val response = http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/runtime-log") {
        }
        return runCatching { response.body<RuntimeLogClearResultDto>() }
            .getOrElse {
                throw IllegalStateException(
                    "Ktor request failed: ${response.status.value} ${response.bodyAsText()}",
                )
            }
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}
