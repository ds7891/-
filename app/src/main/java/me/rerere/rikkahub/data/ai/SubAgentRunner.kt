package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.READ_ONLY_BLOCKED_TOOLS
import me.rerere.rikkahub.data.model.SubAgent

private const val SUB_AGENT_MAX_STEPS = 24
private const val MAX_DISCUSSION_ROUNDS = 5

/** 子智能体之间不允许互相派发，避免无限递归。 */
private val SUB_AGENT_EXCLUDED_TOOLS = setOf("dispatch_subagents", "discuss_subagents")

/**
 * 在主智能体的生成过程中执行子智能体任务。
 *
 * 复用 [GenerationLoop] 完成一次独立的多步生成（含工具调用），
 * 从而让子智能体既拥有自己的系统提示词与工具白名单，又不必重复实现生成循环。
 */
class SubAgentRunner(
    private val generationLoop: GenerationLoop,
) {
    /** 一次子智能体发言的结果。 */
    data class Turn(
        val agentName: String,
        val round: Int,
        val output: String,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /** 让单个子智能体完成一个任务。 */
    suspend fun runTask(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agent: SubAgent,
        task: String,
        availableTools: List<Tool>,
    ): Turn = runTurn(
        settings = settings,
        callerAssistant = callerAssistant,
        callerModel = callerModel,
        agent = agent,
        prompt = task.trim(),
        round = 0,
        availableTools = availableTools,
    )

    /**
     * 组织多个子智能体围绕一个主题进行多轮讨论，
     * 每个子智能体都能看到此前所有发言，从而形成"智能体探讨"。
     */
    suspend fun discuss(
        settings: Settings,
        callerAssistant: Assistant,
        callerModel: Model,
        agents: List<SubAgent>,
        topic: String,
        rounds: Int,
        availableTools: List<Tool>,
    ): List<Turn> {
        val result = mutableListOf<Turn>()
        val maxRounds = rounds.coerceIn(1, MAX_DISCUSSION_ROUNDS)
        repeat(maxRounds) { roundIndex ->
            agents.forEach { agent ->
                val prompt = buildDiscussionPrompt(topic, agent, result)
                result += runTurn(
                    settings = settings,
                    callerAssistant = callerAssistant,
                    callerModel = callerModel,
                    agent = agent,
                    prompt = prompt,
                    round = roundIndex + 1,
                    availableTools = availableTools,
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
        availableTools: List<Tool>,
    ): Turn {
        val agentAssistant = Assistant(
            id = agent.id,
            chatModelId = callerModel.id,
            name = agent.name,
            systemPrompt = buildString {
                append(agent.systemPrompt)
                if (agent.readOnly) {
                    append("\n\n【只读约束】你处于只读模式：只能读取与检索信息，")
                    append("禁止修改文件、执行写操作或做任何会改变系统状态的事情。")
                }
            },
            temperature = callerAssistant.temperature,
            topP = callerAssistant.topP,
            maxTokens = callerAssistant.maxTokens,
            streamOutput = false,
            reasoningLevel = ReasoningLevel.AUTO,
            localTools = emptyList(),
        )
        var last: UIMessage? = null
        return try {
            generationLoop.generateText(
                settings = settings,
                model = callerModel,
                messages = listOf(UIMessage.user(prompt.ifBlank { "请开始你的工作。" })),
                assistant = agentAssistant,
                tools = resolveTools(agent, availableTools),
                maxSteps = SUB_AGENT_MAX_STEPS,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    last = chunk.messages.lastOrNull()
                }
            }
            Turn(
                agentName = agent.name,
                round = round,
                output = last?.toText()?.trim().orEmpty().ifBlank { "(无输出)" },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Turn(
                agentName = agent.name,
                round = round,
                output = "",
                error = e.message ?: e.javaClass.simpleName,
            )
        }
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
