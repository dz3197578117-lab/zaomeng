package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分支会话的归档偏移归一。
 *
 * 真机回归背景：2.4.0 的自检探针在 `sess-7bc1ce5174d0` 上报过唯一一条 FAIL——
 * 分支会话 `transcript_start=101`（应为 0），读取时会把父会话的归档拼到前缀前面。
 */
class TranscriptBookkeepingTest {

    @Test
    fun `mainline session keeps its archive offset`() {
        val session = buildJsonObject {
            put("transcript_start", 101)
            put("branch_meta", buildJsonObject { put("is_mainline", true) })
        }
        assertEquals(101, effectiveTranscriptStart(session))
        assertFalse(isBranchSession(session))
    }

    @Test
    fun `branch session with branch_origin ignores inherited offset`() {
        val session = buildJsonObject {
            put("transcript_start", 101)
            put("branch_origin", "session-parent")
        }
        assertEquals(0, effectiveTranscriptStart(session))
        assertTrue(isBranchSession(session))
    }

    @Test
    fun `branch session marked by meta ignores inherited offset`() {
        val session = buildJsonObject {
            put("transcript_start", 42)
            put("branch_meta", buildJsonObject { put("is_mainline", false) })
        }
        assertEquals(0, effectiveTranscriptStart(session))
        assertTrue(isBranchSession(session))
    }

    @Test
    fun `missing or negative offset reads as zero`() {
        assertEquals(0, effectiveTranscriptStart(buildJsonObject { }))
        assertEquals(
            0,
            effectiveTranscriptStart(buildJsonObject { put("transcript_start", -5) }),
        )
    }

    @Test
    fun `branch marker wins over a positive offset even without meta`() {
        val session = buildJsonObject {
            put("transcript_start", 7)
            put("branch_origin", "x")
            put("transcript_count", 12)
        }
        assertEquals(0, effectiveTranscriptStart(session))
    }
}
