package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.RestoreFailedException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.builtin.BuiltinProxyManager
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "QingshuiApp"

/**
 * 启动优化：维护类任务（临时文件清理、技能解压、文件同步、工作区校验）延后一段时间再执行，
 * 并在同一个 IO 线程上串行跑，避免与首帧渲染、设置/会话读取抢占 CPU 与磁盘 IO。
 */
private const val STARTUP_MAINTENANCE_DELAY_MS = 1500L

/**
 * 动态加载：非首帧必需的服务（通知订阅、内置模型代理、MCP、WebServer、维护任务）统一延后到这里。
 * 短暂延迟是为了让首帧先渲染出来，避免和首屏抢主线程与磁盘。
 */
private const val DEFERRED_INIT_DELAY_MS = 600L

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class QingshuiApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1) 备份恢复必须早于 Koin / Room / SettingsStore，否则恢复的数据会被旧值覆盖。
        //    没有待恢复目录时跳过 runBlocking，避免主线程在冷启动时等待一次磁盘 IO。
        if (BackupManager.hasPendingRestore(this)) {
            try {
                val restored = runBlocking(Dispatchers.IO) {
                    BackupManager.applyPendingRestore(this@QingshuiApp, JsonInstant)
                }
                if (restored) {
                    Toast.makeText(this, R.string.backup_page_restore_success, Toast.LENGTH_LONG).show()
                }
            } catch (e: RestoreFailedException) {
                Log.e(TAG, "Backup restore rolled back", e)
                Toast.makeText(this, "备份恢复失败，已保留原数据。请重新导入备份。", Toast.LENGTH_LONG).show()
            }
        }

        // 2) 只注册模块，不主动创建单例：Room / SettingsStore 等按需懒加载，缩短冷启动。
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@QingshuiApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }

        // 3) 崩溃捕获尽早安装，保证启动阶段的异常也能被捕获上报。
        CrashHandler.install(this)

        // 4) 其余非必需工作在首帧之后动态加载，避免和首屏抢主线程 / 磁盘 IO。
        scheduleDeferredInit()
    }

    /**
     * 首帧之后再加载非必需服务：通知渠道、内置模型代理、MCP、WebServer、启动维护任务等。
     * 基础对话与其核心服务在进入首页时按需初始化，这里只负责“额外的服务”。
     */
    private fun scheduleDeferredInit() {
        get<AppScope>().launch {
            delay(DEFERRED_INIT_DELAY_MS)
            runCatching { createNotificationChannel() }
                .onFailure { Log.e(TAG, "createNotificationChannel failed", it) }
            DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

            // 额外服务：进入首页后再初始化，避免拖慢冷启动
            runCatching { get<ChatNotificationManager>() }
                .onFailure { Log.e(TAG, "init ChatNotificationManager failed", it) }
            runCatching { get<BuiltinProxyManager>() }
                .onFailure { Log.e(TAG, "init BuiltinProxyManager failed", it) }
            runCatching { get<McpManager>() }
                .onFailure { Log.e(TAG, "init McpManager failed", it) }
            runCatching { get<WebServerManager>() }
                .onFailure { Log.e(TAG, "init WebServerManager failed", it) }

            // 维护任务与 WebServer 启动
            scheduleStartupMaintenance()
            startWebServerIfEnabled()
            incrementLaunchCount()
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun scheduleStartupMaintenance() {
        get<AppScope>().launch(Dispatchers.IO) {
            delay(STARTUP_MAINTENANCE_DELAY_MS)
            runCatching { get<SkillManager>().ensureBuiltinSkillsExtracted() }
                .onFailure { Log.e(TAG, "extractBuiltinSkills failed", it) }
            runCatching { deleteTempFilesNow() }
                .onFailure { Log.e(TAG, "deleteTempFiles failed", it) }
            runCatching { cleanupToolOutputsNow() }
                .onFailure { Log.e(TAG, "cleanupToolOutputs failed", it) }
            runCatching { get<FilesManager>().syncFolder() }
                .onFailure { Log.e(TAG, "syncManagedFiles failed", it) }
            runCatching { get<WorkspaceManager>().cleanupAllTempDirs() }
                .onFailure { Log.e(TAG, "cleanupWorkspaceTempDirs failed", it) }
            runCatching { get<WorkspaceRepository>().checkIntegrity() }
                .onFailure { Log.e(TAG, "checkWorkspaceIntegrity failed", it) }
        }
    }

    private fun deleteTempFilesNow() {
        val dir = appTempFolder
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun cleanupToolOutputsNow() {
        val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@QingshuiApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@QingshuiApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@QingshuiApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
