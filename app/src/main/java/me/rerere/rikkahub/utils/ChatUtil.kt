package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip",
    // 安装包：可交给 apk_tool 读取 / 改 smali / 重签 / 打包
    "application/vnd.android.package-archive",
    "application/x-dex",
    // 压缩包：可交给工作区工具解包处理
    "application/zip", "application/x-zip-compressed",
    "application/java-archive",
    "application/x-7z-compressed",
    "application/vnd.rar", "application/x-rar-compressed",
    "application/x-tar",
    "application/gzip", "application/x-gzip",
    "application/x-bzip2",
    "application/x-xz",
    "application/zstd",
    "application/x-lz4",
    "application/x-iso9660-image",
    // Office 开放文档 / 其它文档
    "application/rtf",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    // 系统对未知二进制常返回该类型；内容不会被当作文本读取，只作为文件交给 AI
    "application/octet-stream",
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml", "agc",
    "py", "rb", "lua", "sql", "java", "kt", "ts", "tsx",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "rs", "cs", "markdown", "mdx",
    "toml", "ini", "env", "gradle", "kts", "properties",
    "proto", "graphql", "gql", "yml", "yaml",
    // 安装包 / 打包产物
    "apk", "apks", "xapk", "apkm", "aab", "dex", "smali", "jar", "aar", "war",
    // 压缩包
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "iso", "img",
    // 其它常见格式
    "log", "conf", "cfg", "lock", "rtf", "odt", "ods", "odp", "epub",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "so", "bin", "sqlite", "db", "ttf", "otf", "woff", "woff2",
)

fun isAllowedFileType(fileName: String, mime: String): Boolean {
    val normalizedMime = mime.substringBefore(';').trim().lowercase()
    if (normalizedMime in ALLOWED_MIME_TYPES || normalizedMime.startsWith("text/")) return true
    if (normalizedMime.startsWith("image/") ||
        normalizedMime.startsWith("video/") ||
        normalizedMime.startsWith("audio/")
    ) {
        return true
    }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in ALLOWED_FILE_EXTENSIONS || extension in FileUtils.ARCHIVE_EXTENSIONS
}
