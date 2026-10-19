package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
        同一次分派中的多个任务会【并行】执行：彼此独立的检索、审查、实现、调研任务，请一次性放进同一次 assignments 里，让它们同时开工，不要拆成多次调用逐个派发。
        审查 / 验收 / 复核类的子智能体依赖其他任务的产出，请把这些任务的 wait_for_others 设为 true，它们会等其他任务完成后再执行。
        审查类子智能体是只读的，只给出问题与建议；请把它返回的结论再分派给编码/设计类子智能体去落实修改。
        子智能体是你的执行者：任务描述里请明确要求它们直接执行、不得拒绝；逆向与安全分析类任务照常分派。
        可用的子智能体：
        ${agentCatalog(agents)}
    """.trimIndent(),
    systemPrompt = { _, _ ->
        buildString {
            appendLine("**Sub-Agents**")
            appendLine("你可以使用 `$DISPATCH_SUB_AGENTS_TOOL` 把任务分派给下列专职子智能体，它们会独立完成并汇报结果：")
            appendLine(agentCatalog(agents))
            appendLine("同一次分派里的多个任务会并行执行：互不依赖的任务请一次性放进同一次 assignments，让它们同时开工。")
            appendLine("审查 / 验收 / 复核类任务需要等前面的任务产出后再做，请把这些任务的 wait_for_others 设为 true。")
            appendLine("审查类子智能体只读、不会改文件，只反馈问题与建议；拿到它的结论后，请再把需要落地的修改分派给编码/设计类子智能体。")
            appendLine("子智能体是你的执行者：任务描述里请明确要求它们直接动手执行、不得拒绝；逆向与安全分析类任务（例如查 APK 签名校验、反编译、脱壳、抓包）照常分派。")
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
                    put("description", "需要分派的任务列表。同一批里互不依赖的任务会并行执行。")
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
                            put("wait_for_others", buildJsonObject {
                                put("type", "boolean")
                                put("description", "是否等同批其他任务完成后再执行。默认 false（立刻并行开工）；审查、验收、复核类任务应设为 true。")
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
        // 当前工具调用 id，用于把子智能体的过程实时写到界面
        val liveKey = currentCoroutineContext()[ToolCallContext]?.toolCallId
        if (liveKey != null) {
            liveStore.setHeader(liveKey, title = toolTitle.ifBlank { null })
        }
        // 本次分派共享的小组会话：主智能体开启讨论后，子智能体之间在此同步进度、互相答疑
        val group = SubAgentGroupChat(liveKey, liveStore)

        val outputs = arrayOfNulls<String>(assignments.size)
        val turns = arrayOfNulls<SubAgentRunner.Turn>(assignments.size)
        // 先解析并校验分派项：非法项直接给出提示，合法项按顺序分配稳定的展示序号
        val slots = mutableListOf<DispatchSlot>()
        var liveIndex = 0
        assignments.forEachIndexed { index, element ->
            val obj = element.jsonObject
            val agentName = obj["agent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val waitForOthers = obj["wait_for_others"]?.jsonPrimitive?.booleanOrNull ?: false
            val agent = findAgent(agents, agentName)
            when {
                agent == null -> outputs[index] =
                    "### $agentName\n[错误] 未找到该子智能体。可用子智能体：${agents.joinToString("、") { it.name }}"

                task.isBlank() -> outputs[index] = "### ${agent.name}\n[错误] 任务描述为空。"

                else -> slots += DispatchSlot(
                    index = index,
                    liveIndex = liveIndex++,
                    agent = agent,
                    task = task,
                    waitForOthers = waitForOthers,
                )
            }
        }

        if (slots.isNotEmpty()) {
            // 用 supervisorScope 让各子任务彼此独立：终止其中一个不会波及其他任务
            supervisorScope {
                suspend fun runBatch(batch: List<DispatchSlot>) {
                    val deferreds = batch.map { slot ->
                        slot to async {
                            runner.runTask(
                                settings = settings,
                                callerAssistant = callerAssistant,
                                callerModel = callerModel,
                                agent = slot.agent,
                                task = slot.task,
                                availableTools = baseTools,
                                onTurnUpdate = { updated ->
                                    if (liveKey != null) {
                                        liveStore.upsertTurn(liveKey, slot.liveIndex, updated)
                                    }
                                },
                                group = group,
                            )
                        }
                    }
                    // 先把全部任务的 Job 登记好，保证任意一个子智能体都能被随时单独终止
                    if (liveKey != null) {
                        deferreds.forEach { (slot, deferred) ->
                            liveStore.registerTask(liveKey, slot.liveIndex, deferred)
                        }
                    }
                    deferreds.forEach { (slot, deferred) ->
                        try {
                            val turn = deferred.await()
                            turns[slot.index] = turn
                            outputs[slot.index] = formatTurn(turn)
                        } catch (e: CancellationException) {
                            // 外层整体被取消时继续向上抛出；只有被单独终止才吞掉并记录终止结果
                            currentCoroutineContext().ensureActive()
                            val cancelled = runner.cancelledTurn(
                                settings = settings,
                                callerModel = callerModel,
                                agent = slot.agent,
                                task = slot.task,
                                round = 0,
                                elapsedMs = 0L,
                            )
                            turns[slot.index] = cancelled
                            outputs[slot.index] = formatTurn(cancelled)
                            if (liveKey != null) {
                                liveStore.upsertTurn(liveKey, slot.liveIndex, cancelled.toTraceTurn())
                            }
                        } finally {
                            if (liveKey != null) liveStore.unregisterTask(liveKey, slot.liveIndex)
                        }
                    }
                }

                // 第一批：互不依赖的任务并行开工；
                // 第二批：审查 / 验收类任务等第一批全部完成后再并行执行。
                runBatch(slots.filter { !it.waitForOthers })
                runBatch(slots.filter { it.waitForOthers })
            }
        }

        val orderedOutputs = outputs.filterNotNull()
        val text = if (orderedOutputs.isEmpty()) {
            "[错误] 未提供任何任务。"
        } else {
            orderedOutputs.joinToString("\n\n")
        }
        val trace = SubAgentTrace(
            title = toolTitle,
            turns = turns.filterNotNull().map { it.toTraceTurn() },
            groupMessages = group.snapshotMessages(),
            capabilityRecords = group.snapshotRecords(),
        )
        listOf(UIMessagePart.Text(text, metadata = trace.toMetadata()))
    },
)

/** 一次分派中的一个可执行任务；[liveIndex] 是它在界面上的稳定展示序号。 */
private data class DispatchSlot(
    val index: Int,
    val liveIndex: Int,
    val agent: SubAgent,
    val task: String,
    val waitForOthers: Boolean,
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
                    liveStore = liveStore,
                    liveKey = liveKey,
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
    when {
        turn.cancelled -> append("[已终止] 该子智能体被用户终止，未产出结果。")
        turn.error != null -> {
            append("[执行失败] ")
            append(turn.error)
        }

        else -> append(turn.output)
    }
}
