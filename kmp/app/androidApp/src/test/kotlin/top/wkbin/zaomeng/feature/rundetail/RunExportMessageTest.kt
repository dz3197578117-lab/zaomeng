package top.wkbin.zaomeng.feature.rundetail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunExportMessageTest {
    @Test
    fun `message with dialogue says it is included`() {
        val message = exportSuccessMessage(includesDialogue = true)

        assertTrue(message.contains("含会话记录"), message)
        assertTrue(message.contains("转发前请留意"), message)
    }

    @Test
    fun `message without dialogue says it is excluded`() {
        val message = exportSuccessMessage(includesDialogue = false)

        assertTrue(message.contains("不含聊天记录"), message)
        assertFalse(message.contains("含会话记录"), message)
        assertFalse(message.contains("转发前请留意"), message)
    }
}
