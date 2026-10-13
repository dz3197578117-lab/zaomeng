package top.wkbin.zaomeng.ktor.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.platform.SimpleLock

/**
 * 功能自检装配件：**注册表 + 上下文 + 执行器**的组合，供服务图持有。
 *
 * 两个关键行为：
 * 1. [refresh] 是 suspend，由「运行诊断」页刷新或显式「重新自检」触发；
 * 2. [snapshot] 是**同步**的，永远返回上一轮结果（首轮未跑时返回注册表统计，
 *    已注册/未注册计数依然正确，只是 `items` 为空）。
 *    诊断导出（`GET /export`）是同步的，不能为了等探针把 HTTP 请求挂住——
 *    这正是「探针必须廉价且可缓存」在架构上的落点。
 *
 * 采集开关无关性：本类**不接收也不查询** `RuntimeCaptureState`。
 * 结构化事件走独立的 `DiagnosticsEventLog`（`DiagnosticsSink` 写入），
 * 常规 logcat 采集关闭不会让任何探针缺少输入，顶多个别探针报「无样本」（SKIPPED）。
 */
class FeatureProbeExecutor(
    val registry: FeatureProbeRegistry,
    private val runner: FeatureProbeRunner,
) {
    private val lock = SimpleLock()

    @Volatile
    private var latest: FeatureProbeReportDto = FeatureProbeReportDto()

    /**
     * 上一轮结果（同步、无 IO）。
     *
     * 首轮探针还没跑完时**不返回空报告**，而是返回只依赖注册表的统计
     * （见 [pendingReport]）：否则概览/导出会显示「已注册 0 项 / 0 个功能未注册」，
     * 把「一项都没检查」显示成「没有漏检」——「未注册可见」就废了。
     */
    fun snapshot(): FeatureProbeReportDto =
        latest.takeIf { it.items.isNotEmpty() } ?: pendingReport()

    /**
     * 重新执行全部探针并更新快照。
     *
     * 单个探针失败/超时不会影响其它探针（由 [FeatureProbeRunner] 保证），
     * 因此这里不需要 try/catch 包每个探针。
     */
    suspend fun refresh(): FeatureProbeReportDto {
        val outcome = runner.runAll()
        val report = FeatureProbeReportBuilder.build(outcome, registry)
        lock.withLock { latest = report }
        return report
    }

    /** 丢弃缓存后重跑（显式「重新自检」）。 */
    suspend fun refreshForced(): FeatureProbeReportDto {
        runner.invalidate()
        return refresh()
    }

    /**
     * 在后台作用域里预热一次（服务图装配后调用）。
     *
     * 只是让首屏就有结论；失败也不影响任何业务。
     */
    fun warmUp(scope: CoroutineScope) {
        scope.launch {
            runCatching { refresh() }
        }
    }

    /**
     * 首轮还没跑完时的占位快照。
     *
     * 关键：**已注册数、未注册数与未注册清单不依赖探针执行**，这里必须立刻给对
     * （否则「M 个功能未注册」会显示成 0，正是「未注册可见」要避免的假象）。
     */
    fun pendingReport(): FeatureProbeReportDto = FeatureProbeReportBuilder.buildPending(registry)
}
