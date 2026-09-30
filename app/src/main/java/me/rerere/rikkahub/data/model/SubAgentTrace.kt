package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

/** 子智能体过程步骤的类型 */
object SubAgentStepType {
    const val REASONING = "reasoning"
    const val TEXT = "text"
    const val TOOL = "tool"
}

/** 子智能体一次发言的状态 */
object SubAgentTurnStatus {
    const val RUNNING = "running"
    const val OK = "ok"
    const val ERROR = "error"
}

/** 小组讨论消息的类型 */
object GroupMessageKind {
    /** 同步当前进度、遇到的问题与解决方法 */
    const val PROGRESS = "progress"
    /** 提出问题，等待空闲智能体回答 */
    const val QUESTION = "question"
    /** 回答他人的问题、提意见 */
    const val ANSWER = "answer"
    /** 主智能体的决定（例如同意开启讨论） */
    const val DECISION = "decision"
}

/** 小组讨论中的一条消息 */
@Serializable
data class GroupMessage(
    /** 发送者：子智能体名，或"主智能体" */
    val sender: String = "",
    val kind: String = GroupMessageKind.PROGRESS,
    val content: String = "",
)

/** 一条工具 / 权限申请记录 */
@Serializable
data class CapabilityRecord(
    val agent: String = "",
    /** 申请的工具名 */
    val tools: List<String> = emptyList(),
    val reason: String = "",
    /** 获批的工具名 */
    val approved: List<String> = emptyList(),
    /** 被拒绝的工具名 */
    val denied: List<String> = emptyList(),
    /** 由谁裁定：主智能体 / 用户 */
    val decidedBy: String = "",
)

/**
 * 一次子智能体运行（分派或探讨）的完整过程记录。
 *
 * 该结构序列化后存放在工具输出部件的 metadata 中：模型只看到工具输出的纯文本摘要，
 * 界面则读取 metadata 渲染出"多智能体对话"视图，因此过程不会污染模型上下文。
 */
@Serializable
data class SubAgentTrace(
    /** 分派时由主智能体给出的任务标题 */
    val title: String = "",
    /** 探讨时的讨论主题 */
    val topic: String = "",
    val turns: List<SubAgentTraceTurn> = emptyList(),
    /** 小组讨论记录（子智能体之间交流进度 / 问题 / 建议） */
    val groupMessages: List<GroupMessage> = emptyList(),
    /** 工具 / 权限申请记录 */
    val capabilityRecords: List<CapabilityRecord> = emptyList(),
)

/** 单个子智能体的一次发言（含它自己的推理、工具调用与输出） */
@Serializable
data class SubAgentTraceTurn(
    val agent: String = "",
    /** 探讨场景下的轮次，分派场景固定为 0 */
    val round: Int = 0,
    /** 实际使用的模型名 */
    val model: String = "",
    val readOnly: Boolean = false,
    val status: String = SubAgentTurnStatus.RUNNING,
    /** 主智能体交给它的任务描述（探讨场景为空） */
    val task: String = "",
    val steps: List<SubAgentTraceStep> = emptyList(),
    val output: String = "",
    val error: String? = null,
)

/** 子智能体过程中的一个步骤 */
@Serializable
data class SubAgentTraceStep(
    val type: String = SubAgentStepType.TEXT,
    val text: String = "",
    val tool: String = "",
    val input: String = "",
    val output: String = "",
)

/** 写入 [me.rerere.ai.ui.UIMessagePart.metadata]，随会话一起持久化 */
fun SubAgentTrace.toMetadata(): JsonObject = JsonInstant.encodeToJsonElement(this).jsonObject

/** 从 [me.rerere.ai.ui.UIMessagePart.metadata] 读取，解析失败或不存在时返回 null */
fun JsonObject?.toSubAgentTrace(): SubAgentTrace? =
    this?.let { runCatching { JsonInstant.decodeFromJsonElement<SubAgentTrace>(it) }.getOrNull() }
