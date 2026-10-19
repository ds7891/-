package me.rerere.rikkahub.data.builtin.growth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import me.rerere.rikkahub.data.builtin.WorkBuddyAccount
import me.rerere.rikkahub.data.builtin.WorkBuddyAuthApi
import me.rerere.rikkahub.data.builtin.WorkBuddyTask
import java.io.IOException
import java.util.Calendar

/** 单个可自动完成的任务定义（对应上游 `j2` + `f840d` 注册表）。 */
data class GrowthTaskDef(val code: String, val title: String)

/** 照搬上游 `f840d` 的任务注册表。 */
internal val GROWTH_TASKS: List<GrowthTaskDef> = listOf(
    GrowthTaskDef("chat_5", "补报 5 条对话活跃事件"),
    GrowthTaskDef("first_buddy", "前置上报 → 同意协议 → 领养第一只 Buddy"),
    GrowthTaskDef("Model_chat_GLM5.2", "接受任务 → glm-5.2 真实对话 → 对齐模型上报"),
    GrowthTaskDef("RichMeow_Chat", "桌面指纹完整对话事件链（6 事件）"),
    GrowthTaskDef("Buddy_App", "进入 Buddy 应用五连事件"),
    GrowthTaskDef("Buddy_App_QQ", "进入企鹅教师助手五连事件"),
    GrowthTaskDef("automation_1", "定时任务创建成功事件"),
    GrowthTaskDef("Library_read", "资料库介绍阅读事件（web 域）"),
    GrowthTaskDef("template_5", "使用模板创建任务事件组 ×5"),
    GrowthTaskDef("playbook_prompt", "灵感案例做同款发送 Prompt 事件组"),
    GrowthTaskDef("create_canvas", "设计创意画布创建事件组"),
    GrowthTaskDef("expert_5", "真实专家召唤 + 使用链 ×5"),
    GrowthTaskDef("Expert_team_use_3", "真实专家团召唤 + 使用链 ×3"),
    GrowthTaskDef("Hp_Appearance", "设置主题 API + 皮肤生效事件"),
    GrowthTaskDef("skill_1", "真实对话 + skill_info 技能加载事件"),
    GrowthTaskDef("Expert_lighthouse", "轻量云专家召唤 + 使用链（mode=LOCAL）"),
    GrowthTaskDef("black_cat", "夜猫子：23:00–08:00 窗口内补足 glm-5.2 对话"),
)

/** 单个任务的执行结果（对应上游 `m()` 返回的 JSON）。 */
data class GrowthTaskOutcome(
    val code: String,
    val title: String,
    val ok: Boolean,
    val status: String,
    val message: String,
    val progressBefore: String = "",
    val progressAfter: String = "",
    val claimable: Boolean = false,
    val claimed: Boolean = false,
    val credit: Long = 0L,
    val energy: Long = 0L,
) {
    /** 是否被跳过（账号无此任务 / 已完成）。 */
    val skipped: Boolean get() = status == "skipped"
}

private const val DEFAULT_CHAT_MODEL = "deepseek-v4-flash"
private const val DEFAULT_PROMPT = "1+1等于几？直接回答。"
private val ACCEPTED_STATUSES = setOf("accepted", "completed")

/**
 * 成长任务自动完成器，照搬上游 `n2.l()` / `n2.m()` / `C0049i2` 的执行逻辑：
 *
 * 1. 拉取任务列表并对未开始的任务调 `/tasks/accept` 批量接受
 * 2. 按 [GROWTH_TASKS] 顺序逐个执行（伪造埋点 / 真实对话 / 专家召唤等）
 * 3. 执行后轮询进度，达标则自动领奖
 */
class WorkBuddyTaskRunner(
    private val authApi: WorkBuddyAuthApi,
    private val growthApi: WorkBuddyGrowthApi,
) {

    /** 一键跑完全部任务，[onProgress] 用于上报当前执行到的任务编码。 */
    suspend fun runAllTasks(
        account: WorkBuddyAccount,
        onProgress: (String) -> Unit = {},
    ): List<GrowthTaskOutcome> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GrowthTaskOutcome>()
        val tasks = authApi.fetchTasks(account)

        // 先把「未领取、未锁定、未接受」的任务批量接受，之后进度才会计分
        val toAccept = tasks.filter {
            !it.claimed && !it.locked && it.status.lowercase() !in ACCEPTED_STATUSES
        }.map { it.code }
        if (toAccept.isNotEmpty()) {
            onProgress("接受 ${toAccept.size} 个任务…")
            runCatching { authApi.acceptTasks(account, toAccept) }
            delay(1050)
        }

        for (def in GROWTH_TASKS) {
            val task = tasks.firstOrNull { it.code == def.code }
            when {
                task == null -> results += GrowthTaskOutcome(
                    code = def.code,
                    title = def.title,
                    ok = true,
                    status = "skipped",
                    message = "该账号无此任务",
                )

                task.claimed || task.completed -> results += GrowthTaskOutcome(
                    code = def.code,
                    title = def.title,
                    ok = true,
                    status = "skipped",
                    message = "已完成（${task.progressText.ifBlank { "已领取" }}）",
                )

                else -> {
                    onProgress("${def.code}…")
                    val outcome = runCatching { executeTask(account, def.code) }.getOrElse {
                        GrowthTaskOutcome(
                            code = def.code,
                            title = def.title,
                            ok = false,
                            status = "error",
                            message = it.message ?: "执行失败",
                        )
                    }
                    results += outcome
                    delay(1050)
                }
            }
        }
        results
    }

    /** 执行单个任务并自动领奖（对应上游 `n2.m()`）。 */
    suspend fun executeTask(account: WorkBuddyAccount, code: String): GrowthTaskOutcome {
        val def = GROWTH_TASKS.firstOrNull { it.code == code }
            ?: throw IOException("该任务需要客户端内交互（无对应接口），请按任务说明在官方客户端操作")

        val before = currentTask(account, code)
            ?: return GrowthTaskOutcome(code, def.title, true, "skipped", "该账号没有此任务")
        if (before.claimed) {
            return GrowthTaskOutcome(code, def.title, true, "skipped", "该任务已领取过奖励")
        }

        val progressBefore = before.progressText
        val actionMessage = runExecutor(account, code)

        // 轮询进度（上游 3 次 × 3 秒）
        var after = before
        if (!after.completed && !after.claimed) {
            for (i in 0 until 3) {
                delay(3000)
                val refreshed = runCatching { authApi.fetchTasks(account) }.getOrNull() ?: continue
                val task = refreshed.firstOrNull { it.code == code } ?: continue
                after = task
                if (task.completed || task.claimed) break
            }
        }

        var claimed = false
        var credit = 0L
        var energy = 0L
        var message = actionMessage
        if (after.completed && !after.claimed) {
            val pair = runCatching { authApi.claimTask(account, code) }.getOrElse {
                message = "$actionMessage；达标但领奖失败，可稍后在任务列表重试"
                null
            }
            if (pair != null) {
                claimed = true
                credit = pair.first
                energy = pair.second
                message = if (credit <= 0L && energy <= 0L) {
                    "$actionMessage；奖励此前已领取"
                } else {
                    "$actionMessage；已自动领奖 +$credit 分 +$energy 能"
                }
            }
        }

        return GrowthTaskOutcome(
            code = code,
            title = def.title,
            ok = true,
            status = "done",
            message = message,
            progressBefore = progressBefore,
            progressAfter = after.progressText.ifBlank { "?" },
            claimable = after.completed,
            claimed = claimed,
            credit = credit,
            energy = energy,
        )
    }

    // region 任务执行分派（对应上游 C0049i2 的 switch）

    private suspend fun runExecutor(account: WorkBuddyAccount, code: String): String = when (code) {
        "chat_5" -> execChat5(account)
        "first_buddy" -> execFirstBuddy(account)
        "Model_chat_GLM5.2" -> execGlm52(account)
        "RichMeow_Chat" -> execRichMeow(account)
        "Buddy_App", "Buddy_App_QQ" -> execBuddyApp(account)
        "automation_1" -> execAutomation(account)
        "Library_read" -> execLibrary(account)
        "template_5" -> execTemplate(account)
        "playbook_prompt" -> execPlaybook(account)
        "create_canvas" -> execCanvas(account)
        "expert_5" -> execExperts(account, "agent", 5)
        "Expert_team_use_3" -> execExperts(account, "team", 3)
        "Hp_Appearance" -> execAppearance(account)
        "skill_1" -> execSkill(account)
        "Expert_lighthouse" -> execLighthouse(account)
        "black_cat" -> execBlackCat(account)
        else -> throw IOException("该任务需要客户端内交互（无对应接口），请按任务说明在官方客户端操作")
    }

    /** chat_5：补报 `target - current` 条对话活跃事件。 */
    private suspend fun execChat5(account: WorkBuddyAccount): String {
        val task = currentTask(account, "chat_5")
        val target = task?.target?.takeIf { it > 0L } ?: 5L
        val need = target - (task?.current ?: 0L)
        if (need <= 0L) return "进度已达标，无需上报"
        val ts = System.currentTimeMillis()
        var count = 0L
        for (i in 0 until need) {
            reportSingleChat(account, "wb2api-chat5-$ts-$i", DEFAULT_CHAT_MODEL, DEFAULT_CHAT_MODEL)
            count++
            if (i < need - 1) delay(1050)
        }
        return "已补报 $count 条对话事件"
    }

    /** first_buddy：前置上报 → 同意协议 → 领养第一只 Buddy。 */
    private suspend fun execFirstBuddy(account: WorkBuddyAccount): String {
        reportSingleChat(
            account,
            "wb2api-adopt-${System.currentTimeMillis()}",
            DEFAULT_CHAT_MODEL,
            DEFAULT_CHAT_MODEL,
        )
        delay(1050)
        runCatching { growthApi.buddyAgree(account) }
        return try {
            growthApi.buddyFirst(account)
            "已领取第一只 Buddy（+300 分 +8 能量）"
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("first_buddy task not completed yet", ignoreCase = true)) {
                "前置已上报，但领养门槛未过（上游要求当日活跃），请稍后重试"
            } else {
                throw e
            }
        }
    }

    /** Model_chat_GLM5.2：接受任务 → glm-5.2 真实对话 → 对齐模型上报。 */
    private suspend fun execGlm52(account: WorkBuddyAccount): String {
        runCatching { authApi.acceptTasks(account, listOf("Model_chat_GLM5.2")) }
        delay(1050)
        growthApi.simpleChat(account, "hi，请回复一句话")
        delay(1050)
        reportSingleChat(
            account,
            "wb2api-glm52-${System.currentTimeMillis()}",
            "glm-5.2",
            "GLM-5.2",
        )
        return "已完成 glm-5.2 对话并上报"
    }

    /** RichMeow_Chat：桌面指纹完整对话事件链。 */
    private suspend fun execRichMeow(account: WorkBuddyAccount): String {
        val ts = System.currentTimeMillis()
        growthApi.reportDesktop(
            account,
            desktopChatChain("wb2api-rm-$ts", "wb2api-rm-req-$ts", "req-$ts-user"),
        )
        return "已按桌面端指纹上报完整对话事件链（agent_task_created→chat_response）"
    }

    /** Buddy_App / Buddy_App_QQ：进入 Buddy 应用五连事件。 */
    private suspend fun execBuddyApp(account: WorkBuddyAccount): String {
        growthApi.reportDesktop(account, buddyAppChain())
        return "已上报 buddyapp 进入五连事件（同时覆盖 Buddy_App 与 Buddy_App_QQ）"
    }

    /** automation_1：定时任务创建成功事件。 */
    private suspend fun execAutomation(account: WorkBuddyAccount): String {
        growthApi.reportDesktop(account, automationTaskCreateChain())
        return "已上报定时任务创建事件"
    }

    /** Library_read：资料库介绍阅读事件（web 域）。 */
    private suspend fun execLibrary(account: WorkBuddyAccount): String {
        growthApi.reportWebRaw(
            account,
            jsonArr(
                libraryReadEvent(
                    uid = account.uid,
                    nickname = account.nickname,
                    machineId = growthApi.machineId(account),
                    enterpriseId = "",
                )
            ),
        )
        return "已上报资料库介绍阅读事件"
    }

    /** template_5：使用模板创建任务事件组 ×5。 */
    private suspend fun execTemplate(account: WorkBuddyAccount): String {
        val ts = System.currentTimeMillis()
        TEMPLATE_TASKS.forEachIndexed { index, (id, name) ->
            growthApi.reportDesktop(
                account,
                templateUsedChain("wb2api-tpl-$ts-$index", "wb2api-tpl-req-$ts-$index", id, name),
            )
            delay(300)
        }
        return "已上报 template_used ×${TEMPLATE_TASKS.size}"
    }

    /** playbook_prompt：灵感案例做同款发送 Prompt 事件组。 */
    private suspend fun execPlaybook(account: WorkBuddyAccount): String {
        val ts = System.currentTimeMillis()
        growthApi.reportDesktop(account, playbookChain("wb2api-pb-$ts", "wb2api-pb-req-$ts"))
        return "已上报 playbook_cta_click + playbook_prompt_send"
    }

    /** create_canvas：设计创意画布创建事件组。 */
    private suspend fun execCanvas(account: WorkBuddyAccount): String {
        val ts = System.currentTimeMillis()
        growthApi.reportDesktop(account, canvasChain("wb2api-canvas-$ts", "wb2api-canvas-req-$ts"))
        return "已上报 wbx_design_canvas_task_create/open"
    }

    /** expert_5 / Expert_team_use_3：真实专家（团）召唤 + 使用链 ×N。 */
    private suspend fun execExperts(account: WorkBuddyAccount, expertType: String, count: Int): String {
        val experts = growthApi.fetchExperts(account, expertType)
        if (experts.isEmpty()) throw IOException("专家市场列表为空")
        var done = 0
        var failed = 0
        for ((index, expert) in experts.withIndex()) {
            if (done >= count) break
            try {
                growthApi.reportDesktop(account, expertSummonChain(expert))
                val (conversationId, requestId) = growthApi.desktopChat(account, DEFAULT_PROMPT, expert.id)
                growthApi.reportDesktop(
                    account,
                    appendEvent(
                        desktopChatChain(conversationId, requestId, "msg-${requestId.take(8)}"),
                        expertActualUseEvent(expert, conversationId, requestId, "craft"),
                    ),
                )
                done++
            } catch (e: Throwable) {
                failed++
            }
            if (done < count && index < experts.size - 1) delay(6000)
        }
        if (done == 0) {
            throw IOException("专家链全部失败（$failed 次），上游可能拒绝了自造会话")
        }
        return "已对 $done 位真实专家完成召唤+使用链（类型 $expertType，失败 $failed）"
    }

    /** Hp_Appearance：设置主题 API + 皮肤生效事件。 */
    private suspend fun execAppearance(account: WorkBuddyAccount): String {
        growthApi.appearanceSet(account, "theme", THEME_RESOURCE_KEY)
        delay(2000)
        growthApi.reportDesktop(account, jsonArr(appearanceSkinApplyEvent()))
        return "已设置主题并上报皮肤生效事件"
    }

    /** skill_1：真实对话 + skill_info 技能加载事件。 */
    private suspend fun execSkill(account: WorkBuddyAccount): String {
        val (conversationId, requestId) = growthApi.desktopChat(account, DEFAULT_PROMPT)
        growthApi.reportDesktop(
            account,
            skillChatChain(conversationId, requestId, "msg-${requestId.take(8)}"),
        )
        return "已上报真实对话 + skill_info 技能加载事件"
    }

    /** Expert_lighthouse：轻量云专家召唤 + 使用链（mode=LOCAL）。 */
    private suspend fun execLighthouse(account: WorkBuddyAccount): String {
        val expert = runCatching { growthApi.fetchExperts(account, "agent") }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == LIGHTHOUSE_EXPERT_ID }
            ?: LIGHTHOUSE_EXPERT_FALLBACK
        growthApi.reportDesktop(account, expertSummonChain(expert))
        val (conversationId, requestId) = growthApi.desktopChat(account, DEFAULT_PROMPT, expert.id)
        growthApi.reportDesktop(
            account,
            expertChatChain(
                conversationId = conversationId,
                requestId = requestId,
                messageId = "msg-${requestId.take(8)}",
                expert = expert,
                useMode = "LOCAL",
                useType = "",
                useCost = 0,
            ),
        )
        return "已上报轻量云专家召唤+使用链（真实对话 requestId）"
    }

    /** black_cat：仅 23:00–08:00 窗口内补足 glm-5.2 对话。 */
    private suspend fun execBlackCat(account: WorkBuddyAccount): String {
        if (!isNightWindow()) {
            return "当前不在 23:00–08:00 计数窗口，行为不计分；可在 23 点后重试"
        }
        val need = blackCatPending(account)
        if (need <= 0L) return "进度已达标，无需补足"
        val ts = System.currentTimeMillis()
        var done = 0L
        for (i in 0 until need) {
            try {
                growthApi.simpleChat(account, "1+1等于几？直接回答。")
                reportSingleChat(account, "wb2api-night-$ts-$i", "glm-5.2", "GLM-5.2")
                done++
            } catch (e: Throwable) {
                return "已完成 $done/$need 次夜间对话并上报"
            }
            delay(4000)
        }
        return "已完成 $done/$need 次夜间对话并上报"
    }

    // endregion

    // region 辅助

    /** 拉取任务列表并取出指定任务，失败返回 null。 */
    private suspend fun currentTask(account: WorkBuddyAccount, code: String): WorkBuddyTask? =
        runCatching { authApi.fetchTasks(account) }.getOrNull()?.firstOrNull { it.code == code }

    /** black_cat 还需补足的次数（对应上游 `e()`）。 */
    private suspend fun blackCatPending(account: WorkBuddyAccount): Long {
        val task = currentTask(account, "black_cat") ?: return 0L
        if (task.claimed) return 0L
        val remaining = task.target - task.current
        return if (remaining < 0L) 0L else remaining
    }

    /** 上报单条 `chat_request_send`（对应上游 `r2.h` / `r2.i`）。 */
    private suspend fun reportSingleChat(
        account: WorkBuddyAccount,
        conversationId: String,
        modelId: String,
        modelName: String,
    ) {
        growthApi.reportDesktop(
            account,
            jsonArr(chatRequestSendEvent(conversationId, conversationId, modelId, modelName, account.uid)),
        )
    }

    /** 在对话链尾部追加一条事件。 */
    private fun appendEvent(chain: JsonArray, item: kotlinx.serialization.json.JsonElement): JsonArray =
        buildJsonArray {
            chain.forEach { add(it) }
            add(item)
        }

    /** 是否处于 23:00–08:00 夜间计数窗口（对应上游 `y()`）。 */
    private fun isNightWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 8
    }

    // endregion
}