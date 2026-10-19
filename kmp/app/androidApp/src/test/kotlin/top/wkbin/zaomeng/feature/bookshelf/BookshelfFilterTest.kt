package top.wkbin.zaomeng.feature.bookshelf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.RunManifestDto

class BookshelfFilterTest {
    private val runs = listOf(
        run("ready", "红楼梦.txt", "林黛玉"),
        run("running", "西游记.txt", "孙悟空"),
        run("failed", "水浒传.txt", "林冲"),
    )

    @Test
    fun `search matches a character name`() {
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = runs, searchQuery = "悟空"),
        )

        assertEquals(listOf("running"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `needs attention includes failed and stopped runs`() {
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = runs, filter = BookshelfFilter.NeedsAttention),
        )

        assertEquals(listOf("failed"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `title sort is alphabetical by book title`() {
        val titledRuns = listOf(
            run("gamma", "Gamma.txt", "甲"),
            run("alpha", "Alpha.txt", "乙"),
            run("beta", "Beta.txt", "丙"),
        )
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = titledRuns, sort = BookshelfSort.Title),
        )

        assertEquals(listOf("alpha", "beta", "gamma"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `draft filter also matches pending runs`() {
        // 书架卡片对 pending 与 draft 都显示「待蒸馏」，筛选必须与标签一致。
        val mixedRuns = listOf(
            run("pending", "甲传.txt", "甲"),
            run("draft", "乙传.txt", "乙"),
            run("ready", "丙传.txt", "丙"),
        )
        val result = filterBookshelfRuns(
            BookshelfUiState(runs = mixedRuns, filter = BookshelfFilter.Draft),
        )

        assertEquals(listOf("pending", "draft"), result.map(RunManifestDto::runId))
    }

    @Test
    fun `clearing search and filter restores every run`() {
        val state = BookshelfUiState(runs = runs, searchQuery = "找不到的词")

        assertTrue(filterBookshelfRuns(state).isEmpty())
        assertTrue(filterBookshelfRuns(state.copy(searchQuery = "")).size == runs.size)
    }

    private fun run(id: String, sourceName: String, character: String): RunManifestDto = RunManifestDto(
        runId = id,
        status = id,
        novelSources = listOf(NovelSourceDto(sourceName = sourceName)),
        lockedCharacters = listOf(character),
    )
}
