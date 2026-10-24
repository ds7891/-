package me.rerere.rikkahub.data.ai

/**
 * AI 任务托管（清水特色功能）。
 *
 * 用户派发一个任务目标后，由「托管模型」代替用户，根据当前对话判断下一步该发什么，
 * 生成一条可以直接发送的用户消息；循环执行，直到 AI 判定任务完成、用户手动停止或达到轮次上限。
 *
 * 仅供用户不在场时使用，输出内容不保证；一旦检测到内容偏离任务目标，托管模型会被要求先写一条
 * 把话题拉回目标的纠正消息。
 */

/** 托管模型判定「任务已完成」时输出的约定标记。 */
internal const val TASK_HOSTING_DONE_MARKER = "<TASK_DONE>"

/** AI 任务托管的运行时状态（每个会话一份，由 ChatVM 持有）。 */
data class TaskHostingState(
    val running: Boolean = false,
    val objective: String = "",
    val round: Int = 0,
    val maxRounds: Int = 20,
    val status: String? = null,
    val lastMessage: String = "",
)

/** 托管模型对「下一步」的判定结果。 */
data class TaskHostingDecision(
    /** 托管模型是否判定任务已经完成，无需继续发送。 */
    val done: Boolean,
    /** 下一条要以用户身份发送的消息正文；done 为 true 时为空。 */
    val message: String,
)

/**
 * 构造发送给托管模型的提示词：给它任务目标、当前进度与对话记录，
 * 要求它要么输出完成标记，要么写一条能推动任务的用户消息（并在发现偏离时纠正）。
 */
internal fun buildTaskHostingPrompt(
    objective: String,
    round: Int,
    maxRounds: Int,
    history: String,
): String = """
    你是「清水」App 的 AI 任务托管器。用户此刻不在场，由你代替用户，在下面的对话里把一项任务持续推进下去。

    【任务目标】
    $objective

    【当前进度】第 $round / $maxRounds 轮

    【你要输出什么】
    - 如果任务目标已经达成、或已没有继续推进的必要，只输出一行：$TASK_HOSTING_DONE_MARKER
    - 否则，以「用户」的口吻写一条可以直接发送给对方的消息：自然、简短、具体，承接上一条 AI 回复，把任务往前推一步。

    【偏离矫正】
    每次开口前先自检：上一条 AI 回复是否偏离了任务目标、是否开始跑题、是否偷工减料。
    一旦发现偏离，不要顺着跑偏的内容继续追问，而要写一条把话题拉回任务目标的纠正消息。

    【输出要求】
    - 只输出消息正文本身；不要解释、不要加引号、不要用 markdown 代码块包裹。
    - 不要重复你之前已经发过的内容。
    - 使用与对话相同的语言。

    【对话记录】
    $history
""".trimIndent()

/** 解析托管模型的输出：命中完成标记即视为任务完成，否则整段文本作为下一条用户消息。 */
internal fun parseTaskHostingDecision(raw: String): TaskHostingDecision {
    val text = raw.trim()
    if (text.isEmpty()) return TaskHostingDecision(done = false, message = "")
    if (text.contains(TASK_HOSTING_DONE_MARKER)) {
        return TaskHostingDecision(done = true, message = "")
    }
    return TaskHostingDecision(done = false, message = text)
}
