package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 子智能体申请高权限工具、等待用户确认时的一条待办。
 */
data class CapabilityApprovalRequest(
    val id: String,
    /** 发起申请的子智能体名 */
    val agentName: String,
    /** 申请的（高权限）工具名列表 */
    val tools: List<String>,
    /** 申请理由 */
    val reason: String,
)

/**
 * 子智能体 → 用户的权限申请桥梁。
 *
 * 子智能体运行中申请"高权限工具"时，会把申请放进这里并挂起等待；
 * 界面监听 [requests] 弹出确认框，用户允许/拒绝后调用 [resolve] 唤醒子智能体继续执行。
 */
class CapabilityApprovalStore {
    private val _requests = MutableStateFlow<List<CapabilityApprovalRequest>>(emptyList())
    val requests: StateFlow<List<CapabilityApprovalRequest>> = _requests.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** 发起一次申请并挂起，直到用户允许（true）或拒绝（false）。 */
    suspend fun request(agentName: String, tools: List<String>, reason: String): Boolean {
        val id = Uuid.random().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        _requests.update { it + CapabilityApprovalRequest(id, agentName, tools, reason) }
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
            _requests.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** 用户在界面上做出的决定。 */
    fun resolve(id: String, approved: Boolean) {
        pending.remove(id)?.complete(approved)
    }

    /** 生成被取消/停止时，让所有等待中的申请立即以"拒绝"结束，避免协程悬挂。 */
    fun cancelAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        _requests.value = emptyList()
    }
}
