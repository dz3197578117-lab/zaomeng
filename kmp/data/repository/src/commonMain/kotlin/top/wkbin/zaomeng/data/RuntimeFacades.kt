package top.wkbin.zaomeng.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import okio.Sink
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectionTestDto
import top.wkbin.zaomeng.data.api.ModelListResponseDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.OnDeviceLogDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.preferences.AppPreferences

interface AppRuntimeRepository {
    val backendState: StateFlow<BackendState>
    val preferences: Flow<AppPreferences>

    fun startBackend()
    fun retryBackend()
}

interface ModelSettingsRepository {
    suspend fun getModelSettings(): ModelSettingsDto
    suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto
    suspend fun testModelSettings(request: TestModelSettingsRequest): ModelConnectionTestDto
    suspend fun detectModelCapabilities(request: TestModelSettingsRequest): ModelCapabilityReportDto
    suspend fun fetchModels(request: TestModelSettingsRequest): ModelListResponseDto
    suspend fun activateModelProfile(profileId: String): ModelSettingsDto
    suspend fun deleteModelProfile(profileId: String): ModelSettingsDto

    // ------------------------------------------------------------------
    // 端侧推理（APK 内嵌 llama.cpp）
    // ------------------------------------------------------------------

    /** 状态 + 已就位模型列表 + 下载进度：UI 轮询这一个即可。 */
    suspend fun onDeviceStatus(): OnDeviceStatusDto

    suspend fun onDeviceLogs(maxChars: Int = 8_000): OnDeviceLogDto

    suspend fun startOnDevice(request: StartOnDeviceRequest): OnDeviceStatusDto

    suspend fun stopOnDevice(): OnDeviceStatusDto

    suspend fun downloadOnDeviceModel(request: DownloadOnDeviceModelRequest): OnDeviceStatusDto

    suspend fun cancelOnDeviceDownload(): OnDeviceStatusDto

    suspend fun deleteOnDeviceModel(fileName: String): OnDeviceStatusDto
}

interface DiagnosticsRepository {
    /**
     * 导出诊断 JSON。
     *
     * @param tier 导出分级（R3）：`compact`（默认，概览 + 问题摘要 + 探针结论）或
     *   `full`（额外含运行日志、完整堆栈与事件明细）。默认必须是精简档——
     *   几十 MB 的导出在真机上根本传不出去。
     */
    suspend fun exportDiagnostics(destination: Sink, tier: String = DiagnosticsExportTier.COMPACT): Long

    /**
     * 拉取运行日志快照（服务端已脱敏），供应用内「运行日志」页展示与单独导出。
     *
     * @param maxLines 期望行数，服务端会夹紧到自己的上限。
     */
    suspend fun runtimeLog(maxLines: Int): RuntimeLogDto

    /** 「运行诊断」概览：自检 + 可读问题清单 + 事件统计 + 模型连通性（不含日志正文）。 */
    suspend fun overview(): DiagnosticsOverviewDto

    /**
     * 重新执行功能自检探针（显式「重新自检」）。
     *
     * 概览里的探针结果是**上一轮快照**，且不受运行日志采集开关影响；
     * 只有本调用会真正重跑探针。
     */
    suspend fun refreshFeatureProbes(): FeatureProbeReportDto

    /** 读取采集开关状态（不拉日志正文，轻量）。 */
    suspend fun captureState(): RuntimeCaptureStateDto

    /**
     * 启停「持续把日志写入日志文件」。
     *
     * 服务端侧幂等：重复/并发点击不会重复启停，也不会写坏日志文件；关闭不清除已采集内容。
     */
    suspend fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureStateDto

    /**
     * 清除已采集的运行日志与崩溃/卡顿记录。
     *
     * 只清日志，不触碰书卷、会话、记忆、模型档案等任何业务数据；重复调用安全。
     */
    suspend fun clearRuntimeLog(): RuntimeLogClearResultDto
}

interface PluginRepository {
    suspend fun listPlugins(): List<PluginDto>
    suspend fun refreshPlugins(): List<PluginDto>
    suspend fun inspectPluginPackage(filename: String, contentBase64: String): PluginPackageInspectionDto
    suspend fun installPluginPackage(token: String, allowUpdate: Boolean): PluginDto
    suspend fun enablePlugin(pluginId: String): PluginDto
    suspend fun disablePlugin(pluginId: String): PluginDto
    suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse
    suspend fun listPluginLogs(pluginId: String): List<PluginLogDto>
    suspend fun updatePluginConfig(pluginId: String, config: JsonObject): PluginConfigResponse
    suspend fun generatePluginDraft(description: String): PluginBuilderValidationDto
    suspend fun validatePluginDraft(draft: PluginDraft): PluginBuilderValidationDto
    suspend fun installPluginDraft(draft: PluginDraft): PluginDto
    suspend fun exportPluginDraft(draft: PluginDraft, destination: Sink): Long

    suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String = "",
        direction: String = "",
        selection: String = "",
    ): PluginChatActionResponse

    suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String = "",
    ): PluginTemporaryNpcGeneratorResponse

    suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto
}

/**
 * 导出分级常量（R3）。
 *
 * 放在契约层而不是设置页：服务端、客户端与导出文件里的 `tier` 字段必须用同一批字面量，
 * 否则「导出的是哪一档」会出现三种说法。
 */
object DiagnosticsExportTier {
    /** 精简：概览 + 问题清单摘要 + 探针结论。默认。 */
    const val COMPACT = "compact"

    /** 完整：额外包含运行日志、完整堆栈、完整事件明细与完整载荷。 */
    const val FULL = "full"
}
