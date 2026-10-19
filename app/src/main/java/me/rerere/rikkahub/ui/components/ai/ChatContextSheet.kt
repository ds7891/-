package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import java.util.Locale
import kotlin.math.roundToInt

/** 参考上下文窗口的默认值，用户可在面板内自行更换。 */
private const val DEFAULT_REFERENCE_CONTEXT_WINDOW = 128_000

/** 自定义参考窗口的上下限：1K – 3M。 */
private const val MIN_REFERENCE_WINDOW = 1_000
private const val MAX_REFERENCE_WINDOW = 3_000_000

/** 参考窗口的可选档位（滑动时按档位吸附，同时支持直接输入 K 值）。 */
private val ReferenceWindowLadder = listOf(
    1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 48_000, 64_000, 96_000,
    128_000, 192_000, 256_000, 384_000, 512_000, 768_000,
    1_000_000, 1_500_000, 2_000_000, 3_000_000,
)

/** 图片 / 音视频 / 文档类附件按每个约 800 token 的粗略值计入。 */
private const val TOKENS_PER_ATTACHMENT = 800

/** 输入给「上下文消耗」面板的原始数据。 */
@Immutable
data class ChatContextInput(
    val messages: List<UIMessage> = emptyList(),
    val systemPrompt: String = "",
    val customSystemPrompt: String? = null,
    val modelName: String = "",
    val draftText: String = "",
    val contextMessageLimit: Int = 0,
    /** 当前会话自定义的参考窗口（token），null 时使用 [DEFAULT_REFERENCE_CONTEXT_WINDOW]。 */
    val referenceWindow: Int? = null,
    /** 上游返回的真实 prompt token 数（最近一次请求），有值时与估算值一起展示。 */
    val upstreamTokens: Int? = null,
)

/** 上下文分布中的一类。 */
@Immutable
private data class ContextSlice(
    val label: String,
    val tokens: Int,
    val color: Color,
)

/** 固定配色，保证每次打开面板颜色稳定。 */
private val SliceColors = listOf(
    Color(0xFF6C8CFF),
    Color(0xFF35C2A0),
    Color(0xFFF5A623),
    Color(0xFFB56CFF),
    Color(0xFFEF5DA8),
    Color(0xFF00B8D9),
    Color(0xFF8D9BA8),
)

/**
 * 粗略的 token 估算：CJK 汉字约 1 token/字，其它字符约 4 字符/token。
 * 仅用于展示分布，绝对值与上游分词存在偏差。
 */
internal fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    var cjk = 0
    var other = 0
    text.forEach { ch ->
        val code = ch.code
        val isCjk = code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
        if (isCjk) cjk++ else other++
    }
    return (cjk + other / 4.0).roundToInt().coerceAtLeast(1)
}

private fun formatTokens(tokens: Int): String =
    if (tokens >= 1000) String.format(Locale.US, "%.1fK", tokens / 1000.0) else tokens.toString()

/** 工具类消息取可计量的文本（工具名 + 入参 + 出参）。 */
private fun UIMessagePart.toolText(): String = when (this) {
    is UIMessagePart.Tool -> buildString {
        append(toolName)
        if (input.isNotBlank()) {
            append('\n')
            append(input)
        }
    }

    is UIMessagePart.ServerTool -> buildString {
        append(toolName)
        input?.let { append('\n').append(it.toString()) }
        output?.let { append('\n').append(it.toString()) }
    }

    else -> ""
}

/** 把当前会话拆成若干可计量的上下文分段。 */
private fun buildSlices(input: ChatContextInput): List<ContextSlice> {
    val effectiveSystemPrompt =
        if (!input.customSystemPrompt.isNullOrBlank()) input.customSystemPrompt else input.systemPrompt

    var userTokens = 0
    var assistantTokens = 0
    var reasoningTokens = 0
    var toolTokens = 0
    var attachmentTokens = 0

    input.messages.forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> when (message.role) {
                    MessageRole.USER -> userTokens += estimateTokens(part.text)
                    MessageRole.ASSISTANT -> assistantTokens += estimateTokens(part.text)
                    else -> userTokens += estimateTokens(part.text)
                }

                is UIMessagePart.Reasoning -> reasoningTokens += estimateTokens(part.reasoning)
                is UIMessagePart.Tool, is UIMessagePart.ServerTool ->
                    toolTokens += estimateTokens(part.toolText())

                is UIMessagePart.Image, is UIMessagePart.Video,
                is UIMessagePart.Audio, is UIMessagePart.Document,
                -> attachmentTokens += TOKENS_PER_ATTACHMENT

                else -> {}
            }
        }
    }

    val draftTokens = estimateTokens(input.draftText)

    return listOf(
        ContextSlice("系统提示词", estimateTokens(effectiveSystemPrompt.orEmpty()), SliceColors[0]),
        ContextSlice("用户输入", userTokens, SliceColors[1]),
        ContextSlice("AI 回复", assistantTokens, SliceColors[2]),
        ContextSlice("思考过程", reasoningTokens, SliceColors[3]),
        ContextSlice("工具调用", toolTokens, SliceColors[4]),
        ContextSlice("附件", attachmentTokens, SliceColors[5]),
        ContextSlice("待发送", draftTokens, SliceColors[6]),
    )
}

/**
 * 聊天输入栏 mcp 按钮右侧的「上下文」按钮，点击查看当前对话的上下文消耗分布。
 */
@Composable
fun ChatContextButton(
    input: ChatContextInput,
    modifier: Modifier = Modifier,
    onReferenceWindowChange: (Int) -> Unit = {},
) {
    var showSheet by remember { mutableStateOf(false) }
    val referenceWindow = input.referenceWindow ?: DEFAULT_REFERENCE_CONTEXT_WINDOW
    val totalTokens = remember(input) { buildSlices(input).sumOf { it.tokens } }

    ToggleSurface(
        checked = totalTokens > 0,
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "上下文", style = MaterialTheme.typography.labelLarge)
            if (totalTokens > 0) {
                Text(
                    text = formatTokens(totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showSheet) {
        ChatContextSheet(
            input = input,
            referenceWindow = referenceWindow,
            onReferenceWindowChange = onReferenceWindowChange,
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun ChatContextSheet(
    input: ChatContextInput,
    referenceWindow: Int,
    onReferenceWindowChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val slices = remember(input) { buildSlices(input).filter { it.tokens > 0 } }
    val totalTokens = slices.sumOf { it.tokens }
    val usageRatio = totalTokens.toFloat() / referenceWindow
    val windowUsage = usageRatio.coerceIn(0f, 1f)
    var showReferenceDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "上下文消耗", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showReferenceDialog = true }) {
                    Text("更换参考")
                }
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                }
            }

            // 总量 + 参考窗口占比
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = formatTokens(totalTokens),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "占参考窗口 %s 的 %.1f%%",
                        formatTokens(referenceWindow),
                        usageRatio * 100,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (input.modelName.isNotBlank()) {
                    Text(
                        text = "模型：${input.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (input.contextMessageLimit > 0) {
                    Text(
                        text = "上下文消息上限：${input.contextMessageLimit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 窗口占用进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(windowUsage)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            if (slices.isEmpty()) {
                Text(
                    text = "当前对话还没有可统计的内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(text = "分布", style = MaterialTheme.typography.titleSmall)

                // 堆叠分布条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp)),
                ) {
                    slices.forEach { slice ->
                        Box(
                            modifier = Modifier
                                .weight(slice.tokens.toFloat())
                                .fillMaxHeight()
                                .background(slice.color),
                        )
                    }
                }

                // 图例：颜色 + 名称 + token 数 + 占比
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { slice ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(slice.color),
                            )
                            Text(
                                text = slice.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s · %.1f%%",
                                    formatTokens(slice.tokens),
                                    slice.tokens.toFloat() / totalTokens * 100,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            input.upstreamTokens?.let { upstream ->
                Text(
                    text = if (upstream > 0) {
                        "上游实测 prompt：${formatTokens(upstream)}"
                    } else {
                        "本机估算（上游未返回用量）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "token 为本机粗略估算，与上游分词存在偏差，仅供参考。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showReferenceDialog) {
        ReferenceWindowDialog(
            current = referenceWindow,
            onConfirm = {
                onReferenceWindowChange(it)
                showReferenceDialog = false
            },
            onDismiss = { showReferenceDialog = false },
        )
    }
}

/** 更换参考窗口：滑动吸附到常用档位，也可直接输入 K 值（1K – 3M）。 */
@Composable
private fun ReferenceWindowDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ladder = ReferenceWindowLadder
    val initialIndex = remember(current) {
        ladder.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: (ladder.size - 1)
    }
    var index by remember(current) { mutableIntStateOf(initialIndex) }
    var customK by remember(current) { mutableStateOf((current / 1000).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换参考窗口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = formatTokens(ladder[index]),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = index.toFloat(),
                    onValueChange = {
                        index = it.roundToInt().coerceIn(0, ladder.size - 1)
                        customK = (ladder[index] / 1000).toString()
                    },
                    valueRange = 0f..(ladder.size - 1).toFloat(),
                    steps = ladder.size - 2,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "1K",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "3M",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = customK,
                    onValueChange = { raw ->
                        customK = raw.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("自定义（K）") },
                    suffix = { Text("K") },
                    supportingText = { Text("范围 1 – 3000 K") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val custom = (customK.toIntOrNull() ?: 0) * 1000
                    val value = if (custom in MIN_REFERENCE_WINDOW..MAX_REFERENCE_WINDOW) {
                        custom
                    } else {
                        ladder[index]
                    }
                    onConfirm(value.coerceIn(MIN_REFERENCE_WINDOW, MAX_REFERENCE_WINDOW))
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
