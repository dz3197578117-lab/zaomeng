package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.FeatureEventStatDto
import top.wkbin.zaomeng.platform.ClientDiagnostics

/**
 * 通用功能事件聚合（R4）。
 *
 * 把 [ClientDiagnostics] 里的累计计数映射成对外契约。
 *
 * 为什么是「累计计数」而不是「流水条数」：流水受环形缓冲上限约束，会丢；
 * 报告里若用流水长度，一次高频操作的失败会随着新事件涌入而被冲淡成「看起来还好」。
 *
 * 纯函数、无 IO：可以在 JVM 测试里直接喂累计数据验证成功率与耗时统计。
 */
object FeatureEventAggregator {

    fun aggregate(
        aggregates: List<ClientDiagnostics.FeatureAggregate> = ClientDiagnostics.featureAggregates(),
        limit: Int = DEFAULT_LIMIT,
    ): List<FeatureEventStatDto> = aggregates
        .map { item ->
            FeatureEventStatDto(
                feature = item.feature,
                action = item.action,
                total = item.total.toInt(),
                okCount = item.okCount.toInt(),
                degradedCount = item.degradedCount.toInt(),
                failedCount = item.failedCount.toInt(),
                successRate = successRate(item.okCount, item.failedCount),
                avgDurationMs = if (item.total > 0) item.totalDurationMs / item.total else 0,
                maxDurationMs = item.maxDurationMs,
                firstAt = item.firstAt,
                lastAt = item.lastAt,
                lastStatus = item.lastStatus,
                lastDetail = item.lastDetail,
            )
        }
        // 先按失败数、再按调用量排序：报告里最该被看到的是「一直在失败的」与「量最大的」。
        .sortedWith(
            compareByDescending<FeatureEventStatDto> { it.failedCount }
                .thenByDescending { it.total },
        )
        .take(limit)

    /**
     * 成功率百分比。
     *
     * 分母只算「成功 + 失败」，**降级不计入分母**：降级是「能继续但结果不是最优」，
     * 把它当失败会让成功率无谓偏低，当成功又会掩盖「一直在走兜底」。
     * 两者都为 0 时返回 0，界面上显示为「—」而不是「0%」。
     */
    fun successRate(ok: Long, failed: Long): Int {
        val denominator = ok + failed
        if (denominator <= 0) return 0
        return ((ok * 100.0) / denominator).toInt().coerceIn(0, 100)
    }

    /** 报告里最多展示多少组（界面另有渲染上限，这里先截一次，避免导出膨胀）。 */
    const val DEFAULT_LIMIT = 40
}
