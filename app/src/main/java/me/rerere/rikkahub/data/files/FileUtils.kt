package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.uuid.Uuid

object FileUtils {
    private const val TAG = "FileUtils"

    /** 压缩包 / 安装包后缀：内容为二进制，不能按文本读取，需交给解包工具处理。 */
    val ARCHIVE_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "apkm", "aab",
        "zip", "jar", "aar", "war",
        "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4",
        "iso", "img",
    )

    /**
     * 系统 [MimeTypeMap] 认不出来的后缀 → MIME 兜底表。
     *
     * 主要覆盖 APK、各类压缩包与其它二进制产物：这些格式系统多返回 null，
     * 若直接退化成 text/plain，后续会被当成文本读取而读出乱码。
     */
    private val EXTRA_MIME_MAP = mapOf(
        // Android 安装包 / 打包产物
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/vnd.android.package-archive",
        "xapk" to "application/vnd.android.package-archive",
        "apkm" to "application/vnd.android.package-archive",
        "aab" to "application/zip",
        "dex" to "application/x-dex",
        "smali" to "text/plain",
        // 压缩包
        "zip" to "application/zip",
        "jar" to "application/java-archive",
        "aar" to "application/java-archive",
        "war" to "application/java-archive",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "zst" to "application/zstd",
        "lz4" to "application/x-lz4",
        // 其它常见二进制
        "so" to "application/octet-stream",
        "bin" to "application/octet-stream",
        "img" to "application/octet-stream",
        "iso" to "application/x-iso9660-image",
        // 文档
        "rtf" to "application/rtf",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
        // 纯文本类，补上系统库里缺失的后缀
        "log" to "text/plain",
        "conf" to "text/plain",
        "cfg" to "text/plain",
        "ini" to "text/plain",
        "env" to "text/plain",
        "gradle" to "text/plain",
        "kts" to "text/plain",
        "properties" to "text/plain",
        "yml" to "text/plain",
        "yaml" to "text/plain",
        "toml" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdx" to "text/markdown",
    )

    /** 按后缀推断 MIME；系统表查不到时回落到 [EXTRA_MIME_MAP]。查不到返回 null。 */
    fun mimeFromFileName(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == fileName) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: EXTRA_MIME_MAP[ext]
    }

    /** 该后缀是否属于「压缩包 / 安装包」这类可解包处理的二进制格式。 */
    fun isArchiveFileName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

    fun buildUuidFileName(displayName: String?, mimeType: String?): String {
        val extFromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it != displayName }
            ?.lowercase()
        val extFromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) }
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
        val ext = extFromName ?: extFromMime ?: "bin"
        return "${Uuid.random()}.$ext"
    }

    fun buildRelativePath(folder: String, file: File): String =
        "$folder/${file.name}"

    fun getRelativePathInFilesDir(filesDir: File, file: File): String? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val canonicalFilesDir = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
        val basePath = canonicalFilesDir.path
        val filePath = canonicalFile.path
        if (!filePath.startsWith("$basePath${File.separator}")) {
            return null
        }
        return canonicalFile.relativeTo(canonicalFilesDir).path.replace(File.separatorChar, '/')
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            var fileName: String? = null
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val documentDisplayNameIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (documentDisplayNameIndex != -1) {
                        fileName = cursor.getString(documentDisplayNameIndex)
                    } else {
                        val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (openableDisplayNameIndex != -1) {
                            fileName = cursor.getString(openableDisplayNameIndex)
                        }
                    }
                }
            }
            fileName
        }.onFailure {
            Log.w(TAG, "getFileNameFromUri: Failed to query display name for $uri", it)
        }.getOrNull()
    }

    fun getFileMimeType(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.getType(uri)
            }.onFailure {
                Log.w(TAG, "getFileMimeType: Failed to resolve MIME for $uri", it)
            }.getOrNull()
            else -> null
        }
    }

    fun guessMimeType(file: File, fileName: String): String {
        // 后缀优先（含 APK / 压缩包等系统表缺失的兜底），后缀无法判定时再嗅探字节
        mimeFromFileName(fileName)?.let { return it }
        return sniffMimeType(file)
    }

    fun compressBitmapToPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }

    private fun sniffMimeType(file: File): String {
        val header = ByteArray(16)
        val read = runCatching {
            FileInputStream(file).use { input ->
                input.read(header)
            }
        }.getOrDefault(-1)

        if (read <= 0) return "application/octet-stream"

        if (header.startsWithBytes(0x89, 0x50, 0x4E, 0x47)) return "image/png"
        if (header.startsWithBytes(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (header.startsWithBytes(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (header.startsWithBytes(0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        if (header.startsWithBytes(0x50, 0x4B, 0x03, 0x04)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x05, 0x06)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x07, 0x08)) return "application/zip"
        if (header.startsWithBytes(0x52, 0x49, 0x46, 0x46) && header.sliceArray(8..11)
                .contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }
        // HEIF/HEIC/AVIF: ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        if (read >= 12 && header.sliceArray(4..7).toString(Charsets.US_ASCII) == "ftyp") {
            when (header.sliceArray(8..11).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return "image/heic"

                "avif", "avis" -> return "image/avif"
            }
        }

        val textSample = runCatching {
            val sample = ByteArray(512)
            FileInputStream(file).use { input ->
                val len = input.read(sample)
                if (len <= 0) return@runCatching null
                sample.copyOf(len)
            }
        }.getOrNull()
        if (textSample != null && isLikelyText(textSample)) {
            return "text/plain"
        }

        return "application/octet-stream"
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        var printable = 0
        var total = 0
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            total += 1
            if (c == 0x09 || c == 0x0A || c == 0x0D) {
                printable += 1
            } else if (c in 0x20..0x7E) {
                printable += 1
            }
        }
        return total > 0 && printable.toDouble() / total >= 0.8
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (this.size < values.size) return false
        for (i in values.indices) {
            if ((this[i].toInt() and 0xFF) != values[i]) return false
        }
        return true
    }
}
