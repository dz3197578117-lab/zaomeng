package top.wkbin.zaomeng.ktor.features

import okio.Path.Companion.toPath
import kotlinx.coroutines.runBlocking
import top.wkbin.zaomeng.data.api.KnownFeatures
import top.wkbin.zaomeng.ktor.services.DiagnosticsEventLog
import top.wkbin.zaomeng.ktor.services.PromptLoader
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.platform.PromptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 功能自检注册表 / 执行器回归。
 *
 * 钉住四件在真实事故里最容易退化的事：
 * 1. 注册、枚举、**去重**（注册重了不能让整页诊断崩，但必须能被看出来）；
 * 2. **单个探针抛异常不影响其它探针**（诊断自己是诊断，不能整体挂掉）；
 * 3. **未注册计算正确**（它就是「漏了什么」的唯一信号，算错等于没做）；
 * 4. 缓存命中时不重跑（探针必须廉价）。
 */
class FeatureProbeRegistryTest {

    private class FakeProbe(
        override val id: String,
        override val title: String = "假探针",
        override val domain: String = KnownFeatureDomain.BOOKS,
        override val features: List<String> = listOf("bookshelf.distill"),
        private val block: suspend () -> ProbeResult = { ProbeResult.ok("ok") },
    ) : FeatureProbe {
        var runs: Int = 0
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            runs++
            return block()
        }
    }

    private object KnownFeatureDomain {
        const val BOOKS = "书卷与整理"
    }

    /**
     * 本测试不读提示词，但 [FeatureProbeContext] 需要**真实存在**的 [PromptLoader]。
     *
     * 这里曾经写 `promptLoader = error("本测试不读提示词")`：`error()` 在构造 context 时
     * 就被求值，于是所有用到 [newContext] 的用例一开头就抛异常，而不是「用到提示词时才抛」。
     * 空实现比抛异常更贴合真实运行环境（读不到就是 `null`），见
     * `FeatureProbeCaptureIndependenceTest` 的同名做法。
     */
    private object EmptyPromptSource : PromptSource {
        override fun read(relativePath: String): Pair<String, Long>? = null
        override fun lastModified(relativePath: String): Long? = null
    }

    private fun newContext(dir: okio.Path): FeatureProbeContext {
        val storage = StorageService(dir)
        return FeatureProbeContext(
            storage = storage,
            promptLoader = PromptLoader(EmptyPromptSource),
            eventLog = DiagnosticsEventLog(dir / "events.jsonl"),
            connectivityLog = null,
        )
    }

    @Test
    fun `registers enumerates and deduplicates by id`() {
        val registry = FeatureProbeRegistry()
        val first = FakeProbe("probe.a")
        val second = FakeProbe("probe.a")

        assertTrue(registry.register(first))
        assertFalse(registry.register(second), "同 id 第二次注册必须被忽略")
        assertEquals(1, registry.size())
        assertEquals(listOf("probe.a"), registry.all().map { it.id })
        assertEquals(listOf("probe.a"), registry.duplicateIds().toList())
    }

    @Test
    fun `unregistered features are known features minus probe coverage`() {
        val registry = FeatureProbeRegistry()
        registry.register(FakeProbe("probe.a", features = listOf("bookshelf.distill")))
        registry.register(FakeProbe("probe.b", features = listOf("chat.generate", "chat.timeline")))

        val unregistered = registry.unregisteredFeatures().map { it.id }
        assertEquals(KnownFeatures.all.size - 3, unregistered.size)
        assertFalse("bookshelf.distill" in unregistered)
        assertFalse("chat.generate" in unregistered)
        assertTrue("bookshelf.import" in unregistered)
        // 保持清单声明顺序
        assertEquals(KnownFeatures.all.map { it.id }.filter { it in unregistered }, unregistered)
    }

    @Test
    fun `unknown feature refs are reported instead of silently counted`() {
        val registry = FeatureProbeRegistry()
        registry.register(FakeProbe("probe.a", features = listOf("bookshelf.distill", "chat.not-a-feature")))
        assertEquals(listOf("chat.not-a-feature"), registry.unknownFeatureRefs())
        // 未知 id 会被单独报出（上一行），但它不影响已知功能的覆盖统计：
        // computeUnregisteredFeatures 是纯集合差（known - covered），
        // 而探针确实声明了 bookshelf.distill，所以它算已覆盖、不该出现在未注册里。
        assertFalse(registry.unregisteredFeatures().any { it.id == "bookshelf.distill" })
    }

    @Test
    fun `a throwing probe does not affect other probes and becomes FAIL`() = runBlocking {
        val registry = FeatureProbeRegistry()
        val boom = FakeProbe("probe.boom") { error("探针内部炸了") }
        val fine = FakeProbe("probe.fine") { ProbeResult.ok("正常") }
        registry.registerAll(boom, fine)

        val outcome = FeatureProbeRunner(registry, newContext(tempDir())).runAll()
        assertEquals(2, outcome.entries.size)
        assertEquals(1, outcome.failCount())
        val boomEntry = outcome.entries.first { it.probe.id == "probe.boom" }
        assertEquals(ProbeStatus.FAIL, boomEntry.result.status)
        assertTrue(boomEntry.result.summary.contains("IllegalStateException"), boomEntry.result.summary)
        val fineEntry = outcome.entries.first { it.probe.id == "probe.fine" }
        assertEquals(ProbeStatus.OK, fineEntry.result.status)
    }

    @Test
    fun `cached results are not re-executed within ttl`() = runBlocking {
        val registry = FeatureProbeRegistry()
        val probe = FakeProbe("probe.cached")
        registry.register(probe)
        val runner = FeatureProbeRunner(registry, newContext(tempDir()), ttlMillis = 60_000)

        val first = runner.runAll()
        assertFalse(first.entries.single().fromCache)
        val second = runner.runAll()
        assertTrue(second.entries.single().fromCache, "TTL 内应命中缓存")
        assertEquals(1, probe.runs, "缓存命中时不得重新执行探针")
    }

    @Test
    fun `runner never throws even when every probe fails`() = runBlocking {
        val registry = FeatureProbeRegistry()
        registry.registerAll(
            FakeProbe("probe.a") { error("a") },
            FakeProbe("probe.b") { error("b") },
        )
        val outcome = FeatureProbeRunner(registry, newContext(tempDir())).runAll()
        assertEquals(2, outcome.failCount())
    }

    @Test
    fun `report builder exposes unregistered and counts`() = runBlocking {
        val registry = FeatureProbeRegistry()
        registry.registerAll(
            FakeProbe("probe.a", features = listOf("bookshelf.distill")) { ProbeResult.ok("ok") },
            FakeProbe("probe.b", features = listOf("chat.generate")) { ProbeResult.fail("坏了") },
        )
        val outcome = FeatureProbeRunner(registry, newContext(tempDir())).runAll()
        val report = FeatureProbeReportBuilder.build(outcome, registry)

        assertEquals(2, report.registeredCount)
        assertEquals(1, report.items.count { it.status == "FAIL" })
        assertEquals(1, report.okCount)
        assertEquals(KnownFeatures.all.size - 2, report.unregisteredCount)
        assertEquals(KnownFeatures.all.size, report.knownFeatureCount)
        assertEquals(2, report.coveredFeatureCount)
        assertTrue(report.unregisteredFeatures.any { it.id == "bookshelf.import" })
    }

    @Test
    fun `compute helpers are pure and order stable`() {
        val known = KnownFeatures.all
        val covered = listOf("chat.generate", "chat.generate", " chat.timeline ")
        val unregistered = top.wkbin.zaomeng.data.api.computeUnregisteredFeatures(covered, known)
        assertEquals(known.size - 2, unregistered.size)
        assertEquals(
            known.filter { it.id !in setOf("chat.generate", "chat.timeline") }.map { it.id },
            unregistered.map { it.id },
        )
        assertEquals(
            listOf("nope.one"),
            top.wkbin.zaomeng.data.api.computeUnknownFeatureRefs(listOf("nope.one", "nope.one", "chat.generate"), known),
        )
    }

    private fun tempDir(): okio.Path =
        kotlin.io.path.createTempDirectory("zaomeng-probe-registry").toString().toPath()
}
