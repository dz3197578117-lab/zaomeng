package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NovelChapterIndexServiceTest {

    private data class Fixture(
        val dir: java.nio.file.Path,
        val storage: StorageService,
        val manifest: JsonObject,
        val runId: String,
    )

    private fun fixture(text: String): Fixture {
        val dir = createTempDirectory("zaomeng-novel-chapters")
        val storage = StorageService(dir.toString().toPath())
        val runId = "run-chapters"
        val runDir = storage.getRunDirectory(runId)
        storage.mkdirs(runDir)
        val source = runDir / "novel.txt"
        storage.writeTextAtomically(source, text)
        val manifest = buildJsonObject {
            put("run_id", runId)
            put("novel_path", source.toString())
        }
        return Fixture(dir, storage, manifest, runId)
    }

    private fun JsonObject.chapters(): List<JsonObject> =
        this["chapters"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    @Test
    fun `splits chinese chapter headings and exposes excerpt`() {
        val body = "风雪压城，两个人影一前一后走在长街上，谁也没有先开口说话。".repeat(20)
        val text = buildString {
            append("第1章 风雪夜\n").append(body).append("\n")
            append("第2章 山神庙\n").append(body).append("\n")
            append("第3章 归人\n").append(body).append("\n")
        }
        val f = fixture(text)
        try {
            val service = NovelChapterIndexService(f.storage)
            val payload = service.list(f.manifest)

            assertTrue(payload["detected"]?.jsonPrimitive?.booleanOrNull == true)
            val chapters = payload.chapters()
            assertEquals(3, chapters.size)
            assertEquals(
                listOf("第1章 风雪夜", "第2章 山神庙", "第3章 归人"),
                chapters.map { it["title"]?.jsonPrimitive?.contentOrNull },
            )
            assertEquals(listOf(1, 2, 3), chapters.map { it["index"]?.jsonPrimitive?.intOrNull })

            // 章节切片必须首尾相接且覆盖全文，否则「从某一章开始」会漏掉原文。
            assertEquals(0, chapters.first()["start_char"]?.jsonPrimitive?.intOrNull)
            assertEquals(text.length, chapters.last()["end_char"]?.jsonPrimitive?.intOrNull)
            chapters.zipWithNext().forEach { (current, next) ->
                assertEquals(
                    current["end_char"]?.jsonPrimitive?.intOrNull,
                    next["start_char"]?.jsonPrimitive?.intOrNull,
                )
            }
            chapters.forEach { chapter ->
                assertTrue(
                    (chapter["char_count"]?.jsonPrimitive?.intOrNull ?: 0) >= 300,
                    "chapter ${chapter["index"]} should keep a meaningful body",
                )
            }

            val excerpt = service.excerpt(f.manifest, 2)
            assertNotNull(excerpt)
            assertEquals(2, excerpt.index)
            assertEquals("第2章 山神庙", excerpt.title)
            assertTrue(excerpt.text.startsWith("第2章 山神庙"))
            assertTrue(excerpt.text.contains("风雪压城"))

            // 章号不存在或为 0（从开头开始）时不返回片段，开场走普通分支。
            assertEquals(null, service.excerpt(f.manifest, 0))
            assertEquals(null, service.excerpt(f.manifest, 99))
        } finally {
            f.dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `falls back to fixed chunks when no chapter heading exists`() {
        val text = "他抬头看了一眼天色，继续往前走。".repeat(400)
        val f = fixture(text)
        try {
            val service = NovelChapterIndexService(f.storage)
            val payload = service.list(f.manifest)

            assertFalse(payload["detected"]?.jsonPrimitive?.booleanOrNull == true)
            val chapters = payload.chapters()
            assertTrue(chapters.size >= 2, "long un-chaptered text should still be splittable")

            // 回退分块同样要首尾相接：否则用户选「第 N 段」时会漏掉中间的原文。
            assertEquals(0, chapters.first()["start_char"]?.jsonPrimitive?.intOrNull)
            assertEquals(text.length, chapters.last()["end_char"]?.jsonPrimitive?.intOrNull)
            chapters.zipWithNext().forEach { (current, next) ->
                assertEquals(
                    current["end_char"]?.jsonPrimitive?.intOrNull,
                    next["start_char"]?.jsonPrimitive?.intOrNull,
                )
            }
            assertNotNull(service.excerpt(f.manifest, 1))
        } finally {
            f.dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `chapter cache follows the source file signature`() {
        val body = "月色落在瓦上，像一层薄霜。".repeat(40)
        val first = buildString {
            append("第一章 起\n").append(body).append("\n")
            append("第二章 承\n").append(body).append("\n")
        }
        val f = fixture(first)
        try {
            val service = NovelChapterIndexService(f.storage)
            assertEquals(2, service.list(f.manifest).chapters().size)

            // 续写原文后（追加第三章）缓存必须失效，章节数随之增加。
            val extended = buildString {
                append(first).append("\n")
                append("第三章 转\n").append(body).append("\n")
            }
            f.storage.writeTextAtomically(f.storage.getRunDirectory(f.runId) / "novel.txt", extended)
            assertEquals(3, service.list(f.manifest).chapters().size)

            // force 重建与缓存命中结果一致。
            assertEquals(3, service.list(f.manifest, force = true).chapters().size)
            assertNotNull(service.chapterDetail(f.manifest, 3))
        } finally {
            f.dir.toFile().deleteRecursively()
        }
    }
}
