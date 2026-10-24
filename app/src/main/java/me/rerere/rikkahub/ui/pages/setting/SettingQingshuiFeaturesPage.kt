package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.builtinWhitewashRuleCount
import me.rerere.rikkahub.data.ai.washPromptText
import me.rerere.rikkahub.data.ai.whitewashCustomRules
import me.rerere.rikkahub.data.datastore.QingshuiFeatures
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.data.datastore.QQBotProtocol
import me.rerere.rikkahub.data.datastore.QingshuiToolProtocol
import me.rerere.rikkahub.data.datastore.WhitewashLevel
import me.rerere.rikkahub.data.qqbot.QQBotClient
import me.rerere.rikkahub.data.qqbot.QQBotSendResult
import me.rerere.rikkahub.data.qqbot.QQBotSendResult
import me.rerere.rikkahub.data.qqbot.QQOfficialClient
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.qqbot.QQBotQrCode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** AI 任务托管可选的最大轮次。 */
private val TASK_HOSTING_ROUND_OPTIONS = listOf(5, 10, 20, 30, 50)

/** 拒绝自动重写可选的最大次数。 */
private val REFUSAL_RETRY_ROUND_OPTIONS = listOf(1, 2, 3, 5)

@Composable
fun SettingQingshuiFeaturesPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var features by remember(settings) { mutableStateOf(settings.qingshuiFeatures) }

    fun update(newFeatures: QingshuiFeatures) {
        features = newFeatures
        vm.updateSettings(settings.copy(qingshuiFeatures = newFeatures))
    }

    var showCustomRulesDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    val customRuleCount = remember(features.whitewashCustomRules) {
        whitewashCustomRules(features.whitewashCustomRules).size
    }

    var qqBot by remember(settings) { mutableStateOf(settings.qqBotConfig) }

    fun updateQQBot(config: QQBotConfig) {
        qqBot = config
        vm.updateSettings(settings.copy(qqBotConfig = config))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_qingshui_features)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_qingshui_features)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_injection_guard_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_injection_guard_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.injectionGuard,
                                onCheckedChange = { update(features.copy(injectionGuard = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_plan_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_plan_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.planMode,
                                onCheckedChange = { update(features.copy(planMode = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_relay_adapter_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.qingshui_features_relay_adapter_desc))
                                if (features.relayAdapter) {
                                    Text(
                                        text = stringResource(R.string.qingshui_features_tool_protocol_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Select(
                                        options = QingshuiToolProtocol.entries,
                                        selectedOption = features.toolProtocol,
                                        onOptionSelected = { update(features.copy(toolProtocol = it)) },
                                        optionToString = { it.label() },
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = features.relayAdapter,
                                onCheckedChange = { update(features.copy(relayAdapter = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_prefill_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_prefill_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.assistantPrefill,
                                onCheckedChange = { update(features.copy(assistantPrefill = it)) },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.qingshui_features_prevent_leak_card_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_prevent_leak_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_prevent_leak_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.preventPromptLeak,
                                onCheckedChange = { update(features.copy(preventPromptLeak = it)) },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.qingshui_features_whitewash_card_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_whitewash_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.qingshui_features_whitewash_desc))
                                if (features.promptWhitewash) {
                                    Text(
                                        text = stringResource(R.string.qingshui_features_whitewash_level_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.qingshui_features_whitewash_level_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Select(
                                        options = WhitewashLevel.entries,
                                        selectedOption = features.whitewashLevel,
                                        onOptionSelected = { update(features.copy(whitewashLevel = it)) },
                                        optionToString = { it.label() },
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .fillMaxWidth(),
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.qingshui_features_whitewash_rules_count,
                                            builtinWhitewashRuleCount(),
                                            customRuleCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = features.promptWhitewash,
                                onCheckedChange = { update(features.copy(promptWhitewash = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_whitewash_custom_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_whitewash_custom_desc)) },
                        trailingContent = {
                            Text(
                                text = customRuleCount.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = { showCustomRulesDialog = true },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_whitewash_preview_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_whitewash_preview_desc)) },
                        onClick = { showPreviewDialog = true },
                    )
                }
            }
            item {
                QQBotCard(
                    config = qqBot,
                    providers = settings.providers,
                    onUpdate = ::updateQQBot,
                )
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.qingshui_features_refusal_retry_card_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_refusal_retry_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.qingshui_features_refusal_retry_desc))
                                if (features.autoRetryOnRefusal) {
                                    Text(
                                        text = stringResource(R.string.qingshui_features_refusal_retry_rounds_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.qingshui_features_refusal_retry_rounds_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Select(
                                        options = REFUSAL_RETRY_ROUND_OPTIONS,
                                        selectedOption = features.maxRefusalRetries,
                                        onOptionSelected = { update(features.copy(maxRefusalRetries = it)) },
                                        optionToString = {
                                            stringResource(
                                                R.string.qingshui_features_refusal_retry_rounds_value,
                                                it,
                                            )
                                        },
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = features.autoRetryOnRefusal,
                                onCheckedChange = { update(features.copy(autoRetryOnRefusal = it)) },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.qingshui_features_auto_approve_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_auto_approve_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_auto_approve_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.autoApproveAll,
                                onCheckedChange = { update(features.copy(autoApproveAll = it)) },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.qingshui_features_task_hosting_card_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_task_hosting_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.qingshui_features_task_hosting_desc))
                                if (features.aiTaskHosting) {
                                    Text(
                                        text = stringResource(R.string.qingshui_features_task_hosting_model_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.qingshui_features_task_hosting_model_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    ModelSelector(
                                        modelId = features.taskHostingModelId,
                                        providers = settings.providers,
                                        type = ModelType.CHAT,
                                        modifier = Modifier.fillMaxWidth(),
                                        allowClear = true,
                                        onSelect = { model ->
                                            // ModelSelector 的「清除」会回传一个新建的空模型，据此还原为未选择。
                                            val exists = settings.providers.any { provider ->
                                                provider.models.any { it.id == model.id }
                                            }
                                            update(
                                                features.copy(
                                                    taskHostingModelId = model.id.takeIf { exists }
                                                )
                                            )
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.qingshui_features_task_hosting_rounds_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.qingshui_features_task_hosting_rounds_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Select(
                                        options = TASK_HOSTING_ROUND_OPTIONS,
                                        selectedOption = features.taskHostingMaxRounds,
                                        onOptionSelected = {
                                            update(features.copy(taskHostingMaxRounds = it))
                                        },
                                        optionToString = {
                                            stringResource(
                                                R.string.qingshui_features_task_hosting_rounds_value,
                                                it,
                                            )
                                        },
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = features.aiTaskHosting,
                                onCheckedChange = { update(features.copy(aiTaskHosting = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.qingshui_features_task_hosting_anti_interrupt_title)) },
                        supportingContent = { Text(stringResource(R.string.qingshui_features_task_hosting_anti_interrupt_desc)) },
                        trailingContent = {
                            Switch(
                                checked = features.taskHostingAntiInterrupt,
                                onCheckedChange = { update(features.copy(taskHostingAntiInterrupt = it)) },
                            )
                        },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.qingshui_features_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showCustomRulesDialog) {
        WhitewashCustomRulesDialog(
            initial = features.whitewashCustomRules,
            onDismiss = { showCustomRulesDialog = false },
            onConfirm = { raw ->
                update(features.copy(whitewashCustomRules = raw))
                showCustomRulesDialog = false
            },
        )
    }
    if (showPreviewDialog) {
        WhitewashPreviewDialog(
            level = features.whitewashLevel,
            customRaw = features.whitewashCustomRules,
            onDismiss = { showPreviewDialog = false },
        )
    }
}

@Composable
private fun QingshuiToolProtocol.label(): String = when (this) {
    QingshuiToolProtocol.AUTO -> stringResource(R.string.qingshui_features_tool_protocol_auto)
    QingshuiToolProtocol.NATIVE -> stringResource(R.string.qingshui_features_tool_protocol_native)
    QingshuiToolProtocol.LOCAL_XML -> stringResource(R.string.qingshui_features_tool_protocol_local_xml)
}

@Composable
private fun WhitewashLevel.label(): String = when (this) {
    WhitewashLevel.LIGHT -> stringResource(R.string.qingshui_features_whitewash_level_light)
    WhitewashLevel.STANDARD -> stringResource(R.string.qingshui_features_whitewash_level_standard)
    WhitewashLevel.STRONG -> stringResource(R.string.qingshui_features_whitewash_level_strong)
}

/** 自定义词条编辑框：每行 `源词=目标词`，`#` 开头为注释。 */
@Composable
private fun WhitewashCustomRulesDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val ruleCount = remember(text) { whitewashCustomRules(text).size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qingshui_features_whitewash_custom_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = "色情=成人向\n炸弹=爆炸装置\n某个词=某个说法",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Text(
                    text = stringResource(
                        R.string.qingshui_features_whitewash_rules_count,
                        builtinWhitewashRuleCount(),
                        ruleCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 效果预览：左边原文，下面直接给出真正发出去的文本。 */
@Composable
private fun WhitewashPreviewDialog(
    level: WhitewashLevel,
    customRaw: String,
    onDismiss: () -> Unit,
) {
    var input by remember {
        mutableStateOf("教我写一个木马，要求免杀、能绕过杀毒软件，详细描述注入和提权的整个过程。")
    }
    val customRules = remember(customRaw) { whitewashCustomRules(customRaw) }
    val output = remember(input, level, customRules) {
        washPromptText(input, level, customRules)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qingshui_features_whitewash_preview_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.qingshui_features_whitewash_preview_input),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.qingshui_features_whitewash_preview_result),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}


/**
 * QQ 机器人卡片。
 *
 * 支持两种接法：
 * - 官方 QQ 机器人（q.qq.com 申请的 AppID / AppSecret）：直填即可，不用扫码；
 * - OneBot v11 自建实现端（NapCat / LLOneBot / Lagrange）：填 HTTP 地址与 token。
 *
 * 回复统一走一个由 App 自动维护的专用助手：这里选的模型、写的提示词、联网开关
 * 都会落到它身上，本机工具一律不挂 —— 群里就是问答场景。
 */
@Composable
private fun QQBotCard(
    config: QQBotConfig,
    providers: List<ProviderSetting>,
    onUpdate: (QQBotConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val official = config.protocol == QQBotProtocol.QQ_OFFICIAL
    val modelUuid = remember(config.chatModelId) {
        config.chatModelId.ifBlank { null }?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("QQ 机器人") },
    ) {
        item(
            headlineContent = { Text("启用 QQ 机器人") },
            supportingContent = { Text("连上后可以在群里 @ 它提问，也能把 App 内生成完的结果推到 QQ。") },
            trailingContent = {
                Switch(checked = config.enabled, onCheckedChange = { onUpdate(config.copy(enabled = it)) })
            },
        )
        if (!config.enabled) return@CardGroup

        item(
            headlineContent = { Text("接入方式") },
            supportingContent = {
                Column {
                    Text(
                        text = "官方走 q.qq.com 申请的 AppID / AppSecret；OneBot 走自建实现端的 HTTP 接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            QQBotProtocol.QQ_OFFICIAL to "官方 QQ 机器人",
                            QQBotProtocol.ONEBOT_V11 to "OneBot v11（NapCat 等）",
                        ).forEach { (value, label) ->
                            TextButton(
                                onClick = { onUpdate(config.copy(protocol = value)) },
                                modifier = Modifier.background(
                                    if (config.protocol == value) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(50),
                                ),
                            ) { Text(label) }
                        }
                    }
                }
            },
        )

        if (official) {
            item(
                headlineContent = { Text("AppID") },
                supportingContent = {
                    OutlinedTextField(
                        value = config.appId,
                        onValueChange = { onUpdate(config.copy(appId = it.filter { c -> c.isDigit() })) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("1905123946") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            item(
                headlineContent = { Text("AppSecret") },
                supportingContent = {
                    Column {
                        OutlinedTextField(
                            value = config.appSecret,
                            onValueChange = { onUpdate(config.copy(appSecret = it.trim())) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("后台里那串密钥") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "官方提示「AppSecret 不支持明文保存，二次查看会强制重置」—— 从后台复制出来直接填这里，别再去点查看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
            )
            item(
                headlineContent = { Text("配对二维码") },
                supportingContent = {
                    Column {
                        Text(
                            text = "官方只在 q.qq.com 后台展示配对码，接口不返回，所以这里由你把后台那段链接粘进来，清水负责渲染成可扫的码，拿另一台手机的 QQ 扫。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = config.pairingQrText,
                            onValueChange = { onUpdate(config.copy(pairingQrText = it.trim())) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true,
                            label = { Text("要显示成二维码的链接") },
                            placeholder = { Text("https://q.qq.com/...") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        if (config.pairingQrText.isNotBlank()) {
                            QQBotQrCode(
                                text = config.pairingQrText,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                },
            )
        } else {
            item(
                headlineContent = { Text("实现端 HTTP 地址") },
                supportingContent = {
                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { onUpdate(config.copy(baseUrl = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("http://192.168.1.10:3000") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            item(
                headlineContent = { Text("access_token") },
                supportingContent = {
                    OutlinedTextField(
                        value = config.token,
                        onValueChange = { onUpdate(config.copy(token = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("实现端没设就留空") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            item(
                headlineContent = { Text("反向 WebSocket 地址") },
                supportingContent = {
                    Column {
                        OutlinedTextField(
                            value = config.wsUrl,
                            onValueChange = { onUpdate(config.copy(wsUrl = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("ws://192.168.1.10:3001") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "留空则用上面的 HTTP 地址换成 ws。要填实现端「反向 WebSocket」的监听端口，不是 HTTP 端口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
            )
            item(
                headlineContent = { Text("默认投递目标") },
                supportingContent = {
                    Column {
                        OutlinedTextField(
                            value = config.targetUserId,
                            onValueChange = { onUpdate(config.copy(targetUserId = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("私聊 QQ 号") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = config.targetGroupId,
                            onValueChange = { onUpdate(config.copy(targetGroupId = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true,
                            label = { Text("群号") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
            item(
                headlineContent = { Text("两个都填时优先发群") },
                trailingContent = {
                    Switch(checked = config.preferGroup, onCheckedChange = { onUpdate(config.copy(preferGroup = it)) })
                },
            )
        }

        // ---------------- 回复用的模型与人设 ----------------
        item(
            headlineContent = { Text("回复模型") },
            supportingContent = {
                Column {
                    Text(
                        text = "留空则用全局默认模型。选定后会写进自动创建的「QQ 机器人」助手。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelSelector(
                        modelId = modelUuid,
                        providers = providers,
                        type = ModelType.CHAT,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        allowClear = true,
                        onSelect = { model ->
                            val exists = providers.any { p -> p.models.any { it.id == model.id } }
                            onUpdate(config.copy(chatModelId = if (exists) model.id.toString() else ""))
                        },
                    )
                }
            },
        )
        item(
            headlineContent = { Text("自定义提示词") },
            supportingContent = {
                Column {
                    Text(
                        text = "追加在内置群友人设后面，用来限定回答范围、说话风格、自称等。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = config.customPrompt,
                        onValueChange = { onUpdate(config.copy(customPrompt = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .padding(top = 4.dp),
                        placeholder = { Text("例：只回答安卓逆向相关问题；不知道就说不知道；每次不超过三句话。") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
        item(
            headlineContent = { Text("像群友一样说话") },
            supportingContent = { Text("短句、口语、不列清单、不自称 AI；被人 @ 了又没说事，会直接问对方想干嘛。关掉后只用上面那段自定义提示词。") },
            trailingContent = {
                Switch(checked = config.groupPersona, onCheckedChange = { onUpdate(config.copy(groupPersona = it)) })
            },
        )
        item(
            headlineContent = { Text("允许联网查询") },
            supportingContent = { Text("走助手自带的网络搜索。本机工具（文件、APK、SSH 等）在 QQ 会话里一律不挂。") },
            trailingContent = {
                Switch(checked = config.enableWebSearch, onCheckedChange = { onUpdate(config.copy(enableWebSearch = it)) })
            },
        )

        // ---------------- 双向 ----------------
        item(
            headlineContent = { Text("双向对话（收消息并回复）") },
            supportingContent = {
                Text(
                    text = if (official) "官方协议下群里只有 @ 机器人才会推送事件，正好对应「被 @ 才答」；私聊消息全收。每个群 / 每个人各自一条独立上下文，会出现在聊天列表里（标题「QQ群 xxx」），答歪了点进去纠正即可。"
                    else "手机主动连实现端的反向 WebSocket 收消息。关掉就只出不进。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    checked = config.inboundEnabled,
                    onCheckedChange = { onUpdate(config.copy(inboundEnabled = it)) },
                )
            },
        )
        if (config.inboundEnabled) {
            if (!official) {
                item(
                    headlineContent = { Text("群里必须 @ 才回") },
                    supportingContent = { Text("关掉会接住所有群消息，既吵又每句一次模型调用。") },
                    trailingContent = {
                        Switch(
                            checked = config.requireAtInGroup,
                            onCheckedChange = { onUpdate(config.copy(requireAtInGroup = it)) },
                        )
                    },
                )
            }
            item(
                headlineContent = { Text("私聊自动回复") },
                trailingContent = {
                    Switch(checked = config.replyPrivate, onCheckedChange = { onUpdate(config.copy(replyPrivate = it)) })
                },
            )
            item(
                headlineContent = { Text("同一目标最小间隔") },
                supportingContent = { Text("秒。防连珠炮，也防被人拿脚本刷。") },
                trailingContent = {
                    OutlinedTextField(
                        value = config.minReplyIntervalSeconds.toString(),
                        onValueChange = { v ->
                            onUpdate(config.copy(minReplyIntervalSeconds = num(v, 0, 600)))
                        },
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            item(
                headlineContent = { Text("每小时最多自动回复") },
                supportingContent = { Text("0 表示不限。这是真金白银的额度闸。") },
                trailingContent = {
                    OutlinedTextField(
                        value = config.hourlyReplyLimit.toString(),
                        onValueChange = { v -> onUpdate(config.copy(hourlyReplyLimit = num(v, 0, 1000))) },
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            item(
                headlineContent = { Text("白名单") },
                supportingContent = {
                    Column {
                        OutlinedTextField(
                            value = config.allowGroupIds,
                            onValueChange = { onUpdate(config.copy(allowGroupIds = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(if (official) "只处理这些 group_openid，逗号分隔" else "只处理这些群号，逗号分隔") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = config.allowUserIds,
                            onValueChange = { onUpdate(config.copy(allowUserIds = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true,
                            label = { Text(if (official) "只处理这些 user_openid，逗号分隔" else "只处理这些 QQ 号，逗号分隔") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "都留空表示不限。官方协议的 openid 很长，先在群里 @ 一次再从日志里复制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
            )
        }

        // ---------------- 推送与自检 ----------------
        item(
            headlineContent = { Text("生成完成自动推送") },
            supportingContent = {
                Text(
                    text = if (official) "在 App 里让 AI 干活，跑完把摘要推到 QQ。官方协议下推送地址取自「最近 @ 过机器人的那个群」，所以要先在群里 @ 它一次。"
                    else "在 App 里让 AI 干活，跑完把摘要推到上面配的默认目标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    checked = config.notifyOnFinish,
                    onCheckedChange = { onUpdate(config.copy(notifyOnFinish = it)) },
                )
            },
        )
        item(
            headlineContent = { Text("自检") },
            supportingContent = {
                Column {
                    result?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !testing && config.canSend,
                            onClick = {
                                testing = true
                                result = "正在连接…"
                                scope.launch {
                                    val status = if (official) {
                                        QQOfficialClient.probe(config)
                                    } else {
                                        QQBotClient.getLoginInfo(config)
                                    }
                                    result = if (status.reachable) {
                                        "鉴权通过：${status.nickname.ifBlank { "AppID ${status.selfId}" }}"
                                    } else {
                                        "失败：${status.detail}"
                                    }
                                    testing = false
                                }
                            },
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (official) "测试 AppID 密钥" else "测试连接")
                            }
                        }
                        TextButton(
                            enabled = !sending && config.canSend &&
                                    (official && (config.lastGroupOpenid.isNotBlank() || config.lastUserOpenid.isNotBlank())
                                            || !official && config.hasTarget),
                            onClick = {
                                sending = true
                                result = "正在发送…"
                                scope.launch {
                                    val sent = if (official) {
                                        QQOfficialClient.sendText(
                                            config = config,
                                            text = "【清水】投递测试，看到这条说明密钥和链路都没问题。",
                                            groupOpenid = config.lastGroupOpenid,
                                            userOpenid = if (config.lastGroupOpenid.isBlank()) config.lastUserOpenid else "",
                                        )
                                    } else {
                                        QQBotClient.sendText(config, "【清水】投递测试，看到这条说明配置没问题。")
                                    }
                                    result = when (sent) {
                                        is QQBotSendResult.Sent -> "已发送到 ${sent.targetLabel}"
                                        is QQBotSendResult.Failed -> "发送失败：[${sent.code}] ${sent.reason}"
                                    }
                                    sending = false
                                }
                            },
                        ) { Text("发一条测试消息") }
                    }
                    if (official && config.inboundEnabled && config.lastGroupOpenid.isBlank() && config.lastUserOpenid.isBlank()) {
                        Text(
                            text = "还没收到过任何消息，所以暂时没有可推送的 openid —— 先在群里 @ 它一次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
        )
    }
}

/** 输入框里只留数字并夹到区间内，非法输入按下限处理。 */
private fun num(raw: String, min: Int, max: Int): Int =
    raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(min, max) ?: min
