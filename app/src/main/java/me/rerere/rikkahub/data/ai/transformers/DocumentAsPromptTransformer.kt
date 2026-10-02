package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.files.FileUtils
import java.io.File

/** 明确属于二进制的 MIME：内容不能按文本读取。 */
private val BINARY_MIME_TYPES = setOf(
    "application/vnd.android.package-archive",
    "application/x-dex",
    "application/zip",
    "application/x-zip-compressed",
    "application/java-archive",
    "application/x-7z-compressed",
    "application/vnd.rar",
    "application/x-rar-compressed",
    "application/x-tar",
    "application/gzip",
    "application/x-gzip",
    "application/x-bzip2",
    "application/x-xz",
    "application/zstd",
    "application/x-lz4",
    "application/x-iso9660-image",
    "application/octet-stream",
)

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val path = resolveWorkspacePath(document)
                                val content = readDocumentContent(document, path)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document, workspacePath: String?): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }

        // 压缩包 / 安装包等二进制文件不能按文本读取，否则会读出一堆乱码并挤爆上下文。
        // 这里只给出元信息与工作区路径，让 AI 用 file_system / apk_tool 等工具去读取或解包。
        if (isBinaryDocument(document, file)) {
            return binaryPlaceholder(document, file, workspacePath)
        }

        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> readTextCapped(file)
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }

    /** 超过该字节数的文本只截取开头部分，避免超大日志/源码挤爆上下文。 */
    private const val MAX_INLINE_TEXT_BYTES = 256 * 1024

    private fun readTextCapped(file: File): String {
        if (file.length() <= MAX_INLINE_TEXT_BYTES) return file.readText()
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_INLINE_TEXT_BYTES)
            val read = input.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
        }
        return buildString {
            append(head)
            append("\n\n[内容过长已截断，仅显示前 ")
            append(MAX_INLINE_TEXT_BYTES / 1024)
            append(" KB，共 ")
            append(file.length() / 1024)
            append(" KB。需要完整内容请用工作区工具按路径读取]")
        }
    }

    /**
     * 判断附件是否为二进制。
     *
     * 先看 MIME 是否属于已知的压缩包/二进制类型，再嗅探文件头（含 NUL 字节或大量控制字符即视为二进制），
     * 这样即使媒体库把 APK 报成 application/octet-stream 或 text/plain 也能兜住。
     */
    private fun isBinaryDocument(document: UIMessagePart.Document, file: File): Boolean {
        if (FileUtils.isArchiveFileName(document.fileName)) return true
        val mime = document.mime.substringBefore(';').trim().lowercase()
        if (mime in BINARY_MIME_TYPES) return true
        return looksBinary(file)
    }

    private fun looksBinary(file: File): Boolean {
        val sample = ByteArray(2048)
        val read = runCatching {
            file.inputStream().use { it.read(sample) }
        }.getOrDefault(-1)
        if (read <= 0) return false
        var controlChars = 0
        for (i in 0 until read) {
            val c = sample[i].toInt() and 0xFF
            if (c == 0x00) return true
            if (c < 0x09 || (c in 0x0E..0x1F)) controlChars++
        }
        return controlChars.toDouble() / read > 0.05
    }

    private fun binaryPlaceholder(
        document: UIMessagePart.Document,
        file: File,
        workspacePath: String?,
    ): String {
        val ext = document.fileName.substringAfterLast('.', "").lowercase()
        val sizeText = when {
            file.length() >= 1024 * 1024 -> "%.1f MB".format(file.length() / 1024.0 / 1024.0)
            file.length() >= 1024 -> "%.1f KB".format(file.length() / 1024.0)
            else -> "${file.length()} B"
        }
        val pathHint = workspacePath?.let { " path=\"$it\"" } ?: ""
        val toolHint = when (ext) {
            "apk", "apks", "xapk", "apkm", "aab", "dex" ->
                "这是 Android 安装包/编译产物，可用 apk_tool（list / disasm / replace / asm / sign）配合 file_system 读取、修改并重新打包。"
            "zip", "jar", "aar", "war", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "iso", "img" ->
                "这是压缩包/归档文件，可用工作区命令解压后再用 file_system 逐个处理。"
            else -> "这是二进制文件，可用 file_system（encoding=base64 读写）或工作区命令处理。"
        }
        return buildString {
            append("[二进制文件，未内联内容。大小 $sizeText，MIME=${document.mime}]\n")
            append(toolHint)
            if (pathHint.isEmpty()) {
                append("\n未能解析出工作区路径，请通过工作区文件列表查找该文件。")
            } else {
                append("\n工作区路径：")
                append(workspacePath)
            }
        }
    }
}
