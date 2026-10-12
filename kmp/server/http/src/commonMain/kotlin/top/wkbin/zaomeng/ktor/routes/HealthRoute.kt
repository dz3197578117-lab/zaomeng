package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import top.wkbin.zaomeng.ktor.GeneratedAppVersion

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val backend: String,
)

/**
 * 健康检查路由
 *
 * 提供 /api/web/health 端点，与 Python FastAPI 版本兼容
 */
fun Route.healthRoute() {
    get("/api/web/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                // 应用版本，由构建期生成（来源：kmp/gradle.properties）。
                // 此前写死 "0.1.0"，与应用版本无关，排查时会误导。
                version = GeneratedAppVersion.VERSION_NAME,
                backend = "ktor",
            )
        )
    }
}
