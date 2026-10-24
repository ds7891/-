package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.qqbot.QQBotClient
import me.rerere.rikkahub.data.qqbot.QQBotPersona
import me.rerere.rikkahub.data.qqbot.QQBotSendResult

/** QQ 机器人工具注册名。 */
const val QQBOT_TOOL = "qq_bot"

private fun textOnly(json: String): List<UIMessagePart> = listOf(UIMessagePart.Text(json))

private fun toolError(code: String, message: String): String = buildJsonObject {
    put("error", code)
    put("message", message)
}.toString()

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

/**
 * 让模型主动往 QQ 发一条消息。
 *
 * 与「生成完成自动推送」的区别：那个是每轮结束无脑推一条，
 * 这个由模型自己判断该不该发 —— 适合「跑完长任务再通知」「只在被要求时推」的场景。
 */
internal fun buildQQBotTool(settingsStore: SettingsStore): Tool = Tool(
    name = QQBOT_TOOL,
    description = """
        向 QQ 发送消息，需要用户已在「设置 → 清水特色功能 → QQ 机器人」里配好实现端地址与目标。
        典型用法：长任务跑完通知一声、用户要求「发到 QQ 上」、定时提醒。
        参数：
        - text 必填，消息正文。QQ 不渲染 markdown，写纯文本，别用星号井号和表格
        - user_id / group_id 选填，不填则用设置里的默认目标
        - at_user_id 选填，群聊里 @ 某人
        - reply_to 选填，引用某条消息的 id
        返回 JSON：ok / target / message_id，失败给 error 与原因。
        注意：这是真实投递，不要为了测试反复发送刷屏；一轮对话里同一件事最多推一条。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "消息正文，纯文本。")
                })
                put("user_id", buildJsonObject {
                    put("type", "string")
                    put("description", "私聊目标 QQ 号，选填。")
                })
                put("group_id", buildJsonObject {
                    put("type", "string")
                    put("description", "群号，选填。")
                })
                put("at_user_id", buildJsonObject {
                    put("type", "string")
                    put("description", "群聊里要 @ 的 QQ 号，选填。")
                })
                put("reply_to", buildJsonObject {
                    put("type", "string")
                    put("description", "要引用的消息 id，选填。")
                })
            },
            required = listOf("text"),
        )
    },
    // 真实外发到第三方 IM，必须经用户确认，除非开了「默认申请全部同意」
    needsApproval = { true },
    execute = { input: JsonElement ->
        val params = runCatching { input.jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val config = settingsStore.settingsFlow.value.qqBotConfig
        if (!config.enabled) {
            return@Tool textOnly(toolError("DISABLED", "QQ 机器人未启用，请到设置里打开"))
        }
        QQBotClient.checkSendable(config)?.let {
            return@Tool textOnly(toolError("NOT_CONFIGURED", it))
        }
        val body = params.str("text")
        if (body.isBlank()) {
            return@Tool textOnly(toolError("EMPTY_TEXT", "text 不能为空"))
        }
        when (val result = QQBotClient.sendText(
            config = config,
            text = QQBotPersona.promptForOutbound(body),
            userId = params.str("user_id"),
            groupId = params.str("group_id"),
            replyTo = params.str("reply_to"),
            atUserId = params.str("at_user_id"),
        )) {
            is QQBotSendResult.Sent -> textOnly(buildJsonObject {
                put("ok", true)
                put("target", result.targetLabel)
                put("message_id", result.messageId)
            }.toString())

            is QQBotSendResult.Failed -> textOnly(buildJsonObject {
                put("ok", false)
                put("error", result.code)
                put("message", result.reason)
            }.toString())
        }
    },
)
