package top.wkbin.zaomeng.platform

/**
 * 已下载的神经语音模型在本机的位置，以及 sherpa-onnx 需要的路径字符串。
 *
 * 刻意与平台无关（只有字符串拼装），因此可以在 jvmTest 里覆盖——
 * 路径拼错、FST 少列一个，症状都是**运行时静默不响或读错数字**，在真机上极难定位。
 *
 * 神经引擎目前只在 Android 生效，路径分隔符按 Android/Linux 的 `/` 拼。
 */
data class NeuralSpeechPaths(
    val modelDir: String,
    /** VITS 主模型。 */
    val model: String,
    val lexicon: String,
    val tokens: String,
    /** 文本正则化 FST，**逗号连接**（引擎要求 CSV 形式）。 */
    val ruleFsts: String,
)

/**
 * 把模型目录 + 目录清单拼成引擎需要的路径。
 *
 * 目录末尾的斜杠会被规整掉：否则会拼出 `.../tts//theresa.onnx`，
 * 多数系统能容忍，但 `ruleFsts` 是 CSV，多一个斜杠就是另一条路径，不值得冒险。
 */
fun NeuralVoiceModel.pathsIn(modelDirAbsolutePath: String): NeuralSpeechPaths {
    val dir = modelDirAbsolutePath.trimEnd('/')
    fun file(name: String) = "$dir/$name"
    return NeuralSpeechPaths(
        modelDir = dir,
        model = file(modelFileName),
        lexicon = file(lexiconFileName),
        tokens = file(tokensFileName),
        ruleFsts = ruleFstFileNames.joinToString(",") { file(it) },
    )
}

/**
 * 缺哪些文件（相对模型目录的文件名，顺序与目录清单一致）。
 *
 * 调用方应在**加载引擎之前**用它做一次检查：模型文件不齐时引擎构造会失败或产生静默无声，
 * 提前报出缺哪几个文件，用户至少知道要重新下载，而不是以为「这个功能坏了」。
 */
fun NeuralVoiceModel.missingFileNames(existingFileNames: Collection<String>): List<String> {
    val present = existingFileNames.toSet()
    return files.map { it.fileName }.filterNot { it in present }
}

/** 目录里的文件是否已经齐了。 */
fun NeuralVoiceModel.isFullyDownloaded(existingFileNames: Collection<String>): Boolean =
    missingFileNames(existingFileNames).isEmpty()

/**
 * 模型在本机保存时用的**相对目录**（按模型 id 分目录）。
 *
 * 为什么每个模型一个子目录：不同模型的清单里可能有同名文件（三个模型都有 `lexicon.txt`、
 * `tokens.txt`、以及那四个同名 `.fst`）。平铺在一个目录里会互相覆盖——
 * 换一次模型就把上一个模型的词典换掉了，而症状只是「读音变怪」，极难定位。
 */
fun NeuralVoiceModel.relativeDirectory(): String = "tts/$id"

/** 一个模型文件的下载计划：[url] 已按选定下载源拼好。 */
data class NeuralVoiceDownloadPlan(
    /** 落盘文件名（模型目录内的名字）。 */
    val fileName: String,
    val url: String,
    /** 期望字节数（目录实测值），用于下载后校验与进度显示。 */
    val sizeBytes: Long,
)

/**
 * 按选定下载源拼出该模型的全部下载计划。
 *
 * 直链在官方站与镜像站上**同构**（只有主机名不同），与端侧 GGUF 模型清单用**同一约定**：
 * `<base>/<repo>/resolve/main/<path>`。这样「换源」就只是换一个 baseUrl 字符串。
 *
 * 刻意把 baseUrl 作为参数而不是内置常量：下载源由设置页决定（官方 / hf-mirror），
 * 这里只负责拼装，不替用户选源。
 */
fun NeuralVoiceModel.downloadPlans(baseUrl: String): List<NeuralVoiceDownloadPlan> {
    val base = baseUrl.trimEnd('/')
    return files.map { file ->
        NeuralVoiceDownloadPlan(
            fileName = file.fileName,
            url = "$base/$repo/resolve/main/${file.path}",
            sizeBytes = file.sizeBytes,
        )
    }
}

/** 模型仓库页（给「查看来源」用）。 */
fun NeuralVoiceModel.repoUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/$repo"
