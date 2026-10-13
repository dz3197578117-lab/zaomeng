package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.ProcessExitInfoDto

/**
 * 运行日志脱敏。
 *
 * 日志一旦随「运行诊断」导出或从应用内分享出去，就离开了应用私有目录，
 * 因此导出前必须过一遍脱敏：密钥、令牌、Authorization 头、URL 里的 key 查询参数
 * 一律替换为 `***`，超长行截断到 [MAX_LINE_CHARS]。
 *
 * 设计取向与仓库既有规则一致（不记录完整模型输出、完整提示词、密钥）：
 * 这里只做「必然泄露凭据」这一档的拦截，不做语义级内容审查——原文与提示词
 * 仍可能出现在日志里，所以导出入口在 UI 上仍需用户主动触发。
 *
 * 规则是纯函数、无状态，便于回归测试；不依赖 Android，JVM 测试可直接覆盖。
 */
object LogRedactor {
    /** 单行最大字符数：超长行（例如整段模型输出）截断，避免一条日志撑爆导出文件。 */
    const val MAX_LINE_CHARS = 4_000

    private const val MASK = "***"
    private const val TRUNCATED_SUFFIX = "…[已截断]"

    /**
     * 脱敏规则。顺序有意义：先处理带引号的 JSON 形态，再处理 `key=value` 形态，
     * 最后兜底裸密钥（`sk-` 等）。所有规则都只吃掉「值」，保留键名便于排查。
     *
     * `$1` / `$2` 是 Regex 的分组引用（用 `${'$'}1` 写字面量），`$MASK` 是 Kotlin 插值。
     */
    private val rules: List<Pair<Regex, String>> = listOf(
        // "apiKey": "sk-xxx" / "token":"xxx"（JSON 形态）
        Regex(
            """(?i)("(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|secret|password|passwd|authorization)"\s*:\s*")([^"]{4,})(")""",
        ) to "${'$'}1$MASK${'$'}3",

        // Bearer 令牌。
        // 必须排在「键值形态」之前：否则 `Authorization: Bearer eyJ...` 会先被通用规则
        // 吃掉 "Bearer" 这个词，真正的令牌反而原样留在日志里。
        Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}""") to "${'$'}1 $MASK",

        // URL 查询串里的凭据：?api_key=xxx&token=yyy。
        // 排在「键值形态」之前：URL 的值到 & 为止，交给通用规则会把后面的参数一起吃掉。
        Regex("""(?i)([?&](?:api[_-]?key|apikey|key|token|access[_-]?token|api[_-]?token)=)[^&\s]{4,}""") to "${'$'}1$MASK",

        // api_key=xxx / token: xxx / password = xxx（键值形态）
        // - 值遇到 & 即停，避免顺带吃掉 URL 的后续参数；
        // - 值已经是 Bearer/*** 时不再重复遮蔽，避免产出 "*** ***" 这种噪音。
        Regex(
            """(?i)\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|secret|password|passwd|x-api-key|authorization)\b(\s*[:=]\s*)(?!(?:[Bb]earer\b|\*{3}))([^\s,;"'&]{4,})""",
        ) to "${'$'}1${'$'}2$MASK",

        // 裸密钥：OpenAI / GitHub 等常见前缀
        Regex("""\b(sk|rk|pk|ghp|gho|ghu|ghs|github_pat)-[A-Za-z0-9_\-]{12,}""") to "${'$'}1-$MASK",
    )

    /** 脱敏单行（并截断超长行）。 */
    fun redactLine(line: String): String {
        var result = line
        for ((pattern, replacement) in rules) {
            result = pattern.replace(result, replacement)
        }
        if (result.length > MAX_LINE_CHARS) {
            result = result.take(MAX_LINE_CHARS) + TRUNCATED_SUFFIX
        }
        return result
    }

    /** 批量脱敏；行数不变，便于与原始行号对照。 */
    fun redactLines(lines: List<String>): List<String> = lines.map(::redactLine)

    /** 崩溃记录脱敏（堆栈同样可能带出凭据，例如异常信息里回显的请求 URL）。 */
    fun redactCrashes(crashes: List<CrashRecordDto>): List<CrashRecordDto> = crashes.map { crash ->
        crash.copy(
            summary = redactLine(crash.summary),
            stackTrace = crash.stackTrace.lineSequence().joinToString("\n", transform = ::redactLine),
        )
    }

    /**
     * 进程退出记录脱敏。
     *
     * ANR / native 崩溃轨迹里会回显请求 URL、Authorization 头等内容，导出前同样必须过一遍。
     * 轨迹按行脱敏（不是整段当一行），否则单行 4000 字符的截断会把后面的敏感内容一起带出去。
     */
    fun redactProcessExits(exits: List<ProcessExitInfoDto>): List<ProcessExitInfoDto> = exits.map { exit ->
        exit.copy(
            description = redactLine(exit.description),
            trace = exit.trace.lineSequence().joinToString("\n", transform = ::redactLine),
        )
    }

    /** 检测文本中是否仍残留疑似凭据；用于测试与导出前的自检。 */
    fun containsSensitive(text: String): Boolean = rules.any { it.first.containsMatchIn(text) }
}
