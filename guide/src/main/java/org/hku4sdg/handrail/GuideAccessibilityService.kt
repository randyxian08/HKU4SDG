package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

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
    private var shownBounds: Rect? = null
    private var receiptVisible = false
    private var hintVisible = false
    private var adjusting = false
    private var toolsExpanded = false
    private var endConfirming = false
    private var seenTaskWindow = false
    private var phoneLabel = ""
    private var phoneAtMaximum = false
    private var phoneNodes: List<SettingsUiNode> = emptyList()
    private var departureCheck: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        preferences = GuidePreferences(this)
        overlay = GuideOverlay(this)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        cloudTts = CloudTts(this)
        connected = true
        ServiceDiagnostics.record(this, "service_connected")
        preferences.pause(PauseReason.SERVICE_RESTART)
        handler.postDelayed({ if (preferences.active) render(true) }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || !preferences.active) {
            if (receiptVisible && rootInActiveWindow?.packageName?.toString() in allowedPackages()) return
            receiptVisible = false
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
        shownBounds = null
        phoneNodes = emptyList()
    }

    private fun resetTargets() {
        shownStep = null
        chromeVerified = false
        chromeSaveOffered = false
        hintVisible = false
        adjusting = false
        toolsExpanded = false
        endConfirming = false
        renderKey = null
        phoneLabel = ""
        phoneAtMaximum = false
        phoneNodes = emptyList()
    }

    private fun syncSession() {
        if (currentSessionId != preferences.guideSessionId) {
            currentSessionId = preferences.guideSessionId
            overlay.resetPosition()
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
        // Android interrupts feedback, including on focus changes. This does not revoke access.
        ServiceDiagnostics.record(this, "feedback_interrupted")
        tts?.stop()
        cloudTts?.stop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearFeedback()
        if (::preferences.isInitialized && preferences.active) render(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        ServiceDiagnostics.record(this, "service_unbound")
        handler.removeCallbacksAndMessages(null)
        departureCheck = null
        if (::preferences.isInitialized) preferences.pause(PauseReason.SERVICE_RESTART)
        clearFeedback()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        ServiceDiagnostics.record(this, "service_destroyed")
        if (::preferences.isInitialized) preferences.pause(PauseReason.SERVICE_RESTART)
        handler.removeCallbacksAndMessages(null)
        departureCheck = null
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

    private fun phoneTarget(root: AccessibilityNodeInfo, task: GuideTask): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() !in if (task == GuideTask.FONT_SIZE) setOf(SETTINGS_PACKAGE)
            else setOf(SETTINGS_PACKAGE, SYSTEMUI_PACKAGE)) return null
        val live = mutableListOf<AccessibilityNodeInfo>()
        val nodes = mutableListOf<SettingsUiNode>()
        fun visit(node: AccessibilityNodeInfo, parent: Int) {
            val index = live.size
            val bounds = Rect().also(node::getBoundsInScreen)
            live += node
            nodes += SettingsUiNode(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), parent, node.isVisibleToUser, node.isEnabled, node.isClickable,
                node.className?.toString()?.contains("SeekBar", ignoreCase = true) == true ||
                    node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id },
                bounds.left, bounds.top, bounds.right, bounds.bottom, node.isCheckable)
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child, index) } }
        }
        visit(root, -1)
        phoneNodes = nodes.map { it.copy(text = PhoneSettingsMatcher.safeLabel(it.text),
            description = PhoneSettingsMatcher.safeLabel(it.description)) }
        val match = if (task == GuideTask.FONT_SIZE) PhoneSettingsMatcher.font(nodes, resources.displayMetrics.density)
            else PhoneSettingsMatcher.brightness(nodes, resources.displayMetrics.density)
        phoneLabel = match?.label.orEmpty()
        phoneAtMaximum = false
        if (match == null) return null
        if (!adjusting && match.controlPage) {
            val increased = if (task == GuideTask.FONT_SIZE)
                Settings.System.getFloat(contentResolver, FONT_SCALE_KEY, resources.configuration.fontScale) > preferences.initialFontScale + 0.01f
                else Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1) > preferences.initialBrightness
            if (increased) return (if (task == GuideTask.FONT_SIZE) GuideStep.COMPLETE else GuideStep.BRIGHTNESS_COMPLETE) to null
        }
        val target = match.index?.let(live::get)
        target?.rangeInfo?.let { range -> phoneAtMaximum = range.max > range.min && range.current >= range.max }
        return match.step to target
    }

    private fun copyPhoneDiagnostics() {
        val root = rootInActiveWindow
        if (!preferences.active || !TaskAvailability.experimental(this, preferences.activeTask) ||
            root?.packageName?.toString() !in allowedPackages()) return
        phoneTarget(root, preferences.activeTask)
        val report = JSONObject().put("app_version", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android_api", Build.VERSION.SDK_INT)
            .put("system_language", resources.configuration.locales[0].toLanguageTag()).put("task", preferences.activeTask.id)
            .put("package", root.packageName.toString()).put("node_count", phoneNodes.size)
            .put("truncated", phoneNodes.size > 160)
        val nodes = JSONArray()
        phoneNodes.take(160).forEachIndexed { index, node ->
            nodes.put(JSONObject().put("index", index).put("parent", node.parent).put("id", node.id)
                .put("visible", node.visible).put("enabled", node.enabled).put("clickable", node.clickable)
                .put("adjustable", node.adjustable).put("bounds", JSONArray(listOf(node.left, node.top, node.right, node.bottom)))
                .put("label", PhoneSettingsMatcher.safeLabel(node.text))
                .put("description", PhoneSettingsMatcher.safeLabel(node.description)))
        }
        report.put("nodes", nodes).put("scope", "Only known Settings labels and control structure; no other screen text, audio or identifiers")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("数字扶手适配信息", report.toString()))
        Toast.makeText(this, ExperienceCopy.choose(preferences.textLanguage,
            "已复制控件结构和设备版本，不含其他页面文字。可粘贴到反馈中。",
            "复制咗控件结构同设备版本，冇其他页面文字。可以贴到反馈入面。",
            "Copied control structure and device version, without other page text. Paste it into your feedback."), Toast.LENGTH_LONG).show()
    }

    private fun fontTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (TaskAvailability.experimental(this, GuideTask.FONT_SIZE)) return phoneTarget(root, GuideTask.FONT_SIZE)
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

    private fun automaticBrightnessTarget(root: AccessibilityNodeInfo, automatic: Boolean): Pair<GuideStep, AccessibilityNodeInfo?>? {
        if (root.packageName?.toString() != SETTINGS_PACKAGE) return null
        if (TaskAvailability.adaptiveBrightnessAvailable(this) == false) return null
        val live = mutableListOf<AccessibilityNodeInfo>()
        val nodes = mutableListOf<SettingsUiNode>()
        fun visit(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (depth > 35 || live.size >= 500) return
            val index = live.size; val b = Rect().also(node::getBoundsInScreen)
            live += node
            nodes += SettingsUiNode(text = node.text?.toString().orEmpty(), description = node.contentDescription?.toString().orEmpty(),
                id = node.viewIdResourceName.orEmpty(), parent = parent, visible = node.isVisibleToUser,
                enabled = node.isEnabled, clickable = node.isClickable, left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                checkable = node.isCheckable)
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child, index, depth + 1) } }
        }
        visit(root, -1, 0)
        phoneNodes = nodes.map { it.copy(text = PhoneSettingsMatcher.safeLabel(it.text), description = PhoneSettingsMatcher.safeLabel(it.description)) }
        val match = PhoneSettingsMatcher.automaticBrightness(nodes) ?: return null
        phoneLabel = match.label
        if (match.index != null && BrightnessFlow.action(preferences.brightnessGoal, automatic, false) == BrightnessAction.READY)
            return GuideStep.BRIGHTNESS_AUTO_COMPLETE to null
        return match.step to match.index?.let(live::get)
    }

    private fun previewAreas(root: AccessibilityNodeInfo, step: GuideStep, bounds: Rect?): List<Rect> {
        if (step.task != GuideTask.FONT_SIZE || step !in setOf(GuideStep.SLIDER, GuideStep.COMPLETE)) return emptyList()
        val preview = mutableListOf<Rect>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 35 || ++visited > 500) return
            if (node.isVisibleToUser && node.viewIdResourceName?.substringAfterLast('/')?.contains("preview", true) == true)
                Rect().also(node::getBoundsInScreen).takeIf { it.width() > 0 && it.height() > 0 }?.let(preview::add)
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child, depth + 1) } }
        }
        visit(root, 0)
        if (preview.isNotEmpty()) return preview
        // OEMs may expose preview text without an ID. Reserve the body above the known slider.
        val slider = bounds ?: shownBounds ?: return emptyList()
        val top = dp(100); val bottom = slider.top - dp(24)
        return if (bottom > top) listOf(Rect(0, top, resources.displayMetrics.widthPixels, bottom)) else emptyList()
    }

    private fun brightnessTarget(root: AccessibilityNodeInfo): Pair<GuideStep, AccessibilityNodeInfo?>? {
        val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        if (mode !in 0..1) return null
        if (preferences.brightnessGoal == BrightnessGoal.ADAPTIVE || mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return automaticBrightnessTarget(root, mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        }
        if (!preferences.brightnessBaselineReady) {
            preferences.initialBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            preferences.brightnessBaselineReady = true
        }
        if (TaskAvailability.experimental(this, GuideTask.SCREEN_BRIGHTNESS)) return phoneTarget(root, GuideTask.SCREEN_BRIGHTNESS)
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
        if (!::overlay.isInitialized || !::preferences.isInitialized || !preferences.active) return
        try { renderSnapshot(force) }
        catch (error: WindowManager.BadTokenException) { recoverRender(error) }
        catch (error: IllegalArgumentException) { recoverRender(error) }
        catch (error: IllegalStateException) { recoverRender(error) }
        catch (error: SecurityException) { recoverRender(error) }
    }
    private fun recoverRender(error: RuntimeException) {
        ServiceDiagnostics.record(this, "render_rejected", error)
        clearFeedback()
        if (!preferences.active) { receiptVisible = false; return }
        preferences.pause(PauseReason.SYSTEM_INTERRUPT)
        runCatching { overlay.showPaused(SupportCopy.pauseMessage(PauseReason.SYSTEM_INTERRUPT, preferences.textLanguage),
            preferences.textLanguage, ::resumeGuide, ::openRecovery, ::requestEnd) }
            .onFailure { ServiceDiagnostics.record(this, "recovery_window_rejected", it) }
    }
    private fun renderSnapshot(force: Boolean = false) {
        Design.appScale = preferences.appTextScale
        Design.displayScript = preferences.displayScript ?: DisplayScript.HANS
        syncSession()
        val root = rootInActiveWindow
        if (root?.packageName?.toString() !in allowedPackages()) {
            clearFeedback()
            return
        }
        seenTaskWindow = true
        if (endConfirming) {
            tts?.stop(); cloudTts?.stop()
            if (!force && renderKey == "end-confirm") return
            renderKey = "end-confirm"
            overlay.showExitConfirmation(preferences.textLanguage,
                { endConfirming = false; render(true) }, ::endGuide)
            return
        }
        TaskAvailability.block(this, preferences.activeTask)?.let { block ->
            preferences.pause(PauseReason.SYSTEM_INTERRUPT)
            tts?.stop()
            cloudTts?.stop()
            val key = "blocked:$block:${preferences.textLanguage}"
            if (!force && renderKey == key) return
            resetTargets()
            renderKey = key
            overlay.showStatus(TaskAvailability.reason(block, preferences.textLanguage), preferences.textLanguage,
                ::returnToTask, ::requestEnd)
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
            overlay.showPaused(SupportCopy.pauseMessage(preferences.sessionState.reason, l), l,
                ::resumeGuide, ::openRecovery, ::requestEnd)
            return
        }
        // Reviewed flows keep their exact selectors. Phone Settings require unique semantic controls.
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
            overlay.showStatus(if (preferences.activeTask == GuideTask.SCREEN_BRIGHTNESS &&
                    (preferences.brightnessGoal == BrightnessGoal.ADAPTIVE || Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == 1))
                    ExperienceCopy.choose(preferences.textLanguage, "没有识别到自动亮度开关，已停止圈画。请返回选择，或复制适配信息；不会自动改变设置。", "未认到自动光度开关，圈画停咗。请返去选择或者复制适配资料；唔会自动改设置。", "Adaptive brightness could not be identified. Return to choose a guide or copy compatibility info. Settings will not be changed automatically.")
                    else if (TaskAvailability.experimental(this, preferences.activeTask))
                    PhoneGuideCopy.unknown(preferences.activeTask, preferences.textLanguage) else GuideContent.unknown(preferences.textLanguage), preferences.textLanguage,
                    onResume = ::returnToTask, onExit = ::requestEnd,
                    onDiagnostics = if (TaskAvailability.experimental(this, preferences.activeTask)) ::copyPhoneDiagnostics else null)
            return
        }
        val (step, node) = target
        val changedStep = shownStep != step
        if (changedStep) {
            hintVisible = false
            preferences.collapsed = preferences.helpMode == HelpMode.LIGHT
        }
        val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
        // Only a change between circle and directional cue needs new content; scrolling coordinates do not.
        val indicator = when {
            bounds == null -> "none"
            bounds.bottom <= dp(8) -> "above"
            bounds.top >= resources.displayMetrics.heightPixels - dp(8) -> "below"
            else -> "visible"
        }
        val key = "${preferences.activeTask}:$step:$indicator:$phoneLabel:$phoneAtMaximum:${preferences.textLanguage}:${preferences.voiceLanguage}:${preferences.collapsed}:${preferences.muted}:$hintVisible:$adjusting:$toolsExpanded"
        val avoid = previewAreas(root, step, bounds)
        if (!force && key == renderKey) {
            overlay.updateGeometry(bounds, avoid)
            if (bounds != null) shownBounds = Rect(bounds)
            return
        }
        if (bounds != null) shownBounds = Rect(bounds)
        shownStep = step
        renderKey = key
        overlay.showStep(
                step = step,
                bounds = bounds,
                avoidAreas = avoid,
                message = if (adjusting && !step.isScrollHint) ExperienceCopy.adjustment(step.task, preferences.textLanguage)
                    else instruction(step, preferences.textLanguage),
                textLanguage = preferences.textLanguage,
                voiceLanguage = preferences.voiceLanguage,
                collapsed = preferences.collapsed,
                muted = preferences.muted,
                toolsExpanded = toolsExpanded,
                onTools = { toolsExpanded = !toolsExpanded; render(true) },
                onRefresh = { render(true) },
                onMove = { overlay.move(); render(true) },
                onUnavailable = { pauseGuide(PauseReason.NOT_ENOUGH_SPACE) },
                independent = preferences.helpMode == HelpMode.ON_DEMAND && !hintVisible,
                adjusting = adjusting,
                onCollapse = { preferences.collapsed = !preferences.collapsed; toolsExpanded = false; render(true) },
                onTextLanguage = { language -> preferences.textLanguage = language; render(true) },
                onVoiceLanguage = { language -> preferences.voiceLanguage = language; render(true) },
                onReplay = { speak(step, explicit = true) },
                onPause = { pauseGuide(PauseReason.USER) },
                onHint = { hintVisible = true; preferences.collapsed = false; render(true); speak(step) },
                onConfirm = ::confirmGuide,
                onAdjust = { adjusting = true; renderKey = null; render(true) },
                onMute = { preferences.muted = !preferences.muted; if (preferences.muted) { tts?.stop(); cloudTts?.stop() }; render(true) },
                onExit = ::requestEnd
            )
        if (changedStep && !step.isComplete && preferences.helpMode == HelpMode.FULL) speak(step)
    }

    private fun requestEnd() { endConfirming = true; render(true) }

    private fun instruction(step: GuideStep, language: GuideLanguage): String =
        if (step == GuideStep.BRIGHTNESS_AUTO) {
            if (preferences.brightnessGoal == BrightnessGoal.ADAPTIVE) ExperienceCopy.choose(language,
                "请打开圈住的自动亮度开关。", "请开启圈住嘅自动光度开关。", "Turn on the circled adaptive brightness switch.")
            else ExperienceCopy.choose(language, "先关闭圈住的自动亮度，再调亮度滑块。", "先关闭圈住嘅自动光度，再调滑桿。", "Turn off the circled adaptive brightness switch, then adjust the slider.")
        } else if (TaskAvailability.experimental(this, step.task)) {
            if (phoneAtMaximum && !step.isComplete && !step.isScrollHint) PhoneGuideCopy.atMaximum(step.task, language)
            else PhoneGuideCopy.instruction(step, phoneLabel, language)
        } else GuideContent.instruction(step, language)

    private fun speak(step: GuideStep, explicit: Boolean = false) {
        if ((preferences.muted && !explicit) || !preferences.sessionState.canConfirm) return
        tts?.stop()
        val message = if (adjusting && !step.isScrollHint) ExperienceCopy.adjustment(step.task, preferences.voiceLanguage)
            else instruction(step, preferences.voiceLanguage)
        val language = preferences.voiceLanguage
        cloudTts?.speak(message, language) { speakOnDevice(message, language, explicit) }
            ?: speakOnDevice(message, language, explicit)
    }

    private fun speakOnDevice(message: String, language: GuideLanguage, explicit: Boolean = false, receipt: Boolean = false) {
        if ((preferences.muted && !explicit) || !ttsReady ||
            (!preferences.sessionState.canConfirm && !(receipt && receiptVisible && rootInActiveWindow?.packageName?.toString() in allowedPackages()))) return
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
        phoneNodes = emptyList()
        tts?.stop()
        cloudTts?.stop()
        overlay.clear()
        receiptVisible = true
        val confirmed = preferences.records().firstOrNull()?.outcome == PracticeOutcome.CONFIRMED
        try { overlay.showReceipt(confirmed, preferences.textLanguage,
            onStay = { receiptVisible = false; preferences.receiptPending = false; tts?.stop(); cloudTts?.stop(); overlay.clear() },
            onReturn = {
                receiptVisible = false; tts?.stop(); cloudTts?.stop(); overlay.clear()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }) }
        catch (error: WindowManager.BadTokenException) { recoverRender(error) }
        catch (error: IllegalArgumentException) { recoverRender(error) }
        catch (error: IllegalStateException) { recoverRender(error) }
        catch (error: SecurityException) { recoverRender(error) }
        ServiceDiagnostics.record(this, if (confirmed) "guide_confirmed" else "guide_ended_unconfirmed")
        if (!preferences.muted && preferences.helpMode == HelpMode.FULL) {
            val language = preferences.voiceLanguage
            val message = if (confirmed) ExperienceCopy.choose(language, "你已确认完成。可以留在这里，或返回数字扶手。", "你确认完成咗。可以留喺呢度，或者返数字扶手。", "You confirmed completion. Stay here or return to Digital Handrail.")
                else ExperienceCopy.choose(language, "这次引导已结束，尚未确认完成。可以留在这里，或返回数字扶手。", "今次指引结束咗，未确认完成。可以留喺呢度，或者返数字扶手。", "This guide has ended without confirmation. Stay here or return to Digital Handrail.")
            cloudTts?.speak(message, language) { speakOnDevice(message, language, receipt = true) }
                ?: speakOnDevice(message, language, receipt = true)
        }
    }

    private fun openRecovery() {
        preferences.pause(PauseReason.USER)
        clearFeedback()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun returnToTask() {
        endGuide()
        receiptVisible = false; preferences.receiptPending = false; overlay.clear()
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
