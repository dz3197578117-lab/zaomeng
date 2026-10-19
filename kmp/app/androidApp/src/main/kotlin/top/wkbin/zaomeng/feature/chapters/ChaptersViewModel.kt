package top.wkbin.zaomeng.feature.chapters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.ApplyChapterContentRequest
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.ChapterReviewDto
import top.wkbin.zaomeng.data.api.ConsistencyReportDto
import top.wkbin.zaomeng.data.api.DraftVersionDto
import top.wkbin.zaomeng.data.api.DraftVersionsRequest
import top.wkbin.zaomeng.data.api.FinalReviewDto
import top.wkbin.zaomeng.data.api.GenerateSceneCardsRequest
import top.wkbin.zaomeng.data.api.GenerateStoryCoreRequest
import top.wkbin.zaomeng.data.api.RewriteOptionDto
import top.wkbin.zaomeng.data.api.RewriteSelectionRequest
import top.wkbin.zaomeng.data.api.SceneCardDto
import top.wkbin.zaomeng.data.api.StoryCoreDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.GenerateNovelOutlineRequest
import top.wkbin.zaomeng.data.api.GenerateStoryBibleRequest
import top.wkbin.zaomeng.data.api.NovelOutlineChapterDto
import top.wkbin.zaomeng.data.api.RescueOptionDto
import top.wkbin.zaomeng.data.api.RescueRequest
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.StoryBibleDto
import top.wkbin.zaomeng.data.api.WritingProfileDto
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.platformIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer
import okio.use

data class ChaptersUiState(
    val loading: Boolean = true,
    val chapters: List<ChapterDto> = emptyList(),
    val sessions: List<DialogueSessionDto> = emptyList(),
    val saving: Boolean = false,
    val openingChapterId: String = "",
    val syncingChapterId: String = "",
    val exporting: Boolean = false,
    val exported: ExportedChapterManuscript? = null,
    val exportRequestId: Long = 0,
    val navigationSessionId: String = "",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchResultDto> = emptyList(),
    val bookQuestion: String = "",
    val askingBook: Boolean = false,
    val bookAnswer: AskBookResponseDto? = null,
    /** AI 小说创作：正在生成分章大纲。 */
    val generatingOutline: Boolean = false,
    /** AI 小说创作：生成好的大纲提案（尚未写入章节）。 */
    val outline: List<NovelOutlineChapterDto> = emptyList(),
    /** 正在把大纲写入章节。 */
    val creatingFromOutline: Boolean = false,
    /** 正在成稿的章节 id；空串表示没有任务。 */
    val draftingChapterId: String = "",
    /** 大纲写入成功的次数；界面据此收起创作弹层。 */
    val outlineCreationCount: Int = 0,
    /**
     * 这份大纲累计已写入的章数。
     *
     * 写入中途失败时 [outline] 会被裁成还没写的尾部，这里记下已经落盘的部分：
     * 于是「再点一次」只补写剩下的章节，同一份大纲不会出现两套重复章节。
     */
    val outlineWrittenCount: Int = 0,
    // ===== 创作工具：设定 / 类型 / 救援 / 检查 =====
    val storyBible: StoryBibleDto = StoryBibleDto(),
    /** 当前设定是否已落盘；false 表示还是未确认的提案。 */
    val storyBibleSaved: Boolean = true,
    val generatingBible: Boolean = false,
    val savingBible: Boolean = false,
    val writingProfile: WritingProfileDto = WritingProfileDto(),
    val savingProfile: Boolean = false,
    /**
     * 写作助手弹层的**编辑副本**：类型页的五个设置 + 两个生成用的一句话设定。
     *
     * 之前这些都活在弹层里的 `remember` 中，随手点空白关掉弹层或旋屏就全丢，
     * 几百字的文风样本也一起没。现在提到 UiState：关掉弹层再打开、旋屏、切后台回来都还在。
     * `null` 表示「还没开始编辑」，[ChaptersViewModel.loadWritingTools] 打开弹层时会用已保存的
     * 设定播种（见 [writingDraftsSeededFrom]）。保存成功后重新播种，于是「未保存」标记自然清除。
     */
    val writingDrafts: WritingDrafts? = null,
    // ===== 人定核心 =====
    val storyCore: StoryCoreDto = StoryCoreDto(),
    val storyCoreSaved: Boolean = true,
    val generatingCore: Boolean = false,
    val savingCore: Boolean = false,
    // ===== 场景卡与多版本 =====
    /** 扩写面板当前选中的章节。 */
    val expandChapterId: String = "",
    /** 刚生成、还没保存的场景卡；为空时界面回退到章节里已保存的卡。 */
    val sceneCards: List<SceneCardDto> = emptyList(),
    val generatingSceneCards: Boolean = false,
    val savingSceneCards: Boolean = false,
    val draftVersions: List<DraftVersionDto> = emptyList(),
    val draftingVersions: Boolean = false,
    val applyingVersion: String = "",
    // ===== 局部重写 =====
    val rewriteInput: String = "",
    val rewriteInstruction: String = "",
    val rewriteOptions: List<RewriteOptionDto> = emptyList(),
    val rewriting: Boolean = false,
    // ===== 全书检查与终审 =====
    val consistency: ConsistencyReportDto? = null,
    val checkingConsistency: Boolean = false,
    val finalReview: FinalReviewDto? = null,
    val reviewingAll: Boolean = false,
    val finalApproved: Boolean = false,
    val rescueChapterId: String = "",
    val rescueProblem: String = "",
    val rescueKind: String = "plot",
    val rescueOptions: List<RescueOptionDto> = emptyList(),
    val rescuing: Boolean = false,
    val reviewChapterId: String = "",
    val review: ChapterReviewDto? = null,
    val reviewing: Boolean = false,
    val polishingChapterId: String = "",
    val error: String = "",
    val message: String = "",
)

/**
 * 写作助手弹层的编辑副本。
 *
 * 之前这些输入全是弹层里的局部 `remember`，随手点空白关掉就会连同几百字文风样本一起消失，
 * 所以提升成 UiState 里的一份 working copy：持久化仍然走 [WritingProfileDto]，
 * 两者靠 [writingDraftsSeededFrom] / [writingDraftsProfile] 互转。
 *
 * 单独一个类型（而不是直接复用 [WritingProfileDto]）是因为弹层里还有两类
 * **不进 profile** 的输入：生成设定集/核心用的一句话设定，以及设定集页临时选的题材。
 * 它们同样需要活着离开弹层，但不该被当成「改了还没保存的设定」。
 */
data class WritingDrafts(
    // 「类型」页：这五项就是 WritingProfileDto 的五个字段。
    val genre: String = "",
    val chapterLength: Int = 2500,
    val notes: String = "",
    val voiceSample: String = "",
    val avoid: String = "",
    // 「设定 / 核心」页：只用于发起一次生成，不进 profile。
    val biblePremise: String = "",
    val bibleGenreId: String = "",
    val corePremise: String = "",
)

/** 用已保存的设定播种编辑副本；设定集页的题材默认跟随 profile。 */
internal fun writingDraftsSeededFrom(profile: WritingProfileDto): WritingDrafts = WritingDrafts(
    genre = profile.genre,
    chapterLength = profile.chapterLength,
    notes = profile.notes,
    voiceSample = profile.voiceSample,
    avoid = profile.avoid,
    bibleGenreId = profile.genre,
)

/** 编辑副本 → 待保存的 profile（只带会落盘的那五项）。 */
internal fun writingDraftsProfile(drafts: WritingDrafts): WritingProfileDto = WritingProfileDto(
    genre = drafts.genre,
    chapterLength = drafts.chapterLength,
    notes = drafts.notes,
    voiceSample = drafts.voiceSample,
    avoid = drafts.avoid,
)

/**
 * 有没有「改了但还没保存」的类型设置。
 *
 * 只比会落盘的那五项：一句话设定属于一次性的生成输入，草稿留着就不会丢，
 * 拿它去拦「关闭」会变成每次关窗都要点一次确认。
 */
internal fun hasUnsavedWritingDrafts(drafts: WritingDrafts?, profile: WritingProfileDto): Boolean =
    drafts != null && (
        drafts.genre != profile.genre ||
            drafts.chapterLength != profile.chapterLength ||
            drafts.notes != profile.notes ||
            drafts.voiceSample != profile.voiceSample ||
            drafts.avoid != profile.avoid
        )

class ChaptersViewModel(
    private val chapters: ChapterRepository,
    private val sessions: SessionRepository,
    val runId: String,
    private val cacheDir: Path,
    private val novelConversionForeground: NovelConversionForeground,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChaptersUiState())
    val state: StateFlow<ChaptersUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank())
        load()
    }

    fun load() = viewModelScope.launch {
        mutableState.update { it.copy(loading = it.chapters.isEmpty(), error = "") }
        try {
            val chapters = chapters.listChapters(runId)
            val sessions = sessions.listSessions(runId)
            mutableState.update { it.copy(loading = false, chapters = chapters, sessions = sessions) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(loading = false, error = error.message ?: "无法读取章节工作台。") }
        }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value, searchResults = if (value.isBlank()) emptyList() else it.searchResults) }
    }

    fun search() {
        searchFor(state.value.searchQuery)
    }

    fun searchFor(value: String) {
        val query = value.trim()
        if (query.isBlank() || state.value.searching) return
        viewModelScope.launch {
            mutableState.update { it.copy(searchQuery = query, searching = true, error = "") }
            try {
                mutableState.update { it.copy(searching = false, searchResults = chapters.searchRunContent(runId, query)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(searching = false, error = error.message ?: "搜索失败。") }
            }
        }
    }

    fun updateBookQuestion(value: String) {
        mutableState.update { it.copy(bookQuestion = value.take(300), bookAnswer = null) }
    }

    fun askBook() {
        val question = state.value.bookQuestion.trim()
        if (question.isBlank() || state.value.askingBook) return
        viewModelScope.launch {
            mutableState.update { it.copy(askingBook = true, bookAnswer = null, error = "") }
            try {
                val answer = chapters.askBookQuestion(runId, question)
                mutableState.update { it.copy(askingBook = false, bookAnswer = answer) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(askingBook = false, error = error.message ?: "问书卷失败。") }
            }
        }
    }

    /**
     * AI 小说创作第一步：由一句设定生成分章大纲。
     *
     * 结果只放进 [ChaptersUiState.outline]，不落盘——用户点「写入章节」才创建。
     */
    fun generateOutline(
        premise: String,
        chapterCount: Int,
        genre: String,
        perspective: String,
        style: String,
    ) {
        if (state.value.generatingOutline || state.value.creatingFromOutline) return
        val idea = premise.trim()
        if (idea.length < 4) {
            mutableState.update { it.copy(error = "先用一句话写下故事设定（至少 4 个字）。") }
            return
        }
        viewModelScope.launch {
            // 新大纲等于新的写入队列：上一份大纲的断点必须清零，否则会少写前面的章节。
            mutableState.update {
                it.copy(
                    generatingOutline = true,
                    error = "",
                    message = "",
                    outline = emptyList(),
                    outlineWrittenCount = 0,
                )
            }
            try {
                val response = chapters.generateNovelOutline(
                    runId,
                    GenerateNovelOutlineRequest(
                        premise = idea,
                        chapterCount = chapterCount,
                        genre = genre,
                        perspective = perspective,
                        style = style,
                    ),
                )
                mutableState.update {
                    it.copy(
                        generatingOutline = false,
                        outline = response.items,
                        message = if (response.items.isEmpty()) "" else "已生成 ${response.items.size} 章大纲，确认后写入章节。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(generatingOutline = false, error = error.message ?: "生成大纲失败。")
                }
            }
        }
    }

    /** 「重新设定」：连写入断点和上一次的失败原因一起清，弹层回到输入态时不该还挂着旧错误。 */
    fun clearOutline() {
        mutableState.update { it.copy(outline = emptyList(), outlineWrittenCount = 0, error = "") }
    }

    /**
     * 把大纲逐章写入章节列表。
     *
     * 逐章保存而不是批量接口：现有 [saveChapter] 已经是原子的单章写入，
     * 失败时能明确告诉用户「写到了第几章」，已写入的部分也不会回滚成半截数据。
     *
     * 失败后把已落盘的那几章从 [ChaptersUiState.outline] 里摘掉，并把累计章数记进
     * [ChaptersUiState.outlineWrittenCount]；因此「再点一次」只写剩下的尾部，
     * 同一份大纲连点两次不会出现两套重复章节，章号也不会从 1 重新数。
     */
    fun createChaptersFromOutline(perspective: String) {
        val pending = state.value.outline
        if (pending.isEmpty() || state.value.creatingFromOutline) return
        val alreadyWritten = state.value.outlineWrittenCount
        viewModelScope.launch {
            mutableState.update { it.copy(creatingFromOutline = true, error = "", message = "") }
            var created = 0
            try {
                for (item in pending) {
                    chapters.saveChapter(
                        runId,
                        "",
                        SaveChapterRequest(
                            title = outlineChapterTitle(item, alreadyWritten + created + 1),
                            goal = item.goal,
                            content = "",
                            perspective = perspective,
                        ),
                    )
                    created++
                }
                val list = chapters.listChapters(runId)
                val totalWritten = alreadyWritten + created
                mutableState.update {
                    it.copy(
                        creatingFromOutline = false,
                        outline = emptyList(),
                        outlineWrittenCount = 0,
                        chapters = list,
                        outlineCreationCount = it.outlineCreationCount + 1,
                        message = outlineWriteSuccessText(totalWritten),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val progress = outlineWriteProgress(pending, created, alreadyWritten)
                val refreshed = runCatching { chapters.listChapters(runId) }.getOrDefault(state.value.chapters)
                mutableState.update {
                    if (progress.isDone) {
                        // 章节其实都写进去了，失败的只是最后那次重读列表：
                        // 按「已写完」收尾，否则界面会停在「还剩 0 章」的待写状态上骗人再点一次。
                        it.copy(
                            creatingFromOutline = false,
                            outline = emptyList(),
                            outlineWrittenCount = 0,
                            chapters = refreshed,
                            outlineCreationCount = it.outlineCreationCount + 1,
                            message = outlineWriteSuccessText(progress.written),
                        )
                    } else {
                        it.copy(
                            creatingFromOutline = false,
                            chapters = refreshed,
                            outline = progress.remaining,
                            outlineWrittenCount = progress.written,
                            error = outlineWriteFailureText(progress, error.message),
                        )
                    }
                }
            }
        }
    }

    /** AI 小说创作第二步：为指定章节生成正文。会覆盖该章已有正文。 */
    fun draftChapter(chapterId: String) {
        if (chapterId.isBlank() || state.value.draftingChapterId.isNotBlank() || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(draftingChapterId = chapterId, error = "", message = "") }
            try {
                val chapter = chapters.draftChapter(runId, chapterId)
                mutableState.update { current ->
                    current.copy(
                        draftingChapterId = "",
                        chapters = current.chapters.map { if (it.chapterId == chapter.chapterId) chapter else it },
                        message = "已生成《${chapter.title}》正文，可继续编辑或点「AI 成稿」重写。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(draftingChapterId = "", error = error.message ?: "生成正文失败。")
                }
            }
        }
    }

    // ===== 创作工具 =====

    /** 打开创作工具时读取设定集与类型偏好；文件不存在时返回空设定，不打扰用户。 */
    fun loadWritingTools() {
        viewModelScope.launch {
            try {
                val bible = chapters.readStoryBible(runId)
                val profile = chapters.readWritingProfile(runId)
                val core = chapters.readStoryCore(runId)
                mutableState.update {
                    it.copy(
                        storyBible = bible,
                        storyBibleSaved = true,
                        writingProfile = profile,
                        storyCore = core,
                        storyCoreSaved = true,
                        // 只在还没开始编辑时播种：已经改过的草稿不能被重新载入的设定覆盖。
                        writingDrafts = it.writingDrafts ?: writingDraftsSeededFrom(profile),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 设定读不出来不该挡住写作；保留空设定即可。
            }
        }
    }

    /**
     * 写作助手弹层里的一次编辑：写回 [ChaptersUiState.writingDrafts]。
     *
     * 提到 UiState 而不是弹层局部状态，是为了「关掉弹层 / 旋屏 / 切后台回来」都不丢字。
     */
    fun updateWritingDrafts(drafts: WritingDrafts) {
        mutableState.update { it.copy(writingDrafts = drafts) }
    }

    fun generateStoryBible(premise: String, genre: String = state.value.writingProfile.genre) {
        if (state.value.generatingBible) return
        viewModelScope.launch {
            mutableState.update { it.copy(generatingBible = true, error = "", message = "") }
            try {
                val bible = chapters.generateStoryBible(
                    runId,
                    GenerateStoryBibleRequest(
                        premise = premise.trim(),
                        genre = genre.trim(),
                    ),
                )
                mutableState.update {
                    it.copy(
                        generatingBible = false,
                        storyBible = bible,
                        storyBibleSaved = false,
                        message = "设定已生成，确认后保存才会用于创作。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(generatingBible = false, error = error.message ?: "生成设定失败。")
                }
            }
        }
    }

    fun saveStoryBible() {
        val bible = state.value.storyBible
        if (bible.isEmpty || state.value.savingBible) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingBible = true, error = "", message = "") }
            try {
                val saved = chapters.saveStoryBible(runId, bible)
                mutableState.update {
                    it.copy(
                        savingBible = false,
                        storyBible = saved,
                        storyBibleSaved = true,
                        message = "设定已保存，之后的大纲与正文都会带上它。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingBible = false, error = error.message ?: "保存设定失败。")
                }
            }
        }
    }

    /** 人工编辑设定后直接落盘（不经过「生成提案」的确认流程）。 */
    fun saveStoryBibleNow(bible: StoryBibleDto) {
        if (state.value.savingBible) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingBible = true, error = "", message = "") }
            try {
                val saved = chapters.saveStoryBible(runId, bible)
                mutableState.update {
                    it.copy(
                        savingBible = false,
                        storyBible = saved,
                        storyBibleSaved = true,
                        message = "设定已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingBible = false, error = error.message ?: "保存设定失败。")
                }
            }
        }
    }

    fun saveWritingProfile(profile: WritingProfileDto) {
        if (state.value.savingProfile) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingProfile = true, error = "", message = "") }
            try {
                val saved = chapters.saveWritingProfile(runId, profile)
                mutableState.update {
                    it.copy(
                        savingProfile = false,
                        writingProfile = saved,
                        // 落盘成功 = 草稿与已保存内容一致：重新播种，清掉「未保存」标记。
                        writingDrafts = writingDraftsSeededFrom(saved),
                        message = "写作类型已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingProfile = false, error = error.message ?: "保存写作类型失败。")
                }
            }
        }
    }

    // ===== 人定核心 =====

    fun generateStoryCore(premise: String = "") {
        if (state.value.generatingCore) return
        viewModelScope.launch {
            mutableState.update { it.copy(generatingCore = true, error = "", message = "") }
            try {
                val core = chapters.generateStoryCore(runId, GenerateStoryCoreRequest(premise.trim()))
                mutableState.update {
                    it.copy(
                        generatingCore = false,
                        storyCore = core,
                        storyCoreSaved = false,
                        message = "核心已生成，确认后保存；之后的大纲与正文都会按它来。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(generatingCore = false, error = error.message ?: "生成故事核心失败。")
                }
            }
        }
    }

    fun saveStoryCore() {
        val core = state.value.storyCore
        if (core.isEmpty || state.value.savingCore) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingCore = true, error = "", message = "") }
            try {
                val saved = chapters.saveStoryCore(runId, core)
                mutableState.update {
                    it.copy(
                        savingCore = false,
                        storyCore = saved,
                        storyCoreSaved = true,
                        message = "故事核心已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingCore = false, error = error.message ?: "保存故事核心失败。")
                }
            }
        }
    }

    // ===== 场景卡与多版本初稿 =====

    fun selectExpandChapter(chapterId: String) {
        mutableState.update {
            it.copy(expandChapterId = chapterId, sceneCards = emptyList(), draftVersions = emptyList())
        }
    }

    fun generateSceneCards(chapterId: String) {
        if (state.value.generatingSceneCards) return
        val target = chapterId.ifBlank {
            state.value.chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
        }
        if (target.isBlank()) {
            mutableState.update { it.copy(error = "先选一章再拆场景。") }
            return
        }
        viewModelScope.launch {
            mutableState.update {
                it.copy(generatingSceneCards = true, sceneCards = emptyList(), error = "", message = "")
            }
            try {
                val cards = chapters.generateSceneCards(runId, GenerateSceneCardsRequest(target, 4))
                mutableState.update {
                    it.copy(
                        generatingSceneCards = false,
                        expandChapterId = target,
                        sceneCards = cards,
                        message = "拆出 ${cards.size} 张场景卡，确认后保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(generatingSceneCards = false, error = error.message ?: "生成场景卡失败。")
                }
            }
        }
    }

    fun saveSceneCards(chapterId: String) {
        val cards = state.value.sceneCards
        if (cards.isEmpty() || state.value.savingSceneCards) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingSceneCards = true, error = "", message = "") }
            try {
                chapters.saveSceneCards(runId, chapterId, cards)
                val refreshed = chapters.listChapters(runId)
                mutableState.update {
                    it.copy(
                        savingSceneCards = false,
                        sceneCards = emptyList(),
                        chapters = refreshed,
                        message = "场景卡已保存，可以按它写初稿了。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingSceneCards = false, error = error.message ?: "保存场景卡失败。")
                }
            }
        }
    }

    fun draftVersions(chapterId: String) {
        if (state.value.draftingVersions) return
        if (chapterId.isBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(draftingVersions = true, draftVersions = emptyList(), error = "", message = "")
            }
            try {
                val versions = chapters.draftVersions(runId, DraftVersionsRequest(chapterId, count = 2))
                mutableState.update {
                    it.copy(
                        draftingVersions = false,
                        draftVersions = versions,
                        message = "写了 ${versions.size} 版初稿，挑一版采用，或继续改。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(draftingVersions = false, error = error.message ?: "生成初稿失败。")
                }
            }
        }
    }

    fun applyVersion(chapterId: String, version: DraftVersionDto) {
        if (chapterId.isBlank() || state.value.applyingVersion.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(applyingVersion = version.label, error = "", message = "")
            }
            try {
                val saved = chapters.applyChapterContent(
                    runId,
                    chapterId,
                    ApplyChapterContentRequest(version.content),
                )
                mutableState.update { current ->
                    current.copy(
                        applyingVersion = "",
                        draftVersions = emptyList(),
                        chapters = current.chapters.map { if (it.chapterId == saved.chapterId) saved else it },
                        message = "已采用版本 ${version.label}，正文已覆盖。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(applyingVersion = "", error = error.message ?: "采用这一版失败。")
                }
            }
        }
    }

    // ===== 局部重写 =====

    fun updateRewriteInput(value: String) {
        mutableState.update { it.copy(rewriteInput = value, rewriteOptions = emptyList()) }
    }

    fun updateRewriteInstruction(value: String) {
        mutableState.update { it.copy(rewriteInstruction = value.take(300), rewriteOptions = emptyList()) }
    }

    fun rewriteSelection() {
        val current = state.value
        if (current.rewriting) return
        val text = current.rewriteInput.trim()
        if (text.length < 10) {
            mutableState.update { it.copy(error = "先贴一段要改的正文（至少 10 个字）。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(rewriting = true, rewriteOptions = emptyList(), error = "", message = "") }
            try {
                val options = chapters.rewriteSelection(
                    runId,
                    RewriteSelectionRequest(
                        text = text,
                        instruction = current.rewriteInstruction.trim(),
                        count = 3,
                    ),
                )
                mutableState.update {
                    it.copy(rewriting = false, rewriteOptions = options, message = "给了 ${options.size} 个改写版本。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(rewriting = false, error = error.message ?: "改写失败。")
                }
            }
        }
    }

    // ===== 全书一致性与终审 =====

    fun checkConsistency() {
        if (state.value.checkingConsistency) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(checkingConsistency = true, consistency = null, error = "", message = "")
            }
            try {
                val report = chapters.checkConsistency(runId)
                mutableState.update { it.copy(checkingConsistency = false, consistency = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(checkingConsistency = false, error = error.message ?: "一致性检查失败。")
                }
            }
        }
    }

    fun runFinalReview() {
        if (state.value.reviewingAll) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(reviewingAll = true, finalReview = null, finalApproved = false, error = "", message = "")
            }
            try {
                val report = chapters.finalReview(runId)
                mutableState.update { it.copy(reviewingAll = false, finalReview = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(reviewingAll = false, error = error.message ?: "终审失败。")
                }
            }
        }
    }

    fun markFinalApproved(value: Boolean) {
        mutableState.update { it.copy(finalApproved = value) }
    }

    fun updateRescueChapter(chapterId: String) {
        mutableState.update { it.copy(rescueChapterId = chapterId, rescueOptions = emptyList()) }
    }

    fun updateRescueProblem(value: String) {
        mutableState.update { it.copy(rescueProblem = value.take(500), rescueOptions = emptyList()) }
    }

    fun updateRescueKind(kind: String) {
        mutableState.update { it.copy(rescueKind = kind, rescueOptions = emptyList()) }
    }

    fun rescue() {
        val current = state.value
        if (current.rescuing) return
        val problem = current.rescueProblem.trim()
        if (problem.isEmpty()) {
            mutableState.update { it.copy(error = "先写一下卡在哪里。") }
            return
        }
        val chapterId = current.rescueChapterId.ifBlank {
            current.chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
        }
        viewModelScope.launch {
            mutableState.update { it.copy(rescuing = true, error = "", message = "") }
            try {
                val options = chapters.rescue(
                    runId,
                    RescueRequest(chapterId = chapterId, problem = problem, kind = current.rescueKind, count = 3),
                )
                mutableState.update {
                    it.copy(rescuing = false, rescueOptions = options, message = "给了 ${options.size} 个方案，挑一个往下写。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(rescuing = false, error = error.message ?: "生成方案失败。")
                }
            }
        }
    }

    fun updateReviewChapter(chapterId: String) {
        mutableState.update { it.copy(reviewChapterId = chapterId, review = null) }
    }

    fun reviewChapter(chapterId: String) {
        if (state.value.reviewing) return
        val target = chapterId.ifBlank {
            state.value.chapters.firstOrNull { it.content.isNotBlank() }?.chapterId.orEmpty()
        }
        if (target.isBlank()) {
            mutableState.update { it.copy(error = "先选一章有正文的章节。") }
            return
        }
        viewModelScope.launch {
            mutableState.update {
                it.copy(reviewing = true, reviewChapterId = target, review = null, error = "", message = "")
            }
            try {
                val review = chapters.reviewChapter(runId, target)
                mutableState.update { it.copy(reviewing = false, review = review) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(reviewing = false, error = error.message ?: "检查失败。")
                }
            }
        }
    }

    fun polishChapter(chapterId: String) {
        if (state.value.polishingChapterId.isNotBlank()) return
        val target = chapterId.ifBlank { state.value.reviewChapterId }
        if (target.isBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(polishingChapterId = target, error = "", message = "")
            }
            try {
                val polished = chapters.polishChapter(runId, target)
                mutableState.update { current ->
                    current.copy(
                        polishingChapterId = "",
                        chapters = current.chapters.map {
                            if (it.chapterId == polished.chapterId) polished else it
                        },
                        message = "已按建议润色《${polished.title}》，可继续检查。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(polishingChapterId = "", error = error.message ?: "润色失败。")
                }
            }
        }
    }

    fun save(chapterId: String = "", title: String, goal: String, participants: String, content: String) {        if (state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.saveChapter(
                    runId,
                    chapterId,
                    SaveChapterRequest(
                        title = title.trim(),
                        goal = goal.trim(),
                        participants = participants.split(',', '，', '\n', ';', '；').map(String::trim).filter(String::isNotBlank).distinct(),
                        content = content.trim(),
                    ),
                )
                val chapters = chapters.listChapters(runId)
                mutableState.update { it.copy(saving = false, chapters = chapters, message = "章节已保存。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "保存章节失败。") }
            }
        }
    }

    fun archiveSession(sessionId: String, title: String = "") {
        if (state.value.saving || sessionId.isBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.archiveSessionAsChapter(runId, sessionId, title)
                mutableState.update {
                    it.copy(saving = false, chapters = chapters.listChapters(runId), message = "会话已归档为章节草稿。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "归档会话失败。") }
            }
        }
    }

    fun convertSession(sessionId: String, title: String = "") {
        if (state.value.saving || sessionId.isBlank()) return
        val started = novelConversionForeground.start(runId, sessionId, title)
        if (started) {
            mutableState.update {
                it.copy(message = "已放到后台生成小说章节，完成后会通过通知提醒。")
            }
        } else {
            mutableState.update {
                it.copy(error = "无法启动后台小说生成，请稍后重试。")
            }
        }
    }

    fun delete(chapterId: String) {
        if (state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.deleteChapter(runId, chapterId)
                mutableState.update { it.copy(saving = false, chapters = chapters.listChapters(runId), message = "章节已删除。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "删除章节失败。") }
            }
        }
    }

    fun continueWriting(chapterId: String) {
        if (state.value.openingChapterId.isNotBlank() || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(openingChapterId = chapterId, error = "", message = "") }
            try {
                val session = chapters.continueChapter(runId, chapterId)
                mutableState.update {
                    it.copy(
                        openingChapterId = "",
                        navigationSessionId = session.sessionId,
                        chapters = chapters.listChapters(runId),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(openingChapterId = "", error = error.message ?: "无法为章节创建写作会话。") }
            }
        }
    }

    fun syncLatestSession(chapterId: String) {
        if (state.value.syncingChapterId.isNotBlank() || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(syncingChapterId = chapterId, error = "", message = "") }
            try {
                val chapter = chapters.syncChapterSession(runId, chapterId)
                mutableState.update {
                    it.copy(
                        syncingChapterId = "",
                        chapters = it.chapters.map { existing -> if (existing.chapterId == chapter.chapterId) chapter else existing },
                        message = "最新会话已收录回章节草稿。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(syncingChapterId = "", error = error.message ?: "收录会话失败。") }
            }
        }
    }

    fun move(chapterId: String, targetOrder: Int) {
        if (state.value.saving || state.value.openingChapterId.isNotBlank() || state.value.syncingChapterId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                val chapters = chapters.reorderChapter(runId, chapterId, targetOrder)
                mutableState.update { it.copy(saving = false, chapters = chapters, message = "章节顺序已调整。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "调整章节顺序失败。") }
            }
        }
    }

    fun consumeNavigationSession() {
        mutableState.update { it.copy(navigationSessionId = "") }
    }

    fun export(format: String) {
        if (state.value.exporting) return
        viewModelScope.launch {
            mutableState.update { it.copy(exporting = true, exported = null, error = "", message = "") }
            try {
                val exported = chapters.exportChapters(runId, format, cacheDir)
                mutableState.update { it.copy(exporting = false, exported = exported, exportRequestId = it.exportRequestId + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(exporting = false, error = error.message ?: "导出章节失败。") }
            }
        }
    }

    suspend fun saveExport(destination: Sink) {
        val exported = state.value.exported ?: return
        try {
            withContext(platformIoDispatcher) {
                FileSystem.SYSTEM.source(exported.file).buffer().use { source ->
                    destination.buffer().use { sink -> sink.writeAll(source) }
                }
            }
            mutableState.update { it.copy(exported = null, message = "全书草稿已导出。") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "写入导出文件失败。") }
        } finally {
            withContext(platformIoDispatcher) { runCatching { FileSystem.SYSTEM.delete(exported.file) } }
        }
    }

    fun hasNotificationPermission(): Boolean = novelConversionForeground.hasNotificationPermission()

    fun discardExport() {
        val file = state.value.exported?.file ?: return
        mutableState.update { it.copy(exported = null) }
        runCatching { FileSystem.SYSTEM.delete(file) }
    }
}

/**
 * 大纲写入的断点状态：这份大纲累计写了多少章、还剩哪几章。
 *
 * 纯逻辑，不依赖 ViewModel/仓库：失败后靠它决定「哪些章节还该留在待写队列里」，
 * 保证重试只补写尾部、不会重复建章。
 */
internal data class OutlineWriteProgress(
    /** 这份大纲累计已落盘的章数（跨多次重试累加）。 */
    val written: Int,
    /** 还没写的尾部；为空表示这份大纲已经写完。 */
    val remaining: List<NovelOutlineChapterDto>,
) {
    val isDone: Boolean get() = remaining.isEmpty()

    /** 失败时正在写的是第几章（从 1 数，含此前几轮已写好的部分）。 */
    val failedOrdinal: Int get() = written + 1
}

/**
 * 由「本轮开始前还待写的 [pending]」「本轮写成了 [created] 章」推出新的断点。
 *
 * [alreadyWritten] 是此前几轮累计写好的章数，续写时章号与提示文案都要带上它。
 */
internal fun outlineWriteProgress(
    pending: List<NovelOutlineChapterDto>,
    created: Int,
    alreadyWritten: Int = 0,
): OutlineWriteProgress {
    val safeCreated = created.coerceIn(0, pending.size)
    return OutlineWriteProgress(
        written = alreadyWritten.coerceAtLeast(0) + safeCreated,
        remaining = pending.drop(safeCreated),
    )
}

/** 章标题为空时按**绝对**章号补一个；续写时才不会又从「第 1 章」重新数起。 */
internal fun outlineChapterTitle(chapter: NovelOutlineChapterDto, ordinal: Int): String =
    chapter.title.ifBlank { "第 $ordinal 章" }

internal fun outlineWriteSuccessText(totalWritten: Int): String =
    "已写入 $totalWritten 章大纲，点章节里的「AI 成稿」开始写正文。"

/**
 * 失败文案要说清三件事：卡在第几章、已写入多少、再点一次会不会重复。
 * 只说「写入失败」会让人以为得从头再来。
 */
internal fun outlineWriteFailureText(progress: OutlineWriteProgress, cause: String?): String = buildString {
    append("第 ").append(progress.failedOrdinal).append(" 章写入失败：")
    append(cause?.takeIf(String::isNotBlank) ?: "未知错误")
    append("（已写入 ").append(progress.written).append(" 章，还剩 ")
    append(progress.remaining.size).append(" 章；再点一次只补写剩下的部分，不会重复建章）")
}
