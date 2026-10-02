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
                    nodes(candidate.root).any { it.isClickable && it.packageName?.toString() == targetContext.packageName }
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
        val text = prefs.textLanguage; val input = prefs.inputLanguage; val voice = prefs.voiceLanguage; val mute = prefs.muted
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
            click(if (task == GuideTask.FONT_SIZE) "把字调大." else "调亮屏幕.")
            click("打开设置，开始")
            find("收起提示")
            val session = prefs.guideSessionId
            val expanded = checkClearTarget()
            capture("expanded")
            click("收起提示")
            val compact = checkClearTarget()
            check(compact.height() < expanded.height()) { "Collapsing does not shrink the card" }
            log += "compact ${compact.height()}px < expanded ${expanded.height()}px"
            capture("compact")
            click("换个位置")
            checkClearTarget()
            val compactHeader = find("查看本步文字")
            check(compactHeader.isVisibleToUser && bounds(compactHeader).height() >= (48 * targetContext.resources.displayMetrics.density).toInt())
            capture("compact-moved")
            click("查看本步文字")
            checkClearTarget()
            click("更多")
            val replayBefore = bounds(find("听一次")); val pauseBefore = bounds(find("先停一下"))
            val scroll = nodes(overlay().root).first { it.isScrollable }
            repeat(2) { scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(150) }
            check(bounds(find("听一次")) == replayBefore && bounds(find("先停一下")) == pauseBefore) { "Primary controls scroll away" }
            log += "Replay and pause remain fixed while options scroll"
            capture("more-scrolled")
            click("文字 · 普通话"); click("English")
            check(prefs.textLanguage == GuideLanguage.ENGLISH && prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            click("Text · English"); click("粤语")
            check(prefs.textLanguage == GuideLanguage.CANTONESE && prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            click("文字 · 粤语"); click("普通话")
            check(prefs.textLanguage == GuideLanguage.MANDARIN && prefs.inputLanguage == input && prefs.voiceLanguage == voice)
            log += "Direct language selection; input and voice unchanged"
            click("结束，不确认")
            find("结束这次引导？", false); check(prefs.active && prefs.guideSessionId == session)
            capture("end-confirmation")
            click("继续这次任务"); check(prefs.active && prefs.guideSessionId == session)
            click("换个位置"); val moved = checkClearTarget()
            check(moved.height() >= expanded.height()) { "Moving squeezes the primary instruction: $moved, initial $expanded" }
            log += "Moving preserves the full primary instruction; compact card can also move"
            capture("moved")
            click("先停一下"); find("指引已暂停", false)
            check(prefs.sessionState.phase == SessionPhase.PAUSED && prefs.guideSessionId == session)
            capture("paused")
            click("继续指引"); find("听一次")
            check(prefs.sessionState.phase == SessionPhase.RUNNING && prefs.guideSessionId == session)
            val resumed = checkClearTarget()
            check((resumed.top < 1000) == (moved.top < 1000)) { "Chosen side changes after pause" }
            if (roots().any { it.text?.toString() == "收起更多" }) click("收起更多")
            click("更多"); click("结束，不确认"); click("结束引导")
            check(!prefs.active && prefs.guideSessionId == session)
            val records = prefs.records().filter { it.session == session }
            check(records.size == 1 && records.single().outcome == PracticeOutcome.ENDED)
            log += "Cancel end, pause and resume preserve the same attempt; explicit exit records one unconfirmed result"
            result.putString("stream", "\nPASS\n" + log.joinToString("\n") + "\n"); code = Activity.RESULT_OK
        } catch (e: Throwable) { result.putString("stream", "\nFAIL: ${e.message}\n" + log.joinToString("\n") + "\n") }
        finally {
            targetContext.getSharedPreferences("guide", 0).edit().putString("text", text.name).putBoolean("muted", mute).commit()
        }
        runner.finish(code, result)
    }
}
