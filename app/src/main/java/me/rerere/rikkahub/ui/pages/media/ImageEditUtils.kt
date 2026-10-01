package me.rerere.rikkahub.ui.pages.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File

/** 按采样率解码图片，避免大图 OOM；maxSize 为解码后最长边的上限。 */
internal fun decodeScaledBitmap(file: File, maxSize: Int = 2048): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(file.absolutePath, options)
}.getOrNull()

private fun bitmapSampleSize(width: Int, height: Int, maxSize: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var size = 1
    while (width / size > maxSize || height / size > maxSize) size *= 2
    return size
}

/**
 * 依次执行调色 → 旋转/翻转 → 缩放，返回处理后的新 Bitmap。
 * 传入的 [source] 在过程中会被回收。
 */
internal fun applyImageEdits(
    source: Bitmap,
    rotate: Int,
    flipH: Boolean,
    flipV: Boolean,
    scale: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    grayscale: Boolean,
): Bitmap {
    var bitmap = applyColorAdjustments(source, brightness, contrast, saturation, grayscale)
    bitmap = applyOrientation(bitmap, rotate, flipH, flipV)
    bitmap = applyScaleFactor(bitmap, scale)
    return bitmap
}

private fun applyColorAdjustments(
    source: Bitmap,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    grayscale: Boolean,
): Bitmap {
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
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(result).drawBitmap(source, 0f, 0f, paint)
    if (result != source) source.recycle()
    return result
}

private fun applyOrientation(
    source: Bitmap,
    rotate: Int,
    flipH: Boolean,
    flipV: Boolean,
): Bitmap {
    val angle = ((rotate % 360) + 360) % 360
    if (angle == 0 && !flipH && !flipV) return source

    val matrix = Matrix()
    if (angle != 0) matrix.postRotate(angle.toFloat())
    if (flipH || flipV) matrix.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)

    val result = runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }.getOrElse { return source }
    if (result != source) source.recycle()
    return result
}

private fun applyScaleFactor(source: Bitmap, scale: Float): Bitmap {
    if (scale == 1f) return source
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val result = runCatching {
        Bitmap.createScaledBitmap(source, width, height, true)
    }.getOrElse { return source }
    if (result != source) source.recycle()
    return result
}