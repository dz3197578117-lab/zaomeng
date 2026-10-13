package top.wkbin.zaomeng.feature.settings

import top.wkbin.zaomeng.data.api.AppVersionInfoDto
import top.wkbin.zaomeng.data.api.DeviceInfoDto
import top.wkbin.zaomeng.data.api.EnvironmentInfoDto
import top.wkbin.zaomeng.data.api.ProcessExitInfoDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.data.api.SessionContextDto
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 运行日志导出文本必须把「排查必需的现场信息」带上。
 *
 * 此前导出只有日志正文与崩溃段：用户贴过来时既不知道装的是哪个版本，
 * 也不知道上次进程是怎么死的。这里把这些段钉死。
 */
class RuntimeLogFormattingTest {

    @Test
    fun `export text carries version device capture state and process exits`() {
        val snapshot = RuntimeLogDto(
            source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
            available = true,
            capturedAt = "2026-10-13T01:00:00Z",
            totalLines = 12,
            capture = RuntimeCaptureStateDto(supported = true, enabled = false, note = "采集已关闭"),
            environment = EnvironmentInfoDto(
                app = AppVersionInfoDto(versionName = "2.1.11", versionCode = 24),
                device = DeviceInfoDto(
                    manufacturer = "Xiaomi",
                    model = "14",
                    osName = "Android",
                    osRelease = "14",
                    apiLevel = 34,
                    abis = listOf("arm64-v8a"),
                    is64Bit = true,
                    cpuCount = 8,
                ),
                session = SessionContextDto(
                    runId = "run-1",
                    sessionId = "session-1",
                    reportedBy = "client-last-opened-session",
                ),
            ),
            lines = listOf("10-13 01:00:00.000 I Zaomeng: hello"),
            processExits = listOf(
                ProcessExitInfoDto(
                    occurredAt = "2026-10-13T00:50:00Z",
                    reason = "ANR",
                    reasonCode = 6,
                    status = 0,
                    source = "android-application-exit-info:ANR",
                    trace = "main thread blocked",
                ),
            ),
            processExitsNote = "来源：Android ApplicationExitInfo（系统侧记录）",
        )

        val text = formatRuntimeLog(snapshot)

        assertTrue(text.contains("应用版本：2.1.11（24）"), "导出必须带版本号：$text")
        assertTrue(text.contains("设备：Xiaomi 14"), "导出必须带机型：$text")
        assertTrue(text.contains("API 34"), "导出必须带 API level：$text")
        assertTrue(text.contains("采集状态：已停止（未采集）"), "导出必须写明采集是否在跑：$text")
        assertTrue(text.contains("现场会话：run=run-1 session=session-1"), "导出必须带现场会话：$text")
        assertTrue(text.contains("进程退出记录（1）"), "导出必须带进程退出段：$text")
        assertTrue(text.contains("ANR"), "导出必须带退出原因：$text")
        assertTrue(text.contains("10-13 01:00:00.000"), "日志正文仍必须在：$text")
    }

    @Test
    fun `empty snapshot still produces a readable header`() {
        val text = formatRuntimeLog(RuntimeLogDto())
        assertTrue(text.contains("# 造梦运行日志（已脱敏）"), "空快照也要有可读头部：$text")
        assertTrue(text.contains("进程退出记录（0）"), "没有退出记录时也要如实标注：$text")
    }
}
