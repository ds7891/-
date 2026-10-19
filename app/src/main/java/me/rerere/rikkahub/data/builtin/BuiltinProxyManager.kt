package me.rerere.rikkahub.data.builtin

import android.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope

private const val TAG = "BuiltinProxyManager"

/**
 * 监听内置模型开关与端口变化，负责启动/停止本地反向代理服务。
 */
class BuiltinProxyManager(
    private val repository: BuiltinModelRepository,
    private val server: BuiltinProxyServer,
    appScope: AppScope,
) {
    init {
        appScope.launch {
            repository.state
                .map { it.enabled to it.port }
                .distinctUntilChanged()
                .collect { (enabled, port) -> apply(enabled, port) }
        }
    }

    /** 供界面上的「重启服务」调用。 */
    fun restart() {
        val state = repository.state.value
        apply(state.enabled, state.port, force = true)
    }

    private fun apply(enabled: Boolean, port: Int, force: Boolean = false) {
        if (enabled) {
            if (server.isRunning && !force) return
            if (server.isRunning) server.stop()
            runCatching { server.start(port) }
                .onSuccess { repository.setRunning(true) }
                .onFailure {
                    Log.e(TAG, "启动内置模型代理失败", it)
                    repository.setRunning(false, it.message ?: "端口被占用或无法启动")
                }
        } else if (server.isRunning) {
            server.stop()
            repository.setRunning(false)
        }
    }
}
