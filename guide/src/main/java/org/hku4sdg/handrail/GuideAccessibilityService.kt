package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private var cloudTts: CloudTts? = null
    private var ttsReady = false
    private var shownStep: GuideStep? = null
    private var chromeVerified = false
    private var chromeSaveOffered = false
    private var currentSessionId = -1
    private var renderKey: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        preferences = GuidePreferences(this)
        overlay = GuideOverlay(this)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        cloudTts = CloudTts(this)
        handler.postDelayed({ if (preferences.active) render(true) }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || !preferences.active) {
            if (::overlay.isInitialized) overlay.clear()
            tts?.stop()
            cloudTts?.stop()
            return
        }
        val allowed = allowedPackages()
        if (event?.packageName?.toString() !in allowed) {
            if (rootInActiveWindow?.packageName?.toString() !in allowed) {
                overlay.clear()
                tts?.stop()
                cloudTts?.stop()
                renderKey = null
                if (preferences.activeTask != GuideTask.CHROME_BOOKMARK) shownStep = null
            }
            return
        }
        render()
        handler.postDelayed({ if (preferences.active) render() }, 250)
    }

    override fun onInterrupt() {
        tts?.stop()
        cloudTts?.stop()
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        cloudTts?.close()
        if (::overlay.isInitialized) overlay.clear()
        super.onDestroy()
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root.isVisibleToUser && predicate(root)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    private fun header(root: AccessibilityNodeInfo, title: String): Boolean =
        findNode(root) { it.viewIdResourceName == "$SETTINGS_PACKAGE:id/collapsing_toolbar" &&
            it.contentDescription?.toString() == title } != null

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun allowedPackages(): Set<String> = when (preferences.activeTask) {
        GuideTask.FONT_SIZE -> setOf(SETTINGS_PACKAGE)
        GuideTask.SCREEN_BRIGHTNESS -> setOf(SETTINGS_PACKAGE, SYSTEMUI_PACKAGE)
        GuideTask.CHROME_BOOKMARK -> setOf(CHROME_PACKAGE)
    }

    private fun fontTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() != SETTINGS_PACKAGE) return null
        return when {
            header(root, "Display size and text") -> {
                val current = Settings.System.getFloat(contentResolver, FONT_SCALE_KEY, 1f)
                if (current > preferences.initialFontScale + 0.01f) GuideStep.COMPLETE to null
                else GuideStep.SLIDER to findNode(root) {
                    it.className?.toString() == "android.widget.SeekBar" &&
                        it.contentDescription?.toString() == "Font size" &&
                        it.viewIdResourceName == "android:id/seekbar"
                }
            }
            header(root, "Display & touch") -> GuideStep.DISPLAY_SIZE to
                findNode(root) { it.viewIdResourceName == "android:id/title" &&
                    it.text?.toString() == "Display size and text" }?.let(::clickableAncestor)
            else -> null
        }
    }

    private fun brightnessTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        val initial = preferences.initialBrightness
        val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (initial < 0 || current < 0) return null
        return when (root.packageName?.toString()) {
            SETTINGS_PACKAGE -> {
                if (!header(root, "Display & touch")) return null
                val row = findNode(root) { it.viewIdResourceName == "android:id/title" &&
                    it.text?.toString() == "Brightness level" }?.let(::clickableAncestor) ?: return null
                if (current > initial + 5) GuideStep.BRIGHTNESS_COMPLETE to null
                else GuideStep.BRIGHTNESS_ROW to row
            }
            SYSTEMUI_PACKAGE -> {
                if (shownStep !in setOf(GuideStep.BRIGHTNESS_ROW, GuideStep.BRIGHTNESS_SLIDER,
                        GuideStep.BRIGHTNESS_COMPLETE)) return null
                // The dialog briefly hides its slider while a drag settles. Keep the verified
                // brightness path and read the actual setting before requiring a visible target.
                if (current > initial + 5 && shownStep == GuideStep.BRIGHTNESS_SLIDER) {
                    return GuideStep.BRIGHTNESS_COMPLETE to null
                }
                val slider = findNode(root) {
                    it.className?.toString() == "android.widget.SeekBar" &&
                        it.viewIdResourceName == "$SYSTEMUI_PACKAGE:id/slider" &&
                        it.text?.toString() == "Display brightness"
                }
                if (slider == null) return null
                if (current > initial + 5) GuideStep.BRIGHTNESS_COMPLETE to null
                else GuideStep.BRIGHTNESS_SLIDER to slider
            }
            else -> null
        }
    }

    private fun chromeTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() != CHROME_PACKAGE) return null
        val address = findNode(root) { it.viewIdResourceName == "$CHROME_PACKAGE:id/url_bar" }
        if (address != null) {
            val url = address.text?.toString().orEmpty()
            chromeVerified = url == "www.gov.hk/tc/residents/housing/socialservices/?subcat=elderly"
            if (!chromeVerified) {
                chromeSaveOffered = false
            }
        }
        if (!chromeVerified) return null
        if (shownStep == GuideStep.CHROME_COMPLETE) return GuideStep.CHROME_COMPLETE to null
        val edit = findNode(root) { it.viewIdResourceName == "$CHROME_PACKAGE:id/button_two" &&
            it.contentDescription?.toString() == "Edit bookmark" }
        if (edit != null) return GuideStep.CHROME_COMPLETE to null
        val save = findNode(root) { it.viewIdResourceName == "$CHROME_PACKAGE:id/button_two" &&
            it.contentDescription?.toString() == "Bookmark" }
        if (save != null && shownStep in setOf(GuideStep.CHROME_MENU, GuideStep.CHROME_SAVE,
                GuideStep.CHROME_VERIFY)) {
            chromeSaveOffered = true
            return GuideStep.CHROME_SAVE to save
        }
        val menu = findNode(root) { it.viewIdResourceName == "$CHROME_PACKAGE:id/menu_button" &&
            it.contentDescription?.toString() == "Customize and control Google Chrome" }
        return if (address != null && menu != null)
            (if (chromeSaveOffered) GuideStep.CHROME_VERIFY else GuideStep.CHROME_MENU) to menu
        else null
    }

    private fun render(force: Boolean = false) {
        if (currentSessionId != preferences.guideSessionId) {
            currentSessionId = preferences.guideSessionId
            shownStep = null
            chromeVerified = false
            chromeSaveOffered = false
            renderKey = null
        }
        val root = rootInActiveWindow
        if (root?.packageName?.toString() !in allowedPackages()) {
            overlay.clear()
            tts?.stop()
            cloudTts?.stop()
            if (preferences.activeTask != GuideTask.CHROME_BOOKMARK) shownStep = null
            return
        }
        // Strictly match the tested Android 15 Settings or Chrome 124 public-page layouts.
        val target = when (preferences.activeTask) {
            GuideTask.FONT_SIZE -> fontTarget(root)
            GuideTask.SCREEN_BRIGHTNESS -> brightnessTarget(root)
            GuideTask.CHROME_BOOKMARK -> chromeTarget(root)
        }
        if (target == null || (!target.first.isComplete && target.second == null)) {
            tts?.stop()
            cloudTts?.stop()
            if (root.packageName?.toString() !in setOf(SYSTEMUI_PACKAGE, CHROME_PACKAGE)) shownStep = null
            renderKey = null
            overlay.showStatus(GuideContent.unknown(preferences.textLanguage), false,
                onResume = {}, onExit = ::endGuide)
            return
        }
        val (step, node) = target
        val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
        val key = "${preferences.activeTask}:$step:$bounds:${preferences.textLanguage}:${preferences.voiceLanguage}:${preferences.collapsed}:${preferences.muted}"
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
            onMute = { preferences.muted = !preferences.muted; if (preferences.muted) { tts?.stop(); cloudTts?.stop() }; render(true) },
            onExit = ::endGuide
        )
        if (changedStep && !step.isComplete) speak(step)
    }

    private fun next(current: GuideLanguage): GuideLanguage =
        GuideLanguage.entries[(current.ordinal + 1) % GuideLanguage.entries.size]

    private fun speak(step: GuideStep) {
        if (preferences.muted) return
        tts?.stop()
        val message = GuideContent.instruction(step, preferences.voiceLanguage)
        val language = preferences.voiceLanguage
        cloudTts?.speak(message, language) { speakOnDevice(message, language) }
            ?: speakOnDevice(message, language)
    }

    private fun speakOnDevice(message: String, language: GuideLanguage) {
        if (preferences.muted || !ttsReady) return
        val engine = tts ?: return
        val locale = language.locale
        val support = engine.isLanguageAvailable(locale)
        if (support < TextToSpeech.LANG_AVAILABLE || engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, "本机暂不支持 ${language.label} 播报，请查看文字提示。", Toast.LENGTH_LONG).show()
            return
        }
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guide-${language.name}")
    }

    private fun endGuide() {
        preferences.active = false
        shownStep = null
        chromeVerified = false
        chromeSaveOffered = false
        renderKey = null
        tts?.stop()
        cloudTts?.stop()
        overlay.clear()
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val FONT_SCALE_KEY = "font_scale"
    }
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
        if (step.isComplete) {
            root.addView(label("●  已完成", 14f, Design.forest, true))
            space(root, 14)
            root.addView(label(message, 21f, Design.ink, true).apply { setLineSpacing(dp(4).toFloat(), 1f) })
            space(root, 19)
            root.addView(tap("结束引导", Design.forest, Design.surface, 16f, onExit),
                LinearLayout.LayoutParams(-1, dp(52)))
            addCard(root, null)
            return
        }
        val number = "第 ${step.number} / ${if (step.task == GuideTask.CHROME_BOOKMARK) 3 else 2} 步"
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
        minHeight = dp(48)
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
                if (!step.isComplete) {
                    canvas.drawCircle(left, top, dp(14).toFloat(), badge)
                    canvas.drawText(step.number.toString(), left, top - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
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
