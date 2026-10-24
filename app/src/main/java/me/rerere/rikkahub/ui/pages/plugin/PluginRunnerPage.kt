package me.rerere.rikkahub.ui.pages.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.plugins.PluginManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 插件页面渲染器：按 manifest 里声明的 buttons 生成列表，点击后调用插件脚本的 onAction，
 * 把返回值展示在对话框里。这样插件无需重新编译就能新增一个界面 / 功能。
 */
@Composable
fun PluginRunnerPage(pluginId: String, pageId: String) {
    val pluginManager: PluginManager = koinInject()
    val scope = rememberCoroutineScope()
    val plugin = remember(pluginId) { pluginManager.find(pluginId) }
    val page = remember(pluginId, pageId) { plugin?.manifest?.pages?.firstOrNull { it.id == pageId } }
    var result by remember { mutableStateOf<String?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(page?.title ?: plugin?.manifest?.name ?: "插件") },
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
            if (plugin == null || page == null) {
                item {
                    Text(
                        text = "插件或页面不存在，可能已被卸载。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(page.title) },
                    ) {
                        if (page.buttons.isEmpty()) {
                            item(
                                headlineContent = { Text("这个页面还没有按钮") },
                                supportingContent = { Text("在 manifest.json 的 pages[].buttons 里添加") },
                            )
                        }
                        page.buttons.forEach { button ->
                            item(
                                onClick = {
                                    scope.launch {
                                        val output = pluginManager.runAction(pluginId, button.action)
                                        result = output?.takeIf { it.isNotBlank() }
                                            ?: "已执行「${button.label}」（脚本没有返回内容）"
                                    }
                                },
                                headlineContent = { Text(button.label) },
                                supportingContent = {
                                    Column {
                                        if (button.description.isNotBlank()) Text(button.description)
                                        Text(
                                            text = "action: ${button.action}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    result?.let { text ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("插件返回") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { result = null }) { Text("关闭") }
            },
        )
    }
}
