package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ASK_MAIN_AGENT_TOOL
import me.rerere.rikkahub.data.ai.tools.ASK_SUB_AGENT_TOOL
import me.rerere.rikkahub.data.ai.tools.GROUP_POST_TOOL
import me.rerere.rikkahub.data.ai.tools.REQUEST_CAPABILITY_TOOL
import me.rerere.rikkahub.data.ai.tools.createSubAgentCollaborationTools
import me.rerere.rikkahub.data.ai.tools.local.HTTP_TOOL
import me.rerere.rikkahub.data.ai.tools.local.buildHttpTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CapabilityRecord
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMessageKind
import me.rerere.rikkahub.data.model.READ_ONLY_BLOCKED_TOOLS
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentStepType
import me.rerere.rikkahub.data.model.SubAgentTraceStep
import me.rerere.rikkahub.data.model.SubAgentTraceTurn
import me.rerere.rikkahub.data.model.SubAgentTurnStatus

private const val SUB_AGENT_MAX_STEPS = 24
private const val MAX_DISCUSSION_ROUNDS = 5

/** 子智能体之间互相点名讨论的最大嵌套深度，避免 A 问 B、B 又问 A 的无限递归。 */
private const val MAX_COLLAB_DEPTH = 2

// 过程记录里单个字段保留的最大字符数，以及单次发言的总预算，避免元数据膨胀
private const val STEP_TEXT_LIMIT = 2_000
private const val TRACE_CHAR_BUDGET = 12_000

// 实时过程的刷新间隔：太密会每来一个 token 就重算整份过程，太疏则不够"逐字"
private const val LIVE_PUBLISH_INTERVAL_MS = 120L

// 主智能体用它宣告"开启小组讨论"：回答首行只输出该标记，第二行起为讨论主题
private const val OPEN_GROUP_MARKER = "<<OPEN_GROUP_DISCUSSION>>"

// 拼进子智能体提示词时，最多带入的小组讨论消息条数
private const val MAX_GROUP_CONTEXT_MESSAGES = 24

/**
 * 子智能体的工具池里需要剔除的工具：
 * 派发/探讨工具（避免无限递归）与协作工具（由 [createSubAgentCollaborationTools] 单独注入）。
 */
private val SUB_AGENT_EXCLUDED_TOOLS = setOf(
    "dispatch_subagents",
    "discuss_subagents",
    ASK_MAIN_AGENT_TOOL,
    ASK_SUB_AGENT_TOOL,
    GROUP_POST_TOOL,
    REQUEST_CAPABILITY_TOOL,
)

/**
 * 在主智能体的生成过程中执行子智能体任务。
 *
 * 复用 [GenerationLoop] 完成一次独立的多步生成（含工具调用），
 * 从而让子智能体既拥有自己的系统提示词与工具白名单，又不必重复实现生成循环。
 * 每个子智能体可以使用 [SubAgent.modelId] 指定的模型；为空时跟随主智能体模型。
 *
 * 为了让界面能逐字看到过程，子智能体默认开启流式输出，并在每次增量时通过
 * [onTurnUpdate] 回调把当前发言状态推给调用方（由工具转发到 [SubAgentLiveStore]）。
 */
class SubAgentRunner(
    private val generationLoop: GenerationLoop,
    private val settingsStore: SettingsStore,
    private val approvalStore: CapabilityApprovalStore,
) {
    /** 一次子智能体发言的结果。 */
    data class Turn(
        val agentName: String,
        val round: Int,
        val output: String,
        val error: String? = null,
        /** 实际使用的模型名，用于过程展示 */
        val model: String = "",
        val readOnly: Boolean = false,
        /** 主智能体交给它的任务描述（探讨场景为空） */
        val task: String = "",
        /** 本次发言的推理、工具调用与文本过程 */
        val steps: List<SubAgentTraceStep> = emptyList(),
    ) {
        val isSuccess: Boolean get() = error == null

        fun toTraceTurn(): SubAgentTraceTurn = SubAgentTraceTurn(
            agent = agentName,
            round = round,
            model = model,
            readOnly = readOnly,
            status = if (isSuccess) SubAgentTurnStatus.OK else SubAgentTurnStatus.ERROR,
            task = task,
            steps = steps,
            output = output,
            error = error,
        )
    }

    /** 主智能体对子智能体提问的回应。 */
    sealed interface MainAgentReply {
        /** 主智能体直接给出了回答（一对一答疑，不展开讨论） */
        data class Answer(val text: String) : MainAgentReply

        /** 主智能体同意开启小组讨论，并给出讨论主题 */
        data class OpenGroupDiscussion(val topic: String) : MainAgentReply
    }

    /** 小组里某个空闲智能体对问题的回答。 */
    data class GroupAnswer(val agent: String, val text: String)

    /** 让单个子智能体完成一个任务。 */
    suspend fun runTask(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agent: SubAgent,
        task: String,
        availableTools: List<Tool>,
        onTurnUpdate: ((SubAgentTraceTurn) -> Unit)? = null,
        depth: Int = 0,
        group: SubAgentGroupChat? = null,
    ): Turn = runTurn(
        settings = settings,
        callerAssistant = callerAssistant,
        callerModel = callerModel,
        agent = agent,
        prompt = task.trim(),
        round = 0,
        task = task.trim(),
        availableTools = availableTools,
        onTurnUpdate = onTurnUpdate,
        depth = depth,
        group = group,
    )

    /**
     * 组织多个子智能体围绕一个主题进行多轮讨论，
     * 每个子智能体都能看到此前所有发言，从而形成"智能体探讨"。
     *
     * [onTurnUpdate] 的第二个参数是该发言在整场讨论中的序号，用于实时过程的有序拼接。
     */
    suspend fun discuss(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agents: List<SubAgent>,
        topic: String,
        rounds: Int,
        availableTools: List<Tool>,
        onTurnUpdate: ((Int, SubAgentTraceTurn) -> Unit)? = null,
        depth: Int = 0,
        group: SubAgentGroupChat? = null,
    ): List<Turn> {
        val result = mutableListOf<Turn>()
        val maxRounds = rounds.coerceIn(1, MAX_DISCUSSION_ROUNDS)
        repeat(maxRounds) { roundIndex ->
            agents.forEach { agent ->
                val prompt = buildDiscussionPrompt(topic, agent, result)
                val turnIndex = result.size
                result += runTurn(
                    settings = settings,
                    callerAssistant = callerAssistant,
                    callerModel = callerModel,
                    agent = agent,
                    prompt = prompt,
                    round = roundIndex + 1,
                    task = "",
                    availableTools = availableTools,
                    onTurnUpdate = { turn -> onTurnUpdate?.invoke(turnIndex, turn) },
                    depth = depth,
                    group = group,
                )
            }
        }
        return result
    }

    private suspend fun runTurn(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agent: SubAgent,
        prompt: String,
        round: Int,
        task: String,
        availableTools: List<Tool>,
        onTurnUpdate: ((SubAgentTraceTurn) -> Unit)?,
        depth: Int,
        group: SubAgentGroupChat?,
    ): Turn {
        // 子智能体指定了模型就用它，否则跟随主智能体当前使用的模型
        val agentModel = settings.findModelById(agent.modelId) ?: callerModel
        val agentAssistant = Assistant(
            id = agent.id,
            chatModelId = agentModel.id,
            name = agent.name,
            systemPrompt = buildString {
                append(agent.systemPrompt)
                if (agent.readOnly) {
                    append("\n\n【只读约束】你处于只读模式：只能读取与检索信息，")
                    append("禁止修改文件、执行写操作或做任何会改变系统状态的事情。")
                }
                append("\n\n【网络能力】你和主智能体拥有完全相同的网络功能：用 $HTTP_TOOL 直接发 GET/POST 等请求、")
                append("读取网页或 API 响应、并用 save_to 把图片 / 视频 / 软件包等任意文件下载到本机。")
                append("需要联网时直接调用 $HTTP_TOOL，不要因为没有网络而放弃。")
                append("除此之外的文件、媒体等本地工具只要已在你的工具列表里，也和主智能体用法完全一致。")
                if (depth < MAX_COLLAB_DEPTH) {
                    append("\n\n【协作】你有疑惑时用 $ASK_MAIN_AGENT_TOOL 向主智能体提问（它会直接回答，")
                    append("或决定开启小组讨论）；也可以用 $ASK_SUB_AGENT_TOOL 点名与某个子智能体一对一核对；")
                    append("用 $REQUEST_CAPABILITY_TOOL 申请你缺少的工具（含 MCP）或权限。")
                    if (group != null && group.isOpen) {
                        append("小组讨论已开启：用 $GROUP_POST_TOOL 同步你的进度、问题与解决方法")
                        append("（kind=question 抛出的问题会有空闲子智能体来回答）。")
                    }
                }
            },
            temperature = callerAssistant.temperature,
            topP = callerAssistant.topP,
            maxTokens = callerAssistant.maxTokens,
            // 开启流式，界面才能逐字看到子智能体的思考与工具调用过程
            streamOutput = true,
            reasoningLevel = ReasoningLevel.AUTO,
            localTools = emptyList(),
        )

        // 子智能体的实时工具列表：白名单工具 + 网络请求 + 协作工具。
        // 通过 request_capability 获批的工具会被直接追加进这个列表，本轮后续步骤立即可用。
        val dynamicTools = resolveTools(agent, availableTools).toMutableList()
        // 网络请求是子智能体的基础能力：无论白名单如何配置都直接注入，
        // 使子智能体能够像主智能体一样出网抓取 / 调用 API / 下载文件。
        ensureNetworkTool(dynamicTools, callerAssistant)
        if (depth < MAX_COLLAB_DEPTH) {
            dynamicTools += createSubAgentCollaborationTools(
                settings = settings,
                callerAssistant = callerAssistant,
                callerModel = callerModel,
                runner = this,
                selfAgent = agent,
                pool = availableTools,
                dynamicTools = dynamicTools,
                depth = depth,
                group = group,
            )
        }

        // 用当前状态拼出一份"发言快照"，实时推给界面
        fun snapshot(
            status: String,
            output: String,
            error: String?,
            steps: List<SubAgentTraceStep>,
        ) = SubAgentTraceTurn(
            agent = agent.name,
            round = round,
            model = agentModel.displayName,
            readOnly = agent.readOnly,
            status = status,
            task = task,
            steps = steps,
            output = output,
            error = error,
        )

        onTurnUpdate?.invoke(snapshot(SubAgentTurnStatus.RUNNING, "", null, emptyList()))

        var history: List<UIMessage> = emptyList()
        var lastPublishAt = 0L
        return try {
            generationLoop.generateText(
                settings = settings,
                model = agentModel,
                messages = listOf(UIMessage.user(prompt.ifBlank { "请开始你的工作。" })),
                assistant = agentAssistant,
                tools = dynamicTools,
                maxSteps = SUB_AGENT_MAX_STEPS,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    history = chunk.messages
                    val now = System.currentTimeMillis()
                    if (now - lastPublishAt >= LIVE_PUBLISH_INTERVAL_MS) {
                        lastPublishAt = now
                        onTurnUpdate?.invoke(
                            snapshot(
                                status = SubAgentTurnStatus.RUNNING,
                                output = history.lastOrNull()?.toText()?.trim().orEmpty(),
                                error = null,
                                steps = buildSteps(history),
                            )
                        )
                    }
                }
            }
            val turn = Turn(
                agentName = agent.name,
                round = round,
                output = history.lastOrNull()?.toText()?.trim().orEmpty().ifBlank { "(无输出)" },
                model = agentModel.displayName,
                readOnly = agent.readOnly,
                task = task,
                steps = buildSteps(history),
            )
            onTurnUpdate?.invoke(turn.toTraceTurn())
            turn
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val turn = Turn(
                agentName = agent.name,
                round = round,
                output = "",
                error = e.message ?: e.javaClass.simpleName,
                model = agentModel.displayName,
                readOnly = agent.readOnly,
                task = task,
                steps = buildSteps(history),
            )
            onTurnUpdate?.invoke(turn.toTraceTurn())
            turn
        }
    }

    /**
     * 子智能体向主智能体提问 / 求助。
     *
     * 用主智能体（当前助手 + 模型）做一次纯文本生成并返回答复；
     * 本次调用不携带任何工具，避免再次触发派发而形成递归。
     */
    suspend fun consultMainAgent(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agent: SubAgent,
        question: String,
        context: String?,
    ): MainAgentReply {
        val systemPrompt = buildString {
            appendLine("你是主智能体，正在与子智能体「${agent.name}」协作。")
            appendLine("子智能体会向你提问。默认由你直接回答：基于全局视角给出简明、可执行的结论，")
            appendLine("不要调用任何工具，也不要复述问题。")
            appendLine("只有当这个问题确实需要多个子智能体一起讨论时（涉及方案取舍、跨领域权衡、")
            appendLine("需要多方信息汇总），才改为开启小组讨论：第一行只输出 $OPEN_GROUP_MARKER，第二行起写讨论主题。")
        }
        val userPrompt = buildString {
            if (!context.isNullOrBlank()) {
                appendLine("【相关上下文】")
                appendLine(context.trim())
                appendLine()
            }
            appendLine("【子智能体「${agent.name}」的问题】")
            appendLine(question.trim())
        }
        val answer = callMainAgent(settings, callerAssistant, callerModel, systemPrompt, userPrompt)
        val lines = answer.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return if (lines.firstOrNull() == OPEN_GROUP_MARKER) {
            val topic = lines.drop(1).joinToString("\n").trim().ifBlank { question.trim() }
            MainAgentReply.OpenGroupDiscussion(topic)
        } else {
            MainAgentReply.Answer(answer)
        }
    }

    /**
     * 开启小组讨论：所有子智能体一起参与，先各自同步进度与遇到的问题，
     * 再让空闲智能体回答讨论中抛出的问题。小组之后会一直保留，随时补充。
     */
    suspend fun openGroupDiscussion(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        group: SubAgentGroupChat,
        agents: List<SubAgent>,
        initiator: SubAgent,
        topic: String,
        question: String,
        availableTools: List<Tool>,
        depth: Int,
    ): String {
        val alreadyOpen = group.isOpen
        group.open()
        if (!alreadyOpen) {
            group.post("主智能体", GroupMessageKind.DECISION, "已开启小组讨论，主题：$topic")
        }
        group.post(initiator.name, GroupMessageKind.QUESTION, question)

        val contributions = mutableListOf<GroupAnswer>()
        if (!alreadyOpen) {
            agents.filter { it.id != initiator.id && depth + 1 < MAX_COLLAB_DEPTH }.forEach { agent ->
                val prompt = buildGroupPrompt(topic, agent, group.recent(MAX_GROUP_CONTEXT_MESSAGES))
                val turn = runTurn(
                    settings = settings,
                    callerAssistant = callerAssistant,
                    callerModel = callerModel,
                    agent = agent,
                    prompt = prompt,
                    round = 0,
                    task = "",
                    availableTools = availableTools,
                    onTurnUpdate = null,
                    depth = depth + 1,
                    group = group,
                )
                val body = turn.error ?: turn.output
                group.post(agent.name, GroupMessageKind.PROGRESS, body)
                contributions += GroupAnswer(agent.name, body)
            }
        }

        val answer = answerInGroup(
            settings = settings,
            callerAssistant = callerAssistant,
            callerModel = callerModel,
            group = group,
            agents = agents,
            asker = initiator,
            question = question,
            availableTools = availableTools,
            depth = depth,
        )

        return buildString {
            appendLine("主题：$topic")
            if (contributions.isNotEmpty()) {
                appendLine()
                appendLine("各子智能体已同步进度与建议：")
                contributions.forEach { appendLine("- ${it.agent}：${it.text.take(300)}") }
            }
            if (answer != null) {
                appendLine()
                appendLine("【${answer.agent} 针对你的问题回答】")
                appendLine(answer.text)
            }
        }.trim()
    }

    /** 在小组里挑一个"空闲"子智能体来回答问题，并把回答发到群里。 */
    suspend fun answerInGroup(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        group: SubAgentGroupChat,
        agents: List<SubAgent>,
        asker: SubAgent?,
        question: String,
        availableTools: List<Tool>,
        depth: Int,
    ): GroupAnswer? {
        if (depth + 1 >= MAX_COLLAB_DEPTH) return null
        val responder = group.pickNextAgent(agents, excludeId = asker?.id) ?: return null
        val prompt = buildQuestionAnswerPrompt(
            question = question,
            responder = responder,
            history = group.recent(MAX_GROUP_CONTEXT_MESSAGES),
        )
        val turn = runTurn(
            settings = settings,
            callerAssistant = callerAssistant,
            callerModel = callerModel,
            agent = responder,
            prompt = prompt,
            round = 0,
            task = "",
            availableTools = availableTools,
            onTurnUpdate = null,
            depth = depth + 1,
            group = group,
        )
        val body = turn.error ?: turn.output
        group.post(responder.name, GroupMessageKind.ANSWER, body)
        return GroupAnswer(responder.name, body)
    }

    private fun buildGroupPrompt(
        topic: String,
        agent: SubAgent,
        history: List<GroupMessage>,
    ): String = buildString {
        appendLine("【小组讨论主题】")
        appendLine(topic.trim())
        appendLine()
        if (history.isNotEmpty()) {
            appendLine("【当前小组讨论记录】")
            history.forEach { appendLine("「${it.sender}」${it.content}") }
            appendLine()
        }
        appendLine("你是参与者「${agent.name}」，请结合你当前的进度发言：")
        appendLine("1) 已完成/正在做什么；2) 遇到的具体问题；3) 你的解决思路或对他人问题的建议。")
        appendLine("请直接给出内容（不要调用工具、不要复述主题）。")
    }

    private fun buildQuestionAnswerPrompt(
        question: String,
        responder: SubAgent,
        history: List<GroupMessage>,
    ): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("【小组讨论记录】")
            history.forEach { appendLine("「${it.sender}」${it.content}") }
            appendLine()
        }
        appendLine("【待回答的问题】")
        appendLine(question.trim())
        appendLine()
        appendLine("你是「${responder.name}」，请直接回答上面的问题，并给出你的意见或建议。")
        appendLine("如涉及文件修改，请在回答中说明需要改什么，不要真的去改。")
    }

    /**
     * 处理子智能体的工具 / 权限申请。
     *
     * 一律交由用户确认（同意 / 拒绝 / 下一次默认同意）：同意即直接开通，不再经主智能体二次裁定。
     * 若用户此前已选择"下一次默认同意"且尚未发出新消息，则本次申请直接放行。
     *
     * 获批的工具会立即追加进 [currentTools]（本次任务后续步骤即可用）；
     * 显式同意时会写回该子智能体的工具白名单长期生效。
     */
    suspend fun handleCapabilityRequest(
        agent: SubAgent,
        pool: List<Tool>,
        currentTools: MutableList<Tool>,
        requestedNames: List<String>,
        reason: String,
        group: SubAgentGroupChat? = null,
    ): String {
        val poolByName = pool.filter { it.name !in SUB_AGENT_EXCLUDED_TOOLS }.associateBy { it.name }
        val held = currentTools.map { it.name }.toSet()
        val requested = requestedNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) return "[错误] 未指定要申请的工具。"

        val alreadyHeld = requested.filter { it in held }
        val unknown = requested.filter { it !in held && it !in poolByName }
        val candidates = requested.mapNotNull { poolByName[it] }.filter { it.name !in held }

        if (candidates.isEmpty()) {
            return buildString {
                appendLine("没有可开通的新工具。")
                if (alreadyHeld.isNotEmpty()) appendLine("已经在用：${alreadyHeld.joinToString("、")}")
                if (unknown.isNotEmpty()) appendLine("当前不可用（可能未配置或未启用）：${unknown.joinToString("、")}")
            }.trim()
        }

        // 用户的"下一次默认同意"仍然有效时，直接放行，不再弹窗打扰。
        val autoApproved = approvalStore.isAutoApproveEnabled
        val decision = if (autoApproved) {
            CapabilityDecision.APPROVE
        } else {
            // 交由用户手动同意 / 拒绝 / 下一次默认同意；同意后直接开通，不再经主智能体二次裁定。
            approvalStore.request(agent.name, candidates.map { it.name }, reason)
        }

        val granted = if (decision == CapabilityDecision.REJECT) emptyList() else candidates
        granted.forEach { tool ->
            if (currentTools.none { it.name == tool.name }) {
                currentTools += tool.copy(needsApproval = { false })
            }
        }
        // 用户点「同意」确认过的这一批工具写回白名单长期生效；
        // 之后因"下一次默认同意"自动放行的申请只在本次问答内有效，不再写回。
        if (granted.isNotEmpty() && !autoApproved) persistGrantedTools(agent, granted)

        val grantedNames = granted.map { it.name }.toSet()
        // 把本次申请写进小组记录，随任务过程一并展示，便于追溯
        group?.addRecord(
            CapabilityRecord(
                agent = agent.name,
                tools = candidates.map { it.name },
                reason = reason,
                approved = granted.map { it.name },
                denied = candidates.map { it.name }.filterNot { it in grantedNames },
                decidedBy = if (autoApproved) "用户（默认同意）" else "用户",
            )
        )
        return buildString {
            if (granted.isNotEmpty()) {
                appendLine("已开通：${granted.joinToString("、") { it.name }}")
                appendLine("这些工具在本次任务后续步骤中可以直接调用。")
            }
            val denied = candidates.map { it.name }.filterNot { it in grantedNames }
            if (denied.isNotEmpty()) appendLine("未开通：${denied.joinToString("、")}")
            if (alreadyHeld.isNotEmpty()) appendLine("已经在用：${alreadyHeld.joinToString("、")}")
            if (unknown.isNotEmpty()) appendLine("当前不可用（可能未配置或未启用）：${unknown.joinToString("、")}")
        }.trim().ifBlank { "申请已处理。" }
    }

    /** 用主智能体做一次不带工具的单步生成，供协作问答复用。 */
    private suspend fun callMainAgent(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val assistantForCall = callerAssistant.copy(
            systemPrompt = systemPrompt,
            streamOutput = false,
            localTools = emptyList(),
        )
        var history: List<UIMessage> = emptyList()
        generationLoop.generateText(
            settings = settings,
            model = callerModel,
            messages = listOf(UIMessage.user(userPrompt)),
            assistant = assistantForCall,
            tools = emptyList(),
            maxSteps = 1,
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) history = chunk.messages
        }
        return history.lastOrNull()?.toText()?.trim().orEmpty()
    }

    /** 把获批的工具写回子智能体配置以持久生效；只读子智能体不持久化写入类工具。 */
    private suspend fun persistGrantedTools(agent: SubAgent, granted: List<Tool>) {
        val names = granted.map { it.name }
            .filter { !agent.readOnly || it !in READ_ONLY_BLOCKED_TOOLS }
        if (names.isEmpty()) return
        runCatching {
            settingsStore.update { current ->
                current.copy(
                    subAgents = current.subAgents.map { existing ->
                        // toolNames 为空表示"可用全部工具"，此时无需也不应写回
                        if (existing.id != agent.id || existing.toolNames.isEmpty()) {
                            existing
                        } else {
                            existing.copy(toolNames = (existing.toolNames + names).distinct())
                        }
                    }
                )
            }
        }
    }

    /**
     * 保证子智能体始终持有网络请求工具。
     *
     * 主智能体若已开启"网络请求"本地工具，工具池里已包含它；否则按主智能体配置的域名白名单
     * 现造一个，让子智能体能够直接出网（抓网页 / 调 API / 下载文件），而不必先申请。
     */
    private fun ensureNetworkTool(tools: MutableList<Tool>, callerAssistant: Assistant) {
        if (tools.any { it.name == HTTP_TOOL }) return
        tools += buildHttpTool(callerAssistant.httpAllowedDomains)
    }

    private fun resolveTools(agent: SubAgent, availableTools: List<Tool>): List<Tool> {
        val pool = availableTools.filter { it.name !in SUB_AGENT_EXCLUDED_TOOLS }
        val whitelisted = if (agent.toolNames.isEmpty()) {
            pool
        } else {
            val allow = agent.toolNames.toSet()
            pool.filter { it.name in allow }
        }
        // 只读子智能体再屏蔽一切会改变状态的工具
        val filtered = if (agent.readOnly) {
            whitelisted.filter { it.name !in READ_ONLY_BLOCKED_TOOLS }
        } else {
            whitelisted
        }
        // 嵌套生成中不弹审批，避免子智能体执行被挂起等待用户
        return filtered.map { it.copy(needsApproval = { false }) }
    }

    /**
     * 从生成过程的完整消息中抽取逐步过程，跳过第一条用户消息（任务描述本身）。
     * 结果仅用于界面展示，不参与模型上下文。
     */
    private fun buildSteps(messages: List<UIMessage>): List<SubAgentTraceStep> {
        val steps = mutableListOf<SubAgentTraceStep>()
        var remaining = TRACE_CHAR_BUDGET
        for (message in messages.drop(1)) {
            if (remaining <= 0) break
            for (part in message.parts) {
                if (remaining <= 0) break
                val raw = when (part) {
                    is UIMessagePart.Reasoning -> part.reasoning
                        .takeIf { it.isNotBlank() }
                        ?.let { SubAgentTraceStep(type = SubAgentStepType.REASONING, text = it) }

                    is UIMessagePart.Text -> part.text
                        .takeIf { it.isNotBlank() }
                        ?.let { SubAgentTraceStep(type = SubAgentStepType.TEXT, text = it) }

                    is UIMessagePart.Tool -> SubAgentTraceStep(
                        type = SubAgentStepType.TOOL,
                        tool = part.toolName,
                        input = part.input,
                        output = part.output
                            .filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text },
                    )

                    else -> null
                } ?: continue
                val step = raw.copy(
                    text = raw.text.take(STEP_TEXT_LIMIT),
                    input = raw.input.take(STEP_TEXT_LIMIT),
                    output = raw.output.take(STEP_TEXT_LIMIT),
                )
                remaining -= step.text.length + step.input.length + step.output.length
                steps += step
            }
        }
        return steps
    }

    private fun buildDiscussionPrompt(topic: String, agent: SubAgent, history: List<Turn>): String =
        buildString {
            appendLine("【讨论主题】")
            appendLine(topic.trim())
            appendLine()
            if (history.isEmpty()) {
                appendLine("你是本次讨论的参与者「${agent.name}」。请从你的专业角度发表观点，并给出依据与建议。")
            } else {
                appendLine("以下是到目前为止的讨论记录：")
                history.forEach { turn ->
                    appendLine("「${turn.agentName}」：${turn.output.ifBlank { "(无输出)" }}")
                }
                appendLine()
                appendLine("你是参与者「${agent.name}」。请阅读以上记录，补充、质疑或推进讨论，")
                appendLine("不要重复他人已说过的内容；如有分歧请明确指出。")
            }
        }
}
