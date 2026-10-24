package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.plugins.InstalledPlugin
import me.rerere.rikkahub.data.plugins.PluginManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理：上传插件包、启用/停用、授权网络拦截、卸载、打开插件页面。
 */
@Composable
fun PluginSettingsPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val pluginManager: PluginManager = koinInject()

    var plugins by remember { mutableStateOf(pluginManager.list()) }
    var showFormat by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }

    fun refresh() {
        plugins = pluginManager.list()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result: Result<me.rerere.rikkahub.data.plugins.PluginManifest> = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    input.use { pluginManager.install(it).getOrThrow() }
                }
            }
            result.fold(
                onSuccess = { manifest ->
                    refresh()
                    toaster.show("已安装插件：${manifest.name}", type = ToastType.Success)
                },
                onFailure = { e ->
                    toaster.show("安装失败：${e.message ?: e::class.simpleName}", type = ToastType.Error)
                },
            )
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("插件") },
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
                    title = { Text("管理") },
                ) {
                    item(
                        headlineContent = { Text("上传插件") },
                        supportingContent = {
                            Text("选择插件 zip 包安装；格式说明见下方")
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }) {
                                Text("选择文件")
                            }
                        },
                    )
                    item(
                        onClick = { showFormat = true },
                        headlineContent = { Text("插件格式说明") },
                        supportingContent = { Text("manifest.json + main.js，可新增页面、按钮、AI 工具，并拦截请求/响应") },
                    )
                }
            }

            if (plugins.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "还没有安装插件\n点「上传插件」选择一个 zip 包",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            plugins.forEach { plugin ->
                item(key = plugin.manifest.id) {
                    PluginCard(
                        plugin = plugin,
                        onToggleEnabled = { enabled ->
                            pluginManager.setEnabled(plugin.manifest.id, enabled)
                            refresh()
                        },
                        onToggleIntercept = { enabled ->
                            pluginManager.setInterceptEnabled(plugin.manifest.id, enabled)
                            refresh()
                        },
                        onOpenPage = { pageId ->
                            navController.navigate(Screen.PluginPage(plugin.manifest.id, pageId))
                        },
                        onDelete = { pendingDelete = plugin },
                    )
                }
            }
        }
    }

    if (showFormat) {
        AlertDialog(
            onDismissRequest = { showFormat = false },
            title = { Text("插件格式") },
            text = {
                Text(
                    """
                    插件是一个 zip 包，根目录放两个文件：

                    manifest.json
                    {
                      "id": "my-plugin",
                      "name": "示例插件",
                      "version": "1.0.0",
                      "entry": "main.js",
                      "pages": [
                        { "id": "home", "title": "示例页面",
                          "buttons": [
                            { "label": "打个招呼", "action": "hello" }
                          ] }
                      ],
                      "actions": [
                        { "id": "quick", "label": "快捷动作" }
                      ],
                      "tools": [
                        { "name": "my_tool", "description": "插件工具" }
                      ],
                      "intercept": { "request": true, "response": true }
                    }

                    main.js 里可定义这些钩子（都能省略）：
                    function onAction(actionId) { return "结果文本或 JSON"; }
                    function onTool(name, argsJson) { return "工具结果"; }
                    function onRequest(payload) { return null; }  // 返回字符串即替换请求体
                    function onResponse(payload) { return null; }

                    payload 与返回值都是 JSON 字符串：
                    { "url": "...", "method": "...", "headers": {...}, "body": "..." }

                    pages 会在本页出现「打开」入口；actions 会出现在聊天「+」面板；
                    tools 会注册给 AI 调用；intercept 需在本页单独授权开关后才生效。
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showFormat = false }) { Text("知道了") }
            },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("卸载插件？") },
            text = { Text("将删除「${plugin.manifest.name}」及其全部文件。") },
            confirmButton = {
                TextButton(onClick = {
                    pluginManager.uninstall(plugin.manifest.id)
                    pendingDelete = null
                    refresh()
                    toaster.show("已卸载插件", type = ToastType.Normal)
                }) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleIntercept: (Boolean) -> Unit,
    onOpenPage: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val manifest = plugin.manifest
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column {
                    Text(manifest.name)
                    Text(
                        text = "v${manifest.version}" + if (manifest.author.isNotBlank()) " · ${manifest.author}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        item(
            headlineContent = { Text("启用插件") },
            supportingContent = {
                if (manifest.description.isNotBlank()) Text(manifest.description)
            },
            trailingContent = {
                Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
            },
        )
        if (manifest.intercept.request || manifest.intercept.response) {
            item(
                headlineContent = { Text("允许干预网络请求") },
                supportingContent = {
                    Text(
                        "开启后插件的 onRequest / onResponse 会改写 AI 请求与响应体；" +
                            "请求体内可能包含你的 API Key，请确认插件来源可信。"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = plugin.interceptEnabled,
                        onCheckedChange = onToggleIntercept,
                        enabled = plugin.enabled,
                    )
                },
            )
        }
        manifest.pages.forEach { page ->
            item(
                onClick = { onOpenPage(page.id) },
                headlineContent = { Text(page.title) },
                supportingContent = { Text("插件页面 · ${page.buttons.size} 个按钮") },
                trailingContent = { Text("打开") },
            )
        }
        item(
            headlineContent = { Text("卸载") },
            trailingContent = {
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, contentDescription = "卸载")
                }
            },
        )
    }
}
