package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Checks platform accessibility actions with services kept running. This is not a gesture/user study. */
class AccessibilityCheck : Instrumentation() {
    private var argumentsCheck: String? = null
    private lateinit var automation: UiAutomation
    private val events = mutableListOf<String>()
    private var overlayTask: GuideTask? = null
    private var phoneCheck = false
    private var integrationCheck = false
    override fun onCreate(arguments: Bundle?) {
        argumentsCheck = arguments?.getString("check")
        integrationCheck = arguments?.getString("check") == "integration"
        phoneCheck = arguments?.getString("check") == "phone"
        if (arguments?.getString("check") == "overlay") overlayTask =
            if (arguments.getString("task") == "brightness") GuideTask.SCREEN_BRIGHTNESS else GuideTask.FONT_SIZE
        super.onCreate(arguments); start()
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root != null) { add(root); repeat(root.childCount) { addAll(nodes(root.getChild(it))) } }
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(30) {
            val found = automation.windows.flatMap { nodes(it.root) }.firstOrNull {
                it.packageName?.toString() == targetContext.packageName &&
                    (it.text?.toString() == label || it.contentDescription?.toString()?.startsWith(label) == true)
            }
            if (found != null) return found
            SystemClock.sleep(100)
        }
        error("Missing accessible item: $label")
    }
    private fun tap(label: String) {
        val node = find(label)
        check(node.className?.toString() in setOf("android.widget.Button", "android.widget.RadioButton")) { "Wrong role: $label" }
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(300)
        var fresh = find(label)
        var bounds = Rect().also(fresh::getBoundsInScreen)
        val min = (48 * targetContext.resources.displayMetrics.density).toInt()
        // ACTION_SHOW_ON_SCREEN can leave a row partly behind a fixed footer. Scroll its parent too.
        repeat(3) {
            if (bounds.height() < min) {
                var parent = fresh.parent
                while (parent != null && !parent.isScrollable) parent = parent.parent
                parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(300)
                fresh = find(label)
                bounds = Rect().also(fresh::getBoundsInScreen)
            }
        }
        check(bounds.width() >= min && bounds.height() >= min) { "Small touch target: $label $bounds" }
        if (!fresh.isAccessibilityFocused) check(fresh.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) { "Cannot focus: $label" }
        SystemClock.sleep(150)
        check(find(label).isAccessibilityFocused) { "Focus did not move: $label" }
        capture("focus-${events.size}")
        check(find(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot activate: $label" }
        events += "focus + accessible role + >=48dp + click: $label"
        SystemClock.sleep(250)
    }
    private fun capture(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(targetContext.cacheDir, "a11y-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    override fun onStart() {
        if (argumentsCheck == "screen" || argumentsCheck == "screen-offline") {
            ScreenGuideCheck(this, argumentsCheck == "screen-offline").run(); return
        }
        if (argumentsCheck == "ai" || argumentsCheck == "ai-offline") {
            AiVoiceCheck(this, argumentsCheck == "ai-offline").run(); return
        }
        if (argumentsCheck == "reliability") { ReliabilityCheck(this).run(); return }
        if (integrationCheck) { IntegrationCheck(this).run(); return }
        if (phoneCheck) { PhoneSettingsCheck(this).run(); return }
        overlayTask?.let { OverlayCheck(this, it).run(); return }
        val prefs = GuidePreferences(targetContext)
        val snapshot = PreferenceSnapshot(targetContext)
        val input = prefs.inputLanguage
        val voice = prefs.voiceLanguage
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active) { "End the existing attempt before the accessibility check" }
            prefs.receiptPending = false
            check((targetContext.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as
                android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) {
                "Enable TalkBack before checking screen-reader focus"
            }
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = automation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            prefs.initDone = true; prefs.displayScript = DisplayScript.HANS; prefs.appTextScale = 1.15f
            prefs.textLanguage = GuideLanguage.ENGLISH
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find("What would you like to do?").let { check(it.isHeading) }
            tap("Make the text larger.")
            find("Make text larger").let { check(it.isHeading) }
            tap("Guide details.")
            find("Guide details.").let { check(it.contentDescription.toString().contains("Expanded")) }
            tap("Back")
            tap("Settings")
            tap("粤语 · Cantonese")
            find("文字同声音")
            check(prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            tap("简体中文")
            find("文字同声音")
            check(prefs.textLanguage == GuideLanguage.CANTONESE) { "Writing style changed the text language" }
            capture("cantonese")
            tap("普通话 · Mandarin")
            find("字和声音")
            check(prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            tap("完成")
            find("你想在手机上做什么？")
            tap("记录")
            find("你的每一次尝试")
            events += "Trilingual page refresh; input and voice unchanged; history reachable"
            capture("history")
            result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            if (::automation.isInitialized) capture("failure")
            result.putString("stream", "\nFAIL: ${error.message}\n" + events.joinToString("\n") + "\n")
            code = Activity.RESULT_CANCELED
        } finally {
            // Instrumentation finish terminates this process; commit the restored preference first.
            snapshot.restore()
        }
        finish(code, result)
    }
}
