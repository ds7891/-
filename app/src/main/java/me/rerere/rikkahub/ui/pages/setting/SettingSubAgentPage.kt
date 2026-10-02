package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.model.DEFAULT_SUB_AGENTS
import me.rerere.rikkahub.data.model.KNOWN_SUB_AGENT_TOOLS
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSubAgentPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subAgents = settings.subAgents
    val missingPresets = DEFAULT_SUB_AGENTS.filter { def -> subAgents.none { it.id == def.id } }
    var showRestoreDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val creationState = useEditState<SubAgent> { agent ->
        vm.updateSettings(settings.copy(subAgents = subAgents + agent))
    }
    val editState = useEditState<SubAgent> { updated ->
        vm.updateSettings(
            settings.copy(
                subAgents = subAgents.map { if (it.id == updated.id) updated else it }
            )
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sub_agent_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { creationState.open(SubAgent()) }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.setting_sub_agent_page_add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("subAgentMasterSwitch") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_sub_agent_page_title)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.AiBrain01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_sub_agent_page_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_sub_agent_page_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableSubAgents,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(enableSubAgents = enabled))
                                }
                            )
                        }
                    )
                }
            }

            items(subAgents, key = { it.id }) { agent ->
                SubAgentItem(
                    agent = agent,
                    onEdit = { editState.open(agent) },
                    onToggle = { enabled ->
                        vm.updateSettings(
                            settings.copy(
                                subAgents = subAgents.map {
                                    if (it.id == agent.id) it.copy(enabled = enabled) else it
                                }
                            )
                        )
                    },
                    onDelete = {
                        vm.updateSettings(
                            settings.copy(subAgents = subAgents.filterNot { it.id == agent.id })
                        )
                    },
                    modifier = Modifier.animateItem()
                )
            }

            if (subAgents.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.setting_sub_agent_page_empty))
                        Text(
                            text = stringResource(R.string.setting_sub_agent_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (missingPresets.isNotEmpty()) {
                item("restorePresets") {
                    Card(
                        onClick = { showRestoreDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        colors = CardDefaults.cardColors(
                            containerColor = CustomColors.listItemColors.containerColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.AiBrain01, null)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_sub_agent_page_restore_presets),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.setting_sub_agent_page_restore_presets_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SubAgentEditModal(creationState, settings.providers, settings.mcpServers)
    SubAgentEditModal(editState, settings.providers, settings.mcpServers)

    RikkaConfirmDialog(
        show = showRestoreDialog,
        title = stringResource(R.string.setting_sub_agent_page_restore_presets),
        confirmText = stringResource(R.string.setting_mcp_page_save),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRestoreDialog = false
            vm.updateSettings(settings.copy(subAgents = subAgents + missingPresets))
        },
        onDismiss = { showRestoreDialog = false },
    ) {
        Text(stringResource(R.string.setting_sub_agent_page_restore_presets_desc))
    }
}

@Composable
private fun SubAgentItem(
    agent: SubAgent,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onEdit,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.AiBrain01, null)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = agent.name.ifBlank { stringResource(R.string.setting_sub_agent_page_unnamed) },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (agent.builtin) {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.setting_sub_agent_page_builtin))
                        }
                    }
                    if (agent.readOnly) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.setting_sub_agent_page_read_only_tag))
                        }
                    }
                }
                if (agent.description.isNotBlank()) {
                    Text(
                        text = agent.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (agent.toolNames.isEmpty()) {
                        stringResource(R.string.setting_sub_agent_page_all_tools)
                    } else {
                        stringResource(R.string.setting_sub_agent_page_tool_count, agent.toolNames.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = agent.enabled,
                onCheckedChange = onToggle,
                size = SwitchSize.Small,
            )

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(
            stringResource(
                R.string.common_delete_confirm_message,
                agent.name.ifBlank { stringResource(R.string.setting_sub_agent_page_unnamed) }
            )
        )
    }
}

@Composable
private fun SubAgentEditModal(
    state: EditState<SubAgent>,
    providers: List<ProviderSetting>,
    mcpServers: List<McpServerConfig>,
) {
    state.EditStateContent { agent, update ->
        ModalBottomSheet(
            onDismissRequest = { state.dismiss() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (agent.builtin) {
                            stringResource(R.string.setting_sub_agent_page_edit_builtin)
                        } else {
                            stringResource(R.string.setting_sub_agent_page_edit_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )

                    OutlinedTextField(
                        value = agent.name,
                        onValueChange = { update(agent.copy(name = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agent_page_name)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agent_page_name_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    HorizontalDivider()

                    OutlinedTextField(
                        value = agent.description,
                        onValueChange = { update(agent.copy(description = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agent_page_description)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agent_page_description_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider()

                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agent_page_model)) },
                        description = { Text(stringResource(R.string.setting_sub_agent_page_model_desc)) },
                    ) {
                        SubAgentModelSection(
                            modelId = agent.modelId,
                            providers = providers,
                            onSelect = { update(agent.copy(modelId = it)) },
                        )
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = agent.systemPrompt,
                        onValueChange = { update(agent.copy(systemPrompt = it)) },
                        label = { Text(stringResource(R.string.setting_sub_agent_page_system_prompt)) },
                        supportingText = { Text(stringResource(R.string.setting_sub_agent_page_system_prompt_desc)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                    )

                    HorizontalDivider()

                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agent_page_enable)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.setting_sub_agent_page_enable))
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = agent.enabled,
                                onCheckedChange = { update(agent.copy(enabled = it)) },
                            )
                        }
                    }

                    HorizontalDivider()

                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agent_page_read_only)) },
                        description = { Text(stringResource(R.string.setting_sub_agent_page_read_only_desc)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.setting_sub_agent_page_read_only))
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = agent.readOnly,
                                onCheckedChange = { update(agent.copy(readOnly = it)) },
                            )
                        }
                    }

                    HorizontalDivider()

                    FormItem(
                        label = { Text(stringResource(R.string.setting_sub_agent_page_tools)) },
                        description = { Text(stringResource(R.string.setting_sub_agent_page_tools_desc)) },
                    ) {
                        val mcpEnabledServers = remember(mcpServers) {
                            mcpServers.filter { it.commonOptions.enable }
                        }
                        val onToggleTool: (String) -> Unit = { toolName ->
                            val newTools = if (toolName in agent.toolNames) {
                                agent.toolNames - toolName
                            } else {
                                agent.toolNames + toolName
                            }
                            update(agent.copy(toolNames = newTools))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollapsibleToolGroup(
                                title = stringResource(R.string.setting_sub_agent_page_tools_builtin),
                                description = stringResource(R.string.setting_sub_agent_page_tools_builtin_desc),
                                options = KNOWN_SUB_AGENT_TOOLS,
                                selected = agent.toolNames,
                                emptyText = null,
                                onToggle = onToggleTool,
                            )
                            SubAgentMcpToolGroup(
                                title = stringResource(R.string.setting_sub_agent_page_tools_mcp),
                                description = stringResource(R.string.setting_sub_agent_page_tools_mcp_desc),
                                servers = mcpEnabledServers,
                                selected = agent.toolNames,
                                emptyText = stringResource(R.string.setting_sub_agent_page_tools_mcp_empty),
                                onChangeSelected = { update(agent.copy(toolNames = it)) },
                            )
                            Text(
                                text = stringResource(R.string.setting_sub_agent_page_tools_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = {
                            if (agent.name.isNotBlank()) {
                                state.confirm()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.setting_mcp_page_save))
                    }
                }
            }
        }
    }
}

/**
 * 「可用工具」下的折叠分组标题卡片。
 * 整张卡片可点击展开/折叠；[headerAction] 用于在展开箭头左侧插入额外按钮（如 MCP 的加号）。
 */
@Composable
private fun ToolGroupHeader(
    title: String,
    description: String,
    selectedCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectedCount > 0) {
                Tag(type = TagType.INFO) {
                    Text(stringResource(R.string.setting_sub_agent_page_tools_selected_count, selectedCount))
                }
            }
            headerAction?.invoke()
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
            )
        }
    }
}

/**
 * 「可用工具」下的折叠分组：默认折叠，点击标题展开，再点击折叠。
 * 用于把工具按「内置工具 / MCP 工具」分类，避免一次性铺开过多选项。
 */
@Composable
private fun CollapsibleToolGroup(
    title: String,
    description: String,
    options: List<Pair<String, String>>,
    selected: List<String>,
    emptyText: String?,
    onToggle: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCount = options.count { it.first in selected }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolGroupHeader(
            title = title,
            description = description,
            selectedCount = selectedCount,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )

        if (expanded) {
            if (options.isEmpty()) {
                if (emptyText != null) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (toolName, label) ->
                        FilterChip(
                            selected = toolName in selected,
                            onClick = { onToggle(toolName) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }
}

/** 「MCP 工具」卡片左滑后露出的移除按钮宽度。 */
private val McpToolRevealWidth = 72.dp

/** 子智能体白名单里 MCP 工具名的唯一标识，格式与 ChatToolFactory 注册时一致。 */
private fun mcpToolId(serverName: String, toolName: String) = "mcp__${serverName}__$toolName"

/** 该 MCP 服务下真正可用的工具（全局启用且工具本身启用）。 */
private fun McpServerConfig.enabledTools(): List<McpTool> =
    commonOptions.tools.filter { it.enable && it.name.isNotBlank() }

/**
 * 「MCP 工具」分组：展开后按 MCP 后端（而非逐个工具）堆叠成卡片列表。
 *
 * 每张卡片右侧的胶囊开关控制子智能体能否访问该后端（开=后端全部工具加入白名单，关=全部移除）；
 * 点击卡片主体可展开该后端下的具体工具做细粒度勾选；
 * 卡片从右往左拖可露出「移除」按钮（只从当前子智能体移除，不动全局 MCP 配置）。
 * 标题右侧、展开箭头左侧的加号用于把尚未加入的工具直接添加进来。
 */
@Composable
private fun SubAgentMcpToolGroup(
    title: String,
    description: String,
    servers: List<McpServerConfig>,
    selected: List<String>,
    emptyText: String,
    onChangeSelected: (List<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    // 手风琴：同一时刻只展开一个 MCP 后端，避免多后端一起铺开
    var expandedServerId by remember { mutableStateOf<Uuid?>(null) }
    val selectedSet = remember(selected) { selected.toSet() }

    val selectedCount = remember(servers, selectedSet) {
        servers.sumOf { server ->
            val serverName = server.commonOptions.name
            server.enabledTools().count { mcpToolId(serverName, it.name) in selectedSet }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolGroupHeader(
            title = title,
            description = description,
            selectedCount = selectedCount,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            headerAction = {
                IconButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = stringResource(R.string.setting_sub_agent_page_tools_mcp_add),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        if (expanded) {
            if (servers.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    servers.forEach { server ->
                        val isExpanded = expandedServerId == server.id
                        SubAgentMcpServerCard(
                            server = server,
                            selected = selectedSet,
                            onChangeSelected = onChangeSelected,
                            expanded = isExpanded,
                            onToggleExpanded = {
                                expandedServerId = if (isExpanded) null else server.id
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        SubAgentMcpAddSheet(
            servers = servers,
            selected = selectedSet,
            onChangeSelected = onChangeSelected,
            onDismiss = { showAddSheet = false },
        )
    }
}

/**
 * 单个 MCP 后端的卡片：主体点击展开工具、右侧胶囊开关整体开关、左滑露出移除按钮。
 */
@Composable
private fun SubAgentMcpServerCard(
    server: McpServerConfig,
    selected: Set<String>,
    onChangeSelected: (List<String>) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    // 工具名里的服务器名必须用原始值，才能与 ChatToolFactory 注册的工具名对上；空白时仅在界面上兜底显示
    val serverName = server.commonOptions.name
    val displayName = serverName.ifBlank { "MCP" }
    val tools = server.enabledTools()
    val toolIds = remember(server) { tools.map { mcpToolId(serverName, it.name) } }
    val selectedInServer = toolIds.count { it in selected }
    // 全部选中才视为「已全量授权」；部分选中时胶囊开关仍为开，但副标题会写清 N/M
    val fullyAuthorized = tools.isNotEmpty() && selectedInServer == tools.size

    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { McpToolRevealWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val revealed = offsetX.value < -1f

    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：左滑后出现在右侧的圆形移除按钮
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (revealed) {
                IconButton(
                    onClick = {
                        scope.launch { offsetX.animateTo(0f, tween(150)) }
                        // 移除后该后端工具清空；若正展开着这一张，顺手收起
                        if (expanded) onToggleExpanded()
                        onChangeSelected(selected.toList() - toolIds.toSet())
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_sub_agent_page_tools_mcp_remove),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 顶层：可滑动的卡片本体
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        scope.launch {
                            offsetX.animateTo(
                                if (offsetX.value <= -revealPx / 2f) -revealPx else 0f,
                                tween(180),
                            )
                        }
                    },
                ),
            shape = MaterialTheme.shapes.medium,
            color = CustomColors.listItemColors.containerColor,
            tonalElevation = 0.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = tools.isNotEmpty()) { onToggleExpanded() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (tools.isEmpty()) {
                                stringResource(R.string.setting_sub_agent_page_tools_mcp_no_tools)
                            } else {
                                stringResource(
                                    R.string.setting_sub_agent_page_tools_mcp_count,
                                    selectedInServer,
                                    tools.size,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Switch(
                        // 开关语义：子智能体能否访问该后端。全选/部分选都算开；只有清空才关
                        checked = selectedInServer > 0,
                        onCheckedChange = { checked ->
                            onChangeSelected(
                                if (checked) {
                                    (selected + toolIds).toList()
                                } else {
                                    // 关闭后该后端工具全部移除；若正好展开着这一张，顺手收起，界面更干净
                                    if (expanded) onToggleExpanded()
                                    (selected - toolIds.toSet()).toList()
                                }
                            )
                        },
                        size = SwitchSize.Small,
                        enabled = tools.isNotEmpty(),
                        trackColor = if (fullyAuthorized) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }

                if (expanded && tools.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tools.forEach { tool ->
                            val id = mcpToolId(serverName, tool.name)
                            FilterChip(
                                selected = id in selected,
                                onClick = {
                                    onChangeSelected(
                                        if (id in selected) {
                                            (selected - id).toList()
                                        } else {
                                            (selected + id).toList()
                                        }
                                    )
                                },
                                label = { Text(tool.name, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 加号面板里的一组「未添加工具」：按 MCP 后端分组。
 * [serverId] 用于拼工具名，[displayName] 仅用于展示。
 */
private data class SubAgentMcpAddGroup(
    val serverId: String,
    val displayName: String,
    val toolNames: List<String>,
)

/**
 * 加号打开的面板：只列出尚未加入该子智能体的 MCP 工具，点击即添加。
 * 已全部添加时给出提示文案。
 */
@Composable
private fun SubAgentMcpAddSheet(
    servers: List<McpServerConfig>,
    selected: Set<String>,
    onChangeSelected: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 按后端分组，只保留还有未添加工具的后端；id 用原始服务器名，展示名做空白兜底
    val groups = remember(servers, selected) {
        servers.mapNotNull { server ->
            val serverName = server.commonOptions.name
            val missing = server.enabledTools().filter { mcpToolId(serverName, it.name) !in selected }
            if (missing.isEmpty()) {
                null
            } else {
                SubAgentMcpAddGroup(
                    serverId = serverName,
                    displayName = serverName.ifBlank { "MCP" },
                    toolNames = missing.map { it.name },
                )
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_sub_agent_page_tools_mcp_add_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.setting_sub_agent_page_tools_mcp_add_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = group.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            group.toolNames.forEach { toolName ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onChangeSelected(
                                            (selected + mcpToolId(group.serverId, toolName)).toList()
                                        )
                                    },
                                    label = { Text(toolName, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = HugeIcons.Add01,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 子智能体的模型选择器：默认为「跟随主模型」，展开后按供应商分组列出已添加的对话模型。
 */
@Composable
private fun SubAgentModelSection(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Uuid?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentModel = modelId?.let { providers.findModelById(it) }
    val providerGroups = remember(providers) {
        providers.filter { it.enabled }.mapNotNull { provider ->
            val models = provider.models.filter { it.type == ModelType.CHAT }
            if (models.isEmpty()) null else provider to models
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = currentModel?.modelId ?: "auto",
                    modifier = Modifier.size(28.dp),
                    color = Color.Transparent,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = currentModel?.displayName
                            ?: stringResource(R.string.setting_sub_agent_page_model_follow),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentModel?.findProvider(providers)?.name
                            ?: stringResource(R.string.setting_sub_agent_page_model_follow_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                )
            }
        }

        if (expanded) {
            ModelOptionRow(
                selected = modelId == null,
                title = stringResource(R.string.setting_sub_agent_page_model_follow),
                subtitle = stringResource(R.string.setting_sub_agent_page_model_follow_hint),
                onClick = { onSelect(null) },
            )
            if (providerGroups.isEmpty()) {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            providerGroups.forEach { (provider, models) ->
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                models.forEach { model ->
                    ModelOptionRow(
                        selected = model.id == modelId,
                        title = model.displayName,
                        subtitle = null,
                        iconName = model.modelId,
                        onClick = { onSelect(model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    iconName: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconName != null) {
                AutoAIIcon(
                    name = iconName,
                    modifier = Modifier.size(24.dp),
                    color = Color.Transparent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
