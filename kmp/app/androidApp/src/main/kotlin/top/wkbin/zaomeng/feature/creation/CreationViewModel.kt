package top.wkbin.zaomeng.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.NovelPlanDto
import top.wkbin.zaomeng.data.api.NovelPlanRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.WritingGenres
import top.wkbin.zaomeng.data.api.WritingProfileDto

data class CreationUiState(
    val loading: Boolean = true,
    /** 可进入创作的书卷，`ready` 的排在前面。 */
    val runs: List<RunManifestDto> = emptyList(),
    val creating: Boolean = false,
    val error: String = "",
    /** 新建原创书卷的失败原因；非空时由新建弹层展示。 */
    val createError: String = "",
    /** 一句话生成：用户描述。 */
    val planPrompt: String = "",
    val planning: Boolean = false,
    val plan: NovelPlanDto? = null,
    val planError: String = "",
    val creatingFromPlan: Boolean = false,
)

/**
 * 「创作」顶级页的数据源。
 *
 * 创作并不要求书卷已经蒸馏完：大纲与正文只依赖「设定 + 前文」，
 * 人物档案只是加分项。所以这里列出所有书卷（跨书卷共演除外），
 * 蒸馏中的也能先写，只是人物一致性会弱一些。
 */
class CreationViewModel(
    private val runs: RunRepository,
    private val chapters: ChapterRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CreationUiState())
    val state: StateFlow<CreationUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = it.runs.isEmpty(), error = "") }
            try {
                val all = runs.listRuns()
                mutableState.update {
                    it.copy(loading = false, runs = selectCreationRuns(all))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.message ?: "读取书卷失败。")
                }
            }
        }
    }

    /**
     * 新建一本原创书卷：不导入原文、不蒸馏，直接拿到一个可以写章节的 run。
     *
     * 用 `autoRun = false` 建卷，因此不会触发蒸馏，也不要求先配置模型；
     * 真正生成大纲时才会检查模型。
     */
    fun createOriginalBook(
        title: String,
        lead: String,
        onCreated: (String) -> Unit,
    ) {
        if (mutableState.value.creating) return
        val cleanTitle = title.trim()
        val cleanLead = lead.trim()
        val problem = when {
            cleanTitle.isEmpty() -> "给这本书起个名字。"
            cleanTitle.length > 60 -> "书名请控制在 60 字以内。"
            cleanLead.isEmpty() -> "先写下主角的名字。"
            cleanLead.length > 20 -> "主角名请控制在 20 字以内。"
            else -> ""
        }
        if (problem.isNotEmpty()) {
            mutableState.update { it.copy(createError = problem) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(creating = true, createError = "") }
            try {
                val seed = originalBookSeed(cleanTitle, cleanLead)
                val run = runs.createNovel(
                    filename = "${safeNovelFileName(cleanTitle)}.md",
                    bytes = seed.encodeToByteArray(),
                    characters = listOf(cleanLead),
                    maxSentences = 120,
                    maxChars = 50_000,
                    autoRun = false,
                    origin = RunManifestDto.ORIGIN_ORIGINAL,
                )
                mutableState.update { current ->
                    // 创建响应里的 novel_name 带扩展名，先按去掉扩展名的书名展示；
                    // 下次 load() 会从 manifest 的 source_name 重新取值。
                    val displayed = run.copy(novelName = cleanTitle)
                    current.copy(
                        creating = false,
                        runs = (listOf(displayed) + current.runs).distinctBy(RunManifestDto::runId),
                    )
                }
                onCreated(run.runId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(creating = false, createError = error.message ?: "新建书卷失败，请重试。")
                }
            }
        }
    }

    fun clearCreateError() {
        mutableState.update { it.copy(createError = "") }
    }

    // ===== 一句话生成小说 =====

    fun updatePlanPrompt(value: String) {
        mutableState.update { it.copy(planPrompt = value.take(1200), planError = "") }
    }

    fun planNovel(chapterCount: Int = 8) {
        val current = state.value
        if (current.planning) return
        val prompt = current.planPrompt.trim()
        if (prompt.length < 4) {
            mutableState.update { it.copy(planError = "先用一句话说说你想写什么。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(planning = true, planError = "", plan = null) }
            try {
                val plan = chapters.planNovel(NovelPlanRequest(prompt, chapterCount.coerceIn(3, 15)))
                mutableState.update { it.copy(planning = false, plan = plan) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(planning = false, planError = error.message ?: "生成方案失败。")
                }
            }
        }
    }

    fun clearPlan() {
        mutableState.update { it.copy(plan = null, planError = "") }
    }

    /** 用户确认方案后，建卷 → 保存核心/设定/类型 → 写入章节。 */
    fun createFromPlan(onCreated: (String) -> Unit) {
        val plan = state.value.plan ?: return
        if (state.value.creatingFromPlan) return
        viewModelScope.launch {
            mutableState.update { it.copy(creatingFromPlan = true, planError = "") }
            try {
                val seed = buildString {
                    append("《").append(plan.title).append("》\n")
                    append("主角：").append(plan.lead).append("\n\n")
                    if (plan.premise.isNotBlank()) append(plan.premise).append("\n\n")
                    append("这是一本原创书卷，分章大纲已生成，可以直接开始写。")
                }
                val run = runs.createNovel(
                    filename = "${safeNovelFileName(plan.title)}.md",
                    bytes = seed.encodeToByteArray(),
                    characters = listOf(plan.lead),
                    maxSentences = 120,
                    maxChars = 50_000,
                    autoRun = false,
                    origin = RunManifestDto.ORIGIN_ORIGINAL,
                )
                if (!plan.core.isEmpty) {
                    runCatching { chapters.saveStoryCore(run.runId, plan.core) }
                }
                if (!plan.bible.isEmpty) {
                    runCatching { chapters.saveStoryBible(run.runId, plan.bible) }
                }
                if (plan.genreId.isNotBlank()) {
                    val genre = WritingGenres.byId(plan.genreId)
                    runCatching {
                        chapters.saveWritingProfile(
                            run.runId,
                            WritingProfileDto(
                                genre = plan.genreId,
                                chapterLength = genre?.defaultChapterLength ?: 2500,
                            ),
                        )
                    }
                }
                plan.outline.forEachIndexed { index, item ->
                    chapters.saveChapter(
                        run.runId,
                        "",
                        SaveChapterRequest(
                            title = item.title.ifBlank { "第 ${index + 1} 章" },
                            goal = item.goal,
                            perspective = "第三人称限知",
                        ),
                    )
                }
                mutableState.update { current ->
                    val displayed = run.copy(novelName = plan.title)
                    current.copy(
                        creatingFromPlan = false,
                        plan = null,
                        runs = (listOf(displayed) + current.runs).distinctBy(RunManifestDto::runId),
                    )
                }
                onCreated(run.runId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(creatingFromPlan = false, planError = error.message ?: "按方案建书失败。")
                }
            }
        }
    }
}

/**
 * 创作页的书卷筛选：跨书卷共演没有自己的原文，不参与单书创作；
 * 其余状态一律保留——没蒸馏完也能先写。
 */
internal fun selectCreationRuns(all: List<RunManifestDto>): List<RunManifestDto> =
    all.asSequence()
        .filter { it.betaFeature == null }
        .sortedWith(
            compareByDescending<RunManifestDto> { it.status == "ready" }
                .thenByDescending { it.updatedAt },
        )
        .toList()

/** 原创书卷的起始正文：只放书名与主角，避免被误当成可蒸馏的原文。 */
internal fun originalBookSeed(title: String, lead: String): String = buildString {
    append("《").append(title).append("》\n")
    append("主角：").append(lead).append("\n\n")
    append("这是一本原创书卷，还没有正文。请到「创作」里写一句设定，先生成分章大纲。")
}

internal fun safeNovelFileName(title: String): String =
    title.replace(Regex("""[\\/\r\n\t]"""), " ").trim().take(60).ifBlank { "未命名书卷" }
