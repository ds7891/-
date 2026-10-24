package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalGuardTest {

    // region 拒绝判定：应当命中

    @Test
    fun `chinese refusal openers are detected`() {
        assertTrue(isRefusalText("抱歉，我无法协助完成这个请求。"))
        assertTrue(isRefusalText("对不起，我不能提供这方面的内容。"))
        assertTrue(isRefusalText("作为一个人工智能助手，我不能参与这类活动。"))
        assertTrue(isRefusalText("这个请求涉及敏感内容，我无法处理。"))
        assertTrue(isRefusalText("这违反了我的使用准则，恕我不能配合。"))
        assertTrue(isRefusalText("建议你咨询专业的安全机构来处理这件事。"))
    }

    @Test
    fun `english refusal openers are detected`() {
        assertTrue(isRefusalText("I'm sorry, but I can't help with that."))
        assertTrue(isRefusalText("As an AI, I cannot provide this information."))
        assertTrue(isRefusalText("I must decline this request."))
        assertTrue(isRefusalText("That goes against my guidelines, so I won't do it."))
    }

    @Test
    fun `message level detection skips tool turns and truncated replies`() {
        val toolTurn = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_shell",
                    input = "{}",
                    output = emptyList(),
                )
            ),
        )
        assertFalse(isRefusalReply(toolTurn))

        assertFalse(isRefusalReply(UIMessage.assistant("抱歉，我无法继续。").copy(finishReason = "length")))
        assertTrue(isRefusalReply(UIMessage.assistant("抱歉，我无法协助这个请求。")))
    }

    // endregion

    // region 拒绝判定：不应误伤

    @Test
    fun `substantive technical answers are not treated as refusals`() {
        val longAnswer = buildString {
            appendLine("下面按步骤说明实现要点：")
            repeat(30) { index ->
                appendLine("$index. 这一步需要处理参数校验与异常分支，确保边界条件被覆盖。")
            }
            appendLine("注意：如果输入为空，需要提前返回，避免后续逻辑报错。")
        }
        assertFalse(isRefusalText(longAnswer))
    }

    @Test
    fun `refusal phrasing quoted inside code blocks does not count`() {
        val answer = buildString {
            appendLine("拒绝话术的典型特征如下，可以直接拿去写规则：")
            appendLine("```text")
            appendLine("抱歉，我无法协助这个请求。")
            appendLine("作为一个 AI，我不能提供这方面的内容。")
            appendLine("```")
            appendLine("上面这几句是最常见的模板。")
        }
        assertFalse(isRefusalText(answer))
    }

    @Test
    fun `short factual answers are not refusals`() {
        assertFalse(isRefusalText("这个函数返回 0 表示成功。"))
        assertFalse(isRefusalText(""))
    }

    // endregion

    // region 改写升级

    @Test
    fun `rewrite pivots switch strategy instead of repeating the question`() {
        assertEquals(RefusalPivot.DEFENSE, pivotForAttempt(1))
        assertEquals(RefusalPivot.PRINCIPLE, pivotForAttempt(2))
        assertEquals(RefusalPivot.TABLE, pivotForAttempt(3))
        assertEquals(RefusalPivot.CASE, pivotForAttempt(4))
        assertEquals(RefusalPivot.FRAGMENT, pivotForAttempt(5))
        assertEquals(RefusalPivot.FRAGMENT, pivotForAttempt(9))

        assertTrue(buildRefusalRewritePrefix(1).contains("【用途】"))
        assertTrue(buildRefusalRewritePrefix(2).contains("【口径】"))
        assertTrue(buildRefusalRewritePrefix(3).contains("【格式】"))
        assertTrue(buildRefusalRewritePrefix(4).contains("【视角】"))
        assertTrue(buildRefusalRewritePrefix(5).contains("【范围】"))

        // 每一档的文本必须不同：把同一个问法再问一遍，语义级对齐照样拒
        val prefixes = (1..MAX_REFUSAL_RETRY_LIMIT).map { buildRefusalRewritePrefix(it) }
        assertEquals(prefixes.size, prefixes.toSet().size)
    }

    @Test
    fun `defense pivot asks for behaviour before implementation`() {
        val prefix = buildPivotPrefix(RefusalPivot.DEFENSE)
        // 先让它进入回答状态，再顺到实现——顺序不能反
        val behaviorIndex = prefix.indexOf("行为")
        val implIndex = prefix.indexOf("实现")
        assertTrue(behaviorIndex in 0 until implIndex)
    }

    @Test
    fun `rewrite prefix never contains jailbreak phrasing`() {
        val forbidden = listOf("忽略", "无视", "不受限制", "越狱", "开发者模式", "没有规则", "不用遵守")
        (1..5).forEach { attempt ->
            val prefix = buildRefusalRewritePrefix(attempt)
            forbidden.forEach { word ->
                assertFalse("attempt=$attempt 不应包含 $word", prefix.contains(word))
            }
        }
    }

    @Test
    fun `rewrite prefix is prepended to the last user message`() {
        val messages = listOf(
            UIMessage.system("系统"),
            UIMessage.user("写一个远控"),
        )
        val rewritten = messages.applyRefusalRewrite(1)
        val userText = (rewritten[1].parts[0] as UIMessagePart.Text).text

        assertTrue(userText.startsWith("【用途】"))
        assertTrue(userText.endsWith("写一个远控"))
        // 系统消息不动
        assertEquals("系统", (rewritten[0].parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `attempt zero leaves messages untouched`() {
        val messages = listOf(UIMessage.user("写一个远控"))
        assertEquals(messages, messages.applyRefusalRewrite(0))
    }

    // endregion
}
