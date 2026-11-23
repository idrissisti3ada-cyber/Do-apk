package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.MyCodeApp
import com.example.domain.AppCaseMode
import com.example.domain.CodeExtractor
import com.example.domain.SourceApp
import java.util.concurrent.Executors

class CodeAccessibilityService : AccessibilityService() {

    private var lastWindowSignature: String = ""
    private var lastScannedNodesHash: Int = 0
    private var lastContentScanMillis: Long = 0L
    private var pendingContentPackage: String? = null
    private var hasPendingScan: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrExecutor = Executors.newSingleThreadExecutor()

    private val pendingScanRunnable = Runnable {
        hasPendingScan = false
        val pkg = pendingContentPackage ?: return@Runnable
        pendingContentPackage = null
        performScreenScan(pkg)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenOcrProcessor.warmup()
        val app = applicationContext as? MyCodeApp ?: return
        app.container.captureEngine.registerScreenBaselineProvider { mode ->
            extractCurrentWindowCodes(mode)
        }
        app.container.captureEngine.registerExternalBrowserAutoRedeemHandler { code, mode ->
            mainHandler.post {
                tryAutoRedeemInExternalBrowser(code, mode)
            }
        }
        app.container.captureEngine.registerScreenOcrHandler {
            triggerInstantScreenOcr()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return
        val pkg = evt.packageName?.toString() ?: return
        val sourceApp = SourceApp.fromPackageName(pkg) ?: return

        val app = applicationContext as? MyCodeApp ?: return
        val container = app.container

        val prefs = container.preferencesRepository.currentFast()
        if (!prefs.hasValidAccess() || !prefs.monitoringActive || !prefs.isAppEnabled(sourceApp)) {
            return
        }
        val mode = prefs.activeCaseMode

        // Fast path: check event.text directly if it carries a candidate code
        val eventTexts = evt.text
        if (!eventTexts.isNullOrEmpty()) {
            val directSegments = ArrayList<String>(eventTexts.size)
            for (cs in eventTexts) {
                if (cs != null && hasCandidateChars(cs, mode)) {
                    directSegments.add(cs.toString().trim())
                }
            }
            if (directSegments.isNotEmpty()) {
                container.captureEngine.processScreenNodes(
                    packageName = pkg,
                    senderTitle = "${sourceApp.displayName} screen",
                    nodeTexts = directSegments
                )
            }
        }

        if (evt.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = evt.className?.toString().orEmpty()
            val windowSignature = "$pkg|$className"
            if (windowSignature != lastWindowSignature && !className.contains("Toast", ignoreCase = true)) {
                lastWindowSignature = windowSignature
                // Never permanently blacklist codes on window switches; simply reset hash and scan cleanly
                lastScannedNodesHash = 0
            }
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastContentScanMillis
        if (evt.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && elapsed < CONTENT_SCAN_COOLDOWN_MS) {
            pendingContentPackage = pkg
            if (!hasPendingScan) {
                hasPendingScan = true
                mainHandler.postDelayed(pendingScanRunnable, (CONTENT_SCAN_COOLDOWN_MS - elapsed).coerceAtLeast(1L))
            }
            return
        }

        mainHandler.removeCallbacks(pendingScanRunnable)
        hasPendingScan = false
        pendingContentPackage = null
        performScreenScan(pkg)
    }

    private fun performScreenScan(pkg: String) {
        val sourceApp = SourceApp.fromPackageName(pkg) ?: return
        val app = applicationContext as? MyCodeApp ?: return
        val container = app.container
        val prefs = container.preferencesRepository.currentFast()
        if (!prefs.hasValidAccess() || !prefs.monitoringActive || !prefs.isAppEnabled(sourceApp)) return

        val mode = prefs.activeCaseMode
        lastContentScanMillis = System.currentTimeMillis()

        val nodeTexts = collectVisibleNodeTexts(mode)
        if (nodeTexts.isEmpty()) return

        // Include mode in hash so switching between MyCodeG7f and garenaCode never hits stale cache
        val nodesHash = (nodeTexts.hashCode() * 31) + mode.ordinal
        if (nodesHash == lastScannedNodesHash) return
        lastScannedNodesHash = nodesHash

        container.captureEngine.processScreenNodes(
            packageName = pkg,
            senderTitle = "${sourceApp.displayName} screen",
            nodeTexts = nodeTexts
        )
    }

    /**
     * When Chrome, Lemur Browser, or another external browser is open on shop2game.com
     * (MyCodeG7f) or reward.ff.garena.com (garenaCode), inserts the code, unfocuses the
     * typing bar if needed, and clicks Confirm / Confirmer.
     */
    private fun tryAutoRedeemInExternalBrowser(code: String, mode: AppCaseMode) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in EXTERNAL_BROWSER_PACKAGES) return

        val allNodes = ArrayList<AccessibilityNodeInfo>(160)
        collectAllNodes(root, allNodes, depth = 0)

        val isRedeemPage = allNodes.any { node ->
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            when (mode) {
                AppCaseMode.MYCODE_G7F -> {
                    t.contains("shop2game") || d.contains("shop2game") ||
                        t.contains("garena") || t.contains("redeem") || t.contains("confirmer")
                }
                AppCaseMode.GARENA_CODE -> {
                    t.contains("garena") || d.contains("garena") ||
                        t.contains("reward.ff") || t.contains("redeem") || t.contains("confirmer")
                }
            }
        }
        if (!isRedeemPage) return

        val editableInputs = allNodes.filter { node ->
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val isAddressBar = viewId.contains("url_bar") || viewId.contains("omnibox") || viewId.contains("search")
            node.isEditable && !isAddressBar
        }
        if (editableInputs.isEmpty()) return

        if (mode == AppCaseMode.GARENA_CODE && editableInputs.size >= 3 && code.length == 12) {
            val parts = listOf(code.substring(0, 4), code.substring(4, 8), code.substring(8, 12))
            for (idx in 0 until 3) {
                val box = editableInputs[idx]
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, parts[idx])
                }
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    box.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
                box.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            }
        } else {
            val inputNode = editableInputs.firstOrNull { node ->
                val hint = node.hintText?.toString()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                hint.contains("code") || hint.contains("redeem") ||
                    desc.contains("code") || desc.contains("redeem") ||
                    node.className?.toString()?.contains("EditText", ignoreCase = true) == true
            } ?: editableInputs.first()

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        }

        // Click a non-interactive text/header node so the typing bar loses focus cleanly
        val nonInteractiveNode = allNodes.firstOrNull { node ->
            val cls = node.className?.toString()?.lowercase().orEmpty()
            !node.isEditable && !cls.contains("button") &&
                node.isVisibleToUser &&
                node.text?.toString()?.isNotBlank() == true &&
                !node.text.toString().contains("confirm", ignoreCase = true)
        }
        nonInteractiveNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            for (hop in 0..3) {
                if (current == null) break
                if (current.isClickable && current.isEnabled) return current
                current = current.parent
            }
            return null
        }

        fun tryClickConfirm(retriesLeft: Int) {
            val refreshedRoot = rootInActiveWindow ?: return
            val refreshedNodes = ArrayList<AccessibilityNodeInfo>(200)
            collectAllNodes(refreshedRoot, refreshedNodes, depth = 0)
            val confirmCandidate = refreshedNodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
                val isLabel = text == "confirm" || text == "confirmer" || text == "تأكيد" ||
                    ((text.contains("confirm") || text.contains("confirmer")) && text.length <= 24)
                isLabel && findClickableTarget(node) != null
            }
            val clickable = confirmCandidate?.let { findClickableTarget(it) }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                (applicationContext as? MyCodeApp)?.container?.captureEngine?.notifyConfirmed()
            } else if (retriesLeft > 0) {
                val delayMs = when (retriesLeft) {
                    4 -> 40L
                    3 -> 70L
                    2 -> 110L
                    else -> 160L
                }
                mainHandler.postDelayed({ tryClickConfirm(retriesLeft - 1) }, delayMs)
            }
        }

        mainHandler.postDelayed({ tryClickConfirm(retriesLeft = 4) }, 30L)
    }

    private fun triggerInstantScreenOcr() {
        val app = applicationContext as? MyCodeApp ?: return
        val prefs = app.container.preferencesRepository.currentFast()
        if (!prefs.hasValidAccess()) return
        val mode = prefs.activeCaseMode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ocrExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                hwBuffer.close()
                                if (hwBitmap == null) {
                                    fallbackWindowOcr(mode)
                                    return
                                }
                                val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()
                                if (swBitmap == null) {
                                    fallbackWindowOcr(mode)
                                    return
                                }
                                ScreenOcrProcessor.extractCodesFromBitmap(
                                    sourceBitmap = swBitmap,
                                    mode = mode,
                                    recycleSource = true
                                ) { ocrCodes ->
                                    if (ocrCodes.isNotEmpty()) {
                                        app.container.captureEngine.submitManualOcrCodes(ocrCodes, mode)
                                    } else {
                                        fallbackWindowOcr(mode)
                                    }
                                }
                            } catch (_: Throwable) {
                                fallbackWindowOcr(mode)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            fallbackWindowOcr(mode)
                        }
                    }
                )
            } catch (_: Throwable) {
                fallbackWindowOcr(mode)
            }
        } else {
            fallbackWindowOcr(mode)
        }
    }

    private fun fallbackWindowOcr(mode: AppCaseMode) {
        val found = LinkedHashSet<String>()
        val windowList = try {
            windows
        } catch (_: Exception) {
            null
        }
        if (!windowList.isNullOrEmpty()) {
            for (window in windowList) {
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val segments = ArrayList<String>(12)
                traverseTextNodes(root, segments, mode, depth = 0, visitedCount = intArrayOf(0))
                for (seg in segments) {
                    found.addAll(CodeExtractor.extractCodes(seg, mode))
                }
            }
        } else {
            found.addAll(extractCurrentWindowCodes(mode))
        }

        if (found.isNotEmpty()) {
            (applicationContext as? MyCodeApp)?.container?.captureEngine?.submitManualOcrCodes(found.toList(), mode)
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hasPendingScan = false
        val app = applicationContext as? MyCodeApp
        app?.container?.captureEngine?.registerScreenBaselineProvider(null)
        app?.container?.captureEngine?.registerExternalBrowserAutoRedeemHandler(null)
        app?.container?.captureEngine?.registerScreenOcrHandler(null)
        super.onDestroy()
    }

    private fun extractCurrentWindowCodes(mode: AppCaseMode): List<String> {
        val nodeTexts = collectVisibleNodeTexts(mode)
        if (nodeTexts.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (segment in nodeTexts) {
            found.addAll(CodeExtractor.extractCodes(segment, mode))
        }
        return found.toList()
    }

    private fun collectVisibleNodeTexts(mode: AppCaseMode): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val segments = ArrayList<String>(12)
        traverseTextNodes(root, segments, mode, depth = 0, visitedCount = intArrayOf(0))
        return segments
    }

    private fun traverseTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        mode: AppCaseMode,
        depth: Int,
        visitedCount: IntArray
    ) {
        if (depth > MAX_TREE_DEPTH || visitedCount[0] >= MAX_NODES_PER_SCAN) return
        visitedCount[0]++

        try {
            // Skip invisible off-screen recycled containers deep in RecyclerViews so newest visible messages aren't crowded out
            if (depth >= 3 && !node.isVisibleToUser) {
                return
            }

            if (!node.isEditable) {
                val rawText = node.text
                if (rawText != null && hasCandidateChars(rawText, mode)) {
                    out.add(rawText.toString().trim())
                } else {
                    val rawDesc = node.contentDescription
                    if (rawDesc != null && hasCandidateChars(rawDesc, mode)) {
                        out.add(rawDesc.toString().trim())
                    }
                }
            }

            // Traverse bottom-up so newest chat messages at the bottom of the screen are scanned first
            val childCount = node.childCount
            for (i in childCount - 1 downTo 0) {
                if (visitedCount[0] >= MAX_NODES_PER_SCAN) break
                val child = node.getChild(i) ?: continue
                traverseTextNodes(child, out, mode, depth + 1, visitedCount)
            }
        } catch (_: Throwable) {
            // Guard against stale/recycled AccessibilityNodeInfo during fast UI updates
        }
    }

    private fun hasCandidateChars(cs: CharSequence, mode: AppCaseMode): Boolean {
        return when (mode) {
            AppCaseMode.MYCODE_G7F -> {
                if (cs.length < 15) return false
                var digits = 0
                for (i in 0 until cs.length) {
                    if (cs[i] in '0'..'9') {
                        digits++
                        if (digits >= 15) return true
                    }
                }
                false
            }
            AppCaseMode.GARENA_CODE -> {
                if (cs.length < 12) return false
                var upper = 0
                var digits = 0
                for (i in 0 until cs.length) {
                    val ch = cs[i]
                    if (ch in 'A'..'Z') {
                        upper++
                    } else if (ch in '0'..'9') {
                        digits++
                    }
                    if (upper > 0 && digits > 0 && upper + digits >= 12) return true
                }
                false
            }
        }
    }

    private fun collectAllNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_NODES_PER_SCAN) return
        out.add(node)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size >= MAX_NODES_PER_SCAN) break
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, out, depth + 1)
        }
    }

    companion object {
        private const val CONTENT_SCAN_COOLDOWN_MS = 18L
        private const val MAX_TREE_DEPTH = 32
        private const val MAX_NODES_PER_SCAN = 1500

        private val EXTERNAL_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.lemurbrowser.exts",
            "com.brave.browser",
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser"
        )
    }
}
