package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.data.api.RuntimeLogDto

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
     */
    suspend fun export(destination: Sink): Long {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/export") {
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
     */
    suspend fun runtimeLog(maxLines: Int): RuntimeLogDto {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/runtime-log") {
                parameter("lines", maxLines)
            }
        }
        return response.body()
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}
