package top.wkbin.zaomeng.ktor

import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.RuntimeLogCoroutineFailures
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.ktor.services.*

/** Application-scoped Ktor services. Routes receive dependencies from this graph. */
class KtorServiceGraph(platform: ServerPlatform) {
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
    // 「测试连接」的结果也要进诊断：否则报告里的模型一节只有 profile 摘要、没有连通性结论。
    val connectivityLog = ModelConnectivityLog(storage.getStorageRoot() / "diagnostics/model-connectivity.jsonl")
    val modelApiKeys = ModelApiKeyService(platform.secureStore())
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
    )
    val promptLoader = PromptLoader(platform.promptSource)
    val llm = LlmClient(modelApiKeys, storage)
    val worldMemory = WorldMemoryService(storage)
    val originalKnowledge = OriginalKnowledgeService(storage)
    val novelChapters = NovelChapterIndexService(storage)
    val plugins = PluginService(storage, top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins.all, diagnosticsLog)
    val pluginRules = PluginRuleEngine(storage, plugins)
    val dialogue = DialogueService(storage, llm, promptLoader, worldMemory, pluginRules)
    val dialogueStream = DialogueStreamService(storage, llm, promptLoader, dialogue, diagnosticsLog)
    val suggestions = SuggestionsService(storage, llm, promptLoader)
    val dialogueAdvanced = DialogueAdvancedService(storage, llm, promptLoader)
    val sessionManagement = SessionManagementService(storage, dialogue, worldMemory, novelChapters)
    val distillExecutor = DistillExecutor(storage, llm, promptLoader, originalKnowledge)
    val runManagement = RunManagementService(storage, distillExecutor)
    val runPackages = RunPackageService(storage)
    val settingsManagement = SettingsManagementService(storage, modelApiKeys, connectivityLog)
    // 端侧推理：按存储根做进程内单例（后端 retry() 会重建本服务图，不能丢掉 llama-server 句柄）
    val onDeviceModels = OnDeviceModelService.getOrCreate(platform)
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
    }
}
