package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.RuntimeCaptureRequestDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.platform.RuntimeLogSource

/**
 * 诊断 API 路由
 *
 * 对应 Python FastAPI 的 diagnostics 端点
 */
fun Route.diagnosticsRoute(
    storageService: StorageService,
    diagnosticsService: DiagnosticsService
) {
    route("/api/web/diagnostics") {
        // GET /api/web/diagnostics/export - 导出诊断报告
        // run_id / session_id 可选：客户端带上「最近打开的会话」，服务端不知道 UI 状态
        get("/export") {
            try {
                val report = diagnosticsService.buildDiagnosticsReport(
                    runId = call.request.queryParameters["run_id"].orEmpty(),
                    sessionId = call.request.queryParameters["session_id"].orEmpty(),
                )

                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"zaomeng-diagnostics.json\""
                )

                call.respond(HttpStatusCode.OK, report)
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to build diagnostics report"))
            }
        }

        // GET /api/web/diagnostics/overview - 「运行诊断」页概览（自检 + 可读问题清单 + 事件统计 + 模型连通性）
        get("/overview") {
            try {
                call.respond(
                    HttpStatusCode.OK,
                    diagnosticsService.buildDiagnosticsOverview(
                        runId = call.request.queryParameters["run_id"].orEmpty(),
                        sessionId = call.request.queryParameters["session_id"].orEmpty(),
                    ),
                )
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to build diagnostics overview"))
            }
        }

        // GET /api/web/diagnostics/runtime-log?lines=N - 拉取运行日志（已脱敏，供应用内「运行日志」页）
        get("/runtime-log") {
            try {
                val requested = call.request.queryParameters["lines"]?.toIntOrNull()
                    ?: RuntimeLogSource.DEFAULT_SNAPSHOT_LINES
                call.respond(
                    HttpStatusCode.OK,
                    diagnosticsService.buildRuntimeLogSnapshot(
                        maxLines = requested,
                        runId = call.request.queryParameters["run_id"].orEmpty(),
                        sessionId = call.request.queryParameters["session_id"].orEmpty(),
                    ),
                )
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to read runtime log"))
            }
        }

        // GET /api/web/diagnostics/runtime-log/capture - 采集开关状态（应用内悬浮窗轮询这一个轻量端点）
        get("/runtime-log/capture") {
            try {
                call.respond(HttpStatusCode.OK, diagnosticsService.runtimeCaptureState())
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to read capture state"))
            }
        }

        // PUT /api/web/diagnostics/runtime-log/capture - 启停持续采集（幂等；状态由平台持久化）
        put("/runtime-log/capture") {
            val request = try {
                call.receive<RuntimeCaptureRequestDto>()
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "请求体必须是 {\"enabled\": true|false}。")
                return@put
            }
            try {
                call.respond(HttpStatusCode.OK, diagnosticsService.setRuntimeLogCapture(request.enabled))
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to update capture state"))
            }
        }

        // DELETE /api/web/diagnostics/runtime-log - 清除已采集的运行日志与崩溃记录（幂等）
        delete("/runtime-log") {
            try {
                val result = diagnosticsService.clearRuntimeLog()
                if (!result.cleared && result.supported) {
                    call.respond(HttpStatusCode.InternalServerError, result)
                } else {
                    call.respond(HttpStatusCode.OK, result)
                }
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to clear runtime log"))
            }
        }
    }
}
