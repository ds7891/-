package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Base64
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** APK 工具箱的注册名。 */
const val APK_TOOL = "apk_tool"

/** 会改动磁盘内容的操作，执行前需用户确认。 */
private val DESTRUCTIVE_ACTIONS = setOf("extract", "disasm", "asm", "replace", "delete", "sign")

private const val DEFAULT_LIST_LIMIT = 300
private const val DEFAULT_READ_BYTES = 512 * 1024
private const val HARD_READ_BYTES = 8 * 1024 * 1024
private const val DEFAULT_SMALI_LIMIT = 200

/** baksmali 2.5.2 支持的最高 ART 版本对应 Android 11（API 30），再高会饱和，因此固定用它以覆盖全部已知指令。 */
private const val DEX_API_LEVEL = 30

/** 内置测试签名证书（assets 内），密码与别名同为 rikkahub。 */
private const val BUILTIN_KEYSTORE_ASSET = "apk/debug_signer.p12"
private const val BUILTIN_KEYSTORE_PASSWORD = "rikkahub"
private const val BUILTIN_KEY_ALIAS = "rikkahub"

/**
 * APK 工具箱：查看 APK 信息、浏览/读取内部文件、反编译 dex 为 smali、替换或删除内部文件、重新签名。
 *
 * 需要在本地工具设置中开启"APK 工具"，并在系统里授予"所有文件访问权限"。
 * 改动 APK（replace/delete）会让原有签名失效，需要随后用 action=sign 重新签名。
 */
internal fun buildApkTool(context: Context): Tool = Tool(
    name = APK_TOOL,
    description = """
        对 APK 安装包做逆向与改包，相当于一个精简的 APK 编辑器 + 签名工具：
        - info：查看 APK 信息，包含包名、版本号、minSdk/targetSdk、权限、四大组件、入口 Activity、签名信息（证书指纹）等
        - list：列出 APK 内部的所有文件（相当于打开压缩包查看目录）
        - read：读取 APK 内部某个文件的内容；若为二进制 AndroidManifest.xml 会自动还原为可读 XML
        - smali：快速查看 dex 反编译结果。只传 dex 时列出全部类名；再传 class_name 时输出该类的 smali 代码
        - disasm：把一个 dex 完整反编译到目录，产出可编辑的 smali 工程
        - asm：把编辑过的 smali 目录回编译成 dex
        - extract：把 APK 内部文件解压到本机目录
        - replace：用本机文件（source）或内联内容（content）替换/新增 APK 内部的某个文件
        - delete：删除 APK 内部的文件（例如旧的 META-INF 签名文件）
        - sign：对 APK 重新签名（默认使用内置测试证书，也可指定自己的 keystore）
        改代码的完整流程：disasm 把 classes.dex 反编译到 smali 目录 → 用 file_system 编辑目录里的 .smali 文件
        → asm 把 smali 目录回编译成新 dex → replace 用新 dex 覆盖 APK 里的 classes.dex → sign 重新签名。
        改资源或配置的流程：extract 整体解包 → 用 file_system 改文件 → replace 写回 → sign。
        多 dex 的 APK 请对每个 classesN.dex 分别执行 disasm/asm。
        注意：replace/delete 会破坏原签名，改完后必须用 sign 重新签名，产物才能安装。
        path 支持绝对路径、相对路径（相对内部共享存储根目录）与 /sdcard 前缀。
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
                                "info", "list", "read", "smali", "disasm", "asm",
                                "extract", "replace", "delete", "sign",
                            ).forEach { add(it) }
                        }
                    )
                    put("description", "要执行的操作。")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "目标 APK 文件路径，所有操作必填。")
                })
                put("entry", buildJsonObject {
                    put("type", "string")
                    put("description", "APK 内部的条目路径，例如 AndroidManifest.xml、classes.dex、res/layout/main.xml、lib/arm64-v8a/libx.so。")
                })
                put("entries", buildJsonObject {
                    put("type", "array")
                    put("description", "delete 时要删除的多个条目路径。")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("dex", buildJsonObject {
                    put("type", "string")
                    put("description", "smali/disasm 时指定要处理的 dex 条目，默认 classes.dex。")
                })
                put("smali_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "asm 时要回编译的 smali 目录（disasm 的输出目录）。")
                })
                put("class_name", buildJsonObject {
                    put("type", "string")
                    put("description", "smali 时要查看的类名，形如 com.example.MainActivity 或 Lcom/example/MainActivity;。留空则只列出类名。")
                })
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "replace 时本机用于替换的源文件路径。")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "replace 时直接写入的内联内容（与 source 二选一）。")
                })
                put("encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "replace/read 的编码：text（默认，UTF-8）或 base64。")
                })
                put("output", buildJsonObject {
                    put("type", "string")
                    put("description", "输出路径：replace/delete/sign 输出新的 APK 文件；extract 输出目录；disasm 输出 smali 目录；asm 输出 dex 文件。默认在 APK 同目录下自动生成。")
                })
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "list 时按条目名过滤的关键字（忽略大小写，支持 * 与 ? 通配符）。")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "list/smali 返回的最大条目数。")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "read 时最多读取的字节数，默认 524288。")
                })
                put("keystore", buildJsonObject {
                    put("type", "string")
                    put("description", "sign 时自定义签名库文件路径（.p12/.jks）。不填则使用内置测试证书。")
                })
                put("store_password", buildJsonObject {
                    put("type", "string")
                    put("description", "sign 时自定义签名库口令。")
                })
                put("key_alias", buildJsonObject {
                    put("type", "string")
                    put("description", "sign 时自定义签名库中的密钥别名。")
                })
                put("key_password", buildJsonObject {
                    put("type", "string")
                    put("description", "sign 时自定义密钥口令，缺省与 store_password 相同。")
                })
            },
            required = listOf("action", "path"),
        )
    },
    execute = { args ->
        val output = if (!context.hasAllFilesAccess()) {
            errorJson(
                "NO_PERMISSION",
                "尚未获得\"所有文件访问权限\"。请用户在助手的本地工具设置中开启\"APK 工具\"并授予存储权限后重试。",
            )
        } else {
            runCatching { runApkAction(context, args.jsonObject) }
                .getOrElse { errorJson("TOOL_ERROR", "执行失败：${it.message ?: it::class.simpleName}") }
        }
        listOf(UIMessagePart.Text(output))
    },
)

private fun runApkAction(context: Context, params: JsonObject): String {
    val action = params.stringOrNull("action")?.trim().orEmpty()
    return when (action) {
        "info" -> actionInfo(params)
        "list" -> actionList(params)
        "read" -> actionRead(params)
        "smali" -> actionSmali(params)
        "disasm" -> actionDisasm(params)
        "asm" -> actionAsm(params)
        "extract" -> actionExtract(params)
        "replace" -> actionReplace(params)
        "delete" -> actionDelete(params)
        "sign" -> actionSign(context, params)
        else -> errorJson("UNKNOWN_ACTION", "未知的 action：'$action'")
    }
}

private fun requireApk(params: JsonObject): File? = resolvePath(params.stringOrNull("path"))

/** 查看 APK 的综合信息。 */
private fun actionInfo(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")

    return ZipFile(apk).use { zip ->
        val entries = zip.entries().toList()
        val manifestBytes = zip.getEntry("AndroidManifest.xml")?.let {
            zip.getInputStream(it).use { input -> input.readBytes() }
        }
        val manifest = manifestBytes?.let { AndroidBinaryXml.parse(it) }
        val dexEntries = entries.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.map { it.name }
        val abis = entries
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .mapNotNull { it.name.split('/').getOrNull(1) }
            .distinct()
            .sorted()

        buildJsonObject {
            put("path", apk.absolutePath)
            put("size", apk.length())
            put("entry_count", entries.size)
            put("dex_files", stringArray(dexEntries))
            put("abis", stringArray(abis))
            put("libraries", stringArray(libNames(entries)))
            put("signature", signatureJson(apk))
            put(
                "manifest",
                manifest?.let { manifestJson(it) }
                    ?: buildJsonObject { put("error", "无法解析 AndroidManifest.xml") },
            )
        }.toString()
    }
}

/** 列出 APK 内部的条目。 */
private fun actionList(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")

    val pattern = params.stringOrNull("pattern")?.trim()
    val matcher = pattern?.takeIf { it.isNotBlank() }?.let { globMatcher(it) }
    val limit = (params.intOrNull("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, 5000)

    return ZipFile(apk).use { zip ->
        val all = zip.entries().toList()
        val matched = all
            .filter { matcher == null || matcher.matches(it.name) }
        val result = buildJsonArray {
            matched.take(limit).forEach { entry ->
                add(buildJsonObject {
                    put("name", entry.name)
                    put("size", if (entry.size < 0) 0L else entry.size)
                    put("compressed_size", if (entry.compressedSize < 0) 0L else entry.compressedSize)
                    put("method", if (entry.method == ZipEntry.STORED) "stored" else "deflated")
                    put("is_dir", entry.isDirectory)
                })
            }
        }
        buildJsonObject {
            put("path", apk.absolutePath)
            put("total", all.size)
            put("count", result.size)
            put("entries", result)
        }.toString()
    }
}

/** 读取 APK 内部某个文件的内容。 */
private fun actionRead(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val name = params.stringOrNull("entry")?.trim()
    if (name.isNullOrBlank()) return errorJson("MISSING_ENTRY", "entry is required")

    val maxBytes = (params.intOrNull("max_bytes") ?: DEFAULT_READ_BYTES).coerceIn(1, HARD_READ_BYTES)
    return ZipFile(apk).use { zip ->
        val entry = zip.getEntry(name) ?: return errorJson("ENTRY_NOT_FOUND", "APK 内不存在该条目：$name")
        if (entry.isDirectory) return errorJson("IS_DIRECTORY", "目标是目录：$name")
        val bytes = zip.getInputStream(entry).use { input -> readCapped(input, maxBytes) }
        val total = entry.size

        buildJsonObject {
            put("path", apk.absolutePath)
            put("entry", name)
            put("size", if (total < 0) bytes.size.toLong() else total)
            put("truncated", total >= 0 && bytes.size.toLong() < total)
            if (AndroidBinaryXml.isBinaryXml(bytes)) {
                put("encoding", "binary-xml")
                val xml = AndroidBinaryXml.parse(bytes)
                if (xml != null) {
                    put("content", xml.toXmlString())
                } else {
                    put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } else if (encodingOf(params) == "base64") {
                put("encoding", "base64")
                put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                put("encoding", "text")
                put("content", String(bytes, Charsets.UTF_8))
            }
        }.toString()
    }
}

/** 反编译 dex 为 smali：列出类名或输出指定类的 smali 代码。 */
private fun actionSmali(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val dexName = params.stringOrNull("dex")?.trim()?.takeIf { it.isNotBlank() } ?: "classes.dex"
    val className = params.stringOrNull("class_name")?.trim()?.takeIf { it.isNotBlank() }
    val limit = (params.intOrNull("limit") ?: DEFAULT_SMALI_LIMIT).coerceIn(1, 5000)

    val workDir = File(apk.parentFile ?: File("."), ".apk_tool_tmp_${System.currentTimeMillis()}")
    if (!workDir.mkdirs()) return errorJson("TMP_FAILED", "无法创建临时目录：${workDir.absolutePath}")
    return try {
        val dexFile = File(workDir, dexName.substringAfterLast('/'))
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(dexName)
                ?: return errorJson("ENTRY_NOT_FOUND", "APK 内不存在 dex：$dexName")
            zip.getInputStream(entry).use { input -> dexFile.outputStream().use { input.copyTo(it) } }
        }

        val opcodes = Opcodes.forApi(DEX_API_LEVEL)
        val dex = DexFileFactory.loadDexFile(dexFile, opcodes)
        val classes = dex.classes.toList()

        if (className == null) {
            val types = classes.map { it.type }.sorted()
            buildJsonObject {
                put("path", apk.absolutePath)
                put("dex", dexName)
                put("class_count", types.size)
                put("classes", stringArray(types.take(limit)))
                if (types.size > limit) put("truncated", true)
            }.toString()
        } else {
            val target = findClass(classes, className)
                ?: return errorJson("CLASS_NOT_FOUND", "未找到类：$className（可先用不传 class_name 的方式列出全部类名）")
            val options = BaksmaliOptions().apply { apiLevel = opcodes.api }
            val outDir = File(workDir, "out")
            Baksmali.disassembleDexFile(dex, outDir, 1, options)
            val smaliFile = locateSmali(outDir, target.type)
                ?: return errorJson("DISASSEMBLE_FAILED", "反编译失败：未找到 ${target.type} 的 smali 文件")
            buildJsonObject {
                put("path", apk.absolutePath)
                put("dex", dexName)
                put("class", target.type)
                put("smali", smaliFile.readText())
            }.toString()
        }
    } finally {
        workDir.deleteRecursively()
    }
}

/** 把一个 dex 完整反编译到本机目录，产出可继续编辑的 smali 工程。 */
private fun actionDisasm(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val dexName = params.stringOrNull("dex")?.trim()?.takeIf { it.isNotBlank() } ?: "classes.dex"
    val baseName = dexName.substringAfterLast('/').removeSuffix(".dex")
    val outDir = params.stringOrNull("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(apk.parentFile ?: File("."), "${apk.nameWithoutExtension}.$baseName.smali")
    if (outDir.exists() && !outDir.isDirectory) {
        return errorJson("OUTPUT_INVALID", "输出路径已存在且不是目录：${outDir.absolutePath}")
    }
    // 目录里若有上次遗留的 smali，会被一起回编译成重复类导致 asm 失败，这里直接拒绝
    if (outDir.list()?.isNotEmpty() == true) {
        return errorJson("OUTPUT_NOT_EMPTY", "输出目录非空，请先删除或换一个 output：${outDir.absolutePath}")
    }
    if (!outDir.exists() && !outDir.mkdirs()) {
        return errorJson("MKDIR_FAILED", "无法创建输出目录：${outDir.absolutePath}")
    }

    val workDir = File(outDir.parentFile ?: File("."), ".apk_tool_tmp_${System.currentTimeMillis()}")
    if (!workDir.mkdirs()) return errorJson("TMP_FAILED", "无法创建临时目录：${workDir.absolutePath}")
    return try {
        val dexFile = File(workDir, "${baseName}.dex")
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(dexName)
                ?: return errorJson("ENTRY_NOT_FOUND", "APK 内不存在 dex：$dexName")
            zip.getInputStream(entry).use { input -> dexFile.outputStream().use { input.copyTo(it) } }
        }

        val opcodes = Opcodes.forApi(DEX_API_LEVEL)
        val dex = DexFileFactory.loadDexFile(dexFile, opcodes)
        val options = BaksmaliOptions().apply { apiLevel = opcodes.api }
        Baksmali.disassembleDexFile(dex, outDir, 1, options)

        val fileCount = outDir.walkTopDown().count { it.extension == "smali" }
        buildJsonObject {
            put("success", true)
            put("path", apk.absolutePath)
            put("dex", dexName)
            put("output", outDir.absolutePath)
            put("class_count", dex.classes.count())
            put("file_count", fileCount)
            put(
                "next",
                "用 file_system 编辑该目录下的 .smali 文件后，调用 action=asm 回编译成 dex，" +
                    "再用 action=replace 覆盖 APK 里的 $dexName，最后 action=sign 重新签名。",
            )
        }.toString()
    } finally {
        workDir.deleteRecursively()
    }
}

/** 把编辑过的 smali 目录回编译成 dex。 */
private fun actionAsm(params: JsonObject): String {
    val smaliDir = resolvePath(params.stringOrNull("smali_dir"))
        ?: return errorJson("MISSING_SMALI_DIR", "smali_dir is required")
    if (!smaliDir.isDirectory) return errorJson("NOT_FOUND", "smali 目录不存在：${smaliDir.absolutePath}")
    if (smaliDir.walkTopDown().none { it.extension == "smali" }) {
        return errorJson("EMPTY_SMALI_DIR", "目录下没有 .smali 文件：${smaliDir.absolutePath}")
    }

    val outDex = params.stringOrNull("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(smaliDir.parentFile ?: File("."), smaliDir.name + ".dex")
    outDex.parentFile?.mkdirs()
    if (outDex.isDirectory) return errorJson("OUTPUT_INVALID", "输出路径是目录，请指定 .dex 文件名：${outDex.absolutePath}")
    if (outDex.exists() && !outDex.delete()) {
        return errorJson("OUTPUT_LOCKED", "无法覆盖已存在的输出文件：${outDex.absolutePath}")
    }

    val options = SmaliOptions().apply {
        apiLevel = DEX_API_LEVEL
        jobs = 1
        outputDexFile = outDex.absolutePath
    }
    val ok = runCatching { Smali.assemble(options, listOf(smaliDir.absolutePath)) }
        .getOrElse { return errorJson("ASM_FAILED", "回编译失败：${it.message ?: it::class.simpleName}") }
    if (!ok || !outDex.isFile) {
        return errorJson("ASM_FAILED", "回编译失败，未生成 dex，请检查 smali 语法是否正确。")
    }

    return buildJsonObject {
        put("success", true)
        put("smali_dir", smaliDir.absolutePath)
        put("output", outDex.absolutePath)
        put("size", outDex.length())
        put(
            "next",
            "用 action=replace 把该 dex 写回 APK（entry 填对应的 dex 条目名），再用 action=sign 重新签名。",
        )
    }.toString()
}

/** 把 APK 内部条目解压到本机目录。 */
private fun actionExtract(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val dir = resolvePath(params.stringOrNull("output"))
        ?: File(apk.parentFile ?: File("."), apk.nameWithoutExtension + ".extracted")
    if (!dir.exists() && !dir.mkdirs()) return errorJson("MKDIR_FAILED", "无法创建目录：${dir.absolutePath}")

    val single = params.stringOrNull("entry")?.trim()?.takeIf { it.isNotBlank() }
    var count = 0
    ZipFile(apk).use { zip ->
        val targets = if (single != null) {
            val entry = zip.getEntry(single) ?: return errorJson("ENTRY_NOT_FOUND", "APK 内不存在该条目：$single")
            listOf(entry)
        } else {
            zip.entries().toList().filter { !it.isDirectory }
        }
        targets.forEach { entry ->
            val outFile = File(dir, entry.name)
            outFile.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
            count++
        }
    }
    return buildJsonObject {
        put("success", true)
        put("path", apk.absolutePath)
        put("output", dir.absolutePath)
        put("extracted", count)
    }.toString()
}

/** 替换 / 新增 APK 内部的文件。 */
private fun actionReplace(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val entryName = params.stringOrNull("entry")?.trim()
    if (entryName.isNullOrBlank()) return errorJson("MISSING_ENTRY", "entry is required")

    val source = resolvePath(params.stringOrNull("source")?.takeIf { it.isNotBlank() })
    val content = params.stringOrNull("content")
    val bytes = when {
        source != null -> {
            if (!source.isFile) return errorJson("NOT_FOUND", "源文件不存在：${source.absolutePath}")
            source.readBytes()
        }
        content != null -> runCatching { decodeContent(content, encodingOf(params)) }
            .getOrElse { return errorJson("INVALID_CONTENT", "内容解码失败：${it.message ?: it::class.simpleName}") }
        else -> return errorJson("MISSING_SOURCE", "需要提供 source 或 content")
    }

    val output = params.stringOrNull("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(apk.parentFile ?: File("."), apk.nameWithoutExtension + ".patched.apk")
    if (output.absolutePath == apk.absolutePath) {
        return errorJson("SAME_PATH", "输出路径不能与原 APK 相同，请换一个 output。")
    }

    rewriteApk(apk, output, replace = mapOf(entryName to bytes))
    return buildJsonObject {
        put("success", true)
        put("path", apk.absolutePath)
        put("output", output.absolutePath)
        put("entry", entryName)
        put("bytes", bytes.size)
        put("reminder", "APK 已改动，原签名失效，请调用 action=sign 重新签名后才能安装。")
    }.toString()
}

/** 删除 APK 内部的文件。 */
private fun actionDelete(params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")

    val names = params.stringArray("entries").ifEmpty {
        listOfNotNull(params.stringOrNull("entry")?.trim()?.takeIf { it.isNotBlank() })
    }
    if (names.isEmpty()) return errorJson("MISSING_ENTRY", "需要提供 entry 或 entries")

    val output = params.stringOrNull("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(apk.parentFile ?: File("."), apk.nameWithoutExtension + ".patched.apk")
    if (output.absolutePath == apk.absolutePath) {
        return errorJson("SAME_PATH", "输出路径不能与原 APK 相同，请换一个 output。")
    }

    rewriteApk(apk, output, delete = names.toSet())
    return buildJsonObject {
        put("success", true)
        put("path", apk.absolutePath)
        put("output", output.absolutePath)
        put("deleted", stringArray(names))
        put("reminder", "APK 已改动，原签名失效，请调用 action=sign 重新签名后才能安装。")
    }.toString()
}

/** 对 APK 重新签名。 */
private fun actionSign(context: Context, params: JsonObject): String {
    val apk = requireApk(params) ?: return errorJson("MISSING_PATH", "path is required")
    if (!apk.isFile) return errorJson("NOT_FOUND", "APK 不存在：${apk.absolutePath}")
    val output = params.stringOrNull("output")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
        ?: File(apk.parentFile ?: File("."), apk.nameWithoutExtension + ".signed.apk")
    if (output.absolutePath == apk.absolutePath) {
        return errorJson("SAME_PATH", "输出路径不能与原 APK 相同，请换一个 output。")
    }

    val customKeystore = params.stringOrNull("keystore")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
    if (customKeystore != null && !customKeystore.isFile) {
        return errorJson("NOT_FOUND", "签名库不存在：${customKeystore.absolutePath}")
    }
    val storePassword = params.stringOrNull("store_password")?.takeIf { it.isNotEmpty() }
        ?: BUILTIN_KEYSTORE_PASSWORD
    val alias = params.stringOrNull("key_alias")?.takeIf { it.isNotBlank() } ?: BUILTIN_KEY_ALIAS
    val keyPassword = params.stringOrNull("key_password")?.takeIf { it.isNotEmpty() } ?: storePassword

    val keyStore = if (customKeystore != null) {
        loadKeyStore(customKeystore, storePassword)
            ?: return errorJson("KEYSTORE_ERROR", "无法读取签名库：${customKeystore.absolutePath}（请检查口令与格式）")
    } else {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(BUILTIN_KEYSTORE_ASSET).use { keyStore.load(it, BUILTIN_KEYSTORE_PASSWORD.toCharArray()) }
        keyStore
    }

    val resolvedAlias = if (keyStore.containsAlias(alias)) alias else keyStore.aliases().asSequence().firstOrNull()
    if (resolvedAlias == null) return errorJson("KEYSTORE_ERROR", "签名库中没有任何密钥条目。")

    val key = runCatching { keyStore.getKey(resolvedAlias, keyPassword.toCharArray()) as? PrivateKey }
        .getOrNull() ?: return errorJson("KEYSTORE_ERROR", "无法取出私钥，请检查 key_alias 与口令。")
    val certificates = keyStore.getCertificateChain(resolvedAlias)
        ?.filterIsInstance<X509Certificate>()
        ?: keyStore.getCertificate(resolvedAlias)?.let { listOfNotNull(it as? X509Certificate) }
        ?: emptyList()
    if (certificates.isEmpty()) return errorJson("KEYSTORE_ERROR", "签名库中缺少证书链。")

    val signerConfig = ApkSigner.SignerConfig.Builder(resolvedAlias, key, certificates).build()
    val signer = ApkSigner.Builder(listOf(signerConfig))
        .setInputApk(apk)
        .setOutputApk(output)
        .setV1SigningEnabled(true)
        .setV2SigningEnabled(true)
        .build()
    runCatching { signer.sign() }
        .getOrElse { return errorJson("SIGN_FAILED", "签名失败：${it.message ?: it::class.simpleName}") }

    return buildJsonObject {
        put("success", true)
        put("path", apk.absolutePath)
        put("output", output.absolutePath)
        put("key_alias", resolvedAlias)
        put("builtin_key", customKeystore == null)
        put("signature", signatureJson(output))
    }.toString()
}

/** 重写 APK：按需删除条目并写入替换内容。 */
private fun rewriteApk(input: File, output: File, delete: Set<String> = emptySet(), replace: Map<String, ByteArray> = emptyMap()) {
    output.parentFile?.mkdirs()
    val replaced = replace.keys
    ZipFile(input).use { zip ->
        ZipOutputStream(output.outputStream().buffered()).use { out ->
            zip.entries().toList().forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (entry.name in delete || entry.name in replaced) return@forEach
                copyEntry(zip, entry, out)
            }
            replace.forEach { (name, bytes) -> writeEntry(out, name, bytes) }
        }
    }
}

/** 照抄条目时保留原来的压缩方式，避免 resources.arsc 等本应未压缩的条目被重新压缩。 */
private fun copyEntry(zip: ZipFile, entry: ZipEntry, out: ZipOutputStream) {
    val copy = ZipEntry(entry.name)
    if (entry.method == ZipEntry.STORED) {
        copy.method = ZipEntry.STORED
        copy.size = entry.size
        copy.compressedSize = entry.size
        copy.crc = entry.crc
    }
    out.putNextEntry(copy)
    zip.getInputStream(entry).use { it.copyTo(out) }
    out.closeEntry()
}

private fun writeEntry(out: ZipOutputStream, name: String, bytes: ByteArray) {
    val entry = ZipEntry(name)
    if (name == "resources.arsc") {
        // 该文件必须以未压缩方式存放，否则 Android 11+ 会拒绝安装
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
    }
    out.putNextEntry(entry)
    out.write(bytes)
    out.closeEntry()
}

private fun loadKeyStore(file: File, password: String): KeyStore? {
    val types = if (file.extension.lowercase() in setOf("jks", "keystore")) listOf("JKS", "PKCS12") else listOf("PKCS12", "JKS")
    types.forEach { type ->
        runCatching {
            val keyStore = KeyStore.getInstance(type)
            file.inputStream().use { keyStore.load(it, password.toCharArray()) }
            return keyStore
        }
    }
    return null
}

private fun findClass(classes: List<ClassDef>, rawName: String): ClassDef? {
    val type = if (rawName.startsWith("L") && rawName.endsWith(";")) {
        rawName
    } else {
        "L" + rawName.replace('.', '/') + ";"
    }
    classes.firstOrNull { it.type == type }?.let { return it }
    val simple = rawName.substringAfterLast('.').substringAfterLast('/').removeSuffix(";")
    return classes.firstOrNull { it.type.substringAfterLast('/').removeSuffix(";") == simple }
}

/** baksmali 输出的 smali 相对路径由类描述符推导，必要时回退到按文件头精确匹配。 */
private fun locateSmali(outDir: File, type: String): File? {
    val direct = File(outDir, type.removePrefix("L").removeSuffix(";") + ".smali")
    if (direct.isFile) return direct
    val header = ".class $type"
    return outDir.walkTopDown().firstOrNull { file ->
        file.extension == "smali" && file.useLines { lines -> lines.firstOrNull()?.trim() == header }
    }
}

private fun signatureJson(apk: File): JsonObject = runCatching {
    val result = ApkVerifier.Builder(apk).build().verify()
    val certificates = result.signerCertificates.filterIsInstance<X509Certificate>()
    buildJsonObject {
        put("verified", result.isVerified)
        put("v1", result.v1SchemeSigners.isNotEmpty())
        put("v2", result.v2SchemeSigners.isNotEmpty())
        put("v3", result.v3SchemeSigners.isNotEmpty())
        put("signers", buildJsonArray { certificates.forEach { add(certificateJson(it)) } })
        if (result.errors.isNotEmpty()) {
            put("errors", stringArray(result.errors.map { it.toString() }))
        }
        if (result.warnings.isNotEmpty()) {
            put("warnings", stringArray(result.warnings.map { it.toString() }))
        }
    }
}.getOrElse {
    buildJsonObject { put("error", "签名校验失败：${it.message ?: it::class.simpleName}") }
}

private fun certificateJson(cert: X509Certificate): JsonObject = buildJsonObject {
    put("subject", cert.subjectX500Principal.name)
    put("issuer", cert.issuerX500Principal.name)
    put("serial", cert.serialNumber.toString(16))
    put("valid_from", cert.notBefore.time)
    put("valid_to", cert.notAfter.time)
    put("algorithm", cert.sigAlgName)
    put("sha1", cert.encoded.digestHex("SHA-1"))
    put("sha256", cert.encoded.digestHex("SHA-256"))
}

private fun ByteArray.digestHex(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm).digest(this)
    return digest.joinToString(":") { "%02X".format(it) }
}

private fun libNames(entries: List<ZipEntry>): List<String> = entries
    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
    .map { it.name.substringAfterLast('/') }
    .distinct()
    .sorted()

private fun manifestJson(root: AxmlElement): JsonObject {
    val usesSdk = root.findAll("uses-sdk").firstOrNull()
    return buildJsonObject {
        put("package", root.attrString("package") ?: "")
        root.attrInt("versionCode")?.let { put("version_code", it) }
        root.attrString("versionName")?.let { put("version_name", it) }
        putSdk(usesSdk, "minSdkVersion", "min_sdk")
        putSdk(usesSdk, "targetSdkVersion", "target_sdk")
        putSdk(usesSdk, "maxSdkVersion", "max_sdk")

        root.findAll("application").firstOrNull()?.let { application ->
            put("application", buildJsonObject {
                application.attrString("label")?.let { put("label", it) }
                application.attrBool("debuggable")?.let { put("debuggable", it) }
                application.attrBool("allowBackup")?.let { put("allow_backup", it) }
                application.attrString("theme")?.let { put("theme", it) }
            })
        }

        put("main_activity", mainActivity(root) ?: "")
        put("activities", components(root, "activity"))
        put("services", components(root, "service"))
        put("receivers", components(root, "receiver"))
        put("providers", components(root, "provider"))
        put("permissions", stringArray(root.findAll("uses-permission").mapNotNull { it.attrString("name") }))
        put("features", stringArray(root.findAll("uses-feature").mapNotNull { it.attrString("name") }))
    }
}

private fun JsonObjectBuilder.putSdk(element: AxmlElement?, attr: String, key: String) {
    val value = element?.attr(attr) ?: return
    val number = value.intValue ?: value.value?.toIntOrNull()
    if (number != null) put(key, number) else value.value?.let { put(key, it) }
}

private fun mainActivity(root: AxmlElement): String? =
    root.findAll("activity").firstOrNull { activity ->
        activity.findAll("action").any { it.attrString("name") == "android.intent.action.MAIN" } &&
            activity.findAll("category").any { it.attrString("name") == "android.intent.category.LAUNCHER" }
    }?.attrString("name")

private fun components(root: AxmlElement, tag: String): JsonArray =
    buildJsonArray {
        root.findAll(tag).forEach { element ->
            add(buildJsonObject {
                put("name", element.attrString("name") ?: "")
                element.attrBool("exported")?.let { put("exported", it) }
                element.attrString("permission")?.let { put("permission", it) }
            })
        }
    }

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray { values.forEach { add(it) } }

private fun globMatcher(pattern: String): Regex {
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

private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var read = 0
    while (read < maxBytes) {
        val n = input.read(buffer, read, maxBytes - read)
        if (n <= 0) break
        read += n
    }
    return buffer.copyOf(read)
}

private fun encodingOf(params: JsonObject): String =
    params.stringOrNull("encoding")?.lowercase()?.takeIf { it == "base64" } ?: "text"

private fun decodeContent(content: String, encoding: String): ByteArray =
    if (encoding == "base64") Base64.decode(content, Base64.DEFAULT) else content.toByteArray(Charsets.UTF_8)

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.stringArray(key: String): List<String> =
    runCatching {
        this[key]?.let { element ->
            (element as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        } ?: emptyList()
    }.getOrDefault(emptyList())