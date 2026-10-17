package top.wkbin.zaomeng.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 某个系统 TTS 引擎暴露的一个音色。
 *
 * ## 为什么要有这个类型
 *
 * 接第三方引擎（NekoSpeak 等）时，`TextToSpeech.voices` 返回的是 Android 的 [Voice]，
 * 它带 [Locale]、质量、是否需要联网等字段，但**不能直接进 UI 层**：
 * - 它是平台类，单元测试里造不出来；
 * - `name` 往往是引擎内部标识（如 `neko:zh-CN-xiaobei`），直接展示很难看；
 * - 排序规则（中文优先、同语言按名称）是产品决策，应该可测。
 *
 * 所以这里做一次纯值转换，把排序与检索都做成纯函数。
 */
data class SystemTtsVoice(
    /** 引擎内部标识，**写进人物包 `voice_name` 的就是它**。 */
    val name: String,
    /** BCP-47 语言标签，如 `zh-CN`、`en-US`。 */
    val locale: String,
    /** 引擎自评质量（[Voice.QUALITY_*]，0 表示未知）。 */
    val quality: Int = 0,
    /** 是否需要联网才能合成。 */
    val requiresNetwork: Boolean = false,
    /** 是否是该引擎的默认音色。 */
    val isDefault: Boolean = false,
) {
    val isChinese: Boolean get() = locale.startsWith("zh", ignoreCase = true)

    /**
     * 给人看的名字：去掉引擎前缀（`neko:zh-CN-xiaobei` → `zh-CN-xiaobei`）。
     *
     * 只截最后一个分隔符之后的片段，且要求前缀非空、后缀非空——
     * 否则像 `urn:mpeg:...` 这类本身就是复合标识的名字会被截得面目全非。
     */
    val label: String
        get() {
            val raw = name.trim()
            val cut = raw.lastIndexOfAny(charArrayOf(':', '/', '#'))
            return if (cut in 1 until raw.lastIndex) raw.substring(cut + 1) else raw
        }

    /** 语言的中文说明，界面副标题用。 */
    val localeLabel: String
        get() = when {
            locale.isBlank() -> "语言未知"
            isChinese -> if (locale.contains("TW", true) || locale.contains("HK", true)) "中文（繁体）" else "中文"
            locale.startsWith("en", true) -> "英语"
            locale.startsWith("ja", true) -> "日语"
            locale.startsWith("ko", true) -> "韩语"
            else -> locale
        }

    val qualityLabel: String
        get() = when (quality) {
            Voice.QUALITY_VERY_HIGH -> "音质极佳"
            Voice.QUALITY_HIGH -> "音质良好"
            Voice.QUALITY_NORMAL -> "音质一般"
            Voice.QUALITY_LOW -> "音质较低"
            else -> ""
        }
}

/** 平台 [Voice] → [SystemTtsVoice]。 */
fun Voice.toSystemTtsVoice(defaultVoiceName: String = ""): SystemTtsVoice = SystemTtsVoice(
    name = name.orEmpty(),
    locale = locale?.toLanguageTag().orEmpty(),
    quality = quality,
    requiresNetwork = isNetworkConnectionRequired,
    isDefault = defaultVoiceName.isNotBlank() && name == defaultVoiceName,
)

/**
 * 排序 + 检索。**纯函数**，便于单测。
 *
 * 排序规则：中文在前（这是中文小说应用，英文音色是补充），同语言按 locale、再按名称。
 * 不按音质排序：引擎自评质量普遍虚高（大量 `QUALITY_NORMAL`），按它排反而显得杂乱；
 * 名称稳定，用户第二次进来还能在原来的位置找到同一个音色。
 */
fun orderSystemTtsVoices(
    voices: List<SystemTtsVoice>,
    query: String = "",
): List<SystemTtsVoice> {
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) {
        voices
    } else {
        voices.filter { voice ->
            voice.name.contains(keyword, ignoreCase = true) ||
                voice.label.contains(keyword, ignoreCase = true) ||
                voice.locale.contains(keyword, ignoreCase = true)
        }
    }
    return filtered.sortedWith(
        compareBy<SystemTtsVoice> { if (it.isChinese) 0 else 1 }
            .thenBy { it.locale.lowercase() }
            .thenBy { it.name.lowercase() },
    )
}

/**
 * 读取某个系统 TTS 引擎的音色清单。
 *
 * ## 为什么要真正绑定一次引擎
 *
 * `TextToSpeech.voices` 只有在初始化成功后才可读；而 `queryIntentServices`（见 [listSystemTtsEngines]）
 * 只告诉你**装了哪些引擎**，拿不到音色。所以这里必须创建一个实例、等 `OnInitListener` 回调、读完再 `shutdown()`。
 *
 * ## 三个必须处理的坑
 *
 * 1. **回调可能永远不来**（引擎崩溃/被系统冻结），必须设超时，否则设置页会一直转圈；
 * 2. **`voices` 可能为 null 或空**：NekoSpeak 的音色要先在它自己的界面里下载，没下载时就是空表——
 *    这不是错误，要区分「引擎报错」与「引擎没音色」；
 * 3. **必须 shutdown**：TTS 实例会拉起对方进程并常驻，读完不释放等于泄漏一个服务连接。
 */
suspend fun loadSystemTtsVoices(
    context: Context,
    enginePackage: String,
    timeoutMillis: Long = INIT_TIMEOUT_MS,
): Result<List<SystemTtsVoice>> = withContext(Dispatchers.Main) {
    val appContext = context.applicationContext
    var engine: TextToSpeech? = null
    try {
        val loaded = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                var settled = false

                fun finish(result: Result<List<SystemTtsVoice>>) {
                    if (settled) return
                    settled = true
                    continuation.resume(result)
                }

                val listener = TextToSpeech.OnInitListener { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        finish(
                            Result.failure(
                                IllegalStateException(
                                    "语音引擎初始化失败（status=$status）。请确认该引擎仍已安装且可用。",
                                ),
                            ),
                        )
                        return@OnInitListener
                    }
                    val current = engine
                    if (current == null) {
                        finish(Result.failure(IllegalStateException("语音引擎实例已释放。")))
                        return@OnInitListener
                    }
                    val result = runCatching {
                        val defaultName = runCatching { current.defaultVoice?.name }.getOrNull().orEmpty()
                        (current.voices ?: emptySet())
                            .map { it.toSystemTtsVoice(defaultName) }
                            .filter { it.name.isNotBlank() }
                    }
                    finish(result)
                }

                engine = if (enginePackage.isBlank()) {
                    TextToSpeech(appContext, listener)
                } else {
                    TextToSpeech(appContext, listener, enginePackage)
                }

                continuation.invokeOnCancellation {
                    runCatching { engine?.shutdown() }
                }
            }
        }
        loaded ?: Result.failure(
            IllegalStateException(
                "语音引擎在 ${timeoutMillis / 1000} 秒内没有响应。它可能正在初始化，稍后重试一次。",
            ),
        )
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        runCatching { engine?.shutdown() }
    }
}

/** 引擎初始化超时。NekoSpeak 首次冷启动要加载模型索引，8 秒是实测的宽裕值。 */
private const val INIT_TIMEOUT_MS = 8_000L

/** 当前引擎是不是 NekoSpeak（界面用来给「去哪下载音色」的指引）。 */
fun isNekoSpeakEngine(enginePackage: String): Boolean =
    enginePackage == SystemTtsEngine.NEKO_SPEAK_PACKAGE
