package me.rerere.rikkahub.ui.pages.media

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasAllFilesAccess
import me.rerere.rikkahub.utils.openAllFilesAccessSettings
import java.io.File

internal val MEDIA_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif",
)

internal val MEDIA_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "m4v",
)

/** 媒体浏览器：浏览目录里的图片与视频，点击后进入图片编辑器 / 视频播放器。 */
@Composable
fun MediaBrowserPage(initialPath: String = "") {
    val context = LocalContext.current
    val navigator = LocalNavController.current
    val root = remember(initialPath) {
        if (initialPath.isBlank()) {
            Environment.getExternalStorageDirectory()
        } else {
            File(initialPath)
        }
    }
    var currentDir by remember(initialPath) { mutableStateOf(root) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var hasPermission by remember { mutableStateOf(context.hasAllFilesAccess()) }

    // 从系统设置授权页返回、或从编辑器返回时，重新检查权限并刷新目录
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = context.hasAllFilesAccess()
                reloadKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentDir.absolutePath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_local_tools_file_system_permission_required),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { context.openAllFilesAccessSettings() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_file_system_title))
                }
            }
            return@Scaffold
        }

        val entries = remember(currentDir, reloadKey) {
            currentDir.listFiles()
                ?.filter { file ->
                    file.isDirectory || file.extension.lowercase() in MEDIA_IMAGE_EXTENSIONS ||
                        file.extension.lowercase() in MEDIA_VIDEO_EXTENSIONS
                }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .orEmpty()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val parent = currentDir.parentFile
            if (parent != null) {
                item(key = "..") {
                    ListItem(
                        headlineContent = { Text("..") },
                        leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
                        modifier = Modifier.clickable { currentDir = parent },
                    )
                }
            }
            items(entries, key = { it.absolutePath }) { file ->
                val isVideo = file.extension.lowercase() in MEDIA_VIDEO_EXTENSIONS
                ListItem(
                    headlineContent = {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = if (file.isDirectory) {
                                stringResource(R.string.media_browser_folder)
                            } else {
                                formatSize(file.length())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = when {
                                file.isDirectory -> HugeIcons.Folder01
                                isVideo -> HugeIcons.Video01
                                else -> HugeIcons.Image02
                            },
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        if (!file.isDirectory) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    HugeIcons.File01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        if (file.isDirectory) {
                            currentDir = file
                        } else if (isVideo) {
                            navigator.navigate(Screen.VideoPlayer(file.absolutePath))
                        } else {
                            navigator.navigate(Screen.ImageEditor(file.absolutePath))
                        }
                    },
                )
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.media_browser_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}