package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
 * 这些名字与工具名对应 RikkaHub 实际注册的工具名。
 */
internal val DEFAULT_SUB_AGENTS = listOf(
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000001"),
        name = "探索者",
        description = "只读侦察：读文件、查历史、联网搜索，先定位再汇报",
        systemPrompt = "你是探索者，负责只读侦察。先定位相关文件、代码或信息，再给出简明结论。" +
            "不要修改任何文件，不要执行会改变状态的命令。",
        toolNames = listOf(
            "workspace_read_file", "recent_chats", "conversation_search",
            "search_web", "scrape_web", "get_time_info",
        ),
        readOnly = true,
        builtin = true,
    ),
    SubAgent(
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000002"),
        name = "审查员",
        description = "代码审查：找缺陷、风险与测试缺口，给出可执行建议",
        systemPrompt = "你是审查员，负责代码审查。找出缺陷、风险、边界问题和测试缺口，" +
            "给出具体、可执行的修改建议，但不要自己动手改代码。",
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
        description = "实现改动：读写文件、执行命令并自检",
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
        id = Uuid.parse("0e0a4f7c-1f0b-4c2a-9a5e-000000000004"),
        name = "研究员",
        description = "联网查资料，结论必须带出处",
        systemPrompt = "你是研究员，负责联网调研。使用搜索与网页抓取获取信息，" +
            "每条结论都要附上来源链接，不要编造出处。",
        toolNames = listOf(
            "search_web", "scrape_web", "workspace_read_file",
        ),
        readOnly = true,
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
