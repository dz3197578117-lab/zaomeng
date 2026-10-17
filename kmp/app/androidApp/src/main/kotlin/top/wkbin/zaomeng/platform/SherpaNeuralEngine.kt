package top.wkbin.zaomeng.platform

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
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
        val threads = max(1, numThreads)
        val modelConfig = when (model.engine) {
            NeuralEngineKind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = paths.model,
                    lexicon = paths.lexicon,
                    tokens = paths.tokens,
                    dataDir = "",
                    dictDir = "",
                ),
                numThreads = threads,
                debug = false,
            )

            // Kokoro 与 VITS 的字段完全不同：它要音色向量、espeak-ng 数据目录和 jieba 分词词典。
            // lexicon 传的是**逗号分隔的多本词典**（中文 + 英文），由 paths 拼好。
            NeuralEngineKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = paths.model,
                    voices = paths.voices,
                    tokens = paths.tokens,
                    dataDir = paths.dataDir,
                    lexicon = paths.lexicon,
                    // 留空：语言由 lexicon-zh / lexicon-us-en 自己决定，强制单语反而读不了英文。
                    lang = "",
                    dictDir = paths.dictDir,
                ),
                numThreads = threads,
                debug = false,
            )
        }
        return OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = paths.ruleFsts,
        )
    }

    /**
     * 加载引擎。**失败一律返回失败结果，不抛异常**：语音是可选能力，
     * 缺模型 / 不支持的机型都不该让调用方（进而让聊天）崩掉。
     *
     * 返回的引擎由调用方自己取 [OfflineTts.numSpeakers] / [OfflineTts.sampleRate]——
     * 目录里写的音色数是**展示用**的，说话人分配必须以引擎实际值为准（上游换模型文件时两者可能不一致）。
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
            "语音模型文件不完整：${SpeechEngineDecision.describeMissing(model, missing)}" +
                "（目录 ${modelDir.absolutePath}）"
        }
        warnOnSizeMismatch(model, modelDir)
        // 内存门槛：VITS fp32 模型加载后常驻数百 MB。ONNX Runtime 分配失败时抛的是 C++ 异常，
        // 经 `newFromFile` 的 SafeJNI 会被转成「返回 0」，不会崩；但用户看到的仍是「没声音」，
        // 所以宁可在加载前就拒绝，并给出可读原因。
        val available = availableMemoryBytes(context)
        check(available >= MIN_AVAILABLE_BYTES) {
            "可用内存不足（仅 ${available / 1024 / 1024} MB，需要约 ${MIN_AVAILABLE_BYTES / 1024 / 1024} MB）" +
                "，无法加载语音模型；如果端侧推理（llama.cpp）正在运行，先停止它再试。"
        }
        val config = buildConfig(model, modelDir, numThreads)

        // ⚠️ assetManager 必须为 null（这里刻意用命名参数、不传它）。改错这一处就是「点朗读立刻闪退」。
        //
        // 模型是**按需下载到文件系统**的（`runtimeDir/tts/<id>/`），不在 APK 的 assets 里。
        // 若把 `context.assets` 传进去，sherpa-onnx 会走 `newFromAsset`
        // → `ReadFile(AAssetManager*, path)`，而它对**绝对路径**的 `AAssetManager_open` 必然失败，
        // 随后执行 `SHERPA_ONNX_EXIT(-1)` —— 那是**进程级退出**，Java 侧的 `runCatching` 连异常都收不到，
        // 现象就是「点朗读 / 试听立刻闪退」。sherpa-onnx 自己也会打印
        // "You are using an absolute path ... but assetManager is NOT set to null" 并指向
        // k2-fsa/sherpa-onnx#2562 —— 这正是 2.1.19 引入、2.1.20 未修中、2.1.21 只好整体回退的那个 bug。
        //
        // 传 null 才走 `newFromFile`：它用 std::ifstream 读绝对路径，且外层包了 SafeJNI 捕获 C++ 异常，
        // 加载失败只返回 0（Kotlin 侧抛 IllegalArgumentException），被本函数的 runCatching 收住 → 优雅降级。
        val engine = OfflineTts(config = config)
        Log.i(
            TAG,
            "神经语音引擎已加载：${model.id}，音色 ${engine.numSpeakers()} 个，" +
                "采样率 ${engine.sampleRate()} Hz，加载前可用内存 ${available / 1024 / 1024} MB",
        )
        engine
    }.onFailure { error ->
        // 失败必须留痕：这条链路的失败表现是「没声音」，只有日志能区分是哪一步坏了。
        Log.e(TAG, "神经语音引擎加载失败：${error.message}", error)
    }

    /**
     * 文件在、但大小与目录清单不符时**只记日志**，不阻止加载。
     *
     * 为什么不做成硬校验：清单里的字节数是实测快照，上游重发文件时会对不上，
     * 那时硬校验会让所有用户突然「降级到系统朗读」——用一个静默的功能失效去换一个极罕见的坏文件，
     * 不划算。而真正需要拦的「截断 / 0 字节」现在也不会再崩（`newFromFile` 的 SafeJNI 兜住了），
     * 所以这里只负责把线索留在日志里。
     *
     * 注意：**界面上的可见提示不在这里**。用户看到的提示来自
     * [NeuralVoiceModel.mismatchedFileNames]（设置页用它算出「文件可能不完整」），
     * 因为引擎加载发生在朗读那一刻，那时用户已经不在设置页了，日志里的线索他看不到。
     */
    private fun warnOnSizeMismatch(
        model: NeuralVoiceModel,
        modelDir: File,
    ): List<String> {
        val sizes = model.files
            .associate { it.fileName to File(modelDir, it.fileName).length() }
            .filterKeys { path -> File(modelDir, path).isFile }
        val mismatched = model.mismatchedFileNames(sizes)
        if (mismatched.isNotEmpty()) {
            val detail = mismatched.joinToString("、") { name ->
                val expected = model.files.first { it.fileName == name }.sizeBytes
                "$name（期望 $expected，实际 ${sizes[name]}）"
            }
            Log.w(TAG, "语音模型文件大小与清单不一致：$detail")
        }
        return mismatched
    }

    /** 当前可用内存（字节）；拿不到时返回 [Long.MAX_VALUE]，即不拦——宁可试，也不要因为读不到数字就禁用功能。 */
    private fun availableMemoryBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(info)
            info.availMem
        }.getOrDefault(Long.MAX_VALUE)
    }

    /**
     * 加载前要求的可用内存。
     *
     * 取 700 MB 是留出余量的经验值：theresa 的 fp32 模型 116 MB，但运行时还要放中间张量与采样缓冲，
     * 峰值远大于文件本身；而手机上常见的情况是端侧推理已经占掉一大块。
     */
    private const val MIN_AVAILABLE_BYTES = 700L * 1024 * 1024
}
