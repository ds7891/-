package me.rerere.rikkahub.ui.pages.videogen

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Video01
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.videogen.BUILTIN_VIDEO_MODEL
import me.rerere.rikkahub.data.videogen.GeneratedVideo
import me.rerere.rikkahub.data.videogen.VIDEO_PROTOCOL_OPENAI
import me.rerere.rikkahub.data.videogen.VIDEO_PROTOCOL_JSON
import me.rerere.rikkahub.data.videogen.VIDEO_PROTOCOL_NEWAPI
import me.rerere.rikkahub.data.videogen.VIDEO_PROTOCOL_SIFTQ
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_DISPATCHING
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_FAILED
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_QUEUED
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_RUNNING
import me.rerere.rikkahub.data.videogen.VideoModel
import me.rerere.rikkahub.data.videogen.VideoParams
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.util.UUID

@Composable
fun VideoGenPage(
    modifier: Modifier = Modifier,
    vm: VideoGenVM = koinViewModel(),
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }

    BackHandler(isGenerating) { showCancelDialog = true }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.videogen_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::startNewSession) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.videogen_page_new_session))
                    }
                }
            )
        },
        bottomBar = { BottomBar(pagerState, scope) },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> VideoGenScreen(vm = vm)
                else -> VideoGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.videogen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.videogen_page_cancel_generation_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.videogen_page_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.videogen_page_cancel)) } },
    )
}

@Composable
private fun BottomBar(pagerState: PagerState, scope: CoroutineScope) {
    NavigationBar {
        NavigationBarItem(
            selected = pagerState.currentPage == 0,
            label = { Text(stringResource(R.string.videogen_page_title)) },
            icon = { Icon(HugeIcons.Video01, null) },
            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
        )
        NavigationBarItem(
            selected = pagerState.currentPage == 1,
            label = { Text(stringResource(R.string.videogen_page_gallery)) },
            icon = { Icon(HugeIcons.Play, null) },
            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
        )
    }
}

@Composable
private fun VideoGenScreen(vm: VideoGenVM) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val queuePosition by vm.queuePosition.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val elapsedSeconds by vm.elapsedSeconds.collectAsStateWithLifecycle()
    val currentVideo by vm.currentVideo.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val savedMessage by vm.savedMessage.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedModel = config.models.firstOrNull { it.id == config.selectedModelId } ?: BUILTIN_VIDEO_MODEL
    val params = config.paramsFor(selectedModel)

    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    LaunchedEffect(error) {
        error?.let {
            toaster.show(message = it, type = ToastType.Error)
            vm.clearError()
        }
    }
    LaunchedEffect(savedMessage) {
        if (savedMessage > 0) {
            toaster.show(message = context.getString(R.string.videogen_page_saved), type = ToastType.Success)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                currentVideo != null -> CurrentVideoPreview(
                    video = currentVideo!!,
                    aspect = params.aspect,
                    onSave = { vm.saveVideoToDevice(it) },
                    onDelete = { vm.deleteVideo(it) },
                )

                isGenerating -> GenerationStatus(
                    phase = phase,
                    queuePosition = queuePosition,
                    progress = progress,
                    elapsedSeconds = elapsedSeconds,
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Video01,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.videogen_page_empty_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        InputBar(
            prompt = prompt,
            onPromptChange = vm::updatePrompt,
            isGenerating = isGenerating,
            referenceImages = referenceImages,
            onAddReference = { path -> vm.addReferenceImage(path) },
            onRemoveReference = vm::removeReferenceImage,
            selectedModel = selectedModel,
            models = config.models,
            onSelectModel = vm::selectModel,
            onShowSettings = { showSettingsSheet = true },
            onGenerate = vm::generate,
            onCancel = vm::cancelGeneration,
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            vm = vm,
            model = selectedModel,
            params = params,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun CurrentVideoPreview(
    video: GeneratedVideo,
    aspect: String,
    onSave: (GeneratedVideo) -> Unit,
    onDelete: (GeneratedVideo) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineVideoPlayer(
            path = video.filePath,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (aspect == "9:16") it.aspectRatio(9f / 16f) else it.aspectRatio(16f / 9f) }
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            text = video.prompt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = { onSave(video) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Download04,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.videogen_page_save_to_device),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Surface(
                onClick = { showDeleteDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.videogen_page_delete_video_title)) },
            text = { Text(stringResource(R.string.videogen_page_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete(video)
                }) { Text(stringResource(R.string.videogen_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.videogen_page_cancel)) }
            },
        )
    }
}

@Composable
private fun GenerationStatus(
    phase: String,
    queuePosition: Int?,
    progress: Int?,
    elapsedSeconds: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Video01,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = phaseText(phase, queuePosition),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(0.8f),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))
        }
        Text(
            text = stringResource(R.string.videogen_page_elapsed, elapsedSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun phaseText(phase: String, queuePosition: Int?): String = when (phase) {
    VIDEO_STATUS_QUEUED -> if (queuePosition != null) {
        stringResource(R.string.videogen_page_status_queued, queuePosition)
    } else {
        stringResource(R.string.videogen_page_status_queued_unknown)
    }

    VIDEO_STATUS_DISPATCHING -> stringResource(R.string.videogen_page_status_dispatching)
    VIDEO_STATUS_RUNNING -> stringResource(R.string.videogen_page_status_running)
    VIDEO_STATUS_FAILED -> stringResource(R.string.videogen_page_status_failed)
    else -> stringResource(R.string.videogen_page_status_queued_unknown)
}

@Composable
private fun InputBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    isGenerating: Boolean,
    referenceImages: List<String>,
    onAddReference: (String) -> Unit,
    onRemoveReference: (String) -> Unit,
    selectedModel: VideoModel,
    models: List<VideoModel>,
    onSelectModel: (String) -> Unit,
    onShowSettings: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // 支持一次选中多张参考图，选中后按顺序追加，数量不限
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val paths = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching {
                            val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                ?: error("Failed to decode image")
                            val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                            bitmap.recycle()
                            val file = File(context.appTempFolder, "videogen_ref_${UUID.randomUUID()}.png")
                            file.writeBytes(pngBytes)
                            file.absolutePath
                        }.getOrNull()
                    }
                }
                paths.forEach(onAddReference)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (referenceImages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    referenceImages.forEachIndexed { index, path ->
                        Box {
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            // 序号角标：按上传顺序编号，方便对照「图1=人物、图2=背景」的写法
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(3.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.padding(horizontal = 5.dp),
                                )
                            }
                            Surface(
                                onClick = { onRemoveReference(path) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = if (selectedModel.requiresImage) {
                        stringResource(R.string.videogen_page_reference_image_required)
                    } else {
                        stringResource(R.string.videogen_page_reference_image)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (selectedModel.requiresImage) {
            Text(
                text = stringResource(R.string.videogen_page_reference_required_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            placeholder = { Text(stringResource(R.string.videogen_page_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Surface(
                    onClick = { modelMenuExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(HugeIcons.Video01, null, modifier = Modifier.size(16.dp))
                        Text(
                            text = selectedModel.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(90.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name) },
                            leadingIcon = { Icon(HugeIcons.Video01, null) },
                            trailingIcon = {
                                if (model.id == selectedModel.id) Icon(HugeIcons.Tick01, null)
                            },
                            onClick = {
                                modelMenuExpanded = false
                                onSelectModel(model.id)
                            },
                        )
                    }
                }
            }

            IconButton(onClick = onShowSettings) {
                Icon(HugeIcons.Tools, null)
            }

            IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.videogen_page_add_reference))
            }

            Spacer(modifier = Modifier.weight(1f))

            // 需要参考图的模型必须已添加参考图才能提交
            val canSend = prompt.isNotBlank() && (!selectedModel.requiresImage || referenceImages.isNotEmpty())
            Surface(
                onClick = { if (isGenerating) onCancel() else onGenerate() },
                enabled = isGenerating || canSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isGenerating -> MaterialTheme.colorScheme.errorContainer
                    !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                },
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = stringResource(R.string.videogen_page_generate),
                        tint = when {
                            isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                            !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    vm: VideoGenVM,
    model: VideoModel,
    params: VideoParams,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val config by vm.config.collectAsStateWithLifecycle()
    var editingModel by remember { mutableStateOf<VideoModel?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.videogen_page_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = {
                    editingModel = null
                    showEditor = true
                }) {
                    Icon(HugeIcons.Add01, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.videogen_page_add_model))
                }
            }

            FormItem(label = { Text(stringResource(R.string.videogen_page_model)) }) {
                Column {
                    config.models.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectModel(m.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = m.id == config.selectedModelId,
                                onClick = { vm.selectModel(m.id) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (m.builtin) {
                                        stringResource(R.string.videogen_page_builtin)
                                    } else {
                                        "${m.protocol} · ${m.baseUrl}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!m.builtin) {
                                IconButton(onClick = {
                                    editingModel = m
                                    showEditor = true
                                }) {
                                    Icon(HugeIcons.Edit03, null, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { vm.deleteModel(m.id) }) {
                                    Icon(
                                        HugeIcons.Delete01,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_duration)) },
                description = { Text(stringResource(R.string.videogen_page_duration_desc)) },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.durationOptions.forEach { duration ->
                        FilterChip(
                            selected = params.duration == duration,
                            onClick = { vm.updateParams(params.copy(duration = duration)) },
                            label = { Text(stringResource(R.string.videogen_page_seconds, duration)) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_resolution)) },
                description = { Text(stringResource(R.string.videogen_page_resolution_desc)) },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.resolutionOptions.forEach { resolution ->
                        FilterChip(
                            selected = params.resolution == resolution,
                            onClick = { vm.updateParams(params.copy(resolution = resolution)) },
                            label = { Text(resolution) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_fps)) },
                description = { Text(stringResource(R.string.videogen_page_fps_desc)) },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.fpsOptions.forEach { fps ->
                        FilterChip(
                            selected = params.fps == fps,
                            onClick = { vm.updateParams(params.copy(fps = fps)) },
                            label = { Text(stringResource(R.string.videogen_page_fps_value, fps)) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_aspect)) },
                description = { Text(stringResource(R.string.videogen_page_aspect_desc)) },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.aspectOptions.forEach { aspect ->
                        FilterChip(
                            selected = params.aspect == aspect,
                            onClick = { vm.updateParams(params.copy(aspect = aspect)) },
                            label = { Text(aspect) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showEditor) {
        ModelEditorDialog(
            vm = vm,
            initial = editingModel,
            onDismiss = { showEditor = false },
            onConfirm = { model ->
                if (editingModel == null) vm.addModel(model) else vm.updateModel(model)
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelEditorDialog(
    vm: VideoGenVM,
    initial: VideoModel?,
    onDismiss: () -> Unit,
    onConfirm: (VideoModel) -> Unit,
) {
    val cloudModels by vm.cloudModels.collectAsStateWithLifecycle()
    val isFetchingModels by vm.isFetchingModels.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: VIDEO_PROTOCOL_OPENAI) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var modelId by remember { mutableStateOf(initial?.model ?: "") }
    var submitPath by remember { mutableStateOf(initial?.submitPath ?: "videos") }
    var queryPath by remember { mutableStateOf(initial?.queryPath ?: "videos/{id}") }
    var downloadPath by remember { mutableStateOf(initial?.downloadPath ?: "videos/{id}/content") }
    var durations by remember { mutableStateOf(initial?.durationOptions?.joinToString(",") ?: "5,10") }
    var resolutions by remember { mutableStateOf(initial?.resolutionOptions?.joinToString(",") ?: "720P,1080P") }
    var fps by remember { mutableStateOf(initial?.fpsOptions?.joinToString(",") ?: "24,30") }
    var aspects by remember { mutableStateOf(initial?.aspectOptions?.joinToString(",") ?: "16:9,9:16,1:1") }
    var protocolMenuExpanded by remember { mutableStateOf(false) }

    // 每次打开编辑器都清空上一次拉取的模型列表，避免串台
    LaunchedEffect(Unit) { vm.clearCloudModels() }
    // 拉取到云端模型后，自动选中第一个识别到的模型，并补齐模型名称
    LaunchedEffect(cloudModels) {
        if (cloudModels.isNotEmpty() && modelId.isBlank()) {
            val first = cloudModels.first()
            modelId = first
            if (name.isBlank()) name = first
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) {
                    stringResource(R.string.videogen_page_add_model)
                } else {
                    stringResource(R.string.videogen_page_edit_model)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.videogen_page_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedTextField(
                        value = protocol,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.videogen_page_protocol)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { protocolMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = protocolMenuExpanded,
                        onDismissRequest = { protocolMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("openai · ${stringResource(R.string.videogen_page_protocol_openai)}") },
                            onClick = {
                                protocol = VIDEO_PROTOCOL_OPENAI
                                submitPath = "videos"
                                queryPath = "videos/{id}"
                                downloadPath = "videos/{id}/content"
                                protocolMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("json · ${stringResource(R.string.videogen_page_protocol_json)}") },
                            onClick = {
                                protocol = VIDEO_PROTOCOL_JSON
                                submitPath = "videos"
                                queryPath = "videos/{id}"
                                downloadPath = "videos/{id}/content"
                                protocolMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("newapi · ${stringResource(R.string.videogen_page_protocol_newapi)}") },
                            onClick = {
                                protocol = VIDEO_PROTOCOL_NEWAPI
                                submitPath = "video/generations"
                                queryPath = "video/generations/{id}"
                                downloadPath = "video/generations/{id}/content"
                                protocolMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("siftq · ${stringResource(R.string.videogen_page_protocol_siftq)}") },
                            onClick = {
                                protocol = VIDEO_PROTOCOL_SIFTQ
                                protocolMenuExpanded = false
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.videogen_page_base_url)) },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.videogen_page_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 获取云端模型：用上面填写的接口地址与 API Key 拉取可用模型
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = baseUrl.isNotBlank() && !isFetchingModels,
                        onClick = { vm.fetchCloudModels(baseUrl.trim(), apiKey.trim()) },
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(HugeIcons.Download01, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.videogen_page_fetch_models))
                    }
                }
                if (cloudModels.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.videogen_page_cloud_models_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            cloudModels.forEach { cloudModel ->
                                FilterChip(
                                    selected = modelId == cloudModel,
                                    onClick = {
                                        modelId = cloudModel
                                        if (name.isBlank()) name = cloudModel
                                    },
                                    label = { Text(cloudModel) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.videogen_page_model_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (protocol != VIDEO_PROTOCOL_SIFTQ) {
                    OutlinedTextField(
                        value = submitPath,
                        onValueChange = { submitPath = it },
                        label = { Text(stringResource(R.string.videogen_page_submit_path)) },
                        placeholder = { Text("videos") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = queryPath,
                        onValueChange = { queryPath = it },
                        label = { Text(stringResource(R.string.videogen_page_query_path)) },
                        placeholder = { Text("videos/{id}") },
                        supportingText = { Text(stringResource(R.string.videogen_page_path_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = downloadPath,
                        onValueChange = { downloadPath = it },
                        label = { Text(stringResource(R.string.videogen_page_download_path)) },
                        placeholder = { Text("videos/{id}/content") },
                        supportingText = { Text(stringResource(R.string.videogen_page_path_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = durations,
                    onValueChange = { durations = it },
                    label = { Text(stringResource(R.string.videogen_page_duration)) },
                    supportingText = { Text(stringResource(R.string.videogen_page_options_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = resolutions,
                    onValueChange = { resolutions = it },
                    label = { Text(stringResource(R.string.videogen_page_resolution)) },
                    supportingText = { Text(stringResource(R.string.videogen_page_options_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fps,
                    onValueChange = { fps = it },
                    label = { Text(stringResource(R.string.videogen_page_fps)) },
                    supportingText = { Text(stringResource(R.string.videogen_page_options_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aspects,
                    onValueChange = { aspects = it },
                    label = { Text(stringResource(R.string.videogen_page_aspect)) },
                    supportingText = { Text(stringResource(R.string.videogen_page_options_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || modelId.isNotBlank(),
                onClick = {
                    val durationList = durations.split(",").mapNotNull { it.trim().toIntOrNull() }
                        .ifEmpty { listOf(6) }
                    val resolutionList = resolutions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .ifEmpty { listOf("720P") }
                    val fpsList = fps.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(24) }
                    val aspectList = aspects.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .ifEmpty { listOf("16:9") }
                    onConfirm(
                        VideoModel(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { modelId.trim() },
                            protocol = protocol,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = modelId.trim().ifBlank { name.trim() },
                            builtin = false,
                            submitPath = submitPath.trim().ifBlank { "videos" },
                            queryPath = queryPath.trim().ifBlank { "videos/{id}" },
                            downloadPath = downloadPath.trim().ifBlank { "videos/{id}/content" },
                            aspectOptions = aspectList,
                            durationOptions = durationList,
                            resolutionOptions = resolutionList,
                            fpsOptions = fpsList,
                        )
                    )
                },
            ) { Text(stringResource(R.string.videogen_page_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.videogen_page_cancel)) }
        },
    )
}

@Composable
private fun VideoGalleryScreen(vm: VideoGenVM) {
    val videos = vm.generatedVideos.collectAsLazyPagingItems()
    val navigator = LocalNavController.current
    val pullToRefreshState = rememberPullToRefreshState()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val savedMessage by vm.savedMessage.collectAsStateWithLifecycle()

    LaunchedEffect(savedMessage) {
        if (savedMessage > 0) {
            toaster.show(message = context.getString(R.string.videogen_page_saved), type = ToastType.Success)
        }
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { videos.refresh() },
        state = pullToRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (videos.itemCount == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = HugeIcons.Video01,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.videogen_page_no_videos),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = videos.itemCount) { index ->
                    val video = videos[index]
                    video?.let {
                        Card {
                            Column {
                                Box {
                                    val thumbnail = rememberVideoThumbnail(it.filePath)
                                    if (thumbnail != null) {
                                        Image(
                                            bitmap = thumbnail,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(0.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(HugeIcons.Video01, null, modifier = Modifier.size(40.dp))
                                        }
                                    }
                                    Surface(
                                        onClick = { navigator.navigate(Screen.VideoPlayer(it.filePath)) },
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(48.dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = HugeIcons.Play,
                                                contentDescription = stringResource(R.string.videogen_page_play),
                                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                            )
                                        }
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = it.model,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = it.prompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row {
                                        IconButton(
                                            onClick = { vm.saveVideoToDevice(it) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Download04,
                                                contentDescription = stringResource(R.string.videogen_page_save_to_device),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { vm.deleteVideo(it) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Delete01,
                                                contentDescription = stringResource(R.string.videogen_page_delete),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineVideoPlayer(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier,
    )
}

/** 视频首帧缩略图的内存缓存：避免列表滚动 / 重组时反复解码同一视频。 */
private object VideoThumbnailCache {
    private val cache = object : LruCache<String, ImageBitmap>(48) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun put(path: String, bitmap: ImageBitmap) {
        cache.put(path, bitmap)
    }
}

@Composable
private fun rememberVideoThumbnail(path: String?): ImageBitmap? {
    // 命中缓存直接复用，未命中才在后台解码；解码尺寸压到缩略图大小，降低内存占用
    val cached = path?.let { VideoThumbnailCache.get(it) }
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, path) {
        if (path.isNullOrBlank()) {
            value = null
            return@produceState
        }
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMBNAIL_WIDTH,
                            THUMBNAIL_HEIGHT,
                        ) ?: retriever.frameAtTime
                    } else {
                        retriever.frameAtTime
                    }
                    frame?.asImageBitmap()
                } finally {
                    retriever.release()
                }
            }.getOrNull()?.also { VideoThumbnailCache.put(path, it) }
        }
    }
    return bitmap
}

private const val THUMBNAIL_WIDTH = 480
private const val THUMBNAIL_HEIGHT = 270