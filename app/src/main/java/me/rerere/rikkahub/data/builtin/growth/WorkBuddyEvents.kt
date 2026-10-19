package me.rerere.rikkahub.data.builtin.growth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.util.UUID

/**
 * 成长中心埋点事件构造。
 *
 * 全部照搬上游 APP（`com.joy4fire.workbuddy2api`）的事件结构：
 * 桌面端事件先与 [desktopEnvelope] 合并再整批 POST 到 `{apiBase}/v2/report`。
 */

internal fun JsonObjectBuilder.putAny(key: String, value: Any?) {
    when (value) {
        null -> put(key, JsonNull)
        is JsonElement -> put(key, value)
        is Boolean -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Double -> put(key, value)
        is Float -> put(key, value.toDouble())
        is Collection<*> -> putJsonArray(key) { value.forEach { addAny(it) } }
        else -> put(key, value.toString())
    }
}

internal fun JsonArrayBuilder.addAny(value: Any?) {
    when (value) {
        null -> add(JsonNull)
        is JsonElement -> add(value)
        is Boolean -> add(value)
        is Int -> add(value)
        is Long -> add(value)
        is Double -> add(value)
        is Float -> add(value.toDouble())
        is String -> add(value)
        is Number -> add(value)
        else -> add(value.toString())
    }
}

internal fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> putAny(key, value) }
}

internal fun jsonArr(vararg values: Any?): JsonArray = buildJsonArray {
    values.forEach { addAny(it) }
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** 构造单个事件，`eventCode` 固定在最前。 */
internal fun event(code: String, vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    put("eventCode", code)
    pairs.forEach { (key, value) -> putAny(key, value) }
}

/** 企鹅教师助手（Buddy）事件：统一附带 mode/buddyId/buddyName。 */
internal fun buddyEvent(code: String, vararg pairs: Pair<String, Any?>): JsonObject =
    event(
        code,
        "mode" to "LOCAL",
        "buddyId" to BUDDY_ID,
        "buddyName" to BUDDY_NAME,
        *pairs,
    )

/** 桌面端事件信封（`/v2/report` 每条事件都会带上这些指纹字段）。 */
internal fun desktopEnvelope(
    uid: String,
    nickname: String,
    machineId: String,
    sessionId: String,
    now: Long,
): JsonObject = jsonObj(
    "timezone" to "Asia/Shanghai",
    "reportDelay" to 2000,
    "userId" to uid,
    "username" to nickname,
    "userNickname" to nickname,
    "product" to "SaaS",
    "releaseDate" to 1789036585355L,
    "commit" to "5f9692923c93033111c51ad7b003eb80204a9b75",
    "ideName" to "WorkBuddy",
    "ideType" to "WorkBuddy",
    "ideVersion" to "5.5.6",
    "machineId" to machineId,
    "sessionId" to sessionId,
    "extName" to "workbuddy-desktop",
    "extVersion" to "5.5.6",
    "os" to "win32",
    "arch" to "x64",
    "osVersion" to "10.0.26220",
    "cpuCores" to 20,
    "memorySize" to 24,
    "timestamp" to now,
    "presentAt" to now,
)

/** 小程序端事件信封（走 wechatmp 请求头）。 */
internal fun mpEnvelope(uid: String, nickname: String, now: Long): JsonObject = jsonObj(
    "timestamp" to now,
    "ideType" to "WorkBuddy_MP",
    "ideVersion" to "2.4.0",
    "extName" to "workbuddy-mp",
    "extVersion" to "2.4.0",
    "product" to "SaaS",
    "ideName" to "wx_app_cloud",
    "platform" to "mini_program",
    "os" to "windows",
    "osVersion" to "11",
    "arch" to "x64",
    "machineId" to MP_MACHINE_ID,
    "timezone" to "Asia/Shanghai",
    "userId" to uid,
    "userNickname" to nickname,
)

/** 把信封字段与单条事件合并（事件字段覆盖信封同名字段）。 */
internal fun mergeEnvelope(envelope: JsonObject, item: JsonObject): JsonObject = buildJsonObject {
    envelope.forEach { (key, value) -> put(key, value) }
    item.forEach { (key, value) -> put(key, value) }
}

/** 桌面端完整对话事件链（6 条事件，覆盖「桌面端体验 / 对话 N 次」类任务）。 */
internal fun desktopChatChain(conversationId: String, requestId: String, messageId: String): JsonArray {
    val assistantId = "$messageId-assistant"
    val now = System.currentTimeMillis()
    return jsonArr(
        event(
            "agent_task_created",
            "source" to "LOCAL",
            "name" to "working",
            "task_target" to "local",
            "mode" to "craft",
            "requestModelId" to "fast-model",
            "requestModelName" to "fast-model",
            "has_repo" to false,
            "repo_type" to "none",
            "workspace_type" to "empty",
            "has_connector" to false,
            "connector_types" to jsonArr(),
            "has_mention" to false,
            "mention_types" to jsonArr(),
            "has_template" to false,
            "action" to "",
            "template_name" to "",
            "has_expert" to false,
            "expert_id" to "",
            "expert_name" to "",
            "expert_industry_id" to "",
            "has_skill" to false,
            "skill_names" to jsonArr(),
            "conversationId" to conversationId,
            "messageId" to messageId,
            "buddyId" to "",
            "buddyName" to "",
        ),
        event(
            "chat_message_send",
            "messageId" to assistantId,
            "historyCount" to 0,
            "isContextTruncated" to false,
            "currentStepCount" to 1,
            "traceId" to requestId,
            "rootRequestId" to requestId,
            "parentConversationId" to conversationId,
            "agentName" to "cli",
            "agentType" to "main",
        ),
        event(
            "chat_request_send",
            "inputLength" to 24,
            "isPlan" to false,
            "isAutoExecuteTerminal" to false,
            "isAutoModify" to false,
            "codebaseEnable" to false,
            "maxToken" to 0,
            "maxSteps" to 500,
            "temperature" to 0,
            "maxRetries" to 0,
            "mentionContexts" to jsonArr(),
            "knowledgeId" to jsonArr(),
            "knowledgeName" to jsonArr(),
            "codebaseId" to "",
            "mentionContextCount" to 0,
            "command" to "",
            "recommendId" to "",
            "skillId" to "",
            "skillCount" to 0,
            "totalCount" to 0,
            "traceId" to requestId,
            "rootRequestId" to requestId,
            "parentConversationId" to conversationId,
            "agentName" to "cli",
            "agentType" to "main",
            "codebuddy.session_id" to conversationId,
            "codebuddy.conversation_request_id" to requestId,
        ),
        event(
            "chat_message_response",
            "messageId" to assistantId,
            "responseModelId" to "fast-model",
            "inputToken" to 120,
            "outputToken" to 80,
            "totalToken" to 200,
            "cachedTokens" to 0,
            "cachedWriteTokens" to 0,
            "cachedMissTokens" to 0,
            "isSuccessful" to true,
            "messageErrorCode" to "",
            "finishReason" to "stop",
            "firstTokenAt" to now,
            "traceId" to requestId,
            "conversationId" to conversationId,
            "rootRequestId" to requestId,
            "parentConversationId" to conversationId,
            "agentName" to "cli",
            "agentType" to "main",
            "codebuddy.session_id" to conversationId,
            "codebuddy.conversation_request_id" to requestId,
        ),
        event(
            "chat_message_status",
            "messageId" to assistantId,
            "messageErrorCode" to "0",
            "traceId" to requestId,
            "rootRequestId" to requestId,
            "parentConversationId" to conversationId,
            "agentName" to "cli",
            "agentType" to "main",
        ),
        event(
            "chat_request_response",
            "mode" to "craft",
            "toolCallCount" to 0,
            "inputToken" to 120,
            "outputToken" to 80,
            "totalToken" to 200,
            "cachedTokens" to 0,
            "cachedWriteTokens" to 0,
            "cachedMissTokens" to 0,
            "isSuccessful" to true,
            "messageErrorCode" to "",
            "finishReason" to "stop",
            "rootRequestId" to requestId,
            "parentConversationId" to conversationId,
        ),
    )
}

/** 「使用模板创建任务」事件组（在完整对话链基础上补 2 条模板事件）。 */
internal fun templateUsedChain(
    conversationId: String,
    requestId: String,
    templateId: String,
    templateName: String,
): JsonArray = buildJsonArray {
    desktopChatChain(conversationId, requestId, "msg-$templateId").forEach { add(it) }
    add(
        event(
            "agent_task_created_with_template",
            "mode" to "working",
            "isCustomModel" to false,
            "id" to templateId,
            "name" to templateName,
            "requestId" to requestId,
        )
    )
    add(event("template_used", "template_id" to templateId, "task_mode" to "working"))
}

/** 专家「召唤」事件组（web 元素点击 → 召唤点击 → 召唤成功）。 */
internal fun expertSummonChain(expert: WorkBuddyExpert): JsonArray = jsonArr(
    event(
        "web_element_click",
        "source" to expert.id,
        "type" to expert.categoryOrAll(),
        "version" to expert.versionOrFallback(),
        "elementId" to "expert_summon_click",
        "elementName" to "立即召唤",
        "pageURL" to "/C:/Program%20Files/WorkBuddy/resources/app.asar/renderer/index.html",
    ),
    event(
        "expert_summon_click",
        "id" to expert.id,
        "name" to expert.name,
        "expertTitle" to expert.title,
        "type" to "expert-all",
        "position" to 0,
        "expertType" to expert.expertType,
        "version" to expert.versionOrFallback(),
        "mode" to "LOCAL",
    ),
    event(
        "expert_summoned",
        "id" to expert.id,
        "name" to expert.name,
        "expertTitle" to expert.title,
        "type" to "expert-all",
    ),
)

/** 专家「实际使用」事件。 */
internal fun expertActualUseEvent(
    expert: WorkBuddyExpert,
    conversationId: String,
    requestId: String,
    mode: String,
    type: String = expert.categoryOrAll(),
    cost: Int = 9000,
): JsonObject = event(
    "expert_actual_use",
    "id" to expert.id,
    "name" to expert.name,
    "expertTitle" to expert.title,
    "type" to type,
    "expertType" to expert.expertType,
    "source" to "builtin",
    "version" to expert.versionOrFallback(),
    "cost" to cost,
    "characterCount" to 14,
    "conversationId" to conversationId,
    "requestId" to requestId,
    "messageId" to "msg-${requestId.take(8)}",
    "requestModelId" to "fast-model",
    "requestModelName" to "fast-model",
    "mode" to mode,
)

private fun WorkBuddyExpert.categoryOrAll(): String = category.ifBlank { "expert-all" }

private fun WorkBuddyExpert.versionOrFallback(): String = version.ifBlank { "1.0.0" }

/** 进入企鹅教师助手（Buddy）五连事件。 */
internal fun buddyAppChain(): JsonArray = jsonArr(
    buddyEvent("buddyapp_discover_click"),
    buddyEvent("buddyapp_show", "elementId" to BUDDY_ELEMENT_ID, "elementName" to BUDDY_NAME, "position" to 2),
    buddyEvent(
        "buddyapp_enter_click",
        "elementId" to BUDDY_ELEMENT_ID,
        "elementName" to BUDDY_NAME,
        "position" to 2,
        "isFirstPage" to "1",
    ),
    buddyEvent("buddyapp_auth_confirm_click", "elementId" to BUDDY_ELEMENT_ID, "elementName" to BUDDY_NAME),
    buddyEvent("buddyapp_bindaccount_skip_click", "elementId" to BUDDY_ELEMENT_ID, "elementName" to BUDDY_NAME),
)

/** 单条 `chat_request_send` 事件（真实对话后补报用）。 */
internal fun chatRequestSendEvent(
    conversationId: String,
    requestId: String,
    modelId: String,
    modelName: String,
    uid: String,
): JsonObject = event(
    "chat_request_send",
    "timestamp" to System.currentTimeMillis(),
    "reportDelay" to 0,
    "mode" to "craft",
    "conversationId" to conversationId,
    "requestId" to requestId,
    "inputLength" to 12,
    "requestModelId" to modelId,
    "requestModelName" to modelName,
    "isPlan" to false,
    "isAutoExecuteTerminal" to false,
    "isAutoModify" to false,
    "codebaseEnable" to false,
    "maxToken" to 0,
    "maxSteps" to 0,
    "temperature" to 0,
    "maxRetries" to 0,
    "mentionContexts" to jsonArr(),
    "knowledgeId" to jsonArr(),
    "knowledgeName" to jsonArr(),
    "codebaseId" to "",
    "mentionContextCount" to 0,
    "command" to "",
    "expertId" to "",
    "recommendId" to "",
    "skillId" to "",
    "skillCount" to 0,
    "totalCount" to 0,
    "fileUri" to "",
    "presentAt" to System.currentTimeMillis(),
    "traceId" to "",
    "rootRequestId" to requestId,
    "parentConversationId" to conversationId,
    "agentName" to "default",
    "agentType" to "conversation",
    "userId" to uid,
)

/** 定时任务创建成功事件（automation_1）。 */
internal fun automationTaskCreateChain(): JsonArray = jsonArr(
    event(
        "automated_task_create_suc",
        "name" to "wb2api 自动化",
        "source" to "manually",
        "modelId" to "fast-model",
        "modelIsThinking" to true,
        "connectorCount" to 0,
        "skills" to "",
        "skillCount" to 0,
        "scheduleType" to "once",
        "mode" to "LOCAL",
    )
)

/** 灵感案例做同款 Prompt 事件组（playbook_prompt）。 */
internal fun playbookChain(conversationId: String, requestId: String): JsonArray = buildJsonArray {
    desktopChatChain(conversationId, requestId, "msg-pb").forEach { add(it) }
    add(
        event(
            "web_element_click",
            "pageName" to "playbook_detail",
            "elementId" to "playbook_ctaClick",
            "elementName" to PLAYBOOK_NAME,
            "source" to "discover",
        )
    )
    add(
        event(
            "playbook_cta_click",
            "id" to PLAYBOOK_ID,
            "name" to PLAYBOOK_NAME,
            "type" to "document",
            "categoryId" to "",
            "categoryName" to "",
            "source" to "discover",
            "position" to 0,
        )
    )
    add(
        event(
            "playbook_prompt_send",
            "id" to PLAYBOOK_ID,
            "name" to PLAYBOOK_NAME,
            "type" to "document",
            "categoryId" to "",
            "categoryName" to "",
            "conversationId" to conversationId,
            "requestId" to requestId,
        )
    )
}

/** 设计创意画布创建事件组（create_canvas）。 */
internal fun canvasChain(conversationId: String, requestId: String): JsonArray = buildJsonArray {
    desktopChatChain(conversationId, requestId, "msg-canvas").forEach { add(it) }
    add(
        event(
            "wbx_design_canvas_task_create",
            "conversationId" to conversationId,
            "requestId" to requestId,
            "source" to "summon_keyword",
            "cost" to 12000,
            "isSuccessful" to true,
        )
    )
    add(
        event(
            "wbx_design_canvas_open",
            "conversationId" to conversationId,
            "requestId" to requestId,
            "id" to "ardot-file-${requestId.take(8)}",
            "source" to "summon_keyword",
            "type" to "page",
            "cost" to 13000,
            "isSuccessful" to true,
        )
    )
}

/** 皮肤生效事件（Hp_Appearance）。 */
internal fun appearanceSkinApplyEvent(): JsonObject = event(
    "appearance_skin_apply",
    "action" to "apply",
    "source" to "settings_close",
    "id" to THEME_RESOURCE_KEY,
    "vipLevel" to 0,
    "series" to "",
    "type" to "unknown",
)

/** 技能加载事件（skill_1）。 */
internal fun skillInfoEvent(conversationId: String, requestId: String, messageId: String): JsonObject = event(
    "skill_info",
    "id" to SKILL_NAME,
    "skillId" to SKILL_ID,
    "skillVersion" to "1.0.0",
    "toolStatus" to "success",
    "fileCount" to 56,
    "source" to "workbuddy-desktop",
    "conversationId" to conversationId,
    "requestId" to requestId,
    "messageId" to messageId,
    "requestModelId" to "fast-model",
    "requestModelName" to "fast-model",
    "traceId" to requestId,
)

/** 真实对话 + skill_info（skill_1）：把 `chat_message_response` 的 finishReason 改成 `tool_calls` 再补技能事件。 */
internal fun skillChatChain(conversationId: String, requestId: String, messageId: String): JsonArray = buildJsonArray {
    desktopChatChain(conversationId, requestId, messageId).forEach { item ->
        val obj = item as JsonObject
        if (obj.stringOrNull("eventCode") == "chat_message_response") {
            add(rewriteField(obj, "finishReason", "tool_calls"))
        } else {
            add(obj)
        }
    }
    add(skillInfoEvent(conversationId, requestId, messageId))
}

/** 专家真实对话链：把 `agent_task_created` 标成已带专家，再补专家实际使用事件。 */
internal fun expertChatChain(
    conversationId: String,
    requestId: String,
    messageId: String,
    expert: WorkBuddyExpert,
    useMode: String,
    useType: String,
    useCost: Int,
): JsonArray = buildJsonArray {
    desktopChatChain(conversationId, requestId, messageId).forEach { item ->
        val obj = item as JsonObject
        if (obj.stringOrNull("eventCode") == "agent_task_created") {
            add(
                buildJsonObject {
                    obj.forEach { (key, value) -> put(key, value) }
                    put("has_expert", true)
                    put("expert_id", expert.id)
                    put("expert_name", expert.name)
                    put("expert_industry_id", "")
                }
            )
        } else {
            add(obj)
        }
    }
    add(expertActualUseEvent(expert, conversationId, requestId, useMode, type = useType, cost = useCost))
}

/** 复制对象并覆盖单个字段。 */
private fun rewriteField(source: JsonObject, key: String, value: Any?): JsonObject = buildJsonObject {
    source.forEach { (k, v) -> if (k != key) put(k, v) }
    putAny(key, value)
}

/** 资料库介绍阅读事件（web 域，直接 POST 到 workbuddy.cn）。 */
internal fun libraryReadEvent(
    uid: String,
    nickname: String,
    machineId: String,
    enterpriseId: String,
): JsonObject = jsonObj(
    "eventCode" to "web_element_click",
    "timestamp" to System.currentTimeMillis(),
    "reportDelay" to 0,
    "pageURL" to "https://www.workbuddy.cn/space/d/o0KWYeynteVv06UnAZqIFm",
    "elementId" to "library_doc_intro_click",
    "elementName" to "WorkBuddy资料库介绍",
    "os" to "Win32",
    "arch" to "",
    "osVersion" to "10.0",
    "userAgent" to WEB_UA,
    "machineId" to machineId,
    "userId" to uid,
    "userNickname" to nickname,
    "enterpriseId" to enterpriseId,
)

/** 随机 UUID（小写），用作 `draw_uuid` 等。 */
internal fun randomUuid(): String = UUID.randomUUID().toString().lowercase()

/** 与上游一致：`sha256("{salt}:{uid}")` 取十六进制前 30 位。 */
internal fun fingerprint(salt: String, uid: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$salt:$uid".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(30)
}

internal const val BUDDY_ID = "cb_y5Dy46tPQGGWtueMxXbe"
internal const val BUDDY_NAME = "企鹅教师助手"
internal const val BUDDY_ELEMENT_ID = "cb_y5Dy46tPQGGWtueMxXbe"
internal const val MP_MACHINE_ID = "0655736a-607f-4d9d-b430-58176ee9a090"
internal const val PLAYBOOK_ID = "pm-gtm-launch-plan"
internal const val PLAYBOOK_NAME = "新产品上市 GTM 发布计划一页纸"
internal const val THEME_RESOURCE_KEY = "theme-tkmw7j"
internal const val SKILL_ID = "skill_2097350077599879168"
internal const val SKILL_NAME = "润泽小馆·日报撰写"
internal const val LIGHTHOUSE_EXPERT_ID = "ex_2cvvUZQhDyeJ"
internal const val DESKTOP_UA = "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1"
internal const val WEB_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

/** 「使用模板创建任务」的 5 组模板（id → 名称），照搬上游。 */
internal val TEMPLATE_TASKS: List<Pair<String, String>> = listOf(
    "1" to "深度研究",
    "2" to "周报生成",
    "3" to "竞品分析",
    "4" to "活动策划",
    "5" to "代码评审",
)

/** 轻量云专家（Expert_lighthouse）兜底信息，照搬上游。 */
internal val LIGHTHOUSE_EXPERT_FALLBACK = WorkBuddyExpert(
    id = LIGHTHOUSE_EXPERT_ID,
    expertType = "agent",
    name = "腾讯轻量云专家",
    title = "腾讯轻量云专家",
    version = "1.0.2",
    category = "expert-all",
)