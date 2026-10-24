package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.SubAgentLiveStore
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val subAgentRunner: SubAgentRunner,
    private val subAgentLiveStore: SubAgentLiveStore,
    private val pluginManager: me.rerere.rikkahub.data.plugins.PluginManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val tools = buildList {
            if (assistant.enableMemory) {
                val memoryAssistantId = if (assistant.useGlobalMemory) {
                    MemoryRepository.GLOBAL_MEMORY_ID
                } else {
                    assistant.id.toString()
                }
                addAll(
                    buildMemoryTools(
                        json = json,
                        onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                        onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                        onDelete = { id -> memoryRepository.deleteMemory(id) },
                    )
                )
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools, assistant.httpAllowedDomains))
            // 插件注册的工具（由插件脚本 onTool 实现）
            addAll(me.rerere.rikkahub.data.plugins.buildPluginTools(pluginManager))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            // MCP 服务名/工具名里可能含非法字符（空格、中文、点号等），发给模型前会统一规范化；
            // 这里不再因为服务名非法而直接中断整个生成。
            mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }

        val allTools = if (!settings.enableSubAgents) {
            tools
        } else {
            tools + createSubAgentTools(
                settings = settings,
                callerAssistant = assistant,
                callerModel = model,
                runner = subAgentRunner,
                baseTools = tools,
                liveStore = subAgentLiveStore,
            )
        }
        return allTools.normalizeToolNames()
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}

/** 模型侧函数名的长度上限（对齐主流 API 的 64 字符限制）。 */
private const val MAX_TOOL_NAME_LENGTH = 64

/**
 * 规范化并去重工具名。
 *
 * 模型/中转侧的函数名只允许 [A-Za-z0-9_-]，且不允许重复，否则部分中转站或模型会直接
 * 拒绝整个请求体（表现为「请求体错误」）。这里：
 * 1. 把非法字符统一替换为 '_'，并限制长度；
 * 2. 名称冲突时：若两个工具功能一致（描述与入参结构相同）则只保留先出现的那个；
 *    否则追加序号重命名，保证发给模型的工具名全局唯一。
 *
 * 注意：重命名只改对外函数名，不影响工具的 [Tool.execute] 闭包，工具调用派发仍然正确。
 */
private fun List<Tool>.normalizeToolNames(): List<Tool> {
    val used = HashSet<String>(size)
    val result = ArrayList<Tool>(size)
    for (tool in this) {
        val base = tool.name.toValidToolName()
        if (used.add(base)) {
            result.add(if (base == tool.name) tool else tool.copy(name = base))
            continue
        }
        val existing = result.firstOrNull { it.name == base }
        if (existing != null &&
            existing.description == tool.description &&
            existing.parameters() == tool.parameters()
        ) {
            // 功能一致的同名工具，视为重复，保留先出现的那个
            continue
        }
        var index = 2
        var candidate = base.withIndexSuffix(index)
        while (!used.add(candidate)) {
            index += 1
            candidate = base.withIndexSuffix(index)
        }
        result.add(tool.copy(name = candidate))
    }
    return result
}

private fun String.toValidToolName(): String {
    val mapped = buildString(length) {
        for (c in this@toValidToolName) {
            append(
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c
                else '_'
            )
        }
    }
    if (mapped.isEmpty()) return "tool"
    return if (mapped.length <= MAX_TOOL_NAME_LENGTH) mapped else mapped.take(MAX_TOOL_NAME_LENGTH)
}

private fun String.withIndexSuffix(index: Int): String {
    val suffix = "_$index"
    val keep = (MAX_TOOL_NAME_LENGTH - suffix.length).coerceAtLeast(1)
    val head = if (length > keep) take(keep) else this
    return head + suffix
}
