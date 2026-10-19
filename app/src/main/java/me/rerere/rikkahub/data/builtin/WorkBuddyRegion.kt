package me.rerere.rikkahub.data.builtin

/**
 * 内置模型（CodeBuddy / WorkBuddy）支持的两个登录区域。
 *
 * 每个区域对应一套上游地址：
 * - [apiBase] 用于登录、令牌刷新、对话补全等 API 拼接
 * - [domain] 用于上游 `X-Domain` 请求头
 * - [origin]/[referer] 用于部分上游接口的来源校验
 */
enum class WorkBuddyRegion(
    val code: String,
    val label: String,
    val apiBase: String,
    val domain: String,
    val origin: String,
    val referer: String,
    val acceptLanguage: String,
    val modelsBase: String,
) {
    DOMESTIC(
        code = "domestic",
        label = "国内版",
        apiBase = "https://copilot.tencent.com",
        domain = "www.codebuddy.cn",
        origin = "https://www.codebuddy.cn",
        referer = "https://www.codebuddy.cn/",
        acceptLanguage = "zh-CN",
        modelsBase = "https://copilot.tencent.com",
    ),
    INTERNATIONAL(
        code = "international",
        label = "国际版",
        apiBase = "https://www.codebuddy.ai",
        domain = "www.codebuddy.ai",
        origin = "https://www.workbuddy.ai",
        referer = "https://www.workbuddy.ai/",
        acceptLanguage = "en-US",
        modelsBase = "https://www.workbuddy.ai",
    );

    val userAgent: String
        get() = "WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1"

    companion object {
        fun fromCode(code: String?): WorkBuddyRegion =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: INTERNATIONAL
    }
}

/** 登录第一步返回的授权会话信息。 */
data class WorkBuddyAuthSession(
    val region: WorkBuddyRegion,
    val state: String,
    val authUrl: String,
)
