package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 用户对子智能体工具申请的决定。 */
enum class CapabilityDecision {
    /** 同意本次申请，后续申请仍需再次确认。 */
    APPROVE,

    /** 拒绝本次申请。 */
    REJECT,

    /** 同意本次，并在用户下一次提问之前默认同意后续所有申请。 */
    ALWAYS_APPROVE,
}

/**
 * 子智能体申请工具、等待用户确认时的一条待办。
 */
data class CapabilityApprovalRequest(
    val id: String,
    /** 发起申请的子智能体名 */
    val agentName: String,
    /** 申请的工具名列表 */
    val tools: List<String>,
    /** 申请理由 */
    val reason: String,
)

/**
 * 子智能体 → 用户的权限申请桥梁。
 *
 * 子智能体运行中申请工具时，会把申请放进这里并挂起等待；
 * 界面监听 [requests] 弹出确认框（同意 / 拒绝 / 下一次默认同意）后调用 [resolve] 唤醒子智能体继续执行。
 *
 * 用户选择"下一次默认同意"后，[isAutoApproveEnabled] 变为 true，本次任务后续的所有工具申请都会直接放行，
 * 直到用户发下一条消息（由 [beginUserTurn] 重置）。
 */
class CapabilityApprovalStore {
    private val _requests = MutableStateFlow<List<CapabilityApprovalRequest>>(emptyList())
    val requests: StateFlow<List<CapabilityApprovalRequest>> = _requests.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<CapabilityDecision>>()

    /** 本轮对话内是否已由用户选择"下一次默认同意"；用户发出新消息时重置。 */
    @Volatile
    private var autoApproveForCurrentTurn = false

    /** 是否处于"默认同意"状态：为 true 时子智能体的工具申请直接放行，不再弹窗。 */
    val isAutoApproveEnabled: Boolean get() = autoApproveForCurrentTurn

    /** 用户发出新消息时调用，令上一轮选择的"下一次默认同意"失效。 */
    fun beginUserTurn() {
        autoApproveForCurrentTurn = false
    }

    /** 发起一次申请并挂起，直到用户做出决定。 */
    suspend fun request(agentName: String, tools: List<String>, reason: String): CapabilityDecision {
        val id = Uuid.random().toString()
        val deferred = CompletableDeferred<CapabilityDecision>()
        pending[id] = deferred
        _requests.update { it + CapabilityApprovalRequest(id, agentName, tools, reason) }
        return try {
            deferred.await().also { decision ->
                if (decision == CapabilityDecision.ALWAYS_APPROVE) {
                    autoApproveForCurrentTurn = true
                }
            }
        } finally {
            pending.remove(id)
            _requests.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** 用户在界面上做出的决定。 */
    fun resolve(id: String, decision: CapabilityDecision) {
        pending.remove(id)?.complete(decision)
    }

    /** 生成被取消/停止时，让所有等待中的申请立即以"拒绝"结束，避免协程悬挂。 */
    fun cancelAll() {
        pending.values.forEach { it.complete(CapabilityDecision.REJECT) }
        pending.clear()
        _requests.value = emptyList()
    }
}