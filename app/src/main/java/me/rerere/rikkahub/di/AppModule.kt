package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.CapabilityApprovalStore
import me.rerere.rikkahub.data.ai.SubAgentLiveStore
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.builtin.BuiltinModelRepository
import me.rerere.rikkahub.data.builtin.BuiltinProxyManager
import me.rerere.rikkahub.data.builtin.BuiltinProxyServer
import me.rerere.rikkahub.data.qqbot.QQBotGateway
import me.rerere.rikkahub.data.qqbot.QQBotSocketClient
import me.rerere.rikkahub.data.qqbot.QQOfficialSocketClient
import me.rerere.rikkahub.data.builtin.WorkBuddyAuthApi
import me.rerere.rikkahub.data.builtin.growth.WorkBuddyGrowthApi
import me.rerere.rikkahub.data.builtin.growth.WorkBuddyTaskRunner
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费。
    // 启动时不再 createdAtStart，改为首帧后由 QingshuiApp 动态初始化，避免拖慢冷启动。
    single {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        SubAgentRunner(
            generationLoop = get(),
            settingsStore = get(),
            approvalStore = get(),
        )
    }

    single {
        SubAgentLiveStore()
    }

    single {
        CapabilityApprovalStore(settingsStore = get())
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            subAgentRunner = get(),
            subAgentLiveStore = get(),
            pluginManager = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            capabilityApprovalStore = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    // QQ 机器人：反向 WebSocket 收消息 → 判定要不要答 → 交给 ChatService 生成 → 回投 QQ。
    // 依赖 ChatService，必须注册在它后面。
    single { QQBotSocketClient(scope = get()) }
    single { QQOfficialSocketClient(scope = get()) }

    single {
        QQBotGateway(
            settingsStore = get(),
            conversationRepository = get(),
            chatService = get(),
            oneBotClient = get(),
            officialClient = get(),
            appScope = get(),
            eventBus = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }

    // ---------------------------------------------------------------- 内置模型

    single {
        WorkBuddyAuthApi(client = get(), json = get())
    }

    single {
        WorkBuddyGrowthApi(client = get(), json = get())
    }

    single {
        WorkBuddyTaskRunner(authApi = get(), growthApi = get())
    }

    single {
        BuiltinModelRepository(context = get(), authApi = get(), taskRunner = get(), json = get())
    }

    single {
        BuiltinProxyServer(repository = get(), client = get(), json = get(), settingsStore = get())
    }

    // 进程启动后由 QingshuiApp 在首帧后动态初始化，保证「内置模型」开启时本地代理自动运行。
    single {
        BuiltinProxyManager(repository = get(), server = get(), appScope = get())
    }
}
