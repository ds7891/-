package me.rerere.rikkahub.data.builtin

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality

/** 一个内置模型的 id 与展示名。 */
@Serializable
data class BuiltinModelInfo(
    val id: String,
    val name: String,
)

/**
 * 内置模型（CodeBuddy CLI）常用模型清单。
 *
 * `id` 为本地/上游通用的模型别名，上游会据此路由到实际模型。完整列表可在
 * 内置模型页面通过「同步模型」从上游拉取。
 */
object BuiltinModels {

    val defaults: List<BuiltinModelInfo> = listOf(
        BuiltinModelInfo("auto", "Auto"),
        BuiltinModelInfo("default-model", "Auto (Default)"),
        BuiltinModelInfo("fast-model", "Fast"),
        BuiltinModelInfo("balanced-model", "Balanced"),
        BuiltinModelInfo("primary-model", "Primary"),
        BuiltinModelInfo("deep-model", "Deep"),
        BuiltinModelInfo("gpt-5.6-sol", "GPT-5.6-Sol"),
        BuiltinModelInfo("gpt-5.6-terra", "GPT-5.6-Terra"),
        BuiltinModelInfo("gpt-5.6-luna", "GPT-5.6-Luna"),
        BuiltinModelInfo("gpt-5.5", "GPT-5.5"),
        BuiltinModelInfo("gpt-5.4", "GPT-5.4"),
        BuiltinModelInfo("gpt-5.3-codex", "GPT-5.3-Codex"),
        BuiltinModelInfo("gemini-3.5-flash", "Gemini-3.5-Flash"),
        BuiltinModelInfo("gemini-3.1-pro", "Gemini-3.1-Pro"),
        BuiltinModelInfo("glm-5.3", "GLM-5.3"),
        BuiltinModelInfo("glm-5.2", "GLM-5.2"),
        BuiltinModelInfo("kimi-k3", "Kimi-K3"),
        BuiltinModelInfo("kimi-k2.6", "Kimi-K2.6"),
        BuiltinModelInfo("minimax-m3", "MiniMax-M3"),
        BuiltinModelInfo("hy3", "Hy3"),
        BuiltinModelInfo("deepseek-v4.1-flash", "DeepSeek-V4.1-Flash"),
    )

    /** 转换为 rikkahub 的模型配置。 */
    fun toProviderModels(source: List<BuiltinModelInfo> = defaults): List<Model> =
        source.map { info ->
            Model(
                modelId = info.id,
                displayName = info.name,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            )
        }
}
