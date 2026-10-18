package top.wkbin.zaomeng.feature.chapters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.ChapterReviewDto
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
    // ===== 创作工具：设定 / 类型 / 救援 / 检查 =====
    val storyBible: StoryBibleDto = StoryBibleDto(),
    /** 当前设定是否已落盘；false 表示还是未确认的提案。 */
    val storyBibleSaved: Boolean = true,
    val generatingBible: Boolean = false,
    val savingBible: Boolean = false,
    val writingProfile: WritingProfileDto = WritingProfileDto(),
    val savingProfile: Boolean = false,
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
            mutableState.update { it.copy(generatingOutline = true, error = "", message = "", outline = emptyList()) }
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

    fun clearOutline() {
        mutableState.update { it.copy(outline = emptyList()) }
    }

    /**
     * 把大纲逐章写入章节列表。
     *
     * 逐章保存而不是批量接口：现有 [saveChapter] 已经是原子的单章写入，
     * 失败时能明确告诉用户「写到了第几章」，已写入的部分也不会回滚成半截数据。
     */
    fun createChaptersFromOutline(perspective: String) {
        val outline = state.value.outline
        if (outline.isEmpty() || state.value.creatingFromOutline) return
        viewModelScope.launch {
            mutableState.update { it.copy(creatingFromOutline = true, error = "", message = "") }
            var created = 0
            try {
                for (item in outline) {
                    chapters.saveChapter(
                        runId,
                        "",
                        SaveChapterRequest(
                            title = item.title.ifBlank { "第 ${created + 1} 章" },
                            goal = item.goal,
                            content = "",
                            perspective = perspective,
                        ),
                    )
                    created++
                }
                val list = chapters.listChapters(runId)
                mutableState.update {
                    it.copy(
                        creatingFromOutline = false,
                        outline = emptyList(),
                        chapters = list,
                        outlineCreationCount = it.outlineCreationCount + 1,
                        message = "已写入 $created 章大纲，点章节里的「AI 成稿」开始写正文。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val refreshed = runCatching { chapters.listChapters(runId) }.getOrDefault(state.value.chapters)
                mutableState.update {
                    it.copy(
                        creatingFromOutline = false,
                        chapters = refreshed,
                        error = "第 ${created + 1} 章写入失败：${error.message ?: "未知错误"}（已写入 $created 章）",
                    )
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
                mutableState.update {
                    it.copy(storyBible = bible, storyBibleSaved = true, writingProfile = profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 设定读不出来不该挡住写作；保留空设定即可。
            }
        }
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
