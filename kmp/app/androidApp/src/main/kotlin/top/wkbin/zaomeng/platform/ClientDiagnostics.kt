package top.wkbin.zaomeng.platform

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 客户端诊断的**互斥**实现（Android/JVM 用 `synchronized`）。
 *
 * 不用 `Mutex` + `runBlocking`：重组计数在**主线程**上高频发生，`runBlocking` 会为了
 * 「测量卡顿」而制造卡顿。核心区极短且不阻塞，用平台锁更合适。
 */
/**
 * 客户端诊断桥（R4 / R6 / R7）。
 *
 * 存在的理由：诊断的**读**侧（概览、导出、探针）全在 `:server`，而真正产生现场数据的地方
 * （导航、重组、帧、图片请求、repository 请求、聊天工具分发）在客户端模块里。
 * Android 内置后端与 UI **同进程**，因此这里用一个进程内全局收口承接客户端上报：
 * 生产方只管往这里写，`:server` 在构建概览/导出时同步读取，不需要新端点，也不会因为
 * 上报失败影响任何业务路径。
 *
 * 设计约束（与 `DiagnosticsSink` 一致）：
 * - 未注册 provider 时读取返回空快照，**绝不抛异常**；
 * - 所有入口自行截断（工程上限），超限丢弃并计入自身状态；
 * - 全部方法都是「只记录」，不改控制流、不吞业务异常。
 *
 * 线程模型：生产者可能来自主线程（导航/重组）与后台线程（HTTP/IO），
 * 统一走 [withClientDiagnosticsLock] 的短临界区。
 */
object ClientDiagnostics {

    // ------------------------------------------------------------------
    // 功能事件上报状态（R4）
    // ------------------------------------------------------------------

    const val STATUS_OK = "OK"
    const val STATUS_DEGRADED = "DEGRADED"
    const val STATUS_FAILED = "FAILED"

    const val TRAIL_NAVIGATION = "navigation"
    const val TRAIL_OPERATION = "operation"

    // ------------------------------------------------------------------
    // 值类型（core/runtime 不依赖 core/contracts，映射由 :server 负责）
    // ------------------------------------------------------------------

    data class TrailEntry(
        val at: String,
        val kind: String,
        val label: String,
        val detail: String,
    )

    data class FeatureEvent(
        val at: String,
        val feature: String,
        val action: String,
        val status: String,
        val durationMs: Long,
        val detail: String,
    )

    data class FrameBucket(
        val page: String,
        val frames: Long,
        val over16: Long,
        val over32: Long,
        val over100: Long,
        val longestMs: Long,
    )

    data class FrameSnapshot(
        val totalFrames: Long = 0,
        val over16: Long = 0,
        val over32: Long = 0,
        val over100: Long = 0,
        val longestMs: Long = 0,
        val pageBuckets: List<FrameBucket> = emptyList(),
        val mainThreadBlockCount: Long = 0,
        val mainThreadBlocks: List<String> = emptyList(),
        /**
         * 采样间断（应用切后台/息屏期间帧回调停止）的次数与总时长。
         *
         * 这些间隔**不计入** [totalFrames] / [longestMs] / [mainThreadBlockCount]：
         * 挂起期间 Choreographer 不再回调，恢复后的第一帧会把整段挂起时间算成一帧。
         * 单独上报是为了让读报告的人能区分「真的卡」与「采样断了」。
         */
        val samplingGaps: Long = 0,
        val samplingGapMs: Long = 0,
    )

    data class RecompositionStat(
        val name: String,
        val count: Long,
        val maxPerFrame: Long,
        val lastAt: String,
    )

    data class ImageRequestStat(
        val url: String,
        val requests: Long,
        val cacheHits: Long,
        val duplicateRequests: Long,
        val lastAt: String,
    )

    data class LayoutJitterStat(
        val name: String,
        val events: Long,
        val maxShiftDp: Int,
        val windowMs: Long,
        val lastAt: String,
    )

    data class FlickerRegion(
        val region: String,
        val changes: Int,
        val windowMs: Long,
        val at: String,
    )

    data class FlickerSnapshot(
        val enabled: Boolean = false,
        val supported: Boolean = false,
        val sampledFrames: Long = 0,
        val regions: List<FlickerRegion> = emptyList(),
        val note: String = "",
    )

    data class A11yNode(
        val page: String,
        val ref: String,
        val label: String,
        val clickable: Boolean,
        val widthDp: Int,
        val heightDp: Int,
    )

    /**
     * 采样开销。
     *
     * [note] 用来**如实说明这份开销覆盖了哪些采样器**——「显示一个像模像样的 0」
     * 比不显示更坏：用户会以为诊断不花钱。
     */
    data class SamplingCost(
        val overheadMs: Long = 0,
        val samples: Long = 0,
        val note: String = "",
    )

    /**
     * 按 `功能 × 操作` 累计的计数（R4）。
     *
     * 与 [FeatureEvent] 的区别：事件是**流水**（受环形缓冲上限约束，会被丢弃），
     * 这里是**累计计数**（只受 key 数量上限约束）。报告里必须用累计值，
     * 否则「这个操作用了 500 次、失败 480 次」会被环形缓冲截成看不出问题的样子。
     */
    data class FeatureAggregate(
        val feature: String,
        val action: String,
        val total: Long,
        val okCount: Long,
        val degradedCount: Long,
        val failedCount: Long,
        val totalDurationMs: Long,
        val maxDurationMs: Long,
        val firstAt: String,
        val lastAt: String,
        val lastStatus: String,
        val lastDetail: String,
    )

    // ------------------------------------------------------------------
    // 上限（工程上限，不是隐私截断）
    // ------------------------------------------------------------------

    private const val MAX_TRAIL_ENTRIES = 200
    private const val MAX_TRAIL_FIELD_CHARS = 300
    private const val MAX_MAIN_THREAD_BLOCK_CHARS = 1_200
    private const val MAX_RECOMPOSITION_KEYS = 60
    private const val MAX_IMAGE_KEYS = 60
    private const val MAX_JITTER_KEYS = 40
    private const val MAX_A11Y_NODES = 400
    private const val MAX_FEATURE_EVENTS = 400
    /** 累计计数的 key 上限：防止「每个 URL 都是一个 key」把内存吃光。 */
    private const val MAX_FEATURE_AGGREGATE_KEYS = 200
    private const val DEFAULT_JITTER_WINDOW_MS = 300L

    /** 供导出侧换算百分比用。 */
    const val MAX_FEATURE_EVENT_ENTRIES = MAX_FEATURE_EVENTS

    private val trail = ArrayDeque<TrailEntry>()
    private val featureEvents = ArrayDeque<FeatureEvent>()
    private val featureAggregates = LinkedHashMap<String, FeatureAggregate>()
    private val recompositions = LinkedHashMap<String, Long>()
    private val recompositionMaxPerFrame = LinkedHashMap<String, Long>()
    private val recompositionLastAt = LinkedHashMap<String, String>()
    /** 当前帧序号（由平台帧回调推进）；用于识别「一帧里重组很多次」。 */
    private var frameId: Long = 0
    private val recompositionPerFrame = LinkedHashMap<String, Long>()
    private val recompositionLastFrame = LinkedHashMap<String, Long>()
    private val imageRequests = LinkedHashMap<String, Long>()
    private val imageCacheHits = LinkedHashMap<String, Long>()
    private val imageDuplicates = LinkedHashMap<String, Long>()
    private val imageLastAt = LinkedHashMap<String, String>()
    private val jitterEvents = LinkedHashMap<String, Long>()
    private val jitterMaxShift = LinkedHashMap<String, Int>()
    private val jitterLastAt = LinkedHashMap<String, String>()
    private val a11yNodes = ArrayDeque<A11yNode>()

    private var frameProvider: (() -> FrameSnapshot)? = null
    private var flickerProvider: (() -> FlickerSnapshot)? = null
    private var samplingCostProvider: (() -> SamplingCost)? = null

    /** 丢弃计数：超过环形缓冲上限时累加，用来回答「诊断自己丢了多少」。 */
    private var dropped = 0L

    @Volatile
    private var samplingOverheadMillis: Long = 0

    @Volatile
    private var samplingSampleCount: Long = 0

    // ------------------------------------------------------------------
    // 挂接（由平台实现在启动时调用；未挂接时读取为空/不支持）
    // ------------------------------------------------------------------

    fun attachFrameProvider(provider: (() -> FrameSnapshot)?) = withClientDiagnosticsLock {
        frameProvider = provider
    }

    fun attachFlickerProvider(provider: (() -> FlickerSnapshot)?) = withClientDiagnosticsLock {
        flickerProvider = provider
    }

    fun attachSamplingCostProvider(provider: (() -> SamplingCost)?) = withClientDiagnosticsLock {
        samplingCostProvider = provider
    }

    // ------------------------------------------------------------------
    // 写入侧
    // ------------------------------------------------------------------

    /**
     * 通用功能事件上报（R4）。
     *
     * @param feature 功能标识（如 `repository` / `route` / `chat.tool`）
     * @param action 操作标识（如 HTTP 路径 / repository 方法 / 工具名）
     * @param status [STATUS_OK] / [STATUS_DEGRADED] / [STATUS_FAILED] —— **成功也要报**，
     *   否则「成功」在没有对照的情况下无法判断。
     * @param durationMs 耗时；未知传 0
     * @param detail 细节（截断；**绝不允许放密钥**，写入侧与导出侧都会过脱敏）
     */
    fun report(
        feature: String,
        action: String,
        status: String,
        durationMs: Long = 0,
        detail: String = "",
    ) {
        val event = FeatureEvent(
            at = nowIso(),
            feature = feature.trim().take(80).ifBlank { "unknown" },
            action = action.trim().take(160).ifBlank { "unknown" },
            status = normalizeStatus(status),
            durationMs = durationMs.coerceAtLeast(0),
            detail = detail.trim().take(MAX_TRAIL_FIELD_CHARS),
        )
        withClientDiagnosticsLock {
            featureEvents.addLast(event)
            while (featureEvents.size > MAX_FEATURE_EVENTS) {
                featureEvents.removeFirst()
                dropped++
            }
            val key = "${event.feature}|${event.action}"
            val previous = featureAggregates[key]
            featureAggregates[key] = FeatureAggregate(
                feature = event.feature,
                action = event.action,
                total = (previous?.total ?: 0L) + 1,
                okCount = (previous?.okCount ?: 0L) + if (event.status == STATUS_OK) 1 else 0,
                degradedCount = (previous?.degradedCount ?: 0L) + if (event.status == STATUS_DEGRADED) 1 else 0,
                failedCount = (previous?.failedCount ?: 0L) + if (event.status == STATUS_FAILED) 1 else 0,
                totalDurationMs = (previous?.totalDurationMs ?: 0L) + event.durationMs,
                maxDurationMs = maxOf(previous?.maxDurationMs ?: 0L, event.durationMs),
                firstAt = previous?.firstAt ?: event.at,
                lastAt = event.at,
                lastStatus = event.status,
                lastDetail = event.detail,
            )
            while (featureAggregates.size > MAX_FEATURE_AGGREGATE_KEYS) {
                val oldest = featureAggregates.keys.firstOrNull() ?: break
                featureAggregates.remove(oldest)
                dropped++
            }
        }
    }

    /** 记一条导航历史（R6）。 */
    fun recordNavigation(label: String, detail: String = "") {
        recordTrail(TRAIL_NAVIGATION, label, detail)
    }

    /** 记一条关键操作（R6）。 */
    fun recordOperation(label: String, detail: String = "") {
        recordTrail(TRAIL_OPERATION, label, detail)
    }

    private fun recordTrail(kind: String, label: String, detail: String) {
        val entry = TrailEntry(
            at = nowIso(),
            kind = kind,
            label = label.trim().take(MAX_TRAIL_FIELD_CHARS).ifBlank { "(未命名)" },
            detail = detail.trim().take(MAX_TRAIL_FIELD_CHARS),
        )
        withClientDiagnosticsLock {
            trail.addLast(entry)
            while (trail.size > MAX_TRAIL_ENTRIES) {
                trail.removeFirst()
                dropped++
            }
        }
    }

    /**
     * 平台帧回调每来一帧调用一次（R7）。
     *
     * 只推进帧序号：重组计数靠它区分「一帧里重组了 7 次」与「7 帧各重组 1 次」——
     * 前者才是过度重组。
     */
    fun onFrame() {
        withClientDiagnosticsLock { frameId++ }
    }

    /**
     * 记一次重组（R7）。
     *
     * 由组合期的探针在每次组合执行时调用。同名字在同一帧内被调用多次时，
     * [RecompositionStat.maxPerFrame] 会记录下来——这正是「过度重组」的判定依据。
     */
    fun recordRecomposition(name: String) {
        val key = name.trim().take(120).ifBlank { "(anonymous)" }
        withClientDiagnosticsLock {
            recompositions[key] = (recompositions[key] ?: 0L) + 1
            val current = if (recompositionLastFrame[key] == frameId) {
                (recompositionPerFrame[key] ?: 0L) + 1
            } else {
                1L
            }
            recompositionPerFrame[key] = current
            recompositionLastFrame[key] = frameId
            recompositionMaxPerFrame[key] = maxOf(recompositionMaxPerFrame[key] ?: 0L, current)
            recompositionLastAt[key] = nowIso()
            trimOldest(recompositions, MAX_RECOMPOSITION_KEYS)
            trimOldest(recompositionMaxPerFrame, MAX_RECOMPOSITION_KEYS)
            trimOldest(recompositionLastAt, MAX_RECOMPOSITION_KEYS)
            trimOldest(recompositionPerFrame, MAX_RECOMPOSITION_KEYS)
            trimOldest(recompositionLastFrame, MAX_RECOMPOSITION_KEYS)
        }
    }

    /**
     * 记一次图片请求（R7）。
     *
     * @param cacheHit 命中本地缓存（没有真的发请求）
     * @param duplicate 同 URL 在缓存未失效的情况下又拉了一次（真正的重复请求）
     */
    fun recordImageRequest(url: String, cacheHit: Boolean, duplicate: Boolean = false) {
        val key = url.trim().take(300).ifBlank { "(unknown)" }
        withClientDiagnosticsLock {
            if (cacheHit) {
                imageCacheHits[key] = (imageCacheHits[key] ?: 0L) + 1
            } else {
                imageRequests[key] = (imageRequests[key] ?: 0L) + 1
                if (duplicate) imageDuplicates[key] = (imageDuplicates[key] ?: 0L) + 1
            }
            imageLastAt[key] = nowIso()
            // 三个 map 共用同一批 key；裁剪时以 requests 为准，避免漏 key 造成口径不一致。
            trimImageKeys(MAX_IMAGE_KEYS)
        }
    }

    /** 记一次布局跳动（R7）：同一关键节点在窗口内发生位移。 */
    fun recordLayoutJitter(name: String, shiftDp: Int, windowMs: Long = DEFAULT_JITTER_WINDOW_MS) {
        val key = name.trim().take(120).ifBlank { "(anonymous)" }
        withClientDiagnosticsLock {
            jitterEvents[key] = (jitterEvents[key] ?: 0L) + 1
            jitterMaxShift[key] = maxOf(jitterMaxShift[key] ?: 0, shiftDp)
            jitterLastAt[key] = nowIso()
            trimOldest(jitterEvents, MAX_JITTER_KEYS)
            trimOldest(jitterMaxShift, MAX_JITTER_KEYS)
            trimOldest(jitterLastAt, MAX_JITTER_KEYS)
            windowMs // 目前窗口由采样侧固定；保留参数以便真机校准
        }
    }

    /**
     * 这个 URL 之前是否已经真的请求过（R7 重复请求判定）。
     *
     * 由调用方在**缓存未命中**时问一次：命中过说明这是同 URL 的重复拉取，
     * 而重复拉取正是「刷新一下就好了」这类闪烁的常见来源。
     */
    fun hasRequestedImage(url: String): Boolean = withClientDiagnosticsLock {
        (imageRequests[url.trim().take(300)] ?: 0L) > 0
    }

    /** 上报一次可访问性采样结果（R6）：整批替换，避免历史节点无限堆积。 */
    fun replaceA11yNodes(nodes: List<A11yNode>) {
        withClientDiagnosticsLock {
            a11yNodes.clear()
            nodes.takeLast(MAX_A11Y_NODES).forEach { a11yNodes.addLast(it) }
        }
    }

    fun recordSamplingOverhead(millis: Long) {
        if (millis <= 0) return
        samplingOverheadMillis += millis
        samplingSampleCount += 1
    }

    // ------------------------------------------------------------------
    // 读取侧（:server 在构建概览/导出时调用）
    // ------------------------------------------------------------------

    fun trailEntries(): List<TrailEntry> = withClientDiagnosticsLock { trail.toList() }

    /**
     * 最近一次**导航**的目标名（R7 页面分桶用）。
     *
     * 与 [trailEntries] 分开：帧回调每 16ms 都可能问一次，复制 200 条轨迹太浪费。
     * 这里只从尾部往前找第一条导航记录。
     */
    fun lastPageLabel(): String = withClientDiagnosticsLock {
        trail.lastOrNull { it.kind == TRAIL_NAVIGATION }?.label.orEmpty()
    }

    fun recentFeatureEvents(limit: Int = MAX_FEATURE_EVENTS): List<FeatureEvent> =
        withClientDiagnosticsLock { featureEvents.toList().takeLast(limit.coerceIn(1, MAX_FEATURE_EVENTS)) }

    /** 累计的功能事件计数（报告里用它，不用流水长度）。 */
    fun featureAggregates(): List<FeatureAggregate> = withClientDiagnosticsLock {
        featureAggregates.values.sortedWith(
            compareByDescending<FeatureAggregate> { it.failedCount }
                .thenByDescending { it.total },
        )
    }

    fun recompositionStats(): List<RecompositionStat> = withClientDiagnosticsLock {
        recompositions.entries.map { (key, count) ->
            RecompositionStat(
                name = key,
                count = count,
                maxPerFrame = recompositionMaxPerFrame[key] ?: 0L,
                lastAt = recompositionLastAt[key].orEmpty(),
            )
        }.sortedByDescending { it.count }
    }

    fun imageRequestStats(): List<ImageRequestStat> = withClientDiagnosticsLock {
        imageRequests.entries.map { (key, count) ->
            ImageRequestStat(
                url = key,
                requests = count,
                cacheHits = imageCacheHits[key] ?: 0L,
                duplicateRequests = imageDuplicates[key] ?: 0L,
                lastAt = imageLastAt[key].orEmpty(),
            )
        }.sortedWith(compareByDescending<ImageRequestStat> { it.duplicateRequests }.thenByDescending { it.requests })
    }

    fun layoutJitterStats(): List<LayoutJitterStat> = withClientDiagnosticsLock {
        jitterEvents.entries.map { (key, count) ->
            LayoutJitterStat(
                name = key,
                events = count,
                maxShiftDp = jitterMaxShift[key] ?: 0,
                windowMs = DEFAULT_JITTER_WINDOW_MS,
                lastAt = jitterLastAt[key].orEmpty(),
            )
        }.sortedByDescending { it.events }
    }

    fun a11yNodeSnapshot(): List<A11yNode> = withClientDiagnosticsLock { a11yNodes.toList() }

    fun frameSnapshot(): FrameSnapshot =
        runCatching { withClientDiagnosticsLock { frameProvider }?.invoke() }.getOrNull() ?: FrameSnapshot()

    fun flickerSnapshot(): FlickerSnapshot =
        runCatching { withClientDiagnosticsLock { flickerProvider }?.invoke() }.getOrNull() ?: FlickerSnapshot()

    fun samplingCost(): SamplingCost {
        val provider = runCatching { withClientDiagnosticsLock { samplingCostProvider } }.getOrNull()
        val fromProvider = runCatching { provider?.invoke() }.getOrNull()
        return SamplingCost(
            overheadMs = samplingOverheadMillis + (fromProvider?.overheadMs ?: 0L),
            samples = samplingSampleCount + (fromProvider?.samples ?: 0L),
            note = fromProvider?.note.orEmpty(),
        )
    }

    fun droppedCount(): Long = withClientDiagnosticsLock { dropped }

    /** 测试与平台重启时清空；生产路径不调用。 */
    fun resetForTest() {
        withClientDiagnosticsLock {
            trail.clear()
            featureEvents.clear()
            featureAggregates.clear()
            recompositions.clear()
            recompositionMaxPerFrame.clear()
            recompositionLastAt.clear()
            recompositionPerFrame.clear()
            recompositionLastFrame.clear()
            frameId = 0
            imageRequests.clear()
            imageCacheHits.clear()
            imageDuplicates.clear()
            imageLastAt.clear()
            jitterEvents.clear()
            jitterMaxShift.clear()
            jitterLastAt.clear()
            a11yNodes.clear()
            frameProvider = null
            flickerProvider = null
            samplingCostProvider = null
            dropped = 0
        }
        samplingOverheadMillis = 0
        samplingSampleCount = 0
    }

    private fun normalizeStatus(status: String): String = when (status.trim().uppercase()) {
        STATUS_OK, "SUCCESS" -> STATUS_OK
        STATUS_DEGRADED, "DEGRADE", "FALLBACK" -> STATUS_DEGRADED
        STATUS_FAILED, "FAIL", "ERROR" -> STATUS_FAILED
        else -> status.trim().uppercase().take(20).ifBlank { STATUS_OK }
    }

    private fun <V> trimOldest(map: LinkedHashMap<String, V>, limit: Int) {
        while (map.size > limit) {
            val oldest = map.keys.firstOrNull() ?: return
            map.remove(oldest)
        }
    }

    private fun trimImageKeys(limit: Int) {
        while (imageRequests.size > limit) {
            val oldest = imageRequests.keys.firstOrNull() ?: return
            imageRequests.remove(oldest)
            imageCacheHits.remove(oldest)
            imageDuplicates.remove(oldest)
            imageLastAt.remove(oldest)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowIso(): String = Clock.System.now().toString()
}
