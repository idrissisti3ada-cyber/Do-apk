package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.MyCodeApp
import com.example.R
import com.example.data.preferences.MonitorPreferences
import com.example.domain.AppCaseMode
import com.example.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CodeMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bubbleManager: FloatingBubbleManager
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var confirmJob: Job? = null
    private var expiryJob: Job? = null
    private var unlockReceiverRegistered = false

    private val appContainer by lazy { (application as MyCodeApp).container }

    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                val pending = appContainer.captureEngine.peekPendingClipboardCode() ?: return
                bubbleManager.copyViaOverlayFocus(pending)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        ScreenOcrProcessor.warmup()

        bubbleManager = FloatingBubbleManager(
            context = this,
            onToggleMonitoring = {
                serviceScope.launch {
                    val current = appContainer.preferencesRepository.current()
                    val nextActive = !current.monitoringActive
                    if (nextActive) {
                        appContainer.captureEngine.activateWaitingSession(current.activeCaseMode)
                    }
                    appContainer.preferencesRepository.setMonitoringActive(nextActive)
                    ClipboardHelper.vibrateShort(this@CodeMonitorService, 30L)
                }
            },
            onDoubleTapOcr = {
                val started = appContainer.captureEngine.requestInstantScreenOcr()
                if (!started) {
                    Toast.makeText(
                        this@CodeMonitorService,
                        "Enable Accessibility permission for Screen OCR",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onBeforeRequestFocus = {
                appContainer.captureEngine.releaseFloatingBrowserFocus()
            },
            onClipboardSynced = {
                appContainer.captureEngine.consumePendingClipboardCode()
            }
        )

        registerScreenUnlockReceiver()
        observeStateAndEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MONITORING -> {
                serviceScope.launch {
                    val current = appContainer.preferencesRepository.current()
                    val nextActive = !current.monitoringActive
                    if (nextActive) {
                        appContainer.captureEngine.activateWaitingSession(current.activeCaseMode)
                    }
                    appContainer.preferencesRepository.setMonitoringActive(nextActive)
                }
            }

            ACTION_SWITCH_CASE -> {
                serviceScope.launch {
                    val current = appContainer.preferencesRepository.current()
                    val nextMode = when (current.activeCaseMode) {
                        AppCaseMode.MYCODE_G7F -> AppCaseMode.GARENA_CODE
                        AppCaseMode.GARENA_CODE -> AppCaseMode.MYCODE_G7F
                    }
                    appContainer.preferencesRepository.setActiveCaseMode(nextMode)
                    appContainer.captureEngine.activateWaitingSession(nextMode)
                }
            }

            ACTION_STOP_SERVICE -> {
                bubbleManager.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        serviceScope.launch {
            val prefs = appContainer.preferencesRepository.current()
            val notif = buildNotification(prefs)
            promoteToForeground(notif)
        }

        return START_STICKY
    }

    private fun observeStateAndEvents() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            appContainer.preferencesRepository.preferencesFlow.collect { prefs ->
                if (!prefs.hasValidAccess()) {
                    expiryJob?.cancel()
                    bubbleManager.hide()
                    FloatingBrowserService.closeAll(this@CodeMonitorService)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                scheduleExpiryCheck(prefs)

                if (!prefs.hasAnyAppEnabled) {
                    bubbleManager.hide()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                promoteToForeground(buildNotification(prefs))
                bubbleManager.showOrUpdate(
                    active = prefs.monitoringActive,
                    caseMode = prefs.activeCaseMode
                )
            }
        }

        eventJob?.cancel()
        eventJob = serviceScope.launch {
            appContainer.captureEngine.capturedEvents.collect { event ->
                val showImmediate = !appContainer.preferencesRepository.currentFast().autoRedeemShop2Game ||
                    !FloatingBrowserService.hasActiveRedeemPageFor(event.caseMode)
                bubbleManager.copyInstant(
                    clipboardText = event.clipboardText,
                    showCheckImmediately = showImmediate
                )
            }
        }

        confirmJob?.cancel()
        confirmJob = serviceScope.launch {
            appContainer.captureEngine.confirmedEvents.collect {
                bubbleManager.showCheckmarkBriefly()
            }
        }
    }

    private fun scheduleExpiryCheck(prefs: MonitorPreferences) {
        expiryJob?.cancel()
        if (!prefs.isUnlocked || prefs.isAdmin || prefs.isUnlimitedLicense) return
        val expiry = prefs.expiresAtMillis ?: return

        expiryJob = serviceScope.launch {
            val remaining = expiry - System.currentTimeMillis()
            if (remaining > 0L) {
                delay(remaining)
            }
            appContainer.preferencesRepository.lockSession(expired = true)
            bubbleManager.hide()
            FloatingBrowserService.closeAll(this@CodeMonitorService)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

    private fun buildNotification(prefs: MonitorPreferences): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CodeMonitorService::class.java).apply {
                action = ACTION_TOGGLE_MONITORING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchCaseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CodeMonitorService::class.java).apply {
                action = ACTION_SWITCH_CASE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (prefs.activeCaseMode) {
            AppCaseMode.MYCODE_G7F -> "MyCodeG7f (15-16 Digits)"
            AppCaseMode.GARENA_CODE -> "garenaCode (12-Char)"
        }

        val watchedApps = buildList {
            if (prefs.whatsAppEnabled) add("WhatsApp")
            if (prefs.instagramEnabled) add("Instagram")
        }.joinToString(" & ").ifEmpty { "None" }

        val title = if (prefs.monitoringActive) {
            getString(R.string.notif_title_active)
        } else {
            getString(R.string.notif_title_paused)
        }

        val contentText = "$modeLabel • Watching $watchedApps • Double-tap OCR"
        val toggleLabel = if (prefs.monitoringActive) {
            getString(R.string.notif_action_pause)
        } else {
            getString(R.string.notif_action_resume)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_shield)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, getString(R.string.notif_action_switch_case), switchCaseIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerScreenUnlockReceiver() {
        if (unlockReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this,
            screenUnlockReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        unlockReceiverRegistered = true
    }

    override fun onDestroy() {
        isRunning = false
        if (unlockReceiverRegistered) {
            try {
                unregisterReceiver(screenUnlockReceiver)
            } catch (_: Exception) {
            }
            unlockReceiverRegistered = false
        }
        bubbleManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "mycodeg7f_monitor_channel"
        const val NOTIFICATION_ID = 7071
        const val ACTION_SYNC_STATE = "com.example.ACTION_SYNC_STATE"
        const val ACTION_TOGGLE_MONITORING = "com.example.ACTION_TOGGLE_MONITORING"
        const val ACTION_SWITCH_CASE = "com.example.ACTION_SWITCH_CASE"
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE"

        @Volatile
        private var isRunning = false

        fun startOrSync(context: Context) {
            val intent = Intent(context, CodeMonitorService::class.java).apply {
                action = ACTION_SYNC_STATE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, CodeMonitorService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
