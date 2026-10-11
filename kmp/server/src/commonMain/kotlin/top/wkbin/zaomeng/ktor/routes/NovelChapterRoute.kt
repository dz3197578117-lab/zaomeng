package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.ktor.services.NovelChapterIndexService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 原文分章路由
 *
 * - GET  /api/web/runs/{run_id}/novel-chapters           列出原文章节（首次访问自动切分并缓存）
 * - POST /api/web/runs/{run_id}/novel-chapters/rebuild    强制重新切分（原文被替换后使用）
 * - GET  /api/web/runs/{run_id}/novel-chapters/{index}    单章正文片段（默认最多 8000 字）
 */
fun Route.novelChapterRoutes(
    service: NovelChapterIndexService,
    storage: StorageService,
) {
    get("/api/web/runs/{run_id}/novel-chapters") {
        novelChapterCall(storage) { _, manifest ->
            call.respond(service.list(manifest))
        }
    }
    post("/api/web/runs/{run_id}/novel-chapters/rebuild") {
        novelChapterCall(storage) { _, manifest ->
            call.respond(service.list(manifest, force = true))
        }
    }
    get("/api/web/runs/{run_id}/novel-chapters/{index}") {
        novelChapterCall(storage) { _, manifest ->
            val index = call.parameters["index"]?.toIntOrNull()
                ?: throw IllegalArgumentException("index 必须是数字。")
            val detail = service.chapterDetail(manifest, index)
                ?: throw NoSuchElementException("原文里没有第 $index 章。")
            call.respond(detail)
        }
    }
}

private suspend fun RoutingContext.novelChapterCall(
    storage: StorageService,
    block: suspend (String, JsonObject) -> Unit,
) {
    val runId = call.parameters["run_id"]
        ?: return call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
    val manifest = storage.readRunManifest(runId)
        ?: return call.respondError(HttpStatusCode.NotFound, "Run not found")
    try {
        block(runId, manifest)
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, (error.message ?: "Not found"))
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, (error.message ?: "Invalid request"))
    }
}
