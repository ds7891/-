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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** HTTP 请求工具的注册名。 */
const val HTTP_TOOL = "http_request"

private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val MAX_TIMEOUT_SECONDS = 120
private const val DEFAULT_MAX_BYTES = 512 * 1024
private const val HARD_MAX_BYTES = 8 * 1024 * 1024

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
        - 想直接把响应存成文件时传 save_to（本机路径）
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
                    put("description", "最多读取的响应字节数，默认 524288，最大 8388608。")
                })
                put("follow_redirects", buildJsonObject {
                    put("type", "boolean")
                    put("description", "是否自动跟随重定向，默认 true。")
                })
                put("save_to", buildJsonObject {
                    put("type", "string")
                    put("description", "把响应体原样保存到该本机路径（父目录会自动创建）。")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        listOf(UIMessagePart.Text(runHttpRequest(params, allowedDomains)))
    },
)

private fun runHttpRequest(params: JsonObject, allowedDomains: List<String>): String {
    val rawUrl = params.stringOrNull("url")?.trim().orEmpty()
    if (rawUrl.isBlank()) return errorJson("MISSING_URL", "url is required")
    val url = rawUrl.toHttpUrlOrNull()
        ?: return errorJson("INVALID_URL", "无法解析的地址（需带 http:// 或 https:// 前缀）：$rawUrl")

    if (!isHostAllowed(url, allowedDomains)) {
        return buildJsonObject {
            put("error", "NOT_ALLOWED")
            put("message", "该域名不在允许访问的白名单内：${url.host}")
            put("allowed_domains", buildJsonArray { allowedDomains.forEach { add(it) } })
        }.toString()
    }

    val method = params.stringOrNull("method")?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "GET"
    if (method !in SUPPORTED_METHODS) {
        return errorJson("UNSUPPORTED_METHOD", "不支持的请求方法：$method")
    }

    val timeout = (params.intOrNull("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
        .coerceIn(1, MAX_TIMEOUT_SECONDS)
    val maxBytes = (params.intOrNull("max_bytes") ?: DEFAULT_MAX_BYTES).coerceIn(1, HARD_MAX_BYTES)
    val followRedirects = params.boolOrNull("follow_redirects") ?: true
    val headers = params.headersOrNull()

    val bodyBytes = params.stringOrNull("body")?.let { raw ->
        when (params.stringOrNull("body_encoding")?.lowercase()) {
            "base64" -> runCatching { Base64.decode(raw, Base64.DEFAULT) }
                .getOrElse { return errorJson("INVALID_BODY", "body 不是合法的 base64。") }

            else -> raw.toByteArray(Charsets.UTF_8)
        }
    }
    if (bodyBytes != null && method in setOf("GET", "HEAD")) {
        return errorJson("BODY_NOT_ALLOWED", "$method 请求不能携带 body，请改用 POST/PUT/PATCH。")
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
    return runCatching {
        client.newCall(requestBuilder.build()).execute().use { response ->
            val stream: InputStream = response.body.byteStream()
            val bytes = stream.use { readCappedBytes(it, maxBytes) }
            val declaredSize = response.body.contentLength()
            val elapsed = System.currentTimeMillis() - startedAt

            val saveTo = params.stringOrNull("save_to")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
            val savedPath = saveTo?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.absolutePath
                }.getOrNull()
            }

            val contentTypeHeader = response.header("Content-Type")
            val asBase64 = when (params.stringOrNull("response_encoding")?.lowercase()) {
                "base64" -> true
                "text" -> false
                else -> !looksLikeText(contentTypeHeader)
            }
            val truncated = declaredSize >= 0 && bytes.size.toLong() < declaredSize

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
                if (savedPath != null) put("saved_to", savedPath)
            }.toString()
        }
    }.getOrElse {
        errorJson("REQUEST_FAILED", "请求失败：${it.message ?: it::class.simpleName}")
    }
}

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

/** 只有明确是文本类（text/*、JSON、XML、JS、表单）时才按文本返回，其余按二进制处理。 */
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