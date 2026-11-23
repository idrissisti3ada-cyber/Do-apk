package com.example.service

import android.content.Context
import com.example.data.preferences.PreferencesRepository
import com.example.domain.AppCaseMode
import com.example.domain.CodeExtractor
import com.example.domain.SourceApp
import com.example.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class CapturedCodeEvent(
    val codes: List<String>,
    val clipboardText: String,
    val sourceApp: SourceApp,
    val caseMode: AppCaseMode,
    val senderTitle: String,
    val timestampMillis: Long
)

class CodeCaptureEngine(
    private val appContext: Context,
    private val preferencesRepository: PreferencesRepository
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _capturedEvents = MutableSharedFlow<CapturedCodeEvent>(extraBufferCapacity = 64)
    val capturedEvents: SharedFlow<CapturedCodeEvent> = _capturedEvents.asSharedFlow()

    private val _confirmedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val confirmedEvents: SharedFlow<Unit> = _confirmedEvents.asSharedFlow()

    private val pendingClipboardCode = AtomicReference<String?>(null)
    private val baselineIgnoredKeys = ConcurrentHashMap.newKeySet<String>()
    private val recentCapturedTimestamps = ConcurrentHashMap<String, Long>()

    @Volatile
    private var screenBaselineProvider: ((AppCaseMode) -> List<String>)? = null

    @Volatile
    private var notificationBaselineProvider: ((AppCaseMode) -> List<String>)? = null

    @Volatile
    private var externalBrowserAutoRedeemHandler: ((String, AppCaseMode) -> Unit)? = null

    @Volatile
    private var screenOcrHandler: (() -> Unit)? = null

    @Volatile
    private var floatingBrowserFocusReleaser: (() -> Unit)? = null

    fun registerScreenBaselineProvider(provider: ((AppCaseMode) -> List<String>)?) {
        screenBaselineProvider = provider
    }

    fun registerNotificationBaselineProvider(provider: ((AppCaseMode) -> List<String>)?) {
        notificationBaselineProvider = provider
    }

    fun registerExternalBrowserAutoRedeemHandler(handler: ((String, AppCaseMode) -> Unit)?) {
        externalBrowserAutoRedeemHandler = handler
    }

    fun registerScreenOcrHandler(handler: (() -> Unit)?) {
        screenOcrHandler = handler
    }

    fun registerFloatingBrowserFocusReleaser(releaser: (() -> Unit)?) {
        floatingBrowserFocusReleaser = releaser
    }

    fun releaseFloatingBrowserFocus() {
        runCatching { floatingBrowserFocusReleaser?.invoke() }
    }

    fun requestInstantScreenOcr(): Boolean {
        val handler = screenOcrHandler ?: return false
        handler.invoke()
        return true
    }

    fun notifyConfirmed() {
        _confirmedEvents.tryEmit(Unit)
    }

    /**
     * Snapshots codes already visible on screen or in active notifications ONLY when the user
     * starts/resumes monitoring or switches modes so old messages aren't immediately copied.
     * Window state changes while already monitoring do NOT blacklist codes.
     */
    fun activateWaitingSession(modeOverride: AppCaseMode? = null) {
        val mode = modeOverride ?: preferencesRepository.currentFast().activeCaseMode
        baselineIgnoredKeys.clear()
        recentCapturedTimestamps.clear()
        runCatching {
            screenBaselineProvider?.invoke(mode)?.forEach { baselineIgnoredKeys.add(keyFor(mode, it)) }
        }
        runCatching {
            notificationBaselineProvider?.invoke(mode)?.forEach { baselineIgnoredKeys.add(keyFor(mode, it)) }
        }
    }

    fun processIncomingNotification(
        packageName: String,
        senderTitle: String,
        combinedText: String
    ) {
        processScreenNodes(
            packageName = packageName,
            senderTitle = senderTitle,
            nodeTexts = listOf(combinedText)
        )
    }

    fun processScreenNodes(
        packageName: String,
        senderTitle: String,
        nodeTexts: List<String>
    ) {
        val sourceApp = SourceApp.fromPackageName(packageName) ?: return
        if (nodeTexts.isEmpty()) return

        val prefs = preferencesRepository.currentFast()
        if (!prefs.hasValidAccess() || !prefs.monitoringActive || !prefs.isAppEnabled(sourceApp)) {
            return
        }
        val mode = prefs.activeCaseMode

        val extracted = LinkedHashSet<String>()
        for (segment in nodeTexts) {
            if (segment.isNotBlank()) {
                extracted.addAll(CodeExtractor.extractCodes(segment, mode))
            }
        }
        if (extracted.isEmpty()) return

        val nowMillis = System.currentTimeMillis()
        pruneExpiredRecentCodes(nowMillis)

        val newlyCaptured = ArrayList<String>(extracted.size)
        for (code in extracted) {
            val key = keyFor(mode, code)
            if (baselineIgnoredKeys.contains(key)) {
                continue
            }
            val lastSeen = recentCapturedTimestamps[key]
            if (lastSeen == null || (nowMillis - lastSeen) >= DUPLICATE_COOLDOWN_MS) {
                recentCapturedTimestamps[key] = nowMillis
                newlyCaptured.add(code)
            }
        }

        if (newlyCaptured.isEmpty()) return

        val clipboardPayload = newlyCaptured.joinToString(separator = "\n")
        pendingClipboardCode.set(clipboardPayload)

        _capturedEvents.tryEmit(
            CapturedCodeEvent(
                codes = newlyCaptured,
                clipboardText = clipboardPayload,
                sourceApp = sourceApp,
                caseMode = mode,
                senderTitle = senderTitle,
                timestampMillis = nowMillis
            )
        )

        if (prefs.autoRedeemShop2Game) {
            externalBrowserAutoRedeemHandler?.invoke(newlyCaptured.first(), mode)
        }

        ioScope.launch {
            ClipboardHelper.triggerFeedback(
                context = appContext,
                vibrate = prefs.vibrateOnCopy,
                sound = prefs.soundOnCopy
            )
        }
    }

    /**
     * Immediately copies & dispatches codes found via double-tap Screen OCR without
     * blocking on baseline-ignored lists or cooldown deduplication.
     */
    fun submitManualOcrCodes(
        rawCodes: List<String>,
        mode: AppCaseMode = preferencesRepository.currentFast().activeCaseMode
    ) {
        if (rawCodes.isEmpty()) return
        val cleanCodes = rawCodes.distinct()
        val clipboardPayload = cleanCodes.joinToString(separator = "\n")
        val nowMillis = System.currentTimeMillis()
        val prefs = preferencesRepository.currentFast()
        if (!prefs.hasValidAccess(nowMillis)) return

        for (code in cleanCodes) {
            val key = keyFor(mode, code)
            baselineIgnoredKeys.remove(key)
            recentCapturedTimestamps[key] = nowMillis
        }
        pendingClipboardCode.set(clipboardPayload)

        _capturedEvents.tryEmit(
            CapturedCodeEvent(
                codes = cleanCodes,
                clipboardText = clipboardPayload,
                sourceApp = SourceApp.OCR,
                caseMode = mode,
                senderTitle = "Screen OCR",
                timestampMillis = nowMillis
            )
        )

        if (prefs.autoRedeemShop2Game) {
            externalBrowserAutoRedeemHandler?.invoke(cleanCodes.first(), mode)
        }

        ioScope.launch {
            ClipboardHelper.triggerFeedback(
                context = appContext,
                vibrate = prefs.vibrateOnCopy,
                sound = prefs.soundOnCopy
            )
        }
    }

    private fun pruneExpiredRecentCodes(nowMillis: Long) {
        if (recentCapturedTimestamps.size < 24) return
        val iterator = recentCapturedTimestamps.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis - entry.value >= DUPLICATE_COOLDOWN_MS) {
                iterator.remove()
            }
        }
        if (recentCapturedTimestamps.size > 128) {
            recentCapturedTimestamps.clear()
        }
    }

    fun consumePendingClipboardCode(): String? {
        return pendingClipboardCode.getAndSet(null)
    }

    fun peekPendingClipboardCode(): String? = pendingClipboardCode.get()

    private fun keyFor(mode: AppCaseMode, code: String): String = "${mode.id}:$code"

    companion object {
        private const val DUPLICATE_COOLDOWN_MS = 5_000L
    }
}
