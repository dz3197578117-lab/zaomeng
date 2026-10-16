package top.wkbin.zaomeng.data

import io.ktor.client.call.body
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.data.api.VoiceModelStatusDto
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.data.api.GeneratePluginDraftRequest
import top.wkbin.zaomeng.data.api.InspectPluginPackageRequest
import top.wkbin.zaomeng.data.api.InstallPluginPackageRequest
import top.wkbin.zaomeng.data.local.DiagnosticsClient
import top.wkbin.zaomeng.data.local.ModelSettingsClient
import top.wkbin.zaomeng.data.local.PluginClient
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectionTestDto
import top.wkbin.zaomeng.data.api.ModelListResponseDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.OnDeviceLogDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.data.api.PluginChatActionRequest
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.PackagePluginDraftRequest
import top.wkbin.zaomeng.data.api.ValidatePluginDraftRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.SecureStoreNames
import top.wkbin.zaomeng.client.platform.clientBase64Encode

class AppRuntimeRepositoryImpl(
    private val backend: BackendController,
    private val appPreferences: AppPreferencesRepository,
) : AppRuntimeRepository {
    override val backendState: StateFlow<BackendState> = backend.state
    override val preferences: Flow<AppPreferences> = appPreferences.preferences

    override fun startBackend() = backend.start()
    override fun retryBackend() = backend.retry()
}

class ModelSettingsRepositoryImpl(
    private val modelSettings: ModelSettingsClient,
    private val secureStore: SecureKeyValueStore,
) : ModelSettingsRepository {
    override suspend fun getModelSettings(): ModelSettingsDto = repositoryRequest {
        modelSettings.get()
    }

    override suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto = repositoryRequest {
        modelSettings.save(request).also { saved ->
            secureStore.put(
                SecureStoreNames.secretName(request.profileId.ifBlank { saved.activeProfileId }),
                request.apiKey,
            )
        }
    }

    override suspend fun testModelSettings(request: TestModelSettingsRequest): ModelConnectionTestDto = repositoryRequest {
        modelSettings.test(request)
    }

    override suspend fun detectModelCapabilities(request: TestModelSettingsRequest): ModelCapabilityReportDto = repositoryRequest {
        modelSettings.detectCapabilities(request)
    }

    override suspend fun fetchModels(request: TestModelSettingsRequest): ModelListResponseDto = repositoryRequest {
        modelSettings.fetchModels(request)
    }

    override suspend fun activateModelProfile(profileId: String): ModelSettingsDto = repositoryRequest {
        modelSettings.activate(profileId)
    }

    override suspend fun deleteModelProfile(profileId: String): ModelSettingsDto = repositoryRequest {
        modelSettings.delete(profileId).also {
            secureStore.remove(SecureStoreNames.secretName(profileId))
        }
    }

    override suspend fun onDeviceStatus(): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceStatus()
    }

    override suspend fun onDeviceLogs(maxChars: Int): OnDeviceLogDto = repositoryRequest {
        modelSettings.onDeviceLogs(maxChars)
    }

    override suspend fun startOnDevice(request: StartOnDeviceRequest): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceStart(request)
    }

    override suspend fun stopOnDevice(): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceStop()
    }

    override suspend fun downloadOnDeviceModel(request: DownloadOnDeviceModelRequest): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceDownload(request)
    }

    override suspend fun cancelOnDeviceDownload(): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceCancelDownload()
    }

    override suspend fun deleteOnDeviceModel(fileName: String): OnDeviceStatusDto = repositoryRequest {
        modelSettings.onDeviceDeleteModel(fileName)
    }

    override suspend fun voiceModelStatus(modelId: String): VoiceModelStatusDto = repositoryRequest {
        modelSettings.voiceModelStatus(modelId)
    }

    override suspend fun downloadVoiceModel(
        request: DownloadVoiceModelRequest,
    ): VoiceModelStatusDto = repositoryRequest {
        modelSettings.voiceModelDownload(request)
    }

    override suspend fun cancelVoiceModelDownload(): VoiceModelStatusDto = repositoryRequest {
        modelSettings.voiceModelCancelDownload()
    }

    override suspend fun deleteVoiceModel(modelId: String): VoiceModelStatusDto = repositoryRequest {
        modelSettings.voiceModelDelete(modelId)
    }
}

class DiagnosticsRepositoryImpl(
    private val diagnosticsClient: DiagnosticsClient,
    private val appPreferences: AppPreferencesRepository,
) : DiagnosticsRepository {
    override suspend fun exportDiagnostics(destination: Sink, tier: String): Long = repositoryRequest {
        val context = currentSessionContext()
        diagnosticsClient.export(destination, context.runId, context.sessionId, tier)
    }

    override suspend fun runtimeLog(maxLines: Int): RuntimeLogDto = repositoryRequest {
        val context = currentSessionContext()
        diagnosticsClient.runtimeLog(maxLines, context.runId, context.sessionId)
    }

    override suspend fun overview(): DiagnosticsOverviewDto = repositoryRequest {
        val context = currentSessionContext()
        diagnosticsClient.overview(context.runId, context.sessionId)
    }

    override suspend fun refreshFeatureProbes(): FeatureProbeReportDto = repositoryRequest {
        diagnosticsClient.refreshFeatureProbes()
    }

    override suspend fun captureState(): RuntimeCaptureStateDto = repositoryRequest {
        diagnosticsClient.captureState()
    }

    override suspend fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureStateDto = repositoryRequest {
        diagnosticsClient.setCaptureEnabled(enabled)
    }

    override suspend fun clearRuntimeLog(): RuntimeLogClearResultDto = repositoryRequest {
        diagnosticsClient.clearRuntimeLog()
    }

    /**
     * 现场上下文：最近打开的 run/session。
     *
     * 服务端不知道 UI 状态，这两个值只能由客户端上报；读偏好失败时退化为空（诊断不应因此失败），
     * 服务端会把它标注为「客户端上报」而不是「正在生成的会话」。
     */
    private suspend fun currentSessionContext(): SessionContext {
        val preferences = runCatching { appPreferences.currentPreferences() }.getOrNull()
        return SessionContext(
            runId = preferences?.lastRunId.orEmpty(),
            sessionId = preferences?.lastSessionId.orEmpty(),
        )
    }

    private data class SessionContext(val runId: String, val sessionId: String)
}

class PluginRepositoryImpl(
    private val plugins: PluginClient,
) : PluginRepository {
    override suspend fun listPlugins(): List<PluginDto> = repositoryRequest {
        plugins.list().items
    }

    override suspend fun refreshPlugins(): List<PluginDto> = repositoryRequest {
        plugins.refresh().items
    }

    override suspend fun inspectPluginPackage(
        filename: String,
        contentBase64: String,
    ): PluginPackageInspectionDto = repositoryRequest {
        plugins.inspect(InspectPluginPackageRequest(filename, contentBase64))
    }

    override suspend fun installPluginPackage(
        token: String,
        allowUpdate: Boolean,
    ): PluginDto = repositoryRequest {
        plugins.install(
            token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = allowUpdate,
            ),
        )
    }

    override suspend fun enablePlugin(pluginId: String): PluginDto = repositoryRequest {
        plugins.enable(pluginId)
    }

    override suspend fun disablePlugin(pluginId: String): PluginDto = repositoryRequest {
        plugins.disable(pluginId)
    }

    override suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse = repositoryRequest {
        plugins.uninstall(pluginId)
    }

    override suspend fun listPluginLogs(pluginId: String): List<PluginLogDto> = repositoryRequest {
        plugins.logs(pluginId).items
    }

    override suspend fun updatePluginConfig(
        pluginId: String,
        config: JsonObject,
    ): PluginConfigResponse = repositoryRequest {
        plugins.updateConfig(pluginId, config)
    }

    override suspend fun validatePluginDraft(draft: PluginDraft): PluginBuilderValidationDto = repositoryRequest {
        plugins.validateBuilder(ValidatePluginDraftRequest(draft))
    }

    override suspend fun generatePluginDraft(description: String): PluginBuilderValidationDto = repositoryRequest {
        plugins.generateBuilder(GeneratePluginDraftRequest(description))
    }

    override suspend fun installPluginDraft(draft: PluginDraft): PluginDto = repositoryRequest {
        val validation = plugins.validateBuilder(ValidatePluginDraftRequest(draft))
        if (!validation.valid) {
            throw ApiRequestException(validation.issues.joinToString("；") { it.message }.ifBlank { "插件草稿未通过校验。" })
        }
        val bytes = plugins.packageBuilder(PackagePluginDraftRequest(validation.draft))
        val inspection = plugins.inspect(
            InspectPluginPackageRequest(validation.filename, clientBase64Encode(bytes)),
        )
        plugins.install(
            inspection.token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = inspection.operation == "update",
            ),
        )
    }

    override suspend fun exportPluginDraft(draft: PluginDraft, destination: Sink): Long = repositoryRequest {
        val bytes = plugins.packageBuilder(PackagePluginDraftRequest(draft))
        destination.buffer().use { it.write(bytes) }
        bytes.size.toLong()
    }

    override suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String,
        direction: String,
        selection: String,
    ): PluginChatActionResponse = repositoryRequest {
        val result = plugins.chatAction(
            runId,
            sessionId,
            pluginId,
            actionId,
            PluginChatActionRequest(seedText = seedText, direction = direction, selection = selection),
        )
        if (
            result.suggestion.isBlank() &&
            result.suggestions.none { it.suggestion.isNotBlank() } &&
            result.choices.none { it.value.isNotBlank() } &&
            result.session.sessionId.isBlank() &&
            result.notice.isBlank()
        ) {
            throw ApiRequestException("插件没有返回可用结果。")
        }
        result
    }

    override suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String,
    ): PluginTemporaryNpcGeneratorResponse = repositoryRequest {
        plugins.temporaryNpcGenerator(
            runId,
            sessionId,
            pluginId,
            generatorId,
            PluginTemporaryNpcGeneratorRequest(direction = direction),
        )
    }

    override suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = repositoryRequest {
        plugins.setEnhancerState(
            runId,
            sessionId,
            pluginId,
            enhancerId,
            enabled,
        )
    }
}
