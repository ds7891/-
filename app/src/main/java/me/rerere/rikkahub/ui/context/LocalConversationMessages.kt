package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.ai.ui.UIMessage

/**
 * 当前对话的全部消息。
 *
 * 由聊天列表在渲染时提供，供消息内部的工具渲染器（如子智能体卡片）汇总
 * "本对话累计消耗"，而无需把整份消息列表层层透传下去。
 */
val LocalConversationMessages = staticCompositionLocalOf<List<UIMessage>> { emptyList() }