package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.SubAgentLiveStore
import me.rerere.rikkahub.data.ai.tools.DISCUSS_SUB_AGENTS_TOOL
import me.rerere.rikkahub.data.ai.tools.DISPATCH_SUB_AGENTS_TOOL
import me.rerere.rikkahub.data.model.SubAgentStepType
import me.rerere.rikkahub.data.model.SubAgentTraceStep
import me.rerere.rikkahub.data.model.SubAgentTraceTurn
import me.rerere.rikkahub.data.model.toSubAgentTrace
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import org.koin.compose.koinInject

/** 子智能体任务分派的步骤渲染器：展开后展示每个子智能体的推理、工具调用与输出。 */
object SubAgentDispatchToolUI : ToolUIRenderer {
    override val toolName: String = DISPATCH_SUB_AGENTS_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiBrain01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_sub_agent_dispatch)

    // 运行中也展示，才能在生成过程中逐字看到子智能体的推理与工具调用
    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.isExecuted || context.loading

    @Composable
    override fun Summary(context: ToolUIContext) {
        SubAgentTraceContent(context)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        SubAgentTraceContent(context, scrollable = true)
    }
}

/** 子智能体探讨的步骤渲染器：展开后展示多轮多智能体的对话过程。 */
object SubAgentDiscussToolUI : ToolUIRenderer {
    override val toolName: String = DISCUSS_SUB_AGENTS_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiBrain01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_sub_agent_discuss)

    // 运行中也展示，才能在生成过程中逐字看到各子智能体的发言
    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.isExecuted || context.loading

    @Composable
    override fun Summary(context: ToolUIContext) {
        SubAgentTraceContent(context)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        SubAgentTraceContent(context, scrollable = true)
    }
}

/**
 * 渲染"多智能体对话"视图。
 *
 * 生成过程中读取 [SubAgentLiveStore] 里的实时过程，随每次快照逐字刷新；
 * 工具执行结束后消息 metadata 里已有完整记录，则以它为准。
 */
@Composable
private fun SubAgentTraceContent(context: ToolUIContext, scrollable: Boolean = false) {
    val persisted = context.tool.output
        .filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.metadata
        .toSubAgentTrace()

    val liveStore: SubAgentLiveStore = koinInject()
    val liveTraces by liveStore.traces.collectAsState()
    val trace = persisted ?: liveTraces[context.tool.toolCallId]

    val modifier = if (scrollable) {
        Modifier
            .fillMaxHeight(0.8f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            trace != null && trace.turns.isNotEmpty() -> {
                val heading = trace.topic.ifBlank { trace.title }
                if (heading.isNotBlank()) {
                    Text(
                        text = heading,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                trace.turns.forEach { turn ->
                    SubAgentTurnCard(turn)
                }
            }
            // 子任务刚启动、尚未产出过程时不显示空占位，避免闪一下
            context.loading -> Unit
            else -> Text(
                text = stringResource(R.string.chat_message_sub_agent_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubAgentTurnCard(turn: SubAgentTraceTurn) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.AiBrain01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = turn.agent,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (turn.round > 0) {
                    Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.chat_message_sub_agent_round, turn.round))
                    }
                }
                if (turn.readOnly) {
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.chat_message_sub_agent_read_only))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (turn.model.isNotBlank()) {
                    Text(
                        text = turn.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (turn.task.isNotBlank()) {
                SubAgentLabeledText(
                    label = stringResource(R.string.chat_message_sub_agent_task),
                    text = turn.task,
                )
            }

            turn.steps.forEach { step ->
                SubAgentStepView(step)
            }

            if (turn.output.isNotBlank()) {
                HorizontalDivider()
                SubAgentLabeledText(
                    label = stringResource(R.string.chat_message_sub_agent_output),
                    text = turn.output,
                )
            }

            if (!turn.error.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_message_sub_agent_failed) + ": " + turn.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SubAgentStepView(step: SubAgentTraceStep) {
    when (step.type) {
        SubAgentStepType.REASONING -> SubAgentLabeledText(
            label = stringResource(R.string.chat_message_sub_agent_thinking),
            text = step.text,
            muted = true,
        )

        SubAgentStepType.TOOL -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_sub_agent_tool) + " · " + step.tool,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (step.input.isNotBlank()) {
                Text(
                    text = step.input,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.output.isNotBlank()) {
                Text(
                    text = step.output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> Text(
            text = step.text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SubAgentLabeledText(label: String, text: String, muted: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
