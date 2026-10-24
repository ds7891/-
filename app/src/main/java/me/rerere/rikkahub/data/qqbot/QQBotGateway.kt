package me.rerere.rikkahub.data.qqbot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.data.datastore.QQBotProtocol
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * QQ 机器人总调度：收消息 → 判断要不要答 → 找会话 → 交给清水生成 → 把回复发回 QQ。
 *
 * 回复统一走一个由本类自动维护的专用助手（名字「QQ 机器人」）：
 * 模型、人设、联网开关都在设置卡片里改，落到这个助手身上。
 * 之所以不挂本机工具 —— 群里就是问答场景，文件系统 / APK / SSH 那套既用不上，
 * 又会让人在群里一句话就触发本机操作。所以 `localTools` 与 `mcpServers` 一律清空，
 * 只留 `enableWebSearch` 这一个开关给联网查询。
 *
 * 好处是这些对话照常出现在聊天列表里（标题「QQ群 xxx」），它答歪了你点进去手动纠正即可。
 */
class QQBotGateway(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val oneBotClient: QQBotSocketClient,
    private val officialClient: QQOfficialSocketClient,
    appScope: AppScope,
    eventBus: AppEventBus,
) {
    // AppScope 本身就是 CoroutineScope
    private val scope: CoroutineScope = appScope

    private val selfNickname = MutableStateFlow("")
    private val statusText = MutableStateFlow("未启动")
    val status = statusText.asStateFlow()

    /** 会话 → 待回复队列。群里连问两句会排两次生成，用队列才不会被后者覆盖目标。 */
    private val pendingReplies = MutableStateFlow<Map<Uuid, List<QQBotInbound>>>(emptyMap())

    private val lastAnsweredAt = ConcurrentHashMap<String, Long>()
    private var windowStartedAt = System.currentTimeMillis()
    private var answeredInWindow = 0

    /** 当前正在跑的是哪个协议的连接。 */
    @Volatile
    private var runningProtocol: QQBotProtocol? = null

    init {
        scope.launch(Dispatchers.Default) {
            settingsStore.settingsFlow.collect { applyConfig(it.qqBotConfig) }
        }
        scope.launch(Dispatchers.Default) {
            oneBotClient.inbounds.collect { handleInbound(it) }
        }
        scope.launch(Dispatchers.Default) {
            officialClient.inbounds.collect { handleInbound(it) }
        }
        scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                if (event is AppEvent.ChatGenerationEnded) handleGenerationEnded(event.conversationId)
            }
        }
        scope.launch {
            // 状态取当前协议那一路
            combine(
                oneBotClient.state,
                officialClient.state,
                settingsStore.settingsFlow,
            ) { a, b, settings ->
                val cfg = settings.qqBotConfig
                val state = if (cfg.protocol == QQBotProtocol.QQ_OFFICIAL) b else a
                renderStatus(cfg, state)
            }.collect { statusText.value = it }
        }
    }

    private fun renderStatus(config: QQBotConfig, state: QQBotLinkState): String = when {
        !config.enabled -> "未启用"
        state is QQBotLinkState.Online -> "已连接（${if (state.selfId.isBlank()) "官方机器人" else "QQ ${state.selfId}"}）"
        state is QQBotLinkState.Connecting -> "连接中…"
        state is QQBotLinkState.Offline -> "已断开：${state.reason}"
        !config.inboundEnabled -> if (config.canSend) "仅出站（未开双向）" else "未启动"
        else -> "未连接"
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    private fun applyConfig(config: QQBotConfig) {
        val wantInbound = config.enabled && config.inboundEnabled
        val official = config.protocol == QQBotProtocol.QQ_OFFICIAL

        // 协议切了就先把另一路停掉，避免两条连接同时收消息各回一遍
        if (official) {
            if (runningProtocol == QQBotProtocol.ONEBOT_V11) oneBotClient.stop()
            if (wantInbound) {
                if (!officialClient.isConnected) officialClient.start(config)
            } else officialClient.stop()
            runningProtocol = if (wantInbound) QQBotProtocol.QQ_OFFICIAL else null
        } else {
            if (runningProtocol == QQBotProtocol.QQ_OFFICIAL) officialClient.stop()
            if (wantInbound && QQBotClient.checkEndpoint(config) == null) {
                if (!oneBotClient.isConnected) oneBotClient.start(config)
            } else oneBotClient.stop()
            runningProtocol = if (wantInbound) QQBotProtocol.ONEBOT_V11 else null
        }

        if (official && wantInbound) {
            scope.launch { refreshNickname(config) }
        }
    }

    private suspend fun refreshNickname(config: QQBotConfig) {
        // OneBot 才有 get_login_info；官方协议没有对应接口，昵称拿不到就用 AppID
        if (config.protocol != QQBotProtocol.QQ_OFFICIAL) {
            val info = QQBotClient.getLoginInfo(config)
            if (info.reachable) selfNickname.value = info.nickname
        } else {
            selfNickname.value = ""
        }
    }

    // ------------------------------------------------------------------
    // 入站
    // ------------------------------------------------------------------

    private suspend fun handleInbound(inbound: QQBotInbound) {
        val config = settingsStore.settingsFlow.value.qqBotConfig
        if (!config.enabled || !config.inboundEnabled) return
        if (!shouldAnswer(config, inbound)) return
        if (!passThrottle(config, inbound)) return

        rememberTarget(inbound)
        val conversationId = resolveConversation(config, inbound) ?: return
        pendingReplies.update { map ->
            map + (conversationId to (map[conversationId].orEmpty() + inbound))
        }
        chatService.sendMessage(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(QQBotPersona.promptFor(inbound))),
            answer = true,
        )
    }

    /**
     * 要不要答。
     *
     * 官方协议下群里事件本身就是「被 @ 才推」，所以 mentionedMe 恒真，
     * 这里的 requireAtInGroup 主要约束 OneBot。
     */
    private fun shouldAnswer(config: QQBotConfig, inbound: QQBotInbound): Boolean {
        if (inbound.isGroup) {
            if (!config.allowsGroup(inbound.groupId)) return false
            if (config.requireAtInGroup && !inbound.mentionedMe) return false
        } else {
            if (!config.replyPrivate) return false
            if (!config.allowsUser(inbound.userId)) return false
        }
        // 纯表情 / 纯图片，且没 @ 机器人：不理
        if (inbound.text.isBlank() && !inbound.mentionedMe) return false
        return true
    }

    /** 记下最近活跃目标：官方协议的 openid 只能从事件里来，主动推送要用。 */
    private suspend fun rememberTarget(inbound: QQBotInbound) {
        settingsStore.update { s ->
            val c = s.qqBotConfig
            val next = if (inbound.isGroup) {
                if (c.lastGroupOpenid == inbound.groupId) return@update s else c.copy(lastGroupOpenid = inbound.groupId)
            } else {
                if (c.lastUserOpenid == inbound.userId) return@update s else c.copy(lastUserOpenid = inbound.userId)
            }
            s.copy(qqBotConfig = next)
        }
    }

    private fun passThrottle(config: QQBotConfig, inbound: QQBotInbound): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStartedAt > 3_600_000L) {
            windowStartedAt = now
            answeredInWindow = 0
        }
        if (config.hourlyReplyLimit > 0 && answeredInWindow >= config.hourlyReplyLimit) {
            Log.i(TAG, "本小时已达上限 ${config.hourlyReplyLimit}，忽略 ${inbound.bindingKey}")
            return false
        }
        val intervalMs = config.minReplyIntervalSeconds * 1000L
        if (intervalMs > 0) {
            val last = lastAnsweredAt[inbound.bindingKey] ?: 0L
            if (now - last < intervalMs) return false
            lastAnsweredAt[inbound.bindingKey] = now
        }
        answeredInWindow++
        return true
    }

    // ------------------------------------------------------------------
    // 专用助手
    // ------------------------------------------------------------------

    /**
     * 建/更新 QQ 专用助手：模型、人设、联网由卡片决定，工具一律清空。
     *
     * 只在配置真变了的时候写回，避免每来一条消息就刷一次 DataStore。
     */
    private suspend fun ensureAssistant(config: QQBotConfig): Uuid {
        val settings = settingsStore.settingsFlow.value
        val modelId = config.chatModelId.ifBlank { null }?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val prompt = QQBotPersona.build(
            selfName = selfNickname.value.ifBlank { "清水" },
            assistantName = "清水",
            custom = config.customPrompt,
            usePersona = config.groupPersona,
        )
        val existingId = config.assistantId.ifBlank { null }?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val existing = existingId?.let { id -> settings.assistants.firstOrNull { it.id == id } }

        if (existing == null) {
            val fresh = Assistant(
                id = Uuid.random(),
                name = "QQ 机器人",
                chatModelId = modelId,
                systemPrompt = prompt,
                localTools = emptyList(),
                mcpServers = emptySet(),
                enableWebSearch = config.enableWebSearch,
                enableMemory = false,
                contextMessageLimit = 24,
                allowConversationSystemPrompt = false,
            )
            settingsStore.update { s ->
                s.copy(
                    assistants = s.assistants + fresh,
                    qqBotConfig = s.qqBotConfig.copy(assistantId = fresh.id.toString()),
                )
            }
            return fresh.id
        }

        val dirty = existing.systemPrompt != prompt ||
                existing.chatModelId != modelId ||
                existing.enableWebSearch != config.enableWebSearch ||
                existing.localTools.isNotEmpty() ||
                existing.mcpServers.isNotEmpty()
        if (dirty) {
            val updated = existing.copy(
                name = "QQ 机器人",
                chatModelId = modelId,
                systemPrompt = prompt,
                localTools = emptyList(),
                mcpServers = emptySet(),
                enableWebSearch = config.enableWebSearch,
                contextMessageLimit = 24,
                allowConversationSystemPrompt = false,
            )
            settingsStore.update { s ->
                s.copy(assistants = s.assistants.map { if (it.id == updated.id) updated else it })
            }
        }
        return existing.id
    }

    /** 找到（或新建）这个 QQ 目标对应的清水会话。 */
    private suspend fun resolveConversation(config: QQBotConfig, inbound: QQBotInbound): Uuid? {
        val key = inbound.bindingKey
        config.conversationBindings[key]?.let { stored ->
            val uuid = runCatching { Uuid.parse(stored) }.getOrNull() ?: return@let
            if (conversationRepository.existsConversationById(uuid)) return uuid
        }

        val assistantId = ensureAssistant(config)
        val id = Uuid.random()
        val created = runCatching {
            conversationRepository.insertConversation(
                Conversation(
                    id = id,
                    assistantId = assistantId,
                    title = QQBotPersona.conversationTitle(inbound),
                    messageNodes = emptyList(),
                )
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "创建 QQ 会话失败", error)
            false
        }
        if (!created) return null

        settingsStore.update { s ->
            val c = s.qqBotConfig
            s.copy(qqBotConfig = c.copy(conversationBindings = c.conversationBindings + (key to id.toString())))
        }
        return id
    }

    // ------------------------------------------------------------------
    // 回投与推送
    // ------------------------------------------------------------------

    private suspend fun handleGenerationEnded(conversationId: Uuid) {
        val queue = pendingReplies.value[conversationId]
        if (queue != null && queue.isNotEmpty()) {
            replyToInbound(conversationId, queue)
            return
        }
        notifyFinish(conversationId)
    }

    private suspend fun replyToInbound(conversationId: Uuid, queue: List<QQBotInbound>) {
        val inbound = queue.first()
        pendingReplies.update { map ->
            val rest = map.getValue(conversationId).drop(1)
            if (rest.isEmpty()) map - conversationId else map + (conversationId to rest)
        }
        val config = settingsStore.settingsFlow.value.qqBotConfig
        if (!config.enabled) return
        val reply = lastAssistantText(conversationId)
        if (reply.isEmpty()) return

        // 官方被动回复只有 60 秒窗口，超了会被拒；那时退化成不带 id 的主动消息
        val quote = if (config.protocol == QQBotProtocol.QQ_OFFICIAL) inbound.messageId else ""
        val result = deliver(
            config = config,
            text = QQBotPersona.promptForOutbound(reply),
            inbound = inbound,
            quoteMessageId = quote,
            atUserId = "",
        )
        when (result) {
            is QQBotSendResult.Sent -> Log.i(TAG, "已回复 ${result.targetLabel}")
            is QQBotSendResult.Failed -> Log.w(TAG, "回复 ${inbound.bindingKey} 失败：[${result.code}] ${result.reason}")
        }
    }

    private suspend fun notifyFinish(conversationId: Uuid) {
        val config = settingsStore.settingsFlow.value.qqBotConfig
        if (!config.enabled || !config.notifyOnFinish) return
        if (config.conversationBindings.values.any { it == conversationId.toString() }) return
        val reply = lastAssistantText(conversationId)
        if (reply.isBlank()) return

        val group = if (config.protocol == QQBotProtocol.QQ_OFFICIAL) config.lastGroupOpenid else config.targetGroupId
        val user = if (config.protocol == QQBotProtocol.QQ_OFFICIAL) config.lastUserOpenid else config.targetUserId
        if (group.isBlank() && user.isBlank()) {
            Log.i(TAG, "没有可推送目标：官方协议下需要先在群里 @ 它一次")
            return
        }
        val text = QQBotClient.buildPushText(
            text = reply,
            modelName = if (config.notifyWithModelName) modelName(lastAssistantModelId(conversationId)) else null,
            limit = config.maxPushChars.coerceIn(100, 1200),
        )
        // 有群就发群；只有开了「优先发群」且群确实存在时才用群，否则用私聊
        val useGroup = if (config.preferGroup) group.isNotBlank() else (group.isNotBlank() && user.isBlank())
        val result = deliver(
            config = config,
            text = QQBotPersona.promptForOutbound(text, config.maxPushChars),
            inbound = QQBotInbound(
                messageId = "",
                isGroup = useGroup,
                groupId = group,
                userId = user,
                senderName = "",
                text = "",
                mentionedMe = false,
                selfId = "",
            ),
            quoteMessageId = "",
            atUserId = "",
        )
        when (result) {
            is QQBotSendResult.Sent -> Log.i(TAG, "生成完成已推送到 ${result.targetLabel}")
            is QQBotSendResult.Failed -> Log.w(TAG, "推送失败：[${result.code}] ${result.reason}")
        }
    }

    /** 按协议把消息投递出去。 */
    private suspend fun deliver(
        config: QQBotConfig,
        text: String,
        inbound: QQBotInbound,
        quoteMessageId: String,
        atUserId: String,
    ): QQBotSendResult = when (config.protocol) {
        QQBotProtocol.QQ_OFFICIAL -> QQOfficialClient.sendText(
            config = config,
            text = text,
            groupOpenid = if (inbound.isGroup) inbound.groupId else "",
            userOpenid = if (inbound.isGroup) "" else inbound.userId,
            quoteMessageId = quoteMessageId,
        )

        QQBotProtocol.ONEBOT_V11 -> QQBotClient.sendText(
            config = config,
            text = text,
            userId = if (inbound.isGroup) "" else inbound.userId.ifBlank { config.targetUserId },
            groupId = if (inbound.isGroup) inbound.groupId.ifBlank { config.targetGroupId } else "",
            replyTo = quoteMessageId,
            atUserId = atUserId,
        )
    }

    private suspend fun lastAssistantText(conversationId: Uuid): String = runCatching {
        chatService.getConversationFlow(conversationId).value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()?.trim().orEmpty()
    }.getOrDefault("")

    private fun lastAssistantModelId(conversationId: Uuid): Uuid? = runCatching {
        chatService.getConversationFlow(conversationId).value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }?.modelId
    }.getOrNull()

    private fun modelName(id: Uuid?): String? {
        if (id == null) return null
        return settingsStore.settingsFlow.value.providers
            .firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == id }?.displayName }
    }

    private companion object {
        const val TAG = "QQBotGateway"
    }
}
