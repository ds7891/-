package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val fileSystemTool by lazy { buildFileSystemTool(context) }

    val apkTool by lazy { buildApkTool(context) }

    val mediaTool by lazy { buildMediaTool(context) }

    val sshTool by lazy { buildSshTool() }

    val tcpTool by lazy { buildTcpTool() }

    val webSocketTool by lazy { buildWebSocketTool() }

    val qqBotTool by lazy { buildQQBotTool(settingsStore) }

    fun getTools(
        options: List<LocalToolOption>,
        httpAllowedDomains: List<String> = emptyList(),
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.FileSystem)) {
            tools.add(fileSystemTool)
        }
        if (options.contains(LocalToolOption.Apk)) {
            tools.add(apkTool)
        }
        if (options.contains(LocalToolOption.Media)) {
            tools.add(mediaTool)
        }
        if (options.contains(LocalToolOption.Http)) {
            tools.add(buildHttpTool(httpAllowedDomains))
        }
        if (options.contains(LocalToolOption.Ssh)) {
            tools.add(sshTool)
        }
        if (options.contains(LocalToolOption.Tcp)) {
            tools.add(tcpTool)
        }
        if (options.contains(LocalToolOption.WebSocket)) {
            tools.add(webSocketTool)
        }
        if (options.contains(LocalToolOption.QQBot)) {
            tools.add(qqBotTool)
        }
        return tools
    }
}
