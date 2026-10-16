package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 离线神经语音目录与说话人分配的回归测试。
 *
 * 目录里的数字（音色数、采样率、文件字节）都是**实测来源**，不是估计：说话人数量与采样率来自
 * sherpa-onnx 官方模型表，字节数由 HuggingFace tree API 实测。所以这些用例同时起到
 * 「数据被改动就必须有人重新核对来源」的作用。
 */
class NeuralVoiceCatalogTest {

    // ------------------------------------------------------------------
    // 目录数据
    // ------------------------------------------------------------------

    @Test
    fun `catalog ids are unique and resolvable`() {
        val ids = NeuralVoiceCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "模型 id 重复了：$ids")
        for (model in NeuralVoiceCatalog.models) {
            assertSame(model, NeuralVoiceCatalog.byId(model.id), "byId 应能取回同一个模型")
        }
    }

    @Test
    fun `every catalog model is multi speaker and carries the three required files`() {
        for (model in NeuralVoiceCatalog.models) {
            assertTrue(
                model.speakerCount > 1,
                "${model.id} 只有 ${model.speakerCount} 个音色——单说话人模型做不到「每个角色不同声音」，不该进目录",
            )
            val onnx = model.files.filter { it.fileName.endsWith(".onnx") }
            assertEquals(1, onnx.size, "${model.id} 应恰好有一个 .onnx，实际 ${onnx.map { it.fileName }}")
            assertEquals(model.modelFileName, onnx.single().fileName)
            assertTrue(model.lexiconFileName.endsWith("lexicon.txt"))
            assertTrue(model.tokensFileName.endsWith("tokens.txt"))
            assertTrue(model.ruleFstFileNames.isNotEmpty(), "${model.id} 缺文本正则化 FST（数字/日期读不对）")
        }
    }

    @Test
    fun `verified upstream numbers are pinned`() {
        // 来源：sherpa-onnx 官方 vits 预训练模型表（音色数 / 采样率）
        assertEquals(804, NeuralVoiceCatalog.THERESA.speakerCount)
        assertEquals(22_050, NeuralVoiceCatalog.THERESA.sampleRateHz)
        assertEquals(804, NeuralVoiceCatalog.EULA.speakerCount)
        assertEquals(187, NeuralVoiceCatalog.FANCHEN_C.speakerCount)
        assertEquals(16_000, NeuralVoiceCatalog.FANCHEN_C.sampleRateHz)
        assertEquals("csukuangfj/vits-zh-hf-theresa", NeuralVoiceCatalog.THERESA.repo)
    }

    @Test
    fun `total size equals the sum of files and stays inside the agreed budget`() {
        for (model in NeuralVoiceCatalog.models) {
            assertEquals(model.files.sumOf { it.sizeBytes }, model.totalBytes)
            assertTrue(
                model.totalBytes in 100L * 1024 * 1024..200L * 1024 * 1024,
                "${model.id} 是 ${model.totalBytes / 1024 / 1024} MB，超出商定的 100~200 MB",
            )
        }
    }

    @Test
    fun `model file names are unique inside a model`() {
        for (model in NeuralVoiceCatalog.models) {
            val names = model.files.map { it.fileName }
            assertEquals(names.size, names.toSet().size, "${model.id} 文件名重复：$names")
        }
    }

    @Test
    fun `resolve or default never returns null`() {
        assertSame(NeuralVoiceCatalog.THERESA, NeuralVoiceCatalog.resolveOrDefault(NeuralVoiceCatalog.THERESA.id))
        assertSame(NeuralVoiceCatalog.defaultModel, NeuralVoiceCatalog.resolveOrDefault("已下线的旧 id"))
        assertSame(NeuralVoiceCatalog.defaultModel, NeuralVoiceCatalog.resolveOrDefault(null))
        assertSame(NeuralVoiceCatalog.defaultModel, NeuralVoiceCatalog.resolveOrDefault(""))
    }

    @Test
    fun `default model is the most capable one`() {
        val best = NeuralVoiceCatalog.models.maxBy { it.speakerCount * 100_000 + it.sampleRateHz }
        assertSame(best, NeuralVoiceCatalog.defaultModel, "默认模型应是音色最多、采样率最高的那个")
    }

    // ------------------------------------------------------------------
    // 说话人编号
    // ------------------------------------------------------------------

    @Test
    fun `speaker id is always inside range and never negative`() {
        val names = listOf("林黛玉", "贾宝玉", "陆城", "苏晚", "", "  ", "A", "很长很长的角色名字", "🙂")
        for (count in listOf(1, 2, 5, 187, 804)) {
            for (name in names) {
                val sid = neuralSpeakerId(name, count)
                assertTrue(sid in 0 until count, "「$name」/$count → $sid 越界或为负")
            }
        }
    }

    @Test
    fun `speaker id is deterministic`() {
        val first = neuralSpeakerId("林黛玉", 804)
        repeat(5) { assertEquals(first, neuralSpeakerId("林黛玉", 804)) }
        assertEquals(neuralSpeakerId("林黛玉", 804), neuralSpeakerId("  林黛玉  ", 804))
    }

    @Test
    fun `degenerate inputs collapse to zero instead of crashing`() {
        assertEquals(0, neuralSpeakerId("林黛玉", 0))
        assertEquals(0, neuralSpeakerId("林黛玉", -3))
        assertEquals(0, neuralSpeakerId("", 804))
        assertEquals(emptyMap(), assignNeuralSpeakers(emptyList(), 804))
        assertEquals(emptyMap(), assignNeuralSpeakers(listOf("", "  "), 804))
        assertEquals(mapOf("林黛玉" to 0), assignNeuralSpeakers(listOf("林黛玉"), 0))
    }

    @Test
    fun `a realistic cast gets pairwise distinct speakers`() {
        val cast = listOf("林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "李慕白", "陆城", "苏晚", "顾青")
        val assigned = assignNeuralSpeakers(cast, 804)
        assertEquals(cast.size, assigned.size)
        val sids = assigned.values.toList()
        assertEquals(sids.size, sids.toSet().size, "同 cast 内出现了重复音色：$assigned")
    }

    @Test
    fun `assignment ignores the order characters appear in`() {
        val cast = listOf("林黛玉", "陆城", "苏晚")
        val forward = assignNeuralSpeakers(cast, 804)
        val backward = assignNeuralSpeakers(cast.reversed(), 804)
        assertEquals(forward, backward, "出场顺序不该影响音色分配")
    }

    @Test
    fun `uncontested characters keep their name derived speaker`() {
        // 实测事实：这个 8 人 cast 在 804 个音色下**真的会撞车**（顾青的首选 541 被别人占了）。
        // 所以「一个都不许挪」是错的期望；正确的性质是：
        // **首选编号没有被别人占用的角色，不该被牵连挪位。**
        val cast = listOf("林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "李慕白", "陆城", "苏晚", "顾青")
        val preferred = cast.associateWith { neuralSpeakerId(it, 804) }
        val contested = preferred.values.groupBy { it }.filterValues { it.size > 1 }.keys

        val assigned = assignNeuralSpeakers(cast, 804)

        for (name in cast) {
            val preference = preferred.getValue(name)
            if (preference !in contested) {
                assertEquals(preference, assigned[name], "「$name」的首选编号没人占用，不该挪位")
            }
        }
        assertEquals(cast.size, assigned.values.toSet().size, "撞车必须被解决")
    }

    @Test
    fun `only the colliding characters are moved`() {
        // 构造一次必然的撞车：音色总数压到 4，取两个首选相同的名字 + 一个无关的旁观者
        val pool = listOf("甲一", "乙二", "丙三", "丁四", "戊五", "己六", "庚七", "辛八")
        val speakerCount = 4
        val colliding = pool.groupBy { neuralSpeakerId(it, speakerCount) }
            .values.first { it.size >= 2 }
            .sorted()
        val conflictingSids = colliding.take(2).map { neuralSpeakerId(it, speakerCount) }.toSet()
        val bystander = pool.firstOrNull {
            it !in colliding && neuralSpeakerId(it, speakerCount) !in conflictingSids
        }
        assertNotNull(bystander, "构造不出旁观者，用例前提不成立")

        val assigned = assignNeuralSpeakers(listOf(colliding[0], colliding[1], bystander), speakerCount)

        assertEquals(3, assigned.values.toSet().size, "撞车没有被解决：$assigned")
        assertEquals(
            neuralSpeakerId(colliding[0], speakerCount),
            assigned[colliding[0]],
            "排序后第一个冲突者应保留自己的首选编号",
        )
        assertNotEquals(assigned[colliding[0]], assigned[colliding[1]], "第二个冲突者应被挪开")
        assertEquals(
            neuralSpeakerId(bystander, speakerCount),
            assigned[bystander],
            "没冲突的角色不该被牵连挪位",
        )
    }

    @Test
    fun `cast larger than the speaker set uses every speaker and does not hang`() {
        // 角色比音色多时无法保证互不相同（否则会死循环）；此时的正确行为是**把音色用满**。
        val cast = listOf("甲一", "乙二", "丙三", "丁四", "戊五", "己六", "庚七", "辛八")
        val assigned = assignNeuralSpeakers(cast, 4)
        assertEquals(cast.size, assigned.size, "每个角色都要有编号，哪怕会重复")
        assertEquals(4, assigned.values.toSet().size, "应把 4 个可用音色全部用上")
    }

    @Test
    fun `fallback to default model yields a usable speaker count`() {
        // 旧 id 下线后回落到默认模型，说话人数量必须仍然支持当前 cast（不会因为回落而全部撞到 0）
        val fallback = NeuralVoiceCatalog.resolveOrDefault("已下线")
        val cast = listOf("林黛玉", "贾宝玉", "陆城")
        val assigned = assignNeuralSpeakers(cast, fallback.speakerCount)
        assertEquals(cast.size, assigned.values.toSet().size)
        assertNotEquals(0, assigned.values.sum(), "不该全部落到 0 号音色")
    }
}
