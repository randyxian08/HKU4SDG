package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            step = step,
            bounds = bounds,
            message = GuideContent.instruction(step, preferences.textLanguage),
            textLanguage = preferences.textLanguage,
            voiceLanguage = preferences.voiceLanguage,
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
        step: GuideStep, bounds: Rect?, message: String,
        textLanguage: GuideLanguage, voiceLanguage: GuideLanguage,
        collapsed: Boolean, muted: Boolean,
        onCollapse: () -> Unit, onTextLanguage: () -> Unit, onVoiceLanguage: () -> Unit,
        onReplay: () -> Unit, onMute: () -> Unit, onExit: () -> Unit
    ) {
        clear()
        if (bounds != null) addRing(bounds, step)
        val root = cardBase()
        if (step == GuideStep.COMPLETE) {
            root.addView(label("●  练习完成", 14f, Design.forest, true))
            space(root, 14)
            root.addView(label(message, 21f, Design.ink, true).apply { setLineSpacing(dp(4).toFloat(), 1f) })
            space(root, 19)
            root.addView(tap("结束练习", Design.forest, Design.surface, 16f, onExit),
                LinearLayout.LayoutParams(-1, dp(52)))
            addCard(root, null)
            return
        }
        val number = "第 ${step.ordinal + 1} / 3 步"
        val eyebrow = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        eyebrow.addView(label("●  正在引导  ·  $number", 13f, Design.forest, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        eyebrow.addView(tap(if (collapsed) "展开文字  ⌄" else "收起文字  ⌃", Design.paleGreen,
            Design.forest, 13f, onCollapse))
        root.addView(eyebrow)
        if (collapsed) {
            space(root, 9)
            root.addView(label("圈出的地方，就是下一步", 17f, Design.ink, true))
        } else {
            space(root, 15)
            root.addView(label(message, 22f, Design.ink, true).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            })
            space(root, 17)
            val languages = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
            languages.addView(tap("文字  ${textLanguage.label}  ⌄", Design.paleGreen,
                Design.forest, 14f, onTextLanguage), LinearLayout.LayoutParams(0, dp(44), 1f))
            languages.addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
            languages.addView(tap("播报  ${voiceLanguage.label}  ⌄", Design.paleGreen,
                Design.forest, 14f, onVoiceLanguage), LinearLayout.LayoutParams(0, dp(44), 1f))
            root.addView(languages)
        }
        space(root, 15)
        val actions = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(tap("再听一遍", Design.forest, Design.surface, 15f, onReplay),
            LinearLayout.LayoutParams(0, dp(48), 1.3f))
        actions.addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(tap(if (muted) "开声音" else "静音", Design.background, Design.ink, 14f, onMute),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(tap("结束", Design.background, Design.secondary, 14f, onExit),
            LinearLayout.LayoutParams(0, dp(48), 0.7f))
        root.addView(actions)
        addCard(root, bounds)
    }

    fun showStatus(message: String, paused: Boolean, onResume: () -> Unit, onExit: () -> Unit) {
        clear()
        val root = cardBase()
        root.addView(label(if (paused) "●  私人页面 · 已暂停" else "●  页面暂未识别", 13f,
            if (paused) Design.orange else Design.secondary, true))
        space(root, 13)
        root.addView(label(message, 20f, Design.ink, true).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        space(root, 18)
        if (paused) {
            root.addView(tap("返回普通页面后继续", Design.forest, Design.surface, 16f, onResume),
                LinearLayout.LayoutParams(-1, dp(52)))
            space(root, 8)
        }
        root.addView(tap("结束引导", Design.background, Design.ink, 15f, onExit),
            LinearLayout.LayoutParams(-1, dp(48)))
        addCard(root, null)
    }

    private fun cardBase() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(20))
        background = Design.shape(Design.surface, dp(26).toFloat(), Design.line, dp(1))
        elevation = dp(15).toFloat()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun tap(value: String, fill: Int, color: Int, size: Float, click: () -> Unit) = TextView(service).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minHeight = dp(44)
        background = Design.shape(fill, dp(13).toFloat())
        setOnClickListener { click() }
    }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(service), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun addRing(bounds: Rect, step: GuideStep) {
        val padding = dp(15)
        val view = object : View(service) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.orange
                style = Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AE8783D }
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.orange }
            val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.surface
                textSize = dp(13).toFloat()
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = padding.toFloat()
                val top = padding.toFloat()
                val right = (width - padding).toFloat()
                val bottom = (height - padding).toFloat()
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), fill)
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), stroke)
                if (step != GuideStep.COMPLETE) {
                    canvas.drawCircle(left, top, dp(14).toFloat(), badge)
                    canvas.drawText((step.ordinal + 1).toString(), left, top - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
                }
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

    private fun addCard(view: View, bounds: Rect?) {
        val width = service.resources.displayMetrics.widthPixels - dp(32)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val screenHeight = service.resources.displayMetrics.heightPixels
        val atBottom = bounds == null || bounds.bottom + dp(16) < screenHeight - view.measuredHeight - dp(48)
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            x = dp(16)
            y = dp(if (atBottom) 24 else 64)
        }
        wm.addView(view, params)
        card = view
    }
}
