package top.wkbin.zaomeng.platform

/**
 * 离线神经语音模型目录（**按需下载**，不随 APK 分发）。
 *
 * ## 数据来源与实测时间
 *
 * 说话人数量与采样率取自 sherpa-onnx 官方预训练模型表（`vits` 页的「All models in a single table」）；
 * 文件清单与**精确字节数**由 HuggingFace 仓库的 tree API 实测（经 hf-mirror，2026-10-14）。
 * 体积写进代码是为了在下载界面上**如实显示要下多少**，而不是等用户点下去才发现是 119 MB。
 * 真实下载仍以服务端 `Content-Length` 为准（文件可能被上游重发）。
 *
 * ## 为什么只收多说话人模型
 *
 * 用户要的是「每个人物都有不同的声音」。单说话人模型无论怎么调音高语速都是同一个人，
 * 做不到这一点；所以目录里**只放多说话人中文模型**，并尽量选音色多、采样率高的。
 */
data class NeuralVoiceModel(
    /** 稳定标识，会被持久化到偏好里，**不要改名**。 */
    val id: String,
    val displayName: String,
    /** 一句话说明，用于界面。 */
    val summary: String,
    /** HuggingFace 仓库（`owner/repo`）。下载时按镜像设置换成 hf-mirror 或官方地址。 */
    val repo: String,
    /** 音色数量（官方模型表的数据）。 */
    val speakerCount: Int,
    /** 采样率（Hz）。8 kHz 是电话音质，会**显著**影响「不机械」的听感。 */
    val sampleRateHz: Int,
    val files: List<NeuralVoiceFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** 主模型文件名（`OfflineTtsConfig` 要指向它）。 */
    val modelFileName: String get() = files.first { it.fileName.endsWith(".onnx") }.fileName

    /** 词典文件名。 */
    val lexiconFileName: String get() = files.first { it.fileName == "lexicon.txt" }.fileName

    /** 词表文件名。 */
    val tokensFileName: String get() = files.first { it.fileName == "tokens.txt" }.fileName

    /** 文本正则化 FST（数字/日期/多音字）。 */
    val ruleFstFileNames: List<String> get() = files.filter { it.fileName.endsWith(".fst") }.map { it.fileName }
}

/**
 * 模型需要的单个文件。
 *
 * [fileName] 是落到本机后的文件名，[path] 是仓库内路径——两者目前相同，
 * 分开是因为仓库路径随时可能调整，而本机文件名一旦定下就不该再变（会影响已下好的用户）。
 */
data class NeuralVoiceFile(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
)

/**
 * 目录本体。
 *
 * 刻意**不提供**「随便填个 URL 就能用」的入口：模型必须能被引擎正确加载，
 * 一套不匹配的配置只会得到一个静默不响的语音，比没有这个功能更糟。
 */
object NeuralVoiceCatalog {
    /** VITS 通用需要的文本正则化 FST（theresa / eula / fanchen-C 三个仓库里同名同大小）。 */
    private val RULE_FSTS = listOf(
        NeuralVoiceFile("date.fst", "date.fst", 59_154),
        NeuralVoiceFile("number.fst", "number.fst", 64_482),
        NeuralVoiceFile("phone.fst", "phone.fst", 88_630),
        NeuralVoiceFile("new_heteronym.fst", "new_heteronym.fst", 21_974),
    )

    /** 804 个音色、22.05 kHz —— 目前质量与音色数量最好的中文多说话人模型。 */
    val THERESA = NeuralVoiceModel(
        id = "vits-zh-hf-theresa",
        displayName = "中文多音色 · Theresa（804 音色）",
        summary = "804 个音色、22.05 kHz。音色数量最多、采样率最高，推荐给「每个角色都要有辨识度」的用法。",
        repo = "csukuangfj/vits-zh-hf-theresa",
        speakerCount = 804,
        sampleRateHz = 22_050,
        files = listOf(
            NeuralVoiceFile("theresa.onnx", "theresa.onnx", 121_913_906),
            NeuralVoiceFile("lexicon.txt", "lexicon.txt", 2_764_076),
            NeuralVoiceFile("tokens.txt", "tokens.txt", 268),
        ) + RULE_FSTS,
    )

    /** 与 THERESA 同规模，另一套音色（804 音色 / 22.05 kHz）。 */
    val EULA = NeuralVoiceModel(
        id = "vits-zh-hf-eula",
        displayName = "中文多音色 · Eula（804 音色）",
        summary = "与 Theresa 同为 804 音色、22.05 kHz，是另一套音色库；觉得某些角色的音色不合适时可以换它。",
        repo = "csukuangfj/vits-zh-hf-eula",
        speakerCount = 804,
        sampleRateHz = 22_050,
        files = listOf(
            NeuralVoiceFile("eula.onnx", "eula.onnx", 121_913_906),
            NeuralVoiceFile("lexicon.txt", "lexicon.txt", 2_764_076),
            NeuralVoiceFile("tokens.txt", "tokens.txt", 268),
        ) + RULE_FSTS,
    )

    /** 187 个音色、16 kHz，体积略小一点。 */
    val FANCHEN_C = NeuralVoiceModel(
        id = "vits-zh-hf-fanchen-C",
        displayName = "中文多音色 · Fanchen-C（187 音色）",
        summary = "187 个音色、16 kHz。音色比 Theresa 少但依然够用，体积略小。",
        repo = "csukuangfj/vits-zh-hf-fanchen-C",
        speakerCount = 187,
        sampleRateHz = 16_000,
        files = listOf(
            NeuralVoiceFile("vits-zh-hf-fanchen-C.onnx", "vits-zh-hf-fanchen-C.onnx", 121_266_652),
            NeuralVoiceFile("lexicon.txt", "lexicon.txt", 2_457_843),
            NeuralVoiceFile("tokens.txt", "tokens.txt", 268),
        ) + RULE_FSTS,
    )

    /** 可选模型，按推荐程度排序（界面直接用这个顺序）。 */
    val models: List<NeuralVoiceModel> = listOf(THERESA, EULA, FANCHEN_C)

    /** 默认模型：音色最多、采样率最高。 */
    val defaultModel: NeuralVoiceModel get() = THERESA

    fun byId(id: String): NeuralVoiceModel? = models.firstOrNull { it.id == id }

    /**
     * 解析持久化的模型 id：找不到（例如旧版本存的 id 已下线）时回落到默认模型。
     *
     * 不返回 null：朗读链路上「没有模型」比「用默认模型」难处理得多，
     * 而用户并不会因此丢失什么——已经下好的模型仍按各自 id 匹配。
     */
    fun resolveOrDefault(id: String?): NeuralVoiceModel = byId(id.orEmpty()) ?: defaultModel
}

/**
 * 角色名 → 说话人编号（`0 until speakerCount`）的**首选值**。
 *
 * 「首选」意味着它可能落在已被同 cast 其他角色占用的编号上；排序与避让见 [assignNeuralSpeakers]。
 *
 * 为什么不按「出场顺序」轮流分配：那样换一次会话、或多来一个角色，
 * 所有人的音色都会跟着变——「林黛玉的声音」必须稳定，否则用户会觉得人物变了。
 * 这里与 [derivedVoiceProfile] 共用同一套雪崩混合，保证两条引擎路径对「谁是谁」的判断一致。
 */
fun neuralSpeakerId(characterName: String, speakerCount: Int): Int {
    if (speakerCount <= 0) return 0
    val key = characterName.trim()
    if (key.isEmpty()) return 0
    // 转成无符号再取模：avalanche 的结果可能为负，负数取模会得到负编号。
    return ((avalanche(key.hashCode()).toLong() and 0xFFFF_FFFFL) % speakerCount).toInt()
}

/**
 * 为一组角色分配**互不相同**的说话人编号。
 *
 * 三步：① 对名字排序（让结果与出场顺序无关）；② 每个角色先取名字派生的首选编号；
 * ③ 与已占用冲突时**只让冲突者**向后探测，其余人保持原值。
 *
 * 第 ③ 步是关键：如果改成「按顺序依次取下一个空位」，那么只要 cast 变一次，
 * 所有角色的音色都会整体挪位。这里的取舍是**「不撞车」优先、且尽量不动别人**——
 * 角色数量远小于音色数量（8 人 vs 804 音色），撞车本来就少见，撞到时也只影响撞上的那两个。
 *
 * 角色数超过音色数时无法保证互不相同，此时允许重复（否则会死循环）。
 */
fun assignNeuralSpeakers(characterNames: List<String>, speakerCount: Int): Map<String, Int> {
    val names = characterNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    if (names.isEmpty()) return emptyMap()
    if (speakerCount <= 0) return names.associateWith { 0 }

    val taken = mutableSetOf<Int>()
    val assigned = LinkedHashMap<String, Int>()
    for (name in names) {
        var sid = neuralSpeakerId(name, speakerCount)
        var probes = 0
        while (sid in taken && probes < speakerCount) {
            sid = (sid + 1) % speakerCount
            probes++
        }
        taken += sid
        assigned[name] = sid
    }
    return assigned
}
