package top.wkbin.zaomeng.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import top.wkbin.zaomeng.client.platform.ClientLog
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.FeatureReportStatus
import top.wkbin.zaomeng.platform.platformIoDispatcher

internal val repositoryJson: Json = Json { ignoreUnknownKeys = true }

/**
 * 所有 Repository 请求的**统一入口**。
 *
 * R4 起在这里接入通用功能事件上报：**新增一个 repository 方法，不改任何诊断代码，
 * 它就会自动出现在「运行诊断 → 功能事件」里**（功能 = `repository`，操作 = 调用点标签）。
 *
 * 纪律：
 * - **只记录**：上报失败一律吞掉（`DiagnosticsSink.report` 本身也不允许抛），
 *   业务异常照原样抛出，控制流一字未改；
 * - **成功也报**：只报失败的话，「这个方法从没被调用过」与「每次都成功」长得一模一样；
 * - **不记录请求/响应正文**：正文里可能有用户原文与密钥，只带异常消息摘要。
 *
 * 调用点标签用 lambda 的类名推导（JVM/Android 上形如 `PersonaRepositories$getPersonaAvatar$2`），
 * 因此不需要改动 100 多处调用点；推导失败时退化为 `repository.request`。
 * 需要更可读的名字时可以显式传 [label]。
 */
internal suspend fun <T> repositoryRequest(
    label: String = "",
    block: suspend () -> T,
): T = withContext(platformIoDispatcher) {
    val action = label.ifBlank { callerLabel(block) }
    val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
    try {
        val result = block()
        DiagnosticsSink.report(
            feature = FEATURE_REPOSITORY,
            action = action,
            status = FeatureReportStatus.OK,
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        )
        result
    } catch (cancelled: CancellationException) {
        // 取消不算失败：用户切页面导致的取消不是「功能坏了」，报成 FAILED 会污染成功率。
        DiagnosticsSink.report(
            feature = FEATURE_REPOSITORY,
            action = action,
            status = FeatureReportStatus.DEGRADED,
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            detail = "请求被取消",
        )
        throw cancelled
    } catch (error: ApiRequestException) {
        DiagnosticsSink.report(
            feature = FEATURE_REPOSITORY,
            action = action,
            status = FeatureReportStatus.FAILED,
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            detail = error.message.orEmpty().take(200),
        )
        throw error
    } catch (error: Throwable) {
        ClientLog.e("Repository", "Repository request failed", error)
        val readable = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "请求失败，请稍后重试。"
        DiagnosticsSink.report(
            feature = FEATURE_REPOSITORY,
            action = action,
            status = FeatureReportStatus.FAILED,
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            detail = readable.take(200),
        )
        throw ApiRequestException(readable, error)
    }
}

/** 功能事件里的功能名：所有 Repository 调用共用。 */
private const val FEATURE_REPOSITORY = "repository"

/**
 * 从 lambda 的运行时类名推导调用点标签（零改动的自动覆盖）。
 *
 * 失败（平台不支持反射 / simpleName 为空）时返回 `repository.request`，
 * 不影响覆盖率——只是标签粗一点。
 */
private fun callerLabel(block: Any): String = runCatching {
    block::class.simpleName
        ?.substringBeforeLast('$')
        ?.takeIf { it.isNotBlank() }
        ?: "repository.request"
}.getOrDefault("repository.request")

internal fun errorDetail(body: String?, status: Int): String {
    val detail = runCatching {
        repositoryJson.parseToJsonElement(body.orEmpty()).jsonObject["detail"]
    }.getOrNull()
    val message = when (detail) {
        is JsonPrimitive -> detail.contentOrNull.orEmpty()
        is JsonArray -> detail.mapNotNull { item ->
            val issue = item as? JsonObject ?: return@mapNotNull null
            val location = (issue["loc"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.dropWhile { it == "body" }
                ?.joinToString(".")
                .orEmpty()
            val issueMessage = (issue["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                issueMessage.isBlank() -> null
                location.isBlank() -> issueMessage
                else -> "$location: $issueMessage"
            }
        }.take(3).joinToString("；")
        is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
            ?: (detail["detail"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        else -> ""
    }
    return message.takeIf(String::isNotBlank) ?: "本地接口返回 HTTP $status。"
}

internal fun parseFilename(contentDisposition: String): String {
    val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.groupValues?.getOrNull(1)
    if (!encoded.isNullOrBlank()) {
        return percentDecodeUtf8(encoded.replace("+", "%2B"))
    }
    return Regex("filename=\"?([^;\"]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
}

internal fun kindToSegment(kind: ReusableCardKind): String = when (kind) {
    ReusableCardKind.Scene -> "scene"
    ReusableCardKind.Self -> "self"
    ReusableCardKind.Opening -> "opening"
}

private fun percentDecodeUtf8(input: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val high = input[index + 1].digitToIntOrNull(16)
            val low = input[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().let { bytes.addAll(it.toList()) }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

class ApiRequestException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause)

enum class ReusableCardKind {
    Scene,
    Self,
    Opening,
}
