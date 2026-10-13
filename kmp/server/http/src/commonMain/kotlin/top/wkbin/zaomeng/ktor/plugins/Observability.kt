package top.wkbin.zaomeng.ktor.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import top.wkbin.zaomeng.ktor.http.respondError
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.FeatureReportStatus
import top.wkbin.zaomeng.platform.PlatformLog

fun Application.configureObservability() {
    // ktor-server-call-logging 无 Kotlin/Native 变体，用 Monitoring 拦截器实现等价请求日志，
    // 三端行为一致（输出到 PlatformLog 与应用日志）。
    //
    // R4：这里同时是**服务端统一入口兜底**。任何走 Ktor 路由的操作——包括**今天还不存在**、
    // 以后新加的功能——都会自动被记成一条功能事件（`route` × 规范化路径），
    // 新功能不需要写任何诊断代码就会出现在「运行诊断 → 功能事件」里。
    //
    // 三条纪律：
    // - **只记录**：不改控制流、不吞异常（`finally` 里做事，异常照常向上抛给 StatusPages）；
    // - **成功也要报**：只记失败的话，「这个接口从没被调用过」与「每次都成功」分不出来；
    // - **不记录查询串**：URI 常带 `?api_key=...`，只取路径部分，避免密钥进诊断。
    intercept(ApplicationCallPipeline.Monitoring) {
        val startedAt = System.currentTimeMillis()
        try {
            proceed()
        } finally {
            val statusCode = call.response.status()?.value
            val status = statusCode?.toString() ?: "-"
            val line = "${call.request.httpMethod.value} ${call.request.uri} -> $status"
            PlatformLog.i(TAG, line)
            call.application.environment.log.info(line)
            runCatching {
                DiagnosticsSink.report(
                    feature = FEATURE_ROUTE,
                    action = "${call.request.httpMethod.value} ${normalizePath(call.request.uri)}",
                    status = statusOf(statusCode),
                    durationMs = System.currentTimeMillis() - startedAt,
                    detail = if (statusCode != null && statusCode >= 400) "HTTP $status" else "",
                )
            }
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            PlatformLog.e(TAG, "Unhandled Ktor request failure: ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.application.environment.log.error(
                "Unhandled Ktor request failure: ${call.request.httpMethod.value} ${call.request.uri}",
                cause,
            )
            // 未处理异常同样计入统一入口兜底：500 是「功能坏了」最直接的信号，
            // 而它此前只进 logcat，用户手里的诊断导出里看不到。
            runCatching {
                DiagnosticsSink.report(
                    feature = FEATURE_ROUTE,
                    action = "${call.request.httpMethod.value} ${normalizePath(call.request.uri)}",
                    status = FeatureReportStatus.FAILED,
                    durationMs = 0,
                    detail = "未处理异常：${cause::class.simpleName ?: "Throwable"}: ${cause.message.orEmpty().take(200)}",
                )
            }
            if (!call.response.isCommitted) {
                call.respondError(HttpStatusCode.InternalServerError, "Internal server error")
            }
        }
    }

}

/** 功能事件里的功能名：所有 HTTP 路由共用。 */
private const val FEATURE_ROUTE = "route"

/**
 * HTTP 状态码 → 功能事件结局。
 *
 * - 5xx → FAILED（服务端确实坏了）；
 * - 4xx → DEGRADED（请求没成，但服务本身还活着；把它算成 FAILED 会让成功率失真）；
 * - 其余 → OK。
 */
internal fun statusOf(statusCode: Int?): FeatureReportStatus = when {
    statusCode == null -> FeatureReportStatus.FAILED
    statusCode >= 500 -> FeatureReportStatus.FAILED
    statusCode >= 400 -> FeatureReportStatus.DEGRADED
    else -> FeatureReportStatus.OK
}

/**
 * 路径规范化：把「每次都不一样」的部分抹掉，否则聚合表会被 id 撑爆。
 *
 * - 去掉查询串（**可能是密钥**）；
 * - UUID / 长十六进制 / 长数字段 → `{id}`；
 * - 纯数字段 → `{n}`。
 *
 * 结果形如 `/api/web/runs/{id}/sessions/{id}`，一个端点一行统计。
 */
internal fun normalizePath(uri: String): String {
    val path = uri.substringBefore('?').substringBefore('#')
    if (path.isEmpty()) return "/"
    return path.split('/').joinToString("/") { segment ->
        when {
            segment.isEmpty() -> segment
            UUID_REGEX.matches(segment) -> "{id}"
            segment.length >= 16 && HEX_REGEX.matches(segment) -> "{id}"
            segment.all { it.isDigit() } -> "{n}"
            else -> segment
        }
    }
}

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

private const val TAG = "ZaomengServer"
