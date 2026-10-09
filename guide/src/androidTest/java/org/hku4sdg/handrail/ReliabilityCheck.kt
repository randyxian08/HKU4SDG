package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File

/** Device-side regression for the reported failures. Uses real Settings and platform interrupts. */
internal class ReliabilityCheck(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private lateinit var ui: UiAutomation
    private val log = mutableListOf<String>()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        root?.let { add(it); repeat(it.childCount) { i -> addAll(nodes(it.getChild(i))) } }
    }
    private fun all() = ui.windows.flatMap { nodes(it.root) }
    private fun find(text: String, own: Boolean = true): AccessibilityNodeInfo {
        repeat(80) {
            all().firstOrNull { (!own || it.packageName?.toString() == context.packageName) &&
                (it.text?.toString() == text || it.contentDescription?.toString()?.startsWith(text) == true) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $text; " + all().mapNotNull { it.text?.toString() }.take(12))
    }
    private fun tap(text: String, own: Boolean = true) {
        find(text, own).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(200)
        var n = find(text, own)
        while (!n.isClickable) n = n.parent ?: error("Cannot tap $text")
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(400)
    }
    private fun home() {
        runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("你想在手机上做什么？")
    }
    private fun touch(text: String, own: Boolean = true) {
        // Keep the guide service running; standalone uiautomator dump suppresses it.
        val n = find(text, own)
        check(n.isVisibleToUser)
        val bounds = Rect().also(n::getBoundsInScreen)
        ui.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        SystemClock.sleep(400)
    }
    private fun set(key: String, value: String) { ui.executeShellCommand("settings put system $key $value").use { }; SystemClock.sleep(500) }
    private fun card() = ui.windows.first { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
        nodes(it.root).any { node -> node.packageName?.toString() == context.packageName && node.isClickable } }
    private fun capture(name: String) {
        ui.takeScreenshot()?.let { b -> File(context.cacheDir, "reliability-$name.png").outputStream().use {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; b.recycle() }
    }
    private fun progress(description: String) {
        val n = all().first { it.className?.toString()?.contains("SeekBar") == true &&
            if (description == "Brightness") it.packageName?.toString() == "com.android.systemui" &&
                it.viewIdResourceName == "com.android.systemui:id/slider" && it.text?.toString() == "Display brightness"
            else it.packageName?.toString() == "com.android.settings" && it.contentDescription?.toString() == description }
        val range = n.rangeInfo!!
        check(n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            val delta = if (description == "Brightness") maxOf(1f, (range.max - range.min) * .15f) else 1f
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, (range.current + delta).coerceAtMost(range.max))
        }))
    }
    fun run() {
        val prefs = GuidePreferences(context)
        val snapshot = PreferenceSnapshot(context)
        val mode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 102)
        val font = Settings.System.getFloat(context.contentResolver, "font_scale", 1f)
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active)
            ui = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nWaiting up to 10 seconds for service rebind\n") })
            repeat(100) { if (!GuideAccessibilityService.connected) SystemClock.sleep(100) }
            check(GuideAccessibilityService.connected)
            prefs.initDone = true; prefs.displayScript = DisplayScript.HANS; prefs.textLanguage = GuideLanguage.MANDARIN
            prefs.appTextScale = 1.15f; prefs.helpMode = HelpMode.FULL; prefs.muted = true; prefs.receiptPending = false
            set("font_scale", "1.0"); set("screen_brightness_mode", "0"); set("screen_brightness", "102")
            home(); tap("设置"); tap("服务状态与诊断")
            find("系统权限：已开启"); find("服务连接：已连接"); tap("复制诊断信息")
            val diagnostics = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            val parsed = org.json.JSONObject(diagnostics)
            check(parsed.getBoolean("permission_enabled") && parsed.getBoolean("service_connected"))
            check(parsed.getString("build").startsWith("0.1.2"))
            check(!parsed.has("nodes") && !parsed.has("screen") && !parsed.has("goal") && !parsed.has("audio"))
            capture("diagnostics"); log += "Versioned local diagnostics distinguish permission, connection and task state"
            tap("去系统设置开启"); tap("首次开启帮助"); find("开关不可用或提示受限制？"); capture("setup-help")
            log += "First-time restricted-settings help is reachable before or after enabling the service"
            home(); tap("把字调大"); tap("打开设置，开始")
            find("请点圈住的“Display size and text”。")
            val session = prefs.guideSessionId
            val windowId = card().id
            runner.runOnMainSync { context.getSystemService(AccessibilityManager::class.java).interrupt() }
            SystemClock.sleep(500)
            check(prefs.sessionState.phase == SessionPhase.RUNNING && prefs.guideSessionId == session)
            check(card().id == windowId) { "Feedback interruption recreated the card" }
            val after = org.json.JSONObject(ServiceDiagnostics.report(context)).getJSONArray("events")
            check((0 until after.length()).any { after.getJSONObject(it).getString("event") == "feedback_interrupted" })
            repeat(3) { ui.executeShellCommand("input swipe 540 1700 540 1550 250").use { }; SystemClock.sleep(350) }
            check(card().id == windowId) { "Scrolling recreated the card" }
            check(ServiceDiagnostics.enabled(context) && GuideAccessibilityService.connected)
            log += "Real platform interrupt preserves running attempt; scrolling reuses the same overlay window"
            touch("Display size and text", false)
            find("请亲手把圈住的“Font size”滑块向右拖动。")
            val preview = all().filter { it.packageName?.toString() == "com.android.settings" && it.isVisibleToUser &&
                it.viewIdResourceName?.substringAfterLast('/')?.contains("preview", true) == true }
            check(preview.isNotEmpty()) { "Reviewed Settings preview was not exposed" }
            val overlayBounds = Rect().also(card()::getBoundsInScreen)
            preview.forEach { val r = Rect().also(it::getBoundsInScreen); check(!Rect.intersects(overlayBounds, r)) { "Preview covered: $r $overlayBounds" } }
            capture("font-preview")
            log += "Physical touch through the non-touchable highlight opens the actual Font size page"
            tap("更多"); tap("收起提示"); touch("再听")
            check(prefs.sessionState.phase == SessionPhase.RUNNING && prefs.guideSessionId == session)
            touch("查看本步文字"); find("请亲手把圈住的“Font size”滑块向右拖动。")
            log += "Collapsed Replay and Text are reachable by physical touch; audio quality is not evaluated"
            progress("Font size"); find("现在的字读起来合适吗？"); capture("font-result")
            tap("确认完成"); find("你已确认完成"); find("留在这里"); capture("confirmed-receipt")
            check(!prefs.active && prefs.records().first().outcome == PracticeOutcome.CONFIRMED)
            tap("返回数字扶手"); find("你已确认完成"); tap("返回首页")
            log += "Font preview stays visible; actual font increase + user confirmation gives a persistent receipt"
            if (TaskAvailability.adaptiveBrightnessAvailable(context) != false) {
            // Only execute a real adaptive-switch flow when the platform exposes that capability.
            set("screen_brightness_mode", "1"); prefs.brightnessGoal = BrightnessGoal.MANUAL
            tap("调亮屏幕"); find("你希望怎样调节？"); capture("brightness-choice"); tap("打开设置，开始")
            find("先关闭圈住的自动亮度，再调亮度滑块。")
            tap("Adaptive brightness", false)
            find("请点圈住的“Brightness level”。")
            check(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == 0)
            tap("Brightness level", false); find("请亲手把圈住的亮度滑块向右拖动。")
            capture("brightness-manual"); progress("Brightness")
            find("现在的亮度舒服吗？"); tap("确认完成"); find("你已确认完成"); tap("留在这里")
            check(!prefs.active && prefs.records().first().outcome == PracticeOutcome.CONFIRMED)
            log += "Adaptive-on -> explicit manual goal -> real switch off -> slider -> checked brightness -> confirmed receipt"
            home(); tap("调亮屏幕"); tap("随环境自动调节"); tap("打开设置，开始")
            find("请打开圈住的自动亮度开关。"); tap("Adaptive brightness", false)
            find("自动亮度已开启，符合你的需要吗？"); capture("brightness-auto")
            check(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == 1)
            tap("确认完成"); find("你已确认完成"); tap("留在这里")
            log += "Manual -> explicit adaptive goal -> actual switch on -> result confirmation; no manual-slider detour"
            } else {
                prefs.brightnessGoal = BrightnessGoal.MANUAL
                tap("调亮屏幕"); find("这台设备没有提供自动亮度功能，可选择手动调节。")
                check(!find("随环境自动调节").isEnabled)
                capture("brightness-choice")
                tap("打开设置，开始"); find("请点圈住的“Brightness level”。")
                tap("Brightness level", false); find("请亲手把圈住的亮度滑块向右拖动。")
                capture("brightness-manual"); progress("Brightness")
                find("现在的亮度舒服吗？"); tap("确认完成"); find("你已确认完成"); tap("留在这里")
                check(!prefs.active && prefs.records().first().outcome == PracticeOutcome.CONFIRMED)
                log += "Actual manual brightness + checked result + confirmation; unavailable adaptive choice is disabled and explained"
                log += "LIMIT: native adaptive-switch branches cannot run on this emulator (platform capability false); covered by synthetic matching/state tests only"
            }
            home(); tap("把字调大"); tap("打开设置，开始"); tap("更多"); tap("结束，不确认"); tap("结束引导")
            find("这次引导已结束"); find("尚未确认完成。你可以随时重新开始。"); capture("ended-receipt")
            check(!prefs.active && prefs.records().first().outcome == PracticeOutcome.ENDED)
            tap("留在这里"); check(!prefs.receiptPending)
            log += "Unconfirmed exit remains distinct from completion and gives an explicit stay/return receipt"
            code = Activity.RESULT_OK; result.putString("stream", "\nPASS\n" + log.joinToString("\n") + "\n")
        } catch (e: Throwable) {
            if (::ui.isInitialized) capture("failure")
            result.putString("stream", "\nFAIL: ${e.message}\n" + log.joinToString("\n") + "\n")
        } finally {
            if (::ui.isInitialized) {
                set("font_scale", font.toString()); set("screen_brightness_mode", mode.toString()); set("screen_brightness", brightness.toString())
            }
            snapshot.restore()
        }
        runner.finish(code, result)
    }
}
