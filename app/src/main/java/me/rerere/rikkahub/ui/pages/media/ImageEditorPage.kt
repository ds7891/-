package me.rerere.rikkahub.ui.pages.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File

/** 内置图片编辑器：读取目录里的图片，旋转 / 翻转 / 缩放 / 调色后另存为新文件。 */
@Composable
fun ImageEditorPage(path: String) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val sourceFile = remember(path) { File(path) }
    val outputFile = remember(path) {
        File(sourceFile.parentFile ?: File("."), "${sourceFile.nameWithoutExtension}.edited.png")
    }

    var rotate by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var brightness by remember { mutableFloatStateOf(1f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var grayscale by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(sourceFile, rotate, flipH, flipV, scale, brightness, contrast, saturation, grayscale) {
        preview = withContext(Dispatchers.Default) {
            val base = decodeScaledBitmap(sourceFile, 1080) ?: return@withContext null
            applyImageEdits(base, rotate, flipH, flipV, scale, brightness, contrast, saturation, grayscale)
                .asImageBitmap()
        }
    }

    val savedMessage = stringResource(R.string.image_editor_saved, outputFile.absolutePath)
    val saveFailedTemplate = stringResource(R.string.image_editor_save_failed)

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val base = decodeScaledBitmap(sourceFile, 4096) ?: error("decode failed")
                    val edited = applyImageEdits(
                        base, rotate, flipH, flipV, scale, brightness, contrast, saturation, grayscale,
                    )
                    outputFile.parentFile?.mkdirs()
                    val format = if (outputFile.extension.equals("png", ignoreCase = true)) {
                        Bitmap.CompressFormat.PNG
                    } else {
                        Bitmap.CompressFormat.JPEG
                    }
                    outputFile.outputStream().use { edited.compress(format, 95, it) }
                    if (!edited.isRecycled) edited.recycle()
                }
            }
            saving = false
            result
                .onSuccess { toaster.show(savedMessage) }
                .onFailure { toaster.show(saveFailedTemplate.format(it.message ?: "")) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = sourceFile.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { save() }, enabled = !saving) {
                        Text(stringResource(R.string.image_editor_save))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = preview
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.image_editor_output_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rotate = (rotate + 90) % 360 }) {
                        Text(stringResource(R.string.image_editor_rotate))
                    }
                    OutlinedButton(onClick = { flipH = !flipH }) {
                        Text(stringResource(R.string.image_editor_flip_h))
                    }
                    OutlinedButton(onClick = { flipV = !flipV }) {
                        Text(stringResource(R.string.image_editor_flip_v))
                    }
                }

                ControlSlider(
                    label = stringResource(R.string.image_editor_scale),
                    value = scale,
                    range = 0.1f..2f,
                    onValueChange = { scale = it },
                )
                ControlSlider(
                    label = stringResource(R.string.image_editor_brightness),
                    value = brightness,
                    range = 0.2f..2f,
                    onValueChange = { brightness = it },
                )
                ControlSlider(
                    label = stringResource(R.string.image_editor_contrast),
                    value = contrast,
                    range = 0.2f..2f,
                    onValueChange = { contrast = it },
                )
                ControlSlider(
                    label = stringResource(R.string.image_editor_saturation),
                    value = saturation,
                    range = 0f..2f,
                    onValueChange = { saturation = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.image_editor_grayscale),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = grayscale,
                        onCheckedChange = { grayscale = it },
                        size = SwitchSize.Small,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            rotate = 0
                            flipH = false
                            flipV = false
                            scale = 1f
                            brightness = 1f
                            contrast = 1f
                            saturation = 1f
                            grayscale = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.image_editor_reset))
                    }
                    Button(
                        onClick = { save() },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (saving) {
                                stringResource(R.string.image_editor_saving)
                            } else {
                                stringResource(R.string.image_editor_save)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}