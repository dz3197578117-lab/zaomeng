package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.CrashRecordDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.RuntimeLogSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断报告必须内嵌「整个应用的运行日志」，且日志与崩溃都要先脱敏。
 *
 * 这条链路上任何一环退化（来源没接上、脱敏没跑、JSON 序列化坏掉），
 * 用户拿到的就是一份「看起来有日志、实际不能用或会泄密」的报告。
 */
class RuntimeLogDiagnosticsTest {

    private class FakeRuntimeLogSource(
        private val snapshot: RuntimeLogDto,
    ) : RuntimeLogSource {
        override val source: String = snapshot.source

        override fun snapshot(maxLines: Int): RuntimeLogDto = snapshot.copy(lines = snapshot.lines.takeLast(maxLines))
    }

    private fun newDirectory(): Path = createTempDirectory("zaomeng-runtime-log").toString().toPath()

    private fun serviceWith(source: RuntimeLogSource?): DiagnosticsService {
        val directory = newDirectory()
        return DiagnosticsService(
            storageRoot = directory,
            storageService = StorageService(directory),
            runtimeLogSource = source,
        )
    }

    @Test
    fun `report embeds the runtime log and bumps schema version`() {
        val source = FakeRuntimeLogSource(
            RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
                available = true,
                capturedAt = "2026-10-12T20:00:00+08:00",
                totalLines = 42,
                lines = listOf(
                    "10-12 20:00:00.000  1234  1234 I Zaomeng.Dialogue: 对话生成完成",
                    "10-12 20:00:01.000  1234  1234 E Zaomeng.Llm: failed api_key=abcdef123456",
                ),
            ),
        )

        // 运行日志明细只在 full 档导出（2.1.13 起 compact 档刻意不带明细），
        // 本用例断言的正是明细内容，所以必须显式取 full 档。
        val report = serviceWith(source).buildDiagnosticsReport(tier = DIAGNOSTICS_TIER_FULL)

        // 当前值见 DiagnosticsService.buildDiagnosticsReport 里的 schemaVersion；
        // 每次上调版本都应是一次有意识的修改，所以这里断言确切值而不是「>= 4」。
        assertEquals(6, report.schemaVersion, "schemaVersion 6 为当前导出契约版本")
        assertEquals(RuntimeLogDto.SOURCE_LOGCAT_FILE, report.runtimeLog.source)
        assertTrue(report.runtimeLog.available)
        assertEquals(42L, report.runtimeLog.totalLines)
        assertEquals(2, report.runtimeLog.lines.size)
        assertFalse(
            report.runtimeLog.lines.any { it.contains("abcdef123456") },
            "报告里的日志必须已脱敏：${report.runtimeLog.lines}",
        )
        assertTrue(
            report.runtimeLog.lines.any { it.contains("对话生成完成") },
            "正常日志内容应保留",
        )
    }

    @Test
    fun `report survives json round trip with runtime log`() {
        val source = FakeRuntimeLogSource(
            RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
                available = true,
                capturedAt = "2026-10-12T20:00:00+08:00",
                totalLines = 7,
                truncated = true,
                note = "仅保留尾部",
                lines = listOf("line-1", "line-2"),
                crashes = listOf(
                    CrashRecordDto(
                        occurredAt = "2026-10-12T19:59:59+08:00",
                        thread = "main",
                        summary = "NullPointerException: boom",
                        stackTrace = "at A.b(A.kt:1)\n\tat C.d(C.kt:2)",
                    ),
                ),
            ),
        )

        // 运行日志明细只在 full 档导出（2.1.13 起 compact 档刻意不带明细），
        // 本用例断言的正是明细内容，所以必须显式取 full 档。
        val report = serviceWith(source).buildDiagnosticsReport(tier = DIAGNOSTICS_TIER_FULL)
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(top.wkbin.zaomeng.ktor.services.DiagnosticsReport.serializer(), report)
        val decoded = json.decodeFromString(top.wkbin.zaomeng.ktor.services.DiagnosticsReport.serializer(), encoded)

        assertEquals(report.runtimeLog, decoded.runtimeLog, "运行日志段必须能 JSON 往返")
        assertEquals(1, decoded.runtimeLog.crashes.size)
        assertEquals("main", decoded.runtimeLog.crashes.single().thread)
    }

    @Test
    fun `missing source degrades to unavailable instead of failing the export`() {
        val report = serviceWith(null).buildDiagnosticsReport()

        assertFalse(report.runtimeLog.available, "没有采集器时应标记为不可用")
        assertTrue(report.runtimeLog.note.isNotBlank(), "不可用必须给出说明：${report.runtimeLog.note}")
        assertTrue(report.runtimeLog.lines.isEmpty())
    }

    @Test
    fun `standalone runtime log snapshot is redacted and bounded`() {
        val source = FakeRuntimeLogSource(
            RuntimeLogDto(
                source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
                available = true,
                lines = (1..50).map { "line-$it token=abcdef123456" },
            ),
        )
        val service = serviceWith(source)

        val snapshot = service.buildRuntimeLogSnapshot(maxLines = 10)
        assertEquals(10, snapshot.lines.size, "行数应按请求夹紧")
        assertFalse(snapshot.lines.any { it.contains("abcdef123456") }, "单独拉取也必须脱敏")
    }
}
