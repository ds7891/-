package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.SubAgentTrace
import me.rerere.rikkahub.data.model.SubAgentTraceTurn
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 标记当前协程正在执行的工具调用。
 *
 * [GenerationLoop] 在执行工具前把它加进协程上下文，子智能体的工具据此就知道
 * 自己该把实时过程写到 [SubAgentLiveStore] 的哪个键下。
 */
class ToolCallContext(val toolCallId: String) : AbstractCoroutineContextElement(ToolCallContext) {
    companion object Key : CoroutineContext.Key<ToolCallContext>
}

/**
 * 子智能体运行过程的实时状态：以工具调用 id 为键保存进行中的 [SubAgentTrace]。
 *
 * 生成过程中 [SubAgentRunner] 会不断把最新的推理 / 工具调用 / 输出写进来，界面据此逐字刷新；
 * 工具执行结束后，完整记录仍会写入消息的 metadata 持久化，因此这里只保留最近若干条即可。
 */
class SubAgentLiveStore {
    private val _traces = MutableStateFlow<Map<String, SubAgentTrace>>(emptyMap())
    val traces: StateFlow<Map<String, SubAgentTrace>> = _traces.asStateFlow()

    /** 设置某次运行的标题（分派）或主题（探讨）。 */
    fun setHeader(key: String, title: String? = null, topic: String? = null) {
        mutate(key) { trace ->
            trace.copy(
                title = title ?: trace.title,
                topic = topic ?: trace.topic,
            )
        }
    }

    /** 覆盖某次运行中第 [index] 个子智能体发言。 */
    fun upsertTurn(key: String, index: Int, turn: SubAgentTraceTurn) {
        mutate(key) { trace ->
            val turns = trace.turns.toMutableList()
            while (turns.size <= index) turns.add(SubAgentTraceTurn())
            turns[index] = turn
            trace.copy(turns = turns)
        }
    }

    private fun mutate(key: String, transform: (SubAgentTrace) -> SubAgentTrace) {
        _traces.update { current ->
            // LinkedHashMap 重新插入以维持最近使用顺序，从而按 LRU 淘汰旧条目
            val next = LinkedHashMap(current)
            next.remove(key)
            next[key] = transform(current[key] ?: SubAgentTrace())
            while (next.size > MAX_LIVE_TRACES) {
                val eldest = next.keys.firstOrNull() ?: break
                next.remove(eldest)
            }
            next
        }
    }

    private companion object {
        const val MAX_LIVE_TRACES = 8
    }
}
