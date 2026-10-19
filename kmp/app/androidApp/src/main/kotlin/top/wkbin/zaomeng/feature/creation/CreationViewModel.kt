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
import top.wkbin.zaomeng.feature.chapters.outlineChapterTitle

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
    /**
     * 「就按这个写」已经建好的书卷 id。
     *
     * 建卷与写章节是两次落盘；中途失败时保留它，重试只走「补写剩余章节」，
     * 不会在书架上留下第二本同名书。新方案/清空方案时一律清零。
     */
    val planRunId: String = "",
    /** 该书卷已按大纲写入的章数（配合 [planRunId] 做断点续写）。 */
    val planWrittenChapters: Int = 0,
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
            // 新方案 = 新的一本书：上一份方案留下的建卷/续写断点必须清零，
            // 否则新方案的章节会被补写进旧书里。
            mutableState.update {
                it.copy(
                    planning = true,
                    planError = "",
                    plan = null,
                    planRunId = "",
                    planWrittenChapters = 0,
                )
            }
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

    /**
     * 丢掉当前方案（「改描述，重新生成」/「先不写」都要走这里）。
     *
     * 连建卷断点一起清：已建好的书还在书架与 [CreationUiState.runs] 里，
     * 下次生成的是**另一本**书，不能把新大纲补写进旧书。
     */
    fun clearPlan() {
        mutableState.update {
            it.copy(plan = null, planError = "", planRunId = "", planWrittenChapters = 0)
        }
    }

    /**
     * 用户确认方案后，建卷 → 保存核心/设定/类型 → 逐章写入大纲。
     *
     * 建卷与写章节是两次落盘，中间任何一步失败都会留下「书已建好、章节只写了一半」的状态。
     * 所以建卷成功就立刻把 [CreationUiState.planRunId] 记下、并把书放进 [CreationUiState.runs]，
     * 之后重试只补写缺失的设定与剩余章节——不会再 createNovel 出第二本同名书。
     */
    fun createFromPlan(onCreated: (String) -> Unit) {
        val plan = state.value.plan ?: return
        if (state.value.creatingFromPlan) return
        viewModelScope.launch {
            val resumedRunId = state.value.planRunId
            var written = state.value.planWrittenChapters
            mutableState.update { it.copy(creatingFromPlan = true, planError = "") }
            try {
                val runId = if (resumedRunId.isNotBlank()) {
                    resumedRunId
                } else {
                    val seed = buildString {
                        append("《").append(plan.title).append("》\n")
                        append("主角：").append(plan.lead).append("\n\n")
                        if (plan.premise.isNotBlank()) append(plan.premise).append("\n\n")
                        append("这是一本原创书卷，分章大纲已生成，可以直接开始写。")
                    }
                    val created = runs.createNovel(
                        filename = "${safeNovelFileName(plan.title)}.md",
                        bytes = seed.encodeToByteArray(),
                        characters = listOf(plan.lead),
                        maxSentences = 120,
                        maxChars = 50_000,
                        autoRun = false,
                        origin = RunManifestDto.ORIGIN_ORIGINAL,
                    )
                    // 书卷此刻已经真实落盘：先记 id、先放进列表，
                    // 后面章节写失败时重试才不会又建一本同名的。
                    mutableState.update { current ->
                        current.copy(
                            planRunId = created.runId,
                            runs = (listOf(created.copy(novelName = plan.title)) + current.runs)
                                .distinctBy(RunManifestDto::runId),
                        )
                    }
                    created.runId
                }

                val settingFailures = savePlanSettings(runId, plan)
                if (settingFailures.isNotEmpty()) {
                    // 设定缺一份就不往下写：章节一旦生成就要花额度重来，
                    // 而这几份设定都是整份覆盖，重试代价几乎为零。
                    failPlanCreation(plan, written, settingFailures, cause = null)
                    return@launch
                }

                for (item in plan.outline.drop(written)) {
                    chapters.saveChapter(
                        runId,
                        "",
                        SaveChapterRequest(
                            title = outlineChapterTitle(item, written + 1),
                            goal = item.goal,
                            perspective = "第三人称限知",
                        ),
                    )
                    written++
                }
                mutableState.update { current ->
                    current.copy(
                        creatingFromPlan = false,
                        plan = null,
                        planRunId = "",
                        planWrittenChapters = 0,
                    )
                }
                onCreated(runId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failPlanCreation(plan, written, emptyList(), error.message)
            }
        }
    }

    /**
     * 「就按这个写」失败时的收尾：方案与已建好的书卷都保留，只把进度与原因写进
     * [CreationUiState.planError]，让人能就地重试（重试是幂等的：只补写缺失部分）。
     */
    private fun failPlanCreation(
        plan: NovelPlanDto,
        written: Int,
        settingFailures: List<String>,
        cause: String?,
    ) {
        mutableState.update {
            it.copy(
                creatingFromPlan = false,
                planWrittenChapters = written,
                planError = planCreationFailureText(
                    bookTitle = plan.title,
                    bookCreated = it.planRunId.isNotBlank(),
                    writtenChapters = written,
                    totalChapters = plan.outline.size,
                    settingFailures = settingFailures,
                    cause = cause,
                ),
            )
        }
    }

    /**
     * 把方案的设定写进书卷：故事核心、设定集、写作类型。
     *
     * 三份都是整份覆盖，重试再写一遍是安全的；失败**不再**被静默吞掉——
     * 否则「书建成了却没有设定」，之后生成的大纲与正文都会缺上下文，用户却毫不知情。
     */
    private suspend fun savePlanSettings(runId: String, plan: NovelPlanDto): List<String> {
        val failures = mutableListOf<String>()
        if (!plan.core.isEmpty) {
            try {
                chapters.saveStoryCore(runId, plan.core)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += "故事核心没存上（${error.message ?: "未知错误"}）"
            }
        }
        if (!plan.bible.isEmpty) {
            try {
                chapters.saveStoryBible(runId, plan.bible)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += "设定集没存上（${error.message ?: "未知错误"}）"
            }
        }
        if (plan.genreId.isNotBlank()) {
            val genre = WritingGenres.byId(plan.genreId)
            try {
                chapters.saveWritingProfile(
                    runId,
                    WritingProfileDto(
                        genre = plan.genreId,
                        chapterLength = genre?.defaultChapterLength ?: 2500,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += "写作类型没存上（${error.message ?: "未知错误"}）"
            }
        }
        return failures
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

/**
 * 「就按这个写」失败时的说明。
 *
 * 必须回答三件事，否则用户只能靠猜：书到底建了没有、大纲写到第几章、
 * 再点一次是续写还是会再建一本。重试之所以安全，是因为
 * [CreationViewModel.createFromPlan] 会在 [CreationUiState.planRunId] 非空时跳过建卷。
 */
internal fun planCreationFailureText(
    bookTitle: String,
    bookCreated: Boolean,
    writtenChapters: Int,
    totalChapters: Int,
    settingFailures: List<String> = emptyList(),
    cause: String? = null,
): String {
    val total = totalChapters.coerceAtLeast(0)
    val written = writtenChapters.coerceIn(0, total)
    val remaining = total - written
    val failureCause = cause?.takeIf(String::isNotBlank) ?: "未知错误"
    if (!bookCreated) {
        return "建书失败：$failureCause。方案还在，可以再点一次「就按这个写」重试。"
    }
    val name = bookTitle.trim().ifBlank { "这本新书" }
    val problems = buildList {
        if (settingFailures.isNotEmpty()) {
            add("有设定没存上：" + settingFailures.joinToString("；"))
        }
        // 只有真的写到章节了才提「第 N 章失败」：设定没存上时一章都还没开始写，
        // 谎报一章失败会把用户引向错误的排查方向。
        if (remaining > 0 && (cause != null || settingFailures.isEmpty())) {
            add("第 ${written + 1} 章写入失败：$failureCause")
        }
    }
    return buildString {
        append("《").append(name).append("》已经建好（不会再建第二本），但")
        append(problems.joinToString("；").ifBlank { "还有内容没写完" })
        append("。已写入 ").append(written).append(" 章 / ").append(total).append(" 章")
        if (remaining > 0) append("，还剩 ").append(remaining).append(" 章")
        append("；再点「就按这个写」只补写缺失的部分，也不会再建一本。")
        append("也可以关掉这里，从创作页直接打开这本书继续写。")
    }
}
