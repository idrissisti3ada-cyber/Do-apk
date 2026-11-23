package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.MyCodeApp
import com.example.R
import com.example.domain.AppCaseMode
import com.example.util.BrowserHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FloatingBrowserService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var browserManager: FloatingBrowserManager
    private var captureJob: Job? = null

    private val appContainer by lazy { (application as MyCodeApp).container }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        browserManager = FloatingBrowserManager(
            context = this,
            onTabsChanged = { tabs ->
                _openTabs.value = tabs
                hasActiveShop2GamePage = browserManager.hasRedeemPageFor(AppCaseMode.MYCODE_G7F)
                hasActiveGarenaRewardPage = browserManager.hasRedeemPageFor(AppCaseMode.GARENA_CODE)
                hasActiveRedeemPage = hasActiveShop2GamePage || hasActiveGarenaRewardPage
                if (tabs.isNotEmpty()) {
                    promoteToForeground(buildNotification(tabs.size))
                }
            },
            onAllTabsClosed = {
                _openTabs.value = emptyList()
                hasActiveShop2GamePage = false
                hasActiveGarenaRewardPage = false
                hasActiveRedeemPage = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            onPageConfirmed = {
                appContainer.captureEngine.notifyConfirmed()
            }
        )

        appContainer.captureEngine.registerFloatingBrowserFocusReleaser {
            browserManager.releaseAllKeyboardFocus()
        }

        captureJob = serviceScope.launch {
            appContainer.captureEngine.capturedEvents.collect { event ->
                val prefs = appContainer.preferencesRepository.currentFast()
                if (prefs.autoRedeemShop2Game && event.codes.isNotEmpty()) {
                    browserManager.autoRedeemCodesAcrossPages(event.codes, event.caseMode)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(buildNotification(browserManager.currentTabs().size))

        when (intent?.action) {
            ACTION_OPEN_BROWSER -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: BrowserHelper.DEFAULT_REDEEM_URL
                val profilePkg = intent.getStringExtra(EXTRA_PROFILE_PACKAGE)
                val profileLabel = intent.getStringExtra(EXTRA_PROFILE_LABEL) ?: "Floating"
                val opened = browserManager.openNewPage(
                    initialUrl = url,
                    browserProfilePackage = profilePkg,
                    browserProfileLabel = profileLabel
                )
                if (opened == null && browserManager.currentTabs().isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_RESTORE_TAB -> {
                val tabId = intent.getIntExtra(EXTRA_TAB_ID, -1)
                if (tabId != -1) browserManager.restoreOrFocusPage(tabId)
            }

            ACTION_MINIMIZE_TAB -> {
                val tabId = intent.getIntExtra(EXTRA_TAB_ID, -1)
                if (tabId != -1) browserManager.minimizePage(tabId)
            }

            ACTION_CLOSE_TAB -> {
                val tabId = intent.getIntExtra(EXTRA_TAB_ID, -1)
                if (tabId != -1) browserManager.closePage(tabId)
            }

            ACTION_CLOSE_ALL -> {
                browserManager.closeAllPages()
            }
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(tabCount: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeAllIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, FloatingBrowserService::class.java).apply {
                action = ACTION_CLOSE_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_shield)
            .setContentTitle("MyCode Hub • Floating Browsers")
            .setContentText("$tabCount browser window(s) active • Drag to border for bubble")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Close All", closeAllIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_browser_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_browser_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        hasActiveShop2GamePage = false
        hasActiveGarenaRewardPage = false
        hasActiveRedeemPage = false
        appContainer.captureEngine.registerFloatingBrowserFocusReleaser(null)
        captureJob?.cancel()
        browserManager.closeAllPages()
        _openTabs.value = emptyList()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mycodeg7f_browser_channel"
        private const val NOTIFICATION_ID = 7072

        private const val ACTION_OPEN_BROWSER = "com.example.ACTION_OPEN_BROWSER"
        private const val ACTION_RESTORE_TAB = "com.example.ACTION_RESTORE_TAB"
        private const val ACTION_MINIMIZE_TAB = "com.example.ACTION_MINIMIZE_TAB"
        private const val ACTION_CLOSE_TAB = "com.example.ACTION_CLOSE_TAB"
        private const val ACTION_CLOSE_ALL = "com.example.ACTION_CLOSE_ALL"

        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_PROFILE_PACKAGE = "extra_profile_package"
        private const val EXTRA_PROFILE_LABEL = "extra_profile_label"
        private const val EXTRA_TAB_ID = "extra_tab_id"

        @Volatile
        private var isRunning = false

        @Volatile
        var hasActiveRedeemPage: Boolean = false
            private set

        @Volatile
        var hasActiveShop2GamePage: Boolean = false
            private set

        @Volatile
        var hasActiveGarenaRewardPage: Boolean = false
            private set

        fun hasActiveRedeemPageFor(mode: AppCaseMode): Boolean = when (mode) {
            AppCaseMode.MYCODE_G7F -> hasActiveShop2GamePage
            AppCaseMode.GARENA_CODE -> hasActiveGarenaRewardPage
        }

        private val _openTabs = MutableStateFlow<List<FloatingBrowserTabInfo>>(emptyList())
        val openTabs: StateFlow<List<FloatingBrowserTabInfo>> = _openTabs.asStateFlow()

        fun openFloatingBrowser(
            context: Context,
            url: String = BrowserHelper.DEFAULT_REDEEM_URL,
            profilePackage: String? = null,
            profileLabel: String = "Floating"
        ) {
            val intent = Intent(context, FloatingBrowserService::class.java).apply {
                action = ACTION_OPEN_BROWSER
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_PROFILE_PACKAGE, profilePackage)
                putExtra(EXTRA_PROFILE_LABEL, profileLabel)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restoreTab(context: Context, tabId: Int) {
            if (!isRunning) return
            context.startService(
                Intent(context, FloatingBrowserService::class.java).apply {
                    action = ACTION_RESTORE_TAB
                    putExtra(EXTRA_TAB_ID, tabId)
                }
            )
        }

        fun minimizeTab(context: Context, tabId: Int) {
            if (!isRunning) return
            context.startService(
                Intent(context, FloatingBrowserService::class.java).apply {
                    action = ACTION_MINIMIZE_TAB
                    putExtra(EXTRA_TAB_ID, tabId)
                }
            )
        }

        fun closeTab(context: Context, tabId: Int) {
            if (!isRunning) return
            context.startService(
                Intent(context, FloatingBrowserService::class.java).apply {
                    action = ACTION_CLOSE_TAB
                    putExtra(EXTRA_TAB_ID, tabId)
                }
            )
        }

        fun closeAll(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, FloatingBrowserService::class.java).apply {
                    action = ACTION_CLOSE_ALL
                }
            )
        }
    }
}
