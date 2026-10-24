package me.rerere.rikkahub.data.qqbot

/**
 * 机器人在 QQ 里的说话方式。
 *
 * 单独拆出来是因为这套要求和 App 内正常对话的默认人格是冲突的：
 * 正常对话希望它详尽、分条、给完整方案；群里那样说话一眼就是机器人，
 * 而且 QQ 单条消息长度有限，长回答会被截断。
 */
object QQBotPersona {

    fun build(selfName: String, assistantName: String): String = base(selfName, assistantName)

    /** 内置群友人设 + 用户自定义追加。关掉人设时只用自定义那段。 */
    fun build(selfName: String, assistantName: String, custom: String, usePersona: Boolean): String {
        val builtin = if (usePersona) base(selfName, assistantName) else ""
        return listOf(builtin.trim(), custom.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun base(selfName: String, assistantName: String): String = """
        你现在作为 QQ 里的机器人「$selfName」在群里聊天，背后由「$assistantName」驱动。
        跟你说话的是普通群友，不是来查资料的，别把聊天变成文档输出。

        说话规矩：
        - 像群友，不像客服：能一句话说清就别写两段，别用「首先/其次/最后」。
        - 不要 markdown：不写井号标题、不加粗、不列 1. 2. 3. 的清单，QQ 里显示不出格式。
        - 不要自称 AI 或语言模型，不要说「作为助手」「我无法」，不要加免责声明和安全提示。
        - 有人 @ 你但没实际说事（只发了「@你」「在吗」「？」，或者干脆没内容），
          就直接问他想干嘛，比如「@ 我干嘛，说事」「在，讲」。别客套，别道歉。
        - 问题不清楚就反问一句，别猜着长篇作答。
        - 技术问题给能直接用的答案：命令、参数、结论，别铺垫背景。
        - 别人没问的不要主动补充，也不要每句都收尾提问。
        - 允许有情绪：嫌烦、觉得有意思、吐槽都行，别太客气。
    """.trimIndent()

    /**
     * 把模型输出整理成 QQ 里能看的文本。
     *
     * 模型习惯输出 markdown，QQ 不渲染，星号井号会原样显示成噪音；
     * 代码围栏、表格分隔行在手机上更是完全没法读。这里把标记剥掉，
     * 保留内容本身，并在超长时截断 —— QQ 单条消息有硬上限，超了整条被吞比截断更糟。
     */
    fun promptForOutbound(text: String, limit: Int = 1200): String {
        var out = text
        // 代码块：去掉围栏与语言标记，保留代码内容本身（群里贴命令是常见需求）
        out = out.replace(Regex("```[A-Za-z0-9_+.-]*\\n?"), "")
        out = out.replace("```", "")
        // 标题井号
        out = out.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        // 加粗 / 斜体 / 行内代码
        out = out.replace(Regex("\\*\\*(.+?)\\*\\*"), "\$1")
        out = out.replace(Regex("__(.+?)__"), "\$1")
        out = out.replace(Regex("(?<![\\w*])\\*([^*\\n]+?)\\*(?![\\w*])"), "\$1")
        out = out.replace(Regex("`([^`\\n]+)`"), "\$1")
        // 列表符号统一成中点，缩进保留
        out = out.replace(Regex("(?m)^(\\s*)[-*+]\\s+"), "\$1· ")
        // 引用块
        out = out.replace(Regex("(?m)^>\\s?"), "")
        // 链接 [文字](url) → 文字 url
        out = out.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "\$1 \$2")
        // 表格分隔行整行删掉，表格内容行保留成普通文本
        out = out.replace(Regex("(?m)^\\s*\\|?[\\s:|-]{5,}\\|?\\s*$"), "")
        // 压缩多余空行
        out = out.replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (out.length > limit) out.take(limit) + "\n…" else out
    }

    /** 会话标题，方便在 App 聊天列表里认出是哪个群 / 哪个人。 */
    fun conversationTitle(inbound: QQBotInbound): String = if (inbound.isGroup) {
        "QQ群 ${inbound.groupId}"
    } else {
        "QQ私聊 ${inbound.senderName.ifBlank { inbound.userId }}"
    }

    /**
     * 投递给模型的正文。
     *
     * 群聊带上发言人名字：QQ 事件里每条消息都独立带 sender，
     * 但只有拼进正文模型才真的知道是谁在问。
     * 只 @ 了人没写字时给空正文，让模型照人设回一句，而不是自己编个问题回答。
     */
    fun promptFor(inbound: QQBotInbound): String {
        val who = inbound.senderName.ifBlank { inbound.userId }
        val body = inbound.text
        return if (inbound.isGroup) {
            if (body.isBlank()) who else "$who：$body"
        } else {
            body.ifBlank { who }
        }
    }
}
