package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 基于当前真实设置做增量修改。
     *
     * 别用 updateSettings(settings.copy(...)) 传页面快照：dataStore 尚未加载完时，
     * 页面拿到的 settings 是 SettingsStore 的占位值（init=true），
     * 而 SettingsStore.update 对 init=true 是直接 return 的 —— 表现就是
     * 「界面上改了、也显示着，但根本没存进去」，且没有任何提示。
     * 这里走 update(fn)，fn 收到的一定是 settingsFlow.value，不会踩到占位值。
     */
    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }
}
