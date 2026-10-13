package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 诊断事件日志与诊断报告测试。
 *
 * 重点守护两件事：事件在重启后不丢、字段有界（不会把用户磁盘写爆）；
 * 以及诊断报告能被 JSON 往返——导出功能就是靠这个序列化，坏在这里等于功能不可用。
 */
class DiagnosticsEventLogTest {
    private val fs = FileSystem.SYSTEM

    private fun newLog(directory: Path, maxEntries: Int = 400): DiagnosticsEventLog =
        DiagnosticsEventLog(directory / "diagnostics-events.jsonl", fs, maxEntries)

    @Test
    fun `recent returns newest events in chronological order`() {
        val directory = createTempDirectory("zaomeng-diag-log").toString().toPath()
        val log = newLog(directory)

        log.record("dialogue.parse_failed", "第一次", mapOf("attempt" to "1"))
        log.record("dialogue.parse_failed", "第二次", mapOf("attempt" to "2"))

        val recent = log.recent(10)
        assertEquals(listOf("第一次", "第二次"), recent.map { it.message })
        assertEquals(listOf("1", "2"), recent.map { it.details["attempt"] })
        assertEquals(2, log.size())
    }

    @Test
    fun `buffer is capped and keeps the newest entries`() {
        val directory = createTempDirectory("zaomeng-diag-cap").toString().toPath()
        val log = newLog(directory, maxEntries = 5)

        repeat(12) { index -> log.record("evt", "m$index") }

        assertEquals(5, log.size())
        assertEquals(listOf("m7", "m8", "m9", "m10", "m11"), log.recent(10).map { it.message })
    }

    @Test
    fun `events survive a new instance and corrupt lines are skipped`() {
        val directory = createTempDirectory("zaomeng-diag-reload").toString().toPath()
        val file = directory / "diagnostics-events.jsonl"
        newLog(directory).apply {
            record("plugin.action", "第一次动作")
            record("plugin.action_failed", "动作失败")
        }

        // 模拟写入被中断/外部改写：追加一行损坏内容
        val existing = fs.read(file) { readUtf8() }
        fs.write(file) { writeUtf8(existing + "{not json\n") }

        val reloaded = newLog(directory)
        assertEquals(listOf("第一次动作", "动作失败"), reloaded.recent(10).map { it.message })
    }

    @Test
    fun `fields are truncated so one failure cannot bloat the log`() {
        val directory = createTempDirectory("zaomeng-diag-truncate").toString().toPath()
        val log = newLog(directory)

        log.record("dialogue.parse_failed", "m".repeat(2_000), mapOf("output_excerpt" to "y".repeat(2_000)))

        val event = log.recent(1).single()
        assertTrue(event.message.length <= 400, "message 应当被截断")
        assertTrue((event.details["output_excerpt"] ?: "").length <= 800, "detail 应当被截断")
    }

    @Test
    fun `parent directory is created on demand so events survive a restart`() {
        // 数据根目录在首次写入时可能还不存在；okio 不会自动建父目录，
        // 缺这一步会静默丢日志（导出正常、重启后历史全没——线上踩过一次）。
        val root = createTempDirectory("zaomeng-diag-mkdir").toString().toPath()
        val nested = root / "not-created-yet" / "deep"
        val log = DiagnosticsEventLog(nested / "diagnostics-events.jsonl", fs)
        log.record("backend.ready", "服务图已就绪")

        assertTrue(fs.exists(nested / "diagnostics-events.jsonl"), "事件文件应被自动创建（含父目录）")
        val reloaded = DiagnosticsEventLog(nested / "diagnostics-events.jsonl", fs)
        assertEquals(listOf("服务图已就绪"), reloaded.recent(10).map { it.message })
    }

    @Test
    fun `report carries recent events and on-device snapshot and stays serializable`() {
        val directory = createTempDirectory("zaomeng-diag-report").toString().toPath()
        val log = newLog(directory)
        log.record("ondevice.failed", "推理进程在模型加载阶段退出。", mapOf("log_tail" to "llama: loaded 0/1"))
        val storage = StorageService(directory)
        val service = DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            eventLog = log,
            onDeviceSnapshot = { mapOf("state" to "failed", "log_tail" to "llama: loaded 0/1") },
        )

        // 事件明细只在 full 档导出（2.1.13 起 compact 档只保留类别统计），
        // 本用例断言 recentEvents 内容，因此显式取 full 档。
        val report = service.buildDiagnosticsReport(tier = DIAGNOSTICS_TIER_FULL)
        // 当前值见 DiagnosticsService.buildDiagnosticsReport 里的 schemaVersion。
        // （本用例不注入日志来源，runtimeLog 段为「不可用」）
        assertEquals(6, report.schemaVersion)
        assertEquals(1, report.recentEvents.size)
        assertEquals("ondevice.failed", report.recentEvents.single().category)
        assertEquals("failed", report.onDevice["state"])
        assertEquals("llama: loaded 0/1", report.onDevice["log_tail"])

        // 导出就是走这个序列化：报告必须能 JSON 往返，坏在这里等于诊断功能不可用
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(DiagnosticsReport.serializer(), report)
        val decoded = json.decodeFromString(DiagnosticsReport.serializer(), encoded)
        assertEquals(report.recentEvents, decoded.recentEvents)
        assertEquals(report.onDevice, decoded.onDevice)
        assertTrue(encoded.contains("recentEvents"))
        assertTrue(encoded.contains("onDevice"))
    }

    @Test
    fun `report still builds when a section fails`() {
        val directory = createTempDirectory("zaomeng-diag-degrade").toString().toPath()
        val storage = StorageService(directory)
        val service = DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            eventLog = null,
            // 端侧快照抛异常时只能降级为空，绝不能让整个导出 500
            onDeviceSnapshot = { error("端侧快照不可用") },
        )

        val report = service.buildDiagnosticsReport()
        assertTrue(report.onDevice.isEmpty())
        assertTrue(report.recentEvents.isEmpty())
    }
}
