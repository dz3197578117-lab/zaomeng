package top.wkbin.zaomeng.ktor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.wkbin.zaomeng.platform.DiagnosticsPayloadSink
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.RuntimeLogCoroutineFailures
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.ktor.features.DataIntegrityAudit
import top.wkbin.zaomeng.ktor.features.FeatureProbeContext
import top.wkbin.zaomeng.ktor.features.FeatureProbeExecutor
import top.wkbin.zaomeng.ktor.features.FeatureProbeRegistry
import top.wkbin.zaomeng.ktor.features.FeatureProbeRunner
import top.wkbin.zaomeng.ktor.features.registerAllFeatureProbes
import top.wkbin.zaomeng.ktor.services.*

/** Application-scoped Ktor services. Routes receive dependencies from this graph. */
class KtorServiceGraph(platform: ServerPlatform) {
    /**
     * 诊断/探针用的后台作用域：只用于「预热」这类可丢弃的派生工作。
     * 独立于任何请求作用域——探针绝不能因为发起它的请求结束而被取消。
     */
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = buildZaomengDatabase(platform.databaseBuilder())
    private val documentStore = RoomDocumentStore(database.documentDao())
    val storage = StorageService(
        platform.dataRoot,
        documentStore,
        DomainStore(platform.dataRoot, database.domainDao(), documentStore),
    )
    // 诊断事件日志：跨重启保留的关键事件（对话解析失败/插件动作/端侧推理状态），
    // 随诊断导出一起交给排查者，避免只能靠 logcat 猜。
    val diagnosticsLog = DiagnosticsEventLog(storage.getStorageRoot() / "diagnostics-events.jsonl")
    // 问题面包屑（R2）：客户端轨迹（导航/操作）+ 结构化事件 + 系统检查点组成的时间线。
    val diagnosticsBreadcrumbs = DiagnosticsBreadcrumbs()
    // 「测试连接」的结果也要进诊断：否则报告里的模型一节只有 profile 摘要、没有连通性结论。
    val connectivityLog = ModelConnectivityLog(storage.getStorageRoot() / "diagnostics/model-connectivity.jsonl")
    val modelApiKeys = ModelApiKeyService(platform.secureStore())
    val promptLoader = PromptLoader(platform.promptSource)
    val llm = LlmClient(modelApiKeys, storage)
    val worldMemory = WorldMemoryService(storage)
    val originalKnowledge = OriginalKnowledgeService(storage)
    val novelChapters = NovelChapterIndexService(storage)
    val plugins = PluginService(storage, top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins.all, diagnosticsLog)

    // onDeviceSnapshot / apiKeyConfiguredOf 用 lambda 延迟绑定：被调对象在下方才创建，导出时才求值。
    val diagnostics = DiagnosticsService(
        storageRoot = storage.getStorageRoot(),
        storageService = storage,
        eventLog = diagnosticsLog,
        onDeviceSnapshot = { onDeviceModels.diagnosticsSnapshot() },
        // 运行日志来源：Android 抓本进程 logcat，桌面端读进程内缓冲（见 ServerPlatform）。
        runtimeLogSource = platform.runtimeLogSource(),
        // 以安全存储的实际结果判断"是否配了 Key"：ModelProfile.apiKeyConfigured 从不被写入，
        // 直接读它会让已配 Key 的档案在诊断里永远显示未配置（最常见的假阴性）。
        apiKeyConfiguredOf = { profileId -> modelApiKeys.getApiKey(profileId)?.isNotBlank() },
        connectivityLog = connectivityLog,
        // 自检里的「数据目录可写」用原子写探一次；失败只影响这一项，不影响导出
        dataRootWritable = {
            runCatching {
                storage.mkdirs(storage.getStorageRoot())
                storage.writeTextAtomically(
                    storage.getStorageRoot() / "diagnostics-write-probe.txt",
                    "ok",
                )
                true
            }.getOrDefault(false)
        },
        pluginsRoot = { (storage.getStorageRoot() / "plugins").toString() },
        processUptimeMillis = { platform.processUptimeMillis() },
        // 功能自检快照：只返回上一轮结果（同步、无 IO）。页面刷新时才真正重跑探针。
        featureProbeReport = { featureProbeExecutor.snapshot() },
        breadcrumbs = diagnosticsBreadcrumbs,
        // 数据损坏审计（R6）：只读关键文件，不做修复；与探针共用同一个只读上下文。
        dataIntegrityAudit = {
            runCatching {
                DataIntegrityAudit.audit(featureProbeContext)
            }.getOrElse { top.wkbin.zaomeng.data.api.DataIntegrityAuditDto(note = "审计失败，已跳过该节。") }
        },
    )
    val pluginRules = PluginRuleEngine(storage, plugins)
    val dialogue = DialogueService(storage, llm, promptLoader, worldMemory, pluginRules)
    val dialogueStream = DialogueStreamService(storage, llm, promptLoader, dialogue, diagnosticsLog)
    val suggestions = SuggestionsService(storage, llm, promptLoader)
    val dialogueAdvanced = DialogueAdvancedService(storage, llm, promptLoader)
    val sessionManagement = SessionManagementService(storage, dialogue, worldMemory, novelChapters)
    val distillExecutor = DistillExecutor(storage, llm, promptLoader, originalKnowledge)
    val runManagement = RunManagementService(storage, distillExecutor)
    val runPackages = RunPackageService(storage)
    // 端侧实时地址用 lambda 延迟绑定（onDeviceModels 在下方才创建，与上面 diagnostics 同一写法）：
    // 本地档案的 base_url 以实时端口为准，否则端侧重启换端口后持久化的那份仍指向死端口。
    val settingsManagement = SettingsManagementService(
        storage,
        modelApiKeys,
        connectivityLog,
        onDeviceBaseUrl = { onDeviceModels.liveBaseUrl() },
    )
    // 端侧推理：按存储根做进程内单例（后端 retry() 会重建本服务图，不能丢掉 llama-server 句柄）
    val onDeviceModels = OnDeviceModelService.getOrCreate(platform)

    // ------------------------------------------------------------------
    // 功能自检（探针）
    // ------------------------------------------------------------------
    // 注册表 + 只读上下文 + 可缓存的执行器。
    // 放在这里是因为探针要用端侧快照（onDeviceModels）；而 diagnostics 通过
    // 「同步快照 lambda」反向引用本执行器，因此两者必须分开声明、不存在构造期的环。
    // 结果：诊断导出始终能立刻拿到**上一轮**探针结论，不会被任何慢探针拖住 HTTP 请求。
    val featureProbes = FeatureProbeRegistry()
    // 只读上下文提到外面：数据损坏审计（R6）与探针共用同一个「只能读」的口子，
    // 审计因此天然不能顺手修复业务数据。
    val featureProbeContext = FeatureProbeContext(
        storage = storage,
        promptLoader = promptLoader,
        // 结构化事件走独立环形缓冲，**不受运行日志采集开关影响**：
        // 采集关闭时探针照常给结论，顶多个别项报「无样本」。
        eventLog = diagnosticsLog,
        connectivityLog = connectivityLog,
        onDeviceSnapshot = { onDeviceModels.diagnosticsSnapshot(logChars = 1_200) },
        apiKeyConfiguredOf = { profileId -> modelApiKeys.getApiKey(profileId)?.isNotBlank() },
        keystoreHealth = { readKeystoreHealth(platform) },
    )
    val featureProbeExecutor = FeatureProbeExecutor(
        registry = featureProbes,
        runner = FeatureProbeRunner(
            registry = featureProbes,
            context = featureProbeContext,
        ),
    )

    val chapter = ChapterService(storage, llm, promptLoader)
    val chapterManagement = ChapterManagementService(storage, sessionManagement, llm, promptLoader)
    val cards = CardsService(storage, llm, promptLoader)
    val cardsManagement = CardsManagementService(storage)
    val relations = RelationsService(storage)
    val persona = PersonaService(storage, llm, promptLoader, relations)
    val runOperations = RunOperationsService(
        storage,
        runManagement,
        runPackages,
        distillExecutor,
    )
    val pluginHost = PluginHostImpl(storage, llm, dialogueAdvanced, suggestions, plugins)
    val pluginOperations = PluginOperationsService(storage, plugins, pluginHost, diagnosticsLog)
    val pluginBuilder = PluginBuilderService { messages ->
        llm.chatCompletion(
            messages = messages,
            temperature = 0.2,
            maxTokens = 1_600,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content.orEmpty()
    }

    init {
        // 功能自检：注册全部探针（唯一挂载点，见 registerAllFeatureProbes）。
        registerAllFeatureProbes(featureProbes, plugins, storage)
        // 预热一次：让用户第一次打开「运行诊断」就能看到结论，而不是空列表。
        featureProbeExecutor.warmUp(diagnosticsScope)
        // Distillation jobs are process-local coroutines. If the backend was restarted,
        // a persisted "running" manifest no longer has a worker behind it; expose it as
        // an interruptible run so the existing resume flow can recover it.
        distillExecutor.markPersistedRunsInterrupted()
        // 端侧推理是进程内单例，图装配完成后补挂事件日志（构造期拿不到）。
        onDeviceModels.attachEventLog(diagnosticsLog)
        // 静默失败收口：关键路径里被 runCatching 吃掉的失败补一条结构化事件（阶段 + 原因 + 是否重试）。
        // 生产方散布在 storage/llm/聚合服务里，逐个构造注入会把签名与测试铺开一大片，
        // 因此用全局收口；未挂接时为空操作，绝不影响主流程。
        DiagnosticsSink.attach { category, message, details ->
            diagnosticsLog.record(category = category, message = message, details = details)
        }
        // R5：完整载荷（完整提示词 / 完整模型输出 / 完整原文）走独立上限的收口，
        // 仍然在写入侧过一遍 LogRedactor，凭证绝不落盘。
        DiagnosticsPayloadSink.attach { category, message, details ->
            diagnosticsLog.recordPayload(category = category, label = message, payload = details)
        }
        // 协程未捕获异常：同样纳入运行日志口径（记录后仍交回线程原处理器，不改变崩溃行为）。
        RuntimeLogCoroutineFailures.attach { platform.runtimeLogSource() }
        // 后端每次重建都留痕：排查「重启后行为异常」类问题时需要知道图被重建了几次。
        diagnosticsLog.record(
            category = "backend.ready",
            message = "内嵌后端服务图已就绪。",
            details = mapOf(
                "data_root" to platform.dataRoot.toString(),
                "plugins_root" to (storage.getStorageRoot() / "plugins").toString(),
            ),
        )
        // R2：系统检查点作为面包屑的基线。之后的每条问题都能回溯到「当时的页面/内存/现场/采集状态」。
        recordSystemBreadcrumb("服务图就绪")
    }

    /**
     * 记一条系统检查点（R2 面包屑）。
     *
     * 内容刻意只放**排查必需且不会泄露内容**的字段：当前现场、内存水位、采集开关。
     * 每次概览/导出被读取时也会记一条，于是「问题发生之前的内存水位」有真实的历史值，
     * 而不是拿读取这一刻的内存去冒充。
     */
    fun recordSystemBreadcrumb(label: String) {
        runCatching {
            diagnosticsBreadcrumbs.recordSystem(
                label = label,
                detail = buildString {
                    append("capture=").append(if (diagnostics.runtimeCaptureState().enabled) "on" else "off")
                    val memory = top.wkbin.zaomeng.platform.buildEnvironmentInfo().memory
                    append(" · heap=").append(memory.heapUsedBytes / (1024 * 1024)).append("MiB/")
                        .append(memory.heapMaxBytes / (1024 * 1024)).append("MiB")
                    if (memory.systemAvailableBytes > 0) {
                        append(" · sysAvail=").append(memory.systemAvailableBytes / (1024 * 1024)).append("MiB")
                    }
                    append(" · runs=").append(runCatching { storage.listRunIds().size }.getOrDefault(0))
                },
            )
        }
    }
}
