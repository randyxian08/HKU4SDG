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
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Real Settings/UI regression: onboarding languages, independence, and no timed completion. */
internal class IntegrationCheck(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private lateinit var ui: UiAutomation
    private val events = mutableListOf<String>()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        root?.let { add(it); repeat(it.childCount) { i -> addAll(nodes(it.getChild(i))) } }
    }
    private fun all() = ui.windows.flatMap { nodes(it.root) }
    private fun find(label: String, app: Boolean = true, attempts: Int = 60): AccessibilityNodeInfo {
        repeat(attempts) {
            all().firstOrNull { (!app || it.packageName?.toString() == context.packageName) &&
                (it.text?.toString() == label || it.contentDescription?.toString()?.startsWith(label) == true) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $label; " + all().mapNotNull { it.text?.toString() }.take(20))
    }
    private fun tap(label: String, app: Boolean = true) {
        var node = find(label, app)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(150)
        node = find(label, app)
        while (!node.isClickable) node = node.parent ?: error("Cannot activate $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(300)
    }
    private fun home() {
        runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(350)
    }
    private fun capture(label: String) {
        ui.takeScreenshot()?.let { b ->
            File(context.cacheDir, "integration-$label.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
    }
    fun run() {
        val prefs = GuidePreferences(context)
        val guide = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
        val snapshot = PreferenceSnapshot(context)
        val initialSystemFont = Settings.System.getFloat(context.contentResolver, "font_scale", 1f)
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active)
            prefs.receiptPending = false
            ui = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = ui.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nWaiting for service rebind\n") })
            repeat(100) { if (!GuideAccessibilityService.connected) SystemClock.sleep(100) }
            check(GuideAccessibilityService.connected)
            guide.edit().putBoolean("initDone", false).putInt("initStep", 0).putFloat("appTextScale", 1.15f).commit()
            home(); tap("English")
            find("Choose a comfortable text size"); capture("onboarding-en")
            tap("Continue"); find("What language will you use?")
            tap("粤语 · Cantonese"); tap("Continue")
            tap("English"); tap("Continue")
            find("Your guide is ready"); tap("Try a guide"); tap("Tap here")
            check(prefs.initDone && prefs.inputLanguage == GuideLanguage.CANTONESE && prefs.voiceLanguage == GuideLanguage.ENGLISH && prefs.textLanguage == GuideLanguage.ENGLISH)
            find("What would you like to do?"); capture("home-en")
            tap("Settings"); find("Text and voice")
            // Change written replies alone; input and spoken replies must remain unchanged.
            tap("普通话 · Mandarin")
            check(prefs.textLanguage == GuideLanguage.MANDARIN && prefs.inputLanguage == GuideLanguage.CANTONESE && prefs.voiceLanguage == GuideLanguage.ENGLISH)
            tap("完成"); find("你想在手机上做什么？"); capture("home-zh")
            val input = find("输入想完成的手机任务")
            val inputBounds = Rect().also(input::getBoundsInScreen)
            val firstTask = Rect().also(find("把字调大").let { node -> var n = node; while (!n.isClickable) n = n.parent; n }::getBoundsInScreen)
            check(inputBounds.top < firstTask.top) { "Goal input is not before task shortcuts" }
            events += "English onboarding; independent input/text/voice; goal input precedes tasks"
            prefs.muted = true
            tap("输入想完成的手机任务")
            check(find("输入想完成的手机任务").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "把字调大")
            }))
            tap("查找引导")
            find("你要做的是：把字调大。对不对？", attempts = 200)
            capture("goal-confirmation")
            tap("对"); tap("轻提示"); tap("打开设置，开始")
            find("查看本步文字")
            check(prefs.helpMode == HelpMode.LIGHT && prefs.collapsed)
            tap("更多"); tap("结束，不确认"); tap("结束引导")
            check(!prefs.active && prefs.records().first().outcome == PracticeOutcome.ENDED)
            tap("返回数字扶手"); find("这次引导已结束"); tap("返回首页")
            events += "Typed goal leads to confirmation; light help starts collapsed; exit stays unconfirmed"
            prefs.helpMode = HelpMode.ON_DEMAND; prefs.muted = true
            tap("把字调大"); tap("自己先试试"); tap("打开设置，开始")
            find(ExperienceCopy.own(GuideLanguage.MANDARIN)); tap("给我提示")
            find("请点圈住的“Display size and text”。")
            tap("更多"); tap("先停一下")
            find("指引已暂停"); tap("继续指引"); tap("给我提示")
            tap("更多"); tap("收起提示")
            find("查看本步文字"); capture("collapsed")
            tap("查看本步文字")
            tap("Display size and text", false)
            tap("给我提示")
            find("请亲手把圈住的“Font size”滑块向右拖动。")
            val initial = Settings.System.getFloat(context.contentResolver, "font_scale", 1f)
            SystemClock.sleep(13_000)
            check(prefs.active && prefs.records().none { it.session == prefs.guideSessionId && it.outcome == PracticeOutcome.CONFIRMED })
            check(all().none { it.packageName?.toString() == context.packageName && it.text?.toString() == "确认完成" }) { "Completion appeared without changing the slider" }
            capture("unchanged-after-13s")
            val slider = all().first { it.packageName?.toString() == "com.android.settings" && it.contentDescription?.toString() == "Font size" && it.className?.toString()?.contains("SeekBar") == true }
            val range = slider.rangeInfo!!
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, (range.current + 1).coerceAtMost(range.max))
            }))
            find("现在的字读起来合适吗？"); capture("result-confirmation")
            check(Settings.System.getFloat(context.contentResolver, "font_scale", 1f) > initial)
            tap("确认完成")
            find("你已确认完成")
            events += "On-demand help, pause/resume, collapse; 13 seconds idle cannot complete; real font increase plus explicit confirmation"
            code = Activity.RESULT_OK
            result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
        } catch (e: Throwable) {
            if (::ui.isInitialized) capture("failure")
            result.putString("stream", "\nFAIL: ${e.message}\n" + events.joinToString("\n") + "\n")
        } finally {
            if (::ui.isInitialized) ui.executeShellCommand("settings put system font_scale $initialSystemFont").close()
            snapshot.restore()
        }
        runner.finish(code, result)
    }
}
