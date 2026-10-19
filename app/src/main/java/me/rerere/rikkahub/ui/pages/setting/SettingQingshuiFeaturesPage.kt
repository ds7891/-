package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.QingshuiFeatures
import me.rerere.rikkahub.data.datastore.QingshuiToolProtocol
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingQingshuiFeaturesPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var features by remember(settings) { mutableStateOf(settings.qingshuiFeatures) }

    fun update(newFeatures: QingshuiFeatures) {
        features = newFeatures
        vm.updateSettings(settings.copy(qingshuiFeatures = newFeatures))
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
                Text(
                    text = stringResource(R.string.qingshui_features_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun QingshuiToolProtocol.label(): String = when (this) {
    QingshuiToolProtocol.AUTO -> stringResource(R.string.qingshui_features_tool_protocol_auto)
    QingshuiToolProtocol.NATIVE -> stringResource(R.string.qingshui_features_tool_protocol_native)
    QingshuiToolProtocol.LOCAL_XML -> stringResource(R.string.qingshui_features_tool_protocol_local_xml)
}