package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SubAgentGroupChat
import me.rerere.rikkahub.data.ai.SubAgentLiveStore
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.ToolCallContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentTrace
import me.rerere.rikkahub.data.model.SubAgentTraceTurn
import me.rerere.rikkahub.data.model.SubAgentTurnStatus
import me.rerere.rikkahub.data.model.toMetadata

const val DISPATCH_SUB_AGENTS_TOOL = "dispatch_subagents"
const val DISCUSS_SUB_AGENTS_TOOL = "discuss_subagents"

/** 子智能体运行中可用的协作工具（注入给子智能体，主智能体不直接使用） */
const val ASK_MAIN_AGENT_TOOL = "ask_main_agent"
const val ASK_SUB_AGENT_TOOL = "ask_sub_agent"
const val REQUEST_CAPABILITY_TOOL = "request_capability"

/** 小组讨论开启后，子智能体用它往小组同步进度、问题与建议（注入给子智能体）。 */
const val GROUP_POST_TOOL = "group_post"

private const val MAX_DISCUSSION_AGENTS = 4

/**
 * 为主智能体构建子智能体相关工具。
 *
 * - [DISPATCH_SUB_AGENTS_TOOL]：把一个/多个任务分派给命名的子智能体
 * - [DISCUSS_SUB_AGENTS_TOOL]：组织多个子智能体围绕一个主题多轮讨论
 *
 * @param baseTools 主智能体当前可用的工具；子智能体从中按白名单取用，且不会获得派发工具本身。
 */
fun createSubAgentTools(
    settings: Settings,
    callerAssistant: Assistant,
    callerModel: Model,
    runner: SubAgentRunner,
    baseTools: List<Tool>,
    liveStore: SubAgentLiveStore,
): List<Tool> {
    val agents = settings.subAgents.filter { it.enabled && it.name.isNotBlank() }
    if (agents.isEmpty()) return emptyList()

    return listOf(
        buildDispatchTool(agents, settings, callerAssistant, callerModel, runner, baseTools, liveStore),
        buildDiscussTool(agents, settings, callerAssistant, callerModel, runner, baseTools, liveStore),
    )
}

private fun buildDispatchTool(
    agents: List<SubAgent>,
    settings: Settings,
    callerAssistant: Assistant,
    callerModel: Model,
    runner: SubAgentRunner,
    baseTools: List<Tool>,
    liveStore: SubAgentLiveStore,
): Tool = Tool(
    name = DISPATCH_SUB_AGENTS_TOOL,
    description = """
        把一个或多个任务分派给专职子智能体，并等待它们完成后返回结果。
        当任务需要独立的检索、审查、实现或调研，或需要并行处理多个子任务时，优先使用本工具。
        审查类子智能体是只读的，只给出问题与建议；请把它返回的结论再分派给编码/设计类子智能体去落实修改。
        可用的子智能体：
        ${agentCatalog(agents)}
    """.trimIndent(),
    systemPrompt = { _, _ ->
        buildString {
            appendLine("**Sub-Agents**")
            appendLine("你可以使用 `$DISPATCH_SUB_AGENTS_TOOL` 把任务分派给下列专职子智能体，它们会独立完成并汇报结果：")
            appendLine(agentCatalog(agents))
            appendLine("当任务需要独立检索、审查、实现或调研时，优先委派给合适的子智能体。")
            appendLine("审查类子智能体只读、不会改文件，只反馈问题与建议；拿到它的结论后，请再把需要落地的修改分派给编码/设计类子智能体。")
            appendLine("你也可以使用 `$DISCUSS_SUB_AGENTS_TOOL` 组织多个子智能体围绕一个主题展开讨论。")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_title", buildJsonObject {
                    put("type", "string")
                    put("description", "简短的任务标题（5-10 个词），用于向用户展示本次分派。")
                })
                put("assignments", buildJsonObject {
                    put("type", "array")
                    put("description", "需要分派的任务列表。")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("agent", buildJsonObject {
                                put("type", "string")
                                put("description", "子智能体名称，必须是：${agents.joinToString("、") { it.name }}")
                            })
                            put("task", buildJsonObject {
                                put("type", "string")
                                put("description", "交给该子智能体的具体任务，需包含充分的上下文。")
                            })
                        })
                        putJsonArray("required") {
                            add(JsonPrimitive("agent"))
                            add(JsonPrimitive("task"))
                        }
                    })
                })
            },
            required = listOf("tool_title", "assignments"),
        )
    },
    execute = { args ->
        val root = args.jsonObject
        val toolTitle = root["tool_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val assignments = root["assignments"]?.jsonArray ?: JsonArray(emptyList())
        val outputs = mutableListOf<String>()
        val turns = mutableListOf<SubAgentRunner.Turn>()
        // 当前工具调用 id，用于把子智能体的过程实时写到界面
        val liveKey = currentCoroutineContext()[ToolCallContext]?.toolCallId
        if (liveKey != null) {
            liveStore.setHeader(liveKey, title = toolTitle.ifBlank { null })
        }
        // 本次分派共享的小组会话：主智能体开启讨论后，子智能体之间在此同步进度、互相答疑
        val group = SubAgentGroupChat(liveKey, liveStore)
        assignments.forEach { element ->
            val obj = element.jsonObject
            val agentName = obj["agent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val agent = findAgent(agents, agentName)
            when {
                agent == null -> outputs += "### $agentName\n[错误] 未找到该子智能体。可用子智能体：${agents.joinToString("、") { it.name }}"
                task.isBlank() -> outputs += "### ${agent.name}\n[错误] 任务描述为空。"
                else -> {
                    val liveIndex = turns.size
                    val turn = runner.runTask(
                        settings = settings,
                        callerAssistant = callerAssistant,
                        callerModel = callerModel,
                        agent = agent,
                        task = task,
                        availableTools = baseTools,
                        onTurnUpdate = { updated ->
                            if (liveKey != null) liveStore.upsertTurn(liveKey, liveIndex, updated)
                        },
                        group = group,
                    )
                    turns += turn
                    outputs += formatTurn(turn)
                }
            }
        }
        if (outputs.isEmpty()) {
            outputs += "[错误] 未提供任何任务。"
        }
        val trace = SubAgentTrace(
            title = toolTitle,
            turns = turns.map { it.toTraceTurn() },
            groupMessages = group.snapshotMessages(),
            capabilityRecords = group.snapshotRecords(),
        )
        listOf(UIMessagePart.Text(outputs.joinToString("\n\n"), metadata = trace.toMetadata()))
    },
)

private fun buildDiscussTool(
    agents: List<SubAgent>,
    settings: Settings,
    callerAssistant: Assistant,
    callerModel: Model,
    runner: SubAgentRunner,
    baseTools: List<Tool>,
    liveStore: SubAgentLiveStore,
): Tool = Tool(
    name = DISCUSS_SUB_AGENTS_TOOL,
    description = """
        组织多个子智能体围绕一个主题进行多轮讨论，返回完整讨论记录。
        适用于需要多视角权衡、头脑风暴或方案评审的场景。
        可用的子智能体：
        ${agentCatalog(agents)}
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "讨论主题，需说明背景、目标与需要回答的问题。")
                })
                put("agents", buildJsonObject {
                    put("type", "array")
                    put("description", "参与讨论的子智能体名称列表，至少 2 个，最多 $MAX_DISCUSSION_AGENTS 个。")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("description", "子智能体名称，必须是：${agents.joinToString("、") { it.name }}")
                    })
                })
                put("rounds", buildJsonObject {
                    put("type", "integer")
                    put("description", "讨论轮数，1 到 5，默认 2。")
                })
            },
            required = listOf("topic", "agents"),
        )
    },
    execute = { args ->
        val root = args.jsonObject
        val topic = root["topic"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val names = root["agents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val participants = names
            .mapNotNull { findAgent(agents, it) }
            .distinctBy { it.id }
            .take(MAX_DISCUSSION_AGENTS)
        val rounds = root["rounds"]?.jsonPrimitive?.intOrNull ?: 2

        // 当前工具调用 id，用于把各子智能体的发言实时写到界面
        val liveKey = currentCoroutineContext()[ToolCallContext]?.toolCallId
        if (liveKey != null && topic.isNotBlank()) {
            liveStore.setHeader(liveKey, topic = topic)
        }
        // 本次探讨共享的小组会话：受主智能体开启后，各子智能体可同步进度、互相答疑
        val group = SubAgentGroupChat(liveKey, liveStore)

        var turns: List<SubAgentRunner.Turn> = emptyList()
        val text = when {
            topic.isBlank() -> "[错误] 讨论主题不能为空。"
            participants.size < 2 -> "[错误] 至少需要两个有效的子智能体才能进行讨论。可用子智能体：${agents.joinToString("、") { it.name }}"
            else -> {
                turns = runner.discuss(
                    settings = settings,
                    callerAssistant = callerAssistant,
                    callerModel = callerModel,
                    agents = participants,
                    topic = topic,
                    rounds = rounds,
                    availableTools = baseTools,
                    onTurnUpdate = { index, turn ->
                        if (liveKey != null) liveStore.upsertTurn(liveKey, index, turn)
                    },
                    group = group,
                )
                buildString {
                    appendLine("## 智能体探讨：$topic")
                    appendLine()
                    turns.forEach {
                        appendLine(formatTurn(it))
                        appendLine()
                    }
                }.trim()
            }
        }
        val trace = SubAgentTrace(
            topic = topic,
            turns = turns.map { it.toTraceTurn() },
            groupMessages = group.snapshotMessages(),
            capabilityRecords = group.snapshotRecords(),
        )
        listOf(UIMessagePart.Text(text, metadata = trace.toMetadata()))
    },
)

private fun agentCatalog(agents: List<SubAgent>): String =
    agents.joinToString("\n") { agent ->
        val suffix = if (agent.readOnly) "（只读：只审查、不改文件）" else ""
        "- ${agent.name}：${agent.description.ifBlank { "（无描述）" }}$suffix"
    }

private fun findAgent(agents: List<SubAgent>, name: String): SubAgent? =
    agents.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

private fun formatTurn(turn: SubAgentRunner.Turn): String = buildString {
    append("### ")
    append(turn.agentName)
    if (turn.round > 0) {
        append("（第 ${turn.round} 轮）")
    }
    appendLine()
    if (turn.error != null) {
        append("[执行失败] ")
        append(turn.error)
    } else {
        append(turn.output)
    }
}

/** 把一次发言转成可持久化的过程记录，供界面还原多智能体对话视图。 */
private fun SubAgentRunner.Turn.toTraceTurn(): SubAgentTraceTurn = SubAgentTraceTurn(
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
