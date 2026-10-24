package me.rerere.rikkahub.data.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

private val json = Json { ignoreUnknownKeys = true }
private const val MAX_INTERCEPT_BODY_BYTES = 2 * 1024 * 1024

/**
 * 插件网络拦截器：在请求发出前与响应返回后，把 URL / 方法 / 头 / 正文交给插件的
 * onRequest / onResponse，插件返回的 JSON 可以整体替换这三部分。
 *
 * 只处理 JSON / 文本类正文，跳过流式（SSE）响应，避免打断 AI 流式输出；
 * 插件抛错或返回非法 JSON 时静默放行原始内容。
 */
class PluginInterceptInterceptor(private val pluginManager: PluginManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val plugins = pluginManager.interceptPlugins()
        if (plugins.isEmpty()) return chain.proceed(chain.request())

        val requestPlugins = plugins.filter { it.manifest.intercept.request }
        val responsePlugins = plugins.filter { it.manifest.intercept.response }

        var request = chain.request()
        if (requestPlugins.isNotEmpty()) {
            request = applyRequestHooks(request, requestPlugins)
        }
        val response = chain.proceed(request)
        if (responsePlugins.isEmpty()) return response
        return applyResponseHooks(response, responsePlugins)
    }

    private fun applyRequestHooks(
        original: Request,
        plugins: List<InstalledPlugin>,
    ): Request {
        val bodyText = runCatching {
            original.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                if (buffer.size > MAX_INTERCEPT_BODY_BYTES) return original
                buffer.readUtf8()
            }
        }.getOrNull()

        var payload = buildJsonObject {
            put("url", original.url.toString())
            put("method", original.method)
            put(
                "headers",
                buildJsonObject {
                    original.headers.forEach { (name, value) -> put(name, value) }
                },
            )
            put("body", bodyText ?: "")
        }

        var changed = false
        plugins.forEach { plugin ->
            val result = runBlocking {
                pluginManager.runIntercept(plugin.manifest.id, "onRequest", payload.toString())
            } ?: return@forEach
            val next = parsePayload(result) ?: return@forEach
            payload = next
            changed = true
        }

        if (!changed) return original

        val newUrl = payload.stringOrNull("url") ?: original.url.toString()
        val newMethod = payload.stringOrNull("method")?.uppercase() ?: original.method
        val newBody = payload.stringOrNull("body") ?: bodyText
        val builder = original.newBuilder().url(newUrl)
        payload["headers"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()?.let { headers ->
                original.headers.names().forEach { builder.removeHeader(it) }
                headers.forEach { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
                }
            }
        }
        val mediaType = original.body?.contentType() ?: "application/json".toMediaTypeOrNull()
        builder.method(newMethod, newBody?.toRequestBody(mediaType))
        return builder.build()
    }

    private fun applyResponseHooks(
        response: Response,
        plugins: List<InstalledPlugin>,
    ): Response {
        val contentType = response.header("Content-Type").orEmpty()
        // 流式响应（SSE）不能缓冲，否则会破坏增量输出
        if (contentType.contains("event-stream", ignoreCase = true)) return response
        val isText = contentType.contains("json", ignoreCase = true) ||
            contentType.startsWith("text/", ignoreCase = true)
        if (!isText) return response

        val body = response.body ?: return response
        val bytes = runCatching { body.bytes() }.getOrNull() ?: return response
        if (bytes.size > MAX_INTERCEPT_BODY_BYTES) {
            // 已经消费了 body，必须用原字节重建，避免上层拿到空响应
            return rebuild(response, bytes, contentType)
        }
        var payload = buildJsonObject {
            put("url", response.request.url.toString())
            put("status", response.code)
            put(
                "headers",
                buildJsonObject { response.headers.forEach { (n, v) -> put(n, v) } },
            )
            put("body", String(bytes, Charsets.UTF_8))
        }
        var changed = false
        plugins.forEach { plugin ->
            val result = runBlocking {
                pluginManager.runIntercept(plugin.manifest.id, "onResponse", payload.toString())
            } ?: return@forEach
            val next = parsePayload(result) ?: return@forEach
            payload = next
            changed = true
        }
        if (!changed) return rebuild(response, bytes, contentType)

        val newBody = payload.stringOrNull("body") ?: String(bytes, Charsets.UTF_8)
        val builder = response.newBuilder()
        payload["headers"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()?.let { headers ->
                response.headers.names().forEach { builder.removeHeader(it) }
                headers.forEach { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
                }
            }
        }
        return builder
            .body(newBody.toResponseBody(response.body?.contentType()))
            .build()
    }

    private fun rebuild(response: Response, bytes: ByteArray, contentType: String): Response =
        response.newBuilder()
            .body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()

    private fun parsePayload(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
