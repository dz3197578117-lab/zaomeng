package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Path
import top.wkbin.zaomeng.ktor.services.DiagnosticsEventLog
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import kotlin.io.path.createTempDirectory
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试：诊断导出必须真的产出一份非空 JSON。
 *
 * 线上症状是「导出后 JSON 文件是空白」——这条测试把服务端序列化与客户端读字节
 * 两段都钉住，任何一段退化成空 body 都会红。
 */
class DiagnosticsExportRouteTest {
    private fun newServices(directory: Path): Pair<StorageService, DiagnosticsService> {
        val storage = StorageService(directory)
        val log = DiagnosticsEventLog(directory / "diagnostics-events.jsonl")
        log.record(
            category = "dialogue.parse_failed",
            message = "Model reply did not contain usable character responses.",
            details = mapOf("output_excerpt" to "{\"speaker\":\"assistant\",\"message\":\"...\"}"),
        )
        val diagnostics = DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            eventLog = log,
            onDeviceSnapshot = { mapOf("state" to "running", "model_file" to "demo.gguf") },
        )
        return storage to diagnostics
    }

    private fun newDirectory(): Path = createTempDirectory("zaomeng-diag-route").toString().toPath()

    private fun serverJson() = Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `export responds with a non-empty json report`() = testApplication {
        val (storage, diagnostics) = newServices(newDirectory())
        application {
            // 与内嵌后端实际配置保持一致
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val response = client.get("/api/web/diagnostics/export")
        assertEquals(HttpStatusCode.OK, response.status)

        val text = response.bodyAsText()
        assertTrue(text.isNotBlank(), "导出响应体不应为空")
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("zaomeng_diagnostics", json["kind"].toString().trim('"'))
        assertTrue("recentEvents" in json, "报告应包含 recentEvents")
        assertTrue("onDevice" in json, "报告应包含 onDevice 快照")
        assertTrue(text.contains("Model reply did not contain usable character responses."), "事件内容应出现在报告里")
    }

    @Test
    fun `client reads the report as non-empty bytes`() = testApplication {
        val (storage, diagnostics) = newServices(newDirectory())
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        // 复刻 App 侧 KtorHttpClientProvider 的关键配置：ContentNegotiation + expectSuccess=false
        val apiClient = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            expectSuccess = false
        }

        val response = apiClient.get("/api/web/diagnostics/export")
        assertEquals(HttpStatusCode.OK, response.status)

        // App 侧现有写法：response.body<ByteArray>()
        val bytes: ByteArray = response.body()
        assertTrue(bytes.isNotEmpty(), "客户端读到的字节不应为空")
        val text = bytes.decodeToString()
        assertTrue(text.contains("recentEvents"), "客户端读到的内容应是完整报告")
    }
}
