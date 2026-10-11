package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.ktor.services.OnDeviceModelService

/**
 * 端侧推理路由
 *
 * - GET    /api/web/on-device/status                 状态 + 已就位模型列表 + 下载进度（前端轮询这一个即可）
 * - GET    /api/web/on-device/logs?max_chars=N       llama-server 日志尾部
 * - POST   /api/web/on-device/start                  启动（body: StartOnDeviceRequest）
 * - POST   /api/web/on-device/stop                   停止
 * - POST   /api/web/on-device/download               从直链下载 GGUF
 * - POST   /api/web/on-device/download/cancel        取消下载
 * - DELETE /api/web/on-device/models/{file_name}     删除模型文件
 */
fun Route.onDeviceModelRoutes(service: OnDeviceModelService) {
    get("/api/web/on-device/status") {
        deviceCall { call.respond(service.status()) }
    }

    get("/api/web/on-device/logs") {
        val maxChars = call.request.queryParameters["max_chars"]?.toIntOrNull() ?: DEFAULT_LOG_CHARS
        deviceCall { call.respond(service.logTail(maxChars)) }
    }

    post("/api/web/on-device/start") {
        deviceCall {
            val request = call.receive<StartOnDeviceRequest>()
            call.respond(service.start(request))
        }
    }

    post("/api/web/on-device/stop") {
        deviceCall { call.respond(service.stop()) }
    }

    post("/api/web/on-device/download") {
        deviceCall {
            val request = call.receive<DownloadOnDeviceModelRequest>()
            // 下载是长任务：这里立刻返回（download.active=true），进度靠轮询 /status
            call.respond(service.startDownload(request))
        }
    }

    post("/api/web/on-device/download/cancel") {
        deviceCall { call.respond(service.cancelDownload()) }
    }

    delete("/api/web/on-device/models/{file_name}") {
        deviceCall {
            val fileName = call.parameters["file_name"]
                ?: throw IllegalArgumentException("Missing file_name")
            call.respond(service.deleteModel(fileName))
        }
    }
}

private const val DEFAULT_LOG_CHARS = 8_000

/**
 * 统一异常映射。
 *
 * - 请求体不合法 / 缺参数 → 400
 * - 模型不存在 → 404
 * - 平台不支持、可执行文件缺失、已有下载任务在进行 → 409
 *   （语义上是「当前状态不允许」，比笼统的 500 更能让前端区分提示）
 */
private suspend fun RoutingContext.deviceCall(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, error.message ?: "Invalid request")
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, error.message ?: "Not found")
    } catch (error: IllegalStateException) {
        call.respondError(HttpStatusCode.Conflict, error.message ?: "Operation not allowed")
    } catch (error: Throwable) {
        call.respondError(HttpStatusCode.InternalServerError, error.message ?: "On-device operation failed")
    }
}
