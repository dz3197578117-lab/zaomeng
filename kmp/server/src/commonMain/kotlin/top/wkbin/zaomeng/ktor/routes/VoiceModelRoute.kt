package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.ktor.http.respondError
import top.wkbin.zaomeng.ktor.services.VoiceModelDownloadService

/**
 * 语音（离线神经 TTS）模型路由
 *
 * - GET    /api/web/voice/status?model_id=X      该模型目录的现状 + 下载进度（前端轮询这一个即可）
 * - POST   /api/web/voice/download               按客户端给的清单下载（body: DownloadVoiceModelRequest）
 * - POST   /api/web/voice/download/cancel        取消下载
 * - DELETE /api/web/voice/models/{model_id}      删除该模型目录
 *
 * 与端侧推理（`/api/web/on-device/...`）分开而不是合并：两者的文件形态完全不同
 * （那边单个 `.gguf` 平铺，这边每个模型一个子目录、7 个文件），混在一起会让
 * 「已就位模型列表」把语音文件当成 GGUF 列出来。
 */
fun Route.voiceModelRoutes(service: VoiceModelDownloadService) {
    get("/api/web/voice/status") {
        voiceCall {
            val modelId = call.request.queryParameters["model_id"]
                ?: throw IllegalArgumentException("Missing model_id")
            call.respond(service.status(modelId))
        }
    }

    post("/api/web/voice/download") {
        voiceCall {
            val request = call.receive<DownloadVoiceModelRequest>()
            // 下载是长任务：立刻返回（download.active=true），进度靠轮询 /status。
            call.respond(service.startDownload(request))
        }
    }

    post("/api/web/voice/download/cancel") {
        voiceCall { call.respond(service.cancelDownload()) }
    }

    delete("/api/web/voice/models/{model_id}") {
        voiceCall {
            val modelId = call.parameters["model_id"]
                ?: throw IllegalArgumentException("Missing model_id")
            call.respond(service.deleteModel(modelId))
        }
    }
}

/**
 * 统一异常映射（与端侧推理路由同一套语义，方便前端用同一套分支处理）。
 *
 * - 请求体不合法、地址不受信任 → 400
 * - 目录不存在 → 404
 * - 平台不支持、已有下载在进行 → 409
 */
private suspend fun RoutingContext.voiceCall(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, error.message ?: "Invalid request")
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, error.message ?: "Not found")
    } catch (error: IllegalStateException) {
        call.respondError(HttpStatusCode.Conflict, error.message ?: "Operation not allowed")
    } catch (error: Throwable) {
        call.respondError(HttpStatusCode.InternalServerError, error.message ?: "Voice model operation failed")
    }
}
