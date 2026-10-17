package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Calls live providers through the emulator bridge. No prerecorded speech is passed off as a microphone recording. */
internal class AiVoiceCheck(private val runner: Instrumentation, private val offline: Boolean) {
    private val context get() = runner.targetContext
    private lateinit var ui: UiAutomation
    private val events = mutableListOf<String>()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        root?.let { add(it); repeat(it.childCount) { i -> addAll(nodes(it.getChild(i))) } }
    }
    private fun all() = ui.windows.flatMap { nodes(it.root) }.filter { it.packageName?.toString() == context.packageName }
    private fun find(label: String, attempts: Int = 200): AccessibilityNodeInfo {
        repeat(attempts) {
            all().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing expected UI: $label")
    }
    private fun tap(label: String) {
        var node = find(label)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(200)
        node = find(label)
        while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }
    private fun home() {
        runner.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("你想在手机上做什么？")
    }
    private fun submit(goal: String) {
        check(find("输入想完成的手机任务").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, goal)
        }))
        tap("查找引导")
    }
    private fun capture(name: String) {
        ui.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "ai-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun checkPlayback(language: GuideLanguage) {
        val latch = CountDownLatch(1)
        val started = AtomicBoolean(false)
        var tts: CloudTts? = null
        runner.runOnMainSync {
            tts = CloudTts(context)
            tts!!.speak(GuideContent.instruction(GuideStep.DISPLAY_SIZE, language), language,
                onStarted = { started.set(true); latch.countDown() }, onUnavailable = { latch.countDown() })
        }
        try {
            check(latch.await(22, TimeUnit.SECONDS)) { "TTS callback timed out" }
            check(started.get() != offline) { "Unexpected cloud playback/fallback: ${language.name}" }
            if (!offline) SystemClock.sleep(800)
        } finally {
            runner.runOnMainSync { tts?.close() }
        }
        check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("handrail-tts-") }) { "Temporary speech audio was not removed" }
        events += if (offline) "Cloud speech unavailable callback; temporary audio removed"
            else "${language.name}: real cloud audio reached Android MediaPlayer.start; temporary audio removed"
    }
    fun run() {
        val prefs = GuidePreferences(context)
        val snapshot = PreferenceSnapshot(context)
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active) { "End the existing guide first" }
            ui = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.serviceInfo = ui.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nWaiting for service rebind\n") })
            repeat(100) { if (!GuideAccessibilityService.connected) SystemClock.sleep(100) }
            check(GuideAccessibilityService.connected)
            prefs.initDone = true; prefs.displayScript = DisplayScript.HANS; prefs.appTextScale = 1.15f
            prefs.textLanguage = GuideLanguage.MANDARIN; prefs.receiptPending = false
            if (offline) {
                home(); submit("把字调大")
                find("你要做的是：把字调大。对不对？")
                find(GuideContent.intentLocalFallback(GuideLanguage.MANDARIN))
                check(all().none { it.text?.toString() == GuideContent.intentMatched(GuideLanguage.MANDARIN) })
                check(!prefs.active); capture("offline-local")
                tap("不对"); submit("I can barely read the tiny letters across my phone")
                find(GuideContent.intentUnavailable(GuideLanguage.MANDARIN)); check(!prefs.active)
                capture("offline-unmatched")
                checkPlayback(GuideLanguage.MANDARIN)
                events += "Proxy stopped: local match is labelled; unmatched goal keeps task cards; no automatic task start"
            } else {
                val goals = listOf(
                    GuideLanguage.MANDARIN to "我想让整部手机显示的文字都大一些",
                    GuideLanguage.CANTONESE to "手机啲字细到睇唔清，想放大佢哋",
                    GuideLanguage.ENGLISH to "I can barely read the tiny letters across my phone")
                for ((language, goal) in goals) {
                    check(TaskMatcher.match(goal) == null) { "Use a goal that distinguishes the model from local rules" }
                    prefs.inputLanguage = language
                    home(); submit(goal)
                    find("你要做的是：把字调大。对不对？")
                    find(GuideContent.intentMatched(GuideLanguage.MANDARIN))
                    check(!prefs.active); capture("live-${language.name.lowercase()}")
                    events += "${language.name}: non-keyword goal reached model-labelled font confirmation, no automatic start"
                    checkPlayback(language)
                }
                prefs.inputLanguage = GuideLanguage.ENGLISH
                home(); submit("Make my phone easier to read")
                find(GuideContent.intentClarify(GuideLanguage.MANDARIN)); check(!prefs.active); capture("clarify")
                home(); submit("Send a message on WhatsApp")
                find(GuideContent.intentUnsupported(GuideLanguage.MANDARIN)); check(!prefs.active); capture("unsupported")
                home(); submit("Please enter my password 12345678")
                find("这段文字可能涉及私人内容，已停止发送。请使用下方任务卡。")
                check(!prefs.active); capture("private-guard")
                events += "Clarification and unsupported requests stay on home; private goal blocked locally; no automatic task start"
            }
            events += "LIMIT: no microphone-input, native-speaker listening or phone/cloud-deployment acceptance"
            code = Activity.RESULT_OK
            result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
        } catch (e: Throwable) {
            if (::ui.isInitialized) capture("failure")
            result.putString("stream", "\nFAIL: ${e.message}\n" + events.joinToString("\n") + "\n")
        } finally {
            snapshot.restore()
        }
        runner.finish(code, result)
    }
}
