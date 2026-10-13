package top.wkbin.zaomeng.platform

/**
 * 进程退出原因（Android `ApplicationExitInfo.reason`）的**跨平台**映射与归因。
 *
 * 存在理由（实机 2.1.12 诊断导出暴露的两个真实缺陷）：
 * 1. 映射只覆盖 API 30 就存在的常量，`reason=16`（`REASON_PACKAGE_UPDATED`，应用被覆盖安装）
 *    被显示成 `UNKNOWN(16)`——一次完全正常的升级看起来像故障；
 * 2. 真正有用的信息在系统给的 `description` 里（`killDueToPackageUpdate`、
 *    `o-stop(40)` 这类 OEM 后台清理），却只被当成附注，界面上用户只看到 `OTHER` / `UNKNOWN`。
 *
 * 因此本文件做三件事（**纯函数、无平台依赖，可单测**）：
 * - 补全 1–16 的 reason 映射（0 是 `REASON_UNKNOWN`，本就没有可归因信息），
 *   映射不到的保留 `UNKNOWN(n)` 并把原始码放在最前面；
 * - 把 `description` 提升为一等字段并给出可读中文归因；
 * - **区分严重度**：「被系统/OEM 清理」「被更新」「用户主动退出」不是崩溃，
 *   不能算进崩溃数，也不该按崩溃严重度排在最前。
 *
 * 归因只是**提示**：`description` 是系统/OEM 的自由文本，没有稳定契约，
 * 因此所有匹配都是「包含关键字」的宽松匹配，匹配不到就退化为按 reason 的默认归因。
 */

/**
 * 退出原因的严重度（与诊断问题清单的 severity 同名同序）。
 *
 * `fatal` / `error` 才代表异常退出；`warning` 是可疑但不一定异常；
 * `info` 是**明确的正常退出**，问题清单里不与崩溃同列。
 */
enum class ExitSeverity {
    FATAL,
    ERROR,
    WARN,
    INFO,
}

/** 与前端 `DiagnosticsProblemDto.kind` 对齐的问题类型。 */
enum class ExitProblemKind {
    /** 未捕获异常（Java/Kotlin）。 */
    CRASH,

    /** native 崩溃。 */
    CRASH_NATIVE,

    /** 无响应被杀。 */
    ANR,

    /** 其他退出（含疑似异常）。 */
    PROCESS_EXIT,

    /** 系统/OEM 主动清理、应用被更新等正常退出。 */
    INTENTIONAL_EXIT,
}

/** 归因来源。 */
enum class ExitAttributionSource {
    /** 由系统/OEM 的 `description` 文本判定。 */
    DESCRIPTION,

    /** 由 `reason` 码判定。 */
    REASON,
}

/**
 * 一条退出记录的展示判定结果。
 *
 * @param reasonCode 原始 reason 码（负数/0 表示系统没给）
 * @param reasonName 映射名（映射不到时是 `UNKNOWN(n)`）
 * @param reasonLabel 中文可读原因（如「应用更新（覆盖安装）」）
 * @param severity 严重度（决定是否计入崩溃数、排序位置）
 * @param attribution 中文归因（如「系统/OEM 后台清理」）
 * @param attributionSource 归因来自 description 还是 reason
 * @param problemKind 问题清单里的类型
 * @param isCrash 是否算「崩溃」（fatal/error 且属于崩溃类）
 * @param knownReason 该 reason 码是否有明确映射（false = UNKNOWN 兜底）
 */
data class ExitReasonInfo(
    val reasonCode: Int,
    val reasonName: String,
    val reasonLabel: String,
    val severity: ExitSeverity,
    val attribution: String,
    val attributionSource: ExitAttributionSource,
    val problemKind: ExitProblemKind,
    val isCrash: Boolean,
    val knownReason: Boolean,
)

/** 一条 reason 码的映射定义。 */
data class ExitReasonDefinition(
    val code: Int,
    val name: String,
    val label: String,
    val severity: ExitSeverity,
    val problemKind: ExitProblemKind,
)

/**
 * reason 码 → 映射表。
 *
 * 覆盖 Android `ApplicationExitInfo` 的 1–16（0 是 `REASON_UNKNOWN`，系统不会给出可归因的信息）。
 * 刻意**不引用 Android 常量**：一是 commonMain 不能引，二是编译期 SDK 里没有的常量
 * 会让「新系统给的值」直接掉进 UNKNOWN——这正是 16 被误报的原因。
 *
 * 数字必须与平台常量逐一对齐（Android 10–16 一致，核对方式见下）。写错一位的后果很具体：
 * - `8` 是 `REASON_PERMISSION_CHANGE`、`9` 是 `REASON_EXCESSIVE_RESOURCE_USAGE`、
 *   `10` 是 `REASON_USER_REQUESTED`；把 8/9/10 整体前移一位会让「权限变更」显示成
 *   「资源占用过高」、让用户主动退出掉进 `UNKNOWN(10)` 并被当成异常退出；
 * - `12` 是 `REASON_DEPENDENCY_DIED`、`13` 才是 `REASON_OTHER`；漏掉 13 会让
 *   OEM 后台清理（`o-stop`）显示成未映射。
 *
 * 核对命令（本仓库已验证 android-36 / android-37.0 两个平台包一致）：
 * ```
 * unzip -o -q $ANDROID_HOME/platforms/android-36/android.jar 'android/app/ApplicationExitInfo*.class'
 * javap -p -constants android/app/ApplicationExitInfo.class | grep REASON
 * ```
 */
val EXIT_REASON_TABLE: List<ExitReasonDefinition> = listOf(
    ExitReasonDefinition(1, "EXIT_SELF", "进程自行退出（正常）", ExitSeverity.INFO, ExitProblemKind.INTENTIONAL_EXIT),
    ExitReasonDefinition(2, "SIGNALED", "被信号终止", ExitSeverity.ERROR, ExitProblemKind.CRASH),
    ExitReasonDefinition(3, "LOW_MEMORY", "系统内存不足被杀", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(4, "CRASH", "Java 崩溃（未捕获异常）", ExitSeverity.FATAL, ExitProblemKind.CRASH),
    ExitReasonDefinition(5, "CRASH_NATIVE", "Native 崩溃", ExitSeverity.FATAL, ExitProblemKind.CRASH_NATIVE),
    ExitReasonDefinition(6, "ANR", "无响应（ANR）被杀", ExitSeverity.ERROR, ExitProblemKind.ANR),
    ExitReasonDefinition(7, "INITIALIZATION_FAILURE", "进程初始化失败", ExitSeverity.FATAL, ExitProblemKind.CRASH),
    ExitReasonDefinition(8, "PERMISSION_CHANGE", "权限变更导致退出", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(9, "EXCESSIVE_RESOURCE_USAGE", "资源占用过高被系统终止", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(10, "USER_REQUESTED", "用户主动退出（返回键 / 划掉任务）", ExitSeverity.INFO, ExitProblemKind.INTENTIONAL_EXIT),
    ExitReasonDefinition(11, "USER_STOPPED", "被用户停止（设置里强制停止等）", ExitSeverity.INFO, ExitProblemKind.INTENTIONAL_EXIT),
    ExitReasonDefinition(12, "DEPENDENCY_DIED", "依赖进程死亡导致退出", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(13, "OTHER", "其他原因退出", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(14, "FREEZER", "被系统冻结（cached 进程回收）", ExitSeverity.INFO, ExitProblemKind.INTENTIONAL_EXIT),
    ExitReasonDefinition(15, "PACKAGE_STATE_CHANGE", "应用状态变更（启用/停用等）", ExitSeverity.WARN, ExitProblemKind.PROCESS_EXIT),
    ExitReasonDefinition(16, "PACKAGE_UPDATED", "应用更新（覆盖安装）", ExitSeverity.INFO, ExitProblemKind.INTENTIONAL_EXIT),
)

private val EXIT_REASON_BY_CODE: Map<Int, ExitReasonDefinition> = EXIT_REASON_TABLE.associateBy { it.code }

/**
 * description 关键字 → 中文归因。
 *
 * 顺序敏感：先匹配到的胜出。`description` 是系统/OEM 自由文本，没有稳定契约，
 * 因此这里只做宽松包含匹配，匹配失败就退回按 reason 的默认归因。
 *
 * 为什么需要它：realme/OPPO 的 `o-stop(40)` 在系统里就是「后台被清理」，
 * 但它落到 reason=13（OTHER），界面上只显示「OTHER」——用户以为应用崩了。
 */
val EXIT_DESCRIPTION_ATTRIBUTIONS: List<ExitDescriptionAttribution> = listOf(
    ExitDescriptionAttribution(
        keyword = "killDueToPackageUpdate",
        attribution = "应用被更新（覆盖安装）",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "o-stop",
        attribution = "系统/OEM 后台清理",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "ostop",
        attribution = "系统/OEM 后台清理",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "force-stop",
        attribution = "被强制停止（用户或系统）",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "forcestop",
        attribution = "被强制停止（用户或系统）",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "background",
        attribution = "后台清理",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "anr",
        attribution = "无响应被杀（ANR）",
        severity = ExitSeverity.ERROR,
        problemKind = ExitProblemKind.ANR,
    ),
    ExitDescriptionAttribution(
        keyword = "low memory",
        attribution = "系统内存不足被杀",
        severity = ExitSeverity.WARN,
        problemKind = ExitProblemKind.PROCESS_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "lowmemory",
        attribution = "系统内存不足被杀",
        severity = ExitSeverity.WARN,
        problemKind = ExitProblemKind.PROCESS_EXIT,
    ),
    ExitDescriptionAttribution(
        keyword = "native",
        attribution = "Native 崩溃",
        severity = ExitSeverity.FATAL,
        problemKind = ExitProblemKind.CRASH_NATIVE,
    ),
    ExitDescriptionAttribution(
        keyword = "crash",
        attribution = "崩溃",
        severity = ExitSeverity.FATAL,
        problemKind = ExitProblemKind.CRASH,
    ),
    ExitDescriptionAttribution(
        keyword = "death",
        attribution = "崩溃",
        severity = ExitSeverity.FATAL,
        problemKind = ExitProblemKind.CRASH,
    ),
    ExitDescriptionAttribution(
        keyword = "user request",
        attribution = "用户主动退出",
        severity = ExitSeverity.INFO,
        problemKind = ExitProblemKind.INTENTIONAL_EXIT,
    ),
)

/** 一条 description 归因规则。 */
data class ExitDescriptionAttribution(
    val keyword: String,
    val attribution: String,
    val severity: ExitSeverity,
    val problemKind: ExitProblemKind,
)

/** 退出记录 `reason` 字段的展示名（映射不到时 `UNKNOWN(n)`）。 */
fun exitReasonName(reasonCode: Int): String =
    EXIT_REASON_BY_CODE[reasonCode]?.name ?: "UNKNOWN($reasonCode)"

/** 退出记录 `reason` 字段的中文可读原因。 */
fun exitReasonLabel(reasonCode: Int): String =
    EXIT_REASON_BY_CODE[reasonCode]?.label ?: "未映射的退出原因（原始码 $reasonCode）"

/** 该 reason 码是否有明确映射（false = 走 `UNKNOWN(n)` 兜底）。 */
fun isKnownExitReason(reasonCode: Int): Boolean = EXIT_REASON_BY_CODE.containsKey(reasonCode)

/** 已映射的 reason 码（升序），诊断报告里列出，说明「还有哪些没映射」。 */
fun mappedExitReasonCodes(): List<Int> = EXIT_REASON_TABLE.map { it.code }.distinct().sorted()

/** 已映射原因的展示行（`code NAME：中文说明`），报告里直接展示。 */
fun mappedExitReasonLines(): List<String> = EXIT_REASON_TABLE
    .sortedBy { it.code }
    .map { "${it.code} ${it.name}：${it.label}" }

/** 未映射的 reason 码（实测到的原始码 - 已映射集合），升序去重。 */
fun unmappedExitReasonCodes(observedCodes: Collection<Int>): List<Int> =
    observedCodes.filter { it != 0 && !isKnownExitReason(it) }.distinct().sorted()

/**
 * 归类一条退出记录：映射 reason + 用 description 覆盖归因 + 定严重度。
 *
 * 判定顺序（与用户看到的信息重要程度一致）：
 * 1. `reason` 码本身是致命崩溃（CRASH / CRASH_NATIVE / INITIALIZATION_FAILURE）→ 直接按崩溃处理，
 *    **不让 description 把崩溃降级**（有的 OEM 会把崩溃描述成 "crash" 之外的字样）；
 * 2. 否则用 description 关键字判定（这就是 `o-stop` / `killDueToPackageUpdate` 生效的地方）；
 * 3. 都没有则按 reason 的默认定义。
 */
fun classifyExitReason(reasonCode: Int, description: String): ExitReasonInfo {
    val definition = EXIT_REASON_BY_CODE[reasonCode]
    val name = definition?.name ?: "UNKNOWN($reasonCode)"
    val label = definition?.label ?: "未映射的退出原因（原始码 $reasonCode）"
    val baseSeverity = definition?.severity ?: ExitSeverity.WARN
    val baseKind = definition?.problemKind ?: ExitProblemKind.PROCESS_EXIT

    // 1) 明确的致命崩溃：description 不允许把它降级。
    val hardCrash = baseSeverity == ExitSeverity.FATAL &&
        (baseKind == ExitProblemKind.CRASH || baseKind == ExitProblemKind.CRASH_NATIVE)
    if (hardCrash) {
        return ExitReasonInfo(
            reasonCode = reasonCode,
            reasonName = name,
            reasonLabel = label,
            severity = baseSeverity,
            attribution = baseKind.let { if (it == ExitProblemKind.CRASH_NATIVE) "Native 崩溃" else "崩溃" },
            attributionSource = ExitAttributionSource.REASON,
            problemKind = baseKind,
            isCrash = true,
            knownReason = definition != null,
        )
    }

    // 2) description 关键字归因（OEM 后台清理 / 被更新 主要靠这条）。
    val normalized = description.lowercase()
    val matched = EXIT_DESCRIPTION_ATTRIBUTIONS.firstOrNull { normalized.contains(it.keyword.lowercase()) }
    if (matched != null) {
        val kind = matched.problemKind
        return ExitReasonInfo(
            reasonCode = reasonCode,
            reasonName = name,
            reasonLabel = label,
            severity = matched.severity,
            attribution = matched.attribution,
            attributionSource = ExitAttributionSource.DESCRIPTION,
            problemKind = kind,
            isCrash = matched.severity == ExitSeverity.FATAL || matched.severity == ExitSeverity.ERROR,
            knownReason = definition != null,
        )
    }

    // 3) 回落到 reason 码的默认定义。
    return ExitReasonInfo(
        reasonCode = reasonCode,
        reasonName = name,
        reasonLabel = label,
        severity = baseSeverity,
        attribution = label,
        attributionSource = ExitAttributionSource.REASON,
        problemKind = baseKind,
        isCrash = baseSeverity == ExitSeverity.FATAL ||
            (baseSeverity == ExitSeverity.ERROR && baseKind != ExitProblemKind.PROCESS_EXIT),
        knownReason = definition != null,
    )
}

/** 诊断问题清单用的 severity 字符串（fatal / error / warning / info）。 */
fun ExitSeverity.wireName(): String = when (this) {
    ExitSeverity.FATAL -> "fatal"
    ExitSeverity.ERROR -> "error"
    ExitSeverity.WARN -> "warning"
    ExitSeverity.INFO -> "info"
}
