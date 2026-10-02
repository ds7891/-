package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupMessageKind
import me.rerere.rikkahub.data.model.SubAgent

/**
 * 给单个子智能体注入"协作工具"，让它在运行过程中能主动：
 *
 * - [ASK_MAIN_AGENT_TOOL]：有疑惑时先向主智能体一对一提问；主智能体可以直接回答，
 *   也可以决定"开启小组讨论"；
 * - [GROUP_POST_TOOL]：小组讨论开启后，往小组里同步进度、抛出问题（会有空闲智能体来回答）；
 * - [ASK_SUB_AGENT_TOOL]：点名向某个子智能体一对一提问；
 * - [REQUEST_CAPABILITY_TOOL]：申请自己缺少的工具（含 MCP 工具）或权限。
 *
 * @param pool 主智能体当前可用的完整工具池，用于裁定并发放申请的工具。
 * @param dynamicTools 正在运行的子智能体的实时工具列表；获批的工具会直接追加进来。
 * @param group 本次分派共享的小组会话；为空时不会注入 [GROUP_POST_TOOL]。
 */
internal fun createSubAgentCollaborationTools(
    settings: Settings,
    callerAssistant: Assistant,
    callerModel: Model,
    runner: SubAgentRunner,
    selfAgent: SubAgent,
    pool: List<Tool>,
    dynamicTools: MutableList<Tool>,
    depth: Int,
    group: SubAgentGroupChat?,
): List<Tool> {
    val allAgents = settings.subAgents.filter { it.enabled && it.name.isNotBlank() }
    val peers = allAgents.filter { it.id != selfAgent.id }

    val tools = mutableListOf<Tool>()

    tools += Tool(
        name = ASK_MAIN_AGENT_TOOL,
        description = """
            你有疑惑时向主智能体一对一提问，并拿到它的回复。
            主智能体要么直接回答你，要么决定开启"小组讨论"（届时所有子智能体会一起参与）。
            缺少关键信息、拿不定方向或需要更高层判断时使用。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "你要向主智能体提出的问题或请求，需包含足够上下文。")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "可选：相关背景、你的初步结论或已经尝试过的做法。")
                    })
                },
                required = listOf("question"),
            )
        },
        execute = { args ->
            val root = args.jsonObject
            val question = root["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val context = root["context"]?.jsonPrimitive?.contentOrNull?.trim()
            if (question.isBlank()) {
                listOf(UIMessagePart.Text("[错误] question 不能为空。"))
            } else {
                when (val reply = runner.consultMainAgent(
                    settings = settings,
                    callerAssistant = callerAssistant,
                    callerModel = callerModel,
                    agent = selfAgent,
                    question = question,
                    context = context,
                )) {
                    is SubAgentRunner.MainAgentReply.Answer ->
                        listOf(UIMessagePart.Text("【主智能体回复】\n" + reply.text.ifBlank { "(无回复)" }))

                    is SubAgentRunner.MainAgentReply.OpenGroupDiscussion -> {
                        if (group == null) {
                            listOf(UIMessagePart.Text("【主智能体回复】请先按你自己的判断继续推进。"))
                        } else {
                            val summary = runner.openGroupDiscussion(
                                settings = settings,
                                callerAssistant = callerAssistant,
                                callerModel = callerModel,
                                group = group,
                                agents = allAgents,
                                initiator = selfAgent,
                                topic = reply.topic,
                                question = question,
                                availableTools = pool,
                                depth = depth,
                            )
                            listOf(
                                UIMessagePart.Text(
                                    "【主智能体已开启小组讨论】\n主题：${reply.topic}\n\n$summary"
                                )
                            )
                        }
                    }
                }
            }
        },
    )

    if (group != null) {
        tools += Tool(
            name = GROUP_POST_TOOL,
            description = """
                向"小组讨论"同步内容。小组讨论需先由主智能体开启。
                kind=progress 时用来同步你的当前进度、遇到的问题与解决方法；
                kind=question 时用来抛出问题，会有空闲的子智能体来回答并给建议。
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "要同步的内容，需简洁清晰。")
                        })
                        put("kind", buildJsonObject {
                            put("type", "string")
                            put("description", "progress（同步进度/问题/解法）或 question（提问）。默认 progress。")
                            putJsonArray("enum") {
                                add(JsonPrimitive(GroupMessageKind.PROGRESS))
                                add(JsonPrimitive(GroupMessageKind.QUESTION))
                            }
                        })
                    },
                    required = listOf("content"),
                )
            },
            execute = { args ->
                val root = args.jsonObject
                val content = root["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val kind = root["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    ?.takeIf { it == GroupMessageKind.QUESTION } ?: GroupMessageKind.PROGRESS
                when {
                    content.isBlank() -> listOf(UIMessagePart.Text("[错误] content 不能为空。"))

                    !group.isOpen -> listOf(
                        UIMessagePart.Text("小组讨论尚未开启。请先用 $ASK_MAIN_AGENT_TOOL 向主智能体申请开启讨论。")
                    )

                    else -> {
                        group.post(selfAgent.name, kind, content)
                        if (kind == GroupMessageKind.QUESTION) {
                            val answer = runner.answerInGroup(
                                settings = settings,
                                callerAssistant = callerAssistant,
                                callerModel = callerModel,
                                group = group,
                                agents = allAgents,
                                asker = selfAgent,
                                question = content,
                                availableTools = pool,
                                depth = depth,
                            )
                            val text = if (answer == null) {
                                "已把问题同步到小组，暂无空闲智能体可回答。"
                            } else {
                                "已把问题同步到小组。\n【${answer.agent} 的回答】\n${answer.text}"
                            }
                            listOf(UIMessagePart.Text(text))
                        } else {
                            listOf(UIMessagePart.Text("已同步到小组讨论。"))
                        }
                    }
                }
            },
        )
    }

    if (peers.isNotEmpty()) {
        tools += Tool(
            name = ASK_SUB_AGENT_TOOL,
            description = """
                点名向另一个子智能体一对一提问，拿到它的答复后再继续你的工作。
                适合与同侪私下核对结论。
                可提问的对象：${peers.joinToString("、") { it.name }}
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("agent", buildJsonObject {
                            put("type", "string")
                            put("description", "子智能体名称，必须是：${peers.joinToString("、") { it.name }}")
                        })
                        put("question", buildJsonObject {
                            put("type", "string")
                            put("description", "你要向该子智能体提出的问题或请求，需包含足够上下文。")
                        })
                        put("context", buildJsonObject {
                            put("type", "string")
                            put("description", "可选：相关背景或你已有的进展。")
                        })
                    },
                    required = listOf("agent", "question"),
                )
            },
            execute = { args ->
                val root = args.jsonObject
                val name = root["agent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val question = root["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val context = root["context"]?.jsonPrimitive?.contentOrNull?.trim()
                val peer = peers.firstOrNull { it.name.equals(name, ignoreCase = true) }
                when {
                    peer == null -> listOf(
                        UIMessagePart.Text("[错误] 未找到子智能体「$name」。可提问的对象：${peers.joinToString("、") { it.name }}")
                    )

                    question.isBlank() -> listOf(UIMessagePart.Text("[错误] question 不能为空。"))

                    else -> {
                        val prompt = buildString {
                            if (!context.isNullOrBlank()) {
                                appendLine("【相关上下文】")
                                appendLine(context)
                                appendLine()
                            }
                            appendLine("【来自子智能体「${selfAgent.name}」的提问】")
                            appendLine(question)
                            appendLine()
                            appendLine("请以「${peer.name}」的身份直接回答，给出简明结论与依据。")
                        }
                        val turn = runner.runTask(
                            settings = settings,
                            callerAssistant = callerAssistant,
                            callerModel = callerModel,
                            agent = peer,
                            task = prompt,
                            availableTools = pool,
                            onTurnUpdate = null,
                            depth = depth + 1,
                            group = group,
                        )
                        val body = turn.error ?: turn.output
                        listOf(UIMessagePart.Text("【${peer.name} 的回复】\n$body"))
                    }
                }
            },
        )
    }

    tools += Tool(
        name = REQUEST_CAPABILITY_TOOL,
        description = """
            当你的工具不够用（例如缺少某个 MCP 工具或其它权限）时，申请开通。
            申请会转交用户确认（同意 / 拒绝 / 下一次默认同意）；用户同意后即直接开通。
            获批的工具在本次任务的后续步骤中即可直接调用。
            注意：网络请求（http_request）属于基础能力，已经默认具备，无需申请。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("description", "申请开通的工具名列表，例如 mcp__server__tool 或 search_web。")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "说明为什么需要这些工具，便于主智能体与用户判断。")
                    })
                },
                required = listOf("tools", "reason"),
            )
        },
        execute = { args ->
            val root = args.jsonObject
            val names = root["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val reason = root["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val text = runner.handleCapabilityRequest(
                agent = selfAgent,
                pool = pool,
                currentTools = dynamicTools,
                requestedNames = names,
                reason = reason,
                group = group,
            )
            listOf(UIMessagePart.Text(text))
        },
    )

    return tools
}
