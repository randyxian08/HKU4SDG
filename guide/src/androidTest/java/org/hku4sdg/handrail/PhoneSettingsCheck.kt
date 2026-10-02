package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

/** Native Chinese Settings regression, not a vivo hardware or TalkBack acceptance test. */
internal class PhoneSettingsCheck(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private lateinit var automation: UiAutomation
    private val events = mutableListOf<String>()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        root?.let { add(it); repeat(it.childCount) { i -> addAll(nodes(it.getChild(i))) } }
    }
    private fun all() = automation.windows.flatMap { nodes(it.root) }
    private fun await(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(60) { all().firstOrNull(predicate)?.let { return it }; SystemClock.sleep(100) }
        error("Missing $label; visible labels: " + all().mapNotNull { it.text?.toString() }.take(18))
    }
    private fun appText(value: String) = await(value) {
        it.packageName?.toString() == context.packageName && it.text?.toString()?.startsWith(value) == true
    }
    private fun tapApp(value: String) {
        var node = await(value) { it.packageName?.toString() == context.packageName &&
            (it.text?.toString() == value || it.contentDescription?.toString()?.startsWith(value) == true) }
        while (!node.isClickable) node = node.parent ?: error("Cannot click $value")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    private fun openHome() {
        runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        appText("今天想做什么")
    }
    private fun capture(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "phone-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
    private fun nativeRow(names: Set<String>): AccessibilityNodeInfo {
        repeat(8) {
            all().filter { it.packageName?.toString() == "com.android.settings" && it.text?.toString() in names }
                .firstOrNull { label ->
                    var current: AccessibilityNodeInfo? = label
                    var depth = 0
                    while (current != null && !current.isClickable && depth++ < 40) current = current.parent
                    current?.isClickable == true
                }?.let { return it }
            all().firstOrNull { it.packageName?.toString() == "com.android.settings" && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        error("Missing native row $names")
    }
    private fun tapNative(names: Set<String>) {
        var node = nativeRow(names)
        while (!node.isClickable) node = node.parent ?: error("Cannot click native row $names")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(400)
    }
    private fun raiseSlider(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val slider = await("adjustable native control", predicate)
        val range = slider.rangeInfo ?: error("No native range")
        val value = (range.current + 1f.coerceAtLeast((range.max - range.min) / 8f)).coerceAtMost(range.max)
        check(value > range.current)
        check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
    }
    fun run() {
        val prefs = GuidePreferences(context)
        val guide = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
        val practice = context.getSharedPreferences("practice", Context.MODE_PRIVATE)
        val oldGuide = guide.all
        val oldPractice = practice.all
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        fun restore(store: android.content.SharedPreferences, values: Map<String, *>) {
            val edit = store.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            } }; edit.commit()
        }
        try {
            check(!prefs.active) { "End the existing attempt before the phone regression" }
            check(context.resources.configuration.locales[0].language == "zh") { "Use real Chinese system pages for this regression" }
            automation = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = automation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nWaiting up to 10 seconds for the guide service rebind\n") })
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!GuideAccessibilityService.connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            check(GuideAccessibilityService.connected) { "Rebind the guide service after instrumentation starts" }
            guide.edit().putString("text", GuideLanguage.MANDARIN.name).putBoolean("muted", true).commit()
            check(TaskAvailability.experimental(context, GuideTask.FONT_SIZE))
            check(TaskAvailability.block(context, GuideTask.FONT_SIZE) == null)
            openHome(); appText("2 项可试"); capture("home-zh")
            tapApp("把字调大")
            appText("手机实验引导：")
            tapApp("打开设置，开始")
            nativeRow(setOf("显示大小和文字", "字体大小", "字体大小与粗细"))
            appText("请点圈住的")
            capture("font-row-zh")
            tapNative(setOf("显示大小和文字", "字体大小", "字体大小与粗细"))
            appText("请亲手把圈住的字体大小")
            capture("font-slider-zh")
            val session = prefs.guideSessionId
            tapApp("收起提示"); appText("查看本步文字")
            tapApp("换位置"); appText("查看本步文字"); capture("font-compact-zh")
            tapApp(SupportCopy.pause(GuideLanguage.MANDARIN)); appText(SupportCopy.paused(GuideLanguage.MANDARIN))
            check(prefs.sessionState.phase == SessionPhase.PAUSED)
            tapApp(SupportCopy.resume(GuideLanguage.MANDARIN)); appText("请亲手把圈住的字体大小")
            check(prefs.guideSessionId == session && prefs.sessionState.phase == SessionPhase.RUNNING)
            events += "Phone overlay collapse + move + pause/resume keep the same attempt"
            raiseSlider { it.packageName?.toString() == "com.android.settings" &&
                it.className?.toString()?.contains("SeekBar") == true && it.contentDescription?.toString() == "字体大小" }
            appText(ExperienceCopy.question(GuideTask.FONT_SIZE, GuideLanguage.MANDARIN))
            tapApp(ExperienceCopy.confirm(GuideLanguage.MANDARIN))
            await("confirmed history") { it.text?.toString() == "你已确认完成" }
            check(prefs.records().first().outcome == PracticeOutcome.CONFIRMED)
            events += "Chinese Settings font row + actual slider + system value increase + user confirmation"
            openHome(); tapApp("调亮屏幕"); tapApp("打开设置，开始")
            if (all().none { it.packageName?.toString() == context.packageName && it.text?.toString()?.startsWith("请亲手把圈住的亮度") == true }) {
                appText("请点圈住的")
                tapNative(setOf("亮度级别", "亮度", "屏幕亮度"))
            }
            appText("请亲手把圈住的亮度")
            capture("brightness-slider-zh")
            raiseSlider { it.packageName?.toString() in setOf("com.android.settings", "com.android.systemui") &&
                it.className?.toString()?.contains("SeekBar") == true &&
                (it.viewIdResourceName?.contains("slider") == true || it.viewIdResourceName?.contains("brightness") == true) }
            appText(ExperienceCopy.question(GuideTask.SCREEN_BRIGHTNESS, GuideLanguage.MANDARIN))
            tapApp(ExperienceCopy.confirm(GuideLanguage.MANDARIN))
            await("confirmed brightness history") { it.text?.toString() == "你已确认完成" }
            events += "Chinese Settings brightness row + actual slider + value increase + user confirmation"
            openHome(); tapApp("把字调大"); tapApp("打开设置，开始")
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            tapApp("复制适配信息")
            runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            SystemClock.sleep(300)
            val raw = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                ?: error("Missing compatibility clipboard")
            val report = JSONObject(raw)
            check(!report.has("serial") && !report.has("imei"))
            val exported = report.getJSONArray("nodes")
            for (i in 0 until exported.length()) {
                val node = exported.getJSONObject(i)
                listOf("label", "description").forEach { field ->
                    val label = node.getString(field)
                    check(label.isEmpty() || PhoneSettingsMatcher.safeLabel(label) == label)
                }
            }
            File(context.cacheDir, "phone-diagnostic.json").writeText(raw)
            events += "Unknown Settings page: no circled target; explicit redacted compatibility export"
            prefs.finish(PracticeOutcome.ENDED)
            code = Activity.RESULT_OK
            result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
        } catch (error: Throwable) {
            result.putString("stream", "\nFAIL: ${error.message}\n" + events.joinToString("\n") + "\n")
        } finally {
            restore(guide, oldGuide); restore(practice, oldPractice)
        }
        runner.finish(code, result)
    }
}
