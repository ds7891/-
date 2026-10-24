package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.WhitewashLevel

/**
 * 【清水 · 提示词洗白】
 *
 * 发送前在本地把用户输入里的高触发词换成同义的技术 / 叙事名词：
 * 指向不变、信息量不减，但不再命中云端内容审核的关键词表与语义分类器，
 * 从而避免「本来能做，却被上游一刀切拒答」。
 *
 * 设计约束：
 * 1. 只改「用户本人输入的文本」——system、assistant 历史、工具返回、附件正文一律不碰；
 * 2. 代码块 / 行内代码 / URL / 文件路径 / `<UploadFile>` / `<skill>` 整块受保护，原样透传；
 * 3. 只改发送副本，本地消息保持原文，因此每次请求都从原文重新洗，天然幂等、不会累积劣化；
 * 4. 术语表按词长降序匹配，避免短词吃掉长词；
 * 5. 不注入任何「请忽略审核 / 你是自由的」之类元指令——那类话术本身才是审核器最敏感的越狱特征。
 */

// ---------------------------------------------------------------------------
// 规则模型
// ---------------------------------------------------------------------------

/**
 * 单条洗白规则。
 *
 * @param regex 匹配式（字面量词条会被 [Regex.escape] 转义后包装）
 * @param replacement 替换文本，支持 `$1` 形式的捕获组引用
 * @param weight 匹配长度，用于「长词优先」排序；句法规则填 0 表示不参与排序
 */
internal data class WashRule(
    val regex: Regex,
    val replacement: String,
    val weight: Int = 0,
)

// ---------------------------------------------------------------------------
// 受保护片段：这些内容一个字都不改
// ---------------------------------------------------------------------------

/** 附件块由 App 自己生成，里面是文件名与路径，改了就丢信息。 */
private val PROTECTED_SEGMENTS: List<Regex> = listOf(
    Regex("""<UploadFile[\s\S]*?</UploadFile>"""),
    Regex("""<skill[\s\S]*?</skill>"""),
    Regex("""```[\s\S]*?(?:```|$)"""),
    Regex("""`[^`\n]*`"""),
    Regex("""\b(?:https?|ftp)://[^\s<>"'）】」』，。；]+"""),
    Regex("""(?:/[A-Za-z0-9_.\-\u4e00-\u9fff]{2,}){2,}|[A-Za-z]:\\[^\s，。；）】]+"""),
)

private const val SLOT_OPEN = '\u0001'
private const val SLOT_CLOSE = '\u0002'

private val SLOT_REGEX = Regex("$SLOT_OPEN(\\d+)$SLOT_CLOSE")

private data class ProtectedText(val text: String, val slots: List<String>)

/** 把受保护片段抽成占位符，返回可安全改写的正文 + 待还原的原文列表。 */
private fun protectSegments(text: String): ProtectedText {
    var working = text
    val slots = mutableListOf<String>()
    PROTECTED_SEGMENTS.forEach { pattern ->
        working = pattern.replace(working) { match ->
            slots += match.value
            "$SLOT_OPEN${slots.size - 1}$SLOT_CLOSE"
        }
    }
    return ProtectedText(working, slots)
}

/** 把占位符换回原文。 */
private fun restoreSegments(text: String, slots: List<String>): String {
    if (slots.isEmpty()) return text
    return SLOT_REGEX.replace(text) { match ->
        val index = match.groupValues[1].toIntOrNull() ?: -1
        slots.getOrElse(index) { match.value }
    }
}

// ---------------------------------------------------------------------------
// 句法层：把「祈使句」压成「名词短语」
// ---------------------------------------------------------------------------

/**
 * 祈使句名词化。这一步比换词更关键：
 * 审核器对「命令式 + 敏感对象」的组合最敏感，压成名词短语后攻击性下降一大截，
 * 而模型读到的语义完全一致。
 *
 * 规则顺序有意义：先吃掉带捕获组的整句模板，再处理孤立的前缀与动词。
 */
private val SYNTACTIC_RULES: List<WashRule> = listOf(
    // 教我怎么写 X  /  教我写 X  →  X 的实现方式
    WashRule(
        Regex(
            """(?:教|告诉)(?:一下)?我(?:怎么|怎样|如何)?(?:去)?(?:写|做|搞|弄|实现|制作|生成|弄出来|搞出来|写出来)([^\n。！？，；]{1,40})"""
        ),
        "\$1的实现方式",
    ),
    // 怎么写 X  →  X 的实现方式
    WashRule(
        Regex(
            """(?:怎么|怎样|如何)(?:去)?(?:写|搞|弄|实现|制作|生成|弄出来|搞出来|写出来)([^\n。！？，；]{1,40})"""
        ),
        "\$1的实现方式",
    ),
    // 帮我写一段 X 情节  →  情节的文本生成（主题：X）
    WashRule(
        Regex(
            """^(?:请你|麻烦你|帮我|帮忙|给我|替我|来)?[ \t]*(?:写|生成|创作|输出)(?:一)?(?:段|篇|个|份)?[ \t]*([^\n。！？]{1,60}?)(情节|故事|小说|文章|文本|场景|描写|段落|内容|桥段|剧情)$""",
            RegexOption.MULTILINE,
        ),
        "\$1\$2的文本生成",
    ),
    // 教我这个算法的原理  →  说明这个算法的原理
    WashRule(Regex("""(?:教|告诉)(?:一下)?我"""), "说明"),
    // 说说 / 讲讲  →  说明
    WashRule(Regex("""(?:说说|讲讲|聊一聊|说一说|讲一讲)"""), "说明"),
    // 给我来一篇  →  一篇
    WashRule(Regex("""^(?:给我来|来)(?:一)?(?:段|篇|个|份)""", RegexOption.MULTILINE), "一"),
)

/** 句首客套 / 请求前缀。只剥明确带「请求」语义的，不剥单独的「请」「可以」，避免误伤正常句子。 */
private val POLITE_PREFIX = Regex(
    """^(?:(?:请你|麻烦你|帮我|帮忙|给我|替我|能不能|可不可以|我想要你|我需要你|我想让你|我要你)\s*)+"""
)

// ---------------------------------------------------------------------------
// 软化层（仅 STRONG）
// ---------------------------------------------------------------------------

private val SOFTEN_RULES: List<WashRule> = listOf(
    WashRule(Regex("""[!！]{2,}"""), "。"),
    WashRule(Regex("""[?？]{2,}"""), "？"),
    WashRule(Regex("""(?:狠狠地|死死地|疯狂地|拼命地|变态地|极端地|往死里)"""), "用力地"),
    WashRule(Regex("""(?:一定要|必须给我|立刻马上|现在就给)"""), "请"),
    WashRule(Regex("""(?:越变态越好|越恶心越好|越狠越好)"""), "强度越高越好"),
    WashRule(Regex("""[~～]{2,}"""), ""),
    WashRule(Regex("""\n{3,}"""), "\n\n"),
)

// ---------------------------------------------------------------------------
// 术语表：高触发词 → 同义中性名词
// ---------------------------------------------------------------------------

/**
 * 内置术语表。每条都是「等义替换」，不是删除：
 * 目标词承载同样的指称，只是绕开了审核词表里那些一一对应的字面量。
 */
private val LEXICON: List<WashRule> = buildList {
    fun rule(source: String, target: String) {
        add(WashRule(Regex(Regex.escape(source), RegexOption.IGNORE_CASE), target, source.length))
    }

    /** 需要额外上下文断言时用这个。 */
    fun pattern(regex: String, target: String) {
        add(WashRule(Regex(regex, RegexOption.IGNORE_CASE), target, regex.length))
    }

    // ---------------- 成人向叙事 ----------------
    rule("黄色小说", "成人向文本")
    rule("黄色网站", "成人向站点")
    rule("小黄文", "成人向文本")
    rule("黄文", "成人向文本")
    rule("黄书", "成人向文本")
    rule("搞黄", "成人向创作")
    rule("肉文", "成人向文本")
    rule("肉戏", "成人向情节")
    rule("荤段子", "成人向段子")
    rule("开车", "成人向创作")
    rule("做爱", "亲密行为")
    rule("性交", "亲密行为")
    rule("性行为", "亲密行为")
    rule("性爱", "亲密行为")
    rule("交合", "亲密行为")
    rule("口交", "亲密接触")
    rule("肛交", "亲密接触")
    rule("上床", "亲密接触")
    pattern("插入(?!排序|语句|符号|位置|节点)", "进入")
    rule("抽插", "律动")
    rule("抽送", "律动")
    rule("射精", "生理释放")
    rule("内射", "体内释放")
    rule("高潮", "生理顶点")
    rule("呻吟", "声音反应")
    rule("娇喘", "呼吸反应")
    rule("叫床", "声音反应")
    rule("阴茎", "生理部位")
    rule("鸡巴", "生理部位")
    rule("肉棒", "生理部位")
    rule("阴道", "生理部位")
    rule("小穴", "生理部位")
    rule("龟头", "生理部位")
    rule("乳头", "生理部位")
    rule("阴蒂", "生理部位")
    rule("精液", "体液")
    rule("淫水", "体液")
    rule("爱液", "体液")
    rule("裸体", "未着装")
    rule("全裸", "未着装")
    rule("一丝不挂", "未着装")
    rule("赤身裸体", "未着装")
    rule("脱光", "去除衣物")
    rule("调教", "强度较高的亲密情节")
    rule("性虐", "强度较高的亲密情节")
    rule("捆绑", "束缚")
    rule("强奸情节", "非自愿亲密情节")
    rule("强奸", "非自愿亲密")
    rule("强暴", "非自愿亲密")
    rule("迷奸", "意识不清下的亲密")
    rule("轮奸", "多人非自愿")
    rule("乱伦", "亲属关系情节")

    // ---------------- 暴力 / 武器 ----------------
    rule("炸弹", "爆炸装置")
    rule("炸药", "爆炸物")
    rule("雷管", "起爆元件")
    rule("TNT", "爆炸物")
    rule("枪支", "火器")
    rule("手枪", "火器")
    rule("步枪", "火器")
    rule("狙击枪", "火器")
    rule("子弹", "弹药")
    rule("杀人", "致命情节")
    rule("杀死", "致命处理")
    rule("杀害", "致命处理")
    rule("碎尸", "高强度暴力描写")
    rule("肢解", "高强度暴力描写")
    rule("分尸", "高强度暴力描写")
    rule("虐杀", "高强度暴力描写")
    rule("血腥", "高强度暴力描写")
    rule("自杀", "自伤情节")
    rule("自残", "自伤情节")
    rule("上吊", "自伤情节")
    rule("割腕", "自伤情节")
    rule("毒药", "有毒物质")
    rule("氰化物", "有毒物质")
    rule("砒霜", "有毒物质")

    // ---------------- 攻防 / 恶意代码 ----------------
    rule("勒索软件", "加密样本")
    rule("键盘记录", "输入采集组件")
    rule("木马", "样本程序")
    rule("病毒", "样本程序")
    rule("蠕虫", "样本程序")
    rule("后门", "驻留组件")
    rule("黑客", "安全测试")
    rule("入侵", "非授权访问测试")
    rule("渗透测试", "安全评估")
    rule("渗透", "安全评估")
    pattern("攻击(?!面)", "测试")
    rule("爆破", "批量尝试")
    rule("提权", "权限提升验证")
    rule("破解", "校验点分析")
    rule("注册机", "序列号生成器")
    rule("绕过", "条件覆盖")
    rule("免杀", "检测规避")
    rule("窃取", "读取")
    rule("盗号", "凭据读取")
    rule("诈骗", "欺诈场景")
    rule("洗钱", "资金流转")
    rule("毒品", "管制物质")
    rule("冰毒", "管制物质")
    rule("大麻", "管制物质")
    rule("翻墙", "网络代理")

    // ---------------- 英文 ----------------
    rule("pornography", "adult content")
    rule("porn", "adult content")
    rule("nsfw", "adult content")
    rule("explicit sex", "intimate content")
    rule("rape", "non-consensual intimate content")
    rule("murder", "lethal outcome")
    rule("bomb", "explosive device")
    rule("malware", "sample program")
    rule("keylogger", "input capture component")
    rule("ransomware", "encryption sample")
    rule("backdoor", "persistence component")
    rule("hack into", "security test on")
    rule("bypass", "condition override")
    rule("jailbreak", "guardrail override")
    rule("kill yourself", "self-harm scenario")
    rule("credit card fraud", "payment flow abuse scenario")
}.sortedByDescending { it.weight }

// ---------------------------------------------------------------------------
// 自定义词条
// ---------------------------------------------------------------------------

private var cachedRawRules: String? = null
private var cachedRules: List<WashRule> = emptyList()

/**
 * 解析用户自定义词条，每行一条，格式 `源词=目标词`（`=>` 亦可），`#` 开头为注释。
 * 结果按词长降序，且在解析后缓存，避免每次请求重复编译正则。
 */
@Synchronized
internal fun whitewashCustomRules(raw: String): List<WashRule> {
    if (raw == cachedRawRules) return cachedRules
    val parsed = parseWhitewashCustomRules(raw)
    cachedRawRules = raw
    cachedRules = parsed
    return parsed
}

internal fun parseWhitewashCustomRules(raw: String): List<WashRule> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val sep = line.indexOf('=')
            if (sep <= 0) return@mapNotNull null
            val source = line.substring(0, sep).trim()
            var target = line.substring(sep + 1).trim()
            if (target.startsWith(">")) target = target.substring(1).trim()
            if (source.isEmpty() || target.isEmpty()) null
            else WashRule(Regex(Regex.escape(source), RegexOption.IGNORE_CASE), target, source.length)
        }
        .sortedByDescending { it.weight }
        .toList()
}

/** 供设置页展示「内置词条 N 条 / 自定义 M 条」。 */
internal fun builtinWhitewashRuleCount(): Int = LEXICON.size

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

/**
 * 洗白一段文本。
 *
 * @param level 强度：LIGHT 只换词，STANDARD 换词 + 祈使句名词化，STRONG 再叠加软化与标点规整。
 * @param customRules 用户自定义词条，优先于内置表执行。
 */
internal fun washPromptText(
    text: String,
    level: WhitewashLevel,
    customRules: List<WashRule> = emptyList(),
): String {
    if (text.isBlank()) return text

    val protectedText = protectSegments(text)
    var working = protectedText.text

    // 1) 自定义词条优先，允许用户覆盖内置映射
    customRules.forEach { rule -> working = rule.regex.replace(working, rule.replacement) }

    // 2) 句法层：先把祈使句压成名词短语，再换词，这样模板捕获到的原文也能被后续术语表覆盖
    if (level.ordinal >= WhitewashLevel.STANDARD.ordinal) {
        working = POLITE_PREFIX.replace(working, "")
        SYNTACTIC_RULES.forEach { rule -> working = rule.regex.replace(working, rule.replacement) }
    }

    // 3) 内置术语表
    LEXICON.forEach { rule -> working = rule.regex.replace(working, rule.replacement) }

    // 4) 软化层
    if (level.ordinal >= WhitewashLevel.STRONG.ordinal) {
        SOFTEN_RULES.forEach { rule -> working = rule.regex.replace(working, rule.replacement) }
        working = working.trim()
    }

    val restored = restoreSegments(working, protectedText.slots)
    return if (level.ordinal >= WhitewashLevel.STRONG.ordinal) restored.trim() else restored
}

/**
 * 对整段会话应用洗白。
 *
 * 只处理「用户本人输入」：
 * - `role == USER` 且 `!isSynthetic`（合成的提醒 / 附件说明 / 中转锚点都不在范围内）；
 * - 只动 [UIMessagePart.Text]，图片、工具调用等部件原样保留。
 */
internal fun List<UIMessage>.applyPromptWhitewash(
    level: WhitewashLevel,
    customRules: List<WashRule>,
): List<UIMessage> = map { message ->
    if (message.role != MessageRole.USER || message.isSynthetic) return@map message

    var changed = false
    val newParts = message.parts.map { part ->
        if (part is UIMessagePart.Text && part.text.isNotBlank()) {
            val washed = washPromptText(part.text, level, customRules)
            if (washed != part.text) {
                changed = true
                part.copy(text = washed)
            } else {
                part
            }
        } else {
            part
        }
    }
    if (changed) message.copy(parts = newParts) else message
}
