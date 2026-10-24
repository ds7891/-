package me.rerere.rikkahub.data.plugins

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.jsonObject

/** 工具名只允许字母、数字、下划线，避免不同供应商的校验差异。 */
private fun sanitizeToolName(raw: String): String =
    raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

/**
 * 把已启用插件里声明的 tools 注册成 AI 可调用的工具。
 * 实际执行交给插件脚本的 `onTool(name, argsJson)`。
 */
fun buildPluginTools(pluginManager: PluginManager): List<Tool> =
    pluginManager.enabledPlugins().flatMap { plugin ->
        plugin.manifest.tools
            .filter { it.name.isNotBlank() }
            .map { declared ->
                Tool(
                    name = sanitizeToolName("plugin_${plugin.manifest.id}_${declared.name}"),
                    description = declared.description.ifBlank {
                        "Plugin tool `${declared.name}` from `${plugin.manifest.name}`"
                    },
                    parameters = {
                        declared.parameters?.let { InputSchema.Obj(properties = it) }
                    },
                    needsApproval = { true },
                    execute = { args ->
                        val argsJson = runCatching { args.jsonObject.toString() }
                            .getOrDefault("{}")
                        val output = pluginManager.runTool(
                            pluginId = plugin.manifest.id,
                            toolName = declared.name,
                            argsJson = argsJson,
                        ) ?: "{\"error\":\"插件工具没有返回结果\"}"
                        listOf(UIMessagePart.Text(output))
                    },
                )
            }
    }

/** 供插件页面 / 快捷动作使用：把插件返回值包装成可展示文本。 */
fun JsonObject?.orEmptyJson(): JsonObject = this ?: JsonObject(emptyMap())
