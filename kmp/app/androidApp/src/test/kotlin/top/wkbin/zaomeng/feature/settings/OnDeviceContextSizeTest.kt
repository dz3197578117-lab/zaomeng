package top.wkbin.zaomeng.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 端侧上下文长度的夹取规则（界面、ViewModel、服务端必须给出同一个数）。
 *
 * 事故形状：输入框接受任意 5 位数字，只有服务端在 `buildServerArgs` 前静默 `coerceIn`。
 * 用户填 99999 → 实际按 32768 跑，输入框还显示 99999；出问题时无从判断「到底跑在多少」。
 * 现在界面提示、提交前的请求值、写回输入框的值都走这一个纯函数。
 */
class OnDeviceContextSizeTest {

    @Test
    fun `blank and non numeric mean auto`() {
        assertEquals(0, resolveOnDeviceContextSize(""))
        assertEquals(0, resolveOnDeviceContextSize("   "))
        assertEquals(0, resolveOnDeviceContextSize("abc"))
        // 0 / 负数都表示「用模型自带长度」，不是「给我 0 个 token」
        assertEquals(0, resolveOnDeviceContextSize("0"))
        assertEquals(0, resolveOnDeviceContextSize("-1"))
    }

    @Test
    fun `a value inside the range is kept as is`() {
        assertEquals(512, resolveOnDeviceContextSize("512"))
        assertEquals(4_096, resolveOnDeviceContextSize("4096"))
        assertEquals(16_384, resolveOnDeviceContextSize("16384"))
        assertEquals(32_768, resolveOnDeviceContextSize("32768"))
        // 输入框允许前后空格（键盘粘贴常见）
        assertEquals(8_192, resolveOnDeviceContextSize(" 8192 "))
    }

    @Test
    fun `values outside the range are clamped to what the server would use`() {
        assertEquals(MAX_ON_DEVICE_CONTEXT_SIZE, resolveOnDeviceContextSize("99999"))
        assertEquals(MIN_ON_DEVICE_CONTEXT_SIZE, resolveOnDeviceContextSize("1"))
        assertEquals(MIN_ON_DEVICE_CONTEXT_SIZE, resolveOnDeviceContextSize("511"))
        assertEquals(MAX_ON_DEVICE_CONTEXT_SIZE, resolveOnDeviceContextSize("32769"))
    }

    @Test
    fun `clamping is idempotent so the value written back to the field is stable`() {
        val once = resolveOnDeviceContextSize("99999")
        assertEquals(MAX_ON_DEVICE_CONTEXT_SIZE, once)
        assertEquals(
            once,
            resolveOnDeviceContextSize(once.toString()),
            "夹好的值写回输入框后再启动，不能又被夹成另一个数",
        )
    }
}
