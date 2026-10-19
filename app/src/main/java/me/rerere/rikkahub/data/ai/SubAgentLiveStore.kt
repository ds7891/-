package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.CapabilityRecord
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.SubAgentTrace
import me.rerere.rikkahub.data.model.SubAgentTraceTurn
import java.util.concurrent.ConcurrentHashMap
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

    // 正在运行的子智能体任务句柄：键为工具调用 id，值为"展示序号 -> Job"。
    // 界面据此可以只终止其中某一个子智能体，而不影响同批的其他任务。
    private val cancelHandles = ConcurrentHashMap<String, ConcurrentHashMap<Int, Job>>()

    /** 登记某个正在运行的子智能体发言，以便界面单独终止它。 */
    fun registerTask(key: String, index: Int, job: Job) {
        cancelHandles.computeIfAbsent(key) { ConcurrentHashMap() }[index] = job
    }

    /** 注销某个子智能体发言（正常结束或已被终止后调用）。 */
    fun unregisterTask(key: String, index: Int) {
        val handles = cancelHandles[key] ?: return
        handles.remove(index)
        if (handles.isEmpty()) cancelHandles.remove(key)
    }

    /** 单独终止某个子智能体发言；返回是否确实终止了一个运行中的任务。 */
    fun cancelTask(key: String, index: Int): Boolean {
        val job = cancelHandles[key]?.get(index) ?: return false
        job.cancel(CancellationException("用户终止了子智能体"))
        return true
    }

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

    /** 追加一条小组讨论消息，界面据此实时刷新小组对话。 */
    fun appendGroupMessage(key: String, message: GroupMessage) {
        mutate(key) { trace ->
            trace.copy(groupMessages = (trace.groupMessages + message).takeLast(MAX_GROUP_MESSAGES))
        }
    }

    /** 追加一条工具 / 权限申请记录。 */
    fun appendCapabilityRecord(key: String, record: CapabilityRecord) {
        mutate(key) { trace ->
            trace.copy(capabilityRecords = (trace.capabilityRecords + record).takeLast(MAX_GROUP_MESSAGES))
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
                cancelHandles.remove(eldest)
            }
            next
        }
    }

    private companion object {
        const val MAX_LIVE_TRACES = 16
        const val MAX_GROUP_MESSAGES = 500
    }
}
