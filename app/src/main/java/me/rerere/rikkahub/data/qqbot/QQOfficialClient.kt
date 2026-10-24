package me.rerere.rikkahub.data.qqbot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 腾讯官方 QQ 机器人（q.qq.com 申请的 AppID / AppSecret）。
 *
 * 与 OneBot 的关键差异，实现时都绕不过去：
 * 1. 身份是 openid 不是 QQ 号：群用 group_openid，人用 user_openid，都从事件里取，
 *    没法自己编，所以「默认目标」这类配置对官方协议没意义；
 * 2. 群里只有 @ 机器人才会收到事件推送 —— 正好符合「被 @ 才答」，不用自己筛；
 * 3. 回复有窗口：被动回复必须在收到消息后 60 秒内、且最多 5 次，
 *    超窗就只能走主动消息，而主动消息有每日配额。所以回答要快，长答案要精简。
 */
object QQOfficialClient {

    private const val TAG = "QQOfficial"
    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
    private const val API_HOST = "https://api.sgroup.qq.com"
    private const val JSON_TYPE = "application/json; charset=utf-8"

    /** 群与好友事件 intent：1 << 25。 */
    private const val INTENT_GROUP_AND_C2C = 1 shl 25

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private data class CachedToken(val value: String, val expireAt: Long)

    @Volatile
    private var cached: CachedToken? = null

    @Volatile
    private var cachedKey: String = ""

    /** access_token 有 7200 秒寿命，提前两分钟换新的，避免请求正好卡在过期边界。 */
    suspend fun accessToken(config: QQBotConfig): Result<String> {
        if (config.appId.isBlank() || config.appSecret.isBlank()) {
            return Result.failure(IllegalStateException("AppID 或 AppSecret 为空"))
        }
        val key = "${config.appId}:${config.appSecret}"
        val now = System.currentTimeMillis()
        cached?.takeIf { cachedKey == key && it.expireAt > now + 120_000L }?.let { return Result.success(it.value) }

        val body = buildJsonObject {
            put("appId", config.appId.trim())
            put("clientSecret", config.appSecret.trim())
        }.toString()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body.toRequestBody(JSON_TYPE.toMediaType()))
            .build()
        return runCatching {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("取 token 失败 HTTP ${resp.code}：${raw.take(160)}")
                    val json = JsonInstant.parseToJsonElement(raw).jsonObject
                    val token = json.stringish("access_token")
                    if (token.isBlank()) error("响应里没有 access_token：${raw.take(160)}")
                    val ttl = json["expires_in"]?.jsonPrimitive
                        ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 7200L
                    cached = CachedToken(token, now + ttl * 1000L)
                    cachedKey = key
                    token
                }
            }
        }.onFailure { Log.w(TAG, "accessToken failed", it) }
    }

    /** 校验 AppID / AppSecret 能不能换到 token，设置页「测试连接」用。 */
    suspend fun probe(config: QQBotConfig): QQBotStatus = when {
        config.appId.isBlank() || config.appSecret.isBlank() ->
            QQBotStatus(false, detail = "AppID 或 AppSecret 没填")

        else -> accessToken(config).fold(
            onSuccess = { QQBotStatus(true, selfId = config.appId, nickname = "官方机器人") },
            onFailure = { QQBotStatus(false, detail = it.message ?: "取 token 失败") },
        )
    }

    /**
     * 发文本消息。[groupOpenid] 非空走群接口，否则走 C2C 私聊。
     * [quoteMessageId] 传事件里的 message_id 即为被动回复（60 秒窗口内有效）。
     */
    suspend fun sendText(
        config: QQBotConfig,
        text: String,
        groupOpenid: String = "",
        userOpenid: String = "",
        quoteMessageId: String = "",
    ): QQBotSendResult {
        val body = text.trim()
        if (body.isEmpty()) return QQBotSendResult.Failed("EMPTY_TEXT", "消息内容为空")
        val path = when {
            groupOpenid.isNotBlank() -> "/v2/groups/$groupOpenid/messages"
            userOpenid.isNotBlank() -> "/v2/users/$userOpenid/messages"
            else -> return QQBotSendResult.Failed("NO_TARGET", "没有 group_openid / user_openid，无法投递")
        }
        val label = if (groupOpenid.isNotBlank()) "群 ${groupOpenid.take(10)}…" else "私聊 ${userOpenid.take(10)}…"
        val token = accessToken(config).getOrElse {
            return QQBotSendResult.Failed("NO_TOKEN", it.message ?: "取 token 失败")
        }

        val payload = buildJsonObject {
            put("msg_type", 0) // 0 = 文本
            put("content", if (body.length > 1500) body.take(1500) + "\n…" else body)
            // 被动回复要带原消息 id；超 60 秒窗口会被拒，那时只能退化成主动消息
            if (quoteMessageId.isNotBlank()) put("id", quoteMessageId)
        }
        return post(config, token, path, payload.toString(), label)
    }

    private suspend fun post(
        config: QQBotConfig,
        token: String,
        path: String,
        body: String,
        label: String,
    ): QQBotSendResult {
        val request = Request.Builder()
            .url("$API_HOST$path")
            .post(body.toRequestBody(JSON_TYPE.toMediaType()))
            .header("Authorization", "QQBot $token")
            .build()
        val (code, raw) = try {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                http.newCall(request).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return QQBotSendResult.Failed("IO_ERROR", e.message ?: "请求失败")
        }

        val json = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        val retcode = json?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if (code == 401) {
            // token 被提前吊销时清缓存，下次自动换新的
            cached = null
            return QQBotSendResult.Failed("TOKEN_EXPIRED", "token 已失效，正在重新获取，请重试一次")
        }
        if (code !in 200..299 || retcode != 0) {
            val msg = json?.stringish("message").orEmpty().ifBlank { raw.take(140) }
            return QQBotSendResult.Failed("HTTP_$code", "上游拒绝：$msg")
        }
        return QQBotSendResult.Sent(label, json.stringish("id").ifBlank { json.stringish("message_id") })
    }

}

/** 文件级扩展：object 与 class 两处都要用，放在成员里另一边就解析不到。 */
internal fun JsonObject.stringish(key: String): String =
    this[key]?.let { p ->
        (p as? JsonPrimitive)?.let { it.contentOrNull ?: it.longOrNull?.toString() }
    }.orEmpty()

/**
 * 官方机器人的 WebSocket 长连接：换 token → 取网关 → Identify → 心跳 → 收事件。
 *
 * 只负责「收到消息」，回复走 [QQOfficialClient.sendText] 的 HTTP 接口。
 */
class QQOfficialSocketClient(private val scope: CoroutineScope) {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _state = MutableStateFlow<QQBotLinkState>(QQBotLinkState.Idle)
    val state: StateFlow<QQBotLinkState> = _state.asStateFlow()

    private val _inbounds = MutableSharedFlow<QQBotInbound>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val inbounds: SharedFlow<QQBotInbound> = _inbounds.asSharedFlow()

    val isConnected: Boolean get() = _state.value is QQBotLinkState.Online

    private var job: Job? = null
    private var socket: WebSocket? = null

    fun start(config: QQBotConfig) {
        stop()
        if (config.appId.isBlank() || config.appSecret.isBlank()) {
            _state.value = QQBotLinkState.Offline("AppID 或 AppSecret 没填")
            return
        }
        _state.value = QQBotLinkState.Connecting
        job = scope.launch(Dispatchers.IO) { runLoop(config) }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close(1000, "shutdown") }
        socket = null
        _state.value = QQBotLinkState.Idle
    }

    private suspend fun runLoop(config: QQBotConfig) {
        var attempt = 0
        while (isActive) {
            _state.value = QQBotLinkState.Connecting
            val token = QQOfficialClient.accessToken(config).getOrElse { err ->
                _state.value = QQBotLinkState.Offline("取 token 失败：${err.message}")
                attempt++
                delay(minOf(2_000L * attempt, 30_000L))
                continue
            }
            val gateway = fetchGateway(token).getOrElse { err ->
                _state.value = QQBotLinkState.Offline("取网关失败：${err.message}")
                attempt++
                delay(minOf(2_000L * attempt, 30_000L))
                continue
            }

            val closed = CompletableDeferred<String>()
            var lastSeq: Long? = null
            var identified = false
            var heartbeatMs = 41_250L
            val ws = http.newWebSocket(
                Request.Builder().url(gateway).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        val op = json["op"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                        val t = json.stringish("t")
                        (json["s"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() })
                            ?.let { lastSeq = it }
                        when (op) {
                            // 服务端要求心跳 / 下发心跳间隔
                            11 -> {
                                val interval = json["d"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                                if (interval != null && interval > 5_000) heartbeatMs = interval
                                if (!identified) identify(webSocket, token)
                                else webSocket.send("""{"op":1,"d":${lastSeq ?: "null"}}""")
                            }
                            // 推送：READY / RESUMED / 业务事件
                            10 -> when {
                                t == "READY" || t == "RESUMED" -> {
                                    identified = true
                                    _state.value = QQBotLinkState.Online(config.appId)
                                    webSocket.send("""{"op":1,"d":${lastSeq ?: "null"}}""")
                                }
                                t.isNotEmpty() -> if (!identified) identify(webSocket, token) else parseEvent(t, json)?.let {
                                    if (!_inbounds.tryEmit(it)) Log.w(TAG, "入站队列已满，丢弃一条消息")
                                }
                                else -> if (!identified) identify(webSocket, token)
                            }
                            // 鉴权成功
                            12 -> {
                                identified = true
                                _state.value = QQBotLinkState.Online(config.appId)
                            }
                            13 -> _state.value = QQBotLinkState.Online(config.appId)
                            // 要求重连
                            7 -> closed.complete("服务端要求重连")
                            // session 失效，需要重新 Identify
                            9 -> {
                                identified = false
                                identify(webSocket, token)
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete("对端关闭（$code $reason）")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(t.message ?: "连接中断")
                    }
                },
            )
            socket = ws

            // 官方要求建连后主动 Identify，不等服务端催
            identify(ws, token)
            val reason = try {
                while (true) {
                    delay(heartbeatMs)
                    if (!ws.send("""{"op":1,"d":${lastSeq ?: "null"}}""")) break
                }
                "心跳发送失败"
            } catch (e: CancellationException) {
                runCatching { ws.cancel() }
                throw e
            }

            runCatching { ws.cancel() }
            socket = null
            _state.value = QQBotLinkState.Offline(reason)
            attempt++
            delay(minOf(1_000L * (1 shl (attempt - 1).coerceAtMost(5)), 30_000L))
        }
    }

    private fun identify(ws: WebSocket, token: String) {
        ws.send(
            buildJsonObject {
                put("op", 2)
                put("d", buildJsonObject {
                    put("token", "QQBot $token")
                    put("intents", INTENTS)
                    put("shard", buildJsonArray { add(0); add(1) })
                })
            }.toString(),
        )
    }

    private suspend fun fetchGateway(token: String): Result<String> = runCatching {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$API_HOST/gateway")
                .header("Authorization", "QQBot $token")
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "HTTP ${resp.code}：${raw.take(140)}" }
                val url = JsonInstant.parseToJsonElement(raw).jsonObject.stringish("url")
                check(url.isNotBlank()) { "响应里没有网关地址：${raw.take(140)}" }
                url
            }
        }
    }

    /** 解析 GROUP_AT_MESSAGE_CREATE / C2C_MESSAGE_CREATE。 */
    private fun parseEvent(type: String, json: JsonObject): QQBotInbound? {
        val d = (json["d"] as? JsonObject) ?: return null
        val message = (d["message"] as? JsonObject) ?: return null
        val content = message.stringish("content")
        val groupId = d.stringish("group_id")
        val author = (d["author"] as? JsonObject)
        val userId = firstNonBlank(
            d.stringish("user_id"),
            author?.stringish("user_openid"),
            author?.stringish("member_openid"),
        )
        if (userId.isBlank() && groupId.isBlank()) return null

        return QQBotInbound(
            messageId = firstNonBlank(message.stringish("message_id"), d.stringish("id")),
            isGroup = type == "GROUP_AT_MESSAGE_CREATE" || groupId.isNotBlank(),
            groupId = groupId,
            userId = userId,
            senderName = "", // 官方事件不给昵称，只有 openid
            text = content.replace(Regex("""@\S+"""), " ").trim(),
            // 群事件本身就是「被 @ 才推」，私聊不需要 @
            mentionedMe = type == "GROUP_AT_MESSAGE_CREATE" || message["mention_me"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true,
            selfId = "",
        )
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private companion object {
        const val TAG = "QQOfficialWS"
        /** 群与好友事件 1 << 25 */
        const val INTENTS = 33_554_432
    }
}
