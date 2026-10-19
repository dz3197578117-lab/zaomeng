package top.wkbin.zaomeng.feature.storyrecap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto

data class StoryRecapUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val session: DialogueSessionDto? = null,
    val error: String = "",
    /**
     * 这一局是否已经提交过「转小说」。由 [NovelConversionTracker] 提供，
     * 页面重建/退出再进都还是「已在后台开始」，不会让用户以为上一次没生效。
     */
    val novelConversionStarted: Boolean = false,
    /** 「开始生成」相关的提示（例如未授权通知、启动失败），非错误通道。 */
    val conversionNotice: String = "",
)

class StoryRecapViewModel(
    private val repository: SessionRepository,
    val runId: String,
    val sessionId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StoryRecapUiState())
    val state: StateFlow<StoryRecapUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        require(sessionId.isNotBlank()) { "sessionId 不能为空。" }
        mutableState.update {
            it.copy(novelConversionStarted = NovelConversionTracker.isStarted(runId, sessionId))
        }
        load()
    }

    /** 后台生成已经排队成功：记进进程内账本，并让按钮立刻变成「已在后台开始」。 */
    fun markNovelConversionStarted() {
        NovelConversionTracker.markStarted(runId, sessionId)
        mutableState.update { it.copy(novelConversionStarted = true, conversionNotice = "") }
    }

    fun updateConversionNotice(message: String) {
        mutableState.update { it.copy(conversionNotice = message) }
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.session == null,
                    refreshing = it.session != null,
                    error = "",
                )
            }
            try {
                val session = repository.getSession(runId, sessionId, includeTranscript = true)
                mutableState.update {
                    it.copy(loading = false, refreshing = false, session = session)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "剧情复盘读取失败。",
                    )
                }
            }
        }
    }
}
