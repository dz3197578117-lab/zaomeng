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
/** 引擎架构。不同架构的 `OfflineTtsModelConfig` 字段完全不同，不能混用。 */
enum class NeuralEngineKind {
    /** VITS（当前三个中文多音色模型）。一行词典 + tokens 即可。 */
    VITS,

    /** Kokoro：需要 voices.bin、espeak-ng-data、jieba dict，音质更好但更重。 */
    KOKORO,
}

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
    val engine: NeuralEngineKind = NeuralEngineKind.VITS,
    /** 主词典文件名（VITS 是 `lexicon.txt`，Kokoro 是 `lexicon-zh.txt`）。 */
    val primaryLexiconFileName: String = "lexicon.txt",
    /** 额外词典（Kokoro 的中英混读需要多本，引擎接收逗号分隔列表）。 */
    val extraLexiconFileNames: List<String> = emptyList(),
    /** Kokoro 的音色向量文件。 */
    val voicesFileName: String? = null,
    /** espeak-ng 数据目录（相对模型目录）。 */
    val dataDirName: String? = null,
    /** jieba 分词词典目录（相对模型目录）。 */
    val dictDirName: String? = null,
    /**
     * 可用的说话人编号白名单。
     *
     * Kokoro 的 103 个音色里只有 45–52 是中文（zf_xiaobei、zm_yunxi 等），
     * 其余是英日等语言——用英文音色读中文会得到一串乱码音。为 null 表示 0 until speakerCount 全可用。
     */
    val selectableSpeakers: List<Int>? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** 主模型文件名（`OfflineTtsConfig` 要指向它）。 */
    val modelFileName: String get() = files.first { it.fileName.endsWith(".onnx") }.fileName

    /** 词典文件名。 */
    val lexiconFileName: String get() = primaryLexiconFileName

    /** 词表文件名。 */
    val tokensFileName: String get() = files.first { it.fileName == "tokens.txt" }.fileName

    /** 文本正则化 FST（数字/日期/多音字）。 */
    val ruleFstFileNames: List<String> get() = files.filter { it.fileName.endsWith(".fst") }.map { it.fileName }

    /** 全部词典（主词典在前），引擎要求逗号分隔。 */
    val allLexiconFileNames: List<String> get() = listOf(primaryLexiconFileName) + extraLexiconFileNames
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

    /**
     * Kokoro 多语言 v1.1 —— 音质路线。
     *
     * 103 个音色里只有 45–52 是中文（4 女 4 男，zf_xiaobei / zf_xiaoni / zf_xiaoxiao /
     * zf_xiaoyi / zm_yunjian / zm_yunxi / zm_yunxia / zm_yunyang），但每个都经过精调，
     * 不存在 VITS 804 音色里「随机落到发闷音色」的问题。24 kHz、支持中英混读。
     *
     * 代价：403 MB、RTF 约 3.2（Raspberry Pi 4 四线程实测；手机上大约是 VITS 的一半速度）。
     * espeak-ng-data 只取中文与英文音素化必需的核心文件（官方整目录有 355 个文件 / 18 MB，
     * 其中 90% 是其它语言的词典，对中文合成没有作用）。
     */
    val KOKORO = NeuralVoiceModel(
        id = "kokoro-multi-lang-v1_1",
        displayName = "高音质中文 · Kokoro（8 中文音色）",
        summary = "24 kHz、中英混读，8 个精调中文音色（4 女 4 男）。音质明显好于多音色模型，" +
            "但体积约 403 MB、合成速度约慢一半。",
        repo = "csukuangfj/kokoro-multi-lang-v1_1",
        speakerCount = 103,
        sampleRateHz = 24_000,
        engine = NeuralEngineKind.KOKORO,
        primaryLexiconFileName = "lexicon-zh.txt",
        extraLexiconFileNames = listOf("lexicon-us-en.txt"),
        voicesFileName = "voices.bin",
        dataDirName = "espeak-ng-data",
        dictDirName = "dict",
        // 只有这 8 个是中文音色；其余 95 个是英/日等语言，用来读中文会得到乱码音。
        selectableSpeakers = (45..52).toList(),
        files = listOf(
            NeuralVoiceFile("model.onnx", "model.onnx", 325_631_784),
            NeuralVoiceFile("voices.bin", "voices.bin", 53_790_720),
            NeuralVoiceFile("tokens.txt", "tokens.txt", 1_111),
            NeuralVoiceFile("lexicon-zh.txt", "lexicon-zh.txt", 2_119_465),
            NeuralVoiceFile("lexicon-us-en.txt", "lexicon-us-en.txt", 5_956_885),
            NeuralVoiceFile("date-zh.fst", "date-zh.fst", 59_154),
            NeuralVoiceFile("number-zh.fst", "number-zh.fst", 64_482),
            NeuralVoiceFile("phone-zh.fst", "phone-zh.fst", 88_630),
            // espeak-ng 核心（中文 + 英文音素化）
            NeuralVoiceFile("espeak-ng-data/phondata", "espeak-ng-data/phondata", 550_424),
            NeuralVoiceFile("espeak-ng-data/phondata-manifest", "espeak-ng-data/phondata-manifest", 21_821),
            NeuralVoiceFile("espeak-ng-data/phonindex", "espeak-ng-data/phonindex", 39_074),
            NeuralVoiceFile("espeak-ng-data/phontab", "espeak-ng-data/phontab", 55_796),
            NeuralVoiceFile("espeak-ng-data/intonations", "espeak-ng-data/intonations", 2_040),
            NeuralVoiceFile("espeak-ng-data/cmn_dict", "espeak-ng-data/cmn_dict", 1_566_335),
            NeuralVoiceFile("espeak-ng-data/en_dict", "espeak-ng-data/en_dict", 166_944),
            NeuralVoiceFile("espeak-ng-data/lang/sit/cmn", "espeak-ng-data/lang/sit/cmn", 686),
            NeuralVoiceFile("espeak-ng-data/lang/sit/cmn-Latn-pinyin", "espeak-ng-data/lang/sit/cmn-Latn-pinyin", 161),
            NeuralVoiceFile("espeak-ng-data/lang/gmw/en", "espeak-ng-data/lang/gmw/en", 140),
            NeuralVoiceFile("espeak-ng-data/lang/gmw/en-US", "espeak-ng-data/lang/gmw/en-US", 257),
            // jieba 分词词典（Kokoro 的中文分词走 dict_dir）
            NeuralVoiceFile("dict/hmm_model.utf8", "dict/hmm_model.utf8", 519_739),
            NeuralVoiceFile("dict/idf.utf8", "dict/idf.utf8", 5_998_717),
            NeuralVoiceFile("dict/jieba.dict.utf8", "dict/jieba.dict.utf8", 5_071_204),
            NeuralVoiceFile("dict/stop_words.utf8", "dict/stop_words.utf8", 8_974),
            NeuralVoiceFile("dict/user.dict.utf8", "dict/user.dict.utf8", 829_194),
        ),
    )

    /** 可选模型，按推荐程度排序（界面直接用这个顺序）。 */
    val models: List<NeuralVoiceModel> = listOf(THERESA, EULA, FANCHEN_C, KOKORO)

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
fun neuralSpeakerId(characterName: String, speakerCount: Int): Int =
    neuralSpeakerId(characterName, (0 until maxOf(speakerCount, 1)).toList())

/**
 * 与 [neuralSpeakerId] 同义，但按模型的**可用音色白名单**取。
 *
 * Kokoro 的 103 个音色里只有 8 个中文；如果仍按 `0 until 103` 派生，角色会拿到英文音色，
 * 读中文时输出的是乱码音。VITS 没有白名单，行为与旧实现完全一致。
 */
fun neuralSpeakerId(characterName: String, model: NeuralVoiceModel): Int =
    neuralSpeakerId(
        characterName,
        model.selectableSpeakers ?: (0 until maxOf(model.speakerCount, 1)).toList(),
    )

/** 在给定音色池里派生一个编号（纯函数，同名字永远同编号）。 */
fun neuralSpeakerId(characterName: String, pool: List<Int>): Int {
    if (pool.isEmpty()) return 0
    val key = characterName.trim()
    if (key.isEmpty()) return pool.first()
    return assignSpeakers(listOf(key), pool)[key] ?: pool.first()
}

/**
 * 作者在人物包里显式指定的音色编号 → `sid`。
 *
 * 为什么需要这条：804 音色的多说话人模型里，**音色质量并不均匀**——有清晰好听的，
 * 也有发闷、含糊、带噪声的。按角色名派生出来的 `sid` 虽然是稳定的，但稳定地落在哪一个
 * 纯属运气：一旦落到一个含糊的音色上，用户听到的就是「读音不清」，而且**无从更换**。
 *
 * `voice_name` 正是留给作者/用户的逃生口：填数字就锁定那个音色。
 * 解析**保守**——不是纯数字就返回 `null`（回落到派生），越界也返回 `null`；
 * 人物包里的 `voice_name` 也可能是自定义音色名，那属于系统 TTS，不该在这里被当成编号。
 */
fun explicitSpeakerId(voiceName: String, speakerCount: Int): Int? {
    if (speakerCount <= 0) return null
    val raw = voiceName.trim()
    if (raw.isEmpty()) return null
    // 允许「第 12 号」「12」这类写法里夹带空白，但绝不猜「低沉」「稍微」这类描述。
    val digits = raw.filterNot { it.isWhitespace() }
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    val value = digits.toLongOrNull() ?: return null
    if (value < 0 || value >= speakerCount) return null
    return value.toInt()
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
    if (speakerCount <= 0) {
        return characterNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            .associateWith { 0 }
    }
    return assignSpeakers(characterNames, (0 until speakerCount).toList())
}

/**
 * 按模型的可用音色白名单分配（Kokoro 只会在 8 个中文音色里挑）。
 *
 * 白名单为空时回落为 0..speakerCount，与旧行为一致。
 */
fun assignNeuralSpeakers(characterNames: List<String>, model: NeuralVoiceModel): Map<String, Int> {
    val pool = model.selectableSpeakers ?: (0 until maxOf(model.speakerCount, 1)).toList()
    if (pool.isEmpty()) {
        return characterNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            .associateWith { 0 }
    }
    return assignSpeakers(characterNames, pool)
}

/** 在给定音色池里分配互不相同的编号（Kokoro 传入的中文白名单就在这里生效）。 */
fun assignNeuralSpeakers(characterNames: List<String>, pool: List<Int>): Map<String, Int> {
    if (pool.isEmpty()) {
        return characterNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            .associateWith { 0 }
    }
    return assignSpeakers(characterNames, pool)
}

/**
 * 从给定的音色池里为角色分配**互不相同**的编号。
 *
 * 三步：① 对名字排序（让结果与出场顺序无关）；② 每个角色先取名字派生的首选编号；
 * ③ 与已占用冲突时**只让冲突者**向后探测，其余人保持原值。
 */
private fun assignSpeakers(characterNames: List<String>, pool: List<Int>): Map<String, Int> {
    val names = characterNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    if (names.isEmpty() || pool.isEmpty()) return emptyMap()

    val taken = mutableSetOf<Int>()
    val assigned = LinkedHashMap<String, Int>()
    for (name in names) {
        // 转成无符号再取模：avalanche 的结果可能为负，负数取模会得到负下标。
        var index = ((avalanche(name.hashCode()).toLong() and 0xFFFF_FFFFL) % pool.size).toInt()
        var probes = 0
        while (pool[index] in taken && probes < pool.size) {
            index = (index + 1) % pool.size
            probes++
        }
        taken += pool[index]
        assigned[name] = pool[index]
    }
    return assigned
}
