package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.platform.RuntimeLogSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试：`GET /api/web/diagnostics/runtime-log` 必须返回可用的日志快照。
 *
 * 这是应用内「运行日志」页与单独导出共用的入口：来源标识要能被客户端识别，
 * 行数要按请求夹紧，凭据必须在离开服务端前就被遮蔽。
 */
class RuntimeLogRouteTest {

    private class FakeRuntimeLogSource(private val snapshot: RuntimeLogDto) : RuntimeLogSource {
        override val source: String = snapshot.source

        override fun snapshot(maxLines: Int): RuntimeLogDto =
            snapshot.copy(lines = snapshot.lines.takeLast(maxLines))
    }

    private fun newService(snapshot: RuntimeLogDto): Pair<StorageService, DiagnosticsService> {
        val directory: Path = createTempDirectory("zaomeng-runtime-log-route").toString().toPath()
        val storage = StorageService(directory)
        return storage to DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            runtimeLogSource = FakeRuntimeLogSource(snapshot),
        )
    }

    private fun serverJson() = Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `runtime log route responds with redacted snapshot`() = testApplication {
        val snapshot = RuntimeLogDto(
            source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
            available = true,
            capturedAt = "2026-10-12T20:00:00+08:00",
            totalLines = 12,
            lines = listOf(
                "10-12 20:00:00.000 I Zaomeng.Dialogue: 对话生成完成",
                "10-12 20:00:01.000 E Zaomeng.Llm: api_key=abcdef123456",
                "line-3",
                "line-4",
            ),
        )
        val (storage, diagnostics) = newService(snapshot)
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val response = client.get("/api/web/diagnostics/runtime-log?lines=2")
        assertEquals(HttpStatusCode.OK, response.status)

        val text = response.bodyAsText()
        assertTrue(text.isNotBlank(), "运行日志响应体不应为空")
        assertTrue(text.contains(RuntimeLogDto.SOURCE_LOGCAT_FILE), "应带来源标识：$text")
        assertFalse(text.contains("abcdef123456"), "凭据必须已在服务端遮蔽：$text")
        assertTrue(text.contains("line-4"), "应返回尾部日志：$text")
        assertFalse(text.contains("对话生成完成"), "超出请求行数的更早日志不应返回：$text")
    }
}
