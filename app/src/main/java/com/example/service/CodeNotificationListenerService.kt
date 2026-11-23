package com.example.service

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.example.MyCodeApp
import com.example.domain.AppCaseMode
import com.example.domain.CodeExtractor
import com.example.domain.SourceApp

class CodeNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = applicationContext as? MyCodeApp ?: return
        app.container.captureEngine.registerNotificationBaselineProvider { mode ->
            extractActiveNotificationCodes(mode)
        }
    }

    override fun onListenerDisconnected() {
        val app = applicationContext as? MyCodeApp
        app?.container?.captureEngine?.registerNotificationBaselineProvider(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return

        // Only inspect notifications from WhatsApp, WhatsApp Business, or Instagram
        if (SourceApp.fromPackageName(pkg) == null) return

        val extracted = extractTextFromNotification(notification) ?: return
        val (senderTitle, segments) = extracted

        val app = applicationContext as? MyCodeApp ?: return
        app.container.captureEngine.processScreenNodes(
            packageName = pkg,
            senderTitle = senderTitle,
            nodeTexts = segments
        )
    }

    private fun extractActiveNotificationCodes(mode: AppCaseMode): List<String> {
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val found = LinkedHashSet<String>()
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (SourceApp.fromPackageName(pkg) == null) continue
            val (_, segments) = extractTextFromNotification(sbn.notification) ?: continue
            for (seg in segments) {
                found.addAll(CodeExtractor.extractCodes(seg, mode))
            }
        }
        return found.toList()
    }

    private fun extractTextFromNotification(notification: Notification?): Pair<String, List<String>>? {
        if (notification == null) return null
        val extras = notification.extras ?: Bundle.EMPTY
        val textParts = ArrayList<String>(8)

        fun addIfNotBlank(cs: CharSequence?) {
            val str = cs?.toString()?.trim()
            if (!str.isNullOrEmpty()) {
                textParts.add(str)
            }
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)

        addIfNotBlank(title)
        addIfNotBlank(conversationTitle)
        addIfNotBlank(extras.getCharSequence(Notification.EXTRA_TEXT))
        addIfNotBlank(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        addIfNotBlank(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        addIfNotBlank(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        addIfNotBlank(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        addIfNotBlank(notification.tickerText)

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            addIfNotBlank(line)
        }

        val parcelables = BundleCompat.getParcelableArray(
            extras,
            Notification.EXTRA_MESSAGES,
            Parcelable::class.java
        )
        parcelables?.forEach { item ->
            val msgBundle = item as? Bundle ?: return@forEach
            addIfNotBlank(msgBundle.getCharSequence("text"))
            addIfNotBlank(msgBundle.getCharSequence("sender"))
        }

        if (textParts.isEmpty()) return null
        val senderTitle = (conversationTitle ?: title ?: "").toString()
        return senderTitle to textParts
    }
}
