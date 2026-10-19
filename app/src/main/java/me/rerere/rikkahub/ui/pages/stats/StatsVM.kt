package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.ModelTokenUsage
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getTokenStatsByModel
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val providerTokenStats: List<ProviderTokenStat> = emptyList(),
)

/** 单个模型在某供应商下的 Token 消耗统计。 */
data class ModelTokenStat(
    val modelId: String,
    val modelName: String,
    val providerId: String,
    val providerName: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val messageCount: Int,
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    /** 缓存命中率：缓存命中的 Token 占输入 Token 的比例（供应商上报的输入 Token 已包含缓存部分）。 */
    val cacheHitRate: Float get() = if (promptTokens > 0) cachedTokens.toFloat() / promptTokens else 0f
}

/** 按供应商分组的 Token 消耗统计。providerName 为空表示未在设置中找到对应供应商。 */
data class ProviderTokenStat(
    val providerId: String,
    val providerName: String,
    val models: List<ModelTokenStat>,
) {
    val promptTokens: Long get() = models.sumOf { it.promptTokens }
    val completionTokens: Long get() = models.sumOf { it.completionTokens }
    val cachedTokens: Long get() = models.sumOf { it.cachedTokens }
    val totalTokens: Long get() = promptTokens + completionTokens
    val cacheHitRate: Float get() = if (promptTokens > 0) cachedTokens.toFloat() / promptTokens else 0f
}

private const val UNKNOWN_PROVIDER_ID = "__unknown_provider__"

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        // 按模型聚合后再映射到供应商，避免把完整消息 JSON 拉回 Kotlin
        val settings = settingsStore.settingsFlow.value
        val providerTokenStats = withContext(Dispatchers.IO) {
            buildProviderTokenStats(
                providers = settings.providers,
                usage = messageNodeDAO.getTokenStatsByModel(),
            )
        }

        val launchCount = settings.launchCount

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            providerTokenStats = providerTokenStats,
        )
    }
}

/** 把按模型聚合的用量映射为「供应商 -> 模型」两层结构；找不到的模型归入未知供应商。 */
private fun buildProviderTokenStats(
    providers: List<ProviderSetting>,
    usage: List<ModelTokenUsage>,
): List<ProviderTokenStat> {
    val modelIndex = HashMap<String, Pair<ProviderSetting, String>>()
    providers.forEach { provider ->
        provider.models.forEach { model ->
            modelIndex[model.id.toString()] = provider to model.displayName.ifBlank { model.modelId }
        }
    }

    return usage
        .map { entry ->
            val modelId = entry.modelId.orEmpty()
            val matched = modelIndex[modelId]
            ModelTokenStat(
                modelId = modelId,
                modelName = matched?.second.orEmpty(),
                providerId = matched?.first?.id?.toString() ?: UNKNOWN_PROVIDER_ID,
                providerName = matched?.first?.name.orEmpty(),
                promptTokens = entry.promptTokens,
                completionTokens = entry.completionTokens,
                cachedTokens = entry.cachedTokens,
                messageCount = entry.messageCount,
            )
        }
        .groupBy { it.providerId }
        .map { (providerId, models) ->
            ProviderTokenStat(
                providerId = providerId,
                providerName = models.first().providerName,
                models = models.sortedByDescending { it.totalTokens },
            )
        }
        .sortedByDescending { it.totalTokens }
}
