package me.rerere.rikkahub.data.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 插件清单（插件包根目录的 manifest.json）。
 *
 * 一个插件 = 一个 zip：
 * ```
 * manifest.json   —— 本清单
 * main.js         —— 入口脚本，定义 onAction / onTool / onRequest / onResponse 等钩子
 * ```
 */
@Serializable
data class PluginManifest(
    /** 唯一 ID，只允许字母、数字、下划线、短横线、点 */
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    /** 入口脚本在插件目录内的相对路径 */
    val entry: String = "main.js",
    /** 由插件新增的页面；每个页面由若干按钮组成，点按钮触发 onAction */
    val pages: List<PluginPage> = emptyList(),
    /** 注册给 AI 的工具，执行时调用 onTool */
    val tools: List<PluginTool> = emptyList(),
    /** 在聊天「+」面板里新增的快捷按钮，点击触发 onAction */
    val actions: List<PluginAction> = emptyList(),
    /** 是否允许干预网络请求 / 响应（需在插件管理里显式开启） */
    val intercept: PluginIntercept = PluginIntercept(),
)

@Serializable
data class PluginPage(
    val id: String,
    val title: String,
    val buttons: List<PluginButton> = emptyList(),
)

@Serializable
data class PluginButton(
    val label: String,
    /** 传给 onAction 的动作 ID */
    val action: String,
    val description: String = "",
)

@Serializable
data class PluginAction(
    val id: String,
    val label: String,
)

@Serializable
data class PluginTool(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
)

@Serializable
data class PluginIntercept(
    val request: Boolean = false,
    val response: Boolean = false,
)

/** 已安装插件：清单 + 是否启用 + 目录。 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val interceptEnabled: Boolean,
    val dirPath: String,
)
