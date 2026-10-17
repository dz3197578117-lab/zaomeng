package top.wkbin.zaomeng.feature.settings

import top.wkbin.zaomeng.platform.NeuralVoiceCatalog
import top.wkbin.zaomeng.data.api.VoiceModelDownloadDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 语音区块界面状态的回归测试。
 *
 * 重点在两件会误导用户的事：
 * ① 体积显示——「要下 119 MB」必须在下单**之前**就看得见，不能等点下去才知道；
 * ② 就绪判定——开关打开但模型没下完时，界面必须能说出「现在其实用的是系统朗读」。
 */
class VoiceModelUiStateTest {

    @Test
    fun `selected model defaults to the catalog recommendation`() {
        val state = VoiceModelUiState()
        assertEquals(NeuralVoiceCatalog.defaultModel.id, state.selectedModelId)
        assertEquals(NeuralVoiceCatalog.defaultModel, state.selectedModel)
    }

    @Test
    fun `an unknown stored model id falls back instead of breaking the page`() {
        // 旧版本存下的 id 可能已经下线；回落而不是抛异常，否则设置页直接打不开
        val state = VoiceModelUiState(selectedModelId = "已下线的旧模型")
        assertEquals(NeuralVoiceCatalog.defaultModel, state.selectedModel)
    }

    @Test
    fun `readiness follows the downloaded model set`() {
        val id = NeuralVoiceCatalog.defaultModel.id
        assertFalse(VoiceModelUiState(selectedModelId = id).selectedReady)
        assertTrue(VoiceModelUiState(selectedModelId = id, readyModelIds = setOf(id)).selectedReady)
    }

    @Test
    fun `selected file size mismatch produces a visible actionable warning`() {
        val state = VoiceModelUiState(
            selectedModelId = NeuralVoiceCatalog.THERESA.id,
            mismatchedFilesByModel = mapOf(NeuralVoiceCatalog.THERESA.id to listOf("lexicon.txt", "date.fst")),
        )

        val warning = state.selectedFileSizeWarning
        assertTrue(warning.orEmpty().contains("lexicon.txt"), warning.orEmpty())
        assertTrue(warning.orEmpty().contains("date.fst"), warning.orEmpty())
        assertTrue(warning.orEmpty().contains("重新下载"), warning.orEmpty())
    }

    @Test
    fun `download activity is read from the download dto`() {
        assertFalse(VoiceModelUiState().isDownloading)
        assertTrue(VoiceModelUiState(download = VoiceModelDownloadDto(active = true)).isDownloading)
    }

    @Test
    fun `download endpoint resolves by id and falls back to the default`() {
        val state = VoiceModelUiState()
        assertEquals(OnDeviceModelPresets.defaultEndpointId, state.endpoint.id)
        val mirrored = VoiceModelUiState(downloadEndpointId = "hf-mirror")
        assertEquals("hf-mirror", mirrored.endpoint.id)
        assertTrue(mirrored.endpoint.baseUrl.contains("hf-mirror"))
        // 未知 id 回落到默认源，而不是崩在这
        assertEquals(OnDeviceModelPresets.defaultEndpointId, VoiceModelUiState(downloadEndpointId = "???").endpoint.id)
    }

    @Test
    fun `voice download tries the mirror first and keeps the official site as fallback`() {
        val candidates = voiceDownloadEndpoints()
        assertTrue(candidates.isNotEmpty(), "至少要有一个下载源")
        assertTrue(
            candidates.first().baseUrl.contains("hf-mirror.com"),
            "镜像应排在第一位（官方站在国内会先卡掉一次连接超时才失败）：${candidates.map { it.label }}",
        )
        assertTrue(
            candidates.any { it.baseUrl.contains("huggingface.co") },
            "官方源要保留为兜底：${candidates.map { it.label }}",
        )
        assertEquals(
            candidates.size,
            candidates.map { it.id }.toSet().size,
            "下载源不该重复：${candidates.map { it.id }}",
        )
        assertEquals(OnDeviceModelPresets.endpoints.size, candidates.size, "不该漏掉任何已配置的源")
    }

    @Test
    fun `megabytes formatting shows the real size before downloading`() {
        // 默认模型的实测体积是 124,912,490 B ≈ 119.1 MB
        val text = formatMegabytes(NeuralVoiceCatalog.defaultModel.totalBytes)
        assertTrue(text.contains("119"), "体积应显示为 119 左右，实际 $text")
        assertTrue(text.endsWith(" MB"), text)
    }

    @Test
    fun `megabytes formatting never claims a bogus size`() {
        assertEquals("未知大小", formatMegabytes(0))
        assertEquals("未知大小", formatMegabytes(-1))
        // 小体积也要如实显示，不能四舍五入成 0 MB 让人以为不用下
        assertTrue(formatMegabytes(512 * 1024).startsWith("0.5"), formatMegabytes(512 * 1024))
    }

    @Test
    fun `every catalog model reports a plausible download size`() {
        for (model in NeuralVoiceCatalog.models) {
            val text = formatMegabytes(model.totalBytes)
            assertTrue(text.contains("11"), "${model.id} 的显示体积应在 110+ MB 量级，实际 $text")
        }
    }
}
