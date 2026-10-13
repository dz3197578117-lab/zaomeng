package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.zaomeng.data.api.KnownFeatures
import top.wkbin.zaomeng.data.api.computeUnregisteredFeatures
import top.wkbin.zaomeng.data.api.computeUnknownFeatureRefs

/**
 * 探针结论。
 *
 * [SKIPPED] 与 [NO_DATA] 都必须与 [FAIL] 严格区分：把「没数据」当成「坏了」
 * 会让用户去修一个根本没坏的功能。两者的差别只在原因是否已知：
 * 已知的环境性原因（运行日志采集关闭 → 无样本）用 [SKIPPED]，其余「还没用过」用 [NO_DATA]。
 */
enum class ProbeStatus {
    /** 检查通过。 */
    OK,

    /** 能跑，但有可疑之处（截断、覆盖率不足、索引与文件不一致等）。 */
    WARN,

    /** 明确不可用或与预期不符。 */
    FAIL,

    /** 无样本可判定（该功能还没被使用过）。 */
    NO_DATA,

    /** 环境性缺失样本，明确知道原因（例如「运行日志采集已关闭」）。 */
    SKIPPED,
}

/**
 * 证据值：**只允许数字或短文本**。
 *
 * 用密封类而不是 `Any` 是为了让「证据里塞了正文」在编译期就不可能：
 * 探针没有别的通道把长文本塞进诊断导出。
 */
sealed interface ProbeEvidenceValue {
    fun render(): String
}

data class ProbeNumber(val value: Number) : ProbeEvidenceValue {
    override fun render(): String = value.toString()
}

data class ProbeText(val value: String) : ProbeEvidenceValue {
    override fun render(): String = value
}

data class ProbeFlag(val value: Boolean) : ProbeEvidenceValue {
    override fun render(): String = if (value) "yes" else "no"
}

/** 证据表：键值对，键保持插入顺序。 */
class ProbeEvidence private constructor(
    private val entries: LinkedHashMap<String, ProbeEvidenceValue>,
) {
    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun asMap(): Map<String, ProbeEvidenceValue> = entries.toMap()

    /** 渲染成 DTO 用的 `Map<String, String>`（值在此处按上限截断）。 */
    fun render(): Map<String, String> = entries.entries.associate { (key, value) ->
        key.trim().take(MAX_KEY_CHARS) to value.render().trim().take(MAX_VALUE_CHARS)
    }

    class Builder {
        private val entries = LinkedHashMap<String, ProbeEvidenceValue>()

        fun number(key: String, value: Number) = apply {
            if (key.isNotBlank()) entries[key] = ProbeNumber(value)
        }

        fun text(key: String, value: String) = apply {
            if (key.isNotBlank()) entries[key] = ProbeText(value.ifBlank { "-" })
        }

        fun flag(key: String, value: Boolean) = apply {
            if (key.isNotBlank()) entries[key] = ProbeFlag(value)
        }

        fun build(): ProbeEvidence = ProbeEvidence(entries)
    }

    companion object {
        const val MAX_KEY_CHARS = 40
        const val MAX_VALUE_CHARS = 120

        fun build(block: Builder.() -> Unit): ProbeEvidence = Builder().apply(block).build()

        val EMPTY: ProbeEvidence = ProbeEvidence(LinkedHashMap())
    }
}

/**
 * 一次探针检查的结果。
 *
 * @param summary 一句话结论（人类可读，已截断）
 * @param evidence 证据数据（数字或短文本；这是结论的支撑，不是日志）
 * @param details 可选细节，每行一条，供界面上展开查看
 */
data class ProbeResult(
    val status: ProbeStatus,
    val summary: String,
    val evidence: ProbeEvidence = ProbeEvidence.EMPTY,
    val details: List<String> = emptyList(),
) {
    companion object {
        const val MAX_SUMMARY_CHARS = 200
        const val MAX_DETAIL_LINES = 40
        const val MAX_DETAIL_LINE_CHARS = 400

        fun ok(summary: String, evidence: ProbeEvidence = ProbeEvidence.EMPTY, details: List<String> = emptyList()) =
            ProbeResult(ProbeStatus.OK, summary, evidence, details)

        fun warn(summary: String, evidence: ProbeEvidence = ProbeEvidence.EMPTY, details: List<String> = emptyList()) =
            ProbeResult(ProbeStatus.WARN, summary, evidence, details)

        fun fail(summary: String, evidence: ProbeEvidence = ProbeEvidence.EMPTY, details: List<String> = emptyList()) =
            ProbeResult(ProbeStatus.FAIL, summary, evidence, details)

        fun noData(summary: String, evidence: ProbeEvidence = ProbeEvidence.EMPTY, details: List<String> = emptyList()) =
            ProbeResult(ProbeStatus.NO_DATA, summary, evidence, details)

        /**
         * 环境性跳过。**不是失败**：采集关闭、平台不支持一类原因导致本次无法判定时用它。
         *
         * @param reason 必须写清「为什么没样本」，界面会原样展示。
         */
        fun skipped(reason: String, evidence: ProbeEvidence = ProbeEvidence.EMPTY, details: List<String> = emptyList()) =
            ProbeResult(ProbeStatus.SKIPPED, "—（$reason）", evidence, details)
    }

    /** 归一化：所有字段在进入注册表前截断，避免任何探针把正文带进诊断导出。 */
    fun normalized(): ProbeResult = ProbeResult(
        status = status,
        summary = summary.trim().take(MAX_SUMMARY_CHARS),
        evidence = evidence,
        details = details.map { it.trim().take(MAX_DETAIL_LINE_CHARS) }
            .filter { it.isNotEmpty() }
            .take(MAX_DETAIL_LINES),
    )
}

/**
 * 功能自检探针契约。
 *
 * 设计要点：
 * - **每个功能自己注册检查项**，诊断代码里不写死清单——新增功能时只需注册一个探针，
 *   诊断页与诊断导出会自动列出它。
 * - [features] 必须引用 `KnownFeatures` 里的 id：未注册统计与「引用未知 id」漂移检查都靠它。
 * - [check] 是 suspend 且**必须廉价**：允许读已缓存的小文件/清单，不允许跑模型、不允许长时 I/O。
 *   需要缓存最近一次结果由 [FeatureProbeRunner] 统一负责，探针实现不用自己缓存。
 * - 探针**只读**：绝不能为了检查而「顺手修复」业务数据；修复必须由用户显式触发。
 * - 单个探针抛异常不得影响其它探针：由 [FeatureProbeRunner] 捕获并转成 FAIL。
 */
interface FeatureProbe {
    /** 稳定 id（例如 `bookshelf.import`）；同一功能可以有多个探针，但 id 必须唯一。 */
    val id: String

    /** 一句话功能名（诊断页每行的主标题）。 */
    val title: String

    /** 所属域，取 `KnownFeature.DOMAIN_*`。 */
    val domain: String

    /** 本探针覆盖的已知功能 id（可以覆盖多个，例如「提示词加载完整性」覆盖 4 个规则键）。 */
    val features: List<String>

    /** 执行检查。[ctx] 提供只读的数据访问与平台代理。 */
    suspend fun check(ctx: FeatureProbeContext): ProbeResult
}

/**
 * 一个探针的最近一次结果（含缓存信息）。
 *
 * [fromCache] 为真表示本次没有重新执行，直接返回上一轮结论——这是「昂贵检查可缓存」的实现位。
 */
data class FeatureProbeEntry(
    val probe: FeatureProbe,
    val result: ProbeResult,
    val checkedAt: String,
    val durationMs: Long,
    val fromCache: Boolean,
    /** 上一次成功结果的检查时间；本轮失败时用于提示「结论可能过期」。 */
    val lastOkAt: String = "",
)

/**
 * 功能自检注册表。
 *
 * 只负责「登记 + 枚举 + 查重」，不负责执行与缓存（那是 [FeatureProbeRunner]）。
 *
 * - 重复 id 会被**忽略并记录**（不抛异常：一个功能模块注册重了不应该让整页诊断崩掉），
 *   重复情况通过 [duplicateIds] 暴露到界面上。
 * - 注册顺序即展示顺序（按域分组由上层负责）。
 */
class FeatureProbeRegistry {
    private val probes = LinkedHashMap<String, FeatureProbe>()
    private val duplicates = LinkedHashSet<String>()

    /** 注册一个探针；id 重复时保留先注册的并记录重复。返回本次是否真的登记成功。 */
    fun register(probe: FeatureProbe): Boolean {
        val id = probe.id.trim()
        if (id.isEmpty()) return false
        if (probes.containsKey(id)) {
            duplicates += id
            return false
        }
        probes[id] = probe
        return true
    }

    /** 批量注册（模块初始化时用）。 */
    fun registerAll(vararg probes: FeatureProbe) {
        probes.forEach(::register)
    }

    /** 所有已注册探针（注册顺序）。 */
    fun all(): List<FeatureProbe> = probes.values.toList()

    fun find(id: String): FeatureProbe? = probes[id.trim()]

    fun size(): Int = probes.size

    fun isEmpty(): Boolean = probes.isEmpty()

    /** 重复 id（注册了两次以上的），供界面暴露「注册处有问题」。 */
    fun duplicateIds(): List<String> = duplicates.toList()

    /** 所有探针覆盖到的功能 id（含重复，未去重；未注册统计用）。 */
    fun coveredFeatureIds(): List<String> = probes.values.flatMap { it.features }.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 未注册的功能：`KnownFeatures` 里没有任何探针覆盖的那些。
     *
     * 这就是「把漏了什么变成可观测」的算法——单一来源清单减去探针覆盖集合。
     */
    fun unregisteredFeatures() = computeUnregisteredFeatures(coveredFeatureIds())

    /** 探针引用了清单里不存在的功能 id（注册处与清单漂移）。 */
    fun unknownFeatureRefs(): List<String> = computeUnknownFeatureRefs(coveredFeatureIds())

    /** 被两个以上探针覆盖的功能 id（仅信息用途，不参与未注册判定）。 */
    fun multiProbeFeatures(): List<String> {
        val known = KnownFeatures.all.map { it.id }.toSet()
        return coveredFeatureIds()
            .filter { it in known }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .toList()
    }
}

/** 探针注册模块：每个域一个，在服务图装配时调用。 */
fun interface FeatureProbeModule {
    fun registerInto(registry: FeatureProbeRegistry)
}

/** 把「短文本键值对」转成证据表（探针里最常见的一步）。 */
fun Map<String, String>.toProbeEvidence(): ProbeEvidence =
    ProbeEvidence.build { this@toProbeEvidence.forEach { (key, value) -> text(key, value) } }

/** 在已有结果上追加证据（键冲突时后加的值覆盖旧的）。 */
fun ProbeResult.withEvidence(extra: Map<String, String>): ProbeResult =
    copy(evidence = (evidence.render() + extra).toProbeEvidence())
