package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
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
    private var hintVisible = false
    private var adjusting = false
    private var toolsExpanded = false
    private var seenTaskWindow = false
    private var departureCheck: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        connected = true
        preferences = GuidePreferences(this)
        overlay = GuideOverlay(this)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        cloudTts = CloudTts(this)
        preferences.pause(PauseReason.SERVICE_RESTART)
        handler.postDelayed({ if (preferences.active) render(true) }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || !preferences.active) {
            if (::overlay.isInitialized) overlay.clear()
            tts?.stop()
            cloudTts?.stop()
            return
        }
        syncSession()
        val allowed = allowedPackages()
        if (event?.packageName?.toString() !in allowed) {
            val packageName = rootInActiveWindow?.packageName?.toString()
            if (packageName !in allowed) {
                clearFeedback()
                if (seenTaskWindow && packageName != null) checkDeparture()
            }
            return
        }
        render()
        handler.postDelayed({ if (preferences.active) render() }, 250)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed({ if (preferences.active) render() }, 750)
        }
    }

    private fun checkDeparture() {
        if (departureCheck != null || preferences.sessionState.phase != SessionPhase.RUNNING) return
        val session = preferences.guideSessionId
        val check = Runnable {
            departureCheck = null
            if (!preferences.active || preferences.guideSessionId != session) return@Runnable
            val packageName = rootInActiveWindow?.packageName?.toString()
            if (packageName != null && packageName !in allowedPackages()) pauseGuide(PauseReason.LEFT_APP)
        }
        departureCheck = check
        handler.postDelayed(check, 600)
    }

    private fun clearFeedback() {
        if (::overlay.isInitialized) overlay.clear()
        tts?.stop()
        cloudTts?.stop()
        renderKey = null
    }

    private fun resetTargets() {
        shownStep = null
        chromeVerified = false
        chromeSaveOffered = false
        hintVisible = false
        adjusting = false
        toolsExpanded = false
        renderKey = null
    }

    private fun syncSession() {
        if (currentSessionId != preferences.guideSessionId) {
            currentSessionId = preferences.guideSessionId
            resetTargets()
            seenTaskWindow = false
        }
    }

    private fun pauseGuide(reason: PauseReason) {
        preferences.pause(reason)
        clearFeedback()
        resetTargets()
        render(true)
    }

    private fun resumeGuide() {
        if (!preferences.active) return
        if (TaskAvailability.block(this, preferences.activeTask) != null) { render(true); return }
        preferences.resume()
        resetTargets()
        render(true)
    }

    override fun onInterrupt() {
        if (::preferences.isInitialized) preferences.pause(PauseReason.SYSTEM_INTERRUPT)
        clearFeedback()
        resetTargets()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearFeedback()
        if (::preferences.isInitialized && preferences.active) render(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (::preferences.isInitialized) preferences.pause(PauseReason.SERVICE_RESTART)
        clearFeedback()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (::preferences.isInitialized) preferences.pause(PauseReason.SERVICE_RESTART)
        handler.removeCallbacksAndMessages(null)
        clearFeedback()
        tts?.shutdown()
        cloudTts?.close()
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
        GuideTask.MAPS_LIBRARY_ADDRESS -> setOf(MAPS_PACKAGE)
    }

    private fun fontTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() != SETTINGS_PACKAGE) return null
        return when {
            header(root, "Display size and text") -> {
                val current = Settings.System.getFloat(contentResolver, FONT_SCALE_KEY, 1f)
                if (!adjusting && current > preferences.initialFontScale + 0.01f) GuideStep.COMPLETE to null
                else findNode(root) {
                    it.className?.toString() == "android.widget.SeekBar" &&
                        it.contentDescription?.toString() == "Font size" &&
                        it.viewIdResourceName == "android:id/seekbar"
                }?.let { GuideStep.SLIDER to it } ?: (GuideStep.SLIDER_SCROLL to null)
            }
            header(root, "Display & touch") ->
                findNode(root) { it.viewIdResourceName == "android:id/title" &&
                    it.text?.toString() == "Display size and text" }
                    ?.let { GuideStep.DISPLAY_SIZE to clickableAncestor(it) }
                    ?: (GuideStep.DISPLAY_SCROLL to null)
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
                if (!adjusting && current > initial + 5) GuideStep.BRIGHTNESS_COMPLETE to null
                else GuideStep.BRIGHTNESS_ROW to row
            }
            SYSTEMUI_PACKAGE -> {
                // Match the reviewed dialog itself after every window transition. Its availability
                // is not dependent on the previous window still having an accessibility root.
                // The dialog briefly hides its slider while a drag settles. Keep the verified
                // brightness path and read the actual setting before requiring a visible target.
                if (!adjusting && current > initial + 5 && shownStep == GuideStep.BRIGHTNESS_SLIDER) {
                    return GuideStep.BRIGHTNESS_COMPLETE to null
                }
                val slider = findNode(root) {
                    it.className?.toString() == "android.widget.SeekBar" &&
                        it.viewIdResourceName == "$SYSTEMUI_PACKAGE:id/slider" &&
                        it.text?.toString() == "Display brightness"
                }
                if (slider == null) return null
                if (!adjusting && current > initial + 5) GuideStep.BRIGHTNESS_COMPLETE to null
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
            it.contentDescription?.toString() in setOf("Customize and control Google Chrome",
                "Update available. More options") }
        return if (address != null && menu != null)
            (if (chromeSaveOffered) GuideStep.CHROME_VERIFY else GuideStep.CHROME_MENU) to menu
        else null
    }

    private fun mapsTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() != MAPS_PACKAGE) return null
        val place = findNode(root) {
            it.text?.toString() == "Hong Kong Central Library" &&
                it.contentDescription?.toString() == "Hong Kong Central Library"
        } ?: return null
        val address = findNode(root) {
            it.contentDescription?.toString() ==
                "Address: 66 Causeway Rd, Causeway Bay, Hong Kong Island, Hong Kong" &&
                it.isClickable
        }
        if (address != null) return GuideStep.MAPS_ADDRESS to address
        if (findNode(root) {
            it.text?.toString() == "66 Causeway Rd, Causeway Bay, Hong Kong Island, Hong Kong"
        } == null) return null
        return GuideStep.MAPS_PLACE to place
    }

    private fun render(force: Boolean = false) {
        syncSession()
        val root = rootInActiveWindow
        if (root?.packageName?.toString() !in allowedPackages()) {
            clearFeedback()
            return
        }
        seenTaskWindow = true
        TaskAvailability.block(this, preferences.activeTask)?.let { block ->
            preferences.pause(PauseReason.SYSTEM_INTERRUPT)
            tts?.stop()
            cloudTts?.stop()
            val key = "blocked:$block:${preferences.textLanguage}"
            if (!force && renderKey == key) return
            resetTargets()
            renderKey = key
            overlay.showStatus(TaskAvailability.reason(block, preferences.textLanguage), preferences.textLanguage,
                ::returnToTask, ::endGuide)
            return
        }
        if (preferences.sessionState.phase == SessionPhase.PAUSED) {
            tts?.stop()
            cloudTts?.stop()
            val l = preferences.textLanguage
            val key = "paused:$currentSessionId:${preferences.sessionState.reason}:$l"
            if (!force && key == renderKey) return
            resetTargets()
            renderKey = key
            try {
                overlay.showPaused(SupportCopy.pauseMessage(preferences.sessionState.reason, l), l,
                    ::resumeGuide, ::openRecovery, ::endGuide)
            } catch (_: WindowManager.BadTokenException) { clearFeedback() }
            return
        }
        // Strictly match only the tested Android 15 Settings, Chrome, or Maps layouts.
        val target = when (preferences.activeTask) {
            GuideTask.FONT_SIZE -> fontTarget(root)
            GuideTask.SCREEN_BRIGHTNESS -> brightnessTarget(root)
            GuideTask.CHROME_BOOKMARK -> chromeTarget(root)
            GuideTask.MAPS_LIBRARY_ADDRESS -> mapsTarget(root)
        }
        if (target == null || (!target.first.isComplete && !target.first.isScrollHint && target.second == null)) {
            tts?.stop()
            cloudTts?.stop()
            if (root.packageName?.toString() !in setOf(SYSTEMUI_PACKAGE, CHROME_PACKAGE)) shownStep = null
            val unknownKey = "unknown:$currentSessionId:${root.packageName}:${preferences.textLanguage}"
            if (!force && renderKey == unknownKey) return
            renderKey = unknownKey
            try {
                overlay.showStatus(GuideContent.unknown(preferences.textLanguage), preferences.textLanguage,
                    onResume = ::returnToTask, onExit = ::endGuide)
            } catch (_: WindowManager.BadTokenException) {
                overlay.clear()
                renderKey = null
            }
            return
        }
        val (step, node) = target
        val changedStep = shownStep != step
        if (changedStep) {
            hintVisible = false
            preferences.collapsed = preferences.helpMode == HelpMode.LIGHT
        }
        val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
        val key = "${preferences.activeTask}:$step:$bounds:${preferences.textLanguage}:${preferences.voiceLanguage}:${preferences.collapsed}:${preferences.muted}:$hintVisible:$adjusting:$toolsExpanded"
        if (!force && key == renderKey) return
        shownStep = step
        renderKey = key
        try {
            overlay.showStep(
                step = step,
                bounds = bounds,
                message = if (adjusting && !step.isScrollHint) ExperienceCopy.adjustment(step.task, preferences.textLanguage)
                    else GuideContent.instruction(step, preferences.textLanguage),
                textLanguage = preferences.textLanguage,
                voiceLanguage = preferences.voiceLanguage,
                collapsed = preferences.collapsed,
                muted = preferences.muted,
                toolsExpanded = toolsExpanded,
                onTools = { toolsExpanded = !toolsExpanded; render(true) },
                independent = preferences.helpMode == HelpMode.ON_DEMAND && !hintVisible,
                adjusting = adjusting,
                onCollapse = { preferences.collapsed = !preferences.collapsed; render(true) },
                onTextLanguage = { preferences.textLanguage = next(preferences.textLanguage); render(true) },
                onVoiceLanguage = { preferences.voiceLanguage = next(preferences.voiceLanguage); render(true) },
                onReplay = { speak(step, explicit = true) },
                onPause = { pauseGuide(PauseReason.USER) },
                onHint = { hintVisible = true; preferences.collapsed = false; render(true); speak(step) },
                onConfirm = ::confirmGuide,
                onAdjust = { adjusting = true; renderKey = null; render(true) },
                onMute = { preferences.muted = !preferences.muted; if (preferences.muted) { tts?.stop(); cloudTts?.stop() }; render(true) },
                onExit = ::endGuide
            )
        } catch (_: WindowManager.BadTokenException) {
            overlay.clear()
            renderKey = null
            return
        }
        if (changedStep && !step.isComplete && preferences.helpMode == HelpMode.FULL) speak(step)
    }

    private fun next(current: GuideLanguage): GuideLanguage =
        GuideLanguage.entries[(current.ordinal + 1) % GuideLanguage.entries.size]

    private fun speak(step: GuideStep, explicit: Boolean = false) {
        if ((preferences.muted && !explicit) || !preferences.sessionState.canConfirm) return
        tts?.stop()
        val message = if (adjusting && !step.isScrollHint) ExperienceCopy.adjustment(step.task, preferences.voiceLanguage)
            else GuideContent.instruction(step, preferences.voiceLanguage)
        val language = preferences.voiceLanguage
        cloudTts?.speak(message, language) { speakOnDevice(message, language, explicit) }
            ?: speakOnDevice(message, language, explicit)
    }

    private fun speakOnDevice(message: String, language: GuideLanguage, explicit: Boolean = false) {
        if ((preferences.muted && !explicit) || !ttsReady || !preferences.sessionState.canConfirm) return
        val engine = tts ?: return
        val locale = language.locale
        val support = engine.isLanguageAvailable(locale)
        if (support < TextToSpeech.LANG_AVAILABLE || engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, ExperienceCopy.choose(preferences.textLanguage,
                "本机暂不支持 ${language.label} 播报，请查看文字提示。",
                "本机暂时未支持 ${language.label} 播报，请睇文字提示。",
                "${language.label} speech is unavailable on this device. Use the text guidance."), Toast.LENGTH_LONG).show()
            return
        }
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guide-${language.name}")
    }

    private fun endGuide() {
        preferences.finish(PracticeOutcome.ENDED)
        shownStep = null
        chromeVerified = false
        chromeSaveOffered = false
        renderKey = null
        tts?.stop()
        cloudTts?.stop()
        overlay.clear()
    }

    private fun openRecovery() {
        preferences.pause(PauseReason.USER)
        clearFeedback()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun returnToTask() {
        endGuide()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("return_to_task", true))
    }

    private fun confirmGuide() {
        if (!preferences.sessionState.canConfirm || TaskAvailability.block(this, preferences.activeTask) != null) {
            render(true); return
        }
        val root = rootInActiveWindow
        if (root?.packageName?.toString() !in allowedPackages()) { clearFeedback(); return }
        val current = when (preferences.activeTask) {
            GuideTask.FONT_SIZE -> fontTarget(root)
            GuideTask.SCREEN_BRIGHTNESS -> brightnessTarget(root)
            GuideTask.CHROME_BOOKMARK -> chromeTarget(root)
            GuideTask.MAPS_LIBRARY_ADDRESS -> mapsTarget(root)
        }
        if (current == null || (!current.first.isComplete && !(adjusting && current.first in
                setOf(GuideStep.SLIDER, GuideStep.BRIGHTNESS_SLIDER)))) { render(true); return }
        preferences.finish(PracticeOutcome.CONFIRMED)
        endGuide()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    companion object {
        @Volatile var connected = false
            private set
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
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
        collapsed: Boolean, muted: Boolean, independent: Boolean, adjusting: Boolean,
        toolsExpanded: Boolean, onTools: () -> Unit,
        onCollapse: () -> Unit, onTextLanguage: () -> Unit, onVoiceLanguage: () -> Unit,
        onReplay: () -> Unit, onMute: () -> Unit, onExit: () -> Unit, onPause: () -> Unit,
        onHint: () -> Unit, onConfirm: () -> Unit, onAdjust: () -> Unit
    ) {
        clear()
        if (bounds != null && (!independent || adjusting || step.isComplete)) addRing(bounds, step)
        val root = cardBase()
        if (step.isComplete) {
            root.addView(label(ExperienceCopy.status(textLanguage), 13f, Design.forest, true))
            space(root, 12)
            root.addView(label(ExperienceCopy.question(step.task, textLanguage), 22f, Design.ink, true))
            space(root, 12)
            root.addView(tap(ExperienceCopy.confirm(textLanguage), Design.forest, Design.surface, 16f, onConfirm))
            if (step.task in setOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS)) {
                space(root, 8)
                root.addView(tap(ExperienceCopy.adjust(textLanguage), Design.paleGreen, Design.forest, 16f, onAdjust))
            }
            space(root, 6)
            root.addView(tap(SupportCopy.pause(textLanguage), Design.paleGreen, Design.forest, 15f, onPause))
            space(root, 6)
            root.addView(tap(ExperienceCopy.unconfirmed(textLanguage), Design.background, Design.secondary, 14f, onExit))
            addCard(root, bounds)
            return
        }
        if (independent && !adjusting) {
            root.addView(label(ExperienceCopy.own(textLanguage), 18f, Design.ink, true))
            space(root, 12)
            root.addView(tap(ExperienceCopy.hint(textLanguage), Design.forest, Design.surface, 16f, onHint))
            space(root, 6)
            root.addView(tap(SupportCopy.pause(textLanguage), Design.paleGreen, Design.forest, 15f, onPause))
            space(root, 6)
            root.addView(tap(ExperienceCopy.end(textLanguage), Design.background, Design.secondary, 14f, onExit))
            addCard(root, bounds)
            return
        }
        val number = SupportCopy.step(textLanguage, step.number, if (step.task == GuideTask.CHROME_BOOKMARK) 3 else 2)
        val eyebrow = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        eyebrow.addView(label(number, 13f, Design.forest, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!step.isScrollHint) eyebrow.addView(tap(SupportCopy.collapse(textLanguage, collapsed), Design.paleGreen,
            Design.forest, 13f, onCollapse))
        root.addView(eyebrow)
        if (collapsed && !step.isScrollHint) {
            space(root, 9)
            root.addView(label(SupportCopy.circled(textLanguage), 17f, Design.ink, true))
        } else {
            space(root, 15)
            root.addView(label(message, 21f, Design.ink, true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        space(root, 14)
        val actions = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(tap(if (muted) ExperienceCopy.choose(textLanguage, "听一次", "听一次", "Play once")
            else SupportCopy.replay(textLanguage), Design.forest, Design.surface, 15f, onReplay),
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(tap(SupportCopy.pause(textLanguage), Design.paleGreen, Design.forest, 15f, onPause),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        space(root, 6)
        val secondary = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        secondary.addView(tap(ExperienceCopy.choose(textLanguage, "语言和声音", "语言同声音", "Language & sound"),
            Design.surface, Design.forest, 14f, onTools).apply {
                contentDescription = ExperienceCopy.choose(textLanguage,
                    "语言和声音，" + if (toolsExpanded) "已展开" else "已收起",
                    "语言同声音，" + if (toolsExpanded) "已展开" else "已收起",
                    "Language and sound, " + if (toolsExpanded) "expanded" else "collapsed")
            }, LinearLayout.LayoutParams(0, -2, 1.5f))
        secondary.addView(tap(ExperienceCopy.end(textLanguage), Design.surface, Design.secondary, 14f, onExit),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(secondary)
        if (toolsExpanded) {
            space(root, 8)
            root.addView(tap("${SupportCopy.text(textLanguage)}: ${textLanguage.label}", Design.paleGreen,
                Design.forest, 14f, onTextLanguage))
            space(root, 6)
            root.addView(tap("${SupportCopy.voice(textLanguage)}: ${voiceLanguage.label}", Design.paleGreen,
                Design.forest, 14f, onVoiceLanguage))
            space(root, 6)
            root.addView(tap(SupportCopy.sound(textLanguage, muted), Design.background, Design.ink, 14f, onMute))
        }
        if (adjusting) {
            space(root, 8)
            root.addView(tap(ExperienceCopy.confirm(textLanguage), Design.forest, Design.surface, 16f, onConfirm))
        }
        addCard(root, bounds)
    }

    fun showStatus(message: String, language: GuideLanguage, onResume: () -> Unit, onExit: () -> Unit) {
        clear()
        val root = cardBase()
        root.addView(label(SupportCopy.unknown(language), 13f, Design.secondary, true))
        space(root, 13)
        root.addView(label(message, 20f, Design.ink, true).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        space(root, 18)
        root.addView(tap(ExperienceCopy.returnTask(language), Design.forest, Design.surface, 16f, onResume))
        space(root, 8)
        root.addView(tap(ExperienceCopy.end(language), Design.background, Design.ink, 15f, onExit))
        addCard(root, null)
    }

    fun showPaused(message: String, language: GuideLanguage, onResume: () -> Unit,
        onReturn: () -> Unit, onExit: () -> Unit) {
        clear()
        val root = cardBase()
        root.addView(label(SupportCopy.paused(language), 14f, Design.forest, true))
        space(root, 13)
        root.addView(label(message, 20f, Design.ink, true))
        space(root, 18)
        root.addView(tap(SupportCopy.resume(language), Design.forest, Design.surface, 16f, onResume))
        space(root, 8)
        root.addView(tap(SupportCopy.returnTask(language), Design.paleGreen, Design.forest, 15f, onReturn))
        space(root, 8)
        root.addView(tap(ExperienceCopy.unconfirmed(language), Design.background, Design.secondary, 14f, onExit))
        addCard(root, null)
    }

    private fun cardBase() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(20))
        background = Design.shape(Design.surface, dp(26).toFloat(), Design.line, dp(1))
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
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        background = Design.ripple(fill, dp(13).toFloat())
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
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AC25825 }
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
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        val margin = dp(6)
        val ringWidth = (bounds.width() + padding * 2).coerceAtMost(screenWidth - margin * 2)
        val ringHeight = (bounds.height() + padding * 2).coerceAtMost(screenHeight - margin * 2)
        val params = WindowManager.LayoutParams(ringWidth, ringHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.left - padding).coerceIn(margin, screenWidth - ringWidth - margin)
            y = (bounds.top - padding).coerceIn(margin, screenHeight - ringHeight - margin)
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
        val above = if (bounds == null) 0 else (bounds.top - dp(80)).coerceAtLeast(0)
        val below = if (bounds == null) screenHeight - dp(120) else (screenHeight - bounds.bottom - dp(56)).coerceAtLeast(0)
        val atBottom = bounds == null || below >= view.measuredHeight || below >= above
        val available = (if (atBottom) below else above).coerceAtLeast(dp(100))
        val height = view.measuredHeight.coerceAtMost(available)
        val scroll = ScrollView(service).apply {
            background = Design.shape(Design.surface, dp(26).toFloat(), Design.line, dp(1))
            clipToOutline = true
            addView(view)
            isFillViewport = false
        }
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            x = dp(16)
            y = dp(if (atBottom) 24 else 64)
        }
        wm.addView(scroll, params)
        card = scroll
    }
}
