package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 进程退出原因映射回归。
 *
 * 直接对应实机 2.1.12 诊断暴露的两个真实缺陷：
 * 1. `reason=16`（`REASON_PACKAGE_UPDATED`，应用被覆盖安装）此前显示成 `UNKNOWN(16)`，
 *    一次正常的升级看起来像故障；
 * 2. 真正有用的信息在系统 `description` 里（`o-stop(40)` = OEM 后台清理），
 *    却只被当成附注，用户只看到 `OTHER`。
 *
 * 另外钉住「严重度区分」：被系统/OEM 清理、被更新、用户主动退出**不是崩溃**。
 */
class ExitReasonMappingTest {

    @Test
    fun `package updated is mapped and not a crash`() {
        assertEquals("PACKAGE_UPDATED", exitReasonName(16))
        assertTrue(isKnownExitReason(16))
        val info = classifyExitReason(reasonCode = 16, description = "stop top.wkbin.zaomeng due to killDueToPackageUpdate")
        assertEquals("应用更新（覆盖安装）", info.reasonLabel)
        assertEquals(ExitSeverity.INFO, info.severity)
        assertEquals(ExitProblemKind.INTENTIONAL_EXIT, info.problemKind)
        assertFalse(info.isCrash, "覆盖安装不是崩溃")
        assertEquals("应用被更新（覆盖安装）", info.attribution)
        assertEquals(ExitAttributionSource.DESCRIPTION, info.attributionSource)
    }

    @Test
    fun `freezer and package state change are mapped`() {
        assertEquals("FREEZER", exitReasonName(14))
        assertEquals("PACKAGE_STATE_CHANGE", exitReasonName(15))
        assertEquals("INFO", classifyExitReason(14, "").severity.name)
        assertEquals("WARN", classifyExitReason(15, "").severity.name)
    }

    @Test
    fun `oem o-stop description beats generic OTHER reason`() {
        // realme/OPPO 的 o-stop(40) 落在 reason=13（OTHER），不是 12（DEPENDENCY_DIED）——
        // 见 ExitReasonMapping 里 EXIT_DESCRIPTION_ATTRIBUTIONS 的 KDoc。
        val info = classifyExitReason(reasonCode = 13, description = "[UNKNOWN] stop top.wkbin.zaomeng.test due to o-stop(40)")
        assertEquals("OTHER", info.reasonName)
        assertEquals("系统/OEM 后台清理", info.attribution)
        assertEquals(ExitAttributionSource.DESCRIPTION, info.attributionSource)
        assertEquals(ExitSeverity.INFO, info.severity)
        assertFalse(info.isCrash, "OEM 后台清理不是崩溃")
    }

    @Test
    fun `unknown reason code falls back to UNKNOWN with the raw code and is flagged unmapped`() {
        val info = classifyExitReason(reasonCode = 99, description = "")
        assertEquals("UNKNOWN(99)", info.reasonName)
        assertFalse(info.knownReason)
        assertTrue(info.reasonLabel.contains("99"))
        assertEquals(listOf(99), unmappedExitReasonCodes(listOf(99, 4, 16)))
    }

    @Test
    fun `existing reason codes keep their semantics`() {
        assertEquals("EXIT_SELF", exitReasonName(1))
        assertEquals("SIGNALED", exitReasonName(2))
        assertEquals("LOW_MEMORY", exitReasonName(3))
        assertEquals("CRASH", exitReasonName(4))
        assertEquals("CRASH_NATIVE", exitReasonName(5))
        assertEquals("ANR", exitReasonName(6))
        assertEquals("INITIALIZATION_FAILURE", exitReasonName(7))
        // 码值必须与 Android ApplicationExitInfo 的 REASON_* 常量一致。
        // 这里曾经把 8/9/10 整体前移一位（8 当资源占用、9 当用户请求、11 当权限变更、
        // 12 当 OTHER），正好是 ExitReasonMapping 的 KDoc 明确警告过的那个错位——
        // 那会让「权限变更」在界面上显示成「资源占用过高」。
        assertEquals("PERMISSION_CHANGE", exitReasonName(8))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", exitReasonName(9))
        assertEquals("USER_REQUESTED", exitReasonName(10))
        assertEquals("USER_STOPPED", exitReasonName(11))
        assertEquals("DEPENDENCY_DIED", exitReasonName(12))
        assertEquals("OTHER", exitReasonName(13))
    }

    @Test
    fun `crash reasons stay crashes even when description looks benign`() {
        val info = classifyExitReason(reasonCode = 4, description = "o-stop(40)")
        assertEquals(ExitSeverity.FATAL, info.severity)
        assertTrue(info.isCrash, "明确的 Java 崩溃不允许被 description 降级")
        assertEquals(ExitProblemKind.CRASH, info.problemKind)
    }

    @Test
    fun `anr is an error and counts as a crash`() {
        val info = classifyExitReason(reasonCode = 6, description = "")
        assertEquals(ExitSeverity.ERROR, info.severity)
        assertTrue(info.isCrash)
        assertEquals(ExitProblemKind.ANR, info.problemKind)
    }

    @Test
    fun `low memory is a warning and does not count as a crash`() {
        val info = classifyExitReason(reasonCode = 3, description = "killed due to low memory")
        assertEquals(ExitSeverity.WARN, info.severity)
        assertFalse(info.isCrash)
    }

    @Test
    fun `mapped codes are listed for the diagnostics report`() {
        val codes = mappedExitReasonCodes()
        assertTrue(13 in codes, "必须覆盖用户列出的 13")
        assertTrue(14 in codes)
        assertTrue(15 in codes)
        assertTrue(16 in codes)
        assertTrue(codes == codes.sorted())
        assertTrue(mappedExitReasonLines().any { it.startsWith("16 PACKAGE_UPDATED") })
    }

    @Test
    fun `severity wire names match diagnostics problem severities`() {
        assertEquals("fatal", ExitSeverity.FATAL.wireName())
        assertEquals("error", ExitSeverity.ERROR.wireName())
        assertEquals("warning", ExitSeverity.WARN.wireName())
        assertEquals("info", ExitSeverity.INFO.wireName())
    }
}
