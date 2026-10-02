package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
private const val MEDIA_MAX_FRAME_COUNT = 600
private const val MEDIA_PREVIEW_MAX = 2048

/** 抽帧输出的最长边上限，避免 4K 视频抽帧时占满内存。 */
private const val MEDIA_FRAME_MAX_SIZE = 1280

/** 抽帧间隔下限（秒）：0.01 秒一帧，用于看运动过程。间隔上限不设限。 */
private const val MEDIA_MIN_FRAME_INTERVAL = 0.01

/** 一次抽帧默认最多落盘多少张，可用 max_frames 上调。 */
private const val MEDIA_DEFAULT_MAX_FRAMES = 60

/** 一次最多返回给模型查看多少张帧，其余只落盘并在 JSON 里给出路径。 */
private const val MEDIA_MAX_RETURNED_IMAGES = 24

/** 帧间隔密到这个值以下时不再做"换一帧重试"，避免密集抽帧时反复解码。 */
private const val MEDIA_DENSE_INTERVAL = 0.1

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
        - video_frames：抽帧，三种方式任选（优先级 timestamps > interval > count）：
          ① timestamps：[0, 2.5, 5] 按确切时间点抽；
          ② interval：按固定间隔抽，配合 start/end 指定区间。间隔最小 0.01 秒（逐帧看运动过程），
             上限不设限；看运动过程建议 1 秒一帧，普通浏览 2~5 秒，静态画面可给 30 秒以上；
          ③ count：不填前两者时整段均匀抽 N 帧，默认 6。
          每张图左上角会烧录"序号 + 时间戳"，返回的 JSON 里给出 mode / interval_seconds /
          effective_interval_seconds / requested_seconds / captured_seconds / path；
          帧数超过 max_frames（默认 60）会均匀降采样并给出 warning；帧数多时只把均匀挑选的
          最多 24 张返回给模型查看，其余仅落盘。若某张标了 duplicate_of，说明它与该路径画面完全相同
          （间隔小于视频帧率间隔属正常，否则可能是该时段静止），此时不要据此判定"视频没动"。
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
                    put("description", "video_frames 要抽帧的确切时间点（秒），例如 [0, 2.5, 5]；优先级最高。")
                    put("items", buildJsonObject { put("type", "number") })
                })
                put("interval", buildJsonObject {
                    put("type", "number")
                    put(
                        "description",
                        "video_frames 按固定间隔抽帧（秒），配合 start/end 使用。" +
                            "最低 0.01（用于逐帧看运动过程），最高不设限；" +
                            "看运动过程建议 1，普通浏览建议 2~5，静态画面可给 30 以上。",
                    )
                })
                put("start", buildJsonObject {
                    put("type", "number")
                    put("description", "video_frames 抽帧起始时间（秒），默认 0。")
                })
                put("end", buildJsonObject {
                    put("type", "number")
                    put("description", "video_frames 抽帧结束时间（秒），默认视频结尾。")
                })
                put("max_frames", buildJsonObject {
                    put("type", "integer")
                    put("description", "video_frames 本次最多落盘多少张帧，默认 60，最大 600；超出会均匀降采样。")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "video_frames 未指定 timestamps / interval 时，整段均匀抽取的帧数，默认 6，最大 600。")
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

/** 抽取视频帧并返回给模型；每个时间点都尽量抽到画面真正变化的那一帧。 */
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
        val durationSeconds = durationMs / 1000.0
        val maxFrames = (params.mediaInt("max_frames") ?: MEDIA_DEFAULT_MAX_FRAMES)
            .coerceIn(1, MEDIA_MAX_FRAME_COUNT)

        val plan = planFrameTimestamps(params, durationSeconds, maxFrames)
        val requestedSeconds = plan.timestamps
        if (requestedSeconds.isEmpty()) {
            return listOf(UIMessagePart.Text(mediaErrorJson("NO_FRAMES", "没有解析出任何抽帧时间点，请检查 timestamps / interval / count。")))
        }
        // 间隔很密时不再做"换一帧重试"，否则密集抽帧会被反复解码拖慢
        val allowNudge = plan.effectiveInterval <= 0.0 || plan.effectiveInterval >= MEDIA_DENSE_INTERVAL

        // 记录已抽出的画面指纹，用于发现"不同时间点抽到同一帧"
        val seenFingerprints = HashMap<String, String>()
        val frameEntries = mutableListOf<Pair<String, String>>() // 时间点 -> 图片路径
        val saved = buildJsonArray {
            requestedSeconds.forEachIndexed { index, seconds ->
                val requested = seconds.coerceAtLeast(0.0)
                val label = "%d/%d  %s".format(
                    index + 1,
                    requestedSeconds.size,
                    formatTimestamp(requested),
                )
                val capture = captureDistinctFrame(retriever, requested, durationSeconds, seenFingerprints, allowNudge)
                if (capture == null) {
                    add(buildJsonObject {
                        put("index", index + 1)
                        put("requested_seconds", requested)
                        put("error", "无法抽取该时间点的帧")
                    })
                    return@forEachIndexed
                }

                val target = File(outDir, "frame_%04d_at_%.2fs.jpg".format(index + 1, requested))
                val rendered = renderFrameForSave(capture.bitmap, label, MEDIA_FRAME_MAX_SIZE)
                runCatching {
                    target.outputStream().use { stream -> rendered.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
                }
                if (!rendered.isRecycled) rendered.recycle()

                val fingerprint = capture.fingerprint
                val duplicateOf = seenFingerprints[fingerprint]
                if (duplicateOf == null) seenFingerprints[fingerprint] = target.absolutePath

                add(buildJsonObject {
                    put("index", index + 1)
                    put("requested_seconds", requested)
                    put("captured_seconds", capture.capturedSeconds)
                    put("label", label)
                    put("path", target.absolutePath)
                    put("size", target.length())
                    // 同一帧被重复抽到时会标注出来，避免把静态画面误判成"视频没动"
                    if (duplicateOf != null) {
                        put("duplicate_of", duplicateOf)
                    }
                })
                if (target.length() > 0) {
                    frameEntries += formatTimestamp(requested) to target.absolutePath
                }
            }
        }

        // 帧数很多时只把均匀挑选的一部分返回给模型查看，其余落盘并在 JSON 里给出路径
        val returned = if (frameEntries.size <= MEDIA_MAX_RETURNED_IMAGES) {
            frameEntries
        } else {
            selectEvenly(frameEntries, MEDIA_MAX_RETURNED_IMAGES)
        }
        returned.forEach { (_, path) ->
            parts.add(UIMessagePart.Image(url = "file://$path"))
        }

        parts.add(
            0,
            UIMessagePart.Text(
                buildJsonObject {
                    put("video", file.absolutePath)
                    put("duration_ms", durationMs)
                    put("output_dir", outDir.absolutePath)
                    put("mode", plan.mode)
                    plan.intervalSeconds?.let { put("interval_seconds", it) }
                    plan.effectiveInterval.takeIf { it > 0 }?.let { put("effective_interval_seconds", it) }
                    plan.startSeconds?.let { put("start_seconds", it) }
                    plan.endSeconds?.let { put("end_seconds", it) }
                    put("requested_count", requestedSeconds.size)
                    put("saved_count", frameEntries.size)
                    put("returned_images", returned.size)
                    put("distinct_frames", seenFingerprints.size)
                    put("frames", saved)
                    if (plan.truncated) {
                        put(
                            "warning",
                            "按间隔请求的帧数超过 max_frames=${maxFrames}，已均匀降采样到 ${requestedSeconds.size} 张，" +
                                "实际间隔约 ${"%.2f".format(plan.effectiveInterval)} 秒。" +
                                "需要更密就缩小 start/end 范围或调大 max_frames。",
                        )
                    } else if (durationMs <= 0) {
                        put("warning", "未能读取视频时长，已按时间点 0 抽帧。")
                    } else if (seenFingerprints.size < requestedSeconds.size) {
                        put(
                            "warning",
                            "有多个时间点抽到完全相同的画面（见 duplicate_of）。" +
                                "间隔小于视频帧率间隔（如 30fps 约 0.033 秒）时属正常现象；" +
                                "否则可能是该时段画面静止。时间戳已烧录在每张图左上角，可据此核对。",
                        )
                    }
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

/** 抽帧计划：最终时间点序列与本次实际使用的间隔。 */
private class FramePlan(
    val timestamps: List<Double>,
    val mode: String,
    /** 用户请求的间隔（仅 interval 模式） */
    val intervalSeconds: Double? = null,
    /** 实际落盘时相邻帧的间隔，用于判断解码密度 */
    val effectiveInterval: Double = 0.0,
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
    val truncated: Boolean = false,
)

/**
 * 解析抽帧时间点，优先级：timestamps > interval > count。
 *
 * interval 支持低至 [MEDIA_MIN_FRAME_INTERVAL]（0.01 秒，用来看运动过程），上限不设限；
 * 生成的帧数超过 [maxFrames] 时均匀降采样，避免一次抽出海量图片。
 */
private fun planFrameTimestamps(params: JsonObject, durationSeconds: Double, maxFrames: Int): FramePlan {
    val explicit = params.mediaDoubleArray("timestamps")
    if (explicit.isNotEmpty()) {
        val sorted = explicit.map { it.coerceAtLeast(0.0) }.sorted()
        val kept = if (sorted.size > maxFrames) selectEvenly(sorted, maxFrames) else sorted
        return FramePlan(
            timestamps = kept,
            mode = "timestamps",
            effectiveInterval = kept.zipWithNext { a, b -> b - a }.minOrNull() ?: 0.0,
            truncated = kept.size < sorted.size,
        )
    }

    val interval = params.mediaDouble("interval")?.takeIf { it > 0 }
    if (interval != null) {
        val step = interval.coerceAtLeast(MEDIA_MIN_FRAME_INTERVAL)
        val start = (params.mediaDouble("start") ?: 0.0).coerceAtLeast(0.0)
        val end = params.mediaDouble("end")?.coerceAtLeast(start)
            ?: durationSeconds.takeIf { it > 0 }?.let { it.coerceAtMost(durationSeconds) }
            ?: start
        val last = (end - 0.001).coerceAtLeast(start)

        val wanted = (Math.floor((last - start) / step).toInt() + 1).coerceAtLeast(1)
        val truncated = wanted > maxFrames
        val actualStep = if (truncated) (last - start) / (maxFrames - 1).coerceAtLeast(1) else step
        val count = if (truncated) maxFrames else wanted
        val timestamps = (0 until count).map { index -> start + index * actualStep }

        return FramePlan(
            timestamps = timestamps,
            mode = "interval",
            intervalSeconds = step,
            effectiveInterval = actualStep,
            startSeconds = start,
            endSeconds = end,
            truncated = truncated,
        )
    }

    val count = (params.mediaInt("count") ?: MEDIA_DEFAULT_FRAME_COUNT).coerceIn(1, maxFrames)
    if (durationSeconds <= 0) {
        return FramePlan(timestamps = listOf(0.0), mode = "count", effectiveInterval = 0.0)
    }
    // 末帧取在时长略微靠前处，避免正好落在 EOF 抽不到画面
    val last = (durationSeconds - 0.05).coerceAtLeast(0.0)
    val timestamps = (0 until count).map { index -> last * index / (count - 1).coerceAtLeast(1) }
    return FramePlan(
        timestamps = timestamps,
        mode = "count",
        effectiveInterval = last / (count - 1).coerceAtLeast(1),
        endSeconds = durationSeconds,
    )
}

/** 从列表中均匀挑选 [limit] 个元素，首尾都会保留。 */
private fun <T> selectEvenly(items: List<T>, limit: Int): List<T> {
    if (items.size <= limit) return items
    if (limit <= 1) return listOf(items.first())
    return (0 until limit).map { index -> items[(index.toLong() * (items.size - 1) / (limit - 1)).toInt()] }
}

/** 一次抽帧的结果。 */
private class CapturedFrame(
    val bitmap: Bitmap,
    val fingerprint: String,
    /** 实际取到画面的时间点（可能因微调而略偏离请求值） */
    val capturedSeconds: Double,
)

/**
 * 在 [requestedSeconds] 附近取一帧，并尽量避开与已抽帧完全相同的画面。
 *
 * `getFrameAtTime(t, OPTION_CLOSEST_SYNC)` 只会退到最近的关键帧，关键帧稀疏时多个时间点会抽到同一帧；
 * 这里优先用 `OPTION_CLOSEST` 解码最接近的真实帧。
 * [allowNudge] 为 true 且仍与已抽帧重复时，再在当前时间点附近做小幅偏移重试；
 * 密集抽帧（间隔 < 0.1 秒）时关闭，避免同一时刻被反复解码拖慢。
 */
private fun captureDistinctFrame(
    retriever: MediaMetadataRetriever,
    requestedSeconds: Double,
    durationSeconds: Double,
    seenFingerprints: Map<String, String>,
    allowNudge: Boolean,
): CapturedFrame? {
    val upperBound = if (durationSeconds > 0) (durationSeconds - 0.001).coerceAtLeast(0.0) else Double.MAX_VALUE
    // 先取请求时间点，再向两侧微调，尽量落在同一秒内的真实画面上
    val offsets = if (allowNudge) {
        listOf(0.0, 0.04, -0.04, 0.12, -0.12, 0.3, -0.3, 0.6, -0.6)
    } else {
        listOf(0.0)
    }

    var fallback: CapturedFrame? = null
    offsets.forEach { offset ->
        val time = (requestedSeconds + offset).coerceIn(0.0, upperBound)
        if (time < 0) return@forEach
        val bitmap = frameAt(retriever, time) ?: return@forEach
        val fingerprint = bitmapFingerprint(bitmap)
        val candidate = CapturedFrame(bitmap, fingerprint, time)

        if (fingerprint !in seenFingerprints) {
            fallback?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            return candidate
        }
        if (fallback == null) {
            fallback = candidate
        } else if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
    return fallback
}

/** 优先解码最接近时间点的真实帧；退化为关键帧只作为兜底。 */
private fun frameAt(retriever: MediaMetadataRetriever, seconds: Double): Bitmap? {
    val timeUs = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong()
    return runCatching {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }.getOrNull() ?: runCatching {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()
}

/** 用 16x16 缩略图的像素做指纹，判断两张帧是否是同一画面。 */
private fun bitmapFingerprint(bitmap: Bitmap): String {
    val size = 16
    val thumbnail = runCatching { Bitmap.createScaledBitmap(bitmap, size, size, true) }.getOrNull() ?: return ""
    val pixels = IntArray(size * size)
    thumbnail.getPixels(pixels, 0, size, 0, 0, size, size)
    if (thumbnail !== bitmap && !thumbnail.isRecycled) thumbnail.recycle()
    var hash = 17L
    pixels.forEach { hash = hash * 31 + it }
    return hash.toString()
}

/**
 * 缩放并在左上角烧录时间戳后返回新图，源图会被回收。
 *
 * 时间戳直接画在画面上：一方面人眼能对上时间，另一方面视觉模型看到图片时也能读出对应时刻。
 */
private fun renderFrameForSave(source: Bitmap, label: String, maxSize: Int): Bitmap {
    val longest = maxOf(source.width, source.height).coerceAtLeast(1)
    val ratio = if (longest > maxSize) maxSize.toFloat() / longest else 1f
    val width = (source.width * ratio).toInt().coerceAtLeast(1)
    val height = (source.height * ratio).toInt().coerceAtLeast(1)

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(result).drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    if (!source.isRecycled) source.recycle()

    val textSize = (height / 16f).coerceIn(20f, 72f)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.textSize = textSize
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(textSize / 6f, 0f, 0f, android.graphics.Color.BLACK)
    }
    val padding = textSize * 0.45f
    val barWidth = (labelPaint.measureText(label) + padding * 2).coerceAtMost(width.toFloat())
    Canvas(result).apply {
        drawRect(
            0f,
            0f,
            barWidth,
            textSize + padding * 2,
            Paint().apply { color = android.graphics.Color.argb(150, 0, 0, 0) },
        )
        drawText(label, padding, textSize + padding, labelPaint)
    }
    return result
}

/** 把秒数格式化成 mm:ss.SS。 */
private fun formatTimestamp(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0)
    val minutes = (safe / 60).toInt()
    return "%02d:%05.2f".format(minutes, safe - minutes * 60)
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