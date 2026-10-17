package top.wkbin.zaomeng.platform

/** 朗读引擎。 */
enum class SpeechEngine {
    /** 系统 TTS：不需要下载任何东西就一定可用，但中文通常只有 1~2 个音色。 */
    SYSTEM,

    /** 离线神经 TTS：每个角色能拿到**真正不同**的音色，但模型要按需下载，且**不支持音高**。 */
    NEURAL,
}

/**
 * 用户选择的朗读引擎与神经模型。
 *
 * 由偏好（`ttsEngine` / `ttsNeuralModelId` / `ttsNeuralModelDir`）构造后一路传到
 * `rememberPlatformTts()`——**不放进全局可变状态**：那样「谁在什么时候改的」无从追查，
 * 而引擎选错的表现只是「没声音」或「声音不对」，最难查的正是这种。
 */
data class SpeechEngineSelection(
    val engine: SpeechEngine = SpeechEngine.SYSTEM,
    /** 选用的模型 id（`NeuralVoiceCatalog` 里的 id）。 */
    val neuralModelId: String = "",
    /** 模型在本机的绝对目录（服务端回报，客户端不自己拼）。 */
    val neuralModelDir: String = "",
    /**
     * 系统 TTS 使用的引擎包名；空串表示跟随系统默认。
     *
     * 非空时用它绑定（`TextToSpeech(context, listener, packageName)`），
     * 这样用户可以把朗读交给 NekoSpeak 这类第三方引擎，用它的音色做角色区分。
     */
    val systemEnginePackage: String = "",
)

/**
 * 真正用哪个引擎，以及**降级原因**。
 *
 * [fallbackReason] 只在「用户选了神经引擎但它不可用」时非空——界面必须把它显示出来。
 * 静默降级是最坏的选择：用户会以为「所谓的不同音色就是这样的」，
 * 而实际上他听到的一直是系统 TTS。
 */
data class SpeechEngineDecision(
    val engine: SpeechEngine,
    val fallbackReason: String? = null,
) {
    val isFallback: Boolean get() = fallbackReason != null

    companion object {
        /**
         * 缺文件时的可读原因：**区分**「必需文件」与「文本正则化 FST」。
         *
         * 两者症状完全不同，混成一句「缺 N 个」会让用户照着错的方向查：
         * - 缺必需文件（onnx/lexicon/tokens）→ 引擎**根本加载不了**，完全没声音；
         * - 缺 FST → 引擎能加载、也能出声，但**数字、日期、多音字读错**，
         *   听感就是「读音含糊」。这正是最难自查的一类——听起来像质量差，其实是缺文件。
         *
         * 所以这里点名缺的是哪一类，而不是只报个数。
         */
        fun describeMissing(model: NeuralVoiceModel, missing: List<String>): String {
            val fstNames = model.ruleFstFileNames.toSet()
            val missingFsts = missing.filter { it in fstNames }
            val missingRequired = missing.filterNot { it in fstNames }
            val detail = buildString {
                if (missingRequired.isNotEmpty()) {
                    append("缺少必需文件 ").append(missingRequired.joinToString("、"))
                }
                if (missingFsts.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("缺少文本正则化规则 ").append(missingFsts.joinToString("、"))
                    append("（会导致数字、日期与多音字读错）")
                }
            }
            return "「${model.displayName}」的文件不完整：$detail。已改用系统朗读；重新下载即可恢复。"
        }
    }
}

/**
 * 决定实际使用哪个引擎。**纯函数**：只看选择与「本机已有哪些文件」，不碰文件系统。
 *
 * 判定顺序（每一步都给出可读的原因）：
 * 1. 选的是系统 TTS → 直接用，无需检查任何东西；
 * 2. 模型 id 不在目录里（例如旧版本存的 id 已下线）→ 降级；
 * 3. 没有模型目录 → 降级（提示去下载）；
 * 4. 模型文件不齐 → 降级，并说明缺几个；
 * 5. 否则用神经引擎。
 */
fun resolveSpeechEngine(
    selection: SpeechEngineSelection,
    catalogModel: NeuralVoiceModel?,
    existingFileNames: Collection<String>,
): SpeechEngineDecision {
    if (selection.engine == SpeechEngine.SYSTEM) {
        return SpeechEngineDecision(SpeechEngine.SYSTEM)
    }
    if (catalogModel == null) {
        return SpeechEngineDecision(
            SpeechEngine.SYSTEM,
            fallbackReason = "所选语音模型已不在可用目录中，已改用系统朗读。",
        )
    }
    if (selection.neuralModelDir.isBlank()) {
        return SpeechEngineDecision(
            SpeechEngine.SYSTEM,
            fallbackReason = "还没有下载「${catalogModel.displayName}」，已改用系统朗读。",
        )
    }
    val missing = catalogModel.missingFileNames(existingFileNames)
    if (missing.isNotEmpty()) {
        return SpeechEngineDecision(
            SpeechEngine.SYSTEM,
            fallbackReason = SpeechEngineDecision.describeMissing(catalogModel, missing),
        )
    }
    return SpeechEngineDecision(SpeechEngine.NEURAL)
}

/**
 * 引擎的一句话说明，用于设置界面。**如实标注能力边界**——尤其「神经引擎不支持音高」，
 * 这是引擎 API 决定的（`generate(text, sid, speed)` 没有音高参数），不是没做。
 */
fun SpeechEngine.description(): String = when (this) {
    SpeechEngine.SYSTEM ->
        "系统 TTS：不用下载，立刻可用。中文通常只有 1~2 个音色，" +
            "角色差异靠音高、语速与音量表达（支持全部三项）。"
    SpeechEngine.NEURAL ->
        "离线神经 TTS：每个角色可分到**不同音色**（多说话人模型），完全离线。" +
            "支持语速与音量；**不支持音高**（引擎没有该参数），情绪主要靠语速与模型表现力。"
}
