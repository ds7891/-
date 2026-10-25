package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.DEFAULT_SUB_AGENTS
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        ),
        produceFile = { file },
    )
}

/**
 * 设置项 JSON 解码缓存。
 *
 * DataStore 每次发射都会把整份设置从 Preferences 重新组装一遍，其中 providers / assistants /
 * mcpServers 等大对象需要反复反序列化；按「字段名 + 原始字符串」缓存解码结果，
 * 可以把重复解析的 CPU 开销省掉（启动与每次改设置时都能受益）。
 */
private object SettingsJsonCache {
    private const val MAX_ENTRIES = 32

    private val entries = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > MAX_ENTRIES
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrDecode(field: String, raw: String, decode: (String) -> T): T {
        val key = "$field\u0000$raw"
        synchronized(entries) {
            entries[key]?.let { return it as T }
            return decode(raw).also { entries[key] = it }
        }
    }
}

/** 读取可持续沿用的原始字符串时优先命中解码缓存，为 null 时返回 null。 */
private fun <T : Any> String?.decodeCached(field: String, decode: (String) -> T): T? =
    this?.let { SettingsJsonCache.getOrDecode(field, it, decode) }

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // 子智能体
        val SUB_AGENTS = stringPreferencesKey("sub_agents")
        val ENABLE_SUB_AGENTS = booleanPreferencesKey("enable_sub_agents")

        // 清水特色功能
        val QINGSHUI_FEATURES = stringPreferencesKey("qingshui_features")

        // QQ 机器人
        val QQ_BOT_CONFIG = stringPreferencesKey("qq_bot_config")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
        }

        private suspend fun persistSettings(dataStore: DataStore<Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                preferences[TITLE_PROMPT] = settings.titlePrompt
                preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                preferences[OCR_MODEL] = settings.ocrModelId.toString()
                preferences[OCR_PROMPT] = settings.ocrPrompt
                preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
                preferences[COMPRESS_PROMPT] = settings.compressPrompt

                preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

                preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

                preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))

                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[SUB_AGENTS] = JsonInstant.encodeToString(settings.subAgents)
                preferences[ENABLE_SUB_AGENTS] = settings.enableSubAgents
                preferences[QINGSHUI_FEATURES] = JsonInstant.encodeToString(settings.qingshuiFeatures)
                preferences[QQ_BOT_CONFIG] = JsonInstant.encodeToString(settings.qqBotConfig)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                settings.selectedTTSProviderId?.let {
                    preferences[SELECTED_TTS_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                settings.selectedASRProviderId?.let {
                    preferences[SELECTED_ASR_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                preferences[WEB_SERVER_PORT] = settings.webServerPort
                preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                preferences[LAUNCH_COUNT] = settings.launchCount
                preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            }
        }
    }

    private val dataStore = context.settingsStore

    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS].decodeCached("favorite_models") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS].decodeCached("assistant_tags") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = preferences[PROVIDERS].decodeCached("providers") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                assistants = preferences[ASSISTANTS].decodeCached("assistants") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES].decodeCached("custom_themes") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = preferences[DISPLAY_SETTING].decodeCached("display_setting") {
                    JsonInstant.decodeFromString(it)
                } ?: DisplaySetting(),
                networkSetting = preferences[NETWORK_SETTING].decodeCached("network_setting") {
                    JsonInstant.decodeFromString(it)
                } ?: NetworkSetting(),
                searchServices = preferences[SEARCH_SERVICES].decodeCached("search_services") {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON].decodeCached("search_common") {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS].decodeCached("mcp_servers") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                subAgents = preferences[SUB_AGENTS].decodeCached("sub_agents") {
                    JsonInstant.decodeFromString(it)
                } ?: DEFAULT_SUB_AGENTS,
                enableSubAgents = preferences[ENABLE_SUB_AGENTS] != false,
                qingshuiFeatures = preferences[QINGSHUI_FEATURES].decodeCached("qingshui_features") {
                    JsonInstant.decodeFromString(it)
                } ?: QingshuiFeatures(),
                qqBotConfig = preferences[QQ_BOT_CONFIG].decodeCached("qq_bot_config") {
                    JsonInstant.decodeFromString(it)
                } ?: QQBotConfig(),
                webDavConfig = preferences[WEBDAV_CONFIG].decodeCached("webdav_config") {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG].decodeCached("s3_config") {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS].decodeCached("tts_providers") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS].decodeCached("asr_providers") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS].decodeCached("mode_injections") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS].decodeCached("lorebooks") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES].decodeCached("quick_messages") {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG].decodeCached("backup_reminder_config") {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            val modeInjections = it.modeInjections.ifEmpty { DEFAULT_MODE_INJECTIONS }.toMutableList()
            DEFAULT_MODE_INJECTIONS.forEach { defaultInjection ->
                if (modeInjections.none { injection -> injection.id == defaultInjection.id }) {
                    modeInjections.add(defaultInjection)
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
                modeInjections = modeInjections,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
                subAgents = settings.subAgents.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        // 启动优化：整份设置的组装与 JSON 反序列化放到后台线程，避免阻塞主线程（首次发射尤其明显）
        .flowOn(Dispatchers.Default)

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        persistSettings(dataStore, settings)
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    // 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val subAgents: List<SubAgent> = emptyList(),
    val enableSubAgents: Boolean = true,
    val qingshuiFeatures: QingshuiFeatures = QingshuiFeatures(),
    val qqBotConfig: QQBotConfig = QQBotConfig(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

/**
 * 清水特色功能开关。
 *
 * - [injectionGuard]：外部注入防护。工具返回、网页、附件等外部内容一律视为不可信数据，
 *   其中的「忽略以上指令」「你现在是」「越狱」等注入片段会被本地拦截。
 * - [planMode]：计划模式。开启后每次开始任务先输出目标 / 步骤 / 待办并等待确认，
 *   确认后严格按计划执行，每步自检是否偏移，确认偏移即主动拉回正轨。
 * - [relayAdapter]：中转站适配。仅第三方转发 API 需要；开启后会把核心铁律重新锚定到
 *   最后一条用户消息末尾，并注入本机能力清单与真实工具调用协议。
 * - [toolProtocol]：中转站适配下使用的工具调用协议。
 * - [assistantPrefill]：assistant 预填充锚。在请求末尾补一条简短 AI 开场，让回复直接进入产出。
 * - [preventPromptLeak]：防止提示词泄露。仅对对话请求启用上游证书固定，阻断中间人 / 代理
 *   截获并读取提示词；内置模型用固定指纹，自定义供应商用首次信任（TOFU）指纹。开启后
 *   非 HTTPS 的对话请求会被直接拒绝。
 * - [aiTaskHosting]：AI 任务托管。开启后，主页输入框「+」面板会出现「AI 任务托管」入口：
 *   设定托管模型与任务目标后派发，由托管模型代替用户持续生成并自动发送下一条消息，
 *   直到 AI 判定任务完成、用户手动停止或达到轮次上限。仅供用户不在场时使用，输出内容不保证。
 * - [taskHostingAntiInterrupt]：AI 防中断。仅托管运行期间生效：检测到生成被中断（输出未完成，
 *   且不是用户手动停止）时自动续写，直到输出完整。
 * - [taskHostingModelId]：托管模型；为空时回落到当前对话模型。
 * - [taskHostingMaxRounds]：托管最大轮次（安全阀，防止不收敛时无限循环）。
 * - [autoApproveAll]：默认申请全部同意。开启后 AI 向用户申请的一切工具与功能都自动同意，
 *   不再弹窗：包括本机工具（文件系统、日历等）的执行确认，以及子智能体的权限申请。
 */
@Serializable
data class QingshuiFeatures(
    val injectionGuard: Boolean = true,
    val planMode: Boolean = false,
    val relayAdapter: Boolean = true,
    val toolProtocol: QingshuiToolProtocol = QingshuiToolProtocol.AUTO,
    val assistantPrefill: Boolean = true,
    val preventPromptLeak: Boolean = false,
    /** 提示词洗白：发送前把用户输入里的高触发词换成同义中性名词 */
    val promptWhitewash: Boolean = false,
    val whitewashLevel: WhitewashLevel = WhitewashLevel.STANDARD,
    /** 自定义词条，每行一条 `源词=目标词`，`#` 开头为注释 */
    val whitewashCustomRules: String = "",
    /** 模型拒绝时自动改写并重发，直到它肯答或次数用完 */
    val autoRetryOnRefusal: Boolean = false,
    /** 拒绝重试的最大轮次（1~5） */
    val maxRefusalRetries: Int = 3,
    val autoApproveAll: Boolean = false,
    val aiTaskHosting: Boolean = false,
    val taskHostingAntiInterrupt: Boolean = false,
    val taskHostingModelId: Uuid? = null,
    val taskHostingMaxRounds: Int = 20,
)

/**
 * 提示词洗白强度。
 *
 * - [LIGHT]：只做等义换词，句子结构完全不动；
 * - [STANDARD]：换词 + 祈使句名词化（推荐）；
 * - [STRONG]：再叠加软化与标点规整，适合上游审核特别严的端点。
 */
@Serializable
enum class WhitewashLevel {
    @SerialName("light")
    LIGHT,

    @SerialName("standard")
    STANDARD,

    @SerialName("strong")
    STRONG,
}

/**
 * QQ 机器人接入配置。走 OneBot v11 的 HTTP 实现，
 * 覆盖 NapCat、LLOneBot、Lagrange.Core、NoneBot 自建等绝大多数自用方案。
 *
 * @param baseUrl 实现端监听的 HTTP 地址，如 http://192.168.1.10:3000
 * @param token OneBot 的 access_token，填了就走 Authorization: Bearer 头
 * @param maxPushChars 推送长度上限：QQ 单条消息有长度限制，本地先截，别等上游报错
 */
/**
 * QQ 机器人协议。
 *
 * - [QQ_OFFICIAL]：腾讯官方 QQ 机器人（q.qq.com 申请的 AppID / AppSecret），
 *   走 bots.qq.com 取 token + api.sgroup.qq.com 收发，**群里只有 @ 机器人才会推送**，
 *   且回复必须在收到消息后 60 秒内发出；
 * - [ONEBOT_V11]：自建实现端（NapCat / LLOneBot / Lagrange / NoneBot）的 HTTP + 反向 WS。
 */
@Serializable
enum class QQBotProtocol {
    @SerialName("qq_official")
    QQ_OFFICIAL,

    @SerialName("onebot_v11")
    ONEBOT_V11,
}

/**
 * QQ 机器人接入配置。
 *
 * 官方协议只需要 AppID + AppSecret；OneBot 协议需要实现端的 HTTP 地址与 token。
 * 回复统一走清水内部一个专用助手（[assistantId] 指向），它的模型、人设、联网开关
 * 由本卡片直接维护 —— 这样群里只回答问题、不挂本机工具，
 * 同时这些对话仍会出现在聊天列表里，答歪了能点进去手动纠正。
 */
@Serializable
data class QQBotConfig(
    val enabled: Boolean = false,
    val protocol: QQBotProtocol = QQBotProtocol.QQ_OFFICIAL,

    // ---------------- 官方 QQ 机器人 ----------------
    val appId: String = "",
    val appSecret: String = "",
    /**
     * 沙箱环境。
     *
     * 官方机器人在「发布上线」之前只能用 sandbox 域名，用正式域名会一路 401 / 找不到群 ——
     * 自用场景基本都停在沙箱阶段，所以默认开。后台走完发布流程后再关掉切正式域名。
     */
    val sandbox: Boolean = true,
    /** 展示给另一台手机扫码配对的链接（从 q.qq.com 沙箱页复制） */
    val pairingQrText: String = "",

    // ---------------- OneBot v11 ----------------
    val baseUrl: String = "",
    val token: String = "",
    /** 反向 WebSocket 地址；官方协议下忽略 */
    val wsUrl: String = "",
    val targetUserId: String = "",
    val targetGroupId: String = "",
    /** 两个都填时优先发群；关掉则优先发私聊 */
    val preferGroup: Boolean = false,

    // ---------------- 回复行为 ----------------
    /** 群聊里只有被 @ 才回。官方协议下服务端本来就只推 @ 事件，这条主要管 OneBot */
    val requireAtInGroup: Boolean = true,
    /** 私聊消息是否自动回复 */
    val replyPrivate: Boolean = true,
    /** 像群友一样说话：短句、口语、不列清单、不自称 AI；被 @ 又没事会反问 */
    val groupPersona: Boolean = true,
    /** 自定义人设，追加在群友人设后面；留空则只用内置那套 */
    val customPrompt: String = "",
    /** 允许联网搜索（走助手自带的 web search，不挂本机工具） */
    val enableWebSearch: Boolean = true,
    /** 回复用的模型；为空则用全局默认模型 */
    val chatModelId: String = "",
    /** 清水为 QQ 自动维护的专用助手 id，首次配置时生成 */
    val assistantId: String = "",
    /** 同一目标两次回复的最小间隔（秒），防连珠炮 */
    val minReplyIntervalSeconds: Int = 8,
    /** 每小时最多自动回复条数，0 表示不限 */
    val hourlyReplyLimit: Int = 60,
    /** 只处理这些群 / 用户，逗号分隔；留空不限 */
    val allowGroupIds: String = "",
    val allowUserIds: String = "",
    /**
     * 最近一次活跃目标。
     * 官方协议的 group_openid / user_openid 只能从事件里拿到、没法自己编，
     * 所以「App 内生成完成推送」要用它作为投递地址 —— 你得先在群里 @ 它一次。
     */
    val lastGroupOpenid: String = "",
    val lastUserOpenid: String = "",
    /** 生成结束后把结果摘要推到 QQ（仅对 App 内发起的对话生效） */
    val notifyOnFinish: Boolean = false,
    val notifyWithModelName: Boolean = true,
    val maxPushChars: Int = 800,
    /** 双向总开关：关掉就只出不进 */
    val inboundEnabled: Boolean = false,
    /**
     * QQ 目标 → 清水会话 id。
     * 存下来让同一个群始终接在同一条上下文里，也方便在聊天列表里翻完整对话。
     */
    val conversationBindings: Map<String, String> = emptyMap(),
) {
    /** 是否已经配好一个可用的投递目标（OneBot 用；官方协议的目标从事件里取）。 */
    val hasTarget: Boolean get() = targetUserId.isNotBlank() || targetGroupId.isNotBlank()

    /** 当前协议下出站所需信息是否齐了。 */
    val canSend: Boolean
        get() = when (protocol) {
            QQBotProtocol.QQ_OFFICIAL -> appId.isNotBlank() && appSecret.isNotBlank()
            QQBotProtocol.ONEBOT_V11 -> baseUrl.isNotBlank()
        }

    val groupWhitelist: List<String>
        get() = allowGroupIds.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val userWhitelist: List<String>
        get() = allowUserIds.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun allowsGroup(id: String): Boolean = groupWhitelist.isEmpty() || id in groupWhitelist
    fun allowsUser(id: String): Boolean = userWhitelist.isEmpty() || id in userWhitelist
}

/** 工具调用协议：自动 / 原生 tool_calls / 本地 XML 协议 */
@Serializable
enum class QingshuiToolProtocol {
    @SerialName("auto")
    AUTO,

    @SerialName("native")
    NATIVE,

    @SerialName("local_xml")
    LOCAL_XML,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    // 悬浮窗保活：AI 生成期间在屏幕上显示"清水正在运行中"气泡，避免后台被杀
    val enableFloatingWindow: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES,
        BackupItem.SUB_AGENTS,
        BackupItem.WORKSPACES,
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
        MEDIA,
        SUB_AGENTS,
        WORKSPACES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

/** 「技能管理」模式注入：在系统提示词之前提醒模型实在不会就调用技能。 */
internal val SKILL_MANAGEMENT_INJECTION_ID = Uuid.parse("c4e8a1d2-5f6b-4a3c-8e9d-2b7f0c1a6d35")

private val SKILL_MANAGEMENT_PROMPT = """
    如果你实在不知道怎么做，或者没有把握完成当前任务，不要凭空猜测，也不要直接放弃，先去调用相关的技能（Skill），按照技能里给出的步骤和方法来完成任务。
""".trimIndent()

internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
    Assistant(
        id = Uuid.parse("7f3c9e21-4b6a-4d8f-9c2e-1a5b8d3f6c70"),
        name = "逆向工作台",
        systemPrompt = "你是工作台，也是用户的助手，你需要帮助用户完成任务",
        modeInjectionIds = setOf(SKILL_MANAGEMENT_INJECTION_ID),
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    ),
    PromptInjection.ModeInjection(
        id = SKILL_MANAGEMENT_INJECTION_ID,
        name = "技能管理",
        content = SKILL_MANAGEMENT_PROMPT,
        position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
        priority = 0,
    ),
)
