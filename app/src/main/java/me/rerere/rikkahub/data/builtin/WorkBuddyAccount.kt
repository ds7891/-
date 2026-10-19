package me.rerere.rikkahub.data.builtin

import kotlinx.serialization.Serializable

/**
 * 一个已登录的 CodeBuddy / WorkBuddy 账号。
 *
 * 上游登录成功后返回 accessToken / refreshToken，两者都用于后续请求上游：
 * - accessToken 放在 `Authorization: Bearer` 头里
 * - refreshToken 用于过期前调用刷新接口换取新的 accessToken
 */
@Serializable
data class WorkBuddyAccount(
    val uid: String = "",
    val nickname: String = "",
    val region: String = WorkBuddyRegion.INTERNATIONAL.code,
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val domain: String = "",
    /** 最近一次刷新到的剩余额度（null 表示尚未刷新）。 */
    val creditsRemaining: Double? = null,
    /** 最近一次刷新到的总额度。 */
    val creditsTotal: Double? = null,
    /** 额度到期时间（epoch 秒）。 */
    val creditsExpireAt: Double? = null,
    /** 最近一次签到日期，格式 `yyyy-MM-dd`。 */
    val lastCheckInDate: String = "",
) {
    val regionEnum: WorkBuddyRegion
        get() = WorkBuddyRegion.fromCode(region)

    /** 展示名：优先昵称，其次 uid。 */
    val displayName: String
        get() = nickname.ifBlank { uid.ifBlank { "未命名账号" } }

    /** 是否已刷新过额度。 */
    val hasQuota: Boolean
        get() = creditsRemaining != null

    /** 额度文案，如 `剩余 120.0 / 总计 500.0 积分`。 */
    val quotaText: String
        get() {
            val remaining = creditsRemaining ?: return "额度未刷新"
            val total = creditsTotal ?: 0.0
            return "剩余 ${formatCredits(remaining)} / 总计 ${formatCredits(total)} 积分"
        }

    /** 判断给定日期（`yyyy-MM-dd`）是否已签到。 */
    fun checkedInOn(date: String): Boolean = lastCheckInDate == date && date.isNotBlank()

    /** accessToken 是否在 [skewMillis] 内即将过期。 */
    fun isExpiring(skewMillis: Long = 60_000L): Boolean {
        if (expiresAt <= 0L) return false
        return System.currentTimeMillis() >= expiresAt - skewMillis
    }

    val isUsable: Boolean
        get() = accessToken.isNotBlank()
}
