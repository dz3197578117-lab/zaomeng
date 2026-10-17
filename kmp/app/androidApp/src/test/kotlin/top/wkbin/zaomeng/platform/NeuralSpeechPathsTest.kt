package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型路径拼装与完整性检查的回归测试。
 *
 * 这两件事错了都不会抛异常，症状只有「点了没声音」或「数字读错」——
 * 在真机上几乎无法定位，所以必须在这里钉住。
 */
class NeuralSpeechPathsTest {

    private val model = NeuralVoiceCatalog.THERESA
    private val dir = "/data/user/0/top.wkbin.zaomeng/files/tts/${model.id}"

    @Test
    fun `paths point at the files the engine needs`() {
        val paths = model.pathsIn(dir)
        assertEquals("$dir/theresa.onnx", paths.model)
        assertEquals("$dir/lexicon.txt", paths.lexicon)
        assertEquals("$dir/tokens.txt", paths.tokens)
        assertEquals(dir, paths.modelDir)
    }

    @Test
    fun `rule fsts are comma separated as the engine requires`() {
        val paths = model.pathsIn(dir)
        val entries = paths.ruleFsts.split(",")
        assertEquals(model.ruleFstFileNames.size, entries.size, "FST 个数与目录不一致：${paths.ruleFsts}")
        for (entry in entries) {
            assertTrue(entry.startsWith("$dir/"), "FST 路径没落在模型目录里：$entry")
            assertTrue(entry.endsWith(".fst"), "FST 路径后缀不对：$entry")
        }
        // 引擎要求 CSV：用逗号而不是平台分隔符之外的东西
        assertTrue(paths.ruleFsts.contains(","), "多个 FST 必须用逗号连接")
        assertTrue(!paths.ruleFsts.contains(" "), "CSV 里不该有空格：${paths.ruleFsts}")
    }

    @Test
    fun `trailing slash in the directory does not produce double slashes`() {
        val withSlash = model.pathsIn("$dir/")
        val withoutSlash = model.pathsIn(dir)
        assertEquals(withoutSlash, withSlash)
        assertTrue(!withSlash.model.contains("//"), "拼出了双斜杠：${withSlash.model}")
        assertTrue(!withSlash.ruleFsts.contains("//"), "FST 里拼出了双斜杠：${withSlash.ruleFsts}")
    }

    @Test
    fun `every catalog file appears in the built paths`() {
        val paths = model.pathsIn(dir)
        val built = (listOf(paths.model, paths.lexicon, paths.tokens) + paths.ruleFsts.split(",")).toSet()
        for (file in model.files) {
            assertTrue("$dir/${file.fileName}" in built, "${file.fileName} 没有被拼进任何路径")
        }
        assertEquals(model.files.size, built.size, "路径数量与目录清单不一致")
    }

    @Test
    fun `completeness check lists exactly the missing files in catalog order`() {
        val onlyModel = listOf(model.modelFileName)
        val missing = model.missingFileNames(onlyModel)
        assertEquals(model.files.map { it.fileName }.filterNot { it == model.modelFileName }, missing)
        assertEquals(false, model.isFullyDownloaded(onlyModel))
    }

    @Test
    fun `a missing FST is incomplete even when the three engine files are present`() {
        val allExceptOneFst = model.files.map { it.fileName } - model.ruleFstFileNames.first()
        val missing = model.missingFileNames(allExceptOneFst)

        assertEquals(listOf(model.ruleFstFileNames.first()), missing)
        assertTrue(!model.isFullyDownloaded(allExceptOneFst), "缺文本正则化规则时不能标记模型完整")
    }

    @Test
    fun `file size mismatch identifies a truncated vocabulary or rule file`() {
        val actualSizes = model.files.associate { it.fileName to it.sizeBytes }.toMutableMap()
        actualSizes[model.lexiconFileName] = model.files.first { it.fileName == model.lexiconFileName }.sizeBytes - 1

        assertEquals(listOf(model.lexiconFileName), model.mismatchedFileNames(actualSizes))
        assertEquals(emptyList(), model.mismatchedFileNames(emptyMap()), "未知大小不能误报为不匹配")
    }

    @Test
    fun `a fully downloaded model reports complete`() {
        val all = model.files.map { it.fileName }
        assertEquals(emptyList(), model.missingFileNames(all))
        assertTrue(model.isFullyDownloaded(all))
    }

    @Test
    fun `extra unrelated files in the directory do not affect completeness`() {
        val all = model.files.map { it.fileName } + listOf("README.md", "download.log", "theresa.onnx.part")
        assertTrue(model.isFullyDownloaded(all))
    }

    @Test
    fun `every catalog model resolves all of its own files`() {
        for (candidate in NeuralVoiceCatalog.models) {
            val paths = candidate.pathsIn(dir)
            val built = (listOf(paths.model, paths.lexicon, paths.tokens) + paths.ruleFsts.split(",")).toSet()
            assertEquals(candidate.files.size, built.size, "${candidate.id} 的路径数与文件数不一致")
            assertTrue(candidate.isFullyDownloaded(candidate.files.map { it.fileName }))
        }
    }

    // ------------------------------------------------------------------
    // 下载计划：直链与镜像同构（与端侧 GGUF 清单同一约定）
    // ------------------------------------------------------------------

    @Test
    fun `download urls follow the same convention as the gguf presets`() {
        val plans = model.downloadPlans("https://huggingface.co")
        for (plan in plans) {
            assertEquals(
                "https://huggingface.co/${model.repo}/resolve/main/${plan.fileName}",
                plan.url,
            )
        }
        assertEquals(model.files.size, plans.size)
    }

    @Test
    fun `switching the mirror changes only the host`() {
        val official = model.downloadPlans("https://huggingface.co")
        val mirror = model.downloadPlans("https://hf-mirror.com")
        assertEquals(official.size, mirror.size)
        for (index in official.indices) {
            assertEquals(official[index].fileName, mirror[index].fileName)
            assertEquals(official[index].sizeBytes, mirror[index].sizeBytes)
            assertEquals(
                official[index].url.removePrefix("https://huggingface.co"),
                mirror[index].url.removePrefix("https://hf-mirror.com"),
                "换源时路径部分必须完全一致：${official[index].url} vs ${mirror[index].url}",
            )
        }
    }

    @Test
    fun `trailing slash in the base url does not produce double slashes`() {
        assertEquals(
            model.downloadPlans("https://hf-mirror.com"),
            model.downloadPlans("https://hf-mirror.com/"),
        )
        assertTrue(model.downloadPlans("https://hf-mirror.com/").none { it.url.contains("//$") || it.url.contains("com//") })
    }

    @Test
    fun `planned sizes match the catalog and file names are unique`() {
        for (candidate in NeuralVoiceCatalog.models) {
            val plans = candidate.downloadPlans("https://hf-mirror.com")
            assertEquals(candidate.totalBytes, plans.sumOf { it.sizeBytes }, "${candidate.id} 计划体积与目录不符")
            assertEquals(plans.size, plans.map { it.fileName }.toSet().size, "${candidate.id} 文件名重复")
        }
    }

    @Test
    fun `each model gets its own directory so same named files do not collide`() {
        val dirs = NeuralVoiceCatalog.models.map { it.relativeDirectory() }
        assertEquals(dirs.size, dirs.toSet().size, "模型目录必须互不相同：$dirs")
        for (candidate in NeuralVoiceCatalog.models) {
            assertTrue(candidate.relativeDirectory().startsWith("tts/"), candidate.relativeDirectory())
        }
        // 三个模型的清单里确实有同名文件——这正是必须分目录的原因
        val sharedNames = NeuralVoiceCatalog.models
            .map { it.files.map { file -> file.fileName }.toSet() }
            .reduce { acc, names -> acc intersect names }
        assertTrue(sharedNames.isNotEmpty(), "若清单里没有同名文件，这个用例的前提就不成立了")
    }
}
