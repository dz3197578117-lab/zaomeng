package top.wkbin.zaomeng.ktor.features

import okio.Path.Companion.toPath
import kotlinx.coroutines.runBlocking
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.platform.PromptSource
import top.wkbin.zaomeng.ktor.services.PromptLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「关闭运行日志采集不能影响运行诊断」的回归。
 *
 * 这条要求有两个层次，本测试分别钉住：
 *
 * **1. 架构上不可能受影响（结构性保证）**
 * [FeatureProbeContext] **根本没有**采集状态（`RuntimeCaptureState`）与运行日志来源
 * （`RuntimeLogSource`）的入口——探针拿不到开关，也就无法因它改变结论。
 * 这不是「我们小心一点」，而是编译期就写不出来的代码。
 * 探针能读的事件缓冲是 `DiagnosticsEventLog`（`DiagnosticsSink` 写入的独立环形缓冲），
 * 与常规 logcat 落盘开关无关。
 *
 * **2. 没有样本 ≠ 出故障（行为保证）**
 * 在「空数据目录 + 空事件日志」这种最极端的「什么都没有」场景下，
 * 文件/事件驱动的探针必须给出 OK / WARN / NO_DATA，**一个 FAIL 都不许有**。
 * 若把「没数据」当成 ❌，用户会去修一个根本没坏的功能。
 *
 * 采集关闭时功能自检里各部分的可用性见 `docs/server-api.md` 的 2.1.3 一节
 * （结论：未注册计数、各探针 ✅/⚠️/❌、概览、启动自检、模型连通性、问题清单里的
 * 崩溃与结构化事件全部照常；只有确实需要日志样本的项降级为「—（无样本）」）。
 */
class FeatureProbeCaptureIndependenceTest {

    private object EmptyPromptSource : PromptSource {
        override fun read(relativePath: String): Pair<String, Long>? = null
        override fun lastModified(relativePath: String): Long? = null
    }

    private fun newContext(dir: okio.Path) = FeatureProbeContext(
        storage = StorageService(dir),
        promptLoader = PromptLoader(EmptyPromptSource),
        // 采集关闭时**没有**运行日志来源，但结构化事件缓冲依然存在——
        // 这正是「诊断不受采集开关影响」的关键：两者是不同的通道。
        eventLog = top.wkbin.zaomeng.ktor.services.DiagnosticsEventLog(dir / "diagnostics-events.jsonl"),
        connectivityLog = null,
        onDeviceSnapshot = { emptyMap() },
    )

    private fun tempDir(): okio.Path =
        kotlin.io.path.createTempDirectory("zaomeng-probe-capture").toString().toPath()

    @Test
    fun `empty data directory never produces FAIL from filesystem and event probes`() = runBlocking {
        val registry = FeatureProbeRegistry()
        registerBookshelfProbes(registry)
        registerChatProbes(registry)
        val context = newContext(tempDir())

        val outcome = FeatureProbeRunner(registry, context).runAll()

        assertTrue(outcome.entries.isNotEmpty(), "书卷/会话域探针应已注册")
        val failures = outcome.entries.filter { it.result.status == ProbeStatus.FAIL }
        assertTrue(
            failures.isEmpty(),
            "空目录 + 空事件日志下不允许有 FAIL（否则用户会去修没坏的功能）：" +
                failures.joinToString { "${it.probe.id}=${it.result.summary}" },
        )
        // 而且必须真的给出了结论，不能整片 FAIL 之后的「沉默」
        assertTrue(
            outcome.entries.all { it.result.summary.isNotBlank() },
            "每个探针都必须给出一句话结论",
        )
        assertEquals(0, outcome.failCount())
    }

    @Test
    fun `event driven probes report no-data instead of failure without samples`() = runBlocking {
        val registry = FeatureProbeRegistry()
        val probe = ChatGenerationProbe()
        registry.register(probe)
        val outcome = FeatureProbeRunner(registry, newContext(tempDir())).runAll()
        assertEquals(ProbeStatus.NO_DATA, outcome.entries.single().result.status)
    }

    @Test
    fun `probe context exposes no capture state so probes cannot depend on it`() {
        // 结构性保证：上下文里没有与运行日志采集开关相关的成员。
        // 如果将来有人把 RuntimeCaptureState / RuntimeLogSource 加进 FeatureProbeContext，
        // 这个测试会失败——提醒他「采集关闭时探针结论必须照常」这条硬性要求。
        val memberNames = FeatureProbeContext::class.members.map { it.name }.toSet()
        val forbidden = listOf("captureState", "runtimeLogSource", "runtimeCaptureState", "capture", "snapshot")
        val hits = forbidden.filter { it in memberNames }
        assertTrue(hits.isEmpty(), "FeatureProbeContext 不应暴露采集/日志来源能力，发现：$hits")
    }
}
