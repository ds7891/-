package me.rerere.rikkahub.data.builtin

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.OutputStream
import java.util.UUID

private const val TAG = "BuiltinProxy"

/** 上游只接受这些字段，其余本地字段（如自定义标识）会被丢弃。 */
private val ALLOWED_CHAT_FIELDS = setOf(
    "reasoning_effort", "verbosity", "model", "messages", "tools", "functions", "tool_choice",
    "temperature", "max_tokens", "max_completion_tokens", "top_p", "stream", "stream_options",
    "stop", "presence_penalty", "frequency_penalty", "n", "response_format", "seed", "user",
    "reasoning_summary", "thinking",
)

/**
 * 对话上游的证书固定（SPKI SHA-256，取自官方站点真实证书）。
 *
 * 上游叶子证书会随签发周期轮换，这里同时固定了当前叶子与签发它的 DigiCert 中间 CA，
 * 只要命中其一即通过。若官方更换 CA 导致校验失败，可在设置里关闭「防止提示词泄露」。
 */
private val UPSTREAM_CERT_PINNER: CertificatePinner = CertificatePinner.Builder()
    .add(
        "copilot.tencent.com",
        "sha256/msf4AsD2EfdPpQLf6h3td+B1/1V0xJllDZxa+Gdh4yY=",
        "sha256/iRVt6vFAG0+gB4h7KFntBfxhDjw2OHuU0tsCfL5BCW8=",
    )
    .add(
        "www.codebuddy.ai",
        "sha256/zMYhKg0pp4y6tA84kI8pcItoSW/nxJtqydfVpE1lr7Q=",
        "sha256/iRVt6vFAG0+gB4h7KFntBfxhDjw2OHuU0tsCfL5BCW8=",
    )
    .build()

/**
 * 本地反向代理服务：在本机 [DEFAULT_BUILTIN_PROXY_PORT] 端口暴露 OpenAI 兼容 API，
 * 把请求转发到 CodeBuddy / WorkBuddy 上游，并带上已登录账号的令牌。
 */
class BuiltinProxyServer(
    private val repository: BuiltinModelRepository,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** 开启「防止提示词泄露」后对话请求使用的客户端：在共享客户端基础上加证书固定。 */
    private val pinnedClient: OkHttpClient by lazy {
        client.newBuilder()
            .certificatePinner(UPSTREAM_CERT_PINNER)
            .build()
    }

    val isRunning: Boolean get() = server != null

    fun start(port: Int, host: String = "127.0.0.1") {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = host) {
            configure()
        }.start(wait = false)
        Log.i(TAG, "Builtin proxy started on $host:$port")
    }

    fun stop() {
        runCatching { server?.stop(1000, 2000) }
        server = null
        Log.i(TAG, "Builtin proxy stopped")
    }

    private fun Application.configure() {
        install(CORS) {
            anyHost()
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader("x-api-key")
            allowHeader("anthropic-version")
            allowHeader("anthropic-beta")
            allowHeader("openai-organization")
            allowHeader("x-requested-with")
            allowNonSimpleContentTypes = true
            anyMethod()
        }
        routing {
            options("{...}") { call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent) }

            get("/") { call.respondJson("""{"status":"ok","service":"builtin-model-proxy"}""") }
            get("/v1") { call.respondJson("""{"status":"ok","service":"builtin-model-proxy"}""") }
            get("/health") { call.respondJson("""{"status":"ok"}""") }
            get("/healthz") { call.respondJson("""{"status":"ok"}""") }
            get("/status") {
                val state = repository.state.value
                call.respondJson(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("running", JsonPrimitive(state.running))
                        put("accounts", JsonPrimitive(state.accounts.size))
                        put("models", JsonPrimitive(state.models.size))
                    }.toString()
                )
            }

            get("/v1/models") { call.handleModels() }
            get("/v1/models/{model}") { call.handleSingleModel() }

            post("/v1/chat/completions") { call.handleChat() }
            post("/v1/completions") { call.handleChat() }
            post("/v1/responses") { call.handleChat() }
            post("/v1/messages") { call.handleMessages() }
            post("/v1/messages/count_tokens") { call.respondJson("""{"input_tokens":0}""") }
            post("/v1/embeddings") {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "上游未提供 embedding 模型，/v1/embeddings 不支持",
                )
            }

            get("/v1/dashboard/billing/subscription") {
                call.respondJson("""{"object":"billing_subscription","hard_limit_usd":999999,"has_payment_method":true}""")
            }
            get("/v1/dashboard/billing/usage") {
                call.respondJson("""{"object":"list","total_usage":0}""")
            }
        }
    }

    // ---------------------------------------------------------------- 鉴权

    /** 校验本地 API Key；未设置 Key 时不校验。 */
    private suspend fun ApplicationCall.authenticate(): Boolean {
        val expected = repository.state.value.apiKey
        if (expected.isBlank()) return true
        val bearer = request.headers["Authorization"]
            ?.removePrefix("Bearer")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val provided = bearer ?: request.headers["x-api-key"]?.trim()
        if (provided == null || provided != expected) {
            respondError(
                HttpStatusCode.Unauthorized,
                "本地 API Key 无效或缺失，请在「内置模型」页面复制正确的密钥",
                type = "authentication_error",
            )
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- 模型

    private suspend fun ApplicationCall.handleModels() {
        val models = repository.state.value.models
        val payload = buildJsonObject {
            put("object", JsonPrimitive("list"))
            put(
                "data",
                buildJsonArray {
                    models.forEach { model ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(model.id))
                                put("object", JsonPrimitive("model"))
                                put("owned_by", JsonPrimitive("builtin"))
                            }
                        )
                    }
                },
            )
        }
        respondJson(payload.toString())
    }

    private suspend fun ApplicationCall.handleSingleModel() {
        val target = parameters["model"].orEmpty()
        val model = repository.state.value.models.firstOrNull { it.id == target }
        if (model == null) {
            respondError(HttpStatusCode.NotFound, "未知模型：$target")
            return
        }
        respondJson(
            buildJsonObject {
                put("id", JsonPrimitive(model.id))
                put("object", JsonPrimitive("model"))
                put("owned_by", JsonPrimitive("builtin"))
            }.toString()
        )
    }

    // ------------------------------------------------------------ 对话补全

    private suspend fun ApplicationCall.handleChat() {
        if (!authenticate()) return
        val raw = receiveBody()
        val body = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (body == null) {
            respondError(HttpStatusCode.BadRequest, "请求体不是合法 JSON")
            return
        }
        val stream = body.booleanOrNull("stream") ?: false
        val account = repository.acquireAccount()
        if (account == null) {
            respondError(
                HttpStatusCode.Unauthorized,
                "尚未登录内置模型账号，请先在「内置模型」页面登录 CodeBuddy / WorkBuddy 账号",
                type = "authentication_error",
            )
            return
        }
        val upstream = repository.auth.buildChatRequest(account, sanitizeChatBody(body), stream)
        val forwardClient = chatForwardClient(upstream.url) ?: run {
            respondError(
                HttpStatusCode.BadGateway,
                "「防止提示词泄露」已开启：上游非 HTTPS，已拒绝发送以避免明文泄露",
                "upstream_error",
            )
            return
        }
        if (stream) {
            forwardStream(forwardClient, upstream) { text -> text }
        } else {
            forwardJson(forwardClient, upstream) { text -> text }
        }
    }

    private suspend fun ApplicationCall.handleMessages() {
        if (!authenticate()) return
        val raw = receiveBody()
        val body = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (body == null) {
            respondError(HttpStatusCode.BadRequest, "请求体不是合法 JSON")
            return
        }
        val stream = body.booleanOrNull("stream") ?: false
        val account = repository.acquireAccount()
        if (account == null) {
            respondError(
                HttpStatusCode.Unauthorized,
                "尚未登录内置模型账号，请先在「内置模型」页面登录 CodeBuddy / WorkBuddy 账号",
                type = "authentication_error",
            )
            return
        }
        val openAiBody = anthropicToOpenAi(body, stream)
        val model = openAiBody.stringOrNull("model") ?: "auto"
        val upstream = repository.auth.buildChatRequest(account, sanitizeChatBody(openAiBody), stream)
        val forwardClient = chatForwardClient(upstream.url) ?: run {
            respondError(
                HttpStatusCode.BadGateway,
                "「防止提示词泄露」已开启：上游非 HTTPS，已拒绝发送以避免明文泄露",
                "upstream_error",
            )
            return
        }
        if (stream) {
            forwardStream(forwardClient, upstream) { text -> text }
        } else {
            forwardJson(forwardClient, upstream) { text -> openAiToAnthropic(text, model) }
        }
    }

    /**
     * 「防止提示词泄露」：开启后对话请求改用带证书固定的客户端，强制 HTTPS 并校验上游公钥指纹，
     * 阻断中间人 / 代理解开流量。返回 null 表示上游非 HTTPS，调用方应拒绝发送。
     */
    private fun chatForwardClient(url: okhttp3.HttpUrl): OkHttpClient? {
        if (!settingsStore.settingsFlow.value.qingshuiFeatures.preventPromptLeak) return client
        return if (url.isHttps) pinnedClient else null
    }

    /** 非流式转发：读取完整响应体（可选转换）后原样返回状态码。 */
    private suspend fun ApplicationCall.forwardJson(
        forwardClient: OkHttpClient,
        upstream: okhttp3.Request,
        transform: (String) -> String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                forwardClient.newCall(upstream).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        respondJson(
                            errorJson("上游错误 ${response.code}: ${text.take(400)}", "upstream_error"),
                            response.code.toStatus(),
                        )
                    } else {
                        respondJson(runCatching { transform(text) }.getOrDefault(text), response.code.toStatus())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "转发失败", e)
                respondError(HttpStatusCode.BadGateway, e.message ?: "代理请求失败", "upstream_error")
            }
        }
    }

    /** 流式转发：逐块透传上游 SSE。 */
    private suspend fun ApplicationCall.forwardStream(
        forwardClient: OkHttpClient,
        upstream: okhttp3.Request,
        transform: (String) -> String,
    ) {
        respondOutputStream(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
            val out: OutputStream = this
            withContext(Dispatchers.IO) {
                try {
                    forwardClient.newCall(upstream).execute().use { response ->
                        if (!response.isSuccessful) {
                            val error = response.body.string()
                            out.write("data: ${errorJson("上游错误 ${response.code}: ${error.take(400)}", "upstream_error")}\n\n".toByteArray())
                            out.flush()
                            return@use
                        }
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var pending = ByteArray(0)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                val chunk = pending + buffer.copyOf(read)
                                val lastNewline = chunk.indexOfLast { it == '\n'.code.toByte() }
                                if (lastNewline < 0) {
                                    pending = chunk
                                    continue
                                }
                                val complete = chunk.copyOfRange(0, lastNewline + 1)
                                pending = chunk.copyOfRange(lastNewline + 1, chunk.size)
                                val text = complete.decodeToString()
                                out.write(transform(text).toByteArray())
                                out.flush()
                            }
                            if (pending.isNotEmpty()) {
                                out.write(transform(pending.decodeToString()).toByteArray())
                                out.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "流式转发中断", e)
                    runCatching {
                        out.write("data: ${errorJson(e.message ?: "代理请求失败", "upstream_error")}\n\n".toByteArray())
                        out.flush()
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- 协议转换

/** Anthropic `/v1/messages` 请求 → OpenAI `/v1/chat/completions` 请求。 */
private fun anthropicToOpenAi(body: JsonObject, stream: Boolean): JsonObject {
    val messages = buildJsonArray {
        body.stringOrNull("system")?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(it)) })
        }
        (body["messages"] as? JsonArray)?.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            val role = message.stringOrNull("role") ?: "user"
            val content = message["content"]
            when (content) {
                is JsonArray -> add(
                    buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", JsonPrimitive(anthropicBlocksToText(content)))
                    }
                )
                else -> add(
                    buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", content ?: JsonPrimitive(""))
                    }
                )
            }
        }
    }
    return buildJsonObject {
        put("model", JsonPrimitive(body.stringOrNull("model") ?: "auto"))
        put("messages", messages)
        put("stream", JsonPrimitive(stream))
        body.stringOrNull("max_tokens")?.let { put("max_tokens", JsonPrimitive(it)) }
        (body["max_tokens"] as? JsonPrimitive)?.intOrNull?.let { put("max_tokens", JsonPrimitive(it)) }
        (body["temperature"] as? JsonPrimitive)?.let { put("temperature", it) }
        (body["top_p"] as? JsonPrimitive)?.let { put("top_p", it) }
        (body["stop_sequences"] as? JsonArray)?.let { put("stop", it) }
        (body["thinking"] as? JsonObject)?.let { put("thinking", it) }
        (body["tools"] as? JsonArray)?.let { tools ->
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { element ->
                        val tool = element as? JsonObject ?: return@forEach
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(tool.stringOrNull("name") ?: ""))
                                        tool.stringOrNull("description")?.let { put("description", JsonPrimitive(it)) }
                                        tool["input_schema"]?.let { put("parameters", it) }
                                    },
                                )
                            }
                        )
                    }
                },
            )
        }
    }
}

private fun anthropicBlocksToText(blocks: JsonArray): String {
    val builder = StringBuilder()
    blocks.forEach { element ->
        val block = element as? JsonObject ?: return@forEach
        when (block.stringOrNull("type")) {
            "text" -> builder.append(block.stringOrNull("text").orEmpty())
            "tool_result" -> {
                val content = block["content"]
                if (content is JsonPrimitive) builder.append(content.contentOrNull.orEmpty())
            }
            else -> Unit
        }
    }
    return builder.toString()
}

/** OpenAI 非流式响应 → Anthropic 消息响应。 */
private fun openAiToAnthropic(openAiText: String, model: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = runCatching { json.parseToJsonElement(openAiText) as? JsonObject }.getOrNull()
        ?: return openAiText
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return openAiText
    val message = choice["message"] as? JsonObject ?: return openAiText
    val text = message.stringOrNull("content").orEmpty()
    val usage = root["usage"] as? JsonObject
    val inputTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 0
    val outputTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0
    return buildJsonObject {
        put("id", JsonPrimitive(root.stringOrNull("id") ?: "msg_${UUID.randomUUID().toString().replace("-", "")}"))
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive("assistant"))
        put("model", JsonPrimitive(root.stringOrNull("model") ?: model))
        put(
            "content",
            buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) })
            },
        )
        put("stop_reason", JsonPrimitive("end_turn"))
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", JsonPrimitive(inputTokens))
                put("output_tokens", JsonPrimitive(outputTokens))
            },
        )
    }.toString()
}

// -------------------------------------------------------------------- 请求辅助

private fun sanitizeChatBody(body: JsonObject): JsonObject {
    val filtered = buildJsonObject {
        body.forEach { (key, value) ->
            if (key in ALLOWED_CHAT_FIELDS) put(key, value)
        }
    }
    val model = (filtered["model"] as? JsonPrimitive)?.contentOrNull
    return if (model.isNullOrBlank()) {
        JsonObject(filtered + ("model" to JsonPrimitive("auto")))
    } else {
        filtered
    }
}

/** 校验本地 API Key：请求头支持 `Authorization: Bearer xxx` 或 `x-api-key: xxx`。 */
private suspend fun ApplicationCall.receiveBody(): String =
    withContext(Dispatchers.IO) { receiveStream().readBytes().decodeToString() }

private suspend fun ApplicationCall.respondJson(
    text: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
    type: String = "invalid_request_error",
) = respondText(errorJson(message, type), ContentType.Application.Json, status)

private fun errorJson(message: String, type: String = "invalid_request_error"): String =
    buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive(type))
                put("code", JsonPrimitive(type))
            },
        )
    }.toString()

private fun Int.toStatus(): HttpStatusCode = HttpStatusCode.fromValue(this)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
