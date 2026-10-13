package top.wkbin.zaomeng.platform

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.math.max

/**
 * 离线神经语音引擎（sherpa-onnx + VITS 多说话人中文模型）的构造与加载。
 *
 * ## 这条引擎能做什么、不能做什么（由它的 API 决定，不是我偷懒）
 *
 * 引擎的合成入口是 `generate(text, sid, speed): GeneratedAudio`——**只有说话人编号和语速**：
 *
 * | 能力 | 系统 TTS | 神经引擎 |
 * | --- | --- | --- |
 * | 真正不同的音色 | ❌（中文引擎通常只 1~2 个音色） | ✅ `sid`（theresa 有 804 个） |
 * | 语速 | ✅ | ✅ `speed` |
 * | 音量 | ✅ | ✅ 在**播放侧**施加（`AudioTrack.setVolume`），见 [SherpaNeuralTts.play] |
 * | 音高 | ✅ | ❌ **引擎没有音高参数** |
 *
 * 所以 [VoiceProfile] 里的 `pitch` 在**这条引擎上不生效**（`speed` 与 `volume` 生效）。
 * 这一点在代码与设置界面里都如实标注，不假装它生效——「听起来没变化」比「说了会变却没变」好得多。
 * 真要做音高，只能对合成出的 PCM 变调（过度变调会有金属音），那是另一块工作。
 *
 * ## 模型文件
 *
 * 模型**按需下载**（不随 APK）。加载前用 [NeuralVoiceModel.missingFileNames] 检查完整性：
 * 文件不齐时引擎构造会失败或产生静默无声，提前报出缺哪几个文件更有用。
 */
internal object SherpaNeuralEngine {

    private const val TAG = "SherpaNeuralTts"

    /** 合成线程数：手机上给 2 个足够，再多只是抢大核。 */
    private const val DEFAULT_NUM_THREADS = 2

    /**
     * 按目录清单构造引擎配置。
     *
     * `ruleFsts` 是**逗号分隔**的路径串（引擎要求 CSV），交给
     * [NeuralVoiceModel.pathsIn] 拼装，避免在多个地方各拼一次。
     */
    fun buildConfig(
        model: NeuralVoiceModel,
        modelDir: File,
        numThreads: Int = DEFAULT_NUM_THREADS,
    ): OfflineTtsConfig {
        val paths = model.pathsIn(modelDir.absolutePath)
        val vits = OfflineTtsVitsModelConfig(
            model = paths.model,
            lexicon = paths.lexicon,
            tokens = paths.tokens,
            dataDir = "",
            dictDir = "",
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = max(1, numThreads),
            debug = false,
        )
        return OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = paths.ruleFsts,
        )
    }

    /**
     * 加载引擎。**失败一律返回失败结果，不抛异常**：语音是可选能力，
     * 缺模型 / 不支持的机型都不该让调用方（进而让聊天）崩掉。
     *
     * 返回里带上引擎自报的 [OfflineTts.numSpeakers]——目录里写的音色数是**展示用**的，
     * 说话人分配必须以引擎实际值为准（上游换模型文件时两者可能不一致）。
     */
    fun load(
        context: Context,
        model: NeuralVoiceModel,
        modelDir: File,
        numThreads: Int = DEFAULT_NUM_THREADS,
    ): Result<OfflineTts> = runCatching {
        val existing = modelDir.list()?.toList().orEmpty()
        val missing = model.missingFileNames(existing)
        check(missing.isEmpty()) {
            "语音模型文件不完整，缺少：${missing.joinToString("、")}（在 ${modelDir.absolutePath}）"
        }
        val config = buildConfig(model, modelDir, numThreads)
        val engine = OfflineTts(assetManager = context.assets, config = config)
        Log.i(
            TAG,
            "神经语音引擎已加载：${model.id}，音色 ${engine.numSpeakers()} 个，采样率 ${engine.sampleRate()} Hz",
        )
        engine
    }.onFailure { error ->
        // 失败必须留痕：这条链路的失败表现是「没声音」，只有日志能区分是哪一步坏了。
        Log.e(TAG, "神经语音引擎加载失败：${error.message}", error)
    }
}
