package top.wkbin.zaomeng.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.RunManifestDto

data class CreationUiState(
    val loading: Boolean = true,
    /** 可进入创作的书卷：蒸馏完成、有人物档案。 */
    val runs: List<RunManifestDto> = emptyList(),
    val error: String = "",
)

/**
 * 「创作」顶级页的数据源。
 *
 * 只列 `ready` 的书卷：AI 创作需要人物档案与世界观做上下文，
 * 蒸馏中/失败的卷进去只会得到一份空大纲，不如先不给入口。
 */
class CreationViewModel(
    private val runs: RunRepository,
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
                    it.copy(
                        loading = false,
                        runs = all.filter { run -> run.status == "ready" && run.betaFeature == null },
                    )
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
}
