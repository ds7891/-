package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.McpServerConfigModal
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** 快捷面板左滑后露出的删除按钮宽度。 */
private val McpRevealWidth = 56.dp

/**
 * 聊天输入栏里的 "mcp" 小按钮，点击直接打开 MCP 管理面板，
 * 可就地启停 / 添加 / 删除 MCP。
 */
@Composable
fun ChatMcpButton(
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val assistant = settings.getCurrentAssistant()
    val enabledCount = settings.mcpServers.count {
        it.commonOptions.enable && it.id in assistant.mcpServers
    }

    ToggleSurface(
        checked = enabledCount > 0,
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "mcp", style = MaterialTheme.typography.labelLarge)
            if (enabledCount > 0) {
                Text(
                    text = enabledCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showSheet) {
        ChatMcpSheet(
            settings = settings,
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun ChatMcpSheet(
    settings: Settings,
    onDismiss: () -> Unit,
) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val servers = settings.mcpServers

    val creationState = useEditState<McpServerConfig> { created ->
        scope.launch {
            settingsStore.update { current ->
                current.copy(mcpServers = current.mcpServers + created)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.34f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题 + 右上角加号
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MCP",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        creationState.open(McpServerConfig.StreamableHTTPServer())
                    }
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                        Text(
                            text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        McpSwipeItem(
                            server = server,
                            assigned = server.id in settings.getCurrentAssistant().mcpServers,
                            onToggle = { enabled ->
                                scope.launch {
                                    settingsStore.update { current ->
                                        val assistant = current.getCurrentAssistant()
                                        current.copy(
                                            mcpServers = current.mcpServers.map { config ->
                                                if (config.id == server.id) {
                                                    config.clone(
                                                        commonOptions = config.commonOptions.copy(enable = enabled)
                                                    )
                                                } else {
                                                    config
                                                }
                                            },
                                            assistants = current.assistants.map { a ->
                                                if (a.id == assistant.id) {
                                                    a.copy(
                                                        mcpServers = if (enabled) {
                                                            a.mcpServers + server.id
                                                        } else {
                                                            a.mcpServers - server.id
                                                        }
                                                    )
                                                } else {
                                                    a
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    settingsStore.update { current ->
                                        current.copy(
                                            mcpServers = current.mcpServers.filter { it.id != server.id },
                                            assistants = current.assistants.map { a ->
                                                a.copy(mcpServers = a.mcpServers - server.id)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    McpServerConfigModal(creationState)
}

/**
 * 可左滑的 MCP 条目：向左滑到底后，右侧露出一个圆形删除按钮，
 * 点击后弹出确认弹窗；条目右侧带启停拨动开关。
 */
@Composable
private fun McpSwipeItem(
    server: McpServerConfig,
    assigned: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(server).collectAsStateWithLifecycle(McpStatus.Idle)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { McpRevealWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val revealed = offsetX.value < -1f

    // 该 MCP 是否对当前助手生效：既要全局启用，也要被当前助手引用
    val active = server.commonOptions.enable && assigned

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        // 底层：滑开后出现在右侧的圆形删除按钮
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (revealed) {
                IconButton(
                    onClick = {
                        scope.launch { offsetX.animateTo(0f, tween(150)) }
                        showDeleteConfirm = true
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 顶层：可滑动的条目本体
        Surface(
            modifier = Modifier
                .fillMaxSize()
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (status) {
                        McpStatus.Idle -> Icon(HugeIcons.MessageBlocked, null, modifier = Modifier.size(20.dp))
                        McpStatus.Connecting -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        McpStatus.Connected -> Icon(HugeIcons.McpServer, null, modifier = Modifier.size(20.dp))
                        is McpStatus.Reconnecting -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        is McpStatus.Error -> Icon(HugeIcons.Alert01, null, modifier = Modifier.size(20.dp))
                        McpStatus.NeedsAuthorization -> Icon(HugeIcons.AlertCircle, null, modifier = Modifier.size(20.dp))
                        McpStatus.Authorizing -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = server.commonOptions.name.ifBlank { "MCP" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (val s = status) {
                            is McpStatus.Idle -> if (active) "已启用" else "已停止"
                            is McpStatus.Connecting -> "连接中…"
                            is McpStatus.Connected -> "已连接"
                            is McpStatus.Reconnecting -> "重连中 (${s.attempt}/${s.maxAttempts})"
                            is McpStatus.Error -> "错误：${s.message}"
                            is McpStatus.NeedsAuthorization -> "需要授权"
                            is McpStatus.Authorizing -> "授权中…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (revealed) {
                    Spacer(Modifier.width(4.dp))
                }

                Switch(
                    checked = server.commonOptions.enable,
                    onCheckedChange = onToggle,
                    size = SwitchSize.Small,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, server.commonOptions.name))
    }
}