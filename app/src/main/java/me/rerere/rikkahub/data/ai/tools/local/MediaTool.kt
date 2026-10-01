package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.hasAllFilesAccess
import java.io.File

/** 媒体工具箱的注册名。 */
const val MEDIA_TOOL = "media_tool"

/** 会改动磁盘内容的操作，执行前需用户确认。 */
private val MEDIA_DESTRUCTIVE_ACTIONS = setOf("edit_image", "video_frames")

private const val MEDIA_DEFAULT_LIST_LIMIT = 200
private const val MEDIA_DEFAULT_FRAME_COUNT = 6
private const val MEDIA_MAX_FRAME_COUNT = 24
private const val MEDIA_PREVIEW_MAX = 2048

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "m4v",
)

/**
 * 媒体工具箱：让 AI 读取、理解并修改本机的图片与视频。
 *
 * - list：列出目录下的图片/视频
 * - image_info：读取图片宽高、格式、大小
 * - view_image：把图片作为可视图返回给模型（视觉模型可"看到"图片内容）
 * - edit_image：对图片做旋转/翻转/裁剪/缩放/调色并另存为新文件
 * - video_info：读取视频时长、分辨率、码率
 * - video_frames：抽取视频指定时间点的关键帧并存成图片，同时返回给模型查看
 *
 * 需要在本地工具设置中开启"媒体工具"，并在系统里授予"所有文件访问权限"。
 */
internal fun buildMediaTool(context: Context): Tool = Tool(
    name = MEDIA_TOOL,
    description = """
        读取、理解并修改本机的图片与视频，相当于内置的图片编辑器 + 视频播放器/抽帧器：
        - list：列出某个目录下的图片与视频，可按 image/video/all 过滤
        - image_info：读取图片的宽高、格式、文件大小
        - view_image：把一张或多张图片直接返回给模型查看（只有支持图片输入的模型才能"看到"内容）
        - edit_image：修改图片并另存为新文件，支持 rotate（旋转角度）、flip_h/flip_v（翻转）、
          crop（按 0~1 的比例裁剪 left/top/right/bottom）、scale（缩放比例）、
          brightness/contrast/saturation（亮度/对比度/饱和度，1 为原始值）、grayscale（转灰度）、
          format（jpeg/png）与 quality（1~100）。传 return_image=true 可把编辑结果再返回给模型查看
        - video_info：读取视频时长、分辨率、旋转角、码率、格式
        - video_frames：抽帧。传 timestamps（秒，数组）按指定时间点抽帧，或用 count 均匀抽取 N 帧；
          抽出的帧会保存到 output_dir，并作为图片返回给模型查看，供模型分析视频画面
        典型用法：先用 list 找到文件 → view_image / video_frames 让模型理解内容 → edit_image 按要求修改。
        path 支持绝对路径、相对路径（相对内部共享存储根目录）与 /sdcard 前缀。
        未授予"所有文件访问权限"时读写会失败，应提示用户到助手设置里开启本工具并授权。
    """.trimIndent().replace("\n", " "),
    needsApproval = { args ->
        val action = runCatching {
            args.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        action in MEDIA_DESTRUCTIVE_ACTIONS
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            listOf(
                                "list", "image_info", "view_image",
                                "edit_image", "video_info", "video_frames",
                            ).forEach { add(it) }
                        },
                    )
                    put("description", "要执行的操作。")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "目标文件或目录路径；list 时为目录，其余为文件（view_image 可用 paths 传多张）。")
                })
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", "view_image 时要查看的多张图片路径。")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("description", "list 时的过滤类型：all（默认）、image 或 video。")
                })
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "list 是否递归子目录，默认 false。")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "list 返回的最大条目数。")
                })
                put("output", buildJsonObject {
                    put("type", "string")
                    put("description", "edit_image 的输出文件路径；不填则在原图同目录生成 xxx.edited.jpg/png。")
                })
                put("output_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "video_frames 抽帧图片的保存目录；不填则在视频同目录生成 xxx.frames。")
                })
                put("rotate", buildJsonObject {
                    put("type", "integer")
                    put("description", "edit_image 旋转角度，支持 90/180/270，默认 0。")
                })
                put("flip_h", buildJsonObject {
                    put("type", "boolean")
                    put("description", "edit_image 是否水平翻转。")
                })
                put("flip_v", buildJsonObject {
                    put("type", "boolean")
                    put("description", "edit_image 是否垂直翻转。")
                })
                put("crop", buildJsonObject {
                    put("type", "object")
                    put("description", "edit_image 裁剪区域，按 0~1 比例：{\"left\":0,\"top\":0,\"right\":1,\"bottom\":1}。")
                    put("properties", buildJsonObject {
                        put("left", buildJsonObject { put("type", "number") })
                        put("top", buildJsonObject { put("type", "number") })
                        put("right", buildJsonObject { put("type", "number") })
                        put("bottom", buildJsonObject { put("type", "number") })
                    })
                })
                put("scale", buildJsonObject {
                    put("type", "number")
                    put("description", "edit_image 缩放比例，1 为原始尺寸，例如 0.5 表示缩小一半。")
                })
                put("brightness", buildJsonObject {
                    put("type", "number")
                    put("description", "edit_image 亮度，1 为原始值，范围约 0~2。")
                })
                put("contrast", buildJsonObject {
                    put("type", "number")
                    put("description", "edit_image 对比度，1 为原始值，范围约 0~2。")
                })
                put("saturation", buildJsonObject {
                    put("type", "number")
                    put("description", "edit_image 饱和度，1 为原始值，0 为灰度。")
                })
                put("grayscale", buildJsonObject {
                    put("type", "boolean")
                    put("description", "edit_image 是否转为灰度图。")
                })
                put("format", buildJsonObject {
                    put("type", "string")
                    put("description", "edit_image 输出格式：jpeg（默认）或 png。")
                })
                put("quality", buildJsonObject {
                    put("type", "integer")
                    put("description", "edit_image JPEG 输出质量，1~100，默认 92。")
                })
                put("return_image", buildJsonObject {
                    put("type", "boolean")
                    put("description", "edit_image 是否把编辑后的图片返回给模型查看，默认 false。")
                })
                put("timestamps", buildJsonObject {
                    put("type", "array")
                    put("description", "video_frames 要抽帧的时间点（秒），例如 [0, 2.5, 5]。")
                    put("items", buildJsonObject { put("type", "number") })
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "video_frames 未指定 timestamps 时，均匀抽取的帧数，默认 6，最大 24。")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        if (!context.hasAllFilesAccess()) {
            listOf(
                UIMessagePart.Text(
                    mediaErrorJson(
                        "NO_PERMISSION",
                        "尚未获得\"所有文件访问权限\"。请用户在助手的本地工具设置中开启\"媒体工具\"并授予存储权限后重试。",
                    )
                )
            )
        } else {
            runCatching { runMediaAction(args.jsonObject) }
                .getOrElse {
                    listOf(UIMessagePart.Text(mediaErrorJson("TOOL_ERROR", "执行失败：${it.message ?: it::class.simpleName}")))
                }
        }
    },
)

private fun runMediaAction(params: JsonObject): List<UIMessagePart> {
    val action = params.mediaString("action")?.trim().orEmpty()
    return when (action) {
        "list" -> listOf(UIMessagePart.Text(actionList(params)))
        "image_info" -> listOf(UIMessagePart.Text(actionImageInfo(params)))
        "view_image" -> actionViewImage(params)
        "edit_image" -> actionEditImage(params)
        "video_info" -> listOf(UIMessagePart.Text(actionVideoInfo(params)))
        "video_frames" -> actionVideoFrames(params)
        else -> listOf(UIMessagePart.Text(mediaErrorJson("UNKNOWN_ACTION", "未知的 action：'$action'")))
    }
}

/** 列出目录下的图片与视频。 */
private fun actionList(params: JsonObject): String {
    val dir = resolvePath(params.mediaString("path"))
        ?: return mediaErrorJson("MISSING_PATH", "path is required")
    if (!dir.exists()) return mediaErrorJson("NOT_FOUND", "路径不存在：${dir.absolutePath}")
    if (!dir.isDirectory) return mediaErrorJson("NOT_DIRECTORY", "路径不是目录：${dir.absolutePath}")

    val kind = params.mediaString("kind")?.lowercase() ?: "all"
    val recursive = params.mediaBool("recursive") ?: false
    val limit = (params.mediaInt("limit") ?: MEDIA_DEFAULT_LIST_LIMIT).coerceIn(1, 2000)

    val candidates = if (recursive) {
        dir.walkTopDown().maxDepth(32)
    } else {
        dir.listFiles()?.asSequence() ?: emptySequence()
    }
    val files = candidates
        .filter { it.isFile }
        .filter { file ->
            val ext = file.extension.lowercase()
            when (kind) {
                "image" -> ext in IMAGE_EXTENSIONS
                "video" -> ext in VIDEO_EXTENSIONS
                else -> ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
            }
        }
        .sortedBy { it.name.lowercase() }
        .take(limit)
        .toList()

    val entries = buildJsonArray {
        files.forEach { file ->
            add(buildJsonObject {
                put("name", file.name)
                put("path", file.absolutePath)
                put("type", if (file.extension.lowercase() in VIDEO_EXTENSIONS) "video" else "image")
                put("size", file.length())
                put("last_modified", file.lastModified())
            })
        }
    }
    return buildJsonObject {
        put("path", dir.absolutePath)
        put("kind", kind)
        put("count", entries.size)
        put("entries", entries)
    }.toString()
}

/** 读取图片的基本信息。 */
private fun actionImageInfo(params: JsonObject): String {
    val file = requireMediaFile(params) ?: return mediaErrorJson("MISSING_PATH", "path is required")
    if (!file.isFile) return mediaErrorJson("NOT_FOUND", "图片不存在：${file.absolutePath}")

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        return mediaErrorJson("DECODE_FAILED", "无法解析该图片：${file.absolutePath}")
    }
    return buildJsonObject {
        put("path", file.absolutePath)
        put("width", options.outWidth)
        put("height", options.outHeight)
        put("mime", options.outMimeType ?: "")
        put("size", file.length())
    }.toString()
}

/** 把图片作为可视图返回给模型。 */
private fun actionViewImage(params: JsonObject): List<UIMessagePart> {
    val paths = params.mediaStringArray("paths").ifEmpty {
        listOfNotNull(params.mediaString("path")?.takeIf { it.isNotBlank() })
    }
    if (paths.isEmpty()) {
        return listOf(UIMessagePart.Text(mediaErrorJson("MISSING_PATH", "需要提供 path 或 paths")))
    }
    val parts = mutableListOf<UIMessagePart>()
    val loaded = buildJsonArray {
        paths.forEach { raw ->
            val file = resolvePath(raw)
            if (file == null || !file.isFile) {
                add(buildJsonObject {
                    put("path", raw)
                    put("error", "文件不存在或无法访问")
                })
                return@forEach
            }
            add(buildJsonObject {
                put("path", file.absolutePath)
                put("size", file.length())
            })
            parts.add(UIMessagePart.Image(url = "file://${file.absolutePath}"))
        }
    }
    parts.add(0, UIMessagePart.Text(buildJsonObject { put("images", loaded) }.toString()))
    return parts
}

/** 编辑图片并另存为新文件。 */
private fun actionEditImage(params: JsonObject): List<UIMessagePart> {
    val file = requireMediaFile(params) ?: return listOf(UIMessagePart.Text(mediaErrorJson("MISSING_PATH", "path is required")))
    if (!file.isFile) return listOf(UIMessagePart.Text(mediaErrorJson("NOT_FOUND", "图片不存在：${file.absolutePath}")))

    val source = decodeScaled(file, MEDIA_PREVIEW_MAX)
        ?: return listOf(UIMessagePart.Text(mediaErrorJson("DECODE_FAILED", "无法解析该图片：${file.absolutePath}")))

    val format = params.mediaString("format")?.lowercase()?.takeIf { it == "png" } ?: "jpeg"
    val quality = (params.mediaInt("quality") ?: 92).coerceIn(1, 100)
    val output = params.mediaString("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(file.parentFile ?: File("."), "${file.nameWithoutExtension}.edited.${if (format == "png") "png" else "jpg"}")
    if (output.absolutePath == file.absolutePath) {
        return listOf(UIMessagePart.Text(mediaErrorJson("SAME_PATH", "输出路径不能与原图相同，请换一个 output。")))
    }

    var bitmap = source
    try {
        bitmap = applyColorAdjustments(bitmap, params)
        bitmap = applyOrientation(bitmap, params)
        bitmap = applyCrop(bitmap, params)
        bitmap = applyScale(bitmap, params)

        output.parentFile?.mkdirs()
        val compressFormat = if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        output.outputStream().use { stream -> bitmap.compress(compressFormat, quality, stream) }
    } catch (error: Throwable) {
        return listOf(UIMessagePart.Text(mediaErrorJson("EDIT_FAILED", "编辑失败：${error.message ?: error::class.simpleName}")))
    } finally {
        if (!source.isRecycled) source.recycle()
    }

    val parts = mutableListOf<UIMessagePart>()
    parts.add(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("input", file.absolutePath)
                put("output", output.absolutePath)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("size", output.length())
            }.toString()
        )
    )
    if (params.mediaBool("return_image") == true) {
        parts.add(UIMessagePart.Image(url = "file://${output.absolutePath}"))
    }
    return parts
}

/** 读取视频信息。 */
private fun actionVideoInfo(params: JsonObject): String {
    val file = requireMediaFile(params) ?: return mediaErrorJson("MISSING_PATH", "path is required")
    if (!file.isFile) return mediaErrorJson("NOT_FOUND", "视频不存在：${file.absolutePath}")

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        buildJsonObject {
            put("path", file.absolutePath)
            put("size", file.length())
            put("duration_ms", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L)
            put("width", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1)
            put("height", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1)
            put("rotation", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
            put("bitrate", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: -1L)
            put("mime", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "")
        }.toString()
    } catch (error: Throwable) {
        mediaErrorJson("READ_FAILED", "无法读取视频信息：${error.message ?: error::class.simpleName}")
    } finally {
        runCatching { retriever.release() }
    }
}

/** 抽取视频关键帧并返回给模型。 */
private fun actionVideoFrames(params: JsonObject): List<UIMessagePart> {
    val file = requireMediaFile(params) ?: return listOf(UIMessagePart.Text(mediaErrorJson("MISSING_PATH", "path is required")))
    if (!file.isFile) return listOf(UIMessagePart.Text(mediaErrorJson("NOT_FOUND", "视频不存在：${file.absolutePath}")))

    val outDir = params.mediaString("output_dir")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(file.parentFile ?: File("."), "${file.nameWithoutExtension}.frames")
    if (!outDir.exists() && !outDir.mkdirs()) {
        return listOf(UIMessagePart.Text(mediaErrorJson("MKDIR_FAILED", "无法创建输出目录：${outDir.absolutePath}")))
    }

    val retriever = MediaMetadataRetriever()
    val parts = mutableListOf<UIMessagePart>()
    try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val explicit = params.mediaDoubleArray("timestamps")
        val timestampsSeconds = explicit.ifEmpty {
            val count = (params.mediaInt("count") ?: MEDIA_DEFAULT_FRAME_COUNT).coerceIn(1, MEDIA_MAX_FRAME_COUNT)
            if (durationMs <= 0) {
                listOf(0.0)
            } else {
                (0 until count).map { index -> durationMs / 1000.0 * index / (count - 1).coerceAtLeast(1) }
            }
        }

        val saved = buildJsonArray {
            timestampsSeconds.forEachIndexed { index, seconds ->
                val timeUs = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong()
                val frame = runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (frame == null) {
                    add(buildJsonObject {
                        put("at_seconds", seconds)
                        put("error", "无法抽取该时间点的帧")
                    })
                    return@forEachIndexed
                }
                val target = File(outDir, "frame_%03d_%.2fs.jpg".format(index, seconds))
                runCatching {
                    target.outputStream().use { stream -> frame.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
                }
                if (!frame.isRecycled) frame.recycle()

                add(buildJsonObject {
                    put("at_seconds", seconds)
                    put("path", target.absolutePath)
                    put("size", target.length())
                })
                if (target.length() > 0) {
                    parts.add(UIMessagePart.Image(url = "file://${target.absolutePath}"))
                }
            }
        }

        parts.add(
            0,
            UIMessagePart.Text(
                buildJsonObject {
                    put("video", file.absolutePath)
                    put("duration_ms", durationMs)
                    put("output_dir", outDir.absolutePath)
                    put("frames", saved)
                }.toString()
            )
        )
    } catch (error: Throwable) {
        return listOf(UIMessagePart.Text(mediaErrorJson("FRAME_FAILED", "抽帧失败：${error.message ?: error::class.simpleName}")))
    } finally {
        runCatching { retriever.release() }
    }
    return parts
}

private fun requireMediaFile(params: JsonObject): File? = resolvePath(params.mediaString("path"))

/** 按采样率解码，避免大图 OOM。 */
private fun decodeScaled(file: File, maxSize: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(file.absolutePath, options)
}.getOrNull()

private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var size = 1
    while (width / size > maxSize || height / size > maxSize) size *= 2
    return size
}

/** 亮度 / 对比度 / 饱和度 / 灰度。 */
private fun applyColorAdjustments(source: Bitmap, params: JsonObject): Bitmap {
    val brightness = (params.mediaDouble("brightness") ?: 1.0).coerceIn(0.0, 3.0).toFloat()
    val contrast = (params.mediaDouble("contrast") ?: 1.0).coerceIn(0.0, 3.0).toFloat()
    val saturation = (params.mediaDouble("saturation") ?: 1.0).coerceIn(0.0, 3.0).toFloat()
    val grayscale = params.mediaBool("grayscale") ?: false
    if (!grayscale && brightness == 1f && contrast == 1f && saturation == 1f) return source

    val matrix = ColorMatrix()
    matrix.setSaturation(if (grayscale) 0f else saturation)
    val offset = (brightness - 1f) * 100f
    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    canvas.drawBitmap(source, 0f, 0f, paint)
    if (result != source) source.recycle()
    return result
}

/** 旋转 / 翻转。 */
private fun applyOrientation(source: Bitmap, params: JsonObject): Bitmap {
    val rotate = ((params.mediaInt("rotate") ?: 0) % 360 + 360) % 360
    val flipH = params.mediaBool("flip_h") ?: false
    val flipV = params.mediaBool("flip_v") ?: false
    if (rotate == 0 && !flipH && !flipV) return source

    val matrix = Matrix()
    if (rotate != 0) matrix.postRotate(rotate.toFloat())
    if (flipH || flipV) matrix.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)

    val result = runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }.getOrElse { return source }
    if (result != source) source.recycle()
    return result
}

/** 按比例裁剪。 */
private fun applyCrop(source: Bitmap, params: JsonObject): Bitmap {
    val crop = (params["crop"] as? JsonObject) ?: return source
    val left = (crop.mediaDouble("left") ?: 0.0).coerceIn(0.0, 1.0)
    val top = (crop.mediaDouble("top") ?: 0.0).coerceIn(0.0, 1.0)
    val right = (crop.mediaDouble("right") ?: 1.0).coerceIn(0.0, 1.0)
    val bottom = (crop.mediaDouble("bottom") ?: 1.0).coerceIn(0.0, 1.0)
    if (right <= left || bottom <= top) return source

    val x = (left * source.width).toInt().coerceIn(0, source.width - 1)
    val y = (top * source.height).toInt().coerceIn(0, source.height - 1)
    val width = ((right - left) * source.width).toInt().coerceIn(1, source.width - x)
    val height = ((bottom - top) * source.height).toInt().coerceIn(1, source.height - y)

    val result = runCatching { Bitmap.createBitmap(source, x, y, width, height) }.getOrElse { return source }
    if (result != source) source.recycle()
    return result
}

/** 缩放。 */
private fun applyScale(source: Bitmap, params: JsonObject): Bitmap {
    val scale = params.mediaDouble("scale")?.coerceIn(0.01, 8.0) ?: return source
    if (scale == 1.0) return source
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val result = runCatching {
        Bitmap.createScaledBitmap(source, width, height, true)
    }.getOrElse { return source }
    if (result != source) source.recycle()
    return result
}

private fun mediaErrorJson(code: String, message: String): String = buildJsonObject {
    put("error", code)
    put("message", message)
}.toString()

private fun JsonObject.mediaString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.mediaInt(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.mediaDouble(key: String): Double? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.mediaBool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.mediaStringArray(key: String): List<String> = runCatching {
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
}.getOrNull().orEmpty()

private fun JsonObject.mediaDoubleArray(key: String): List<Double> = runCatching {
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
}.getOrNull().orEmpty()