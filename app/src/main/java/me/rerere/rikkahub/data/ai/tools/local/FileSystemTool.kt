package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Base64
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

/** 本机文件系统工具的注册名。 */
const val FILE_SYSTEM_TOOL = "file_system"

/** 会改动磁盘内容的操作，执行前需用户确认。 */
private val DESTRUCTIVE_ACTIONS = setOf("write", "append", "delete", "move", "copy")

private const val DEFAULT_MAX_BYTES = 256 * 1024
private const val HARD_MAX_BYTES = 4 * 1024 * 1024
private const val DEFAULT_LIST_LIMIT = 200
private const val DEFAULT_SEARCH_LIMIT = 100
private const val MAX_WALK_DEPTH = 64

/** 内部共享存储根目录（/storage/emulated/0），也是相对路径与 ~ / /sdcard 的基准。 */
private val EXTERNAL_ROOT: String by lazy { Environment.getExternalStorageDirectory().absolutePath }

/**
 * 本机文件系统工具：让 AI 像文件管理器（如 MT 管理器）一样访问本机全部存储目录。
 *
 * 需要在本地工具设置中开启"文件系统"，并在系统里授予"所有文件访问权限"
 * （Android 11+ 为 MANAGE_EXTERNAL_STORAGE，Android 11 以下为读写外部存储）。
 */
internal fun buildFileSystemTool(context: Context): Tool = Tool(
    name = FILE_SYSTEM_TOOL,
    description = """
        直接读写本机存储上的文件与文件夹，等同于一个文件管理器（如 MT 管理器）。
        只要系统已授予"所有文件访问权限"，你就能访问本机全部存储目录，不要以"无法访问本机文件"为由拒绝用户，可以直接用本工具操作：
        - 内部共享存储根目录：$EXTERNAL_ROOT（等价于 /sdcard，也是相对路径的基准）
        - 外置 SD 卡 / U 盘 / OTG：/storage/<卷 ID>，可用 action=roots 列出全部可用卷及其容量
        - 其它绝对路径：例如 /data/data/<包名> 等（能否访问取决于系统权限）
        path 支持绝对路径、相对路径（相对内部共享存储根目录）、以及 ~ 与 /sdcard 前缀。
        常用动作：roots（列出所有存储卷）、list（列目录）、read（读文件）、write（写文件）、append（追加）、
        mkdir（建目录）、delete（删除）、move（移动/重命名）、copy（复制）、exists（是否存在）、
        stat（详细信息）、search（按文件名搜索）。
        read/write/append 默认按 UTF-8 文本处理，可用 encoding=base64 读写二进制；
        write/append/delete/move/copy 会改动磁盘，需用户确认后才执行。
    """.trimIndent().replace("\n", " "),
    needsApproval = { args ->
        val action = runCatching {
            args.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        action in DESTRUCTIVE_ACTIONS
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
                                "roots", "list", "read", "write", "append", "mkdir",
                                "delete", "move", "copy", "exists", "stat", "search",
                            ).forEach { add(it) }
                        }
                    )
                    put("description", "要执行的操作。")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "目标路径；除 roots 外必填。")
                })
                put("path2", buildJsonObject {
                    put("type", "string")
                    put("description", "move/copy 的目标路径（若是目录，则移动/复制到该目录内）。")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "write/append 要写入的内容。")
                })
                put("encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "read/write/append 的编码：text（默认，UTF-8）或 base64。")
                })
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "list/search/delete 是否递归；删除非空目录时必须为 true。")
                })
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "search 时用于匹配文件名的关键字（忽略大小写，支持 * 与 ? 通配符）。")
                })
                put("include_dirs", buildJsonObject {
                    put("type", "boolean")
                    put("description", "search 时是否把目录也算作结果，默认 false（只搜文件）。")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "list/search 返回的最大条目数。")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "read 时最多读取的字节数，默认 262144。")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "write/copy/move 目标已存在时是否覆盖，默认 true。")
                })
                put("create_parents", buildJsonObject {
                    put("type", "boolean")
                    put("description", "目标父目录不存在时是否自动创建，默认 true。")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val output = if (!context.hasAllFilesAccess()) {
            errorJson(
                "NO_PERMISSION",
                "尚未获得\"所有文件访问权限\"。请用户在助手的本地工具设置中开启\"文件系统\"并授予存储权限后重试。",
            )
        } else {
            runCatching { runFileAction(args.jsonObject) }
                .getOrElse { errorJson("TOOL_ERROR", "执行失败：${it.message ?: it::class.simpleName}") }
        }
        listOf(UIMessagePart.Text(output))
    },
)

private fun runFileAction(params: JsonObject): String {
    val action = params.stringOrNull("action")?.trim().orEmpty()
    return when (action) {
        "roots" -> actionRoots()
        "list" -> actionList(params)
        "read" -> actionRead(params)
        "write" -> actionWrite(params, append = false)
        "append" -> actionWrite(params, append = true)
        "mkdir" -> actionMkdir(params)
        "delete" -> actionDelete(params)
        "move" -> actionTransfer(params, copy = false)
        "copy" -> actionTransfer(params, copy = true)
        "exists" -> actionExists(params)
        "stat" -> actionStat(params)
        "search" -> actionSearch(params)
        else -> errorJson("UNKNOWN_ACTION", "未知的 action：'$action'")
    }
}

/** 列出所有可用存储卷（内部共享存储 + 外置 SD 卡 / OTG）。 */
private fun actionRoots(): String {
    val roots = buildJsonArray {
        add(volumeJson(File(EXTERNAL_ROOT), "内部共享存储"))
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("emulated", "self") }
            ?.sortedBy { it.name }
            ?.forEach { add(volumeJson(it, "外部存储卷")) }
    }
    return buildJsonObject { put("roots", roots) }.toString()
}

private fun volumeJson(dir: File, label: String): JsonObject = buildJsonObject {
    put("path", dir.absolutePath)
    put("label", label)
    put("readable", dir.canRead())
    put("writable", dir.canWrite())
    runCatching {
        val stat = StatFs(dir.absolutePath)
        put("total_bytes", stat.totalBytes)
        put("available_bytes", stat.availableBytes)
    }
}

private fun actionList(params: JsonObject): String {
    val dir = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    if (!dir.exists()) return errorJson("NOT_FOUND", "路径不存在：${dir.absolutePath}")
    if (!dir.isDirectory) return errorJson("NOT_DIRECTORY", "路径不是目录：${dir.absolutePath}")
    if (!dir.canRead()) return errorJson("NO_ACCESS", "无读取权限：${dir.absolutePath}")

    val recursive = params.boolOrNull("recursive") ?: false
    val limit = (params.intOrNull("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, 2000)
    val children: List<File> = if (recursive) {
        dir.walkTopDown().maxDepth(MAX_WALK_DEPTH).filter { it != dir }.take(limit).toList()
    } else {
        dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.take(limit)
            ?: emptyList()
    }
    val entries = buildJsonArray { children.forEach { add(fileJson(it)) } }
    return buildJsonObject {
        put("path", dir.absolutePath)
        put("count", entries.size)
        put("entries", entries)
    }.toString()
}

private fun actionRead(params: JsonObject): String {
    val file = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    if (!file.exists()) return errorJson("NOT_FOUND", "文件不存在：${file.absolutePath}")
    if (file.isDirectory) return errorJson("IS_DIRECTORY", "目标是目录，请用 action=list：${file.absolutePath}")
    if (!file.canRead()) return errorJson("NO_ACCESS", "无读取权限：${file.absolutePath}")

    val encoding = encodingOf(params)
    val maxBytes = (params.intOrNull("max_bytes") ?: DEFAULT_MAX_BYTES).coerceIn(1, HARD_MAX_BYTES)
    val total = file.length()
    val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            val n = input.read(buffer, read, maxBytes - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    }
    return buildJsonObject {
        put("path", file.absolutePath)
        put("size", total)
        put("truncated", bytes.size.toLong() < total)
        put("encoding", encoding)
        if (encoding == "base64") {
            put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } else {
            put("content", String(bytes, Charsets.UTF_8))
        }
    }.toString()
}

private fun actionWrite(params: JsonObject, append: Boolean): String {
    val file = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    val content = params.stringOrNull("content")
        ?: return errorJson("MISSING_CONTENT", "content is required")
    if (file.isDirectory) return errorJson("IS_DIRECTORY", "目标是目录，无法写入：${file.absolutePath}")

    if (params.boolOrNull("create_parents") ?: true) {
        file.parentFile?.mkdirs()
    }
    val overwrite = params.boolOrNull("overwrite") ?: true
    if (!append && !overwrite && file.exists()) {
        return errorJson("ALREADY_EXISTS", "文件已存在且 overwrite=false：${file.absolutePath}")
    }

    val bytes = runCatching { decodeContent(content, encodingOf(params)) }
        .getOrElse { return errorJson("INVALID_CONTENT", "内容解码失败：${it.message ?: it::class.simpleName}") }
    runCatching {
        if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
    }.onFailure {
        return errorJson("WRITE_FAILED", "写入失败：${it.message ?: it::class.simpleName}")
    }
    return buildJsonObject {
        put("success", true)
        put("path", file.absolutePath)
        put("bytes_written", bytes.size)
        put("append", append)
    }.toString()
}

private fun actionMkdir(params: JsonObject): String {
    val dir = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    if (dir.isDirectory) {
        return buildJsonObject {
            put("success", true)
            put("path", dir.absolutePath)
            put("created", false)
            put("message", "目录已存在。")
        }.toString()
    }
    val created = dir.mkdirs()
    return if (created || dir.isDirectory) {
        buildJsonObject {
            put("success", true)
            put("path", dir.absolutePath)
            put("created", true)
        }.toString()
    } else {
        errorJson("MKDIR_FAILED", "创建目录失败：${dir.absolutePath}")
    }
}

private fun actionDelete(params: JsonObject): String {
    val target = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    if (!target.exists()) return errorJson("NOT_FOUND", "路径不存在：${target.absolutePath}")

    val recursive = params.boolOrNull("recursive") ?: false
    val success = if (target.isDirectory) {
        if (!recursive && (target.list()?.isNotEmpty() == true)) {
            return errorJson("NOT_EMPTY", "目录非空，删除需 recursive=true：${target.absolutePath}")
        }
        target.deleteRecursively()
    } else {
        target.delete()
    }
    return if (success) {
        buildJsonObject {
            put("success", true)
            put("path", target.absolutePath)
        }.toString()
    } else {
        errorJson("DELETE_FAILED", "删除失败：${target.absolutePath}")
    }
}

private fun actionTransfer(params: JsonObject, copy: Boolean): String {
    val source = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    val destRaw = params.stringOrNull("path2")
        ?: return errorJson("MISSING_PATH2", "path2 is required")
    if (!source.exists()) return errorJson("NOT_FOUND", "源路径不存在：${source.absolutePath}")

    var dest = resolvePath(destRaw) ?: return errorJson("MISSING_PATH2", "path2 is required")
    if (dest.isDirectory) dest = File(dest, source.name)
    if (params.boolOrNull("create_parents") ?: true) dest.parentFile?.mkdirs()

    val overwrite = params.boolOrNull("overwrite") ?: true
    if (dest.exists() && !overwrite) {
        return errorJson("ALREADY_EXISTS", "目标已存在且 overwrite=false：${dest.absolutePath}")
    }
    if (dest.absolutePath == source.absolutePath) {
        return errorJson("SAME_PATH", "源与目标是同一路径。")
    }

    runCatching {
        if (copy) {
            source.copyRecursively(dest, overwrite = overwrite)
        } else if (!source.renameTo(dest)) {
            source.copyRecursively(dest, overwrite = overwrite)
            source.deleteRecursively()
        }
    }.onFailure {
        return errorJson("TRANSFER_FAILED", "${if (copy) "复制" else "移动"}失败：${it.message ?: it::class.simpleName}")
    }
    return buildJsonObject {
        put("success", true)
        put("action", if (copy) "copy" else "move")
        put("from", source.absolutePath)
        put("to", dest.absolutePath)
    }.toString()
}

private fun actionExists(params: JsonObject): String {
    val target = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    return buildJsonObject {
        put("path", target.absolutePath)
        put("exists", target.exists())
        put("is_dir", target.isDirectory)
    }.toString()
}

private fun actionStat(params: JsonObject): String {
    val target = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    return buildJsonObject {
        put("path", target.absolutePath)
        put("exists", target.exists())
        put("is_dir", target.isDirectory)
        put("is_file", target.isFile)
        put("size", target.length())
        put("last_modified", target.lastModified())
        put("readable", target.canRead())
        put("writable", target.canWrite())
        put("parent", target.parent ?: "")
        if (target.isDirectory) {
            put("child_count", target.list()?.size ?: 0)
        }
    }.toString()
}

private fun actionSearch(params: JsonObject): String {
    val start = resolvePath(params.stringOrNull("path"))
        ?: return errorJson("MISSING_PATH", "path is required")
    if (!start.isDirectory) return errorJson("NOT_DIRECTORY", "搜索起点需为目录：${start.absolutePath}")
    val pattern = params.stringOrNull("pattern")?.trim()
    if (pattern.isNullOrBlank()) return errorJson("MISSING_PATTERN", "pattern is required")

    val recursive = params.boolOrNull("recursive") ?: true
    val includeDirs = params.boolOrNull("include_dirs") ?: false
    val limit = (params.intOrNull("limit") ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, 2000)
    val matcher = nameMatcher(pattern)

    val candidates: Sequence<File> = if (recursive) {
        start.walkTopDown().maxDepth(MAX_WALK_DEPTH)
    } else {
        start.listFiles()?.asSequence() ?: emptySequence()
    }
    val matches = candidates
        .filter { it != start }
        .filter { includeDirs || !it.isDirectory }
        .filter { matcher.matches(it.name) }
        .take(limit)
        .toList()

    val results = buildJsonArray { matches.forEach { add(fileJson(it)) } }
    return buildJsonObject {
        put("path", start.absolutePath)
        put("pattern", pattern)
        put("count", results.size)
        put("results", results)
    }.toString()
}

private fun fileJson(file: File): JsonObject = buildJsonObject {
    put("name", file.name)
    put("path", file.absolutePath)
    put("is_dir", file.isDirectory)
    put("size", if (file.isDirectory) 0L else file.length())
    put("last_modified", file.lastModified())
}

internal fun resolvePath(raw: String?): File? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    var path = text
    if (path == "~") {
        path = EXTERNAL_ROOT
    } else if (path.startsWith("~/")) {
        path = EXTERNAL_ROOT + path.removePrefix("~")
    }
    if (path == "/sdcard") {
        path = EXTERNAL_ROOT
    } else if (path.startsWith("/sdcard/")) {
        path = EXTERNAL_ROOT + path.removePrefix("/sdcard")
    }
    val file = File(path)
    return if (file.isAbsolute) file else File(EXTERNAL_ROOT, path)
}

private fun nameMatcher(pattern: String): Regex {
    val sb = StringBuilder()
    pattern.forEach { char ->
        when (char) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(char.toString()))
        }
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}

private fun encodingOf(params: JsonObject): String =
    params.stringOrNull("encoding")?.lowercase()?.takeIf { it == "base64" } ?: "text"

private fun decodeContent(content: String, encoding: String): ByteArray =
    if (encoding == "base64") {
        Base64.decode(content, Base64.DEFAULT)
    } else {
        content.toByteArray(Charsets.UTF_8)
    }

internal fun errorJson(code: String, message: String): String = buildJsonObject {
    put("error", code)
    put("message", message)
}.toString()

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()