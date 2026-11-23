package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.domain.AppCaseMode
import com.example.domain.PageDistributionPlanner
import com.example.util.BrowserHelper
import com.example.util.ClipboardHelper
import com.example.util.PermissionHelper
import kotlin.math.abs

data class FloatingBrowserTabInfo(
    val id: Int,
    val url: String,
    val title: String,
    val profileName: String,
    val isMinimizedToBubble: Boolean,
    val isConfirmed: Boolean = false
)

class FloatingBrowserManager(
    private val context: Context,
    private val onTabsChanged: (List<FloatingBrowserTabInfo>) -> Unit,
    private val onAllTabsClosed: () -> Unit,
    private val onPageConfirmed: ((Int) -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pages = LinkedHashMap<Int, BrowserOverlayPage>()
    private var nextPageId = 1
    private var lastProcessedPageId: Int? = null

    fun openNewPage(
        initialUrl: String = BrowserHelper.DEFAULT_REDEEM_URL,
        browserProfilePackage: String? = null,
        browserProfileLabel: String = "Floating"
    ): Int? {
        if (!PermissionHelper.hasOverlayPermission(context)) return null
        val pageId = nextPageId++
        val offsetPx = ((pages.size % 5) * dp(18))
        val page = BrowserOverlayPage(
            id = pageId,
            initialUrl = BrowserHelper.normalizeUrl(initialUrl),
            profilePackage = browserProfilePackage,
            profileLabel = browserProfileLabel,
            initialOffsetPx = offsetPx
        )
        if (page.attach()) {
            pages[pageId] = page
            notifyTabsChanged()
            return pageId
        }
        return null
    }

    fun restoreOrFocusPage(pageId: Int) {
        val page = pages[pageId] ?: return
        if (page.isMinimized) {
            page.restoreFromBubble()
        } else {
            page.bringToFront()
        }
        notifyTabsChanged()
    }

    fun minimizePage(pageId: Int) {
        val page = pages[pageId] ?: return
        if (!page.isMinimized) {
            page.minimizeToBubble(snapRight = true)
            notifyTabsChanged()
        }
    }

    fun closePage(pageId: Int) {
        val page = pages.remove(pageId) ?: return
        page.detach()
        notifyTabsChanged()
        if (pages.isEmpty()) {
            lastProcessedPageId = null
            onAllTabsClosed()
        }
    }

    fun closeAllPages() {
        val all = pages.values.toList()
        pages.clear()
        lastProcessedPageId = null
        all.forEach { it.detach() }
        notifyTabsChanged()
        onAllTabsClosed()
    }

    fun releaseAllKeyboardFocus() {
        pages.values.forEach { it.releaseKeyboardFocus() }
    }

    fun hasRedeemPage(): Boolean = pages.values.any { it.isOnRedeemPage() }

    fun hasRedeemPageFor(mode: AppCaseMode): Boolean = when (mode) {
        AppCaseMode.MYCODE_G7F -> pages.values.any { it.isOnShop2GamePage() }
        AppCaseMode.GARENA_CODE -> pages.values.any { it.isOnGarenaRewardPage() }
    }

    /**
     * Distributes copied [codes] across opened redeem pages matching [mode]:
     * - More codes than pages -> processes 1 code per page until opened pages end, then stops.
     * - Fewer codes than pages -> processes only [codes.size] pages and stops.
     * - Copied one by one -> advances round-robin so each next code runs in a different page.
     */
    fun autoRedeemCodesAcrossPages(
        codes: List<String>,
        mode: AppCaseMode = AppCaseMode.GARENA_CODE
    ) {
        if (codes.isEmpty() || pages.isEmpty()) return
        val eligible = pages.values
            .filter {
                when (mode) {
                    AppCaseMode.MYCODE_G7F -> it.isOnShop2GamePage()
                    AppCaseMode.GARENA_CODE -> it.isOnGarenaRewardPage()
                }
            }
            .map { it.id to it }
        if (eligible.isEmpty()) return

        val plan = PageDistributionPlanner.distribute(
            codes = codes,
            eligiblePages = eligible,
            lastProcessedPageId = lastProcessedPageId
        )
        lastProcessedPageId = plan.nextLastProcessedPageId

        for (allocation in plan.allocations) {
            allocation.page.tryAutoPasteAndConfirmIfRedeemPage(allocation.code, mode)
        }
    }

    fun currentTabs(): List<FloatingBrowserTabInfo> {
        return pages.values.map { page ->
            FloatingBrowserTabInfo(
                id = page.id,
                url = page.currentUrl,
                title = page.currentTitle,
                profileName = page.profileLabel,
                isMinimizedToBubble = page.isMinimized,
                isConfirmed = page.isConfirmed
            )
        }
    }

    private fun notifyTabsChanged() {
        onTabsChanged(currentTabs())
    }

    private inner class BrowserOverlayPage(
        val id: Int,
        initialUrl: String,
        private val profilePackage: String?,
        val profileLabel: String,
        initialOffsetPx: Int
    ) {
        var currentUrl: String = initialUrl
        var currentTitle: String = initialUrl
        var isMinimized: Boolean = false
            private set
        var isConfirmed: Boolean = false
            private set

        private var isSnappedRight: Boolean = true
        private val screenWidth = context.resources.displayMetrics.widthPixels
        private val screenHeight = context.resources.displayMetrics.heightPixels

        private var savedWidth = (screenWidth * 0.84f).toInt().coerceIn(dp(250), dp(420))
        private var savedHeight = (screenHeight * 0.50f).toInt().coerceIn(dp(280), dp(560))
        private var savedX = ((screenWidth - savedWidth) / 2 + initialOffsetPx)
            .coerceIn(dp(8), (screenWidth - savedWidth - dp(8)).coerceAtLeast(dp(8)))
        private var savedY = (dp(88) + initialOffsetPx)
            .coerceIn(dp(36), (screenHeight - savedHeight - dp(48)).coerceAtLeast(dp(36)))

        private val bubbleSizePx = dp(52)
        private val borderMarginPx = dp(8)
        private val borderSnapThresholdPx = dp(24)

        private var rootContainer: FrameLayout? = null
        private var windowCard: LinearLayout? = null
        private var cardBackground: GradientDrawable? = null
        private var bubbleOrb: FrameLayout? = null
        private var bubbleOrbText: TextView? = null
        private var orbBackground: GradientDrawable? = null
        private var urlInput: EditText? = null
        private var titleBadge: TextView? = null
        private var webView: WebView? = null
        private var layoutParams: WindowManager.LayoutParams? = null
        private var attached = false

        @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
        fun attach(): Boolean {
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                savedWidth,
                savedHeight,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            val root = object : FrameLayout(context) {
                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        enableKeyboardFocus()
                    } else if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        releaseKeyboardFocus()
                    }
                    return super.onInterceptTouchEvent(ev)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        releaseKeyboardFocus()
                    }
                    return super.onTouchEvent(event)
                }
            }

            // 1. Expanded resizable browser card
            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
            cardBackground = cardBg

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                elevation = dp(14).toFloat()
                background = cardBg
                clipToOutline = true
            }

            // Top drag handle & window controls bar
            val topBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#111827"))
                setPadding(dp(10), dp(6), dp(6), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38)
                )
            }

            val pageTitle = TextView(context).apply {
                text = "#$id • $profileLabel"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT_BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleBadge = pageTitle

            fun barActionBtn(label: String, tintHex: String = "#E2E8F0", onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = label
                    setTextColor(Color.parseColor(tintHex))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(28))
                    setOnClickListener { onClick() }
                }
            }

            val backBtn = barActionBtn("‹") {
                val wv = webView ?: return@barActionBtn
                if (wv.canGoBack()) wv.goBack()
            }
            val reloadBtn = barActionBtn("↻") {
                webView?.reload()
            }
            val addTabBtn = barActionBtn("+", "#34D399") {
                openNewPage(currentUrl, profilePackage, profileLabel)
            }
            val minBtn = barActionBtn("—", "#FBBF24") {
                minimizeToBubble(snapRight = (params.x + savedWidth / 2) >= screenWidth / 2)
                notifyTabsChanged()
            }
            val closeBtn = barActionBtn("✕", "#F87171") {
                closePage(id)
            }

            topBar.addView(pageTitle)
            topBar.addView(backBtn)
            topBar.addView(reloadBtn)
            topBar.addView(addTabBtn)
            topBar.addView(minBtn)
            topBar.addView(closeBtn)

            // Dragging top bar moves window; dragging to screen border turns it into a bubble!
            setupWindowDragListener(topBar, params)

            // Compact URL & shop2game quick bar
            val urlBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#1E293B"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34)
                )
            }

            val editUrl = EditText(context).apply {
                setText(currentUrl)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = "Enter URL..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_GO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#0F172A"))
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
                setOnTouchListener { _, _ ->
                    enableKeyboardFocus()
                    false
                }
                setOnEditorActionListener { _, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_GO ||
                        (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                    ) {
                        isConfirmed = false
                        updateConfirmedAppearance()
                        val nextUrl = BrowserHelper.normalizeUrl(text.toString())
                        webView?.loadUrl(nextUrl)
                        releaseKeyboardFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            urlInput = editUrl

            val shopQuickBtn = TextView(context).apply {
                text = "shop2game"
                setTextColor(Color.parseColor("#022C22"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#34D399"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    isConfirmed = false
                    updateConfirmedAppearance()
                    webView?.loadUrl(BrowserHelper.SHOP2GAME_URL)
                }
            }

            val redeemQuickBtn = TextView(context).apply {
                text = "reward.ff"
                setTextColor(Color.parseColor("#082F49"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#38BDF8"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    isConfirmed = false
                    updateConfirmedAppearance()
                    webView?.loadUrl(BrowserHelper.GARENA_REWARD_URL)
                }
            }

            val pasteFillBtn = TextView(context).apply {
                text = "Fill"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#0284C7"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    val clip = ClipboardHelper.getCurrentClipboardText(context)?.lines()?.firstOrNull()?.trim()
                    if (!clip.isNullOrEmpty()) {
                        val inferredMode = if (isOnGarenaRewardPage() || clip.length == 12) {
                            AppCaseMode.GARENA_CODE
                        } else {
                            AppCaseMode.MYCODE_G7F
                        }
                        injectAutoPasteAndConfirmJs(clip, inferredMode)
                    }
                }
            }

            urlBar.addView(editUrl)
            urlBar.addView(shopQuickBtn)
            urlBar.addView(redeemQuickBtn)
            urlBar.addView(pasteFillBtn)

            // WebView + corner resize handle overlay
            val webContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val wv = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = BrowserHelper.userAgentForProfile(profilePackage)
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(GarenaJsBridge(), "MyCodeBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return !(url.startsWith("http://") || url.startsWith("https://"))
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!url.isNullOrBlank()) {
                            currentUrl = url
                            urlInput?.setText(url)
                            updateTitleText()
                            notifyTabsChanged()
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        if (!title.isNullOrBlank()) {
                            currentTitle = title
                            updateTitleText()
                            notifyTabsChanged()
                        }
                    }
                }

                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        enableKeyboardFocus()
                    }
                    false
                }

                loadUrl(currentUrl)
            }
            webView = wv

            // Bottom-right resize corner handle
            val resizeHandle = TextView(context).apply {
                text = "◢"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#CC0F172A"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END)
            }
            setupResizeHandleListener(resizeHandle, params)

            webContainer.addView(wv)
            webContainer.addView(resizeHandle)

            card.addView(topBar)
            card.addView(urlBar)
            card.addView(webContainer)

            // 2. Minimized Border Bubble view (keeps WebView alive in card while hidden)
            val orbBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0284C7"))
                setStroke(dp(3), Color.parseColor("#7DD3FC"))
            }
            orbBackground = orbBg

            val orb = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
                visibility = View.GONE
                elevation = dp(12).toFloat()
                background = orbBg
            }

            val orbText = TextView(context).apply {
                text = "W$id"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            bubbleOrbText = orbText
            orb.addView(orbText)
            setupBubbleTouchListener(orb, params)

            root.addView(card)
            root.addView(orb)

            return try {
                windowManager.addView(root, params)
                rootContainer = root
                windowCard = card
                bubbleOrb = orb
                layoutParams = params
                attached = true
                true
            } catch (_: Exception) {
                false
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupWindowDragListener(handle: View, params: WindowManager.LayoutParams) {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        // Allow dragging slightly past left/right edge so user can drop on border to bubble
                        val minX = -savedWidth / 2
                        val maxX = screenWidth - savedWidth / 2
                        val maxY = (screenHeight - dp(64)).coerceAtLeast(dp(48))
                        params.x = (startX + dx).coerceIn(minX, maxX)
                        params.y = (startY + dy).coerceIn(dp(28), maxY)
                        savedX = params.x
                        savedY = params.y
                        safeUpdateLayout(params)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val nearLeftBorder = event.rawX <= borderSnapThresholdPx || params.x <= -savedWidth / 4
                        val nearRightBorder = event.rawX >= (screenWidth - borderSnapThresholdPx) ||
                            (params.x + savedWidth) >= (screenWidth + savedWidth / 4)

                        if (nearLeftBorder) {
                            minimizeToBubble(snapRight = false)
                            notifyTabsChanged()
                        } else if (nearRightBorder) {
                            minimizeToBubble(snapRight = true)
                            notifyTabsChanged()
                        } else {
                            // Keep cleanly inside screen
                            savedX = params.x.coerceIn(dp(4), (screenWidth - savedWidth - dp(4)).coerceAtLeast(dp(4)))
                            params.x = savedX
                            safeUpdateLayout(params)
                        }
                        true
                    }

                    else -> false
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupResizeHandleListener(handle: View, params: WindowManager.LayoutParams) {
            var startW = 0
            var startH = 0
            var touchX = 0f
            var touchY = 0f

            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startW = params.width
                        startH = params.height
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        val maxW = (screenWidth - dp(16)).coerceAtLeast(dp(240))
                        val maxH = (screenHeight - dp(72)).coerceAtLeast(dp(260))
                        savedWidth = (startW + dx).coerceIn(dp(220), maxW)
                        savedHeight = (startH + dy).coerceIn(dp(240), maxH)
                        params.width = savedWidth
                        params.height = savedHeight
                        safeUpdateLayout(params)
                        true
                    }

                    else -> true
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupBubbleTouchListener(orb: View, params: WindowManager.LayoutParams) {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var dragging = false
            val slop = dp(8)

            orb.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > slop || abs(dy) > slop) {
                            dragging = true
                        }
                        if (dragging) {
                            val maxX = (screenWidth - bubbleSizePx - borderMarginPx).coerceAtLeast(borderMarginPx)
                            val maxY = (screenHeight - bubbleSizePx - dp(32)).coerceAtLeast(dp(36))
                            params.x = (startX + dx).coerceIn(borderMarginPx, maxX)
                            params.y = (startY + dy).coerceIn(dp(36), maxY)
                            safeUpdateLayout(params)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            restoreFromBubble()
                            notifyTabsChanged()
                        } else {
                            val center = params.x + bubbleSizePx / 2
                            isSnappedRight = center >= screenWidth / 2
                            params.x = if (isSnappedRight) {
                                (screenWidth - bubbleSizePx - borderMarginPx).coerceAtLeast(borderMarginPx)
                            } else {
                                borderMarginPx
                            }
                            savedY = params.y
                            safeUpdateLayout(params)
                        }
                        true
                    }

                    else -> false
                }
            }
        }

        fun minimizeToBubble(snapRight: Boolean) {
            if (!attached || isMinimized) return
            val params = layoutParams ?: return
            isMinimized = true
            isSnappedRight = snapRight
            releaseKeyboardFocus()

            // Save current window position before shrinking to border bubble
            savedX = params.x.coerceIn(dp(6), (screenWidth - savedWidth - dp(6)).coerceAtLeast(dp(6)))
            savedY = params.y.coerceIn(dp(36), (screenHeight - dp(120)).coerceAtLeast(dp(36)))

            windowCard?.visibility = View.GONE
            bubbleOrb?.visibility = View.VISIBLE

            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = if (snapRight) {
                (screenWidth - bubbleSizePx - borderMarginPx).coerceAtLeast(borderMarginPx)
            } else {
                borderMarginPx
            }
            params.y = savedY
            safeUpdateLayout(params)
        }

        fun restoreFromBubble() {
            if (!attached || !isMinimized) return
            val params = layoutParams ?: return
            isMinimized = false

            bubbleOrb?.visibility = View.GONE
            windowCard?.visibility = View.VISIBLE

            params.width = savedWidth
            params.height = savedHeight
            params.x = if (isSnappedRight) {
                (screenWidth - savedWidth - dp(8)).coerceAtLeast(dp(8))
            } else {
                dp(8)
            }
            params.y = savedY.coerceIn(dp(36), (screenHeight - savedHeight - dp(48)).coerceAtLeast(dp(36)))
            safeUpdateLayout(params)
        }

        fun bringToFront() {
            val root = rootContainer ?: return
            val params = layoutParams ?: return
            if (!attached) return
            try {
                windowManager.removeView(root)
                windowManager.addView(root, params)
            } catch (_: Exception) {
            }
        }

        fun isOnShop2GamePage(): Boolean {
            val url = webView?.url ?: currentUrl
            return url.contains("shop2game", ignoreCase = true)
        }

        fun isOnGarenaRewardPage(): Boolean {
            val url = webView?.url ?: currentUrl
            return url.contains("ff.garena.com", ignoreCase = true) ||
                url.contains("reward.ff.garena", ignoreCase = true)
        }

        fun isOnRedeemPage(): Boolean = isOnShop2GamePage() || isOnGarenaRewardPage()

        fun tryAutoPasteAndConfirmIfRedeemPage(code: String, mode: AppCaseMode) {
            val matches = when (mode) {
                AppCaseMode.MYCODE_G7F -> isOnShop2GamePage()
                AppCaseMode.GARENA_CODE -> isOnGarenaRewardPage()
            }
            if (!matches) return
            injectAutoPasteAndConfirmJs(code, mode)
        }

        private inner class GarenaJsBridge {
            @JavascriptInterface
            fun onConfirmed() {
                mainHandler.post {
                    if (!attached) return@post
                    isConfirmed = true
                    releaseKeyboardFocus()
                    updateConfirmedAppearance()
                    notifyTabsChanged()
                    onPageConfirmed?.invoke(id)
                }
            }
        }

        private fun injectAutoPasteAndConfirmJs(code: String, mode: AppCaseMode) {
            val wv = webView ?: return
            if (isConfirmed) {
                isConfirmed = false
                updateConfirmedAppearance()
            }
            val escapedCode = code.replace("\\", "\\\\").replace("'", "\\'")
            val isGarenaSplit = mode == AppCaseMode.GARENA_CODE
            val js = """
                (function() {
                    var code = '$escapedCode';
                    var allowSplit = $isGarenaSplit;
                    var allInputs = document.querySelectorAll('input');
                    var visibleInputs = [];
                    for (var i = 0; i < allInputs.length; i++) {
                        var el = allInputs[i];
                        var type = (el.type || 'text').toLowerCase();
                        if (el.disabled || el.readOnly || (type !== 'text' && type !== 'tel' && type !== 'number' && type !== 'search' && type !== '')) continue;
                        var style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden') continue;
                        visibleInputs.push(el);
                    }
                    if (visibleInputs.length === 0) return 'no_input';

                    var setterDesc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    function setInputVal(el, val) {
                        el.focus();
                        if (el._valueTracker && el._valueTracker.setValue) {
                            try { el._valueTracker.setValue(''); } catch (e) {}
                        }
                        if (setterDesc && setterDesc.set) {
                            setterDesc.set.call(el, val);
                        } else {
                            el.value = val;
                        }
                        try {
                            el.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true }));
                        } catch (e) {}
                        try {
                            el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: val }));
                        } catch (e) {
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                    }

                    // Support Garena's 3 separate 4-char input boxes OR single input box
                    var fourCharInputs = visibleInputs.filter(function(el) {
                        return el.maxLength === 4 || (visibleInputs.length === 3);
                    });
                    var filledInputs = [];
                    if (allowSplit && code.length === 12 && fourCharInputs.length >= 3) {
                        var parts = [code.slice(0, 4), code.slice(4, 8), code.slice(8, 12)];
                        for (var k = 0; k < 3; k++) {
                            setInputVal(fourCharInputs[k], parts[k]);
                            filledInputs.push(fourCharInputs[k]);
                        }
                    } else {
                        var target = visibleInputs.find(function(el) {
                            var meta = ((el.placeholder || '') + ' ' + (el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                            return /redeem|code|serial|voucher|gift|pin|key|changer/.test(meta);
                        }) || visibleInputs[0];
                        setInputVal(target, code);
                        filledInputs.push(target);
                    }

                    // 1) Blur inputs & click a safe non-interactive spot on the page so the typing bar loses focus
                    for (var m = 0; m < filledInputs.length; m++) {
                        filledInputs[m].blur();
                        filledInputs[m].dispatchEvent(new FocusEvent('blur', { bubbles: true }));
                        filledInputs[m].dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
                    }
                    if (document.activeElement && document.activeElement.blur) {
                        document.activeElement.blur();
                    }
                    var safeSpot = null;
                    var spotCandidates = document.querySelectorAll('h1, h2, h3, p, header, main, section, .title, div');
                    for (var s = 0; s < spotCandidates.length; s++) {
                        var cand = spotCandidates[s];
                        var rect = cand.getBoundingClientRect ? cand.getBoundingClientRect() : null;
                        if (!rect || rect.width < 20 || rect.height < 12) continue;
                        if (cand.closest('button, a, input, textarea, select, [role="button"], [class*="confirm"]')) continue;
                        if (cand.querySelector('button, a, input, [role="button"], [class*="confirm"]')) continue;
                        var txt = (cand.innerText || '').trim().toLowerCase();
                        if (txt.indexOf('confirm') !== -1 || txt.indexOf('logout') !== -1 || txt.indexOf('login') !== -1) continue;
                        safeSpot = cand;
                        break;
                    }
                    if (!safeSpot) safeSpot = document.body;
                    if (safeSpot) {
                        try {
                            safeSpot.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
                            safeSpot.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                            safeSpot.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
                            safeSpot.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                            safeSpot.click();
                        } catch (e) {}
                    }

                    function triggerElementClick(el) {
                        var container = el.closest ? (el.closest('button, [role="button"], a, [class*="confirm"], [class*="btn"]') || el) : el;
                        var targets = container !== el ? [el, container] : [el];
                        for (var t = 0; t < targets.length; t++) {
                            var node = targets[t];
                            try {
                                node.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
                            } catch (e) {}
                            try {
                                node.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                            } catch (e) {}
                            try {
                                node.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
                            } catch (e) {}
                            try {
                                node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                            } catch (e) {}
                            try {
                                node.click();
                            } catch (e) {}
                        }
                    }

                    function isVisibleEnabled(el) {
                        if (!el || el.disabled) return false;
                        if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') return false;
                        var cls = (typeof el.className === 'string' ? el.className : '').toLowerCase();
                        if (cls.indexOf('disabled') !== -1) return false;
                        var st = window.getComputedStyle(el);
                        if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                        var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                        if (r && (r.width < 8 || r.height < 8)) return false;
                        return true;
                    }

                    function clickConfirmNow() {
                        var exactBtn = null;
                        var partialBtn = null;
                        var customExact = null;
                        var customClassMatch = null;

                        var primarySelectors = document.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"], a');
                        for (var j = 0; j < primarySelectors.length; j++) {
                            var btn = primarySelectors[j];
                            if (!isVisibleEnabled(btn)) continue;
                            var label = ((btn.innerText || btn.textContent || btn.value || btn.getAttribute('aria-label') || '')).trim().toLowerCase();
                            if (label === 'confirm' || label === 'confirmer' || label === 'تأكيد') {
                                exactBtn = btn;
                                break;
                            }
                            if (!partialBtn && (label.indexOf('confirm') !== -1 || label.indexOf('confirmer') !== -1 || label.indexOf('تأكيد') !== -1)) {
                                partialBtn = btn;
                            }
                        }

                        var chosen = exactBtn || partialBtn;
                        if (!chosen) {
                            // Garena reward.ff often renders Confirm/Confirmer as <div class="confirm-btn"> or <span>Confirm</span>
                            var allBlocks = document.querySelectorAll('div, span, p, label');
                            for (var d = 0; d < allBlocks.length; d++) {
                                var node = allBlocks[d];
                                if (!isVisibleEnabled(node)) continue;
                                var cls = (typeof node.className === 'string' ? node.className : '').toLowerCase();
                                var txt = ((node.innerText || node.textContent || '')).trim().toLowerCase();
                                if (txt.length === 0 || txt.length > 28) continue;
                                if (txt === 'confirm' || txt === 'confirmer' || txt === 'تأكيد') {
                                    customExact = node;
                                    if (node.children && node.children.length === 0) break;
                                } else if (!customClassMatch && (cls.indexOf('confirm') !== -1 || txt.indexOf('confirm') !== -1 || txt.indexOf('confirmer') !== -1)) {
                                    customClassMatch = node;
                                }
                            }
                            chosen = customExact || customClassMatch;
                        }

                        if (chosen) {
                            triggerElementClick(chosen);
                            if (window.MyCodeBridge) window.MyCodeBridge.onConfirmed();
                            return true;
                        }
                        return false;
                    }

                    var retryDelays = [30, 90, 170, 280, 450];
                    var attemptIdx = 0;
                    function scheduleConfirmAttempt() {
                        if (clickConfirmNow()) return;
                        if (attemptIdx >= retryDelays.length) return;
                        var waitMs = retryDelays[attemptIdx++];
                        setTimeout(scheduleConfirmAttempt, waitMs);
                    }
                    setTimeout(scheduleConfirmAttempt, 25);
                    return 'filled';
                })();
            """.trimIndent()
            wv.clearFocus()
            releaseKeyboardFocus()
            wv.evaluateJavascript(js, null)
        }

        private fun enableKeyboardFocus() {
            val params = layoutParams ?: return
            if (!attached || isMinimized) return
            if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                safeUpdateLayout(params)
            }
        }

        fun releaseKeyboardFocus() {
            val params = layoutParams ?: return
            if (!attached) return
            if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                safeUpdateLayout(params)
            }
        }

        private fun updateTitleText() {
            val host = try {
                android.net.Uri.parse(currentUrl).host?.removePrefix("www.") ?: profileLabel
            } catch (_: Exception) {
                profileLabel
            }
            titleBadge?.text = if (isConfirmed) "#$id • $host ✅" else "#$id • $host"
        }

        private fun updateConfirmedAppearance() {
            updateTitleText()
            if (isConfirmed) {
                titleBadge?.setTextColor(Color.parseColor("#10B981"))
                cardBackground?.setStroke(dp(2), Color.parseColor("#10B981"))
                orbBackground?.apply {
                    setColor(Color.parseColor("#059669"))
                    setStroke(dp(3), Color.parseColor("#6EE7B7"))
                }
                bubbleOrbText?.text = "W$id ✅"
            } else {
                titleBadge?.setTextColor(Color.parseColor("#38BDF8"))
                cardBackground?.setStroke(dp(2), Color.parseColor("#38BDF8"))
                orbBackground?.apply {
                    setColor(Color.parseColor("#0284C7"))
                    setStroke(dp(3), Color.parseColor("#7DD3FC"))
                }
                bubbleOrbText?.text = "W$id"
            }
        }

        private fun safeUpdateLayout(params: WindowManager.LayoutParams) {
            val root = rootContainer ?: return
            if (!attached) return
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }

        fun detach() {
            if (!attached) return
            attached = false
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("MyCodeBridge")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
            rootContainer?.let { root ->
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {
                }
            }
            rootContainer = null
            windowCard = null
            cardBackground = null
            bubbleOrb = null
            bubbleOrbText = null
            orbBackground = null
            layoutParams = null
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
