package me.rerere.rikkahub.data.qqbot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** 一条从 QQ 进来的消息，已经把 CQ 码整理成人能读的正文。 */
data class QQBotInbound(
    val messageId: String,
    val isGroup: Boolean,
    val groupId: String,
    val userId: String,
    val senderName: String,
    /** 剥掉 @ 机器人和其它 CQ 码后的正文 */
    val text: String,
    /** 正文里是否 @ 了机器人自己 */
    val mentionedMe: Boolean,
    /** 机器人自己的号，用于回消息时的自我识别 */
    val selfId: String,
) {
    /** 会话映射用的稳定键。 */
    val bindingKey: String get() = if (isGroup) "group:$groupId" else "private:$userId"
}

/** 反向连接的状态，设置页直接显示。 */
sealed class QQBotLinkState {
    data object Idle : QQBotLinkState()
    data object Connecting : QQBotLinkState()
    data class Online(val selfId: String) : QQBotLinkState()
    data class Offline(val reason: String) : QQBotLinkState()
}

/**
 * OneBot v11 反向 WebSocket 客户端。
 *
 * 为什么走反向 WS 而不是让实现端 HTTP 回调手机：
 * 回调要求实现端能主动访问到手机，而手机 IP 会变、后台 socket 会被系统回收，
 * 加上 NAT 与防火墙，链路根本不稳。反向 WS 只需要手机能连到实现端
 * （和发消息同一个方向），一条长连接挂上去就行，断了自己重连。
 */
class QQBotSocketClient(private val scope: CoroutineScope) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // 让中间设备别把空闲连接掐了
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _state = MutableStateFlow<QQBotLinkState>(QQBotLinkState.Idle)
    val state: StateFlow<QQBotLinkState> = _state.asStateFlow()

    private val _inbounds = MutableSharedFlow<QQBotInbound>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val inbounds: SharedFlow<QQBotInbound> = _inbounds.asSharedFlow()

    private var job: Job? = null
    private var socket: WebSocket? = null

    /** 已经连着就别重复建链。 */
    val isConnected: Boolean get() = _state.value is QQBotLinkState.Online

    fun start(config: QQBotConfig) {
        stop()
        val url = QQBotClient.toWebSocketUrl(config.wsUrl.ifBlank { config.baseUrl })
        if (url == null) {
            _state.value = QQBotLinkState.Offline("地址无法解析成 WebSocket")
            return
        }
        _state.value = QQBotLinkState.Connecting
        job = scope.launch { runLoop(config, url) }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close(1000, "shutdown") }
        socket = null
        _state.value = QQBotLinkState.Idle
    }

    // ------------------------------------------------------------------

    private suspend fun runLoop(config: QQBotConfig, url: String) {
        var attempt = 0
        while (scope.isActive && isActiveJob()) {
            _state.value = if (attempt == 0) QQBotLinkState.Connecting
            else QQBotLinkState.Connecting

            val closed = kotlinx.coroutines.CompletableDeferred<String>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // 连上先不标 Online：等收到带 self_id 的事件才算真的通了
                    Log.i(TAG, "反向 WS 已连接 $url")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete("对端关闭（$code $reason）")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (t is CancellationException) throw t
                    closed.complete(t.message ?: "连接中断")
                }
            }

            val request = Request.Builder().url(url).apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}")
            }.build()

            socket = client.newWebSocket(request, listener)
            val reason = try {
                closed.await()
            } catch (e: CancellationException) {
                runCatching { socket?.cancel() }
                throw e
            }

            runCatching { socket?.cancel() }
            socket = null
            _state.value = QQBotLinkState.Offline(reason)

            attempt++
            // 指数退避封顶 30s：实现端重启时不至于把手机电耗光
            val backoff = minOf(1_000L * (1 shl (attempt - 1).coerceAtMost(5)), 30_000L)
            Log.w(TAG, "反向 WS 断开（$reason），${backoff}ms 后第 $attempt 次重连")
            delay(backoff)
        }
    }

    private fun isActiveJob(): Boolean = job?.isActive != false

    private fun handleFrame(raw: String) {
        val json = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val postType = json.stringish("post_type")

        when {
            // 心跳与 API 响应不参与对话
            postType == "heartbeat" || postType == "response" -> Unit
            postType == "meta_event" -> {
                val metaType = json.stringish("meta_event_type")
                if (metaType == "lifecycle") {
                    val self = json.stringish("self_id")
                    if (self.isNotBlank()) _state.value = QQBotLinkState.Online(self)
                }
            }

            postType == "message" -> parseMessage(json)?.let { inbound ->
                if (!_inbounds.tryEmit(inbound)) Log.w(TAG, "入站队列已满，丢弃一条消息")
            }

            else -> Unit
        }
    }

    private fun parseMessage(json: JsonObject): QQBotInbound? {
        val selfId = json.stringish("self_id")
        val isGroup = json.stringish("message_type") == "group"
        val groupId = json.stringish("group_id")
        val userId = json.stringish("user_id")
        if (userId.isBlank() && groupId.isBlank()) return null

        val raw = json["message"]
        var text = ""
        var mentioned = false
        when (raw) {
            is JsonPrimitive -> {
                val content = raw.contentOrNull.orEmpty()
                mentioned = content.containsAtOf(selfId)
                text = stripCqCodes(content)
            }

            else -> {
                val segments = runCatching { raw.jsonArray }.getOrNull() ?: return null
                val builder = StringBuilder()
                segments.forEach { element ->
                    val seg = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                    when (seg.stringish("type")) {
                        "text" -> builder.append(seg["data"]?.jsonObject?.stringish("text"))
                        "at" -> {
                            val qq = seg["data"]?.jsonObject?.stringish("qq").orEmpty()
                            if (selfId.isNotBlank() && qq == selfId) {
                                // @ 机器人自己只是「呼叫」，不算内容，留在正文里会让模型以为有人在跟它说话的名字是它
                                mentioned = true
                            } else {
                                // @ 别人时保留名字，机器人看得懂这句话在说谁
                                val name = seg["data"]?.jsonObject?.stringish("name")
                                if (name.isNotBlank()) builder.append('@').append(name).append(' ')
                            }
                        }

                        "image" -> builder.append("[图片]")
                        "record" -> builder.append("[语音]")
                        "face" -> Unit
                        "reply" -> Unit
                        else -> Unit
                    }
                }
                text = builder.toString()
            }
        }

        // 自己发的消息不回，否则机器人会自己跟自己聊到天荒地老
        if (selfId.isNotBlank() && userId == selfId) return null

        val sender = runCatching { json["sender"]?.jsonObject }.getOrNull()
        val senderName = firstNonBlank(
            sender?.stringish("card"),
            sender?.stringish("nickname"),
            json.stringish("user_id"),
        )

        return QQBotInbound(
            messageId = json.stringish("message_id"),
            isGroup = isGroup,
            groupId = groupId,
            userId = userId,
            senderName = senderName,
            text = text.trim(),
            mentionedMe = mentioned,
            selfId = selfId,
        )
    }

    private fun String.containsAtOf(selfId: String): Boolean =
        selfId.isNotBlank() && (contains("[CQ:at,qq=$selfId]") || contains("@$selfId"))

    /** 去掉 CQ 码噪音，只留可读正文。 */
    private fun stripCqCodes(content: String): String = content
        .replace(Regex("""\[CQ:at,qq=\d+]"""), " ")
        .replace(Regex("""\[CQ:(image|record|face|video|file)[^\]]*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun JsonObject.stringish(key: String): String =
        this[key]?.let { element ->
            val primitive = element as? JsonPrimitive ?: return@let null
            primitive.contentOrNull ?: primitive.longOrNull?.toString() ?: primitive.intOrNull?.toString()
        }.orEmpty()

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private companion object {
        const val TAG = "QQBotSocket"
    }
}
