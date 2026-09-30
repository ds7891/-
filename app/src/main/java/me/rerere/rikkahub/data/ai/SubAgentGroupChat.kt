package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.CapabilityRecord
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.SubAgent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 一次子智能体分派共享的"小组会话"。
 *
 * 平时子智能体有疑惑只向主智能体一对一提问；当主智能体同意开启讨论后，
 * 这里会变成所有子智能体共同参与的小组对话：大家同步进度、抛出问题、互相答疑，
 * 并在整个任务期间保留，随时补充新的问题与意见，直到任务处理完毕。
 *
 * 消息与申请记录既保存在内存（用于拼提示与最终展示），也实时写入 [SubAgentLiveStore]，
 * 界面据此逐字刷新。
 */
class SubAgentGroupChat(
    private val liveKey: String?,
    private val liveStore: SubAgentLiveStore,
) {
    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()

    private val _records = MutableStateFlow<List<CapabilityRecord>>(emptyList())
    val records: StateFlow<List<CapabilityRecord>> = _records.asStateFlow()

    private val opened = AtomicBoolean(false)
    private val cursor = AtomicInteger(0)

    /** 小组讨论是否已由主智能体开启。 */
    val isOpen: Boolean get() = opened.get()

    fun open() {
        opened.set(true)
    }

    fun snapshotMessages(): List<GroupMessage> = _messages.value

    fun snapshotRecords(): List<CapabilityRecord> = _records.value

    /** 最近 [limit] 条消息，用于拼进子智能体的提示词。 */
    fun recent(limit: Int = 24): List<GroupMessage> = _messages.value.takeLast(limit)

    /** 往小组里发一条消息。 */
    fun post(sender: String, kind: String, content: String): GroupMessage {
        val message = GroupMessage(sender = sender, kind = kind, content = content.trim())
        _messages.update { it + message }
        if (liveKey != null) liveStore.appendGroupMessage(liveKey, message)
        return message
    }

    /** 记录一条工具 / 权限申请。 */
    fun addRecord(record: CapabilityRecord) {
        _records.update { it + record }
        if (liveKey != null) liveStore.appendCapabilityRecord(liveKey, record)
    }

    /** 轮询挑选下一个"空闲"的子智能体来回答问题（排除提问者）。 */
    fun pickNextAgent(agents: List<SubAgent>, excludeId: Uuid?): SubAgent? {
        val pool = agents.filter { it.id != excludeId }
        if (pool.isEmpty()) return null
        return pool[cursor.getAndIncrement().mod(pool.size)]
    }
}
