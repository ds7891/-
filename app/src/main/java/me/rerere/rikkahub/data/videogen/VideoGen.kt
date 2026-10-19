package me.rerere.rikkahub.data.videogen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 协议：内置免费试用（SiftQ / MiniMax-H3）。 */
const val VIDEO_PROTOCOL_SIFTQ = "siftq"

/** 协议：OpenAI 兼容的视频接口（POST {baseUrl}/videos 提交，GET {baseUrl}/videos/{id} 查询），multipart 表单。 */
const val VIDEO_PROTOCOL_OPENAI = "openai"

/** 协议：通用 JSON 视频接口（POST JSON 提交，GET {queryPath} 查询），适合大多数自建/第三方接口。 */
const val VIDEO_PROTOCOL_JSON = "json"

/**
 * 协议：NewAPI / OneAPI 中转站的视频接口。
 *
 * 与 [VIDEO_PROTOCOL_JSON] 类似，但默认路径按中转站约定（`video/generations`），
 * 且提交体对齐中转站常见字段（model / prompt / duration / size / image）。
 */
const val VIDEO_PROTOCOL_NEWAPI = "newapi"

/** 任务状态。 */
const val VIDEO_STATUS_IDLE = "idle"
const val VIDEO_STATUS_QUEUED = "queued"
const val VIDEO_STATUS_DISPATCHING = "dispatching"
const val VIDEO_STATUS_RUNNING = "running"
const val VIDEO_STATUS_SUCCEEDED = "succeeded"
const val VIDEO_STATUS_FAILED = "failed"
const val VIDEO_STATUS_CANCELLED = "cancelled"

/**
 * 一个视频生成模型配置。
 *
 * 内置模型 [BUILTIN_VIDEO_MODEL] 使用免费试用接口；用户还可以自行添加兼容接口的模型，
 * 每个模型可以各自配置支持的时长 / 分辨率 / 帧率 / 画面比例。
 */
@Serializable
data class VideoModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String = VIDEO_PROTOCOL_OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 模型标识，会在请求体里作为 `model` 字段发送。 */
    val model: String = "",
    val builtin: Boolean = false,
    /** 是否必须提供参考图（内置的图生视频接口需要）。 */
    val requiresImage: Boolean = false,
    val submitPath: String = "videos",
    val queryPath: String = "videos/{id}",
    val downloadPath: String = "videos/{id}/content",
    val aspectOptions: List<String> = listOf("16:9", "9:16", "1:1"),
    val durationOptions: List<Int> = listOf(6, 10),
    val resolutionOptions: List<String> = listOf("720P", "1080P"),
    val fpsOptions: List<Int> = listOf(24, 30),
)

/** 某个模型当前选中的生成参数。 */
@Serializable
data class VideoParams(
    val duration: Int,
    val resolution: String,
    val fps: Int,
    val aspect: String,
)

/**
 * 内置免费模型：SiftQ 提供的 MiniMax-H3 试用接口。
 *
 * 完全对齐参考实现的参数：比例固定 9:16，时长可选 6 / 10 / 15 秒。
 * 该接口是「图生视频」：实测不传参考图时服务端直接返回 400，
 * 因此参考图是必填项（[requiresImage] = true），以该图作为首帧生成视频。
 */
val BUILTIN_VIDEO_MODEL = VideoModel(
    id = "builtin_siftq_minimax_h3",
    name = "MiniMax-H3 免费试用",
    protocol = VIDEO_PROTOCOL_SIFTQ,
    model = "MiniMax-H3",
    builtin = true,
    requiresImage = true,
    aspectOptions = listOf("9:16"),
    durationOptions = listOf(6, 10, 15),
    resolutionOptions = listOf("768P"),
    fpsOptions = listOf(24),
)

/** 视频生成模块的本地配置：模型列表、当前选中模型、各模型选中的参数。 */
@Serializable
data class VideoGenState(
    val models: List<VideoModel> = listOf(BUILTIN_VIDEO_MODEL),
    val selectedModelId: String = BUILTIN_VIDEO_MODEL.id,
    val params: Map<String, VideoParams> = mapOf(
        BUILTIN_VIDEO_MODEL.id to VideoParams(
            duration = 6,
            resolution = "768P",
            fps = 24,
            aspect = "9:16",
        )
    ),
) {
    /** 拿到指定模型的选中参数，缺失时回落到该模型的第一项。 */
    fun paramsFor(model: VideoModel): VideoParams = params[model.id] ?: VideoParams(
        duration = model.durationOptions.firstOrNull() ?: 6,
        resolution = model.resolutionOptions.firstOrNull() ?: "768P",
        fps = model.fpsOptions.firstOrNull() ?: 24,
        aspect = model.aspectOptions.firstOrNull() ?: "16:9",
    )
}

/** 把视频模型配置持久化到应用私有目录下的 JSON 文件。 */
class VideoGenStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val file: File get() = File(context.filesDir, "videogen.json")

    fun load(): VideoGenState {
        if (!file.exists()) return VideoGenState()
        return runCatching { json.decodeFromString<VideoGenState>(file.readText()) }
            .getOrElse { VideoGenState() }
            .let { state ->
                // 始终保证内置模型存在且排在最前
                val merged = buildList {
                    add(BUILTIN_VIDEO_MODEL)
                    addAll(state.models.filterNot { it.builtin || it.id == BUILTIN_VIDEO_MODEL.id })
                }
                val selected = state.selectedModelId.takeIf { id -> merged.any { it.id == id } }
                    ?: BUILTIN_VIDEO_MODEL.id
                state.copy(models = merged, selectedModelId = selected)
            }
    }

    fun save(state: VideoGenState) {
        runCatching { file.writeText(json.encodeToString(state)) }
    }

    private val pendingFile: File get() = File(context.filesDir, "videogen_pending.json")

    /** 读取上次未完成的任务快照，用于重启后恢复轮询。 */
    fun loadPending(): PendingVideoTask? {
        if (!pendingFile.exists()) return null
        return runCatching { json.decodeFromString<PendingVideoTask>(pendingFile.readText()) }
            .getOrNull()
    }

    /** 写入 / 清除未完成任务快照。传入 null 表示清除。 */
    fun savePending(pending: PendingVideoTask?) {
        runCatching {
            if (pending == null) {
                pendingFile.delete()
            } else {
                pendingFile.writeText(json.encodeToString(pending))
            }
        }
    }
}

/** 一次生成所使用的身份标识，提交成功后需沿用到查询与下载。 */
@Serializable
class VideoIdentity(
    val clientId: String = newClientId(),
    val visitorId: String = newVisitorId(),
    val forgedIp: String = randomPublicIp(),
) {
    /** 该身份剩余可提交次数，耗尽后不再放回身份池。 */
    var usesLeft: Int = 2
}

/** 生成一个形如 `mmtrial_<uuid>` 的客户端标识。 */
fun newClientId(): String = "mmtrial_${UUID.randomUUID()}"

/** 生成一个形如 `mmguest_<uuid>` 的访客标识。 */
fun newVisitorId(): String = "mmguest_${UUID.randomUUID()}"

/**
 * 生成一个随机公网 IP，用于试用接口的身份轮换。
 *
 * 严格排除私有 / 保留网段（10/8、127/8、172.16/12、192.168/16、169.254/16、
 * 100.64/10、198.18/15、203.0.113/24），避免服务端风控把保留地址判定为异常请求。
 */
fun randomPublicIp(): String {
    while (true) {
        val a = Random.nextInt(1, 224)
        if (a == 10 || a == 127) continue
        val b = Random.nextInt(0, 256)
        if (a == 172 && b in 16..31) continue
        if (a == 192 && b == 168) continue
        if (a == 169 && b == 254) continue
        if (a == 100 && b in 64..127) continue
        if (a == 198 && b in 18..19) continue
        if (a == 203 && b == 0) continue
        return "$a.$b.${Random.nextInt(0, 256)}.${Random.nextInt(1, 255)}"
    }
}

/** 一次视频生成任务的状态快照。 */
@Serializable
data class VideoTask(
    val taskId: String,
    val status: String,
    val queuePosition: Int? = null,
    val progress: Int? = null,
    val videoUrl: String? = null,
    val error: String? = null,
    val accessToken: String? = null,
    val identity: VideoIdentity? = null,
)

/**
 * 进行中的任务快照，落盘后可在 App 重启 / 进程被杀后恢复轮询，
 * 避免排队中的任务因为切后台而丢失。
 */
@Serializable
data class PendingVideoTask(
    val modelId: String,
    val prompt: String,
    val params: VideoParams,
    val task: VideoTask,
    val elapsedSeconds: Int = 0,
    val savedAt: Long = System.currentTimeMillis(),
)

/** 取消任务的结果。 */
enum class CancelOutcome {
    /** 服务端已接受取消。 */
    CANCELLED,

    /** 该接口没有取消能力。 */
    UNSUPPORTED,

    /** 调用了取消接口但未能确认成功。 */
    FAILED,
}

/** 已经保存到本地的视频生成结果。 */
data class GeneratedVideo(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String,
)

/**
 * 视频生成接口调用封装。
 *
 * - [VIDEO_PROTOCOL_SIFTQ]：内置免费试用。完整移植参考实现的调用链：维护身份池，逐个身份
 *   先访问试用页建立会话，再以 multipart 提交任务并取回 `task_id` 与 `access_token`，
 *   遇到 429 / 401~403 会轮换身份重试，之后凭 token 轮询与下载。
 * - [VIDEO_PROTOCOL_OPENAI]：OpenAI 兼容视频接口，multipart 提交，按 `{id}` 占位符轮询/download。
 */
object VideoGenApi {
    /** 内置免费试用接口的基地址。 */
    const val SIFTQ_BASE_URL = "https://siftq.com/api/minimax-trial"

    const val SIFTQ_UPSTREAM = "https://siftq.com"
    private const val SIFTQ_REFERER = "https://siftq.com/minimax-h3/try/zh"
    private const val SIFTQ_SHOWCASE_ID = "case-mtqzygu8"
    private const val SIFTQ_SOURCE_HOST = "siftq.com"
    private const val DEFAULT_ASPECT = "9:16"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val DEFAULT_PROMPT = "Natural gentle motion, keep the scene consistent"

    /** 提交阶段最长重试时间与最大尝试次数，避免无谓地长时间转圈。 */
    private const val SUBMIT_TIMEOUT_MS = 300_000L
    private const val MAX_SUBMIT_ATTEMPTS = 12

    /** 单次 JSON 提交最多剔除多少个被网关拒绝的字段后仍失败。 */
    private const val MAX_JSON_FIELD_DROPS = 6

    /** JSON 提交可能用到的字段，用于从错误信息中识别被拒字段。 */
    private val JSON_BODY_FIELDS = listOf(
        "model", "prompt", "duration", "seconds", "resolution", "fps",
        "aspect_ratio", "ratio", "size", "image_urls", "image_url", "images", "image",
    )

    /** 允许在重试时剔除的字段；model/prompt 必需，参考图字段留待 multipart 兜底。 */
    private val JSON_DROPPABLE_FIELDS = JSON_BODY_FIELDS
        .filterNot { it in setOf("model", "prompt", "image_urls", "image_url", "images", "image") }
        .toSet()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 身份池：提交成功的身份可继续复用，失败的（usesLeft=0）直接丢弃。 */
    private val identityPool = ArrayDeque<VideoIdentity>()
    private val poolLock = Any()

    /**
     * 试用接口会结合会话 Cookie 与身份头做风控。参考实现每次都新建一个带独立 CookieJar 的
     * OkHttpClient：预热与提交共用同一个新 client，但不同任务 / 不同身份之间不共享 Cookie，
     * 避免上一轮身份遗留的 Cookie 让下一轮被判定为滥用而返回 401 / 403。
     */
    private fun newSessionClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(MemoryCookieJar())
        .build()

    /**
     * 查询与下载只需 URL 里的 access_token，用一个独立的无 Cookie client，
     * 避免应用内共享的 OkHttpClient（可能带代理 / 拦截器）影响试用接口的连通性。
     */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    class VideoApiException(message: String) : Exception(message)

    class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    /**
     * 提交一次生成任务。
     *
     * [client] 用于自定义模型；内置免费接口会使用带 Cookie 的独立会话客户端。
     */
    suspend fun submit(
        client: OkHttpClient,
        model: VideoModel,
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
    ): VideoTask = withContext(Dispatchers.IO) {
        when (model.protocol) {
            VIDEO_PROTOCOL_SIFTQ -> submitSiftQ(params, prompt, imageFiles)
            VIDEO_PROTOCOL_JSON, VIDEO_PROTOCOL_NEWAPI ->
                submitWithFallback(client, model, params, prompt, imageFiles, jsonFirst = true)
            else ->
                submitWithFallback(client, model, params, prompt, imageFiles, jsonFirst = false)
        }
    }

    /**
     * 提交自定义模型任务：若服务端不接受当前的请求编码，自动换另一种再试一次。
     *
     * 有的中转站只收 `application/json`，有的 OpenAI 兼容实现只收 `multipart/form-data`，
     * 用户不需要自己判断该选哪种协议。
     */
    private fun submitWithFallback(
        client: OkHttpClient,
        model: VideoModel,
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
        jsonFirst: Boolean,
    ): VideoTask {
        return try {
            if (jsonFirst) {
                submitJson(client, model, params, prompt, imageFiles)
            } else {
                submitOpenAi(client, model, params, prompt, imageFiles)
            }
        } catch (e: VideoApiException) {
            val hint = e.message.orEmpty()
            val shouldFallback =
                if (jsonFirst) wantsMultipart(hint) || rejectedJsonField(hint) != null
                else wantsJson(hint)
            if (!shouldFallback) throw e
            if (jsonFirst) {
                submitOpenAi(client, model, params, prompt, imageFiles)
            } else {
                submitJson(client, model, params, prompt, imageFiles)
            }
        }
    }

    /** 服务端是否要求 JSON 请求体（如“该接口仅支持 Content-Type: application/json”）。 */
    private fun wantsJson(message: String): Boolean {
        val text = message.lowercase()
        if (text.contains("multipart")) return false
        return text.contains("application/json") ||
            (text.contains("json") && (text.contains("content-type") || text.contains("请求体")))
    }

    /** 服务端是否要求 multipart/form-data 请求体。 */
    private fun wantsMultipart(message: String): Boolean =
        message.lowercase().contains("multipart")

    /**
     * 从“未知 / 不允许的字段”类错误中定位被拒绝的字段名。
     *
     * 例如部分网关会返回 `duration 不是允许的请求字段`、`Unrecognized request argument: duration`。
     * 只有确认属于“字段不被接受”类错误时才返回字段名，普通参数校验错误返回 null。
     */
    private fun rejectedJsonField(message: String): String? {
        val text = message.lowercase()
        val unknownFieldError = listOf(
            "不是允许的请求字段", "不允许的请求字段", "未知字段", "不支持的字段",
            "unrecognized", "unknown field", "unknown parameter", "unknown argument",
            "unexpected field", "unexpected parameter", "additional propert",
            "not allowed", "not permitted", "unsupported field", "invalid field",
            "no such field", "extra field",
        ).any { text.contains(it) }
        if (!unknownFieldError) return null
        return JSON_BODY_FIELDS.firstOrNull { field ->
            Regex("(?<![a-z0-9_])${Regex.escape(field)}(?![a-z0-9_])").containsMatchIn(text)
        }
    }

    /** 查询任务进度。[task] 需携带提交时返回的身份与访问令牌。 */
    suspend fun query(
        client: OkHttpClient,
        model: VideoModel,
        task: VideoTask,
    ): VideoTask = withContext(Dispatchers.IO) {
        when (model.protocol) {
            VIDEO_PROTOCOL_SIFTQ -> querySiftQ(plainClient, task)
            VIDEO_PROTOCOL_JSON, VIDEO_PROTOCOL_NEWAPI -> queryJson(client, model, task)
            else -> queryOpenAi(client, model, task)
        }
    }

    /** 下载生成的视频到 [target]。 */
    suspend fun download(
        client: OkHttpClient,
        model: VideoModel,
        task: VideoTask,
        target: File,
    ): File = withContext(Dispatchers.IO) {
        when (model.protocol) {
            VIDEO_PROTOCOL_SIFTQ -> downloadSiftQ(plainClient, task, target)
            VIDEO_PROTOCOL_JSON, VIDEO_PROTOCOL_NEWAPI -> downloadRest(client, model, task, target)
            else -> downloadOpenAi(client, model, task, target)
        }
    }

    /**
     * 请求服务端取消一个进行中的任务。
     *
     * - 内置免费试炼接口没有取消入口，直接返回 [CancelOutcome.UNSUPPORTED]；
     * - 自定义接口按常见约定依次尝试：DELETE / POST `{queryPath}`、
     *   `{queryPath}/cancel`、DELETE `{submitPath}/{id}`，命中 2xx 即视为成功。
     */
    suspend fun cancel(
        client: OkHttpClient,
        model: VideoModel,
        task: VideoTask,
    ): CancelOutcome = withContext(Dispatchers.IO) {
        if (model.protocol == VIDEO_PROTOCOL_SIFTQ) {
            return@withContext CancelOutcome.UNSUPPORTED
        }
        if (task.taskId.isBlank()) return@withContext CancelOutcome.FAILED

        val queryPath = model.queryPath.ifBlank { "videos/{id}" }
        val submitPath = model.submitPath.ifBlank { "videos" }
        val withId = queryPath.replace("{id}", task.taskId)
        val candidates = buildList {
            if (queryPath.contains("{id}")) add(endpoint(model.baseUrl, withId))
            add(endpoint(model.baseUrl, withId.trimEnd('/') + "/cancel"))
            add(endpoint(model.baseUrl, submitPath.trimEnd('/') + "/" + task.taskId))
        }.distinct()

        var lastCode = 0
        for (url in candidates) {
            val deleted = runCatching {
                executeRaw(client, authorizedRequest(model.apiKey, url).delete().build())
            }.getOrNull()
            if (deleted != null) {
                lastCode = deleted.first
                if (deleted.first in 200..299) return@withContext CancelOutcome.CANCELLED
            }
            val posted = runCatching {
                executeRaw(
                    client,
                    authorizedRequest(model.apiKey, url)
                        .post(ByteArray(0).toRequestBody(null))
                        .build(),
                )
            }.getOrNull()
            if (posted != null) {
                lastCode = posted.first
                if (posted.first in 200..299) return@withContext CancelOutcome.CANCELLED
            }
        }
        if (lastCode == 404 || lastCode == 405 || lastCode == 501) {
            CancelOutcome.UNSUPPORTED
        } else {
            CancelOutcome.FAILED
        }
    }

    /** 使用用户填写的 Base URL 与 API Key 拉取可用模型列表。 */
    suspend fun fetchCloudModels(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw VideoApiException("请先填写接口地址")
        val request = authorizedRequest(apiKey, endpoint(baseUrl, "models"))
            .header("Accept", "application/json")
            .get()
            .build()
        val (code, text) = executeRaw(client, request)
        if (code !in 200..299) {
            throw VideoApiException(extractErrorMessage(text, includeGeneric = true) ?: "获取模型失败（$code）")
        }
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["data"] as? JsonArray)
                ?: (element["models"] as? JsonArray)
                ?: (element["result"] as? JsonArray)
            else -> null
        } ?: throw VideoApiException("响应中没有模型列表")
        array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.str("id") ?: item.str("model") ?: item.str("name")
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

    // ---- 内置免费试用 ----

    private suspend fun submitSiftQ(
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
    ): VideoTask {
        val deadline = System.currentTimeMillis() + SUBMIT_TIMEOUT_MS
        var lastError: String? = null
        var lastCode = 0
        var attempts = 0
        while (System.currentTimeMillis() < deadline && attempts < MAX_SUBMIT_ATTEMPTS) {
            attempts++
            val identity = acquireIdentity()
            // 每一次尝试都用全新的 client 与全新的会话，避免上一轮身份留下的 Cookie 触发风控
            val client = newSessionClient()
            try {
                // 预热：先访问试用页面，让服务端下发会话 Cookie
                runCatching {
                    val warmUp = Request.Builder()
                        .url(SIFTQ_REFERER)
                        .applyIdentityHeaders(identity)
                        .get()
                        .build()
                    client.newCall(warmUp).execute().close()
                }

                val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("visitorId", identity.visitorId)
                    .addFormDataPart("channelCode", "direct")
                    .addFormDataPart("sourceHost", SIFTQ_SOURCE_HOST)
                    .addFormDataPart("showcase_id", SIFTQ_SHOWCASE_ID)
                    .addFormDataPart("ratio", params.aspect.ifBlank { DEFAULT_ASPECT })
                    .addFormDataPart("duration", params.duration.toString())
                    .addFormDataPart("client_id", identity.clientId)
                    .addFormDataPart("prompt", prompt.ifBlank { DEFAULT_PROMPT })
                // 内置免费接口只支持单张参考图，取第一张作为首帧
                imageFiles.firstOrNull { it.exists() }?.let { image ->
                    bodyBuilder.addFormDataPart(
                        "image",
                        image.name,
                        image.asRequestBody(sniffMime(image).toMediaType()),
                    )
                }

                val request = Request.Builder()
                    .url("$SIFTQ_BASE_URL/showcase/video-generation")
                    .applyIdentityHeaders(identity)
                    .header("Idempotency-Key", "mmtrial_${UUID.randomUUID()}")
                    .post(bodyBuilder.build())
                    .build()

                val (code, text) = executeRaw(client, request)
                when {
                    code in 200..299 -> {
                        val root = parseObject(text)
                        val taskId = root.str("task_id", "taskId")
                        val accessToken = root.str("access_token", "accessToken")
                        if (!taskId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
                            identity.usesLeft -= 1
                            reportIdentity(identity)
                            return VideoTask(
                                taskId = taskId,
                                status = normalizeStatus(root.str("status", "state"), null, hasVideo = false),
                                queuePosition = root.int(
                                    "queue_position", "queuePosition", "position", "ahead",
                                ),
                                progress = root.int("progress", "percent", "percentage")?.coerceIn(0, 100),
                                accessToken = accessToken,
                                identity = identity,
                            )
                        }
                        lastError = "响应缺少 task_id / access_token"
                    }

                    code == 429 -> {
                        identity.usesLeft = 0
                        lastCode = code
                        lastError = "请求过于频繁（429），正在更换身份重试"
                    }

                    code in 401..403 -> {
                        // 免费试用对单身份 / 单 IP 有次数限制（响应里通常带 login_required），
                        // 换一个身份与伪造 IP 重试。
                        identity.usesLeft = 0
                        lastCode = code
                        lastError = extractErrorMessage(text, includeGeneric = true)
                            ?: "试用次数受限（$code），正在更换身份重试"
                    }

                    code in 500..599 -> {
                        // 服务端偶发 503（generation_retryable，如图片上传失败）允许重试，
                        // 换一个新会话再试一次，避免一次抖动就整体失败。
                        lastCode = code
                        lastError = extractErrorMessage(text, includeGeneric = true)
                            ?: "服务端暂时不可用（$code），正在重试"
                    }

                    else -> throw VideoApiException(
                        extractErrorMessage(text, includeGeneric = true) ?: "提交失败（$code）"
                    )
                }
            } catch (e: VideoApiException) {
                throw e
            } catch (e: Exception) {
                lastCode = 0
                lastError = "网络错误：${e.message ?: e.javaClass.simpleName}"
            }
            // 被风控 / 限流时退避久一点，避免连续请求把当前网络也拖进 403
            val waitMs = when (lastCode) {
                429, 403, 401 -> Random.nextLong(2_000L, 3_500L)
                500, 502, 503, 504 -> Random.nextLong(1_000L, 2_000L)
                else -> Random.nextLong(600L, 1_400L)
            }
            delay(waitMs)
        }
        throw VideoApiException(lastError ?: "提交失败，请稍后重试")
    }

    private fun querySiftQ(client: OkHttpClient, task: VideoTask): VideoTask {
        val identity = task.identity ?: throw VideoApiException("缺少身份信息")
        val token = task.accessToken ?: throw VideoApiException("缺少访问令牌")
        val request = Request.Builder()
            .url("$SIFTQ_BASE_URL/video-generation/${task.taskId}?access_token=$token")
            .applyIdentityHeaders(identity)
            .get()
            .build()
        return try {
            val (code, text) = executeRaw(client, request)
            if (code !in 200..299) {
                task.copy(status = VIDEO_STATUS_FAILED, error = "查询进度失败（HTTP $code）")
            } else {
                val root = parseObject(text)
                task.copy(
                    status = normalizeStatus(root.str("status", "state"), null, hasVideo = false),
                    queuePosition = root.int("queue_position", "queuePosition", "position", "ahead"),
                    progress = root.int("progress", "percent", "percentage")?.coerceIn(0, 100),
                    videoUrl = extractVideoUrl(root),
                )
            }
        } catch (e: Exception) {
            // 单次轮询的网络抖动不应中断整个任务，保持排队状态等待下一次查询
            task.copy(status = VIDEO_STATUS_QUEUED, error = null)
        }
    }

    private fun downloadSiftQ(client: OkHttpClient, task: VideoTask, target: File): File {
        val identity = task.identity ?: throw VideoApiException("缺少身份信息")
        val token = task.accessToken ?: throw VideoApiException("缺少访问令牌")
        val url = "$SIFTQ_BASE_URL/video-generation/${task.taskId}/content" +
            "?client_id=${identity.clientId}&access_token=$token"
        val request = Request.Builder()
            .url(url)
            .applyIdentityHeaders(identity)
            .get()
            .build()
        return streamToFile(client, request, target)
    }

    // ---- 通用 JSON 接口 ----

    private fun submitJson(
        client: OkHttpClient,
        model: VideoModel,
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
    ): VideoTask {
        // 不同网关对请求字段的宽容度不同：严格的网关会直接拒绝它不认识的字段
        // （如 “duration 不是允许的请求字段”）。这里在收到这类错误时逐个剔除被拒的
        // 非关键字段后重试，从而同时兼容 OpenAI 官方字段（seconds/size）与自建接口字段。
        val excluded = mutableSetOf<String>()
        while (true) {
            try {
                return submitJsonOnce(client, model, params, prompt, imageFiles, excluded)
            } catch (e: VideoApiException) {
                val field = rejectedJsonField(e.message.orEmpty()) ?: throw e
                // 关键字段（model/prompt）与参考图字段不剔除：前者必需，后者换 multipart 编码更合适。
                if (field !in JSON_DROPPABLE_FIELDS ||
                    !excluded.add(field) ||
                    excluded.size > MAX_JSON_FIELD_DROPS
                ) {
                    throw e
                }
            }
        }
    }

    private fun submitJsonOnce(
        client: OkHttpClient,
        model: VideoModel,
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
        excluded: Set<String>,
    ): VideoTask {
        val imageDataUris = imageFiles.filter { it.exists() }.map { file ->
            val mime = sniffMime(file)
            "data:$mime;base64,${Base64.getEncoder().encodeToString(file.readBytes())}"
        }
        val fields = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model.model.ifBlank { model.name }),
            "prompt" to JsonPrimitive(prompt),
            "duration" to JsonPrimitive(params.duration),
            // OpenAI 视频接口的 seconds 是字符串枚举，同时保留 duration 数值兼容自建接口
            "seconds" to JsonPrimitive(params.duration.toString()),
            "resolution" to JsonPrimitive(params.resolution),
            "fps" to JsonPrimitive(params.fps),
            "aspect_ratio" to JsonPrimitive(params.aspect),
            "ratio" to JsonPrimitive(params.aspect),
            "size" to JsonPrimitive(openAiSize(params)),
        )
        if (imageDataUris.isNotEmpty()) {
            // 第一张同时以单图字段发送，兼容只认单图的接口
            fields["image"] = JsonPrimitive(imageDataUris.first())
            fields["image_url"] = JsonPrimitive(imageDataUris.first())
            if (imageDataUris.size > 1) {
                // 多张参考图：按顺序放进常见的数组字段，兼容多图接口
                fields["images"] = JsonArray(imageDataUris.map { JsonPrimitive(it) })
                fields["image_urls"] = JsonArray(imageDataUris.map { JsonPrimitive(it) })
            }
        }
        val body = JsonObject(fields.filterKeys { it !in excluded })
        val request = authorizedRequest(model.apiKey, endpoint(model.baseUrl, model.submitPath.ifBlank { "videos" }))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val (code, text) = executeRaw(client, request)
        if (code !in 200..299) {
            throw VideoApiException(extractErrorMessage(text, includeGeneric = true) ?: "提交失败（$code）")
        }
        val root = parseObject(text)
        val error = extractErrorMessage(text)
        val taskId = findTaskId(root)
            ?: throw VideoApiException(error ?: "响应中缺少任务 ID")
        return VideoTask(
            taskId = taskId,
            status = normalizeStatus(root.findStatusStr(), error, hasVideo = false).let {
                if (it == VIDEO_STATUS_RUNNING && error != null) VIDEO_STATUS_FAILED else it
            },
            queuePosition = root.findInt("queue_position", "queuePosition", "position", "ahead"),
            progress = root.findInt("progress", "percent", "percentage")?.coerceIn(0, 100),
            videoUrl = extractVideoUrl(root),
            error = error,
        )
    }

    private fun queryJson(client: OkHttpClient, model: VideoModel, task: VideoTask): VideoTask {
        val path = (model.queryPath.ifBlank { "videos/{id}" }).replace("{id}", task.taskId)
        val request = authorizedRequest(model.apiKey, endpoint(model.baseUrl, path))
            .header("Accept", "application/json")
            .get()
            .build()
        val (code, text) = executeRaw(client, request)
        if (code !in 200..299) {
            return task.copy(status = VIDEO_STATUS_FAILED, error = "查询进度失败（HTTP $code）")
        }
        val root = parseObject(text)
        val videoUrl = extractVideoUrl(root)
        var error = extractErrorMessage(text)
        var status = normalizeStatus(root.findStatusStr(), error, hasVideo = videoUrl != null)
        if (status == VIDEO_STATUS_FAILED && error == null) {
            error = extractErrorMessage(text, includeGeneric = true)
        }
        // 服务端返回了错误信息（如中转站 code!=0 / message）但状态字段缺失时，
        // 不能一直停在“生成中/排队中”，直接判定失败。
        if (status == VIDEO_STATUS_RUNNING && error != null) {
            status = VIDEO_STATUS_FAILED
        }
        return task.copy(
            status = status,
            queuePosition = root.findInt("queue_position", "queuePosition", "position", "ahead"),
            progress = root.findInt("progress", "percent", "percentage")?.coerceIn(0, 100),
            videoUrl = videoUrl ?: task.videoUrl,
            error = error,
        )
    }

    /** 通用下载：优先用查询到的视频地址，否则回落到 `{downloadPath}/{id}`。 */
    private fun downloadRest(client: OkHttpClient, model: VideoModel, task: VideoTask, target: File): File {
        val url = task.videoUrl
        if (!url.isNullOrBlank()) {
            return streamToFile(
                client,
                authorizedRequest(model.apiKey, resolveUrl(model.baseUrl, url)).get().build(),
                target,
            )
        }
        val path = (model.downloadPath.ifBlank { "videos/{id}/content" }).replace("{id}", task.taskId)
        return streamToFile(client, authorizedRequest(model.apiKey, endpoint(model.baseUrl, path)).get().build(), target)
    }

    // ---- OpenAI 兼容接口 ----

    private fun submitOpenAi(
        client: OkHttpClient,
        model: VideoModel,
        params: VideoParams,
        prompt: String,
        imageFiles: List<File>,
    ): VideoTask {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model.model.ifBlank { model.name })
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("seconds", params.duration.toString())
            .addFormDataPart("size", openAiSize(params))
        // 多张参考图按顺序用同名 part 重复提交，兼容支持多图输入的接口
        imageFiles.filter { it.exists() }.forEach { image ->
            builder.addFormDataPart(
                "input_reference",
                image.name,
                image.asRequestBody(sniffMime(image).toMediaType()),
            )
        }
        val request = authorizedRequest(model.apiKey, endpoint(model.baseUrl, model.submitPath.ifBlank { "videos" }))
            .header("Accept", "application/json")
            .post(builder.build())
            .build()
        val (code, text) = executeRaw(client, request)
        if (code !in 200..299) {
            throw VideoApiException(extractErrorMessage(text, includeGeneric = true) ?: "提交失败（$code）")
        }
        val root = parseObject(text)
        val taskId = findTaskId(root)
            ?: throw VideoApiException(extractErrorMessage(text, includeGeneric = true) ?: "响应中缺少任务 ID")
        val error = extractErrorMessage(text)
        return VideoTask(
            taskId = taskId,
            status = normalizeStatus(root.findStatusStr(), error, hasVideo = false),
            progress = root.findInt("progress", "percent", "percentage")?.coerceIn(0, 100),
            videoUrl = extractVideoUrl(root),
            error = error,
        )
    }

    private fun queryOpenAi(client: OkHttpClient, model: VideoModel, task: VideoTask): VideoTask {
        val taskId = task.taskId
        val path = (model.queryPath.ifBlank { "videos/{id}" }).replace("{id}", taskId)
        val request = authorizedRequest(model.apiKey, endpoint(model.baseUrl, path))
            .header("Accept", "application/json")
            .get()
            .build()
        val (code, text) = executeRaw(client, request)
        if (code !in 200..299) {
            return task.copy(status = VIDEO_STATUS_FAILED, error = "查询进度失败（HTTP $code）")
        }
        val root = parseObject(text)
        val videoUrl = extractVideoUrl(root)
        var error = extractErrorMessage(text)
        var status = normalizeStatus(root.findStatusStr(), error, hasVideo = videoUrl != null)
        if (status == VIDEO_STATUS_FAILED && error == null) {
            error = extractErrorMessage(text, includeGeneric = true)
        }
        // 服务端返回了错误信息（如中转站 code!=0 / message）但状态字段缺失时，
        // 不能一直停在“生成中/排队中”，直接判定失败。
        if (status == VIDEO_STATUS_RUNNING && error != null) {
            status = VIDEO_STATUS_FAILED
        }
        return task.copy(
            status = status,
            queuePosition = root.findInt("queue_position", "queuePosition", "position", "ahead"),
            progress = root.findInt("progress", "percent", "percentage")?.coerceIn(0, 100),
            videoUrl = videoUrl ?: task.videoUrl,
            error = error,
        )
    }

    private fun downloadOpenAi(client: OkHttpClient, model: VideoModel, task: VideoTask, target: File): File {
        val url = task.videoUrl
        if (!url.isNullOrBlank()) {
            return streamToFile(
                client,
                authorizedRequest(model.apiKey, resolveUrl(model.baseUrl, url)).get().build(),
                target,
            )
        }
        val path = (model.downloadPath.ifBlank { "videos/{id}/content" }).replace("{id}", task.taskId)
        return streamToFile(client, authorizedRequest(model.apiKey, endpoint(model.baseUrl, path)).get().build(), target)
    }

    // ---- 身份池 ----

    private fun acquireIdentity(): VideoIdentity = synchronized(poolLock) {
        while (identityPool.isNotEmpty()) {
            val cached = identityPool.removeLast()
            if (cached.usesLeft > 0) return cached
        }
        VideoIdentity()
    }

    private fun reportIdentity(identity: VideoIdentity) {
        if (identity.usesLeft > 0) {
            synchronized(poolLock) { identityPool.addLast(identity) }
        }
    }

    // ---- 工具 ----

    private fun streamToFile(client: OkHttpClient, request: Request, target: File): File {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw VideoApiException("下载视频失败（${response.code}）")
            }
            val body = response.body ?: throw VideoApiException("下载视频失败：响应为空")
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    private fun executeRaw(client: OkHttpClient, request: Request): Pair<Int, String> =
        client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }

    private fun authorizedRequest(apiKey: String, url: String): Request.Builder =
        Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }

    private fun Request.Builder.applyIdentityHeaders(identity: VideoIdentity): Request.Builder {
        header("Accept", "application/json")
        header("User-Agent", USER_AGENT)
        header("Referer", SIFTQ_REFERER)
        header("X-MiniMax-Trial-Client", identity.clientId)
        header("X-Forwarded-For", identity.forgedIp)
        header("X-Real-IP", identity.forgedIp)
        return this
    }

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun openAiSize(params: VideoParams): String {
        val resolution = params.resolution.trim()
        if (Regex("""^\d{2,5}x\d{2,5}$""").matches(resolution)) return resolution
        return when (params.aspect) {
            "9:16" -> "720x1280"
            "1:1" -> "720x720"
            "4:3" -> "960x720"
            "3:4" -> "720x960"
            else -> "1280x720"
        }
    }

    private fun parseObject(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw VideoApiException("响应解析失败")

    private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    /** 在整棵响应树（含 data / task / result / output，且支持数组元素）里查找字符串字段。 */
    private fun JsonObject.findStr(vararg keys: String): String? {
        for (obj in collectObjects(this)) obj.str(*keys)?.let { return it }
        return null
    }

    /** 兼容各家命名的状态字段（status / state / task_status / taskStatus）。 */
    private fun JsonObject.findStatusStr(): String? =
        findStr("status", "state", "task_status", "taskStatus", "task_state", "taskState")

    /** 在整棵响应树里查找整型字段。 */
    private fun JsonObject.findInt(vararg keys: String): Int? {
        for (obj in collectObjects(this)) obj.int(*keys)?.let { return it }
        return null
    }

    /** 从多种常见响应结构里找出任务 ID。 */
    private fun findTaskId(root: JsonObject): String? {
        for (obj in collectObjects(root)) {
            obj.str("id", "task_id", "taskId", "request_id", "requestId", "job_id", "jobId")
                ?.let { return it }
        }
        return null
    }

    private fun extractVideoUrl(root: JsonObject): String? {
        for (obj in collectObjects(root)) {
            for (key in listOf(
                "video_url", "videoUrl", "url", "result_url", "resultUrl",
                "download_url", "downloadUrl", "content", "video", "output",
            )) {
                val value = (obj[key] as? JsonPrimitive)?.contentOrNull
                if (!value.isNullOrBlank() && (value.startsWith("http") || value.startsWith("/"))) {
                    return value
                }
            }
        }
        return null
    }

    private fun normalizeStatus(raw: String?, error: String?, hasVideo: Boolean): String {
        val text = raw?.trim()?.lowercase().orEmpty()
        val status = when (text) {
            "queued", "queue", "queuing", "waiting", "pending", "in_queue", "in-queue",
            "wait", "not_started", "notstarted", "排队中", "排队", "等待中" -> VIDEO_STATUS_QUEUED

            "dispatching", "submitting", "submitted", "created", "accepted", "received",
            "提交中", "已提交" -> VIDEO_STATUS_DISPATCHING

            "running", "processing", "generating", "in_progress", "in-progress", "working",
            "渲染中", "生成中", "开拍中", "处理中" -> VIDEO_STATUS_RUNNING

            "succeeded", "success", "succeed", "completed", "complete", "done", "finished",
            "ok", "已完成", "完成", "成功" -> VIDEO_STATUS_SUCCEEDED

            "failed", "failure", "error", "fail", "失败", "出错", "错误" -> VIDEO_STATUS_FAILED

            "cancelled", "canceled", "cancel", "已取消", "已终止" -> VIDEO_STATUS_CANCELLED

            else -> ""
        }
        if (status.isNotEmpty()) return status
        // 无法识别的状态：有错误按失败，有视频按成功，其余保持进行中，避免卡在“排队中”。
        return when {
            error != null -> VIDEO_STATUS_FAILED
            hasVideo -> VIDEO_STATUS_SUCCEEDED
            else -> VIDEO_STATUS_RUNNING
        }
    }

    /**
     * 收集响应里所有对象节点，供字段查找使用。
     *
     * 会递归遍历对象与**数组**（很多中转站把结果放在 `data: [ {...} ]` 里，
     * 若只看对象就会漏掉状态与视频地址，导致一直停在“排队中”），
     * 并用 IdentityHashMap 去重，避免自引用造成死循环。
     */
    private fun collectObjects(root: JsonObject): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val seen = java.util.IdentityHashMap<JsonObject, Boolean>()
        fun walkValue(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    if (seen.put(value, true) == null) {
                        result.add(value)
                        value.values.forEach(::walkValue)
                    }
                }

                is JsonArray -> value.forEach(::walkValue)
                else -> Unit
            }
        }
        walkValue(root)
        return result
    }

    /** 把可能是相对路径的视频地址拼到 Base URL 上。 */
    private fun resolveUrl(baseUrl: String, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else endpoint(baseUrl, url)

    /**
     * 从响应中提取错误信息。
     *
     * [includeGeneric] 为 false 时只认明确的错误字段（`error` / `error_message`），
     * 避免把成功响应里的普通 `message` 当成错误。
     */
    private fun extractErrorMessage(text: String, includeGeneric: Boolean = false): String? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for (obj in collectObjects(root)) {
            when (val error = obj["error"]) {
                is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                else -> Unit
            }
            for (key in listOf("error_message", "errorMessage")) {
                (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        // 常见错误信封：{"code": 400, "message": "..."} / {"status":"error","message":"..."}
        val envelopeMessage = root.str("message", "msg", "detail", "error_msg")
        if (envelopeMessage != null) {
            val code = root.int("code", "status_code", "statusCode", "errno")
            val statusText = root.str("status", "state")?.lowercase()
            val codeBad = code != null && code != 0 && code != 200
            val statusBad = statusText in setOf("error", "failed", "failure")
            if (codeBad || statusBad) return envelopeMessage
        }
        if (includeGeneric) {
            for (obj in collectObjects(root)) {
                for (key in listOf("message", "msg", "detail")) {
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    private fun sniffMime(file: File): String {
        val header = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (read >= 8 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() &&
            header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (read >= 4 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
        ) {
            return "image/webp"
        }
        return "image/png"
    }
}