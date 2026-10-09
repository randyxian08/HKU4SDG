package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File

/** Actual overlay actions and geometry with the guide service running; no provider or user-study claims. */
class OverlayCheck(private val runner: Instrumentation, private val task: GuideTask) {
    private val targetContext get() = runner.targetContext
    private lateinit var ui: UiAutomation
    private lateinit var prefs: GuidePreferences
    private val log = mutableListOf<String>()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root != null) { add(root); repeat(root.childCount) { addAll(nodes(root.getChild(it))) } }
    }
    private fun roots() = ui.windows.flatMap { nodes(it.root) }
    private fun find(value: String, button: Boolean = true): AccessibilityNodeInfo {
        repeat(40) {
            val n = roots().firstOrNull {
                it.packageName?.toString() == targetContext.packageName &&
                    (it.text?.toString() == value || it.contentDescription?.toString()?.startsWith(value) == true) &&
                    (!button || it.isClickable)
            }
            if (n != null) return n
            SystemClock.sleep(100)
        }
        error("Missing overlay item: $value")
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun click(value: String) {
        val node = find(value)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(150)
        val fresh = find(value)
        check(fresh.isVisibleToUser) { "Not visible: $value" }
        val rect = bounds(fresh); val min = (48 * targetContext.resources.displayMetrics.density).toInt()
        check(rect.width() >= min && rect.height() >= min) { "Small target: $value $rect" }
        check(fresh.className.toString() in setOf("android.widget.Button", "android.widget.RadioButton")) { "Missing role: $value" }
        check(fresh.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350)
        log += "click + role + >=48dp: $value"
    }
    private fun overlay(): AccessibilityWindowInfo {
        repeat(30) {
            val window = ui.windows.firstOrNull { candidate ->
                candidate.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    nodes(candidate.root).any {
                        it.packageName?.toString() == targetContext.packageName &&
                            (it.text?.toString()?.length ?: 0) > 4
                    }
            }
            if (window != null) return window
            SystemClock.sleep(100)
        }
        error("Guide card window did not become available")
    }
    private fun checkClearTarget(): Rect {
        val targetText = if (task == GuideTask.FONT_SIZE) "Display size and text" else "Brightness level"
        repeat(20) {
            val target = roots().firstOrNull { it.packageName?.toString() == "com.android.settings" && it.text?.toString() == targetText && it.isVisibleToUser }
            if (target != null) {
                var control: AccessibilityNodeInfo = target
                while (!control.isClickable) {
                    val ancestor = control.parent ?: break
                    control = ancestor
                }
                val card = Rect().also(overlay()::getBoundsInScreen)
                check(!Rect.intersects(card, bounds(control))) { "Card covers $targetText: $card ${bounds(control)}" }
                log += "card clear of $targetText: $card"
                return card
            }
            roots().firstOrNull { it.packageName?.toString() == "com.android.settings" && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        error("Reviewed Settings target is not visible")
    }
    private fun capture(name: String) {
        ui.takeScreenshot()?.let { bitmap ->
            File(targetContext.cacheDir, "overlay-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    fun run() {
        prefs = GuidePreferences(targetContext)
        val text = prefs.textLanguage; val mute = prefs.muted
        var code = Activity.RESULT_CANCELED; val result = Bundle()
        try {
            check(!prefs.active) { "End the current guide before checking overlays" }
            prefs.textLanguage = GuideLanguage.MANDARIN; prefs.muted = true
            ui = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = ui.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            // Starting instrumentation kills the prior target process; the host rebinds the guide.
            repeat(100) { if (!GuideAccessibilityService.connected) SystemClock.sleep(100) }
            check(GuideAccessibilityService.connected) { "Rebind the guide service after starting this check" }
            prefs.setupTask = null
            runner.startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click(if (task == GuideTask.FONT_SIZE) "把字调大" else "调亮屏幕")
            click("打开设置，开始")
            find(if (task == GuideTask.FONT_SIZE) "请点圈住的“Display size and text”。" else "请点圈住的“Brightness level”。", false)
            val session = prefs.guideSessionId
            checkClearTarget()
            check(roots().none { it.text?.toString() in setOf("收起提示", "换个位置", "听一次", "更多") }) { "Old utility buttons are still on the card" }
            capture("sentence")
            prefs.finish(PracticeOutcome.ENDED)
            check(!prefs.active && prefs.guideSessionId == session)
            log += "Short card shows one sentence, stays clear of the target, and has no utility row"
            result.putString("stream", "\nPASS\n" + log.joinToString("\n") + "\n"); code = Activity.RESULT_OK
        } catch (e: Throwable) { result.putString("stream", "\nFAIL: ${e.message}\n" + log.joinToString("\n") + "\n") }
        finally {
            targetContext.getSharedPreferences("guide", 0).edit().putString("text", text.name).putBoolean("muted", mute).commit()
        }
        runner.finish(code, result)
    }
}
