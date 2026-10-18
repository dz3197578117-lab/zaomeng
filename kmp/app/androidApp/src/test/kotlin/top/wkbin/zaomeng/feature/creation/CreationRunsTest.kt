package top.wkbin.zaomeng.feature.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.BetaFeatureDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.feature.rundetail.RunNextActionTarget
import top.wkbin.zaomeng.feature.rundetail.nextActionFor

class CreationRunsTest {
    @Test
    fun `books that are not distilled yet can still be written`() {
        val runs = selectCreationRuns(
            listOf(
                RunManifestDto(runId = "ready", status = "ready"),
                RunManifestDto(runId = "pending", status = "pending"),
                RunManifestDto(runId = "running", status = "running"),
                RunManifestDto(runId = "failed", status = "failed"),
                RunManifestDto(runId = "stopped", status = "stopped"),
            ),
        )

        assertEquals(
            setOf("ready", "pending", "running", "failed", "stopped"),
            runs.map(RunManifestDto::runId).toSet(),
        )
        assertEquals(5, runs.size)
    }

    @Test
    fun `ready books come first and the rest follow updated time`() {
        val runs = selectCreationRuns(
            listOf(
                RunManifestDto(runId = "old-ready", status = "ready", updatedAt = "2026-01-01T00:00:00"),
                RunManifestDto(runId = "new-draft", status = "pending", updatedAt = "2026-03-01T00:00:00"),
                RunManifestDto(runId = "new-ready", status = "ready", updatedAt = "2026-02-01T00:00:00"),
                RunManifestDto(runId = "old-draft", status = "failed", updatedAt = "2026-01-15T00:00:00"),
            ),
        )

        assertEquals(listOf("new-ready", "old-ready", "new-draft", "old-draft"), runs.map(RunManifestDto::runId))
    }

    @Test
    fun `crossover spaces are left out of single book writing`() {
        val runs = selectCreationRuns(
            listOf(
                RunManifestDto(runId = "book", status = "ready"),
                RunManifestDto(
                    runId = "crossover",
                    status = "ready",
                    betaFeature = BetaFeatureDto(kind = "cross_book_crossover"),
                ),
            ),
        )

        assertEquals(listOf("book"), runs.map(RunManifestDto::runId))
    }

    @Test
    fun `original book goes straight to the chapter workbench`() {
        val action = nextActionFor(
            RunManifestDto(
                runId = "original",
                status = "ready",
                origin = RunManifestDto.ORIGIN_ORIGINAL,
            ),
        )

        assertEquals(RunNextActionTarget.OpenChapters, action.target)
        assertEquals("开始写", action.label)
    }

    @Test
    fun `status hint explains missing context without blocking`() {
        assertEquals("", creationStatusHint("ready"))
        assertTrue(creationStatusHint("pending").contains("未蒸馏"))
        assertTrue(creationStatusHint("running").contains("蒸馏中"))
        assertTrue(creationStatusHint("failed").contains("仍可创作"))
    }

    @Test
    fun `original books say they need no distillation`() {
        assertTrue(creationStatusHint("ready", isOriginal = true).contains("不需要蒸馏"))
    }

    @Test
    fun `seed keeps the book name and lead for later prompts`() {
        val seed = originalBookSeed("长夜", "沈砚")

        assertTrue(seed.contains("《长夜》"))
        assertTrue(seed.contains("主角：沈砚"))
    }

    @Test
    fun `file name drops separators that would break the run directory`() {
        assertEquals("长夜 第一部", safeNovelFileName("长夜/第一部"))
        assertEquals("未命名书卷", safeNovelFileName("   "))
    }
}
