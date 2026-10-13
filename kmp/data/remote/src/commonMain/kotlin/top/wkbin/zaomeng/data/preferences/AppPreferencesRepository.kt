package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.IOException

/** 消息字号连续缩放比例：1f 为标准大小。 */
const val CHAT_FONT_SCALE_MIN = 0.8f
const val CHAT_FONT_SCALE_MAX = 1.3f
const val CHAT_FONT_SCALE_DEFAULT = 1f

/** 全局界面缩放（参考 KernelSU pageScale）：1f 为标准大小。 */
const val UI_SCALE_MIN = 0.85f
const val UI_SCALE_MAX = 1.25f
const val UI_SCALE_DEFAULT = 1f

data class ChatDisplayPreferences(
    val fontSizeScale: Float = CHAT_FONT_SCALE_DEFAULT,
    val compactMode: Boolean = false,
    val showModelReasoning: Boolean = false,
    val backgroundImageUri: String = "",
    val backgroundOpacity: Float = 0.35f,
    val backgroundBlurRadius: Float = 0f,
    /**
     * 是否使用**离线神经 TTS** 朗读（关闭 = 系统 TTS）。
     *
     * 放在**对话显示偏好**里而不是顶层：朗读是对话体验的一部分，而这份偏好已经被
     * 「ViewModel → 聊天页」整条链路带着走，不必再造一条通路。
     * 默认关闭——系统 TTS 不需要下载任何东西就一定可用，是安全默认。
     */
    val neuralSpeechEnabled: Boolean = false,
    /** 选用的神经语音模型 id（见 `NeuralVoiceCatalog`）；空串表示未选。 */
    val ttsNeuralModelId: String = "",
    /**
     * 神经语音模型在本机的**绝对目录**，由服务端回报（`VoiceModelStatusDto.directory`）。
     *
     * 客户端**不自己拼**这个路径：「模型放在哪」只在一处定义，
     * 两端各记一份会悄悄漂移，而路径不对的症状只是「没声音」。
     */
    val ttsNeuralModelDir: String = "",
)

data class AppPreferences(
    val defaultCharacters: String = "",
    val autoDistill: Boolean = true,
    val restoreLastLocation: Boolean = true,
    val lastRunId: String = "",
    val lastSessionId: String = "",
    val chatDisplay: ChatDisplayPreferences = ChatDisplayPreferences(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeSeedColorArgb: Long = 0L,
    val dynamicColorEnabled: Boolean = false,
    val uiScale: Float = UI_SCALE_DEFAULT,
    val builtInBackHandlingEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsAutoPlayObserve: Boolean = false,
    /** 应用内悬浮窗（采集状态 + 快捷入口）开关；纯客户端 UI 偏好，默认关闭。 */
    val runtimeCaptureOverlayEnabled: Boolean = false,
)

/** 跨平台偏好仓库：全部平台统一走官方 KMP DataStore（Preferences）。 */
class AppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            AppPreferences(
                defaultCharacters = values[KEY_DEFAULT_CHARACTERS].orEmpty(),
                autoDistill = values[KEY_AUTO_DISTILL] ?: true,
                restoreLastLocation = values[KEY_RESTORE_LAST_LOCATION] ?: true,
                lastRunId = values[KEY_LAST_RUN_ID].orEmpty(),
                lastSessionId = values[KEY_LAST_SESSION_ID].orEmpty(),
                chatDisplay = ChatDisplayPreferences(
                    fontSizeScale = chatFontScaleFrom(
                        values[KEY_CHAT_FONT_SCALE],
                        values[KEY_CHAT_FONT_SIZE],
                    ),
                    compactMode = values[KEY_CHAT_COMPACT_MODE] ?: false,
                    showModelReasoning = values[KEY_CHAT_SHOW_MODEL_REASONING] ?: false,
                    backgroundImageUri = values[KEY_CHAT_BACKGROUND_IMAGE_URI].orEmpty(),
                    backgroundOpacity = (values[KEY_CHAT_BACKGROUND_OPACITY] ?: 0.35f).coerceIn(0.1f, 1f),
                    backgroundBlurRadius = (values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] ?: 0f).coerceIn(0f, 32f),
                    neuralSpeechEnabled = values[KEY_CHAT_NEURAL_SPEECH] ?: false,
                    ttsNeuralModelId = values[KEY_TTS_NEURAL_MODEL_ID].orEmpty(),
                    ttsNeuralModelDir = values[KEY_TTS_NEURAL_MODEL_DIR].orEmpty(),
                ),
                themeMode = ThemeMode.fromStorageValue(values[KEY_THEME_MODE]),
                themeSeedColorArgb = values[KEY_THEME_SEED_COLOR] ?: 0L,
                dynamicColorEnabled = values[KEY_DYNAMIC_COLOR_ENABLED] ?: false,
                uiScale = (values[KEY_UI_SCALE] ?: UI_SCALE_DEFAULT).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                builtInBackHandlingEnabled = values[KEY_BUILT_IN_BACK_HANDLING_ENABLED]
                    ?: values[KEY_LEGACY_PREDICTIVE_BACK_ENABLED]
                    ?: true,
                ttsEnabled = values[KEY_TTS_ENABLED] ?: true,
                ttsSpeechRate = (values[KEY_TTS_SPEECH_RATE] ?: 1.0f).coerceIn(0.5f, 2.0f),
                ttsAutoPlayObserve = values[KEY_TTS_AUTO_PLAY_OBSERVE] ?: false,
                runtimeCaptureOverlayEnabled = values[KEY_RUNTIME_CAPTURE_OVERLAY] ?: false,
            )
        }

    val chatDisplayPreferences: Flow<ChatDisplayPreferences> = preferences
        .map { it.chatDisplay }
        .distinctUntilChanged()

    val themeMode: Flow<ThemeMode> = preferences
        .map { it.themeMode }
        .distinctUntilChanged()

    val themeSeedColorArgb: Flow<Long> = preferences
        .map { it.themeSeedColorArgb }
        .distinctUntilChanged()

    val dynamicColorEnabled: Flow<Boolean> = preferences
        .map { it.dynamicColorEnabled }
        .distinctUntilChanged()

    val uiScale: Flow<Float> = preferences
        .map { it.uiScale }
        .distinctUntilChanged()

    val builtInBackHandlingEnabled: Flow<Boolean> = preferences
        .map { it.builtInBackHandlingEnabled }
        .distinctUntilChanged()

    val ttsEnabled: Flow<Boolean> = preferences
        .map { it.ttsEnabled }
        .distinctUntilChanged()

    /** 应用内悬浮窗开关。 */
    val runtimeCaptureOverlayEnabled: Flow<Boolean> = preferences
        .map { it.runtimeCaptureOverlayEnabled }
        .distinctUntilChanged()

    /**
     * 读取一次偏好快照。平台入口在 UI 组合前调用，先把持久化主题应用上，
     * 避免启动时先按默认主题渲染再跳变（参考 KernelSU 启动即读主题的做法）。
     */
    suspend fun currentPreferences(): AppPreferences = preferences.first()

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        dataStore.edit { values ->
            values[KEY_DEFAULT_CHARACTERS] = characters
            values[KEY_AUTO_DISTILL] = autoDistill
        }
    }

    suspend fun rememberRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (normalizedRunId.isBlank()) {
                values.remove(KEY_LAST_RUN_ID)
            } else {
                values[KEY_LAST_RUN_ID] = normalizedRunId
            }
            values.remove(KEY_LAST_SESSION_ID)
        }
    }

    suspend fun rememberSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) return
        dataStore.edit { values ->
            values[KEY_LAST_RUN_ID] = normalizedRunId
            values[KEY_LAST_SESSION_ID] = normalizedSessionId
        }
    }

    suspend fun forgetRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (values[KEY_LAST_RUN_ID] == normalizedRunId) {
                values.remove(KEY_LAST_RUN_ID)
                values.remove(KEY_LAST_SESSION_ID)
            }
        }
    }

    suspend fun forgetSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        dataStore.edit { values ->
            if (
                values[KEY_LAST_RUN_ID] == normalizedRunId &&
                values[KEY_LAST_SESSION_ID] == normalizedSessionId
            ) {
                values.remove(KEY_LAST_SESSION_ID)
            }
        }
    }

    suspend fun setChatFontSize(scale: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_FONT_SCALE] = scale.coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)
        }
    }

    suspend fun setCompactChatMode(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_CHAT_COMPACT_MODE] = enabled }
    }

    suspend fun setShowModelReasoning(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_CHAT_SHOW_MODEL_REASONING] = enabled }
    }

    suspend fun setChatBackgroundImageUri(uri: String) {
        dataStore.edit { values ->
            if (uri.isBlank()) values.remove(KEY_CHAT_BACKGROUND_IMAGE_URI)
            else values[KEY_CHAT_BACKGROUND_IMAGE_URI] = uri
        }
    }

    suspend fun setChatBackgroundOpacity(opacity: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_BACKGROUND_OPACITY] = opacity.coerceIn(0.1f, 1f)
        }
    }

    suspend fun setChatBackgroundBlurRadius(radius: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] = radius.coerceIn(0f, 32f)
        }
    }

    suspend fun saveChatDisplayPreferences(preferences: ChatDisplayPreferences) {
        dataStore.edit { values ->
            values[KEY_CHAT_FONT_SCALE] = preferences.fontSizeScale
                .coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)
            values[KEY_CHAT_COMPACT_MODE] = preferences.compactMode
            values[KEY_CHAT_SHOW_MODEL_REASONING] = preferences.showModelReasoning
            if (preferences.backgroundImageUri.isBlank()) values.remove(KEY_CHAT_BACKGROUND_IMAGE_URI)
            else values[KEY_CHAT_BACKGROUND_IMAGE_URI] = preferences.backgroundImageUri
            values[KEY_CHAT_BACKGROUND_OPACITY] = preferences.backgroundOpacity.coerceIn(0.1f, 1f)
            values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] = preferences.backgroundBlurRadius.coerceIn(0f, 32f)
            // 朗读引擎也在这里落盘：不写的话，用户一改字号就会把引擎选择悄悄重置回系统 TTS。
            values[KEY_CHAT_NEURAL_SPEECH] = preferences.neuralSpeechEnabled
            if (preferences.ttsNeuralModelId.isBlank()) values.remove(KEY_TTS_NEURAL_MODEL_ID)
            else values[KEY_TTS_NEURAL_MODEL_ID] = preferences.ttsNeuralModelId
            if (preferences.ttsNeuralModelDir.isBlank()) values.remove(KEY_TTS_NEURAL_MODEL_DIR)
            else values[KEY_TTS_NEURAL_MODEL_DIR] = preferences.ttsNeuralModelDir
        }
    }

    suspend fun setRestoreLastLocation(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_RESTORE_LAST_LOCATION] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { values -> values[KEY_THEME_MODE] = themeMode.storageValue }
    }

    suspend fun setThemeSeedColor(argb: Long) {
        dataStore.edit { values ->
            if (argb == 0L) values.remove(KEY_THEME_SEED_COLOR)
            else values[KEY_THEME_SEED_COLOR] = argb
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_DYNAMIC_COLOR_ENABLED] = enabled }
    }

    suspend fun setUiScale(scale: Float) {
        dataStore.edit { values ->
            values[KEY_UI_SCALE] = scale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        }
    }

    suspend fun setBuiltInBackHandlingEnabled(enabled: Boolean) {
        dataStore.edit { values ->
            values[KEY_BUILT_IN_BACK_HANDLING_ENABLED] = enabled
            values.remove(KEY_LEGACY_PREDICTIVE_BACK_ENABLED)
        }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_TTS_ENABLED] = enabled }
    }

    /** 切换是否使用离线神经 TTS 朗读（关闭 = 系统 TTS）。 */
    suspend fun setNeuralSpeechEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_CHAT_NEURAL_SPEECH] = enabled }
    }

    /**
     * 记住选用的神经语音模型，以及服务端回报的**模型目录**。
     *
     * 两者一起写：只有 id 没有目录时引擎无处加载，只有目录没有 id 时界面无法显示当前用的是哪个模型。
     */
    suspend fun setTtsNeuralModel(modelId: String, modelDir: String) {
        dataStore.edit { values ->
            if (modelId.isBlank()) values.remove(KEY_TTS_NEURAL_MODEL_ID)
            else values[KEY_TTS_NEURAL_MODEL_ID] = modelId
            if (modelDir.isBlank()) values.remove(KEY_TTS_NEURAL_MODEL_DIR)
            else values[KEY_TTS_NEURAL_MODEL_DIR] = modelDir
        }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { values ->
            values[KEY_TTS_SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun setTtsAutoPlayObserve(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_TTS_AUTO_PLAY_OBSERVE] = enabled }
    }

    suspend fun setRuntimeCaptureOverlayEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_RUNTIME_CAPTURE_OVERLAY] = enabled }
    }

    private companion object {
        val KEY_DEFAULT_CHARACTERS = stringPreferencesKey("default_characters")
        val KEY_AUTO_DISTILL = booleanPreferencesKey("auto_distill")
        val KEY_RESTORE_LAST_LOCATION = booleanPreferencesKey("restore_last_location")
        val KEY_LAST_RUN_ID = stringPreferencesKey("last_run_id")
        val KEY_LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val KEY_CHAT_FONT_SCALE = floatPreferencesKey("chat_font_scale")
        // 旧版枚举字号（small/standard/large）仅用于迁移读取
        val KEY_CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        val KEY_CHAT_COMPACT_MODE = booleanPreferencesKey("chat_compact_mode")
        val KEY_CHAT_SHOW_MODEL_REASONING = booleanPreferencesKey("chat_show_model_reasoning")
        val KEY_CHAT_BACKGROUND_IMAGE_URI = stringPreferencesKey("chat_background_image_uri")
        val KEY_CHAT_BACKGROUND_OPACITY = floatPreferencesKey("chat_background_opacity")
        val KEY_CHAT_BACKGROUND_BLUR_RADIUS = floatPreferencesKey("chat_background_blur_radius")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_SEED_COLOR = longPreferencesKey("theme_seed_color")
        val KEY_DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_UI_SCALE = floatPreferencesKey("ui_scale")
        val KEY_BUILT_IN_BACK_HANDLING_ENABLED = booleanPreferencesKey("built_in_back_handling_enabled")
        // 兼容旧版本曾用于控制系统预测返回的设置值。
        val KEY_LEGACY_PREDICTIVE_BACK_ENABLED = booleanPreferencesKey("predictive_back_enabled")
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_CHAT_NEURAL_SPEECH = booleanPreferencesKey("chat_neural_speech_enabled")
        val KEY_TTS_NEURAL_MODEL_ID = stringPreferencesKey("tts_neural_model_id")
        val KEY_TTS_NEURAL_MODEL_DIR = stringPreferencesKey("tts_neural_model_dir")
        val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val KEY_TTS_AUTO_PLAY_OBSERVE = booleanPreferencesKey("tts_auto_play_observe")
        val KEY_RUNTIME_CAPTURE_OVERLAY = booleanPreferencesKey("runtime_capture_overlay_enabled")
    }
}

private fun chatFontScaleFrom(scale: Float?, legacy: String?): Float =
    (scale ?: legacyFontScale(legacy)).coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)

private fun legacyFontScale(value: String?): Float = when (value) {
    "small" -> 0.9f
    "large" -> 1.15f
    else -> CHAT_FONT_SCALE_DEFAULT
}
