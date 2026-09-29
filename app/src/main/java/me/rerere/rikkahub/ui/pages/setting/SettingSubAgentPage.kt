package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import me.rerere.rikkahub.data.model.DEFAULT_SUB_AGENTS
import me.rerere.rikkahub.data.model.KNOWN_SUB_AGENT_TOOLS
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.mcpSubAgentToolOptions
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                (KNOWN_SUB_AGENT_TOOLS + mcpSubAgentToolOptions(mcpServers)).forEach { (toolName, label) ->
                                    val selected = toolName in agent.toolNames
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val newTools = if (selected) {
                                                agent.toolNames - toolName
                                            } else {
                                                agent.toolNames + toolName
                                            }
                                            update(agent.copy(toolNames = newTools))
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
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
