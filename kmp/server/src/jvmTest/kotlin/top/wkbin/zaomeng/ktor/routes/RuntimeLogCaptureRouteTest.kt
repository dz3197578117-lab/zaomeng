package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.platform.ProcessExitRecord
import top.wkbin.zaomeng.platform.RuntimeCaptureClearResult
import top.wkbin.zaomeng.platform.RuntimeCaptureState
import top.wkbin.zaomeng.platform.RuntimeLogSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 回归测试：采集开关与清除日志的 HTTP 契约 + 幂等性 + 诊断概览。
 *
 * 这几条链路直接动用户的日志文件，任何一条退化都会造成「以为在录其实没录」或「点一次清除把
 * 业务数据带走」。用可控的假采集器把状态机钉死，不依赖真机。
 */
class RuntimeLogCaptureRouteTest {

    /** 内存假采集器：记录启停与清除调用次数，便于断言幂等。 */
    private class FakeCaptureSource : RuntimeLogSource {
        var enabled = true
            private set
        var setCaptureCalls = 0
            private set
        var clearCalls = 0
            private set

        override val source: String = RuntimeLogDto.SOURCE_LOGCAT_FILE

        override fun snapshot(maxLines: Int): RuntimeLogDto = RuntimeLogDto(
            source = source,
            available = true,
            capturedAt = "2026-10-13T01:00:00Z",
            totalLines = 3,
            lines = listOf("line-1", "line-2", "line-3"),
            crashes = listOf(
                CrashRecordDto(
                    occurredAt = "2026-10-13T00:59:00Z",
                    thread = "main",
                    summary = "NullPointerException: boom",
                    stackTrace = "at A.b(A.kt:1)",
                ),
            ),
        )

        override fun captureState(): RuntimeCaptureState = RuntimeCaptureState(
            supported = true,
            enabled = enabled,
            updatedAt = "2026-10-13T01:00:00Z",
            note = if (enabled) "" else "采集已关闭",
        )

        override fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureState {
            setCaptureCalls++
            if (this.enabled == enabled) return captureState()
            this.enabled = enabled
            return captureState()
        }

        override fun clearCaptured(): RuntimeCaptureClearResult {
            clearCalls++
            return RuntimeCaptureClearResult(
                supported = true,
                clearedLines = 3,
                clearedCrashes = 1,
                enabled = enabled,
                note = "已清除",
            )
        }

        override fun processExits(limit: Int): List<ProcessExitRecord> = listOf(
            ProcessExitRecord(
                occurredAt = "2026-10-13T00:50:00Z",
                reason = "ANR",
                reasonCode = 6,
                status = 0,
                importance = 100,
                trace = "main thread blocked",
                source = "android-application-exit-info:ANR",
            ),
        )
    }

    private fun newService(source: RuntimeLogSource): DiagnosticsService {
        val directory: Path = createTempDirectory("zaomeng-capture-route").toString().toPath()
        return DiagnosticsService(
            storageRoot = directory,
            storageService = StorageService(directory),
            runtimeLogSource = source,
        )
    }

    private fun serverJson() = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `capture switch round trips and is idempotent`() = testApplication {
        val source = FakeCaptureSource()
        val diagnostics = newService(source)
        val storage = StorageService(createTempDirectory("zaomeng-capture-route").toString().toPath())
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val initial = serverJson().decodeFromString(
            RuntimeCaptureStateDto.serializer(),
            client.get("/api/web/diagnostics/runtime-log/capture").bodyAsText(),
        )
        assertTrue(initial.supported, "假采集器支持启停")
        assertTrue(initial.enabled, "默认应为采集中")

        val disabled = serverJson().decodeFromString(
            RuntimeCaptureStateDto.serializer(),
            client.put("/api/web/diagnostics/runtime-log/capture") {
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }.bodyAsText(),
        )
        assertFalse(disabled.enabled, "关闭后状态应为已停止")
        assertFalse(source.enabled, "关闭必须真的落到采集器")

        // 幂等：重复同一个状态不再触发一次实际切换
        client.put("/api/web/diagnostics/runtime-log/capture") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(2, source.setCaptureCalls, "重复点击只应产生两次调用，且第二次不改变状态")

        val enabled = serverJson().decodeFromString(
            RuntimeCaptureStateDto.serializer(),
            client.put("/api/web/diagnostics/runtime-log/capture") {
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }.bodyAsText(),
        )
        assertTrue(enabled.enabled, "重新开启后状态应为采集中")
    }

    @Test
    fun `capture switch rejects malformed body`() = testApplication {
        val source = FakeCaptureSource()
        val diagnostics = newService(source)
        val storage = StorageService(createTempDirectory("zaomeng-capture-body").toString().toPath())
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val response = client.put("/api/web/diagnostics/runtime-log/capture") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":"yes"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(source.enabled, "请求体非法时不得改变采集状态")
    }

    @Test
    fun `clear log returns counts and stays idempotent`() = testApplication {
        val source = FakeCaptureSource()
        val diagnostics = newService(source)
        val storage = StorageService(createTempDirectory("zaomeng-clear-route").toString().toPath())
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val first = serverJson().decodeFromString(
            RuntimeLogClearResultDto.serializer(),
            client.delete("/api/web/diagnostics/runtime-log").bodyAsText(),
        )
        assertTrue(first.cleared)
        assertEquals(3, first.clearedLines)
        assertEquals(1, first.clearedCrashes)
        assertTrue(first.captureEnabled, "清除不应改变采集开关")

        // 幂等：再点一次仍然成功（日志本来就空），不报错
        val second = client.delete("/api/web/diagnostics/runtime-log")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(2, source.clearCalls)
    }

    @Test
    fun `overview exposes self check, problems and process exits`() = testApplication {
        val source = FakeCaptureSource()
        val diagnostics = newService(source)
        val storage = StorageService(createTempDirectory("zaomeng-overview-route").toString().toPath())
        application {
            install(ServerContentNegotiation) { json(serverJson()) }
            routing { diagnosticsRoute(storage, diagnostics) }
        }

        val response = client.get("/api/web/diagnostics/overview")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"startup\""), "概览必须带自检：$text")
        assertTrue(text.contains("backend_graph"), "自检必须包含后端服务图状态：$text")
        val overview = serverJson().decodeFromString(DiagnosticsOverviewDto.serializer(), text)
        assertNotNull(overview.problems.firstOrNull { it.kind == "crash" }, "崩溃必须进可读问题清单")
        assertNotNull(overview.problems.firstOrNull { it.kind == "anr" }, "ANR 必须进可读问题清单")
        assertTrue(overview.copySummary.contains("造梦诊断摘要"), "必须能一键复制摘要")
        assertTrue(
            overview.problems.first().kind in setOf("crash", "anr", "process-exit"),
            "问题清单按时间倒序，最近的在最前",
        )
    }
}
