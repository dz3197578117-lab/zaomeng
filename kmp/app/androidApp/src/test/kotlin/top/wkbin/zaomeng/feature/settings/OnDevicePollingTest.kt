package top.wkbin.zaomeng.feature.settings

import top.wkbin.zaomeng.data.api.OnDeviceDownloadDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端侧进度轮询的**停止条件**。
 *
 * 事故形状：轮询原来写成 `repeat(600)` + 1 秒间隔，等于「10 分钟后当它结束了」。
 * 一个 4 GB 的 GGUF 在手机上经常下得更久，于是第 10 分钟之后进度条与已下载字节数**冻结**
 * （服务端还在下），看起来像卡死；用户点「取消下载」会把半成品 `.part` 清掉，重下又从零开始。
 *
 * 现在只看状态本身：还在下载 / 还在加载就继续跟，谁都不许用「次数上限」冒充「任务结束」。
 */
class OnDevicePollingTest {

    private fun status(
        state: String = OnDeviceStatusDto.STATE_STOPPED,
        downloadActive: Boolean = false,
    ) = OnDeviceStatusDto(
        state = state,
        download = OnDeviceDownloadDto(active = downloadActive, fileName = "qwen.gguf"),
    )

    @Test
    fun `nothing to poll before the first status arrives`() {
        assertFalse(shouldKeepPolling(null))
    }

    @Test
    fun `a stopped endpoint without a download is not polled`() {
        assertFalse(shouldKeepPolling(status()))
        assertFalse(shouldKeepPolling(status(downloadActive = false)))
    }

    @Test
    fun `starting the model keeps polling`() {
        assertTrue(shouldKeepPolling(status(state = OnDeviceStatusDto.STATE_STARTING)))
    }

    @Test
    fun `an active download keeps polling`() {
        assertTrue(shouldKeepPolling(status(downloadActive = true)))
        // 下载与「加载模型」可以前后脚发生，两者都在也要继续
        assertTrue(
            shouldKeepPolling(
                status(state = OnDeviceStatusDto.STATE_RUNNING, downloadActive = true),
            ),
        )
    }

    @Test
    fun `polling stops as soon as the download is no longer active`() {
        // 服务端在取消/失败/完成时都会把 download.active 置回 false —— 这是唯一可靠的结束信号
        assertFalse(shouldKeepPolling(status(downloadActive = false)))
    }

    @Test
    fun `a running endpoint with no download and no start in flight is not polled`() {
        // 加载完成、也没在下载：没有任何进度需要刷，别让屏幕每秒白重组一次
        assertFalse(shouldKeepPolling(status(state = OnDeviceStatusDto.STATE_RUNNING)))
    }

    @Test
    fun `a failed start is not polled forever`() {
        assertFalse(shouldKeepPolling(status(state = OnDeviceStatusDto.STATE_FAILED)))
    }
}
