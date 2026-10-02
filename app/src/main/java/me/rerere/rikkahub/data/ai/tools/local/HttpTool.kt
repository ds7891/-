package me.rerere.rikkahub.data.ai.tools.local

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
import me.rerere.rikkahub.data.files.FilesManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** HTTP 请求工具的注册名。 */
const val HTTP_TOOL = "http_request"

private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val MAX_TIMEOUT_SECONDS = 120
private const val DEFAULT_MAX_BYTES = 512 * 1024
private const val HARD_MAX_BYTES = 32 * 1024 * 1024

/** 流式下载到文件时使用的缓冲区大小。 */
private const val STREAM_BUFFER_BYTES = 64 * 1024

/** 无法从响应头或 URL 推断文件名时的默认名字。 */
private const val DEFAULT_DOWNLOAD_NAME = "download.bin"

/** 未指定 save_to 时，图片自动落地到的目录（相对内部共享存储根目录）。 */
private const val DEFAULT_IMAGE_DIR = "Download/清水AI"

/** 未指定 save_to 时，最多读入内存用于展示的图片字节数。 */
private const val IMAGE_INLINE_MAX_BYTES = 12 * 1024 * 1024

/** 常见图片后缀，用于判断下载文件名是否需要补扩展名。 */
private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg", "avif",
)

private val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/**
 * 通用 HTTP 请求工具：让 AI 直接出网，既能像浏览器一样打开网址读内容，
 * 也能像 curl 一样把数据（含用户提供的密钥）POST 到指定接口并读回响应。
 *
 * [allowedDomains] 为助手上配置的域名白名单，为空表示不限制。
 */
internal fun buildHttpTool(allowedDomains: List<String>): Tool = Tool(
    name = HTTP_TOOL,
    description = """
        直接向网络发出 HTTP 请求并读回响应：既可以像浏览器一样打开某个网址读取内容，也可以像 curl 一样把数据发出去。
        典型用途：
        - GET 一个网址，抓取网页或调用公开 API 并读取返回内容
        - POST 一段 JSON 到指定接口，例如把用户给你的密钥和内容发到大模型 API，再读回它返回的答案
        用法要点：
        - url 必填，必须带 http:// 或 https:// 前缀
        - method 默认 GET；POST/PUT/PATCH 等可传 body 作为请求体
        - headers 传自定义请求头，例如 {"Authorization":"Bearer sk-xxx","Content-Type":"application/json"}
        - 响应默认按文本读取，二进制可用 response_encoding=base64；max_bytes 限制读取长度
        下载文件（图片 / 视频 / 音频 / 模型 / 软件包等任意大文件）：
        - 传 save_to 指定本机保存路径即可，响应体会以流式写入磁盘，不受 max_bytes 限制，可下载任意大小
        - save_to 以 / 结尾或指向一个已存在的目录时，会自动按响应头或 URL 推断文件名
        - 路径支持绝对路径、相对路径（相对 /sdcard）、以及 ~ 与 /sdcard 前缀；父目录会自动创建
        - 若返回 SAVE_FAILED，通常是缺少“所有文件访问权限”，请到系统设置授予后再试
        展示图片：
        - 若响应本身是图片（Content-Type 为 image/*），无需手动转 base64，工具会直接把图片渲染到对话里给用户看
        - 用户说“把图发给我 / 给我看看这张图”时，直接 GET 该图片地址即可，不要用 base64 文本回复
        该助手可能配置了域名白名单：命中白名单之外的域名会返回 NOT_ALLOWED，此时应把当前允许的域名告诉用户，
        请用户到「助手设置 → 本地工具 → 网络请求」里放行，不要反复重试同一个域名。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "完整请求地址，必须带 http:// 或 https:// 前缀。")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray { SUPPORTED_METHODS.forEach { add(it) } },
                    )
                    put("description", "请求方法，默认 GET。")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                    put("description", "自定义请求头，键值均为字符串。")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "请求体，仅 POST/PUT/PATCH/DELETE 等方法使用。")
                })
                put("body_encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "body 的编码：text（默认，UTF-8）或 base64。")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "请求体的 Content-Type，默认 application/json；可用 headers 覆盖。")
                })
                put("response_encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "响应体的返回方式：auto（默认，按 Content-Type 判断）、text 或 base64。")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "超时秒数，默认 30，最大 120。")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "读入内存的响应字节上限，默认 524288，最大 33554432；用 save_to 下载文件时不受此限制。")
                })
                put("follow_redirects", buildJsonObject {
                    put("type", "boolean")
                    put("description", "是否自动跟随重定向，默认 true。")
                })
                put("save_to", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "把响应体流式下载到该本机路径（父目录自动创建，大小不受 max_bytes 限制）。" +
                            "以 / 结尾或传一个已存在的目录时会自动按响应头 / URL 推断文件名。"
                    )
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        runHttpRequest(params, allowedDomains)
    },
)

private fun textOnly(json: String): List<UIMessagePart> = listOf(UIMessagePart.Text(json))

private fun runHttpRequest(params: JsonObject, allowedDomains: List<String>): List<UIMessagePart> {
    val rawUrl = params.stringOrNull("url")?.trim().orEmpty()
    if (rawUrl.isBlank()) return textOnly(errorJson("MISSING_URL", "url is required"))
    val url = rawUrl.toHttpUrlOrNull()
        ?: return textOnly(
            errorJson("INVALID_URL", "无法解析的地址（需带 http:// 或 https:// 前缀）：$rawUrl")
        )

    if (!isHostAllowed(url, allowedDomains)) {
        return textOnly(
            buildJsonObject {
                put("error", "NOT_ALLOWED")
                put("message", "该域名不在允许访问的白名单内：${url.host}")
                put("allowed_domains", buildJsonArray { allowedDomains.forEach { add(it) } })
            }.toString()
        )
    }

    val method = params.stringOrNull("method")?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "GET"
    if (method !in SUPPORTED_METHODS) {
        return textOnly(errorJson("UNSUPPORTED_METHOD", "不支持的请求方法：$method"))
    }

    val timeout = (params.intOrNull("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
        .coerceIn(1, MAX_TIMEOUT_SECONDS)
    val maxBytes = (params.intOrNull("max_bytes") ?: DEFAULT_MAX_BYTES).coerceIn(1, HARD_MAX_BYTES)
    val followRedirects = params.boolOrNull("follow_redirects") ?: true
    val headers = params.headersOrNull()

    val bodyBytes = params.stringOrNull("body")?.let { raw ->
        when (params.stringOrNull("body_encoding")?.lowercase()) {
            "base64" -> runCatching { Base64.decode(raw, Base64.DEFAULT) }
                .getOrElse { return textOnly(errorJson("INVALID_BODY", "body 不是合法的 base64。")) }

            else -> raw.toByteArray(Charsets.UTF_8)
        }
    }
    if (bodyBytes != null && method in setOf("GET", "HEAD")) {
        return textOnly(errorJson("BODY_NOT_ALLOWED", "$method 请求不能携带 body，请改用 POST/PUT/PATCH。"))
    }

    val contentType = headers.keys.firstOrNull { it.equals("Content-Type", ignoreCase = true) }
        ?.let { headers[it] }
        ?: params.stringOrNull("content_type")?.takeIf { it.isNotBlank() }
        ?: "application/json"

    val requestBuilder = Request.Builder().url(url)
    headers.forEach { (name, value) -> requestBuilder.header(name, value) }
    if (bodyBytes != null) {
        requestBuilder.method(method, bodyBytes.toRequestBody(contentType.toMediaTypeOrNull()))
    } else if (method != "GET") {
        requestBuilder.method(method, ByteArray(0).toRequestBody(null))
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .build()

    val startedAt = System.currentTimeMillis()
    val saveToRaw = params.stringOrNull("save_to")?.takeIf { it.isNotBlank() }
    return runCatching {
        client.newCall(requestBuilder.build()).execute().use { response ->
            val elapsed = System.currentTimeMillis() - startedAt
            val contentTypeHeader = response.header("Content-Type")
            val maybeImage = isImageContentType(contentTypeHeader) || urlLooksLikeImage(url)

            // 指定了 save_to：流式下载到磁盘，不受 max_bytes 限制，可保存任意大小的图片 / 视频 / 软件包
            if (saveToRaw != null) {
                val target = resolveDownloadTarget(saveToRaw, url, response)
                    ?: return@use textOnly(errorJson("INVALID_SAVE_PATH", "无法解析保存路径：$saveToRaw"))
                return@use saveResponseToFile(response, target).fold(
                    onSuccess = { written ->
                        downloadResultParts(
                            url = url,
                            response = response,
                            method = method,
                            elapsed = elapsed,
                            contentType = contentTypeHeader,
                            size = written,
                            savedTo = target.absolutePath,
                            showImage = maybeImage,
                        )
                    },
                    onFailure = { e -> textOnly(saveFailedJson(target, e)) },
                )
            }

            // 预判为图片时放宽读取上限，避免默认 512KB 把图片截断成“空白”
            val readCap = if (maybeImage) maxOf(maxBytes, IMAGE_INLINE_MAX_BYTES) else maxBytes
            val bytes = response.body.byteStream().use { readCappedBytes(it, readCap) }
            val declaredSize = response.body.contentLength()

            // 图片响应：无需转 base64，直接把图片渲染到对话里给用户看
            // 除 Content-Type / URL 后缀外，再用文件头（magic bytes）兜底识别
            val sniffedImageMime = sniffImageMime(bytes)
            if (maybeImage || sniffedImageMime != null) {
                val mime = sniffedImageMime
                    ?: imageMimeFromContentType(contentTypeHeader)
                    ?: imageMimeFromUrl(url)
                    ?: "image/png"
                return@use imageResultParts(url, response, method, elapsed, mime, bytes)
            }

            val asBase64 = when (params.stringOrNull("response_encoding")?.lowercase()) {
                "base64" -> true
                "text" -> false
                else -> !looksLikeText(contentTypeHeader)
            }
            val truncated = declaredSize >= 0 && bytes.size.toLong() < declaredSize

            textOnly(
                buildJsonObject {
                    put("url", url.toString())
                    put("final_url", response.request.url.toString())
                    put("method", method)
                    put("status", response.code)
                    put("message", response.message)
                    put("elapsed_ms", elapsed)
                    put("content_type", contentTypeHeader ?: "")
                    put("size", if (declaredSize >= 0) declaredSize else bytes.size.toLong())
                    put("truncated", truncated)
                    put("redirected", response.priorResponse != null)
                    put(
                        "headers",
                        buildJsonObject {
                            response.headers.forEach { (name, value) -> put(name, value) }
                        },
                    )
                    if (asBase64) {
                        put("body_encoding", "base64")
                        put("body_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } else {
                        put("body_encoding", "text")
                        put("body", String(bytes, Charsets.UTF_8))
                    }
                }.toString()
            )
        }
    }.getOrElse {
        textOnly(errorJson("REQUEST_FAILED", "请求失败：${it.message ?: it::class.simpleName}"))
    }
}

/** 把响应体流式写入目标文件，返回写入的字节数。 */
private fun saveResponseToFile(response: Response, target: File): Result<Long> = runCatching {
    target.parentFile?.mkdirs()
    response.body.byteStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_BYTES) }
    }
}

private fun saveFailedJson(target: File, e: Throwable): String = buildJsonObject {
    put("error", "SAVE_FAILED")
    put("message", "下载失败或写入文件被拒绝：${e.message ?: e::class.simpleName}")
    put("path", target.absolutePath)
    put(
        "hint",
        "请到系统设置为本应用授予「所有文件访问权限」，或改存到可写目录（如 /sdcard/Download/）。",
    )
}.toString()

/** 下载完成后返回的元数据；图片会额外附带一个可直接渲染的图片部件。 */
private fun downloadResultParts(
    url: HttpUrl,
    response: Response,
    method: String,
    elapsed: Long,
    contentType: String?,
    size: Long,
    savedTo: String,
    showImage: Boolean,
): List<UIMessagePart> {
    val json = buildJsonObject {
        put("url", url.toString())
        put("final_url", response.request.url.toString())
        put("method", method)
        put("status", response.code)
        put("message", response.message)
        put("elapsed_ms", elapsed)
        put("content_type", contentType ?: "")
        put("size", size)
        put("downloaded", true)
        put("saved_to", savedTo)
        if (showImage) put("image_shown", true)
    }.toString()
    val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(json))
    if (showImage) parts += UIMessagePart.Image("file://$savedTo")
    return parts
}

/**
 * 未指定 save_to 的图片响应：读入内存后落一份到本机并直接在对话中展示给用户。
 * 依次尝试外部存储下载目录、应用内部缓存，最后退化为内联 data URI，确保图片一定能显示。
 */
private fun imageResultParts(
    url: HttpUrl,
    response: Response,
    method: String,
    elapsed: Long,
    mime: String,
    bytes: ByteArray,
): List<UIMessagePart> {
    val declaredSize = response.body.contentLength()
    val truncated = declaredSize >= 0 && bytes.size.toLong() < declaredSize
    val imageUrl = persistImageForDisplay(bytes, mime, imageFileName(url, response, mime))
    val json = buildJsonObject {
        put("url", url.toString())
        put("final_url", response.request.url.toString())
        put("method", method)
        put("status", response.code)
        put("message", response.message)
        put("elapsed_ms", elapsed)
        put("content_type", mime)
        put("size", bytes.size.toLong())
        put("truncated", truncated)
        put("image_shown", true)
        if (truncated) {
            put("hint", "图片较大已截断，如需完整保存请传 save_to 指定本机路径。")
        }
    }.toString()
    return listOf(UIMessagePart.Text(json), UIMessagePart.Image(imageUrl))
}

/** 把图片字节持久化到本机，返回可直接渲染的地址。 */
private fun persistImageForDisplay(bytes: ByteArray, mime: String, name: String): String {
    val dir = resolvePath(DEFAULT_IMAGE_DIR)
    if (dir != null) {
        val external = runCatching {
            // 加时间戳前缀，避免不同图片同名互相覆盖
            val file = File(dir, "${System.currentTimeMillis()}_$name")
            file.parentFile?.mkdirs()
            file.outputStream().use { it.write(bytes) }
            file
        }.getOrNull()
        if (external != null && external.length() > 0) return "file://${external.absolutePath}"
    }

    val managed = runCatching {
        getKoin().get<FilesManager>().createChatFilesByByteArrays(listOf(bytes)).first().toString()
    }.getOrNull()
    if (!managed.isNullOrBlank()) return managed

    return "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}

/** Content-Type 是否为图片。 */
private fun isImageContentType(contentType: String?): Boolean =
    contentType?.trim()?.lowercase()?.startsWith("image/") == true

/** 从 Content-Type 得到规范化的图片 MIME；不是图片时返回 null。 */
private fun imageMimeFromContentType(contentType: String?): String? {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return mime.takeIf { it.startsWith("image/") }
}

/** 用 URL 后缀猜测图片 MIME；猜测不出时返回 null。 */
private fun imageMimeFromUrl(url: HttpUrl): String? = when (url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "avif" -> "image/avif"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> null
}

/** URL 路径是否以图片后缀结尾。 */
private fun urlLooksLikeImage(url: HttpUrl): Boolean {
    val ext = url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase() ?: return false
    return ext in IMAGE_EXTENSIONS
}

/**
 * 通过文件头（magic bytes）识别图片类型。
 * 有些图床 / CDN 会返回 application/octet-stream，仅靠 Content-Type 无法判断。
 */
private fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    ) {
        return "image/jpeg"
    }
    if (bytes.size >= 6 && startsWithAscii(bytes, "GIF8")) return "image/gif"
    if (bytes.size >= 12 && startsWithAscii(bytes, "RIFF") && asciiAt(bytes, 8, "WEBP")) return "image/webp"
    if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp"
    if (bytes.size >= 12 && asciiAt(bytes, 4, "ftyp") &&
        (asciiAt(bytes, 8, "avif") || asciiAt(bytes, 8, "avis"))
    ) {
        return "image/avif"
    }
    return null
}

private fun startsWithAscii(bytes: ByteArray, prefix: String): Boolean = asciiAt(bytes, 0, prefix)

private fun asciiAt(bytes: ByteArray, offset: Int, text: String): Boolean {
    if (offset + text.length > bytes.size) return false
    return text.indices.all { bytes[offset + it].toInt().toChar().equals(text[it], ignoreCase = true) }
}

/** 给下载的图片补一个与 MIME 匹配的后缀，避免无法按图片类型渲染。 */
private fun imageFileName(url: HttpUrl, response: Response, mime: String): String {
    val raw = pickFileName(url, response)
    if (raw.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) return raw
    val ext = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> mime.substringAfter('/', "").ifBlank { "img" }
    }
    return "$raw.$ext"
}

/**
 * 解析下载目标文件：路径以 / 结尾或本身是已存在的目录时，
 * 视为目录并按响应头 / URL 推断文件名；否则按给定文件名保存。
 */
private fun resolveDownloadTarget(raw: String, url: HttpUrl, response: Response): File? {
    val base = resolvePath(raw) ?: return null
    val isDirectory = raw.endsWith("/") || raw.endsWith(File.separator) || base.isDirectory
    return if (isDirectory) File(base, pickFileName(url, response)) else base
}

/** 依次尝试 Content-Disposition 与 URL 末段来推断文件名。 */
private fun pickFileName(url: HttpUrl, response: Response): String {
    val fromHeader = response.header("Content-Disposition")
        ?.let { header ->
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.getOrNull(1)
        }
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (fromHeader != null) return sanitizeFileName(fromHeader)
    val fromUrl = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    return sanitizeFileName(fromUrl ?: DEFAULT_DOWNLOAD_NAME)
}

/** 去掉文件名里的非法字符，避免写入失败或路径穿越。 */
private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { DEFAULT_DOWNLOAD_NAME }

/** 判断域名是否命中白名单；白名单为空表示不限制，支持 *.example.com 通配。 */
private fun isHostAllowed(url: HttpUrl, allowedDomains: List<String>): Boolean {
    if (allowedDomains.isEmpty()) return true
    val host = url.host.lowercase()
    return allowedDomains.any { rule ->
        val pattern = rule.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
        when {
            pattern.isEmpty() -> false
            pattern.startsWith("*.") -> host.endsWith(pattern.removePrefix("*"))
            else -> host == pattern || host.endsWith(".$pattern")
        }
    }
}

/** 只有明确是文本类（text 系列、JSON、XML、JS、表单）时才按文本返回，其余按二进制处理。 */
private fun looksLikeText(contentType: String?): Boolean {
    val type = contentType?.lowercase() ?: return true
    return type.startsWith("text/") ||
        type.contains("json") ||
        type.contains("xml") ||
        type.contains("javascript") ||
        type.contains("x-www-form-urlencoded") ||
        type.contains("csv")
}

private fun readCappedBytes(input: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var read = 0
    while (read < maxBytes) {
        val n = input.read(buffer, read, maxBytes - read)
        if (n <= 0) break
        read += n
    }
    return buffer.copyOf(read)
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.boolOrNull(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.headersOrNull(): Map<String, String> = runCatching {
    this["headers"]?.jsonObject
        ?.mapNotNull { (name, value) -> value.jsonPrimitive.contentOrNull?.let { name to it } }
        ?.toMap()
}.getOrNull().orEmpty()