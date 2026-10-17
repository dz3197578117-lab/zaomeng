package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 引擎选择与降级的回归测试。
 *
 * 这里最要紧的不是「选对了引擎」，而是**降级必须给出原因**：
 * 用户选了神经引擎却没下载模型时，如果静默改用系统 TTS，他会以为
 * 「所谓不同音色就是这样」——而他听到的一直是系统朗读，功能等于没做。
 */
class SpeechEngineSelectionTest {

    private val model = NeuralVoiceCatalog.THERESA
    private val dir = "/data/user/0/top.wkbin.zaomeng/files/zaomeng-ondevice/tts/${model.id}"
    private val allFiles = model.files.map { it.fileName }

    @Test
    fun `system engine is used as requested without any checks`() {
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(engine = SpeechEngine.SYSTEM),
            catalogModel = null,
            existingFileNames = emptyList(),
        )
        assertEquals(SpeechEngine.SYSTEM, decision.engine)
        assertNull(decision.fallbackReason)
        assertEquals(false, decision.isFallback)
    }

    @Test
    fun `neural engine is used when the model is complete on disk`() {
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, model.id, dir),
            catalogModel = model,
            existingFileNames = allFiles,
        )
        assertEquals(SpeechEngine.NEURAL, decision.engine)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun `unknown model id falls back with an explanation`() {
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, "已下线的旧模型", dir),
            catalogModel = null,
            existingFileNames = allFiles,
        )
        assertEquals(SpeechEngine.SYSTEM, decision.engine)
        assertNotNull(decision.fallbackReason)
        assertTrue(decision.fallbackReason!!.contains("已不在可用目录"), decision.fallbackReason!!)
    }

    @Test
    fun `missing model directory falls back and names the model`() {
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, model.id, ""),
            catalogModel = model,
            existingFileNames = emptyList(),
        )
        assertEquals(SpeechEngine.SYSTEM, decision.engine)
        assertTrue(decision.fallbackReason!!.contains(model.displayName), decision.fallbackReason!!)
        assertTrue(decision.fallbackReason!!.contains("还没有下载"), decision.fallbackReason!!)
    }

    @Test
    fun `missing required model files fall back and identify the missing files`() {
        val existing = allFiles - listOf(model.modelFileName, model.lexiconFileName)
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, model.id, dir),
            catalogModel = model,
            existingFileNames = existing,
        )
        assertEquals(SpeechEngine.SYSTEM, decision.engine)
        val reason = decision.fallbackReason!!
        assertTrue(reason.contains("不完整"), reason)
        assertTrue(reason.contains(model.modelFileName), reason)
        assertTrue(reason.contains(model.lexiconFileName), reason)
        assertTrue(reason.contains("重新下载"), reason)
    }

    @Test
    fun `missing FST falls back and explains pronunciation risk`() {
        val missingFst = model.ruleFstFileNames.first()
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, model.id, dir),
            catalogModel = model,
            existingFileNames = allFiles - missingFst,
        )

        assertEquals(SpeechEngine.SYSTEM, decision.engine)
        val reason = decision.fallbackReason!!
        assertTrue(reason.contains(missingFst), reason)
        assertTrue(reason.contains("文本正则化规则"), reason)
        assertTrue(reason.contains("多音字"), reason)
        assertTrue(reason.contains("重新下载"), reason)
    }

    @Test
    fun `a model downloaded into its own directory is enough even without unrelated files`() {
        // 目录里额外的文件（README、.part 半成品）不该影响判定
        val decision = resolveSpeechEngine(
            SpeechEngineSelection(SpeechEngine.NEURAL, model.id, dir),
            catalogModel = model,
            existingFileNames = allFiles + listOf("README.md", "theresa.onnx.part"),
        )
        assertEquals(SpeechEngine.NEURAL, decision.engine)
    }

    @Test
    fun `every catalog model can drive the neural engine when complete`() {
        for (candidate in NeuralVoiceCatalog.models) {
            val decision = resolveSpeechEngine(
                SpeechEngineSelection(SpeechEngine.NEURAL, candidate.id, "/x/${candidate.id}"),
                catalogModel = candidate,
                existingFileNames = candidate.files.map { it.fileName },
            )
            assertEquals(SpeechEngine.NEURAL, decision.engine, "${candidate.id} 下载完整后应能用神经引擎")
        }
    }

    @Test
    fun `engine descriptions state the pitch limitation honestly`() {
        val neural = SpeechEngine.NEURAL.description()
        // 引擎 API 只有 sid + speed，没有音高——这句话必须出现在用户能看到的地方
        assertTrue(neural.contains("不支持音高"), neural)
        assertTrue(neural.contains("语速"), neural)
        val system = SpeechEngine.SYSTEM.description()
        assertTrue(system.contains("不用下载"), system)
    }
}
