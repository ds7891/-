package me.rerere.rikkahub.ui.components.qqbot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把一段文本渲染成二维码。
 *
 * 用白底而不是主题色：二维码要拿另一台手机扫，对比度是硬要求，
 * 深色模式下用主题色反差点扫不出来。
 */
@Composable
fun QQBotQrCode(
    text: String,
    modifier: Modifier = Modifier,
    sizePx: Int = 512,
) {
    val bitmap = remember(text, sizePx) { generateQrBitmap(text, sizePx) }
    if (bitmap == null) {
        Box(modifier = modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "内容太长或为空，生成不了二维码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    Box(
        modifier = modifier
            .size(220.dp)
            .background(ComposeColor.White, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(200.dp)
                .padding(4.dp),
        )
    }
}

private fun generateQrBitmap(text: String, sizePx: Int): Bitmap? {
    if (text.isBlank()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // 留一格静区，贴着屏幕边缘扫会失败
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
