package top.wkbin.zaomeng.ktor.features

import kotlinx.coroutines.withTimeoutOrNull
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 探针执行 + 缓存。
 *
 * 三条硬性行为：
 * 1. **独立失败**：单个探针抛异常 / 超时只影响它自己，转成 FAIL（带异常摘要），
 *    其它探针与整个诊断页照常。
 * 2. **廉价可缓存**：结果允许缓存最近一次。OK/WARN/NO_DATA/SKIPPED 按
 *    [ttlMillis] 缓存，FAIL 按 [failTtlMillis] 缓存（坏的东西不必每刷新一次就再撞一次）。
 *    缓存命中的项不重新执行、不产生耗时，并在结果里标 `fromCache`。
 * 3. **绝不改业务数据**：runner 只调用 `probe.check(ctx)`，上下文里没有写入口。
 *
 * 并发模型：逐个**串行**执行。理由与「廉价」一致——探针读的是同一批清单/文件，
 * 串行能让存储层已有的清单缓存真正生效，也避免高并发把低端机打爆。
 */
class FeatureProbeRunner(
    private val registry: FeatureProbeRegistry,
    private val context: FeatureProbeContext,
    /** 通过结论的缓存时长（默认 30 秒：诊断页刷新不至于重复读盘）。 */
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    /** 失败结论的缓存时长（默认 3 分钟：持续坏的东西不必每次刷新都重跑）。 */
    private val failTtlMillis: Long = DEFAULT_FAIL_TTL_MILLIS,
    /** 单个探针的执行上限（默认 5 秒）；超时按 FAIL 处理，不会拖住整页。 */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private data class Cached(
        val entry: FeatureProbeEntry,
        val cachedAtMillis: Long,
    )

    private val cache = HashMap<String, Cached>()
    private val cacheLock = SimpleLock()

    /** 全部探针的最近结果（顺序 = 注册顺序）。 */
    suspend fun runAll(): ProbeRunOutcome {
        val startedAt = nowEpochMillis()
        val entries = registry.all().map { probe -> runOne(probe) }
        val duration = nowEpochMillis() - startedAt
        return ProbeRunOutcome(
            entries = entries,
            durationMillis = duration,
            cachedCount = entries.count { it.fromCache },
        )
    }

    /** 单个探针：命中缓存直接返回，否则执行并写入缓存。 */
    suspend fun runOne(probe: FeatureProbe): FeatureProbeEntry {
        val now = nowEpochMillis()
        cacheLock.withLock { cache[probe.id] }?.let { cached ->
            val ttl = if (cached.entry.result.status == ProbeStatus.FAIL) failTtlMillis else ttlMillis
            if (ttl > 0 && now - cached.cachedAtMillis < ttl) {
                return cached.entry.copy(fromCache = true)
            }
        }
        val startedAt = nowEpochMillis()
        val result = execute(probe)
        val duration = nowEpochMillis() - startedAt
        val checkedAt = nowIsoString()
        val previousOkAt = cacheLock.withLock { cache[probe.id] }
            ?.let { cached -> if (cached.entry.result.status == ProbeStatus.OK) cached.entry.checkedAt else cached.entry.lastOkAt }
            .orEmpty()
        val entry = FeatureProbeEntry(
            probe = probe,
            result = result,
            checkedAt = checkedAt,
            durationMs = duration,
            fromCache = false,
            lastOkAt = if (result.status == ProbeStatus.OK) checkedAt else previousOkAt,
        )
        cacheLock.withLock { cache[probe.id] = Cached(entry, nowEpochMillis()) }
        return entry
    }

    /** 清空缓存（诊断页显式「重新自检」时用）。 */
    fun invalidate() {
        cacheLock.withLock { cache.clear() }
    }

    /**
     * 执行单个探针，保证**绝不向外抛异常**：
     * - 抛异常 → FAIL + 异常摘要；
     * - 超时 → FAIL + 「检查超时」；
     * - 正常 → 归一化后的结果。
     */
    private suspend fun execute(probe: FeatureProbe): ProbeResult {
        val result = try {
            withTimeoutOrNull(timeoutMillis) { probe.check(context) }
        } catch (error: Throwable) {
            // 探针是诊断的一部分：它自己炸了也只能变成一条 FAIL，不能影响别的探针或整页。
            return ProbeResult.fail(
                summary = "探针自身出错：${errorSummary(error)}",
                details = listOf(errorSummary(error)),
            )
        }
        if (result == null) {
            return ProbeResult.fail("探针执行超时（> ${timeoutMillis}ms），已跳过本次结论。")
        }
        return result.normalized()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val DEFAULT_FAIL_TTL_MILLIS = 180_000L
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** 异常摘要：类型 + 消息，长度受限；**不包含任何数据内容**。 */
        fun errorSummary(error: Throwable): String {
            val type = error::class.simpleName ?: "Throwable"
            val message = error.message?.trim()?.take(160).orEmpty()
            return if (message.isEmpty()) type else "$type: $message"
        }
    }
}

/** 一轮全量检查的结果。 */
data class ProbeRunOutcome(
    val entries: List<FeatureProbeEntry>,
    val durationMillis: Long,
    val cachedCount: Int,
) {
    fun countOf(status: ProbeStatus): Int = entries.count { it.result.status == status }

    /** 计入「问题」的探针数：只有 FAIL 才算问题，WARN 只是提示。 */
    fun failCount(): Int = countOf(ProbeStatus.FAIL)
}
