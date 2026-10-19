package top.wkbin.zaomeng.feature.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.ReusableCardKind

class CardRequiredFieldsTest {
    @Test
    fun `self card without a name is rejected`() {
        val missing = missingRequiredFields(
            ReusableCardKind.Self,
            mapOf("core_identity" to "府里的远房客人"),
        )

        assertTrue(missing.any { it.key == "display_name" })
    }

    @Test
    fun `self card with every required field filled passes`() {
        val filled = definitions(ReusableCardKind.Self).associate { it.key to "填好了" }

        assertTrue(missingRequiredFields(ReusableCardKind.Self, filled).isEmpty())
    }

    @Test
    fun `blank values count as missing`() {
        val blanks = definitions(ReusableCardKind.Opening).associate { it.key to "   " }
        val expected = definitions(ReusableCardKind.Opening).count { it.required }

        assertEquals(expected, missingRequiredFields(ReusableCardKind.Opening, blanks).size)
    }

    @Test
    fun `opening preset requires its participants field`() {
        val missing = missingRequiredFields(
            ReusableCardKind.Opening,
            mapOf("title" to "月下对谈", "mode" to "observe"),
        )

        assertEquals(listOf("participants"), missing.map { it.key })
    }

    @Test
    fun `message names the missing fields`() {
        val missing = missingRequiredFields(ReusableCardKind.Scene, emptyMap())

        val message = missingRequiredFieldsMessage(missing)

        assertTrue(message.contains("场景名"), message)
        assertTrue(message.contains("地点"), message)
    }
}
