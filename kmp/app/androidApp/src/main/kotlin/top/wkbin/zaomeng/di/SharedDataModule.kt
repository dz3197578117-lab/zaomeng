package top.wkbin.zaomeng.di

import org.koin.dsl.module
import top.wkbin.zaomeng.data.AppRuntimeRepository
import top.wkbin.zaomeng.data.AppRuntimeRepositoryImpl
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.CardRepositoryImpl
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.ChapterRepositoryImpl
import top.wkbin.zaomeng.data.ChatSessionGatewayImpl
import top.wkbin.zaomeng.data.CreateDialogueSessionGatewayImpl
import top.wkbin.zaomeng.data.DeleteDialogueSessionGatewayImpl
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.DiagnosticsRepositoryImpl
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.DialogueRepositoryImpl
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.ModelSettingsRepositoryImpl
import top.wkbin.zaomeng.data.OriginalKnowledgeRepository
import top.wkbin.zaomeng.data.OriginalKnowledgeRepositoryImpl
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.PersonaRepositoryImpl
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.PluginRepositoryImpl
import top.wkbin.zaomeng.data.RelationsRepository
import top.wkbin.zaomeng.data.RelationsRepositoryImpl
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.RunRepositoryImpl
import top.wkbin.zaomeng.data.RunReviewRepositoryImpl
import top.wkbin.zaomeng.data.RuntimeCaptureStateStore
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.SessionRepositoryImpl
import top.wkbin.zaomeng.data.WorldMemoryRepository
import top.wkbin.zaomeng.data.WorldMemoryRepositoryImpl
import top.wkbin.zaomeng.data.local.CardsClient
import top.wkbin.zaomeng.data.local.ChapterClient
import top.wkbin.zaomeng.data.local.DiagnosticsClient
import top.wkbin.zaomeng.data.local.DialogueClient
import top.wkbin.zaomeng.data.local.ModelSettingsClient
import top.wkbin.zaomeng.data.local.OriginalKnowledgeClient
import top.wkbin.zaomeng.data.local.PersonaClient
import top.wkbin.zaomeng.data.local.PluginClient
import top.wkbin.zaomeng.backend.AppServices
import top.wkbin.zaomeng.data.local.RelationsClient
import top.wkbin.zaomeng.data.local.RunManagementClient
import top.wkbin.zaomeng.data.local.RunOpsClient
import top.wkbin.zaomeng.data.local.RunsClient
import top.wkbin.zaomeng.data.local.SessionClient
import top.wkbin.zaomeng.data.local.WorldMemoryClient
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.run.RunReviewGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway

internal fun sharedDataModule(platform: AppPlatform) = module {
    single { AppPreferencesRepository(get()) }
    single { ContentDisclaimerPreferences(get()) }

    single {
        ModelSettingsClient(
            settings = get<AppServices>().graph.settingsManagement,
            onDevice = get<AppServices>().graph.onDeviceModels,
            voice = get<AppServices>().graph.voiceModels,
        )
    }
    single {
        PluginClient(
            plugins = get<AppServices>().graph.plugins,
            operations = get<AppServices>().graph.pluginOperations,
            builder = get<AppServices>().graph.pluginBuilder,
        )
    }
    single { RunsClient(get<AppServices>().graph.storage) }
    single {
        RunManagementClient(
            runService = get<AppServices>().graph.runManagement,
            packageService = get<AppServices>().graph.runPackages,
            storage = get<AppServices>().graph.storage,
        )
    }
    single { SessionClient(get<AppServices>().graph.sessionManagement) }
    single {
        ChapterClient(
            chapterManagement = get<AppServices>().graph.chapterManagement,
            storage = get<AppServices>().graph.storage,
        )
    }
    single {
        DiagnosticsClient(
            diagnostics = get<AppServices>().graph.diagnostics,
            refreshProbes = { get<AppServices>().graph.featureProbeExecutor.refreshForced() },
        )
    }
    single {
        CardsClient(
            cardsService = get<AppServices>().graph.cards,
            cardsManagement = get<AppServices>().graph.cardsManagement,
        )
    }
    single { PersonaClient(get<AppServices>().graph.persona) }
    single {
        DialogueClient(
            dialogueStream = get<AppServices>().graph.dialogueStream,
            advanced = get<AppServices>().graph.dialogueAdvanced,
            storage = get<AppServices>().graph.storage,
        )
    }
    single { WorldMemoryClient(get<AppServices>().graph.worldMemory) }
    // 进程内直连：服务来自共享服务图（AppServices），不再经 HTTP。
    single { RelationsClient(get<AppServices>().graph.relations) }
    single {
        RunOpsClient(
            runOperations = get<AppServices>().graph.runOperations,
            novelChapters = get<AppServices>().graph.novelChapters,
            storage = get<AppServices>().graph.storage,
        )
    }
    single { OriginalKnowledgeClient(get<AppServices>().graph.originalKnowledge, get<AppServices>().graph.storage) }

    single { OnlineLibraryRepository(platform.cacheDir) }

    single { RunRepositoryImpl(get(), get(), get(), get()) }

    single<AppRuntimeRepository> { AppRuntimeRepositoryImpl(get(), get()) }
    single<ModelSettingsRepository> { ModelSettingsRepositoryImpl(get(), get()) }
    single<DiagnosticsRepository> { DiagnosticsRepositoryImpl(get(), get()) }
    // 采集状态真源：「运行日志」页开关与应用内悬浮窗共用，避免两边状态不一致。
    single { RuntimeCaptureStateStore(get(), get()) }
    single<PluginRepository> { PluginRepositoryImpl(get()) }
    single<RunRepository> { get<RunRepositoryImpl>() }
    single<WorldMemoryRepository> { WorldMemoryRepositoryImpl(get()) }
    single<RelationsRepository> { RelationsRepositoryImpl(get()) }
    single<SessionRepository> { SessionRepositoryImpl(get(), get(), get()) }
    single<DialogueRepository> { DialogueRepositoryImpl(get()) }
    single<PersonaRepository> { PersonaRepositoryImpl(get()) }
    single<OriginalKnowledgeRepository> { OriginalKnowledgeRepositoryImpl(get()) }
    single<ChapterRepository> { ChapterRepositoryImpl(get()) }
    single<CardRepository> { CardRepositoryImpl(get()) }

    single<ChatSessionGateway> { ChatSessionGatewayImpl(get(), get(), get()) }
    single<DistillPlanningGateway> { get<RunRepositoryImpl>() }
    single<RunReviewGateway> { RunReviewRepositoryImpl(get(), get(), get()) }
    single<CreateDialogueSessionGateway> { CreateDialogueSessionGatewayImpl(get()) }
    single<DeleteDialogueSessionGateway> { DeleteDialogueSessionGatewayImpl(get()) }
}
