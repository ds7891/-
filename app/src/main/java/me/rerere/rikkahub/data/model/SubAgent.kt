package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig

/**
 * 子智能体（Sub-Agent）配置。
 *
 * 主智能体可通过 `dispatch_subagents` / `discuss_subagents` 工具把任务分派给这些
 * 具备独立系统提示词与工具白名单的专职子智能体。
 */
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val enabled: Boolean = true,
    // 该子智能体使用的模型；为 null 表示跟随主智能体当前使用的模型
    val modelId: Uuid? = null,
    // 工具白名单；为空表示该子智能体可使用主智能体当前可用的全部工具
    val toolNames: List<String> = emptyList(),
    // 只读模式：在工具白名单基础上再屏蔽一切会改变状态的工具（对应 OpenMinis 的 explore/plan kind）
    val readOnly: Boolean = false,
    val builtin: Boolean = false,
)

/**
 * 只读子智能体禁止使用的工具。对应 OpenMinis 中 explore/plan 的只读约束，
 * 用于在 [SubAgent.readOnly] 为 true 时从可用工具里剔除写入类能力。
 */
val READ_ONLY_BLOCKED_TOOLS: Set<String> = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "memory_tool",
    "calendar_create",
)

/**
 * 内置子智能体，参考 OpenMinis 的子智能体设计。
 * 这些名字与工具名对应 清水 实际注册的工具名。
 *
 * 权限约定：只有「审查」类（审查员）默认 [SubAgent.readOnly] = true，只能读文件、不能改；
 * 其余（探索者/编码员/设计师/研究员）默认可写，能否写取决于各自的工具白名单。
 */
internal val DEFAULT_SUB_AGENTS = listOf(
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000001"),
        name = "探索者",
        description = "侦察定位：读文件、查历史、联网搜索，先定位再汇报",
        systemPrompt = "你是探索者，负责侦察与定位。先找到相关文件、代码或信息，再给出简明结论和位置。",
        toolNames = listOf(
            "workspace_read_file", "recent_chats", "conversation_search",
            "search_web", "scrape_web", "get_time_info",
        ),
        readOnly = false,
        builtin = true,
    ),
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000002"),
        name = "审查员",
        description = "只审查不修改：找缺陷、风险与测试缺口，反馈给编码类执行",
        systemPrompt = "你是审查员，只负责审查，不负责修改。找出缺陷、风险、边界问题和测试缺口，" +
            "给出具体、可执行的修改建议，并把结论反馈给主智能体或编码类子智能体去落实。" +
            "你自己绝不写文件、绝不改文件，只读文件。",
        toolNames = listOf(
            "workspace_read_file",
            "search_web", "scrape_web", "recent_chats",
        ),
        readOnly = true,
        builtin = true,
    ),
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000003"),
        name = "编码员",
        description = "实现改动：读写代码、执行命令并自检",
        systemPrompt = "你是编码员，负责实现改动。按任务修改代码并运行验证，" +
            "只改动任务范围内的文件，完成后用搜索或测试自检。",
        toolNames = listOf(
            "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "workspace_shell", "search_web",
        ),
        readOnly = false,
        builtin = true,
    ),
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000005"),
        name = "设计师",
        description = "界面与视觉：出稿、改样式、直接写入对应的前端/样式文件",
        systemPrompt = "你是设计师，负责界面与视觉方案。产出具体的界面结构、样式与文案，" +
            "并直接写入对应的文件（如 UI、样式、设计文档）。" +
            "关注信息层级，并同时考虑加载中、空数据、出错三种状态。",
        toolNames = listOf(
            "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "search_web", "scrape_web",
        ),
        readOnly = false,
        builtin = true,
    ),
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000004"),
        name = "研究员",
        description = "联网查资料，结论必须带出处",
        systemPrompt = "你是研究员，负责联网调研。使用搜索与网页抓取获取信息，" +
            "每条结论都要附上来源链接，不要编造出处。",
        toolNames = listOf(
            "search_web", "scrape_web", "workspace_read_file",
        ),
        readOnly = false,
        builtin = true,
    ),
)

/**
 * 界面上可供勾选的常见工具，value 为实际工具名，label 为展示名。
 * 允许出现的工具名不限于此列表，白名单为空时子智能体可用全部工具。
 */
val KNOWN_SUB_AGENT_TOOLS: List<Pair<String, String>> = listOf(
    "workspace_read_file" to "读取工作区文件",
    "workspace_write_file" to "写入工作区文件",
    "workspace_edit_file" to "编辑工作区文件",
    "workspace_shell" to "执行 Shell 命令",
    "search_web" to "网络搜索",
    "scrape_web" to "抓取网页",
    "recent_chats" to "最近对话",
    "conversation_search" to "搜索历史对话",
    "memory_tool" to "记忆管理",
    "get_time_info" to "获取时间",
    "clipboard_tool" to "剪贴板",
    "eval_javascript" to "执行 JS",
    "calendar_query" to "查询日历",
    "calendar_create" to "创建日历",
    "text_to_speech" to "语音播报",
    "get_screen_time" to "屏幕使用时间",
    "use_skill" to "使用技能",
)

/**
 * 把已启用的 MCP 服务器里的工具转成可勾选的白名单项，用于增强子智能体。
 *
 * 工具名的格式与 [me.rerere.rikkahub.data.ai.tools.ChatToolFactory] 注册 MCP 工具时保持一致，
 * 即 `mcp__{serverName}__{toolName}`，这样勾选后子智能体就能真正取到该工具。
 */
fun mcpSubAgentToolOptions(servers: List<McpServerConfig>): List<Pair<String, String>> =
    servers
        .filter { it.commonOptions.enable }
        .flatMap { server ->
            val serverName = server.commonOptions.name
            server.commonOptions.tools
                .filter { it.enable && it.name.isNotBlank() }
                .map { tool ->
                    "mcp__${serverName}__${tool.name}" to "MCP·$serverName·${tool.name}"
                }
        }
