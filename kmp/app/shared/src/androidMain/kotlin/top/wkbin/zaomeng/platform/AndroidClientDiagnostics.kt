package top.wkbin.zaomeng.platform

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

/**
 * Android 侧客户端诊断采样（R6 可访问性 / R7 帧与流畅度）。
 *
 * 与 `ClientDiagnostics`（commonMain 的进程内收口）之间的关系：
 * 这里是**生产者**，只负责「采样 → 上报」，不做任何判定；判定与措辞在 `:server`。
 *
 * 三条硬约束：
 * 1. **只采样、不改控制流**：所有回调里都只有 runCatching 包裹的上报，绝不影响 UI；
 * 2. **像素不落盘**：疑似闪烁的采样只在内存里比较 `Bitmap`，**没有任何文件写入调用**
 *    （也没有 `PixelCopy` / `MediaProjection`——截图来自 `decorView.draw(Canvas)`）；
 * 3. **默认关闭**：疑似闪烁默认不开，需要显式 [setFlickerEnabled]。
 *
 * 线程模型：帧回调与 ANR 式卡顿看门狗都在主线程/独立线程上，上报走
 * `ClientDiagnostics` 的短临界区。
 */
object AndroidClientDiagnostics {

    /**
     * 掉帧分档阈值（**纳秒**）：与需求的 >16.7ms / >32ms / >100ms 三档一致。
     *
     * ⚠️ 必须按纳秒比较，不能先转成毫秒再比：
     * 60Hz 一帧是 16.67ms，`nanos / 1_000_000` **截断**成 16，
     * 用「毫秒 >= 16」判掉帧会把**每一帧**都算进 >16.7ms 档（`over16 ≈ totalFrames`），
     * 而 120Hz 又几乎一帧都不进——同一个指标在两种屏幕上给出相反的错误结论。
     */
    private const val FRAME_16_7_NANOS = 16_666_667L
    private const val FRAME_32_NANOS = 33_333_333L
    private const val FRAME_100_NANOS = 100_000_000L

    /**
     * 采样器状态的互斥锁（E）。
     *
     * 生产者分属三个线程：主线程（帧回调）、看门狗线程（长任务）、闪烁/可访问性线程；
     * 读者是 **Ktor 线程**（HTTP 请求里读快照）。`ArrayDeque.toList()` 并发会抛异常，
     * 而 `ClientDiagnostics.frameSnapshot()` 的 `runCatching` 会**把它吞掉**——
     * 结果是「帧统计整段变空且不报错」，也就是诊断自己瞎了却没人知道。
     */
    private val stateLock = Any()

    /**
     * 主线程长任务判定阈值（毫秒）。
     *
     * 取 200ms：一帧 16.7ms，200ms 已经是「肉眼可见的卡住」，
     * 低于它会把正常的重布局/大 JSON 解析全算成长任务。
     */
    private const val MAIN_THREAD_BLOCK_MS = 200L

    /** 卡顿看门狗轮询间隔。 */
    private const val WATCHDOG_INTERVAL_MS = 100L

    /** 同一段卡顿最多抓几条栈（避免长时间卡死刷屏）。 */
    private const val MAX_BLOCK_SAMPLES = 12

    /** 单条栈保留的**最内层**帧数。 */
    private const val STACK_FRAMES = 24

    /**
     * 单条栈额外保留的**本应用**帧数。
     *
     * 为什么必须补这一段：`Thread.stackTrace` 的顺序是「栈顶在前」，而栈顶几乎总是
     * 框架自身（`kotlin.reflect.*`、`androidx.compose.*`、`android.os.*`）。
     * 只取前 24 帧时，真正「谁触发的」——也就是应用自己的调用点——在栈底，**被裁掉了**。
     * 真机导出里 1481ms 那条最长帧就只剩 kotlin-reflect 的元数据解析帧，无法定位触发点。
     */
    private const val STACK_APP_FRAMES = 14

    /** 本应用包名前缀：用于从栈里挑出「我们自己的调用点」。 */
    private const val APP_PACKAGE_PREFIX = "top.wkbin.zaomeng"

    /** 疑似闪烁的采样间隔（低频）。 */
    private const val FLICKER_INTERVAL_MS = 500L

    /** 疑似闪烁的判定窗口：窗口内同一网格变化达到该次数才上报。 */
    private const val FLICKER_WINDOW_MS = 300L
    private const val FLICKER_CHANGE_THRESHOLD = 3

    /** 采样用的网格（宽 × 高），越小越省。 */
    private const val GRID_W = 24
    private const val GRID_H = 24

    private val installed = AtomicBoolean(false)

    @Volatile
    private var activityRef: Activity? = null

    @Volatile
    private var flickerEnabled = false

    // ---- 帧指标（只由主线程写，读取时快照） ----
    private var lastFrameNanos = 0L
    private var totalFrames = 0L
    private var over16 = 0L
    private var over32 = 0L
    private var over100 = 0L
    private var longestMs = 0L
    private val pageBuckets = LinkedHashMap<String, LongArray>()

    // ---- 主线程长任务 ----
    private var blockCount = 0L
    private val blockSamples = ArrayDeque<String>()

    @Volatile
    private var lastFrameAtMillis = 0L

    private var watchdogThread: Thread? = null

    @Volatile
    private var running = false

    /** 采样器自身耗时（纳秒）与采样次数：由各采样器累加，供「诊断自身状态」读取（D）。 */
    private val samplingNanos = java.util.concurrent.atomic.AtomicLong(0)

    private val samplingSamples = java.util.concurrent.atomic.AtomicLong(0)

    /** 同一时刻只排一个闪烁采样，避免连点把 UI 线程的消息队列堆满。 */
    private val flickerSampleQueued = AtomicBoolean(false)

    /**
     * 挂上采样器。幂等：重复调用只保留最后一次的 Activity 引用。
     *
     * 从 `MainActivity.onCreate` 调用；失败一律吞掉——诊断采样不允许影响启动。
     */
    fun install(activity: Activity) {
        activityRef = activity
        if (installed.getAndSet(true)) return
        runCatching {
            ClientDiagnostics.attachFrameProvider { snapshot() }
            ClientDiagnostics.attachFlickerProvider { flickerSnapshot() }
            // D：把三个采样器的真实成本报上去，并**如实写明覆盖范围**。
            ClientDiagnostics.attachSamplingCostProvider {
                ClientDiagnostics.SamplingCost(
                    overheadMs = samplingNanos.get() / 1_000_000L,
                    samples = samplingSamples.get(),
                    note = "本数字统计的是：帧回调、主线程长任务看门狗、疑似闪烁采样、可访问性采样" +
                        "（各自只统计自己那一段耗时）。不含 Ktor/数据库/模型的业务耗时。",
                )
            }
            running = true
            lastFrameAtMillis = System.currentTimeMillis()
            Choreographer.getInstance().postFrameCallback(frameCallback)
            startWatchdog()
            startFlickerSampler()
            startAccessibilitySampler()
        }
    }

    /** 关闭采样（进程退出/测试用）。 */
    fun uninstall() {
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        activityRef = null
        installed.set(false)
    }

    /**
     * 疑似闪烁采样开关（**默认关闭**）。
     *
     * 本版没有为它加界面开关：开启需要调用方显式调用本方法。
     * 之所以不默认开启：屏幕采样在低端机上本身就会掉帧，用它排查闪烁会先制造闪烁。
     */
    fun setFlickerEnabled(enabled: Boolean) {
        flickerEnabled = enabled
    }

    // ------------------------------------------------------------------
    // 帧指标
    // ------------------------------------------------------------------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val samplerStarted = System.nanoTime()
            runCatching {
                ClientDiagnostics.onFrame()
                val previous = lastFrameNanos
                lastFrameNanos = frameTimeNanos
                lastFrameAtMillis = System.currentTimeMillis()
                if (previous != 0L) {
                    recordFrame((frameTimeNanos - previous).coerceAtLeast(0L))
                }
                Choreographer.getInstance().postFrameCallback(this)
                // D：把本采样器自身耗时累加上去（纳秒精度；毫秒级取整在单帧上会永远是 0）。
                samplingNanos.addAndGet(System.nanoTime() - samplerStarted)
                samplingSamples.incrementAndGet()
            }.onFailure {
                // 采样断了就断了，绝不因为诊断把自己搞崩。
                running = false
            }
        }
    }

    /**
     * 记一帧。
     *
     * @param deltaNanos 与上一帧的间隔（**纳秒**，不截断；见 [FRAME_16_7_NANOS] 的说明）。
     */
    private fun recordFrame(deltaNanos: Long) {
        // 毫秒值只用于展示，向上取整（16.67ms 显示成 17ms 比显示成 16ms 诚实）。
        val deltaMs = (deltaNanos + 999_999L) / 1_000_000L
        // 页面名要在锁外取：它会进 ClientDiagnostics 自己的锁，避免嵌套锁顺序问题。
        val page = runCatching { ClientDiagnostics.lastPageLabel() }.getOrDefault("").ifBlank { "(未知页面)" }
        synchronized(stateLock) {
            totalFrames++
            when {
                deltaNanos >= FRAME_100_NANOS -> over100++
                deltaNanos >= FRAME_32_NANOS -> over32++
                deltaNanos >= FRAME_16_7_NANOS -> over16++
            }
            if (deltaMs > longestMs) longestMs = deltaMs
            // 按页面分桶：页面名取自最近一次导航上报（客户端轨迹）。
            val bucket = pageBuckets.getOrPut(page) { LongArray(5) }
            bucket[0]++
            if (deltaNanos >= FRAME_100_NANOS) {
                bucket[3]++
            } else if (deltaNanos >= FRAME_32_NANOS) {
                bucket[2]++
            } else if (deltaNanos >= FRAME_16_7_NANOS) {
                bucket[1]++
            }
            if (deltaMs > bucket[4]) bucket[4] = deltaMs
        }
        // 桶数量有界：超出时丢掉最早的非当前页面，避免长会话里无限增长。
        synchronized(stateLock) {
            while (pageBuckets.size > MAX_PAGE_BUCKETS) {
                val oldest = pageBuckets.keys.firstOrNull { it != page } ?: break
                pageBuckets.remove(oldest)
            }
        }
    }

    /**
     * 取一份帧指标快照。
     *
     * 全程持锁复制（E）：读侧是 Ktor 线程，写侧有主线程与看门狗线程；
     * 不持锁的 `ArrayDeque.toList()` 并发会抛异常，而异常会被上层 `runCatching` 吞成空快照。
     */
    private fun snapshot(): ClientDiagnostics.FrameSnapshot = synchronized(stateLock) {
        ClientDiagnostics.FrameSnapshot(
            totalFrames = totalFrames,
            over16 = over16,
            over32 = over32,
            over100 = over100,
            longestMs = longestMs,
            pageBuckets = pageBuckets.entries.map { (page, bucket) ->
                ClientDiagnostics.FrameBucket(
                    page = page,
                    frames = bucket[0],
                    over16 = bucket[1],
                    over32 = bucket[2],
                    over100 = bucket[3],
                    longestMs = bucket[4],
                )
            }.sortedWith(
                compareByDescending<ClientDiagnostics.FrameBucket> { it.over100 }
                    .thenByDescending { it.over32 },
            ),
            mainThreadBlockCount = blockCount,
            mainThreadBlocks = blockSamples.toList(),
        )
    }

    /**
     * 主线程长任务看门狗（R7）。
     *
     * 原理：帧回调会不断刷新 `lastFrameAtMillis`；看门狗线程每 100ms 检查一次，
     * 若超过 [MAIN_THREAD_BLOCK_MS] 没有新帧，说明主线程被占住了——
     * 此时从**看门狗线程**读主线程 [`Thread.getStackTrace`]，拿到的正是「当时栈」。
     *
     * 为什么要「当时栈」：只报「卡了 800ms」无法定位；有了栈就知道是 JSON 解析、
     * 数据库查询还是图片解码。
     */
    private fun startWatchdog() {
        if (watchdogThread != null) return
        val thread = Thread({
            var reportedForThisStall = false
            while (running) {
                runCatching {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                    val gap = System.currentTimeMillis() - lastFrameAtMillis
                    if (gap >= MAIN_THREAD_BLOCK_MS) {
                        if (!reportedForThisStall) {
                            reportedForThisStall = true
                            val stack = formatWatchdogStack(
                                Looper.getMainLooper().thread.stackTrace.toList(),
                                appPackagePrefix = APP_PACKAGE_PREFIX,
                                innermostFrames = STACK_FRAMES,
                                appFrames = STACK_APP_FRAMES,
                            )
                            synchronized(stateLock) {
                                blockCount++
                                if (blockSamples.size >= MAX_BLOCK_SAMPLES) blockSamples.removeFirst()
                                blockSamples.addLast("主线程阻塞约 ${gap}ms：\n$stack")
                            }
                        }
                    } else {
                        reportedForThisStall = false
                    }
                }
            }
        }, "zaomeng-diagnostics-watchdog")
        thread.isDaemon = true
        thread.start()
        watchdogThread = thread
    }

    // ------------------------------------------------------------------
    // 疑似闪烁（默认关闭；像素只在内存比较）
    // ------------------------------------------------------------------

    private var previousGrid: IntArray? = null
    private var sampledFrames = 0L
    /** 区域 → [最近一次变化时间(epoch ms), 窗口内变化次数]。 */
    private val flickerRegions = LinkedHashMap<String, LongArray>()

    private fun flickerSnapshot(): ClientDiagnostics.FlickerSnapshot = synchronized(stateLock) {
        ClientDiagnostics.FlickerSnapshot(
        enabled = flickerEnabled,
        supported = activityRef != null,
        sampledFrames = sampledFrames,
        regions = flickerRegions.entries.mapNotNull { (region, value) ->
            if (value[1] < FLICKER_CHANGE_THRESHOLD) {
                null
            } else {
                ClientDiagnostics.FlickerRegion(
                    region = region,
                    changes = value[1].toInt(),
                    windowMs = FLICKER_WINDOW_MS,
                    at = java.util.Date(value[0]).toString(),
                )
            }
        }.sortedByDescending { it.changes }.take(12),
        note = if (!flickerEnabled) {
            "默认关闭。开启后为 ${FLICKER_INTERVAL_MS}ms 低频采样，像素只在本地内存逐格比较，不落盘、不外传、不进导出。"
        } else {
            "低频采样中（每 ${FLICKER_INTERVAL_MS}ms 一帧，${GRID_W}×${GRID_H} 网格，只在内存比较）。"
        },
        )
    }

    private fun startFlickerSampler() {
        val thread = Thread({
            while (running) {
                runCatching {
                    Thread.sleep(FLICKER_INTERVAL_MS)
                    if (flickerEnabled) sampleOnce()
                }
            }
        }, "zaomeng-diagnostics-flicker")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 可访问性定期采样（R6）。
     *
     * 3 秒一次：诊断页拉取概览时总能拿到一份「刚才」的语义树快照。
     * 采样只读语义树的四要素（可点 / 有无标签 / 宽 / 高），不读文本内容。
     */
    private fun startAccessibilitySampler() {
        val thread = Thread({
            while (running) {
                runCatching {
                    Thread.sleep(A11Y_INTERVAL_MS)
                    sampleAccessibility()
                }
            }
        }, "zaomeng-diagnostics-a11y")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 触发一次闪烁采样。
     *
     * **必须在 UI 线程绘制**（F）：Android 的 `View` 只能在创建它的线程（UI 线程）上绘制，
     * 后台线程调 `view.draw(Canvas)` 是未定义行为。因此这里只负责 `post`，
     * 真正的绘制在 [sampleOnUiThread] 里跑；同一时刻只排一个，避免连点堆积。
     */
    private fun sampleOnce() {
        val activity = activityRef ?: return
        val view: View = activity.window?.decorView ?: return
        if (view.width <= 0 || view.height <= 0) return
        if (!flickerSampleQueued.compareAndSet(false, true)) return
        view.post {
            runCatching {
                val started = System.nanoTime()
                sampleOnUiThread(view)
                samplingNanos.addAndGet(System.nanoTime() - started)
                samplingSamples.incrementAndGet()
            }
            flickerSampleQueued.set(false)
        }
    }

    /** 真正在 UI 线程上执行的采样：只往内存位图上画，不落盘、不外传。 */
    private fun sampleOnUiThread(view: View) {
        val bitmap = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888)
        try {
            // **只在内存里画**：没有 PixelCopy、没有 MediaProjection、没有任何文件写入。
            view.draw(Canvas(bitmap))
            val grid = IntArray(GRID_W * GRID_H)
            bitmap.getPixels(grid, 0, GRID_W, 0, 0, GRID_W, GRID_H)
            val now = System.currentTimeMillis()
            synchronized(stateLock) {
                sampledFrames++
                val previous = previousGrid
                if (previous != null) {
                    // 只统计「变了几格」，不保存任何像素。
                    for (index in grid.indices) {
                        if (grid[index] != previous[index]) {
                            val region = regionOf(index)
                            val cell = flickerRegions.getOrPut(region) { longArrayOf(now, 0L) }
                            // 半衰：距离上次变化超过窗口就重置计数，避免把长期缓慢变化累计成闪烁。
                            if (now - cell[0] > FLICKER_WINDOW_MS) {
                                cell[1] = 0L
                            }
                            cell[0] = now
                            cell[1]++
                        }
                    }
                    while (flickerRegions.size > MAX_FLICKER_REGIONS) {
                        val oldest = flickerRegions.keys.firstOrNull() ?: break
                        flickerRegions.remove(oldest)
                    }
                }
                previousGrid = grid
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun regionOf(index: Int): String {
        val x = index % GRID_W
        val y = index / GRID_W
        // 3×3 粗粒度分区：输出「区域」而不是具体像素坐标。
        return "区域(${x * 3 / GRID_W},${y * 3 / GRID_H})"
    }

    // ------------------------------------------------------------------
    // 可访问性（R6）
    // ------------------------------------------------------------------

    /**
     * 采样 Compose 语义树并上报（R6）。
     *
     * 走**反射**而不是直接引用 `AndroidComposeView`：
     * Compose 的语义 API 在不同版本间有差异，直接引用会让「诊断」变成「编译期耦合」。
     * 反射失败时静默返回，审计侧会如实显示「未采到节点」。
     *
     * 采集内容只有四样：可点与否、标签、宽、高。**不采集文本内容**——
     * 语义树里可能包含用户原文，无差别采集等于把内容带进导出。
     */
    fun sampleAccessibility() {
        val activity = activityRef ?: return
        val samplerStarted = System.nanoTime()
        runCatching {
            val composeView = findComposeView(activity.window?.decorView ?: return) ?: return
            val owner = composeView.javaClass.getMethod("getSemanticsOwner").invoke(composeView) ?: return
            val root = owner.javaClass.getMethod("getRootSemanticsNode").invoke(owner) ?: return
            val keyClass = Class.forName("androidx.compose.ui.semantics.SemanticsPropertyKey")
            val propsClass = Class.forName("androidx.compose.ui.semantics.SemanticsProperties")
            val actionsClass = Class.forName("androidx.compose.ui.semantics.SemanticsActions")
            val propsInstance = propsClass.getField("INSTANCE").get(null)
            val actionsInstance = actionsClass.getField("INSTANCE").get(null)
            val contentDescriptionKey = propsClass.getMethod("getContentDescription").invoke(propsInstance)
            val textKey = propsClass.getMethod("getText").invoke(propsInstance)
            val onClickKey = actionsClass.getMethod("getOnClick").invoke(actionsInstance)
            val density = activity.resources.displayMetrics.density

            val nodes = mutableListOf<ClientDiagnostics.A11yNode>()
            val visited = HashSet<Int>()
            fun walk(node: Any) {
                if (nodes.size >= MAX_A11Y_NODES) return
                val id = runCatching { node.javaClass.getMethod("getId").invoke(node) as Int }.getOrNull() ?: return
                if (!visited.add(id)) return
                val config = runCatching { node.javaClass.getMethod("getConfig").invoke(node) }.getOrNull()
                if (config != null) {
                    val contains = runCatching {
                        config.javaClass.getMethod("contains", keyClass).invoke(config, onClickKey) as Boolean
                    }.getOrDefault(false)
                    val getOrNull = runCatching {
                        config.javaClass.getMethod("getOrNull", keyClass)
                    }.getOrNull()
                    val description = getOrNull?.let { method ->
                        runCatching { method.invoke(config, contentDescriptionKey) as? List<*> }
                            .getOrNull()?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
                    }.orEmpty()
                    val text = getOrNull?.let { method ->
                        runCatching { method.invoke(config, textKey) as? List<*> }
                            .getOrNull()?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
                    }.orEmpty()
                    val bounds = runCatching {
                        node.javaClass.getMethod("getBoundsInRoot").invoke(node)
                    }.getOrNull()
                    val widthDp = bounds?.let { rect ->
                        runCatching {
                            val left = rect.javaClass.getMethod("getLeft").invoke(rect) as Float
                            val right = rect.javaClass.getMethod("getRight").invoke(rect) as Float
                            ((right - left) / density).toInt()
                        }.getOrDefault(0)
                    } ?: 0
                    val heightDp = bounds?.let { rect ->
                        runCatching {
                            val top = rect.javaClass.getMethod("getTop").invoke(rect) as Float
                            val bottom = rect.javaClass.getMethod("getBottom").invoke(rect) as Float
                            ((bottom - top) / density).toInt()
                        }.getOrDefault(0)
                    } ?: 0
                    nodes += ClientDiagnostics.A11yNode(
                        page = runCatching { ClientDiagnostics.lastPageLabel() }.getOrDefault(""),
                        ref = "node#$id",
                        // 只回答「有没有可读标签」：描述或文本任一存在即可，具体内容不回传。
                        label = if (description.isNotBlank() || text.isNotBlank()) "(有标签)" else "",
                        clickable = contains,
                        widthDp = widthDp,
                        heightDp = heightDp,
                    )
                }
                val children = runCatching {
                    node.javaClass.getMethod("getChildren").invoke(node) as? List<*>
                }.getOrNull().orEmpty()
                children.forEach { child -> child?.let { walk(it) } }
            }
            walk(root)
            if (nodes.isNotEmpty()) ClientDiagnostics.replaceA11yNodes(nodes)
        }
        samplingNanos.addAndGet(System.nanoTime() - samplerStarted)
        samplingSamples.incrementAndGet()
    }

    private fun findComposeView(root: View): View? {
        if (root.javaClass.name == COMPOSE_VIEW_CLASS) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findComposeView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * 进程模型（R6），供诊断标注覆盖范围。
     *
     * 与 `AndroidRuntimeLog.processSnapshot()` 同源同口径；放在这里是因为
     * 「UI 侧也能问一句」在排查时更方便，两处都只读系统公开 API。
     */
    fun processNames(context: Context): List<String> {
        val packageName = context.packageName
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses
                ?.map { it.processName }
                ?.filter { it == packageName || it.startsWith("$packageName:") }
                ?.distinct()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 可访问性采样间隔。 */
    private const val A11Y_INTERVAL_MS = 3_000L

    private const val MAX_PAGE_BUCKETS = 24
    private const val MAX_FLICKER_REGIONS = 12
    private const val MAX_A11Y_NODES = 400
    private const val COMPOSE_VIEW_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
}

/**
 * 裁剪看门狗采到的栈：**既保留最内层，也保留应用自己的调用点**。
 *
 * `Thread.stackTrace` 从栈顶（最内层）开始。只取前 N 帧会得到一堆框架帧
 * （`kotlin.reflect.jvm.internal.*`、`androidx.compose.*`、`android.os.*`），
 * 真正「谁触发了这次阻塞」在栈底，被裁掉后导出里只剩「主线程阻塞 1481ms」，
 * 无法定位。这里：
 *
 * 1. 保留最内层 [innermostFrames] 帧（阻塞现场本身）；
 * 2. 再从整条栈里挑出包名前缀匹配 [appPackagePrefix] 的帧，最多 [appFrames] 帧；
 * 3. 中间被省掉的帧数以 `…（省略 N 帧）` 明确标注——裁剪过就说裁剪过，不假装完整。
 *
 * 纯函数（无 Android 依赖除了 [StackTraceElement]），便于回归验证。
 */
internal fun formatWatchdogStack(
    frames: List<StackTraceElement>,
    appPackagePrefix: String,
    innermostFrames: Int,
    appFrames: Int,
): String {
    if (frames.isEmpty()) return "  （栈不可用）"
    val innermost = frames.take(innermostFrames.coerceAtLeast(1))
    val appOwned = frames
        .filter { it.className.startsWith(appPackagePrefix) }
        .distinct()
        .take(appFrames.coerceAtLeast(0))
        .filterNot { frame -> innermost.any { it == frame } }
    val omitted = frames.size - innermost.size - appOwned.size
    return buildList {
        innermost.forEach { add("  at $it") }
        if (omitted > 0) add("  …（省略 $omitted 帧）")
        appOwned.forEach { add("  at $it") }
    }.joinToString("\n")
}
