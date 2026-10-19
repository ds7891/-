package me.rerere.rikkahub.data.builtin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "WorkBuddyAuthApi"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 成长中心（签到 / 任务）固定域名，领取任务的接口只挂在它下面。 */
private const val GROWTH_HOST = "https://www.workbuddy.cn"

/** 额度与签到接口需要模拟网页端 UA。 */
private const val WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

/** 额度包查询的产品编码。 */
private const val QUOTA_PRODUCT_CODE = "p_tcaca"

/**
 * CodeBuddy / WorkBuddy 账号认证与上游请求构造。
 *
 * 登录流程（与桌面端 CLI 插件一致）：
 * 1. `POST {apiBase}/v2/plugin/auth/state?platform=CLI` 获取 `state` 与 `authUrl`
 * 2. 在 WebView 中打开 `authUrl`，用户完成登录
 * 3. 轮询 `GET {apiBase}/v2/plugin/auth/token?state=...` 直到拿到 accessToken
 */
class WorkBuddyAuthApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun requestAuthState(region: WorkBuddyRegion): WorkBuddyAuthSession =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${region.apiBase}/v2/plugin/auth/state?platform=CLI")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .apply { anonymousHeaders(region) }
                .build()
            val obj = unwrap(executeJson(request))
            val state = obj.stringOrNull("state").orEmpty()
            val authUrl = obj.stringOrNull("authUrl")
                ?: obj.stringOrNull("auth_url")
                ?: obj.stringOrNull("url")
                ?: ""
            if (state.isBlank() || authUrl.isBlank()) {
                throw IOException("登录接口未返回 state/authUrl")
            }
            WorkBuddyAuthSession(region = region, state = state, authUrl = authUrl)
        }

    /**
     * 轮询换取令牌。登录尚未完成时上游通常返回空对象，此时返回 null。
     */
    suspend fun pollAuthToken(region: WorkBuddyRegion, state: String): WorkBuddyAccount? =
        withContext(Dispatchers.IO) {
            val url = "${region.apiBase}/v2/plugin/auth/token?state=${encode(state)}"
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { anonymousHeaders(region) }
                .build()
            val obj = runCatching { unwrap(executeJson(request)) }.getOrElse {
                Log.d(TAG, "pollAuthToken failed: ${it.message}")
                return@withContext null
            }
            parseAccount(obj, region).takeIf { it.accessToken.isNotBlank() }
        }

    /** 拉取账号昵称/uid，用于补全展示信息。 */
    suspend fun fetchAccountInfo(region: WorkBuddyRegion, state: String): JsonObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${region.apiBase}/v2/plugin/login/account?state=${encode(state)}"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { anonymousHeaders(region) }
                    .build()
                unwrap(executeJson(request))
            }.getOrNull()
        }

    /** 过期前刷新 accessToken。 */
    suspend fun refresh(account: WorkBuddyAccount): WorkBuddyAccount = withContext(Dispatchers.IO) {
        val region = account.regionEnum
        val request = Request.Builder()
            .url("${region.apiBase}/v2/plugin/auth/token/refresh")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                authorizedHeaders(region, account)
                header("X-Refresh-Token", account.refreshToken)
                header("X-Auth-Refresh-Source", "plugin")
            }
            .build()
        val obj = unwrap(executeJson(request))
        val refreshed = parseAccount(obj, region)
        account.copy(
            accessToken = refreshed.accessToken.ifBlank { account.accessToken },
            refreshToken = refreshed.refreshToken.ifBlank { account.refreshToken },
            expiresAt = refreshed.expiresAt.takeIf { it > 0L } ?: account.expiresAt,
            domain = refreshed.domain.ifBlank { account.domain },
        )
    }

    /** 拉取该账号可用的模型列表（失败时返回空列表）。 */
    suspend fun fetchModels(account: WorkBuddyAccount): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val region = account.regionEnum
                val request = Request.Builder()
                    .url("${region.modelsBase}/v2/enterprises/personal/models")
                    .get()
                    .apply {
                        authorizedHeaders(region, account)
                        header("Origin", region.origin)
                        header("Referer", region.referer)
                    }
                    .build()
                val root = unwrapValue(executeJson(request))
                val array = when (root) {
                    is JsonArray -> root
                    is JsonObject -> (root["models"] ?: root["data"] ?: root["list"]) as? JsonArray
                    else -> null
                } ?: return@runCatching emptyList()
                array.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = item.stringOrNull("id") ?: return@mapNotNull null
                    id to (item.stringOrNull("name") ?: id)
                }
            }.getOrElse {
                Log.d(TAG, "fetchModels failed: ${it.message}")
                emptyList()
            }
        }

    /**
     * 刷新账号额度。
     *
     * 上游会把「额度包」列在 `get-user-resource` 的 `Accounts` 字段里，逐个求和即得到
     * 剩余 / 总计积分。国内版走 `{apiBase}`，国际版优先尝试 `workbuddy.ai` 域名。
     */
    suspend fun refreshQuota(account: WorkBuddyAccount): WorkBuddyQuota = withContext(Dispatchers.IO) {
        val region = account.regionEnum
        val international = region == WorkBuddyRegion.INTERNATIONAL
        val userAgent = if (international) "WorkBuddy/5.5.4" else WEB_USER_AGENT
        val attempts = buildList {
            if (international) {
                add("https://www.workbuddy.ai/v2/billing/meter/get-user-resource" to quotaBody(withTimeRange = true))
                add("https://www.workbuddy.ai/billing/meter/get-user-resource" to quotaBody(withTimeRange = true))
            }
            add("${region.apiBase}/v2/billing/meter/get-user-resource" to quotaBody(withTimeRange = false))
            add("${region.apiBase}/billing/meter/get-user-resource-summary" to JsonObject(emptyMap()))
        }

        var lastError: Throwable? = null
        for ((url, body) in attempts) {
            val quota = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                    .apply { growthHeaders(region, account, userAgentOverride = userAgent) }
                    .build()
                val root = executeJson(request)
                unwrapValue(root)
                parseQuota(root)
            }.onFailure {
                lastError = it
                Log.d(TAG, "refreshQuota $url failed: ${it.message}")
            }.getOrNull()
            if (quota != null) return@withContext quota
        }
        throw lastError ?: IOException("额度接口未返回任何额度包")
    }

    /**
     * 每日签到。国际版没有签到入口，退化为刷新额度并返回提示。
     */
    suspend fun checkIn(account: WorkBuddyAccount): WorkBuddyCheckInResult = withContext(Dispatchers.IO) {
        val region = account.regionEnum
        if (region == WorkBuddyRegion.INTERNATIONAL) {
            val quota = runCatching { refreshQuota(account) }.getOrNull()
            return@withContext WorkBuddyCheckInResult(
                ok = true,
                already = false,
                supported = false,
                message = "国际版额度由上游自动发放，已为你刷新当前额度",
                creditsRemaining = quota?.remaining,
                creditsTotal = quota?.total,
            )
        }

        val before = account.creditsRemaining
        val request = Request.Builder()
            .url("${region.apiBase}/v2/billing/meter/daily-checkin")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .apply { growthHeaders(region, account, userAgentOverride = WEB_USER_AGENT) }
            .build()
        val root = executeJson(request)
        val message = root.stringOrNull("msg") ?: root.stringOrNull("message") ?: ""
        val code = (root["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
        val already = message.contains("已签到") || message.contains("already", ignoreCase = true)
        val ok = code == 0 || already
        val quota = runCatching { refreshQuota(account) }.getOrNull()
        WorkBuddyCheckInResult(
            ok = ok,
            already = already,
            supported = true,
            message = when {
                ok && already -> "今日已签到"
                ok -> "签到成功"
                message.isNotBlank() -> message
                else -> "签到失败（上游未返回原因）"
            },
            creditsBefore = before,
            creditsRemaining = quota?.remaining,
            creditsTotal = quota?.total,
            creditsGained = if (before != null && quota != null) quota.remaining - before else null,
        )
    }

    /** 拉取成长中心的任务列表。 */
    suspend fun fetchTasks(account: WorkBuddyAccount): List<WorkBuddyTask> = withContext(Dispatchers.IO) {
        val region = account.regionEnum
        val request = Request.Builder()
            .url("${region.apiBase}/v2/activity/growth/tasks")
            .get()
            .apply { growthHeaders(region, account) }
            .build()
        val root = executeJson(request)
        val unwrapped = unwrapValue(root) as? JsonObject ?: root
        val array = (unwrapped["tasks"] as? JsonArray)
            ?: (root["tasks"] as? JsonArray)
            ?: findArrayByKey(root, "tasks")
            ?: return@withContext emptyList()
        array.mapNotNull { it.toTaskOrNull() }
    }

    /** 接受任务（把任务加入「进行中」，之后才会计入进度）。 */
    suspend fun acceptTasks(account: WorkBuddyAccount, codes: List<String>) {
        if (codes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val region = account.regionEnum
            val body = buildJsonObject {
                putJsonArray("task_codes") { codes.forEach { add(it) } }
            }
            val request = Request.Builder()
                .url("${region.apiBase}/v2/activity/growth/tasks/accept")
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .apply { growthHeaders(region, account) }
                .build()
            unwrap(executeJson(request))
        }
    }

    /** 领取任务奖励，返回 (积分, 能量)。 */
    suspend fun claimTask(account: WorkBuddyAccount, taskCode: String): Pair<Long, Long> =
        withContext(Dispatchers.IO) {
            val region = account.regionEnum
            val url = "$GROWTH_HOST/activity/growth/tasks/${encode(taskCode)}/claim"
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    growthHeaders(region, account, webOrigin = true)
                    header("Accept", "application/json, text/plain, */*")
                    header("x-client-platform", "web")
                    header("Origin", GROWTH_HOST)
                    header("Referer", "$GROWTH_HOST/profile/growth-center")
                }
                .build()
            val obj = unwrap(executeJson(request))
            val alreadyClaimed = (obj["already_claimed"] as? JsonPrimitive)?.booleanOrNull == true
            if (alreadyClaimed) {
                0L to 0L
            } else {
                (obj.longOrNull("credit") ?: 0L) to (obj.longOrNull("energy") ?: 0L)
            }
        }

    /** 额度 / 签到 / 任务等成长接口使用的请求头。 */
    private fun Request.Builder.growthHeaders(
        region: WorkBuddyRegion,
        account: WorkBuddyAccount,
        webOrigin: Boolean = false,
        userAgentOverride: String? = null,
    ): Request.Builder = apply {
        header("Authorization", "Bearer ${account.accessToken}")
        if (account.uid.isNotBlank()) {
            header("X-User-Id", account.uid)
        }
        header("X-Domain", account.domain.ifBlank { region.domain })
        header("X-CodeBuddy-Request", "1")
        header("X-Requested-With", "XMLHttpRequest")
        header("X-No-Enterprise-Id", "1")
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        header("Accept-Language", region.acceptLanguage)
        header("User-Agent", userAgentOverride ?: region.userAgent)
        if (webOrigin) {
            header("Origin", region.origin)
            header("Referer", region.referer)
        }
    }

    /** 额度查询请求体；[withTimeRange] 为 true 时附带额度包到期时间范围。 */
    private fun quotaBody(withTimeRange: Boolean): JsonObject = buildJsonObject {
        put("PageNumber", 1)
        put("PageSize", 100)
        put("ProductCode", QUOTA_PRODUCT_CODE)
        putJsonArray("Status") {
            add(0)
            add(3)
        }
        if (withTimeRange) {
            put("PackageEndTimeRangeBegin", formatGmt8DayOffset(0))
            put("PackageEndTimeRangeEnd", formatGmt8DayOffset(36_865))
        }
    }

    /** 把上游返回的额度包逐个求和，得到剩余 / 总计额度。 */
    private fun parseQuota(root: JsonObject): WorkBuddyQuota? {
        val accounts = findArrayByKey(root, "Accounts") ?: return null
        if (accounts.isEmpty()) return null
        var remaining = 0.0
        var total = 0.0
        var expireAt: Double? = null
        val packages = mutableListOf<WorkBuddyCreditPackage>()
        accounts.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val cycleTotal = item.doubleOrNull("CycleCapacitySize") ?: 0.0
            val itemRemaining: Double
            val itemTotal: Double
            if (cycleTotal > 0.0) {
                itemRemaining = item.doubleOrNull("CycleCapacityRemain") ?: 0.0
                itemTotal = cycleTotal
            } else {
                itemRemaining = item.doubleOrNull("CapacityRemain") ?: 0.0
                itemTotal = item.doubleOrNull("CapacitySize") ?: 0.0
            }
            remaining += itemRemaining
            total += itemTotal
            val itemExpire = listOf("ExpireTime", "PackageEndTime", "EndTime")
                .firstNotNullOfOrNull { key ->
                    val raw = item.doubleOrNull(key) ?: return@firstNotNullOfOrNull null
                    if (raw <= 0.0) null else if (raw > 1.0E10) raw / 1000.0 else raw
                }
            if (itemExpire != null && itemRemaining > 0.0) {
                expireAt = expireAt?.let { minOf(it, itemExpire) } ?: itemExpire
            }
            packages += WorkBuddyCreditPackage(
                remaining = itemRemaining,
                total = itemTotal,
                expireAt = itemExpire,
            )
        }
        return WorkBuddyQuota(remaining = remaining, total = total, expireAt = expireAt, packages = packages)
    }

    /** 在 JSON 树里递归查找指定 key 的数组（兼容多种信封层级）。 */
    private fun findArrayByKey(element: JsonElement, key: String): JsonArray? {
        when (element) {
            is JsonArray -> element.forEach { findArrayByKey(it, key)?.let { found -> return found } }
            is JsonObject -> {
                (element[key] as? JsonArray)?.let { return it }
                element.values.forEach { findArrayByKey(it, key)?.let { found -> return found } }
            }
            else -> return null
        }
        return null
    }

    /** 把任务中心的单条 JSON 转成 [WorkBuddyTask]。 */
    private fun JsonElement.toTaskOrNull(): WorkBuddyTask? {
        val item = this as? JsonObject ?: return null
        val code = item.stringOrNull("task_code") ?: return null
        val progress = item["progress"] as? JsonObject
        val progressCurrent = progress?.longOrNull("current") ?: 0L
        val progressTarget = progress?.longOrNull("target") ?: 0L
        val current = if (progressCurrent > 0L || progressTarget > 0L) progressCurrent else item.longOrNull("current") ?: 0L
        val target = if (progressCurrent > 0L || progressTarget > 0L) progressTarget else item.longOrNull("target") ?: 0L
        return WorkBuddyTask(
            code = code,
            title = item.stringOrNull("title") ?: code,
            description = item.stringOrNull("description") ?: "",
            rewardCredit = item.longOrNull("reward_credit") ?: 0L,
            rewardEnergy = item.longOrNull("reward_energy") ?: 0L,
            current = current,
            target = target,
            status = item.stringOrNull("accept_status") ?: "",
            locked = (item["locked"] as? JsonPrimitive)?.booleanOrNull ?: false,
            jumpUrl = item.stringOrNull("jump_url") ?: "",
        )
    }

    /** 生成 GMT+8 时区、偏移 [days] 天的时间字符串（`yyyy-MM-dd HH:mm:ss`）。 */
    private fun formatGmt8DayOffset(days: Int): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT+8")
        return format.format(Date(System.currentTimeMillis() + days.toLong() * 86_400_000L))
    }

    /**
     * 构造发往上游的对话补全请求。
     *
     * 本地三种协议（chat/completions、completions、messages）最终都会走到上游的
     * `POST {apiBase}/v2/chat/completions`。
     */
    fun buildChatRequest(account: WorkBuddyAccount, body: JsonObject, stream: Boolean): Request {
        val region = account.regionEnum
        val builder = Request.Builder()
            .url("${region.apiBase}/v2/chat/completions")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .apply { authorizedHeaders(region, account) }
        if (stream) {
            builder.header("Accept", "text/event-stream")
        }
        return builder.build()
    }

    private fun Request.Builder.anonymousHeaders(region: WorkBuddyRegion): Request.Builder = apply {
        header("X-No-Authorization", "1")
        header("X-No-User-Id", "1")
        header("X-No-Enterprise-Id", "1")
        header("X-No-Department-Info", "1")
        header("X-Domain", region.domain)
        header("Content-Type", "application/json")
        header("Accept-Language", region.acceptLanguage)
        header("User-Agent", region.userAgent)
        header("Origin", region.origin)
        header("Referer", region.referer)
    }

    private fun Request.Builder.authorizedHeaders(region: WorkBuddyRegion, account: WorkBuddyAccount): Request.Builder = apply {
        header("Authorization", "Bearer ${account.accessToken}")
        if (account.uid.isNotBlank()) {
            header("X-User-Id", account.uid)
        }
        header("X-Domain", account.domain.ifBlank { region.domain })
        header("X-Product", "SaaS")
        header("X-Requested-With", "XMLHttpRequest")
        header("Content-Type", "application/json")
        header("Accept", "application/json, text/event-stream")
        header("Accept-Language", region.acceptLanguage)
        header("User-Agent", region.userAgent)
        header("Origin", region.origin)
        header("Referer", region.referer)
    }

    private fun executeJson(request: Request): JsonObject {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("上游返回 ${response.code}: ${text.take(300)}")
            }
            return json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("上游返回了非 JSON 内容")
        }
    }

    private fun parseAccount(obj: JsonObject, region: WorkBuddyRegion): WorkBuddyAccount {
        val auth = (obj["auth"] as? JsonObject) ?: obj
        val account = (obj["account"] as? JsonObject)
            ?: ((auth["account"]) as? JsonObject)
            ?: JsonObject(emptyMap())
        val accessToken = auth.stringOrNull("accessToken")
            ?: auth.stringOrNull("access_token")
            ?: obj.stringOrNull("accessToken")
            ?: ""
        val refreshToken = auth.stringOrNull("refreshToken")
            ?: auth.stringOrNull("refresh_token")
            ?: obj.stringOrNull("refreshToken")
            ?: ""
        val expiresAt = auth.longOrNull("expiresAt")
            ?: auth.longOrNull("expires_at")
            ?: run {
                val expiresIn = auth.longOrNull("expiresIn") ?: auth.longOrNull("expires_in")
                if (expiresIn != null && expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
            }
        val uid = account.stringOrNull("uid")
            ?: account.stringOrNull("userId")
            ?: obj.stringOrNull("uid")
            ?: ""
        val nickname = account.stringOrNull("nickname")
            ?: account.stringOrNull("name")
            ?: obj.stringOrNull("nickname")
            ?: ""
        val domain = auth.stringOrNull("domain") ?: obj.stringOrNull("domain") ?: region.domain
        val regionCode = obj.stringOrNull("region") ?: auth.stringOrNull("region") ?: region.code
        return WorkBuddyAccount(
            uid = uid,
            nickname = nickname,
            region = regionCode,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            domain = domain,
        )
    }

    /** 兼容 `{code, data:{data:{...}}}` / `{code, data:{...}}` / 直接返回 三种信封。 */
    private fun unwrap(root: JsonObject): JsonObject {
        val value = unwrapValue(root)
        return value as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun unwrapValue(root: JsonObject): kotlinx.serialization.json.JsonElement {
        val code = root.stringOrNull("code")
        if (code != null && code != "0") {
            val message = root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "上游请求失败"
            throw IOException(message)
        }
        val data = root["data"] ?: return root
        if (data is JsonObject) {
            return data["data"] ?: data
        }
        return data
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
