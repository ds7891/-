package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.files.TrashEntry
import me.rerere.rikkahub.data.files.TrashManager
import me.rerere.rikkahub.ui.context.LocalToaster
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「最近删除」：AI 删除的文件都先落在这里，不会立刻销毁。
 * 支持单条恢复、全部恢复，以及全部彻底删除。
 */
@Composable
internal fun TrashSheet(
    trashManager: TrashManager,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val toaster = LocalToaster.current
    var entries by remember { mutableStateOf(trashManager.list()) }
    var confirmClearAll by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "最近删除",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val count = trashManager.restoreAll()
                        entries = trashManager.list()
                        toaster.show(
                            message = if (count > 0) "已恢复 $count 项" else "没有可恢复的文件",
                            type = if (count > 0) ToastType.Success else ToastType.Normal,
                        )
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Text("全部恢复")
                }
                TextButton(
                    onClick = { confirmClearAll = true },
                    enabled = entries.isNotEmpty(),
                ) {
                    Text("全部删除")
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "最近删除是空的\nAI 删除的文件会先放到这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TrashEntryItem(
                            entry = entry,
                            onRestore = {
                                val ok = trashManager.restore(entry)
                                entries = trashManager.list()
                                toaster.show(
                                    message = if (ok) "已恢复到原路径" else "恢复失败，文件可能已丢失",
                                    type = if (ok) ToastType.Success else ToastType.Error,
                                )
                            },
                            onDelete = {
                                trashManager.delete(entry)
                                entries = trashManager.list()
                                toaster.show("已彻底删除", type = ToastType.Normal)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("彻底删除全部？") },
            text = { Text("「最近删除」里的 ${entries.size} 项将被永久删除，无法再恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        trashManager.deleteAll()
                        entries = trashManager.list()
                        confirmClearAll = false
                        toaster.show("已清空最近删除", type = ToastType.Normal)
                    }
                ) {
                    Text("彻底删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TrashEntryItem(
    entry: TrashEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = entry.name.ifBlank { entry.originalPath },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.originalPath,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.deletedAtText()} · ${entry.sizeText()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore) {
                    Text("恢复")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.large),
    )
}

private fun TrashEntry.deletedAtText(): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(deletedAt))

private fun TrashEntry.sizeText(): String = when {
    isDirectory -> "目录"
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}
