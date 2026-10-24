package me.rerere.rikkahub.data.ai.tools.local

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.coroutines.resume

/** SSH 工具注册名。 */
const val SSH_TOOL = "ssh_exec"

/** 原始 TCP 工具注册名。 */
const val TCP_TOOL = "tcp_request"

/** WebSocket 工具注册名。 */
const val WEBSOCKET_TOOL = "ws_request"

private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val MAX_TIMEOUT_SECONDS = 300
private const val DEFAULT_MAX_BYTES = 256 * 1024
private const val DEFAULT_READ_IDLE_MS = 1500

private fun textOnly(json: String): List<UIMessagePart> = listOf(UIMessagePart.Text(json))

private fun toolError(code: String, message: String): String = buildJsonObject {
    put("error", code)
    put("message", message)
}.toString()

/** SSH：登录远程主机执行命令，或通过 SFTP 上传 / 下载文件。 */
internal fun buildSshTool(): Tool = Tool(
    name = SSH_TOOL,
    description = """
        通过 SSH 登录远程主机并执行命令，或用 SFTP 上传 / 下载文件。
        适用于 http_request 做不到的场景：登录服务器、跑 shell、看系统状态、传文件、维护 VPS 等。
        参数：
        - host / username 必填，port 默认 22
        - 认证二选一：password；或 private_key（本机私钥文件路径）+ private_key_passphrase
        - action：exec（默认，执行 command）、upload（local_path → remote_path）、download（remote_path → local_path）
        - 首次连接不校验证书指纹（等同 ssh -o StrictHostKeyChecking=no），请只连你自己信任的机器
        返回 JSON：exec 给 stdout / stderr / exit_code；upload / download 给写入的字节数与目标路径。
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "远程主机地址或 IP。")
                })
                put("port", buildJsonObject {
                    put("type", "integer")
                    put("description", "SSH 端口，默认 22。")
                })
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "登录用户名。")
                })
                put("password", buildJsonObject {
                    put("type", "string")
                    put("description", "登录密码；与 private_key 二选一。")
                })
                put("private_key", buildJsonObject {
                    put("type", "string")
                    put("description", "本机私钥文件路径（id_rsa / id_ed25519 等）。")
                })
                put("private_key_passphrase", buildJsonObject {
                    put("type", "string")
                    put("description", "私钥口令，没有可留空。")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("exec")
                            add("upload")
                            add("download")
                        },
                    )
                    put("description", "操作类型，默认 exec。")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "action=exec 时要执行的命令。")
                })
                put("local_path", buildJsonObject {
                    put("type", "string")
                    put("description", "action=upload / download 时的本机路径。")
                })
                put("remote_path", buildJsonObject {
                    put("type", "string")
                    put("description", "action=upload / download 时的远端路径。")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "连接与执行超时，默认 30，最大 300。")
                })
            },
            required = listOf("host", "username"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        withContext(Dispatchers.IO) { runSsh(params) }
    },
)

private fun runSsh(params: JsonObject): List<UIMessagePart> {
    val host = params.stringOrNull("host")?.trim().orEmpty()
    if (host.isBlank()) return textOnly(toolError("MISSING_HOST", "host is required"))
    val username = params.stringOrNull("username")?.trim().orEmpty()
    if (username.isBlank()) return textOnly(toolError("MISSING_USER", "username is required"))
    val port = (params.intOrNull("port") ?: 22).coerceIn(1, 65535)
    val timeoutSeconds = (params.intOrNull("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
        .coerceIn(1, MAX_TIMEOUT_SECONDS)
    val timeoutMs = timeoutSeconds * 1000
    val action = params.stringOrNull("action")?.trim()?.lowercase().orEmpty().ifBlank { "exec" }

    val password = params.stringOrNull("password")?.takeIf { it.isNotBlank() }
    val privateKey = params.stringOrNull("private_key")?.takeIf { it.isNotBlank() }
    if (password == null && privateKey == null) {
        return textOnly(toolError("MISSING_CREDENTIAL", "请提供 password 或 private_key。"))
    }

    val jsch = com.jcraft.jsch.JSch()
    if (privateKey != null) {
        val keyFile = File(privateKey)
        if (!keyFile.isFile) {
            return textOnly(toolError("KEY_NOT_FOUND", "私钥文件不存在：$privateKey"))
        }
        val passphrase = params.stringOrNull("private_key_passphrase")?.takeIf { it.isNotBlank() }
        runCatching { jsch.addIdentity(keyFile.absolutePath, passphrase) }
            .getOrElse { return textOnly(toolError("INVALID_KEY", "私钥加载失败：${it.message}")) }
    }

    var session: com.jcraft.jsch.Session? = null
    return runCatching {
        session = jsch.getSession(username, host, port).apply {
            if (password != null) setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            setServerAliveInterval(15_000)
            connect(timeoutMs)
        }
        when (action) {
            "upload" -> sshTransfer(session!!, params, upload = true, timeoutMs)
            "download" -> sshTransfer(session!!, params, upload = false, timeoutMs)
            else -> sshExec(session!!, params, timeoutMs)
        }
    }.getOrElse { e ->
        textOnly(toolError("SSH_FAILED", "SSH 连接或执行失败：${e.message ?: e::class.simpleName}"))
    }.also {
        runCatching { session?.disconnect() }
    }
}

private fun sshExec(
    session: com.jcraft.jsch.Session,
    params: JsonObject,
    timeoutMs: Int,
): List<UIMessagePart> {
    val command = params.stringOrNull("command")
        ?: return textOnly(toolError("MISSING_COMMAND", "action=exec 时必须提供 command。"))
    val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    channel.setCommand(command)
    channel.setInputStream(null)
    channel.setOutputStream(stdout)
    channel.setErrStream(stderr)
    channel.connect(timeoutMs)
    try {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (!channel.isClosed) {
            channel.disconnect()
            return textOnly(toolError("TIMEOUT", "命令执行超时（${timeoutMs / 1000}s），已断开。"))
        }
    } finally {
        channel.disconnect()
    }
    return textOnly(
        buildJsonObject {
            put("success", channel.exitStatus == 0)
            put("exit_code", channel.exitStatus)
            put("command", command)
            put("stdout", String(stdout.toByteArray(), Charsets.UTF_8))
            put("stderr", String(stderr.toByteArray(), Charsets.UTF_8))
        }.toString()
    )
}

private fun sshTransfer(
    session: com.jcraft.jsch.Session,
    params: JsonObject,
    upload: Boolean,
    timeoutMs: Int,
): List<UIMessagePart> {
    val localRaw = params.stringOrNull("local_path")
        ?: return textOnly(toolError("MISSING_LOCAL_PATH", "local_path is required"))
    val remote = params.stringOrNull("remote_path")
        ?: return textOnly(toolError("MISSING_REMOTE_PATH", "remote_path is required"))
    val local = resolvePath(localRaw)
        ?: return textOnly(toolError("INVALID_LOCAL_PATH", "无法解析本机路径：$localRaw"))

    val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
    channel.connect(timeoutMs)
    try {
        if (upload) {
            local.parentFile?.mkdirs()
            if (!local.isFile) return textOnly(toolError("LOCAL_NOT_FOUND", "本机文件不存在：${local.absolutePath}"))
            channel.put(local.absolutePath, remote)
            return textOnly(
                buildJsonObject {
                    put("success", true)
                    put("uploaded", true)
                    put("local_path", local.absolutePath)
                    put("remote_path", remote)
                    put("size", local.length())
                }.toString()
            )
        }
        local.parentFile?.mkdirs()
        channel.get(remote, local.absolutePath)
        return textOnly(
            buildJsonObject {
                put("success", local.isFile)
                put("downloaded", true)
                put("remote_path", remote)
                put("local_path", local.absolutePath)
                put("size", local.length())
            }.toString()
        )
    } finally {
        channel.disconnect()
    }
}

/** 原始 TCP：直接连端口发字节、读回响应，可处理 HTTP 之外的私有协议。 */
internal fun buildTcpTool(): Tool = Tool(
    name = TCP_TOOL,
    description = """
        建立原始 TCP（可选 TLS）连接，发送字节并读回响应，用于 HTTP 工具覆盖不到的私有协议、调试端口、探测服务等。
        参数：
        - host / port 必填
        - payload 要发送的内容；payload_encoding 为 text（默认，UTF-8）或 base64
        - tls=true 时使用系统 TLS（适合 SMTP/IMAPS 等），默认 false
        - timeout_ms 连接与读取超时，默认 8000；read_idle_ms 连续多久收不到数据就结束，默认 1500
        - max_bytes 读入上限，默认 262144
        返回 JSON：recv_text / recv_base64、字节数与耗时。适合发一条命令看响应；需要持续交互时改用 ws_request。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "目标主机或 IP。")
                })
                put("port", buildJsonObject {
                    put("type", "integer")
                    put("description", "目标端口。")
                })
                put("payload", buildJsonObject {
                    put("type", "string")
                    put("description", "要发送的内容，可按 base64 传二进制。")
                })
                put("payload_encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "text（默认）或 base64。")
                })
                put("tls", buildJsonObject {
                    put("type", "boolean")
                    put("description", "是否使用 TLS，默认 false。")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "连接与读取超时，默认 8000。")
                })
                put("read_idle_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "连续无数据多久结束读取，默认 1500。")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "读入上限，默认 262144。")
                })
            },
            required = listOf("host", "port"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        withContext(Dispatchers.IO) { runTcp(params) }
    },
)

private fun runTcp(params: JsonObject): List<UIMessagePart> {
    val host = params.stringOrNull("host")?.trim().orEmpty()
    if (host.isBlank()) return textOnly(toolError("MISSING_HOST", "host is required"))
    val port = params.intOrNull("port") ?: return textOnly(toolError("MISSING_PORT", "port is required"))
    val timeoutMs = (params.intOrNull("timeout_ms") ?: 8000).coerceIn(200, MAX_TIMEOUT_SECONDS * 1000)
    val idleMs = (params.intOrNull("read_idle_ms") ?: DEFAULT_READ_IDLE_MS).coerceIn(50, timeoutMs)
    val maxBytes = (params.intOrNull("max_bytes") ?: DEFAULT_MAX_BYTES).coerceAtLeast(1)
    val useTls = params.boolOrNull("tls") ?: false

    val payloadBytes = params.stringOrNull("payload")?.let { raw ->
        when (params.stringOrNull("payload_encoding")?.lowercase()) {
            "base64" -> runCatching { Base64.decode(raw, Base64.DEFAULT) }
                .getOrElse { return textOnly(toolError("INVALID_PAYLOAD", "payload 不是合法的 base64。")) }

            else -> raw.toByteArray(Charsets.UTF_8)
        }
    }

    val startedAt = System.currentTimeMillis()
    return runCatching {
        val socket = connectSocket(host, port, timeoutMs, useTls)
        socket.use { s ->
            s.soTimeout = idleMs
            if (payloadBytes != null) {
                s.getOutputStream().apply {
                    write(payloadBytes)
                    flush()
                }
            }
            val received = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (received.size() < maxBytes) {
                val cap = minOf(buffer.size, maxBytes - received.size())
                val n = runCatching { s.getInputStream().read(buffer, 0, cap) }.getOrDefault(-1)
                if (n <= 0) break
                received.write(buffer, 0, n)
            }
            val bytes = received.toByteArray()
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            val controlCount = text?.count { it.code < 9 || it.code in 14..31 } ?: Int.MAX_VALUE
            val looksText = text != null && controlCount <= text.length / 20
            textOnly(
                buildJsonObject {
                    put("success", true)
                    put("host", host)
                    put("port", port)
                    put("tls", useTls)
                    put("elapsed_ms", System.currentTimeMillis() - startedAt)
                    put("size", bytes.size)
                    if (looksText) {
                        put("recv_encoding", "text")
                        put("recv_text", text)
                    } else {
                        put("recv_encoding", "base64")
                        put("recv_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }.toString()
            )
        }
    }.getOrElse { e ->
        textOnly(toolError("TCP_FAILED", "TCP 连接或读取失败：${e.message ?: e::class.simpleName}"))
    }
}

private fun connectSocket(host: String, port: Int, timeoutMs: Int, tls: Boolean): Socket {
    val plain = Socket()
    plain.connect(InetSocketAddress(host, port), timeoutMs)
    if (!tls) return plain
    val factory = SSLContext.getDefault().socketFactory
    val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
    ssl.startHandshake()
    return ssl
}

/** WebSocket：连接、发若干帧、收集一段时间内的回包。 */
internal fun buildWebSocketTool(): Tool = Tool(
    name = WEBSOCKET_TOOL,
    description = """
        连接 WebSocket（ws:// 或 wss://），发送消息并收集服务端回包，用于调试实时接口、订阅数据流。
        参数：
        - url 必填，需以 ws:// 或 wss:// 开头
        - messages 依次发送的文本帧数组；不传则只监听
        - headers 自定义请求头
        - listen_ms 发送后等待回包的总时长，默认 5000，最大 60000
        返回 JSON：messages 收到帧的数组（文本或 base64 标记）、closed 状态。只做一次会话，长连接请在脚本里用 JavaScript 工具实现。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "ws:// 或 wss:// 地址。")
                })
                put("messages", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "依次发送的文本消息。")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                    put("description", "握手时附带的请求头。")
                })
                put("listen_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "等待回包时长，默认 5000。")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        runWebSocket(params)
    },
)

private suspend fun runWebSocket(params: JsonObject): List<UIMessagePart> {
    val url = params.stringOrNull("url")?.trim().orEmpty()
    if (url.isBlank()) return textOnly(toolError("MISSING_URL", "url is required"))
    if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
        return textOnly(toolError("INVALID_URL", "WebSocket 地址必须带 ws:// 或 wss:// 前缀：$url"))
    }
    val listenMs = (params.intOrNull("listen_ms") ?: 5000).coerceIn(200, 60_000)
    val outgoing = (params["messages"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        .orEmpty()

    val client = OkHttpClient.Builder()
        .connectTimeout(listenMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    val builder = Request.Builder().url(url)
    params["headers"]?.jsonObject?.forEach { (name, value) ->
        value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
    }

    val received = mutableListOf<Pair<String, String>>()
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    var webSocketRef: WebSocket? = null

    val startedAt = System.currentTimeMillis()
    val opened = withTimeoutOrNull(listenMs.toLong() + 2000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocketRef = webSocket
                    if (cont.isActive) cont.resume(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(received) { received += "text" to text }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    synchronized(received) {
                        received += "base64" to Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.set(true)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    closed.set(true)
                    if (cont.isActive) cont.resume(false)
                }
            }
            webSocketRef = client.newWebSocket(builder.build(), listener)
            cont.invokeOnCancellation { webSocketRef?.cancel() }
        }
    } ?: false

    if (!opened) return textOnly(toolError("WS_CONNECT_FAILED", "WebSocket 连接失败或超时：$url"))

    runCatching {
        outgoing.forEach { message ->
            webSocketRef?.send(message)
            delay(60)
        }
        delay(listenMs.toLong())
    }
    runCatching { webSocketRef?.close(1000, "done") }
    runCatching { client.dispatcher.executorService.shutdown() }

    return textOnly(
        buildJsonObject {
            put("success", true)
            put("url", url)
            put("elapsed_ms", System.currentTimeMillis() - startedAt)
            put("closed", closed.get())
            put(
                "messages",
                buildJsonArray {
                    synchronized(received) {
                        received.forEach { (encoding, value) ->
                            add(
                                buildJsonObject {
                                    put("encoding", encoding)
                                    put(if (encoding == "text") "text" else "base64", value)
                                }
                            )
                        }
                    }
                },
            )
        }.toString()
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull() }

private fun JsonObject.boolOrNull(key: String): Boolean? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() }
