package top.wkbin.zaomeng.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内置模型清单的不变量。
 *
 * 这些断言不联网，但它们钉住的每一条都是**实际踩过的坑**：
 * 文件名的**大小写不统一**（`Qwen3-1.7B-Q4_K_M.gguf` vs `qwen2.5-3b-instruct-q4_k_m.gguf`）、
 * 仓库下可能根本没有那个量化档、以及「来源」字段写一半导致界面上看不出能力依据。
 * 真正的仓库/文件是否存在只能靠 HF API 校验（清单注释里写了怎么查）。
 */
class OnDeviceModelPresetsTest {

    @Test
    fun `every preset names a plausible gguf file`() {
        assertTrue(OnDeviceModelPresets.all.isNotEmpty(), "清单不该为空")
        OnDeviceModelPresets.all.forEach { preset ->
            assertTrue(
                preset.fileName.endsWith(".gguf"),
                "${preset.id}: 文件名必须以 .gguf 结尾，实际 ${preset.fileName}",
            )
            assertFalse(
                preset.fileName.contains('/') || preset.fileName.contains('\\'),
                "${preset.id}: 文件名不能带路径分隔符（服务端会按名字落盘），实际 ${preset.fileName}",
            )
            assertTrue(preset.sizeBytes > 0, "${preset.id}: 体积必须是实读到的正数")
            assertTrue(preset.note.isNotBlank(), "${preset.id}: 缺体积/内存说明")
        }
    }

    @Test
    fun `preset ids are unique and repositories are owner slash name`() {
        val ids = OnDeviceModelPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "预设 id 必须唯一：$ids")
        OnDeviceModelPresets.all.forEach { preset ->
            assertEquals(
                1,
                preset.repo.count { it == '/' },
                "${preset.id}: 仓库应为 owner/name 形式，实际 ${preset.repo}",
            )
            assertFalse(preset.repo.startsWith("/") || preset.repo.endsWith("/"), "${preset.id}: 仓库名首尾不该有斜杠")
        }
    }

    @Test
    fun `download url follows the resolve main shape on every endpoint`() {
        OnDeviceModelPresets.endpoints.forEach { endpoint ->
            OnDeviceModelPresets.all.forEach { preset ->
                val url = preset.urlFor(endpoint)
                val expected = "${endpoint.baseUrl}/${preset.repo}/resolve/main/${preset.fileName}"
                assertEquals(expected, url, "${preset.id}@${endpoint.id}: 直链形状不对")
                // 换源只换主机名——这正是清单存「仓库 + 文件名」而不是整条 URL 的原因。
                assertEquals(
                    preset.urlFor(OnDeviceModelPresets.endpoints.first()).substringAfter("://").substringAfter('/'),
                    url.substringAfter("://").substringAfter('/'),
                    "${preset.id}: 换源后除主机名外的部分应当一致",
                )
            }
        }
    }

    @Test
    fun `endpoint base urls are https without trailing slash`() {
        OnDeviceModelPresets.endpoints.forEach { endpoint ->
            assertTrue(endpoint.baseUrl.startsWith("https://"), "${endpoint.id}: 下载源必须是 https")
            assertFalse(endpoint.baseUrl.endsWith("/"), "${endpoint.id}: baseUrl 不该带尾斜杠")
            assertTrue(endpoint.label.isNotBlank() && endpoint.note.isNotBlank(), "${endpoint.id}: 缺标签或说明")
        }
    }

    @Test
    fun `default endpoint id must exist`() {
        // 写错一个字母就会静默回落到第一个源，用户看到的默认源就和这里声明的不一致了。
        assertTrue(
            OnDeviceModelPresets.endpoints.any { it.id == OnDeviceModelPresets.defaultEndpointId },
            "defaultEndpointId=${OnDeviceModelPresets.defaultEndpointId} 不在 endpoints 里",
        )
    }

    @Test
    fun `unknown endpoint id falls back instead of throwing`() {
        val endpoint = OnDeviceModelPresets.endpointById("no-such-endpoint")
        assertEquals(OnDeviceModelPresets.endpoints.first(), endpoint)
    }

    @Test
    fun `every preset carries capability provenance`() {
        OnDeviceModelPresets.all.forEach { preset ->
            val p = preset.provenance
            assertTrue(p.publisher.isNotBlank(), "${preset.id}: 缺发布者")
            assertTrue(p.baseModel.contains('/'), "${preset.id}: 上游基座应是 owner/name，实际 ${p.baseModel}")
            assertTrue(p.metadataReadAt.isNotBlank(), "${preset.id}: 缺元数据读取日期——来源必须有时效")
            assertFalse(p.repoUpdatedAt.isBlank(), "${preset.id}: 缺仓库更新时间")
            assertTrue(p.likes >= 0, "${preset.id}: likes 不该为负")
            // 官方标志必须与发布者一致：Qwen 组织的仓库才是官方发布。
            assertEquals(
                p.publisher == "Qwen",
                p.official,
                "${preset.id}: official 与 publisher 不一致（${p.publisher} / official=${p.official}）",
            )
            assertTrue(
                p.baseModelCardUrl().startsWith("https://huggingface.co/"),
                "${preset.id}: 模型卡地址应以官方站为基准",
            )
        }
    }

    @Test
    fun `provenance summary mentions publisher upstream and read date`() {
        OnDeviceModelPresets.all.forEach { preset ->
            val summary = preset.provenance.summary()
            assertTrue(summary.contains(preset.provenance.publisher), "${preset.id}: 来源该写发布者")
            assertTrue(summary.contains(preset.provenance.baseModel), "${preset.id}: 来源该写上游基座")
            assertTrue(summary.contains(preset.provenance.metadataReadAt), "${preset.id}: 来源该写元数据读取日期")
        }
    }

    @Test
    fun `byId finds known presets and returns null for unknown`() {
        OnDeviceModelPresets.all.forEach { preset ->
            assertEquals(preset, OnDeviceModelPresets.byId(preset.id))
        }
        assertEquals(null, OnDeviceModelPresets.byId("does-not-exist"))
        assertNotNull(OnDeviceModelPresets.byId(OnDeviceModelPresets.all.first().id))
    }
}
