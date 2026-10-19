package me.rerere.rikkahub.data.builtin.growth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.builtin.WorkBuddyAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

private const val TAG = "WorkBuddyGrowthApi"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 成长任务领取奖励固定挂在网页端域名下。 */
private const val GROWTH_HOST = "https://www.workbuddy.cn"

/** 桌面端/网页端 UA（照搬上游）。 */
private const val CODEBUDDY_DOMAIN = "www.workbuddy.cn"

/** 上游 `requestId` 形如 `cmb-` + 32 位十六进制。 */
private val REQUEST_ID_REGEX = Regex("\"id\"\\s*:\\s*\"((?:cmb-)?[0-9a-f]{32})\"")

/** 专家市场里的一个专家（对应上游 `l2`）。 */
data class WorkBuddyExpert(
    val id: String,
    val expertType: String = "agent",
    val name: String = "",
    val title: String = "",
    val version: String = "",
    val category: String = "",
)

/**
 * 成长中心 / 埋点上报 / 开学季活动 的上游接口封装。
 *
 * 与 [me.rerere.rikkahub.data.builtin.WorkBuddyAuthApi] 的分工：
 * - AuthApi：登录、令牌、额度、签到、成长任务（`/v2/activity/growth/tasks`）
 * - 本类：`/v2/report` 埋点上报、开学季 portal、Buddy、连登/抽奖、真实对话
 */
class WorkBuddyGrowthApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // region 埋点上报

    /** 桌面端事件批上报（`/v2/report`，自动补全指纹信封）。 */
    suspend fun reportDesktop(account: WorkBuddyAccount, events: JsonArray) =
        report(account, events, mp = false)

    /** 小程序端事件批上报（`/v2/report`，wechatmp 请求头）。 */
    suspend fun reportMiniProgram(account: WorkBuddyAccount, events: JsonArray) =
        report(account, events, mp = true)

    private suspend fun report(account: WorkBuddyAccount, events: JsonArray, mp: Boolean) {
        if (events.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val envelope = if (mp) {
                mpEnvelope(account.uid, account.nickname, now)
            } else {
                desktopEnvelope(account.uid, account.nickname, machineId(account), sessionId(account), now)
            }
            val body = buildJsonArray {
                events.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    add(mergeEnvelope(envelope, item))
                }
            }
            request(
                method = "POST",
                url = "${account.regionEnum.apiBase}/v2/report",
                body = body,
                headers = if (mp) mpHeaders(account) else desktopHeaders(account),
            )
        }
    }

    /** 自定义事件批直接上报到 workbuddy.cn（资料库阅读等 web 埋点）。 */
    suspend fun reportWebRaw(account: WorkBuddyAccount, body: JsonArray) {
        if (body.isEmpty()) return
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                url = "$GROWTH_HOST/v2/report",
                body = body,
                headers = mapOf(
                    "Authorization" to "Bearer ${account.accessToken}",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "x-client-platform" to "web",
                    "Origin" to GROWTH_HOST,
                    "Referer" to "$GROWTH_HOST/space/d/o0KWYeynteVv06UnAZqIFm",
                    "User-Agent" to WEB_UA,
                    "X-User-Id" to account.uid,
                ),
            )
        }
    }

    // endregion

    // region 成长 / 连登 / 抽奖

    /** `GET {apiBase}/activity/growth/streak`。 */
    suspend fun streak(account: WorkBuddyAccount): JsonObject = growthRequest(account, "GET", "/activity/growth/streak")

    /** `GET {apiBase}/activity/growth/heatmap`。 */
    suspend fun heatmap(account: WorkBuddyAccount): JsonObject = growthRequest(account, "GET", "/activity/growth/heatmap")

    /** `POST {apiBase}/activity/growth/makeup-cards/use`，补签某天。 */
    suspend fun useMakeupCard(account: WorkBuddyAccount, targetDate: String): JsonObject =
        growthRequest(account, "POST", "/activity/growth/makeup-cards/use", jsonObj("target_date" to targetDate))

    /** `GET {apiBase}/activity/growth/lottery/summary` → 剩余抽奖次数。 */
    suspend fun lotteryChances(account: WorkBuddyAccount): Int =
        growthRequest(account, "GET", "/activity/growth/lottery/summary").optInt("chances")

    /** `POST {apiBase}/activity/growth/lottery/draw`。 */
    suspend fun lotteryDraw(account: WorkBuddyAccount): JsonObject =
        growthRequest(account, "POST", "/activity/growth/lottery/draw", jsonObj("draw_uuid" to randomUuid()))

    /** `POST {apiBase}/activity/growth/redeem`，连登档位兑换。 */
    suspend fun redeem(account: WorkBuddyAccount, tier: String): JsonObject =
        growthRequest(account, "POST", "/activity/growth/redeem", jsonObj("tier" to tier))

    // endregion

    // region Buddy（企鹅教师助手）

    /** `GET {apiBase}/activity/growth/buddy/info` → buddy 对象，null 表示尚未领养。 */
    suspend fun buddyInfo(account: WorkBuddyAccount): JsonObject? {
        val obj = runCatching { growthRequest(account, "GET", "/activity/growth/buddy/info") }.getOrNull() ?: return null
        val buddy = obj["buddy"] as? JsonObject ?: return null
        return buddy.takeIf { it.isNotEmpty() }
    }

    /** `POST {apiBase}/activity/growth/buddy/agreement`。 */
    suspend fun buddyAgree(account: WorkBuddyAccount): JsonObject =
        growthRequest(account, "POST", "/activity/growth/buddy/agreement", jsonObj("agree" to true))

    /** `POST {apiBase}/activity/growth/buddy/first`，领养第一只 Buddy。 */
    suspend fun buddyFirst(account: WorkBuddyAccount): JsonObject =
        growthRequest(account, "POST", "/activity/growth/buddy/first")

    /** `GET {apiBase}/activity/growth/buddy/travel/status`。 */
    suspend fun travelStatus(account: WorkBuddyAccount): JsonObject =
        growthRequest(account, "GET", "/activity/growth/buddy/travel/status")

    /** `POST {apiBase}/activity/growth/buddy/travel/depart`。 */
    suspend fun travelDepart(account: WorkBuddyAccount): JsonObject =
        growthRequest(account, "POST", "/activity/growth/buddy/travel/depart", jsonObj("location_id" to 1))

    /** `POST {apiBase}/activity/growth/buddy/travel/claim`。 */
    suspend fun travelClaim(account: WorkBuddyAccount, recordId: Long): JsonObject =
        growthRequest(account, "POST", "/activity/growth/buddy/travel/claim", jsonObj("record_id" to recordId))

    // endregion

    // region 开学季 portal

    /** `GET {apiBase}/portal/activity/school/tasks`。 */
    suspend fun schoolTasks(account: WorkBuddyAccount): JsonObject =
        schoolRequest(account, "GET", "/tasks", null)

    /** 标记开学季任务已查看（`/tasks/{code}/viewed`）。 */
    suspend fun schoolViewed(account: WorkBuddyAccount, code: String) {
        schoolRequest(account, "POST", "/tasks/$code/viewed", jsonObj())
    }

    /** 分享任务完成上报（`/tasks/share-complete`）。 */
    suspend fun schoolShareComplete(account: WorkBuddyAccount) {
        schoolRequest(account, "POST", "/tasks/share-complete", jsonObj("channel" to "wechat"))
    }

    /** 领取开学季任务奖励，返回抽奖次数。 */
    suspend fun schoolClaim(account: WorkBuddyAccount, code: String): Int =
        schoolRequest(account, "POST", "/tasks/$code/claim", jsonObj()).optInt("chance_granted")

    /** `GET {apiBase}/portal/activity/school/config` → 抽奖机会余额。 */
    suspend fun schoolDrawChances(account: WorkBuddyAccount): Int =
        (schoolRequest(account, "GET", "/config", null)["chance"] as? JsonObject)?.optInt("balance") ?: 0

    /** `POST {apiBase}/portal/activity/school/wheel/draw`。 */
    suspend fun schoolWheelDraw(account: WorkBuddyAccount): JsonObject =
        schoolRequest(account, "POST", "/wheel/draw", jsonObj("draw_uuid" to randomUuid()))

    // endregion

    // region 额度补偿

    /** `POST {apiBase}/billing/meter/claim-compensation`。 */
    suspend fun claimCompensation(account: WorkBuddyAccount): Long =
        growthRequest(account, "POST", "/billing/meter/claim-compensation").optLong("credit")

    /** `POST {apiBase}/billing/meter/claim-gift`。 */
    suspend fun claimGift(account: WorkBuddyAccount): Long =
        growthRequest(account, "POST", "/billing/meter/claim-gift").optLong("credit")

    // endregion

    // region 专家市场 / 外观

    /**
     * `POST {apiBase}/portal/operation-platform/market/expert/list`。
     *
     * @param expertType `agent`（专家）/ `team`（专家团）；为空则不过滤。
     */
    suspend fun fetchExperts(account: WorkBuddyAccount, expertType: String): List<WorkBuddyExpert> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("page", 1)
                put("page_size", 20)
                put("sort_by", "reco_rank")
                put("sort_order", "desc")
                if (expertType.isNotBlank()) put("expert_type", expertType)
            }
            val headers = mapOf(
                "Authorization" to "Bearer ${account.accessToken}",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to DESKTOP_UA,
                "X-Domain" to account.regionEnum.apiBase,
                "X-Product" to "SaaS",
                "X-User-Id" to account.uid,
            )
            val obj = request(
                "POST",
                "${account.regionEnum.apiBase}/portal/operation-platform/market/expert/list",
                body,
                headers,
            )
            val arr = obj.optArr("experts") ?: return@withContext emptyList()
            arr.mapNotNull { it.toExpertOrNull() }
        }

    /** `POST {apiBase}/v2/user-asset/appearance/set`，设置主题皮肤。 */
    suspend fun appearanceSet(account: WorkBuddyAccount, kind: String, resourceKey: String) {
        withContext(Dispatchers.IO) {
            request(
                "POST",
                "${account.regionEnum.apiBase}/v2/user-asset/appearance/set",
                jsonObj("kind" to kind, "resource_key" to resourceKey),
                desktopHeaders(account),
            )
        }
    }

    // endregion

    // region 真实对话（用于让「对话类」任务真正计分）

    /**
     * 桌面指纹对话：`POST {apiBase}/v2/chat/completions`，从 SSE 中解析服务端 requestId。
     *
     * @return (conversationId, requestId)
     */
    suspend fun desktopChat(
        account: WorkBuddyAccount,
        prompt: String,
        expertId: String = "",
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val conversationId = "wb2api-conv-${System.nanoTime()}"
        val body = jsonObj(
            "model" to "fast-model",
            "messages" to jsonArr(
                jsonObj("role" to "system", "content" to "You are a helpful assistant. 当前处于中文环境，使用简体中文回答。"),
                jsonObj("role" to "user", "content" to prompt),
            ),
            "agent" to "cli",
            "temperature" to 1,
            "stream" to true,
            "stream_options" to jsonObj("include_usage" to true),
        )
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${account.accessToken}",
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
            "User-Agent" to DESKTOP_UA,
            "X-Domain" to account.domain.ifBlank { account.regionEnum.apiBase },
            "X-Product" to "SaaS",
            "X-User-Id" to account.uid,
            "X-Conversation-ID" to conversationId,
            "X-Request-ID" to System.nanoTime().toString(),
            "X-Agent-Intent" to "craft",
            "X-Agent-Type" to "main",
            "X-IDE-Name" to "WorkBuddy",
            "X-IDE-Type" to "WorkBuddy",
            "X-IDE-Version" to "5.5.6",
            "x-codebuddy-request" to "1",
        )
        if (expertId.isNotBlank()) headers["X-Expert-Id"] = expertId

        val text = requestText("POST", "${account.regionEnum.apiBase}/v2/chat/completions", body, headers)
        val requestId = REQUEST_ID_REGEX.find(text)?.groupValues?.get(1)
            ?: throw IOException("SSE 中未找到服务端 requestId")
        conversationId to requestId
    }

    /** 简单对话（glm-5.2，只关心请求是否成功）。 */
    suspend fun simpleChat(account: WorkBuddyAccount, prompt: String) {
        withContext(Dispatchers.IO) {
            val body = jsonObj(
                "model" to "glm-5.2",
                "messages" to jsonArr(jsonObj("role" to "user", "content" to prompt)),
                "stream" to true,
            )
            val headers = mapOf(
                "Authorization" to "Bearer ${account.accessToken}",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
                "User-Agent" to DESKTOP_UA,
                "X-Domain" to account.domain.ifBlank { account.regionEnum.apiBase },
                "X-Product" to "SaaS",
                "X-User-Id" to account.uid,
                "X-IDE-Name" to "WorkBuddy",
                "X-IDE-Type" to "WorkBuddy",
                "X-IDE-Version" to "5.5.6",
                "x-codebuddy-request" to "1",
            )
            requestText("POST", "${account.regionEnum.apiBase}/v2/chat/completions", body, headers)
        }
    }

    // endregion

    // region 指纹

    fun machineId(account: WorkBuddyAccount): String = fingerprint("machine", account.uid)

    fun sessionId(account: WorkBuddyAccount): String = fingerprint("session", account.uid)

    fun requestId(account: WorkBuddyAccount): String =
        fingerprint("req", account.uid) + (System.nanoTime() % 1_000_000)

    // endregion

    // region 内部请求

    private suspend fun growthRequest(
        account: WorkBuddyAccount,
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        request(method, "${account.regionEnum.apiBase}$path", body, growthHeaders(account))
    }

    private suspend fun schoolRequest(
        account: WorkBuddyAccount,
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonObject = withContext(Dispatchers.IO) {
        request(method, "${account.regionEnum.apiBase}/portal/activity/school$path", body, growthHeaders(account))
    }

    /** 成长接口请求头（对应上游 `c()`）。 */
    private fun growthHeaders(account: WorkBuddyAccount): Map<String, String> {
        val region = account.regionEnum
        return buildMap {
            put("Authorization", "Bearer ${account.accessToken}")
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("User-Agent", upstreamUserAgent(account))
            put("X-CodeBuddy-Request", "1")
            put("Accept-Language", region.acceptLanguage)
            put("X-User-Id", account.uid)
            put("X-Domain", account.domain.ifBlank { region.domain })
        }
    }

    /** 桌面端 `/v2/report` 请求头（对应上游 `q()`）。 */
    private fun desktopHeaders(account: WorkBuddyAccount): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${account.accessToken}")
        put("Accept", "application/json, text/plain, */*")
        put("Content-Type", "application/json;charset=UTF-8")
        put("User-Agent", DESKTOP_UA)
        put("X-Domain", account.regionEnum.apiBase)
        put("X-Product", "SaaS")
        put("X-Request-ID", requestId(account))
        put("X-User-Id", account.uid)
    }

    /** 小程序端 `/v2/report` 请求头。 */
    private fun mpHeaders(account: WorkBuddyAccount): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${account.accessToken}")
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        put("User-Agent", upstreamUserAgent(account))
        put("X-User-Id", account.uid)
        put("X-Client-Product", "workbuddy-mp")
        put("X-Client-Version", "2.4.0")
        put("X-Client-Platform", "mp-weixin")
        put("X-Platform", "wechatmp")
    }

    private fun upstreamUserAgent(account: WorkBuddyAccount): String {
        val product = if (account.regionEnum.code == "international") "WorkBuddy AI" else "WorkBuddy"
        return "WorkBuddy/5.5.4 $product/5.5.4 CLI/2.137.1"
    }

    private fun request(
        method: String,
        url: String,
        body: JsonElement?,
        headers: Map<String, String>,
    ): JsonObject {
        val text = requestText(method, url, body, headers)
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun requestText(
        method: String,
        url: String,
        body: JsonElement?,
        headers: Map<String, String>,
    ): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val payload = body?.let { json.encodeToString(JsonElement.serializer(), it) }
        builder.method(method, payload?.toRequestBody(JSON_MEDIA_TYPE))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("上游返回 ${response.code}: ${text.take(200)}")
            }
            return text
        }
    }

    @Suppress("unused")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // endregion
}

internal fun JsonObject.optInt(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

internal fun JsonObject.optLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

internal fun JsonObject.optBoolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

internal fun JsonObject.optString(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optArr(key: String): JsonArray? = this[key] as? JsonArray

@Suppress("unused")
private fun log(message: String) = Log.d(TAG, message)

/** 构建仅含一个字段的对象（便捷函数）。 */
internal fun objOf(key: String, value: Any?): JsonObject = buildJsonObject { putAny(key, value) }

/** 把专家市场的单条 JSON 转成 [WorkBuddyExpert]。 */
private fun JsonElement.toExpertOrNull(): WorkBuddyExpert? {
    val item = this as? JsonObject ?: return null
    val id = item.optString("expert_id")
    if (id.isBlank()) return null
    val category = ((item.optArr("categories")?.firstOrNull()) as? JsonPrimitive)?.contentOrNull.orEmpty()
    return WorkBuddyExpert(
        id = id,
        expertType = item.optString("expert_type"),
        name = item.optString("display_name_zh"),
        title = item.optString("profession_zh"),
        version = item.optString("version"),
        category = category,
    )
}