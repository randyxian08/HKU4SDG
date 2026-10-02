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
    private lateinit var automation: UiAutomation
    private val events = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
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
        check(node.className?.toString() == "android.widget.Button") { "Wrong role: $label" }
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(100)
        val fresh = find(label)
        val bounds = Rect().also(fresh::getBoundsInScreen)
        val min = (48 * targetContext.resources.displayMetrics.density).toInt()
        check(bounds.width() >= min && bounds.height() >= min) { "Small touch target: $label $bounds" }
        if (!fresh.isAccessibilityFocused) check(fresh.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) { "Cannot focus: $label" }
        SystemClock.sleep(150)
        check(find(label).isAccessibilityFocused) { "Focus did not move: $label" }
        capture("focus-${events.size}")
        check(find(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot activate: $label" }
        events += "focus + Button + >=48dp + click: $label"
        SystemClock.sleep(250)
    }
    private fun select(label: String) {
        var node = find(label)
        while (!node.isClickable) node = node.parent ?: error("Menu item cannot activate: $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
        val prefs = GuidePreferences(targetContext)
        val oldText = prefs.textLanguage
        val input = prefs.inputLanguage
        val voice = prefs.voiceLanguage
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active) { "End the existing attempt before the accessibility check" }
            check((targetContext.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as
                android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) {
                "Enable TalkBack before checking screen-reader focus"
            }
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = automation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            prefs.textLanguage = GuideLanguage.ENGLISH
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find("What would you like to do?").let { check(it.isHeading) }
            tap("Make text larger.")
            find("Make text larger").let { check(it.isHeading) }
            tap("Guide details.")
            find("Guide details.").let { check(it.contentDescription.toString().contains("Expanded")) }
            tap("Back")
            tap("Language")
            tap("App text and AI replies.")
            // Popup menu entries use Android's native menu semantics, not the custom Button role.
            select("粤语")
            find("按你嘅习惯嚟")
            check(prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            capture("cantonese")
            tap("界面文字 / AI 提示.")
            select("普通话")
            find("按你的习惯来")
            check(prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            tap("返回")
            find("今天想做什么？")
            tap("记录")
            find("你的每一次尝试")
            events += "Trilingual page refresh; input and voice unchanged; history reachable"
            capture("history")
            result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "\nFAIL: ${error.message}\n" + events.joinToString("\n") + "\n")
            code = Activity.RESULT_CANCELED
        } finally {
            // Instrumentation finish terminates this process; commit the restored preference first.
            targetContext.getSharedPreferences("guide", 0).edit().putString("text", oldText.name).commit()
        }
        finish(code, result)
    }
}
