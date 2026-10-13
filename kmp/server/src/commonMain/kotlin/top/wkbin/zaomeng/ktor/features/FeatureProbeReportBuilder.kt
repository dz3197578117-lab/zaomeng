package top.wkbin.zaomeng.ktor.features

import top.wkbin.zaomeng.data.api.FeatureProbeDto
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.data.api.FeatureProbeStatuses
import top.wkbin.zaomeng.data.api.KnownFeature
import top.wkbin.zaomeng.data.api.KnownFeatures
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 把「探针执行结果」转成网络 DTO（`DiagnosticsOverviewDto.featureProbes`）。
 *
 * 转换放在这里而不是直接序列化服务端类型，是为了让 `core/contracts` 保持对服务端零依赖：
 * 客户端只需要 DTO，服务端内部要 [ProbeStatus] 这种更强的类型。
 */
object FeatureProbeReportBuilder {

    /** 探针状态 → 展示字符串（OK/WARN/FAIL/NO_DATA/SKIPPED）。 */
    fun wireStatus(status: ProbeStatus): String = when (status) {
        ProbeStatus.OK -> FeatureProbeStatuses.OK
        ProbeStatus.WARN -> FeatureProbeStatuses.WARN
        ProbeStatus.FAIL -> FeatureProbeStatuses.FAIL
        ProbeStatus.NO_DATA -> FeatureProbeStatuses.NO_DATA
        ProbeStatus.SKIPPED -> FeatureProbeStatuses.SKIPPED
    }

    /**
     * 「注册 / 未注册」这一半的统计：**只依赖注册表与 `KnownFeatures` 清单，不依赖任何探针执行**。
     *
     * 单独抽出来的原因：探针首轮跑完之前（服务图刚装配、用户还没点「重新自检」时），
     * 界面与导出也必须给出正确的「已注册 N 项 / M 个功能未注册」——
     * 这正是「未注册可见」的核心价值。若这里返回 0，用户会以为所有功能都被检查过了，
     * 而实际是「一项都还没检查」。未注册清单来自「清单 - 探针覆盖」的差集，
     * 计算不需要读任何业务数据，因此永远给得起。
     */
    fun baseStats(registry: FeatureProbeRegistry, generatedAt: String): FeatureProbeReportDto {
        val unregistered = registry.unregisteredFeatures()
        val covered = registry.coveredFeatureIds().toSet()
        return FeatureProbeReportDto(
            generatedAt = generatedAt,
            registeredCount = registry.size(),
            knownFeatureCount = KnownFeatures.all.size,
            coveredFeatureCount = KnownFeatures.all.count { it.id in covered },
            unregisteredCount = unregistered.size,
            unregisteredFeatures = unregistered,
            multiProbeFeatures = registry.multiProbeFeatures(),
            unknownFeatureRefs = registry.unknownFeatureRefs(),
        )
    }

    /** 首轮探针尚未执行时的报告：统计齐备、`items` 为空（界面显示「检查中/未检查」）。 */
    fun buildPending(registry: FeatureProbeRegistry): FeatureProbeReportDto =
        baseStats(registry, nowIsoString())

    fun build(
        outcome: ProbeRunOutcome,
        registry: FeatureProbeRegistry,
    ): FeatureProbeReportDto = baseStats(registry, nowIsoString()).copy(
        items = outcome.entries.map { it.toDto() },
        okCount = outcome.countOf(ProbeStatus.OK),
        warnCount = outcome.countOf(ProbeStatus.WARN),
        failCount = outcome.countOf(ProbeStatus.FAIL),
        // failCount 字段与 items 的 FAIL 条数同源，保证「计数」与「列表」永远一致
        noDataCount = outcome.countOf(ProbeStatus.NO_DATA),
        skippedCount = outcome.countOf(ProbeStatus.SKIPPED),
        durationMs = outcome.durationMillis,
        cachedCount = outcome.cachedCount,
    )

    private fun FeatureProbeEntry.toDto(): FeatureProbeDto = FeatureProbeDto(
        id = probe.id,
        title = probe.title,
        domain = probe.domain,
        status = wireStatus(result.status),
        summary = result.summary,
        evidence = result.evidence.render(),
        details = result.details,
        features = probe.features,
        checkedAt = checkedAt,
        durationMs = durationMs,
        error = if (result.status == ProbeStatus.FAIL) result.summary else "",
    )

    /** 未注册功能的展示分组：按域，保持清单声明顺序。 */
    fun groupUnregistered(features: List<KnownFeature>): List<Pair<String, List<KnownFeature>>> =
        KnownFeature.DOMAINS.map { domain -> domain to features.filter { it.domain == domain } }
            .filter { it.second.isNotEmpty() }
}
