package top.wkbin.zaomeng.ktor

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
    // onDeviceSnapshot 用 lambda 延迟绑定：端侧服务在下方才创建，导出时才求值。
    val diagnostics = DiagnosticsService(
        storageRoot = storage.getStorageRoot(),
        storageService = storage,
        eventLog = diagnosticsLog,
        onDeviceSnapshot = { onDeviceModels.diagnosticsSnapshot() },
    )
    val modelApiKeys = ModelApiKeyService(platform.secureStore())
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
    val settingsManagement = SettingsManagementService(storage, modelApiKeys)
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
