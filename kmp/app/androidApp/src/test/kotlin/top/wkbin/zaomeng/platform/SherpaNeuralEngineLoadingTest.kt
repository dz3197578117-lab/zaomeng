package top.wkbin.zaomeng.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住「神经引擎从**文件系统**加载」这一条不可回退的约定。
 *
 * ## 为什么需要源码级守卫（而不是普通单测）
 *
 * 这个坑的表现是**进程直接退出**：模型按需下载到 `runtimeDir/tts/<id>/`，
 * 若构造 `OfflineTts` 时传了 `context.assets`，sherpa-onnx 会走 `newFromAsset`
 * → `ReadFile(AAssetManager*, path)`，而它对**绝对路径**的 `AAssetManager_open` 必然失败，
 * 紧接着执行 `SHERPA_ONNX_EXIT(-1)`。那是 native 层的 `exit()`，Java 侧连异常都收不到，
 * `runCatching` 一律无效——用户看到的就是「点朗读 / 点试听**立刻闪退**」。
 *
 * 这一处曾经真实发生过：2.1.19 引入后一直闪退，2.1.20 去修了别的地方（合成串行化、AudioTrack 释放）
 * 因此没修中，2.1.21 只能整体回退，2.3.0 移植时又被原样搬了回来。
 *
 * 构造调用是 native 的，普通单测跑不到（JVM 上没有这个 `.so`），所以这里退而求其次：
 * **读源码，断言那个参数没有被加回来**。写死一条源码断言不好看，但比「再闪退一次、再回退一版」便宜得多。
 */
class SherpaNeuralEngineLoadingTest {

    @Test
    fun `engine is constructed through the file path, not the asset path`() {
        val code = effectiveCode(readSource("SherpaNeuralEngine.kt"))
        assertTrue(
            code.contains("OfflineTts(config = config)"),
            "构造引擎必须写成 `OfflineTts(config = config)`（assetManager 留空 → 走 newFromFile）。",
        )
    }

    @Test
    fun `engine construction never references an asset manager`() {
        val code = effectiveCode(readSource("SherpaNeuralEngine.kt"))
        assertFalse(
            code.contains("assetManager"),
            "构造引擎时不能出现 assetManager：模型在文件系统里，传 assets 会让 sherpa-onnx 在 " +
                "AAssetManager_open 失败后 SHERPA_ONNX_EXIT(-1)，进程直接退出（点朗读/试听即闪退）。",
        )
        assertFalse(
            code.contains(".assets"),
            "不要引用 Context.assets——这条引擎只读文件系统路径。",
        )
    }

    @Test
    fun `all model paths handed to the engine are absolute`() {
        // 绝对路径正是「必须走 newFromFile」的原因，也是上面两条断言的语义前提。
        val model = NeuralVoiceCatalog.THERESA
        val dir = "/data/user/0/top.wkbin.zaomeng/files/tts/${model.id}"
        val paths = model.pathsIn(dir)
        val handed = listOf(paths.model, paths.lexicon, paths.tokens) + paths.ruleFsts.split(",")
        for (path in handed) {
            assertTrue(path.startsWith("/"), "交给引擎的路径必须是绝对路径：$path")
        }
        assertEquals(model.files.size, handed.size, "交给引擎的路径数量与目录清单不一致")
    }

    // ------------------------------------------------------------------

    /**
     * 去掉行注释与块注释，只留**有效代码**。
     *
     * 必需：上面那条断言要检查「没有传 assetManager」，而源码里恰恰有一段注释在解释
     * 「为什么不能传 assetManager」——不剥离注释就会把自己的说明文档当成违规。
     */
    private fun effectiveCode(source: String): String {
        val withoutBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source, "")
        return withoutBlockComments.lines()
            .joinToString("\n") { line -> line.substringBefore("//") }
    }

    /**
     * 定位源文件。
     *
     * 单测的工作目录随 Gradle 版本/调用方式而变（模块目录或仓库根目录），
     * 所以按几种常见位置依次尝试，找不到时**报错而不是跳过**——
     * 静默跳过会让这条守卫在真正需要它的时候悄悄失效。
     */
    private fun readSource(fileName: String): String {
        val relative = "src/main/kotlin/top/wkbin/zaomeng/platform/$fileName"
        val candidates = listOf(
            File(relative),
            File("app/androidApp/$relative"),
            File("../app/androidApp/$relative"),
            File("kmp/app/androidApp/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到源文件 $fileName；已尝试：" +
                    candidates.joinToString("、") { it.absolutePath } +
                    "（当前目录 ${File(".").absolutePath}）",
            )
        return file.readText()
    }
}
