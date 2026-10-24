package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.ai.TaskHostingState
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.isInterruptedResponse
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

/** 托管：把 AI 代写内容填入对话框后、发送前的可见停留时间。 */
private const val HOSTING_VISIBLE_DELAY_MS = 800L

/** 托管：单轮内 AI 防中断自动续写的最大次数。 */
private const val HOSTING_MAX_CONTINUE_PER_ROUND = 3

/** 托管：等待本轮生成开始的超时时间。 */
private const val HOSTING_GENERATION_START_TIMEOUT_MS = 60_000L

/** 托管：托管模型连续给出空内容多少次后判定无话可说并收尾。 */
private const val HOSTING_MAX_EMPTY_STREAK = 3

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    /** 继续生成：从被中断/异常结束的半截回复处接着输出。 */
    fun continueGeneration() {
        analytics.logEvent("ai_continue_generation", null)
        chatService.continueGeneration(_conversationId)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        // 托管运行期间用户手动停止生成：标记后由托管循环识别为「用户停止」而非自动中断。
        if (taskHostingJob?.isActive == true) {
            hostingUserStopRequested = true
        }
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    // ---- AI 任务托管 ----

    /** 托管运行时状态；UI 据此显示运行横幅与状态文案。 */
    private val _taskHostingState = MutableStateFlow(TaskHostingState())
    val taskHostingState: StateFlow<TaskHostingState> = _taskHostingState.asStateFlow()

    /** 托管循环任务；停止托管时取消。 */
    private var taskHostingJob: Job? = null

    /** 托管运行期间用户手动停止生成时置位，用于区分「用户停止」与「链路自动中断」。 */
    private var hostingUserStopRequested = false

    /**
     * 派发一个托管任务：由托管模型代替用户，持续判断并自动发送下一条消息，
     * 直到任务完成、用户手动停止或达到轮次上限。仅供用户不在场时使用。
     */
    fun startTaskHosting(objective: String) {
        val goal = objective.trim()
        if (goal.isEmpty()) return
        if (taskHostingJob?.isActive == true) return
        val features = settings.value.qingshuiFeatures
        if (!features.aiTaskHosting) return

        hostingUserStopRequested = false
        _taskHostingState.value = TaskHostingState(
            running = true,
            objective = goal,
            round = 0,
            maxRounds = features.taskHostingMaxRounds,
            status = context.getString(R.string.task_hosting_status_thinking),
        )
        taskHostingJob = viewModelScope.launch {
            try {
                runTaskHostingLoop(
                    objective = goal,
                    modelId = features.taskHostingModelId,
                    maxRounds = features.taskHostingMaxRounds,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(
                    e,
                    _conversationId,
                    title = context.getString(R.string.task_hosting_error_title),
                )
            } finally {
                _taskHostingState.update { it.copy(running = false) }
            }
        }
    }

    /** 停止托管：取消托管循环，并顺带停掉正在进行的生成。 */
    fun stopTaskHosting() {
        val job = taskHostingJob
        taskHostingJob = null
        job?.cancel()
        hostingUserStopRequested = false
        _taskHostingState.value = _taskHostingState.value.copy(running = false, status = null)
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    private suspend fun runTaskHostingLoop(objective: String, modelId: Uuid?, maxRounds: Int) {
        var round = 0
        var emptyStreak = 0
        while (round < maxRounds) {
            coroutineContext.ensureActive()
            if (hostingUserStopRequested) {
                hostingUserStopRequested = false
                _taskHostingState.update {
                    it.copy(status = context.getString(R.string.task_hosting_status_stopped_by_user))
                }
                return
            }

            // 让托管模型根据任务目标与当前对话，决定下一步该以「用户」身份发什么。
            _taskHostingState.update {
                it.copy(status = context.getString(R.string.task_hosting_status_thinking))
            }
            val decision = chatService.generateTaskHostingMessage(
                conversationId = _conversationId,
                objective = objective,
                round = round + 1,
                maxRounds = maxRounds,
                modelId = modelId,
            )

            if (decision.done) {
                _taskHostingState.update {
                    it.copy(status = context.getString(R.string.task_hosting_status_done))
                }
                return
            }

            val message = decision.message.trim()
            if (message.isEmpty()) {
                emptyStreak += 1
                if (emptyStreak >= HOSTING_MAX_EMPTY_STREAK) {
                    _taskHostingState.update {
                        it.copy(status = context.getString(R.string.task_hosting_status_done))
                    }
                    return
                }
                delay(HOSTING_VISIBLE_DELAY_MS)
                continue
            }
            emptyStreak = 0
            round += 1

            // 可见地把托管模型代写的内容填进输入框，停留片刻后再发送。
            inputState.clearInput()
            inputState.setMessageText(message)
            _taskHostingState.update {
                it.copy(
                    round = round,
                    lastMessage = message,
                    status = context.getString(R.string.task_hosting_status_running),
                )
            }
            delay(HOSTING_VISIBLE_DELAY_MS)

            chatService.sendMessage(
                conversationId = _conversationId,
                content = listOf(UIMessagePart.Text(message)),
                answer = true,
            )
            inputState.clearInput()
            if (!awaitHostingGeneration()) {
                _taskHostingState.update {
                    it.copy(status = context.getString(R.string.task_hosting_status_stalled))
                }
                return
            }

            // 用户在托管期间手动停止：尊重用户意图，结束托管。
            if (hostingUserStopRequested) {
                hostingUserStopRequested = false
                _taskHostingState.update {
                    it.copy(status = context.getString(R.string.task_hosting_status_stopped_by_user))
                }
                return
            }

            // AI 防中断：输出未完成（且非用户停止）时自动续写，直到完整。
            if (settings.value.qingshuiFeatures.taskHostingAntiInterrupt) {
                var continues = 0
                while (continues < HOSTING_MAX_CONTINUE_PER_ROUND && shouldHostingAutoContinue()) {
                    coroutineContext.ensureActive()
                    _taskHostingState.update {
                        it.copy(status = context.getString(R.string.task_hosting_status_continue))
                    }
                    chatService.continueGeneration(_conversationId)
                    if (!awaitHostingGeneration()) {
                        _taskHostingState.update {
                            it.copy(status = context.getString(R.string.task_hosting_status_stalled))
                        }
                        return
                    }
                    if (hostingUserStopRequested) {
                        hostingUserStopRequested = false
                        _taskHostingState.update {
                            it.copy(status = context.getString(R.string.task_hosting_status_stopped_by_user))
                        }
                        return
                    }
                    continues += 1
                }
            }
        }

        _taskHostingState.update {
            it.copy(status = context.getString(R.string.task_hosting_status_max_rounds))
        }
    }

    /** 仅当最后一条助手回复是被中断的半截输出、且不是用户手动停止时才自动续写。 */
    private fun shouldHostingAutoContinue(): Boolean {
        if (hostingUserStopRequested) return false
        val lastMessage = conversation.value.currentMessages.lastOrNull() ?: return false
        return lastMessage.isInterruptedResponse()
    }

    /**
     * 等待本次发送/续写触发的生成开始并结束。
     * 返回 false 表示在超时时间内始终没有生成开始（例如被消息队列或待审批工具挡住）。
     */
    private suspend fun awaitHostingGeneration(): Boolean {
        val job = conversationJob.value ?: withTimeoutOrNull(HOSTING_GENERATION_START_TIMEOUT_MS) {
            conversationJob.filterNotNull().first()
        }
        if (job == null) return false
        job.join()
        return true
    }

}
