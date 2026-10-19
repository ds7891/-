package me.rerere.rikkahub.data.builtin

import kotlinx.serialization.Serializable

/** 单个额度包明细（对应上游 `get-user-resource` 返回的一条记录）。 */
@Serializable
data class WorkBuddyCreditPackage(
    val remaining: Double = 0.0,
    val total: Double = 0.0,
    val expireAt: Double? = null,
)

/** 一次「刷新额度」的结果。 */
data class WorkBuddyQuota(
    val remaining: Double,
    val total: Double,
    val expireAt: Double? = null,
    val packages: List<WorkBuddyCreditPackage> = emptyList(),
)

/** 一次「签到」的结果。 */
data class WorkBuddyCheckInResult(
    val ok: Boolean,
    val already: Boolean,
    val supported: Boolean,
    val message: String,
    val creditsBefore: Double? = null,
    val creditsRemaining: Double? = null,
    val creditsTotal: Double? = null,
    val creditsGained: Double? = null,
)

/** 任务中心里的一个任务。 */
@Serializable
data class WorkBuddyTask(
    val code: String,
    val title: String = "",
    val description: String = "",
    val rewardCredit: Long = 0L,
    val rewardEnergy: Long = 0L,
    val current: Long = 0L,
    val target: Long = 0L,
    val status: String = "",
    val locked: Boolean = false,
    val jumpUrl: String = "",
) {
    /** 进度是否已达标。 */
    val completed: Boolean
        get() = target > 0L && current >= target

    /** 是否已领取奖励。 */
    val claimed: Boolean
        get() = status.equals("claimed", ignoreCase = true)

    /** 进度文案，如 `2 / 3`；无目标时为空。 */
    val progressText: String
        get() = if (target > 0L) "$current / $target" else ""
}

/** 额度数值格式化：整数不带小数，小数保留一位。 */
fun formatCredits(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }