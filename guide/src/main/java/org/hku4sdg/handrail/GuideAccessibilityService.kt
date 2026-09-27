package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class GuideAccessibilityService : AccessibilityService() {
    private lateinit var preferences: GuidePreferences
    private lateinit var overlay: GuideOverlay
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var privacyPaused = false
    private var shownStep: GuideStep? = null
    private var renderKey: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        preferences = GuidePreferences(this)
        overlay = GuideOverlay(this)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        handler.postDelayed({ if (preferences.active && !privacyPaused) render(true) }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || !preferences.active) {
            if (::overlay.isInitialized) overlay.clear()
            tts?.stop()
            return
        }
        if (event?.packageName?.toString() != TARGET_PACKAGE) {
            // Receive window changes globally to remove stale overlays, but never inspect
            // another app's node tree. Our own overlay may also emit an event.
            if (rootInActiveWindow?.packageName?.toString() != TARGET_PACKAGE) {
                overlay.clear()
                tts?.stop()
                renderKey = null
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.source?.viewIdResourceName == "$TARGET_PACKAGE:id/private_entry") {
            privacyPaused = true
            shownStep = null
            renderKey = null
            tts?.stop()
            showPause()
            return
        }
        if (privacyPaused) {
            showPause()
            return // Never inspect a private page while paused.
        }
        render()
        handler.postDelayed({ if (preferences.active && !privacyPaused) render() }, 250)
    }

    override fun onInterrupt() {
        tts?.stop()
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        if (::overlay.isInitialized) overlay.clear()
        super.onDestroy()
    }

    private fun find(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$TARGET_PACKAGE:id/$id").firstOrNull { it.isVisibleToUser }

    private fun render(force: Boolean = false) {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != TARGET_PACKAGE) {
            overlay.clear()
            tts?.stop()
            return
        }
        // Match only registered demo IDs. No screenshots, free-text fields or arbitrary coordinates.
        if (find(root, "private_panel") != null) {
            privacyPaused = true
            tts?.stop()
            showPause()
            return
        }
        val target = when {
            find(root, "done_label") != null -> GuideStep.COMPLETE to null
            find(root, "font_slider") != null -> GuideStep.SLIDER to find(root, "font_slider")
            find(root, "open_font_size") != null -> GuideStep.FONT_SIZE to find(root, "open_font_size")
            find(root, "open_display") != null -> GuideStep.DISPLAY to find(root, "open_display")
            else -> null
        }
        if (target == null) {
            tts?.stop()
            shownStep = null
            renderKey = null
            overlay.showStatus(GuideContent.unknown(preferences.textLanguage), false,
                onResume = {}, onExit = ::endGuide)
            return
        }
        val (step, node) = target
        val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
        val key = "$step:$bounds:${preferences.textLanguage}:${preferences.voiceLanguage}:${preferences.collapsed}:${preferences.muted}"
        if (!force && key == renderKey) return
        val changedStep = shownStep != step
        shownStep = step
        renderKey = key
        overlay.showStep(
            bounds = bounds,
            message = GuideContent.instruction(step, preferences.textLanguage),
            collapsed = preferences.collapsed,
            muted = preferences.muted,
            onCollapse = { preferences.collapsed = !preferences.collapsed; render(true) },
            onTextLanguage = { preferences.textLanguage = next(preferences.textLanguage); render(true) },
            onVoiceLanguage = { preferences.voiceLanguage = next(preferences.voiceLanguage); render(true) },
            onReplay = { speak(step) },
            onMute = { preferences.muted = !preferences.muted; if (preferences.muted) tts?.stop(); render(true) },
            onExit = ::endGuide
        )
        if (changedStep && step != GuideStep.COMPLETE) speak(step)
    }

    private fun next(current: GuideLanguage): GuideLanguage =
        GuideLanguage.entries[(current.ordinal + 1) % GuideLanguage.entries.size]

    private fun speak(step: GuideStep) {
        if (preferences.muted || privacyPaused || !ttsReady) return
        val engine = tts ?: return
        val locale = preferences.voiceLanguage.locale
        val support = engine.isLanguageAvailable(locale)
        if (support < TextToSpeech.LANG_AVAILABLE || engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, "本机暂不支持 ${preferences.voiceLanguage.label} 播报，请查看文字提示。", Toast.LENGTH_LONG).show()
            return
        }
        engine.speak(GuideContent.instruction(step, preferences.voiceLanguage), TextToSpeech.QUEUE_FLUSH, null, "guide-$step")
    }

    private fun showPause() {
        tts?.stop()
        overlay.showStatus(GuideContent.pause(preferences.textLanguage), true,
            onResume = {
                privacyPaused = false
                // Revalidate the active page; a tap on Continue alone cannot resume guidance.
                val root = rootInActiveWindow
                if (root?.packageName?.toString() != TARGET_PACKAGE || find(root, "private_panel") != null) {
                    privacyPaused = true
                    showPause()
                } else {
                    renderKey = null
                    render(true)
                }
            },
            onExit = ::endGuide)
    }

    private fun endGuide() {
        preferences.active = false
        privacyPaused = false
        shownStep = null
        renderKey = null
        tts?.stop()
        overlay.clear()
    }

    companion object { private const val TARGET_PACKAGE = "org.hku4sdg.demotarget" }
}

/** Two small accessibility windows: a non-touchable highlight and a touchable card. */
private class GuideOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var ring: View? = null
    private var card: View? = null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun clear() {
        ring?.let { runCatching { wm.removeViewImmediate(it) } }
        card?.let { runCatching { wm.removeViewImmediate(it) } }
        ring = null
        card = null
    }

    fun showStep(
        bounds: Rect?, message: String, collapsed: Boolean, muted: Boolean,
        onCollapse: () -> Unit, onTextLanguage: () -> Unit, onVoiceLanguage: () -> Unit,
        onReplay: () -> Unit, onMute: () -> Unit, onExit: () -> Unit
    ) {
        clear()
        if (bounds != null) addRing(bounds)
        val root = cardBase()
        root.addView(label(if (collapsed) "本步提示 · 查看文字" else message, if (collapsed) 18f else 22f))
        val controls = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(control(if (collapsed) "展开" else "收起", onCollapse))
        controls.addView(control(if (muted) "开声音" else "静音", onMute))
        controls.addView(control("重播", onReplay))
        controls.addView(control("结束", onExit))
        root.addView(controls)
        if (!collapsed) {
            val languages = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
            languages.addView(control("换文字语言", onTextLanguage))
            languages.addView(control("换播报语言", onVoiceLanguage))
            root.addView(languages)
        }
        addCard(root, bounds == null || bounds.centerY() < service.resources.displayMetrics.heightPixels / 2)
    }

    fun showStatus(message: String, paused: Boolean, onResume: () -> Unit, onExit: () -> Unit) {
        clear()
        val root = cardBase()
        root.addView(label(message, 21f))
        if (paused) root.addView(control("继续（返回普通页面后）", onResume).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        })
        root.addView(control("结束引导", onExit).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        })
        addCard(root, true)
    }

    private fun cardBase() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(0xFFFCFFF4.toInt())
            setStroke(dp(2), 0xFF136A59.toInt())
            cornerRadius = dp(18).toFloat()
        }
        elevation = dp(8).toFloat()
    }

    private fun label(value: String, size: Float) = TextView(service).apply {
        text = value
        textSize = size
        setTextColor(0xFF182B28.toInt())
        setPadding(dp(3), dp(3), dp(3), dp(5))
    }

    private fun control(value: String, click: () -> Unit) = Button(service).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        minHeight = dp(48)
        minWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        setOnClickListener { click() }
    }

    private fun addRing(bounds: Rect) {
        val padding = dp(7)
        val view = object : View(service) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE87C24.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dp(4).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawRoundRect(dp(3).toFloat(), dp(3).toFloat(),
                    (width - dp(3)).toFloat(), (height - dp(3)).toFloat(),
                    dp(12).toFloat(), dp(12).toFloat(), paint)
            }
        }
        val params = WindowManager.LayoutParams(bounds.width() + padding * 2, bounds.height() + padding * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left - padding
            y = bounds.top - padding
        }
        wm.addView(view, params)
        ring = view
    }

    private fun addCard(view: View, atBottom: Boolean) {
        val width = service.resources.displayMetrics.widthPixels - dp(24)
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            x = dp(12)
            y = dp(28)
        }
        wm.addView(view, params)
        card = view
    }
}
