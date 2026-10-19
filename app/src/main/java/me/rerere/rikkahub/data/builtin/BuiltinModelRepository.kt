package me.rerere.rikkahub.data.builtin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.builtin.growth.GrowthTaskOutcome
import me.rerere.rikkahub.data.builtin.growth.WorkBuddyTaskRunner
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BuiltinModelRepo"
private const val PREFS_NAME = "builtin_model"
private const val KEY_ENABLED = "enabled"
private const val KEY_PORT = "port"
private const val KEY_API_KEY = "api_key"
private const val KEY_ACCOUNTS = "accounts"
private const val KEY_MODELS = "models"

/** 内置模型代理服务的默认端口。 */
const val DEFAULT_BUILTIN_PROXY_PORT = 8788

data class BuiltinModelState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = DEFAULT_BUILTIN_PROXY_PORT,
    val apiKey: String = "",
    val accounts: List<WorkBuddyAccount> = emptyList(),
    val models: List<BuiltinModelInfo> = BuiltinModels.defaults,
    val error: String? = null,
) {
    val endpoint: String
        get() = "http://127.0.0.1:$port/v1"

    val hasAccount: Boolean
        get() = accounts.any { it.isUsable }

    /** 当前可用（已拿到令牌）的账号。 */
    val usableAccounts: List<WorkBuddyAccount>
        get() = accounts.filter { it.isUsable }

    /** 是否至少有一个账号刷新过额度。 */
    val hasAnyQuota: Boolean
        get() = accounts.any { it.hasQuota }

    /** 所有已刷新额度账号的剩余额度合计。 */
    val totalCreditsRemaining: Double
        get() = accounts.mapNotNull { it.creditsRemaining }.sum()

    /** 所有已刷新额度账号的总额度合计。 */
    val totalCreditsTotal: Double
        get() = accounts.mapNotNull { it.creditsTotal }.sum()
}

/**
 * 内置模型的本地状态：登录得到的账号、本地 API Key、端口与开关。
 *
 * 数据落地在独立的 SharedPreferences 中，不污染主设置。
 */
class BuiltinModelRepository(
    private val context: Context,
    private val authApi: WorkBuddyAuthApi,
    private val taskRunner: WorkBuddyTaskRunner,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(BuiltinModelState())
    val state: StateFlow<BuiltinModelState> = _state.asStateFlow()

    init {
        val loaded = BuiltinModelState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = prefs.getInt(KEY_PORT, DEFAULT_BUILTIN_PROXY_PORT),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            accounts = runCatching {
                prefs.getString(KEY_ACCOUNTS, null)?.let {
                    json.decodeFromString(ListSerializer(WorkBuddyAccount.serializer()), it)
                }
            }.getOrElse {
                Log.w(TAG, "读取账号失败", it)
                null
            } ?: emptyList(),
            models = runCatching {
                prefs.getString(KEY_MODELS, null)?.let {
                    json.decodeFromString(ListSerializer(BuiltinModelInfo.serializer()), it)
                }
            }.getOrElse {
                Log.w(TAG, "读取模型列表失败", it)
                null
            }?.takeIf { it.isNotEmpty() } ?: BuiltinModels.defaults,
        )
        _state.value = loaded
        // 首次进入时确保已生成一把本地 API Key
        if (loaded.apiKey.isBlank()) {
            regenerateApiKey()
        }
    }

    val auth: WorkBuddyAuthApi get() = authApi

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun setRunning(running: Boolean, error: String? = null) {
        _state.value = _state.value.copy(running = running, error = error)
    }

    fun setError(error: String?) {
        _state.value = _state.value.copy(error = error)
    }

    fun setPort(port: Int) {
        if (port !in 1024..65535) return
        prefs.edit().putInt(KEY_PORT, port).apply()
        _state.value = _state.value.copy(port = port)
    }

    /** 生成一把新的本地 API Key（格式 `sk-` + 48 位十六进制）。 */
    fun regenerateApiKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val key = "sk-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_API_KEY, key).apply()
        _state.value = _state.value.copy(apiKey = key)
        return key
    }

    fun upsertAccount(account: WorkBuddyAccount) {
        val current = _state.value.accounts
        val replaced = current.map {
            if (it.uid == account.uid && it.region == account.region) account else it
        }
        val next = if (replaced.any { it.uid == account.uid && it.region == account.region }) {
            replaced
        } else {
            current + account
        }
        persistAccounts(next)
    }

    fun removeAccount(account: WorkBuddyAccount) {
        persistAccounts(_state.value.accounts.filterNot {
            it.uid == account.uid && it.region == account.region
        })
    }

    fun clearAccounts() {
        persistAccounts(emptyList())
    }

    private fun persistAccounts(accounts: List<WorkBuddyAccount>) {
        val encoded = json.encodeToString(ListSerializer(WorkBuddyAccount.serializer()), accounts)
        prefs.edit().putString(KEY_ACCOUNTS, encoded).apply()
        _state.value = _state.value.copy(accounts = accounts)
    }

    fun setModels(models: List<BuiltinModelInfo>) {
        persistModels(models)
    }

    private fun persistModels(models: List<BuiltinModelInfo>) {
        val encoded = json.encodeToString(ListSerializer(BuiltinModelInfo.serializer()), models)
        prefs.edit().putString(KEY_MODELS, encoded).apply()
        _state.value = _state.value.copy(models = models)
    }

    /** 从上游同步模型列表，返回同步到的数量。 */
    suspend fun syncModels(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val account = acquireAccount() ?: error("尚未登录任何账号")
            val fetched = authApi.fetchModels(account)
            if (fetched.isEmpty()) error("上游没有返回模型列表")
            val models = fetched.map { BuiltinModelInfo(id = it.first, name = it.second) }
            persistModels(models)
            models.size
        }
    }

    /**
     * 选取一个可用账号，并在必要时刷新其 accessToken。
     * 没有可用账号时返回 null。
     */
    suspend fun acquireAccount(): WorkBuddyAccount? {
        val candidates = _state.value.accounts.filter { it.isUsable }
        if (candidates.isEmpty()) return null
        val account = candidates.maxByOrNull { it.expiresAt } ?: return null
        return ensureFresh(account)
    }

    /** accessToken 临近过期时自动刷新。 */
    suspend fun ensureFresh(account: WorkBuddyAccount): WorkBuddyAccount {
        if (!account.isExpiring()) return account
        if (account.refreshToken.isBlank()) return account
        return withContext(Dispatchers.IO) {
            runCatching {
                val refreshed = authApi.refresh(account)
                if (refreshed.accessToken != account.accessToken) {
                    upsertAccount(refreshed)
                }
                refreshed
            }.getOrElse {
                Log.w(TAG, "刷新令牌失败", it)
                account
            }
        }
    }

    /** 主动刷新指定账号，供界面上的「刷新」按钮调用。 */
    suspend fun refreshAccount(account: WorkBuddyAccount): Result<WorkBuddyAccount> =
        withContext(Dispatchers.IO) {
            runCatching {
                val refreshed = authApi.refresh(account)
                upsertAccount(refreshed)
                refreshed
            }
        }

    /** 刷新账号额度，成功后把剩余 / 总计额度写回账号并持久化。 */
    suspend fun refreshQuota(account: WorkBuddyAccount): Result<WorkBuddyQuota> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fresh = ensureFresh(account)
                val quota = authApi.refreshQuota(fresh)
                upsertAccount(
                    fresh.copy(
                        creditsRemaining = quota.remaining,
                        creditsTotal = quota.total,
                        creditsExpireAt = quota.expireAt,
                    )
                )
                quota
            }
        }

    /** 每日签到；成功后刷新额度并记录签到日期。 */
    suspend fun checkIn(account: WorkBuddyAccount): Result<WorkBuddyCheckInResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fresh = ensureFresh(account)
                val result = authApi.checkIn(fresh)
                upsertAccount(
                    fresh.copy(
                        creditsRemaining = result.creditsRemaining ?: fresh.creditsRemaining,
                        creditsTotal = result.creditsTotal ?: fresh.creditsTotal,
                        lastCheckInDate = if (result.ok && result.supported) todayKey() else fresh.lastCheckInDate,
                    )
                )
                result
            }
        }

    /** 拉取成长中心任务列表。 */
    suspend fun fetchTasks(account: WorkBuddyAccount): Result<List<WorkBuddyTask>> =
        withContext(Dispatchers.IO) {
            runCatching { authApi.fetchTasks(ensureFresh(account)) }
        }

    /** 接受任务。 */
    suspend fun acceptTasks(account: WorkBuddyAccount, codes: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { authApi.acceptTasks(ensureFresh(account), codes) }
        }

    /** 领取任务奖励，返回 (积分, 能量)。 */
    suspend fun claimTask(account: WorkBuddyAccount, taskCode: String): Result<Pair<Long, Long>> =
        withContext(Dispatchers.IO) {
            runCatching { authApi.claimTask(ensureFresh(account), taskCode) }
        }

    /** 一键自动完成全部成长任务（照搬上游任务执行逻辑），[onProgress] 汇报当前执行到的任务。 */
    suspend fun runAllTasks(
        account: WorkBuddyAccount,
        onProgress: (String) -> Unit = {},
    ): Result<List<GrowthTaskOutcome>> =
        withContext(Dispatchers.IO) {
            runCatching { taskRunner.runAllTasks(ensureFresh(account), onProgress) }
        }

    /** 执行单个成长任务并自动领奖。 */
    suspend fun runTask(account: WorkBuddyAccount, taskCode: String): Result<GrowthTaskOutcome> =
        withContext(Dispatchers.IO) {
            runCatching { taskRunner.executeTask(ensureFresh(account), taskCode) }
        }
}

/** 当前日期（本地时区），格式 `yyyy-MM-dd`。 */
fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
