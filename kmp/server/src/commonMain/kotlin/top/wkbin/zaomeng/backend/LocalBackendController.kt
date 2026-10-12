package top.wkbin.zaomeng.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.ktor.KtorServiceGraph
import top.wkbin.zaomeng.ktor.plugins.configureObservability
import top.wkbin.zaomeng.ktor.plugins.configureSecurity
import top.wkbin.zaomeng.ktor.routes.cardRoutes
import top.wkbin.zaomeng.ktor.routes.cardsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterRoutes
import top.wkbin.zaomeng.ktor.routes.diagnosticsRoute
import top.wkbin.zaomeng.ktor.routes.dialogueAdvancedRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueStreamRoutes
import top.wkbin.zaomeng.ktor.routes.healthRoute
import top.wkbin.zaomeng.ktor.routes.novelChapterRoutes
import top.wkbin.zaomeng.ktor.routes.onDeviceModelRoutes
import top.wkbin.zaomeng.ktor.routes.personaRoutes
import top.wkbin.zaomeng.ktor.routes.originalKnowledgeRoutes
import top.wkbin.zaomeng.ktor.routes.pluginBuilderRoutes
import top.wkbin.zaomeng.ktor.routes.pluginOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.pluginRoutes
import top.wkbin.zaomeng.ktor.routes.relationsRoutes
import top.wkbin.zaomeng.ktor.routes.runManagementRoutes
import top.wkbin.zaomeng.ktor.routes.runOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.runsRoute
import top.wkbin.zaomeng.ktor.routes.sessionManagementRoutes
import top.wkbin.zaomeng.ktor.routes.settingsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.suggestionsRoutes
import top.wkbin.zaomeng.ktor.routes.worldMemoryRoutes
import top.wkbin.zaomeng.platform.ServerPlatform

/**
 * 通用内嵌后端控制器：三端统一的 Ktor CIO server（数据走 Room）。
 * 由平台入口提供 ServerPlatform/端口/token，进程内启动一次。
 */
@OptIn(ExperimentalAtomicApi::class)
class LocalBackendController(
    private val serverPlatform: ServerPlatform,
    /** 0 = 随机端口（默认，避免固定端口被占用/多实例冲突）。 */
    private val port: Int = 0,
    private val token: String = "dev-token",
) : BackendController {
    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)

    override val state: StateFlow<BackendState> = mutableState.asStateFlow()

    /**
     * 幂等启动标记。
     *
     * 这里不用 `synchronized` 而用 `AtomicBoolean.compareAndSet`：CAS 语义与「双重检查 + 锁」
     * 一致——只有第一个调用方真正启动，其余直接返回，且本身是原子的，比「先判断再赋值」更严谨；
     * 同时避免依赖 `kotlin.jvm.*` 专有 API，保持 commonMain 跨 JVM/Android/Desktop 后端无关。
     *
     * `@Volatile` 必须在 commonMain 显式 import `kotlin.concurrent.Volatile`：JVM 默认导入的
     * `kotlin.jvm.Volatile` 在 common 里不可见，`@Volatile` 裸用同样解析不到。
     */
    private val started = AtomicBoolean(false)

    @Volatile
    private var boundPort: Int? = null

    /** 实际绑定端口（随机端口模式下在 start 后解析）。 */
    val actualPort: Int? get() = boundPort

    /** 幂等启动（供端点提供者在请求前确保后端就绪）。 */
    fun ensureStarted() {
        start()
    }

    /** 等待实际端口就绪（随机端口在 start 后异步解析）。 */
    suspend fun awaitPort(): Int {
        boundPort?.let { return it }
        repeat(200) {
            delay(50)
            boundPort?.let { return it }
        }
        error("内嵌后端端口未就绪")
    }

    override fun start() {
        if (started.load()) return
        // CAS 失败说明已有线程在启动或已启动，直接返回（等价于原锁内二次判断）。
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        mutableState.value = BackendState.Starting("启动内嵌后端…")
        try {
            val services = KtorServiceGraph(serverPlatform)
            val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                install(ContentNegotiation) {
                    json(
                        Json {
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                            // 新格式：类型化 DTO 序列化必须写全字段（默认值也落盘）
                            encodeDefaults = true
                        },
                    )
                }
                configureObservability()
                configureSecurity(token)

                val storage = services.storage
                routing {
                    healthRoute()
                    runsRoute(storage)
                    diagnosticsRoute(storage, services.diagnostics)
                    dialogueRoutes(services.dialogue, storage)
                    sessionManagementRoutes(services.sessionManagement)
                    runManagementRoutes(services.runManagement, services.runPackages)
                    settingsManagementRoutes(services.settingsManagement)
                    dialogueStreamRoutes(services.dialogueStream, storage)
                    suggestionsRoutes(services.suggestions)
                    dialogueAdvancedRoutes(services.dialogueAdvanced)
                    chapterRoutes(services.chapter)
                    chapterManagementRoutes(services.chapterManagement)
                    cardRoutes(services.cards)
                    cardsManagementRoutes(services.cardsManagement)
                    personaRoutes(services.persona)
                    relationsRoutes(services.relations)
                    runOperationsRoutes(services.runOperations)
                    worldMemoryRoutes(services.worldMemory)
                    originalKnowledgeRoutes(services.originalKnowledge, storage)
                    novelChapterRoutes(services.novelChapters, storage)
                    onDeviceModelRoutes(services.onDeviceModels)
                    pluginRoutes(services.plugins)
                    pluginOperationsRoutes(services.pluginOperations)
                    pluginBuilderRoutes(services.pluginBuilder)
                }
            }
            server.start(wait = false)
            // CIO 在 start 后异步完成绑定，resolvedConnectors 返回实际端口（端口 0 时为随机值）。
            boundPort = runBlocking {
                runCatching {
                server.engine.resolvedConnectors().firstOrNull()?.port
                }.getOrNull()
            }
            mutableState.value = BackendState.Ready("http://127.0.0.1:${boundPort ?: port}")
        } catch (error: Throwable) {
            mutableState.value = BackendState.Failed(error.message ?: "后端启动失败")
        }
    }

    override fun retry() {
        started.store(false)
        start()
    }
}
