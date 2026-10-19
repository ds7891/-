package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.builtin.BuiltinModelRepository
import me.rerere.rikkahub.data.builtin.BuiltinModelState
import me.rerere.rikkahub.data.builtin.WorkBuddyAccount
import me.rerere.rikkahub.data.builtin.WorkBuddyAuthSession
import me.rerere.rikkahub.data.builtin.WorkBuddyRegion
import me.rerere.rikkahub.data.builtin.WorkBuddyTask
import me.rerere.rikkahub.data.builtin.formatCredits
import me.rerere.rikkahub.data.builtin.todayKey
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 内置模型页面：登录 CodeBuddy / WorkBuddy 账号，把网页版额度转成本地标准 API。
 *
 * 页面负责账号登录、令牌刷新、本地 API Key 与模型的展示与同步，实际的请求转发由
 * [me.rerere.rikkahub.data.builtin.BuiltinProxyServer] 在 8788 端口完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingBuiltinModelPage() {
    val repository = koinInject<BuiltinModelRepository>()
    val state by repository.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var loginSession by remember { mutableStateOf<WorkBuddyAuthSession?>(null) }
    var loggingIn by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    // 额度 / 签到 / 任务中心相关状态
    val today = remember { todayKey() }
    var busyAccountKey by remember { mutableStateOf<String?>(null) }
    var claimingCode by remember { mutableStateOf<String?>(null) }
    var taskAccountKey by remember { mutableStateOf<String?>(null) }
    var tasks by remember { mutableStateOf<List<WorkBuddyTask>>(emptyList()) }
    var tasksLoading by remember { mutableStateOf(false) }
    var tasksError by remember { mutableStateOf<String?>(null) }
    var autoRunning by remember { mutableStateOf(false) }
    var autoProgress by remember { mutableStateOf<String?>(null) }

    val taskAccount = state.usableAccounts.firstOrNull { it.key() == taskAccountKey }
        ?: state.usableAccounts.firstOrNull()

    // 进入页面或切换账号时自动拉取任务列表
    LaunchedEffect(taskAccount?.key()) {
        val account = taskAccount ?: run {
            tasks = emptyList()
            return@LaunchedEffect
        }
        tasksLoading = true
        tasksError = null
        repository.fetchTasks(account)
            .onSuccess { tasks = it }
            .onFailure { tasksError = it.message ?: "获取任务失败" }
        tasksLoading = false
    }

    fun refreshQuota(account: WorkBuddyAccount) {
        if (busyAccountKey != null) return
        busyAccountKey = account.key()
        scope.launch {
            repository.refreshQuota(account)
                .onSuccess { toaster.show("${account.displayName}：剩余 ${formatCredits(it.remaining)} 积分") }
                .onFailure { toaster.show("刷新额度失败：${it.message}", type = ToastType.Error) }
            busyAccountKey = null
        }
    }

    fun checkIn(account: WorkBuddyAccount) {
        if (busyAccountKey != null) return
        busyAccountKey = account.key()
        scope.launch {
            repository.checkIn(account)
                .onSuccess { toaster.show("${account.displayName}：${it.message}") }
                .onFailure { toaster.show("签到失败：${it.message}", type = ToastType.Error) }
            busyAccountKey = null
        }
    }

    fun checkInAll() {
        val domestic = state.usableAccounts.filter { it.regionEnum == WorkBuddyRegion.DOMESTIC }
        if (domestic.isEmpty() || busyAccountKey != null) return
        scope.launch {
            var success = 0
            domestic.forEach { account ->
                busyAccountKey = account.key()
                repository.checkIn(account).onSuccess { if (it.ok) success++ }
            }
            busyAccountKey = null
            toaster.show("签到完成：成功 $success / ${domestic.size}")
        }
    }

    fun loadTasks(account: WorkBuddyAccount) {
        scope.launch {
            tasksLoading = true
            tasksError = null
            repository.fetchTasks(account)
                .onSuccess { tasks = it }
                .onFailure { tasksError = it.message ?: "获取任务失败" }
            tasksLoading = false
        }
    }

    fun claimTask(account: WorkBuddyAccount, task: WorkBuddyTask) {
        if (claimingCode != null) return
        claimingCode = task.code
        scope.launch {
            repository.claimTask(account, task.code)
                .onSuccess { (credit, _) ->
                    toaster.show(if (credit > 0) "领取成功，获得 ${formatCredits(credit.toDouble())} 积分" else "该任务奖励已领取")
                    repository.fetchTasks(account).onSuccess { tasks = it }
                }
                .onFailure { toaster.show("领取失败：${it.message}", type = ToastType.Error) }
            claimingCode = null
        }
    }

    fun acceptTask(account: WorkBuddyAccount, task: WorkBuddyTask) {
        if (claimingCode != null) return
        claimingCode = task.code
        scope.launch {
            repository.acceptTasks(account, listOf(task.code))
                .onSuccess {
                    toaster.show("已接受任务：${task.title}")
                    repository.fetchTasks(account).onSuccess { tasks = it }
                }
                .onFailure { toaster.show("接受任务失败：${it.message}", type = ToastType.Error) }
            claimingCode = null
        }
    }

    fun runAllTasks(account: WorkBuddyAccount) {
        if (autoRunning) return
        autoRunning = true
        autoProgress = null
        scope.launch {
            repository.runAllTasks(account) { autoProgress = it }
                .onSuccess { results ->
                    val done = results.count { it.status == "done" }
                    val claimed = results.count { it.claimed }
                    val credit = results.sumOf { it.credit }
                    val failed = results.count { it.status == "error" }
                    buildString {
                        append("自动完成：执行 $done 个，领奖 $claimed 个")
                        if (credit > 0L) append("，+${formatCredits(credit.toDouble())} 积分")
                        if (failed > 0) append("，失败 $failed 个")
                    }.let { toaster.show(it) }
                    repository.fetchTasks(account).onSuccess { tasks = it }
                }
                .onFailure { toaster.show("自动完成任务失败：${it.message}", type = ToastType.Error) }
            autoRunning = false
            autoProgress = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内置模型") },
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServiceCard(state = state, repository = repository, toaster = toaster)
            AccountCard(
                state = state,
                loggingIn = loggingIn,
                busyAccountKey = busyAccountKey,
                onLogin = { region ->
                    if (loggingIn) return@AccountCard
                    loggingIn = true
                    scope.launch {
                        runCatching { repository.auth.requestAuthState(region) }
                            .onSuccess { loginSession = it }
                            .onFailure {
                                loggingIn = false
                                toaster.show(
                                    "获取登录链接失败：${it.message}",
                                    type = ToastType.Error,
                                )
                            }
                    }
                },
                onRefresh = { account ->
                    scope.launch {
                        repository.refreshAccount(account)
                            .onSuccess { toaster.show("已刷新账号：${it.displayName}") }
                            .onFailure { toaster.show("刷新失败：${it.message}", type = ToastType.Error) }
                    }
                },
                onRefreshQuota = { refreshQuota(it) },
                onRemove = { repository.removeAccount(it) },
                onClear = { repository.clearAccounts() },
            )
            CheckInCard(
                state = state,
                today = today,
                busyAccountKey = busyAccountKey,
                onCheckIn = { checkIn(it) },
                onCheckInAll = { checkInAll() },
            )
            TaskCenterCard(
                state = state,
                taskAccount = taskAccount,
                tasks = tasks,
                loading = tasksLoading,
                error = tasksError,
                claimingCode = claimingCode,
                autoRunning = autoRunning,
                autoProgress = autoProgress,
                onSelect = { taskAccountKey = it.key() },
                onRefresh = { taskAccount?.let { loadTasks(it) } },
                onClaim = { task -> taskAccount?.let { claimTask(it, task) } },
                onAccept = { task -> taskAccount?.let { acceptTask(it, task) } },
                onAutoRun = { taskAccount?.let { runAllTasks(it) } },
            )
            ModelCard(
                state = state,
                syncing = syncing,
                onSync = {
                    if (syncing) return@ModelCard
                    syncing = true
                    scope.launch {
                        repository.syncModels()
                            .onSuccess { toaster.show("已同步 $it 个模型") }
                            .onFailure { toaster.show("同步失败：${it.message}", type = ToastType.Error) }
                        syncing = false
                    }
                },
            )
            Text(
                text = "说明：开启后本地会在 127.0.0.1:${state.port} 提供 OpenAI 兼容接口，把这里的 API 地址与密钥填到「内置模型」供应商即可直接调用网页版额度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
        }
    }

    loginSession?.let { session ->
        LoginDialog(
            session = session,
            repository = repository,
            onSuccess = { account ->
                repository.upsertAccount(account)
                loginSession = null
                loggingIn = false
                toaster.show("登录成功：${account.displayName}", type = ToastType.Success)
            },
            onDismiss = {
                loginSession = null
                loggingIn = false
            },
        )
    }
}

@Composable
private fun ServiceCard(
    state: BuiltinModelState,
    repository: BuiltinModelRepository,
    toaster: com.dokar.sonner.ToasterState,
) {
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地反向代理", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            state.running -> "运行中"
                            state.enabled -> "启动中…"
                            else -> "已停止"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.running) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { repository.setEnabled(it) },
                )
            }

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            InfoRow(
                label = "API 地址",
                value = state.endpoint,
                onCopy = {
                    clipboard.setText(AnnotatedString(state.endpoint))
                    toaster.show("已复制 API 地址", type = ToastType.Success)
                },
            )

            InfoRow(
                label = "API 密钥",
                value = if (showKey) state.apiKey else maskKey(state.apiKey),
                onCopy = {
                    clipboard.setText(AnnotatedString(state.apiKey))
                    toaster.show("已复制 API 密钥", type = ToastType.Success)
                },
                trailing = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                        )
                    }
                    IconButton(onClick = { repository.regenerateApiKey() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "重新生成密钥")
                    }
                },
            )

            Text(
                text = "密钥需复制到刚添加的「内置模型」供应商中，用于访问本地代理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        IconButton(onClick = onCopy) {
            Icon(HugeIcons.Copy01, contentDescription = "复制")
        }
    }
}

@Composable
private fun AccountCard(
    state: BuiltinModelState,
    loggingIn: Boolean,
    busyAccountKey: String?,
    onLogin: (WorkBuddyRegion) -> Unit,
    onRefresh: (WorkBuddyAccount) -> Unit,
    onRefreshQuota: (WorkBuddyAccount) -> Unit,
    onRemove: (WorkBuddyAccount) -> Unit,
    onClear: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "登录腾讯 CodeBuddy / WorkBuddy 账号，网页版额度会通过本地代理转成标准 API。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.accounts.isNotEmpty()) {
                Text(
                    text = buildString {
                        append("可用账号 ${state.usableAccounts.size} / ${state.accounts.size}")
                        if (state.hasAnyQuota) {
                            append(" · 剩余额度合计 ${formatCredits(state.totalCreditsRemaining)} 积分")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onLogin(WorkBuddyRegion.DOMESTIC) },
                    enabled = !loggingIn,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("登录国内版")
                }
                OutlinedButton(
                    onClick = { onLogin(WorkBuddyRegion.INTERNATIONAL) },
                    enabled = !loggingIn,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("登录国际版")
                }
            }

            if (state.accounts.isEmpty()) {
                Text(
                    text = "尚未登录账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        busy = busyAccountKey == account.key(),
                        onRefresh = { onRefresh(account) },
                        onRefreshQuota = { onRefreshQuota(account) },
                        onRemove = { onRemove(account) },
                    )
                    HorizontalDivider()
                }
                TextButton(onClick = onClear) {
                    Text("退出全部账号", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: WorkBuddyAccount,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRefreshQuota: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.AiBrain01, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(account.regionEnum.label)
                        append(" · ")
                        append(
                            when {
                                account.expiresAt <= 0L -> "有效期未知"
                                account.isExpiring(0L) -> "令牌已过期"
                                account.isExpiring() -> "令牌即将过期"
                                else -> "令牌有效"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(HugeIcons.Refresh03, contentDescription = "刷新令牌")
            }
            IconButton(onClick = onRemove) {
                Icon(HugeIcons.Delete01, contentDescription = "删除账号")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = account.quotaText,
                style = MaterialTheme.typography.labelSmall,
                color = if (account.hasQuota) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefreshQuota) {
                    Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("刷新额度")
                }
            }
        }
    }
}

@Composable
private fun CheckInCard(
    state: BuiltinModelState,
    today: String,
    busyAccountKey: String?,
    onCheckIn: (WorkBuddyAccount) -> Unit,
    onCheckInAll: () -> Unit,
) {
    val domestic = state.usableAccounts.filter { it.regionEnum == WorkBuddyRegion.DOMESTIC }
    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("签到中心", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "国内版账号每日签到可领取额度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onCheckInAll,
                    enabled = domestic.isNotEmpty() && busyAccountKey == null,
                ) {
                    Text("一键签到")
                }
            }

            if (domestic.isEmpty()) {
                Text(
                    text = "暂无可签到的国内版账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                domestic.forEach { account ->
                    val checkedIn = account.checkedInOn(today)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = account.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (checkedIn) "今日已签到" else "今日未签到",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (checkedIn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (busyAccountKey == account.key()) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { onCheckIn(account) }, enabled = !checkedIn) {
                                Text(if (checkedIn) "已签到" else "签到")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TaskCenterCard(
    state: BuiltinModelState,
    taskAccount: WorkBuddyAccount?,
    tasks: List<WorkBuddyTask>,
    loading: Boolean,
    error: String?,
    claimingCode: String?,
    autoRunning: Boolean,
    autoProgress: String?,
    onSelect: (WorkBuddyAccount) -> Unit,
    onRefresh: () -> Unit,
    onClaim: (WorkBuddyTask) -> Unit,
    onAccept: (WorkBuddyTask) -> Unit,
    onAutoRun: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val candidates = state.usableAccounts

    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("任务中心", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = taskAccount?.let { "账号：${it.displayName}" } ?: "登录账号后可查看任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidates.size > 1) {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) { Text("切换账号") }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            candidates.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.displayName) },
                                    onClick = {
                                        menuExpanded = false
                                        onSelect(account)
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onRefresh, enabled = taskAccount != null && !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新")
                    }
                }
            }

            Button(
                onClick = onAutoRun,
                enabled = taskAccount != null && !autoRunning && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (autoRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在自动完成…")
                } else {
                    Text("自动完成任务")
                }
            }
            autoProgress?.let {
                Text(
                    text = "正在执行：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                taskAccount == null -> Text(
                    text = "尚未登录可用账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tasks.isEmpty() && !loading && error == null -> Text(
                    text = "暂无任务，点「刷新」重新获取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        claiming = claimingCode == task.code,
                        onClaim = { onClaim(task) },
                        onAccept = { onAccept(task) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: WorkBuddyTask,
    claiming: Boolean,
    onClaim: () -> Unit,
    onAccept: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = buildString {
                    append("奖励 ${formatCredits(task.rewardCredit.toDouble())} 积分")
                    if (task.rewardEnergy > 0L) {
                        append(" · 能量 ${task.rewardEnergy}")
                    }
                    if (task.progressText.isNotBlank()) {
                        append(" · 进度 ${task.progressText}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        when {
            task.claimed -> Text(
                text = "已领取",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            task.locked -> Text(
                text = "未解锁",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            claiming -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            task.completed -> Button(onClick = onClaim) { Text("领取") }
            else -> OutlinedButton(
                onClick = {
                    onAccept()
                    if (task.jumpUrl.isNotBlank()) {
                        runCatching { uriHandler.openUri(task.jumpUrl) }
                    }
                },
            ) {
                Text(if (task.jumpUrl.isNotBlank()) "去做" else "接受")
            }
        }
    }
}

private fun WorkBuddyAccount.key(): String = "$uid@$region"

@Composable
private fun ModelCard(
    state: BuiltinModelState,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "当前 ${state.models.size} 个模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSync, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("同步模型")
                    }
                }
            }

            if (state.models.isNotEmpty()) {
                Text(
                    text = state.models.joinToString("、") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginDialog(
    session: WorkBuddyAuthSession,
    repository: BuiltinModelRepository,
    onSuccess: (WorkBuddyAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    val webState = rememberWebViewState(url = session.authUrl) {
        javaScriptEnabled = true
        domStorageEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }

    // 自动轮询登录结果，最多持续约 5 分钟
    LaunchedEffect(session) {
        repeat(150) {
            delay(2_000)
            val account = repository.auth.pollAuthToken(session.region, session.state)
            if (account != null) {
                onSuccess(account)
                return@LaunchedEffect
            }
        }
        hint = "登录超时，请关闭后重试"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "登录 ${session.region.label}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = hint ?: "完成登录后会自动检测结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hint == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    IconButton(onClick = { webState.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新页面")
                    }
                }

                WebView(
                    state = webState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val account = repository.auth.pollAuthToken(session.region, session.state)
                                if (account != null) {
                                    onSuccess(account)
                                } else {
                                    hint = "尚未检测到登录，请继续在页面中完成登录"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("我已登录完成")
                    }
                }
            }
        }
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 12) key else "${key.take(8)}••••••••${key.takeLast(4)}"
