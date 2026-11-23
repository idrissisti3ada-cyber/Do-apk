package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.AppCaseMode
import com.example.util.ClipboardHelper
import com.example.util.PermissionHelper
import kotlin.math.abs

class FloatingBubbleManager(
    private val context: Context,
    private val onToggleMonitoring: () -> Unit,
    private val onDoubleTapOcr: () -> Unit = {},
    private val onBeforeRequestFocus: () -> Unit = {},
    private val onClipboardSynced: (String) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var collapsedOrb: FrameLayout? = null
    private var orbIcon: ImageView? = null
    private var orbCheckText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isActive = true
    private var activeCaseMode = AppCaseMode.MYCODE_G7F
    private var isSnappedRight = true
    private var lastTapUpMillis = 0L

    private var activeFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var activeFallbackRunnable: Runnable? = null

    private val bubbleSizePx = dp(52)
    private val edgeMarginPx = dp(10)

    private val clearCheckRunnable = Runnable {
        orbCheckText?.visibility = View.GONE
        orbIcon?.visibility = View.VISIBLE
    }

    private val pendingSingleTapRunnable = Runnable {
        onToggleMonitoring()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOrUpdate(active: Boolean, caseMode: AppCaseMode = activeCaseMode) {
        isActive = active
        activeCaseMode = caseMode
        if (!PermissionHelper.hasOverlayPermission(context)) {
            hide()
            return
        }

        if (!isShowing) {
            createOverlayView()
        }
        updateOrbAppearance()
    }

    fun hide() {
        cancelPendingFocusCopy()
        mainHandler.removeCallbacksAndMessages(null)
        if (isShowing && rootContainer != null) {
            try {
                windowManager.removeView(rootContainer)
            } catch (_: Exception) {
            }
        }
        rootContainer = null
        collapsedOrb = null
        orbIcon = null
        orbCheckText = null
        layoutParams = null
        isShowing = false
    }

    fun copyInstant(clipboardText: String, showCheckImmediately: Boolean = false) {
        if (!isShowing) {
            if (ClipboardHelper.copyToClipboard(context, clipboardText)) {
                onClipboardSynced(clipboardText)
            }
            return
        }
        copyViaOverlayFocus(clipboardText)
        if (showCheckImmediately) {
            showCheckmarkBriefly()
        }
    }

    fun showCheckmarkBriefly() {
        if (!isShowing) return
        mainHandler.removeCallbacks(clearCheckRunnable)
        orbIcon?.visibility = View.GONE
        orbCheckText?.visibility = View.VISIBLE
        mainHandler.postDelayed(clearCheckRunnable, 1200L)
    }

    private fun cancelPendingFocusCopy() {
        activeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        activeFallbackRunnable = null
        val view = rootContainer
        val listener = activeFocusListener
        activeFocusListener = null
        if (view != null && listener != null && view.viewTreeObserver.isAlive) {
            try {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            } catch (_: Exception) {
            }
        }
    }

    fun copyViaOverlayFocus(code: String) {
        val directSuccess = ClipboardHelper.copyToClipboard(context, code)
        val isInteractive = powerManager?.isInteractive ?: true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (directSuccess) onClipboardSynced(code)
            return
        }

        val view = rootContainer ?: return
        val params = layoutParams ?: return
        if (!isShowing || !isInteractive) {
            return
        }

        cancelPendingFocusCopy()
        // Release focus from any floating browser window before bubble steals focus cleanly
        onBeforeRequestFocus()

        try {
            // Guarantee clean focus transition even if a previous request left FLAG_NOT_FOCUSABLE clear
            if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                windowManager.updateViewLayout(view, params)
            }

            var completed = false

            fun finishCopy(fromFocusGain: Boolean) {
                if (completed) return
                completed = true
                cancelPendingFocusCopy()
                val copied = ClipboardHelper.copyToClipboard(context, code)
                val verified = if (copied && (fromFocusGain || view.hasWindowFocus())) {
                    true
                } else {
                    copied && ClipboardHelper.getCurrentClipboardText(context) == code
                }
                if (verified) {
                    onClipboardSynced(code)
                }
                // Post flag restoration outside OnWindowFocusChangeListener dispatch to prevent WM lockups
                mainHandler.post { restoreNonFocusableFlags() }
            }

            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    finishCopy(fromFocusGain = true)
                }
            }
            val fallbackRunnable = Runnable { finishCopy(fromFocusGain = false) }

            activeFocusListener = focusListener
            activeFallbackRunnable = fallbackRunnable

            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
            }

            params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            windowManager.updateViewLayout(view, params)
            view.requestFocus()

            mainHandler.postDelayed(fallbackRunnable, 75L)
        } catch (_: Exception) {
            cancelPendingFocusCopy()
            mainHandler.post { restoreNonFocusableFlags() }
        }
    }

    private fun restoreNonFocusableFlags() {
        val view = rootContainer ?: return
        val params = layoutParams ?: return
        if (!isShowing) return
        try {
            if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val initialX = (screenWidth - bubbleSizePx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val initialY = dp(180)
        isSnappedRight = true

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val root = FrameLayout(context)

        val orb = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            elevation = dp(8).toFloat()
        }

        val icon = ImageView(context).apply {
            val iconSize = dp(24)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }

        val checkText = TextView(context).apply {
            text = "✅"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        orb.addView(icon)
        orb.addView(checkText)
        root.addView(orb)

        var startX = 0
        var startY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = dp(8)

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val maxX = (context.resources.displayMetrics.widthPixels - bubbleSizePx - edgeMarginPx)
                            .coerceAtLeast(edgeMarginPx)
                        val maxY = (context.resources.displayMetrics.heightPixels - bubbleSizePx - dp(32))
                            .coerceAtLeast(dp(48))
                        params.x = (startX + dx).coerceIn(edgeMarginPx, maxX)
                        params.y = (startY + dy).coerceIn(dp(36), maxY)
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpMillis <= DOUBLE_TAP_WINDOW_MS) {
                            mainHandler.removeCallbacks(pendingSingleTapRunnable)
                            lastTapUpMillis = 0L
                            onDoubleTapOcr()
                        } else {
                            lastTapUpMillis = now
                            mainHandler.removeCallbacks(pendingSingleTapRunnable)
                            mainHandler.postDelayed(pendingSingleTapRunnable, DOUBLE_TAP_WINDOW_MS)
                        }
                    } else {
                        lastTapUpMillis = 0L
                        mainHandler.removeCallbacks(pendingSingleTapRunnable)
                        snapToNearestEdge(root, params)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(root, params)
            rootContainer = root
            collapsedOrb = orb
            orbIcon = icon
            orbCheckText = checkText
            layoutParams = params
            isShowing = true
        } catch (_: Exception) {
            isShowing = false
        }
    }

    private fun snapToNearestEdge(root: View, params: WindowManager.LayoutParams) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val center = params.x + bubbleSizePx / 2
        val snapRight = center >= screenWidth / 2
        isSnappedRight = snapRight

        val targetX = if (snapRight) {
            (screenWidth - bubbleSizePx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        } else {
            edgeMarginPx
        }

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (!isShowing) return@addUpdateListener
                params.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    private fun updateOrbAppearance() {
        val orb = collapsedOrb ?: return
        val icon = orbIcon ?: return

        if (isActive) {
            val fillColor = when (activeCaseMode) {
                AppCaseMode.MYCODE_G7F -> Color.parseColor("#10B981")
                AppCaseMode.GARENA_CODE -> Color.parseColor("#0284C7")
            }
            val strokeColor = when (activeCaseMode) {
                AppCaseMode.MYCODE_G7F -> Color.parseColor("#A7F3D0")
                AppCaseMode.GARENA_CODE -> Color.parseColor("#7DD3FC")
            }
            orb.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dp(3), strokeColor)
            }
            icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_bubble_bolt))
        } else {
            orb.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#475569"))
                setStroke(dp(2), Color.parseColor("#94A3B8"))
            }
            icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_bubble_pause))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val DOUBLE_TAP_WINDOW_MS = 220L
    }
}
