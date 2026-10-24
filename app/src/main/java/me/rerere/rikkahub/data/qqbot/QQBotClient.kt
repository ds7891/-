package me.rerere.rikkahub.data.qqbot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.QQBotConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 连接探测结果。 */
data class QQBotStatus(
    val reachable: Boolean,
    val selfId: String = "",
    val nickname: String = "",
    /** 失败原因；成功时为 null */
    val detail: String? = null,
)

/** 发送结果。 */
sealed class QQBotSendResult {
    data class Sent(val targetLabel: String, val messageId: String) : QQBotSendResult()
    data class Failed(val code: String, val reason: String) : QQBotSendResult()
}

/** 一次投递的目标。 */
data class QQBotTarget(
    val action: String,
    val key: String,
    val value: String,
    val label: String,
    /** 群内还是私聊，人设与节流都按这个区分 */
    val isGroup: Boolean,
)

/**
 * OneBot v11 的 HTTP 客户端。
 *
 * 覆盖 NapCat / LLOneBot / Lagrange.Core / NoneBot 这类自建实现：
 * 它们在本地或局域网开一个 HTTP 服务，`POST /{action}` 就是标准调用方式。
 *
 * 官方 QQ 开放平台接口形态完全不同（appid + secret 换 token、以被动回复为主、
 * 主动消息有频次与模板限制），不走这里。
 */
object QQBotClient {

    private const val TAG = "QQBotClient"
    private const val JSON_TYPE = "application/json; charset=utf-8"

    /** QQ 单条消息长度上限：超了会被上游截断甚至整条丢弃，本地先切干净。 */
    const val MAX_MESSAGE_CHARS = 1200

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // ------------------------------------------------------------------
    // 配置校验
    // ------------------------------------------------------------------

    /** 归一化地址：容忍少写协议头、结尾多斜杠、误粘了 action 路径。 */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        return Regex("^(https?://[^/]+)").find(withScheme)?.groupValues?.get(1)
    }

    /** 把 http(s) 地址换成 ws(s)，用于反向 WebSocket。 */
    fun toWebSocketUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val base = when {
            trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            else -> normalizeBaseUrl(trimmed)?.replace("http://", "ws://")?.replace("https://", "wss://")
        } ?: return null
        return base.removeSuffix("/")
    }

    /** 连接所需的最小校验；返回 null 表示可用。 */
    fun checkEndpoint(config: QQBotConfig): String? = when {
        config.baseUrl.isBlank() -> "未填写 HTTP 地址"
        normalizeBaseUrl(config.baseUrl) == null -> "HTTP 地址格式不对，应形如 http://192.168.1.10:3000"
        else -> null
    }

    /** 发送所需的完整校验；返回 null 表示可用。 */
    fun checkSendable(config: QQBotConfig): String? =
        checkEndpoint(config) ?: if (!config.hasTarget) "私聊 QQ 号和群号至少要填一个" else null

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    /** 探测连通性并取回机器人自身身份，供设置页「测试连接」。 */
    suspend fun getLoginInfo(config: QQBotConfig): QQBotStatus {
        checkEndpoint(config)?.let { return QQBotStatus(false, detail = it) }
        return when (val response = call(config, "get_login_info", buildJsonObject {})) {
            is CallResult.Err -> QQBotStatus(false, detail = response.reason)
            is CallResult.Ok -> QQBotStatus(
                reachable = true,
                selfId = response.data.stringish("user_id"),
                nickname = response.data.stringish("nickname")
                    .ifBlank { response.data.stringish("card") },
            )
        }
    }

    /**
     * 发一条文本消息。目标优先级：显式参数 > 配置默认值（[QQBotConfig.preferGroup] 决定群还是私聊）。
     *
     * @param replyTo 群内引用回复的消息 id，让机器人像真人一样接话而不是凭空冒出一句
     * @param atUserId 回复时顺带 @ 的人
     */
    suspend fun sendText(
        config: QQBotConfig,
        text: String,
        userId: String = "",
        groupId: String = "",
        replyTo: String = "",
        atUserId: String = "",
    ): QQBotSendResult {
        checkSendable(config)?.let { return QQBotSendResult.Failed("NOT_CONFIGURED", it) }
        val body = text.trim()
        if (body.isEmpty()) return QQBotSendResult.Failed("EMPTY_TEXT", "消息内容为空")

        val target = pickTarget(config, userId, groupId)
            ?: return QQBotSendResult.Failed("NO_TARGET", "没有可用的发送目标")

        val prefix = if (atUserId.isNotBlank() && target.isGroup) "[CQ:at,qq=$atUserId] " else ""
        val clipped = if (body.length > MAX_MESSAGE_CHARS) {
            body.take(MAX_MESSAGE_CHARS) + "\n…（内容过长，已截断）"
        } else {
            body
        }

        val payload = buildJsonObject {
            put(target.key, idValue(target.value))
            put("message", prefix + clipped)
            put("auto_escape", false) // 关掉转义，让 [CQ:...] 生效
            if (replyTo.isNotBlank()) put("reply_message_id", idValue(replyTo))
        }
        return when (val response = call(config, target.action, payload)) {
            is CallResult.Err -> QQBotSendResult.Failed(response.code, response.reason)
            is CallResult.Ok -> QQBotSendResult.Sent(target.label, response.data.stringish("message_id"))
        }
    }

    /** 组装一条推送：加来源标记、可选模型名、控制长度。通知与工具共用。 */
    fun buildPushText(text: String, modelName: String? = null, limit: Int = 800): String = buildString {
        append("【清水】")
        if (!modelName.isNullOrBlank()) append(" · ").append(modelName)
        append('\n')
        val body = text.trim()
        append(if (body.length > limit) body.take(limit) + "…" else body)
    }

    /** 显式参数优先；都没给时按 preferGroup 选配置里的默认目标。 */
    private fun pickTarget(config: QQBotConfig, userId: String, groupId: String): QQBotTarget? {
        if (groupId.isNotBlank()) return groupTarget(groupId.trim())
        if (userId.isNotBlank()) return privateTarget(userId.trim())
        val u = config.targetUserId.trim()
        val g = config.targetGroupId.trim()
        return if (config.preferGroup) {
            g.takeIf { it.isNotEmpty() }?.let(::groupTarget) ?: u.takeIf { it.isNotEmpty() }?.let(::privateTarget)
        } else {
            u.takeIf { it.isNotEmpty() }?.let(::privateTarget) ?: g.takeIf { it.isNotEmpty() }?.let(::groupTarget)
        }
    }

    fun privateTarget(id: String) = QQBotTarget("send_private_msg", "user_id", id, "私聊 $id", false)
    fun groupTarget(id: String) = QQBotTarget("send_group_msg", "group_id", id, "群 $id", true)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private sealed class CallResult {
        class Ok(val data: JsonObject) : CallResult()
        class Err(val code: String, val reason: String) : CallResult()
    }

    private suspend fun call(
        config: QQBotConfig,
        action: String,
        payload: JsonObject,
    ): CallResult {
        val base = normalizeBaseUrl(config.baseUrl)
            ?: return CallResult.Err("BAD_URL", "HTTP 地址格式不对")
        val request = Request.Builder()
            .url("$base/$action")
            .post(payload.toString().toRequestBody(JSON_TYPE.toMediaType()))
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
            .build()

        val (code, raw) = try {
            // OkHttp 的 execute 阻塞，不包 runInterruptible 的话协程取消了也停不住
            runInterruptible(Dispatchers.IO) {
                client.newCall(request).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return CallResult.Err("TIMEOUT", "连接超时，检查地址与端口是否可达")
        } catch (e: ConnectException) {
            return CallResult.Err("UNREACHABLE", "连不上 $base：${e.message ?: "连接被拒绝"}")
        } catch (e: Exception) {
            Log.w(TAG, "$action failed", e)
            return CallResult.Err("IO_ERROR", e.message ?: "请求失败")
        }

        if (code == 401 || code == 403) {
            return CallResult.Err("AUTH", "鉴权失败（$code）：access_token 不对，或实现端没放行本机")
        }
        if (code == 404) {
            return CallResult.Err("NO_ACTION", "接口 404：地址要指向 OneBot 的 HTTP 服务端口，不是 WebSocket 端口")
        }
        if (code !in 200..299) {
            return CallResult.Err("HTTP_$code", "上游返回 $code：${raw.take(160)}")
        }

        val json = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return CallResult.Err("BAD_RESPONSE", "响应不是合法 JSON：${raw.take(160)}")
        val retcode = json["retcode"]?.jsonPrimitive?.retcodeInt() ?: 0
        val status = json["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (retcode != 0 || status.equals("failed", ignoreCase = true)) {
            val wording = json["message"]?.jsonPrimitive?.contentOrNull
                ?: json["wording"]?.jsonPrimitive?.contentOrNull
                ?: "retcode=$retcode"
            return CallResult.Err("RET_$retcode", "上游拒绝：$wording")
        }
        return CallResult.Ok((json["data"] as? JsonObject) ?: JsonObject(emptyMap()))
    }

    /** id 能转数字就转数字（OneBot 规范是 number），否则按字符串传。 */
    private fun idValue(raw: String): JsonPrimitive =
        raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)

    private fun JsonObject.stringish(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonPrimitive.retcodeInt(): Int? =
        contentOrNull?.toIntOrNull() ?: contentOrNull?.toLongOrNull()?.toInt()
}
