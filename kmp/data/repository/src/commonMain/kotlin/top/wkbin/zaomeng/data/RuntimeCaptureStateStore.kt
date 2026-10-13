package top.wkbin.zaomeng.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 采集状态与悬浮窗开关的合并视图。
 *
 * - [known] 为假表示还没从服务端拿到状态（后端未就绪/读取失败）——UI 不能把它当成「未采集」，
 *   否则会对着正在采集的用户说「没在录」。
 * - [supported] 为假表示当前平台常开采集（桌面端），UI 只展示状态、不给可操作开关。
 */
data class RuntimeCaptureStatus(
    val known: Boolean = false,
    val supported: Boolean = false,
    val enabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val updatedAt: String = "",
    val note: String = "",
)

/**
 * 采集状态真源（进程内单例）。
 *
 * 为什么要有它：「运行日志」页的开关与应用内悬浮窗展示的是**同一个状态**，
 * 如果两边各自拉接口，切换后必然一边新一边旧。这里把服务端返回的状态与
 * DataStore 里的悬浮窗开关合并成一个 [StateFlow]，两处订阅同一份。
 *
 * 服务端才是采集开关的真源（它按平台持久化）：这里只做缓存与失效，
 * 不做本地乐观写入，避免「UI 显示已开启、实际没采集」这种最坑的静默失效。
 */
class RuntimeCaptureStateStore(
    private val diagnostics: DiagnosticsRepository,
    private val preferences: AppPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + platformIoDispatcher)
    private val remote = MutableStateFlow<RuntimeCaptureStateDto?>(null)

    /** 采集状态 + 悬浮窗开关。Eagerly：应用外壳一挂上就要有值。 */
    val status: StateFlow<RuntimeCaptureStatus> =
        combine(remote, preferences.runtimeCaptureOverlayEnabled) { state, overlay ->
            RuntimeCaptureStatus(
                known = state != null,
                supported = state?.supported ?: false,
                enabled = state?.enabled ?: true,
                overlayEnabled = overlay,
                updatedAt = state?.updatedAt.orEmpty(),
                note = state?.note.orEmpty(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, RuntimeCaptureStatus())

    /**
     * 重新拉取服务端状态。失败时保留上一次已知值（读不到 ≠ 没采集）。
     *
     * @return 是否成功刷新。
     */
    suspend fun refresh(): Boolean {
        val fetched = runCatching { diagnostics.captureState() }.getOrNull() ?: return false
        remote.value = fetched
        return true
    }

    /** 设置采集开关；成功即刷新缓存。失败时状态保持不变（不做乐观更新）。 */
    suspend fun setCaptureEnabled(enabled: Boolean): Result<RuntimeCaptureStateDto> =
        runCatching { diagnostics.setCaptureEnabled(enabled) }.onSuccess { remote.value = it }

    /** 清除已采集日志；成功不清空缓存状态（采集开关本来就不受影响）。 */
    suspend fun clearCaptured(): Result<RuntimeLogClearResultDto> =
        runCatching { diagnostics.clearRuntimeLog() }

    /** 悬浮窗开关（纯客户端偏好，立即持久化）。 */
    suspend fun setOverlayEnabled(enabled: Boolean) {
        preferences.setRuntimeCaptureOverlayEnabled(enabled)
    }
}
