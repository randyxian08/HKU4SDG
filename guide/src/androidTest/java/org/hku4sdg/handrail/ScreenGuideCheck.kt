package org.hku4sdg.handrail

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Real apps + real model. All touches are test gestures, never product auto-actions. */
internal class ScreenGuideCheck(private val runner: Instrumentation, private val offline: Boolean) {
    private val context = runner.targetContext
    private lateinit var ui: UiAutomation
    private val events = mutableListOf<String>()
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (node != null) { add(node); repeat(node.childCount) { addAll(nodes(node.getChild(it))) } }
    }
    private fun find(label: String, pkg: String = context.packageName): AccessibilityNodeInfo {
        repeat(80) {
            ui.windows.flatMap { nodes(it.root) }.firstOrNull {
                it.packageName?.toString() == pkg && (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI: $label")
    }
    private fun tap(label: String, pkg: String = context.packageName) {
        var node = find(label, pkg)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(200); node = find(label, pkg)
        while (!node.isClickable) node = node.parent ?: error("No clickable parent: $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(300)
    }
    private fun setText(label: String, value: String) {
        check(find(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private fun capture(name: String) {
        val needsAdvice = name.endsWith("target") || name == "fdroid-search-input" || name.endsWith("results") || name.endsWith("awaiting-confirmation") || name == "clock-timer-complete"
        repeat(if (needsAdvice) 50 else 1) {
            val before = main { GuideAccessibilityService.screenDebug?.currentAdvice }
            if (needsAdvice && before == null) { SystemClock.sleep(100); return@repeat }
            // WindowManager geometry is applied on a later frame than the model callback.
            if (needsAdvice) SystemClock.sleep(250)
            val image = ui.takeScreenshot() ?: error("Screenshot unavailable")
            val unchanged = !needsAdvice || before == main { GuideAccessibilityService.screenDebug?.currentAdvice }
            if (unchanged) {
                File(context.cacheDir, "screen-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle(); return
            }
            image.recycle(); SystemClock.sleep(100)
        }
        error("No stable advice for screenshot: $name")
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        runner.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitState(vararg states: String): String {
        repeat(450) {
            val state = main { GuideAccessibilityService.screenDebug?.state.orEmpty() }
            val settled = main { GuideAccessibilityService.screenDebug?.let { guide ->
                guide.currentAdvice?.action?.uppercase() == state &&
                    (state !in setOf("TAP", "TYPE", "SCROLL_FORWARD", "SCROLL_BACKWARD") || guide.currentTarget != null)
            } == true }
            if (state in states && (state in setOf("UNAVAILABLE", "PRIVATE") || settled)) return state
            if (state in setOf("PRIVATE", "UNAVAILABLE", "NO_CONTROLS", "UNREADABLE", "NO_ROOM", "LIMIT", "WINDOW_UNAVAILABLE", "PAUSED")) error("Expected ${states.toList()}, got $state: ${main { GuideAccessibilityService.screenDebug?.lastFailure }}")
            SystemClock.sleep(100)
        }
        error("Expected ${states.toList()}, got ${main { GuideAccessibilityService.screenDebug?.state }}")
    }
    private fun waitNewAdvice(previousSnapshot: String): String {
        repeat(450) {
            val advice = main { GuideAccessibilityService.screenDebug?.currentAdvice }
            if (advice != null && advice.snapshotId != previousSnapshot) {
                waitState(advice.action.uppercase()); return advice.action.uppercase()
            }
            val state = main { GuideAccessibilityService.screenDebug?.state.orEmpty() }
            if (state in setOf("PRIVATE", "UNAVAILABLE", "NO_CONTROLS", "UNREADABLE", "NO_ROOM", "LIMIT", "WINDOW_UNAVAILABLE", "PAUSED"))
                error("Next page failed: $state: ${main { GuideAccessibilityService.screenDebug?.lastFailure }}")
            SystemClock.sleep(100)
        }
        error("No new-page advice; state=${main { GuideAccessibilityService.screenDebug?.state }}; paused=${ScreenGuideSession.current?.paused}")
    }
    private fun start(pkg: String, goal: String, image: Boolean, launch: Boolean = true) {
        main {
            ScreenGuideSession.start(goal, pkg, "Test app", image)
            if (launch) context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuideAccessibilityService.refreshScreenGuide()
        }
    }
    private fun stop() {
        main { ScreenGuideSession.stop(); GuideAccessibilityService.refreshScreenGuide() }
        SystemClock.sleep(300)
    }
    private fun touchTarget(expectedWord: String) {
        val target = main { GuideAccessibilityService.screenDebug?.currentTarget } ?: error("No grounded target")
        check(target.label.contains(expectedWord, ignoreCase = true) || (expectedWord == "Search" && target.editable)) { "Wrong target: ${target.label}" }
        val bounds = Rect(target.left, target.top, target.right, target.bottom)
        var clear = false
        repeat(30) {
            if (!clear) {
                val cards = ui.windows.filter { window -> nodes(window.root).any {
                    it.packageName?.toString() == context.packageName && it.className?.toString() == "android.widget.Button"
                } }
                clear = cards.isNotEmpty() && cards.none { Rect.intersects(Rect().also(it::getBoundsInScreen), bounds) }
                if (!clear) SystemClock.sleep(100)
            }
        }
        check(clear) { "Floating card overlaps grounded target: $bounds" }
        // Physical touches verify that the floating card does not intercept the circled target.
        val at = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(ui.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(80)
        }
        events += "User test gesture on actual node ${target.id}, ${target.label}, $bounds"
    }
    fun run() {
        val prefs = GuidePreferences(context); val saved = PreferenceSnapshot(context)
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        try {
            check(!prefs.active && ScreenGuideSession.current == null) { "End existing guides before this check" }
            ui = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nWaiting for service rebind\n") })
            repeat(150) { if (!GuideAccessibilityService.connected) SystemClock.sleep(100) }
            check(GuideAccessibilityService.connected)
            prefs.initDone = true; prefs.displayScript = DisplayScript.HANS; prefs.textLanguage = GuideLanguage.MANDARIN
            prefs.voiceLanguage = GuideLanguage.CANTONESE; prefs.appTextScale = 1.15f; prefs.receiptPending = false
            prefs.muted = true; prefs.collapsed = false
            if (offline) {
                start("com.google.android.deskclock", "打开时钟的计时器页面", false)
                waitState("UNAVAILABLE"); check(main { GuideAccessibilityService.screenDebug?.currentTarget } == null)
                capture("offline"); events += "Stopped proxy: explicit failure and no target, no invented fallback step"
            } else {
                main { context.startActivity(context.packageManager.getLaunchIntentForPackage("org.fdroid.fdroid")!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                SystemClock.sleep(800)
                // Test setup handles the first-run system notification prompt; product code never does.
                ui.windows.flatMap { nodes(it.root) }.firstOrNull {
                    it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true ||
                        it.text?.toString() in setOf("Don’t allow", "Don't allow", "不允许")
                }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(500)
                // The real entry path must not start or send page content before consent.
                runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                tap("看屏幕，帮我下一步")
                setText("你希望在 App 里完成什么", "打开这个应用的设置页面")
                tap("选择一个 App"); tap("F-Droid")
                capture("consent")
                tap("同意并打开 App")
                check(ScreenGuideSession.current == null) { "Started without user consent" }
                tap("本次引导期间，同意把所选 App 当前页面的可见控件文字交给云端 AI 分析。")
                tap("同意并打开 App")
                waitState("TAP"); capture("fdroid-settings-target")
                val first = main { GuideAccessibilityService.screenDebug?.currentAdvice }!!
                check(Regex("[\\u3400-\\u9fff]").containsMatchIn(first.instruction))
                check(Regex("[\\u3400-\\u9fff]").containsMatchIn(first.spokenInstruction))
                events += "Real model, Mandarin text + Cantonese speech text: ${first.instruction} / ${first.spokenInstruction}"
                val speechStarts = GuideAccessibilityService.screenSpeechStarts
                tap("听一次")
                repeat(180) { if (GuideAccessibilityService.screenSpeechStarts == speechStarts) SystemClock.sleep(100) }
                check(GuideAccessibilityService.screenSpeechStarts > speechStarts) { "Dynamic spoken instruction did not start cloud playback" }
                events += "Replay of model-generated Cantonese instruction reached Android MediaPlayer.start"
                waitState("TAP")
                touchTarget("Settings")
                waitState("COMPLETE"); capture("fdroid-complete-awaiting-confirmation")
                check(ScreenGuideSession.current != null) { "Model auto-completed the session" }
                tap("我确认完成了"); find("你已确认完成"); capture("fdroid-user-confirmed")
                check(ScreenGuideSession.current == null); tap("留在这里")
                events += "F-Droid: unregistered app, real controls -> Settings -> model result -> explicit user confirmation"

                // A multi-step search uses the same planner, not an app-specific flow.
                prefs.textLanguage = GuideLanguage.ENGLISH; prefs.voiceLanguage = GuideLanguage.MANDARIN
                main { context.startActivity(context.packageManager.getLaunchIntentForPackage("org.fdroid.fdroid")!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                SystemClock.sleep(900)
                // Reset only the synthetic query left by a preceding run, using the app's real UI.
                // Clearing an Activity task alone does not clear F-Droid's retained search query.
                tap("Search", "org.fdroid.fdroid")
                var emptySearch: AccessibilityNodeInfo? = null
                repeat(50) {
                    if (emptySearch == null) {
                        emptySearch = ui.windows.flatMap { nodes(it.root) }.firstOrNull {
                            it.packageName?.toString() == "org.fdroid.fdroid" && it.isEditable
                        }
                        if (emptySearch == null) SystemClock.sleep(100)
                    }
                }
                check(emptySearch != null) { "Could not reset test search query" }
                check(emptySearch!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }))
                // This freshly installed test app retains our Wikipedia query in history too.
                // Without this reset, selecting that valid shortcut skips the keyboard path.
                if (ui.windows.flatMap { nodes(it.root) }.any {
                    it.packageName?.toString() == "org.fdroid.fdroid" && it.text?.toString() == "Wikipedia"
                }) tap("Clear", "org.fdroid.fdroid")
                if (ui.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                    check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)); SystemClock.sleep(400)
                }
                tap("Discover", "org.fdroid.fdroid"); SystemClock.sleep(400)
                start("org.fdroid.fdroid", "Search for Wikipedia in this app. Finish when the search results are visible.", false)
                waitState("TAP"); capture("fdroid-search-target")
                var previous = main { GuideAccessibilityService.screenDebug?.currentAdvice?.snapshotId }!!
                touchTarget("Search")
                var inputAction = waitNewAdvice(previous)
                repeat(2) {
                    if (inputAction == "TAP") {
                        previous = main { GuideAccessibilityService.screenDebug?.currentAdvice?.snapshotId }!!
                        touchTarget("Search"); inputAction = waitNewAdvice(previous)
                    }
                }
                check(inputAction == "TYPE") { "Expected input advice, got $inputAction" }
                capture("fdroid-search-input")
                var keyboard = ui.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                if (keyboard == null) {
                    touchTarget("Search"); SystemClock.sleep(600)
                    keyboard = ui.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                }
                check(keyboard != null) { "Keyboard not visible for input check" }
                val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
                val ownButtons = ui.windows.flatMap { nodes(it.root) }.filter {
                    it.packageName?.toString() == context.packageName && it.className?.toString() == "android.widget.Button" && it.isVisibleToUser
                }
                check(ownButtons.isNotEmpty())
                check(ownButtons.none { Rect.intersects(Rect().also(it::getBoundsInScreen), keyboardBounds) }) { "AI controls cover keyboard" }
                val field = ui.windows.flatMap { nodes(it.root) }.first { it.packageName?.toString() == "org.fdroid.fdroid" && it.isEditable }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Wikipedia")
                }))
                waitState("COMPLETE"); capture("fdroid-search-results")
                tap("I confirm it is done"); tap("Stay here")
                events += "F-Droid multi-step search: Search -> editable field -> synthetic Wikipedia input -> visible results; English text + Mandarin voice text; AI controls avoid keyboard"
                prefs.textLanguage = GuideLanguage.MANDARIN; prefs.voiceLanguage = GuideLanguage.CANTONESE

                // Put the existing Clock app on its Alarm tab before requesting Timer.
                main { context.startActivity(context.packageManager.getLaunchIntentForPackage("com.google.android.deskclock")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                SystemClock.sleep(1200)
                val alarms = ui.windows.flatMap { nodes(it.root) }.firstOrNull {
                    it.packageName?.toString() == "com.google.android.deskclock" &&
                        (it.text?.toString() in setOf("Alarm", "Alarms") || it.contentDescription?.toString() in setOf("Alarm", "Alarms"))
                }
                if (alarms != null) { var n: AccessibilityNodeInfo = alarms; while (!n.isClickable && n.parent != null) n = n.parent; n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                SystemClock.sleep(400)
                start("com.google.android.deskclock", "打开时钟的计时器页面，不启动计时", true)
                waitState("TAP"); check(main { GuideAccessibilityService.screenDebug?.imageUsed } == true) { "Optional window screenshot not delivered" }
                capture("clock-timer-target"); touchTarget("Timer")
                waitState("COMPLETE"); capture("clock-timer-complete")
                find("00", "com.google.android.deskclock") // Independently check the actual timer keypad.
                events += "Clock: separate unregistered app, real window screenshot + controls, Timer reached without starting a timer"

                // Switching to a different app pauses and sends no content from that app.
                val calls = main { GuideAccessibilityService.screenDebug?.requestCount }
                main { context.startActivity(context.packageManager.getLaunchIntentForPackage("org.fdroid.fdroid")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                SystemClock.sleep(1800)
                check(ScreenGuideSession.current?.paused == true)
                check(main { GuideAccessibilityService.screenDebug?.requestCount } == calls)
                capture("scope-paused"); events += "Leaving selected app pauses: other-app page was not sent"
                stop()

                // Synthetic private fixture: inspect locally, never send its password field.
                main { context.startActivity(context.packageManager.getLaunchIntentForPackage("org.hku4sdg.demotarget")!!
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                tap("模拟私人步骤，这里不展示操作提示", "org.hku4sdg.demotarget")
                start("org.hku4sdg.demotarget", "看看当前页面可以做什么", true, launch = false)
                waitState("PRIVATE"); check(main { GuideAccessibilityService.screenDebug?.requestCount } == 0)
                check(main { GuideAccessibilityService.screenDebug?.currentTarget } == null)
                capture("private-blocked"); events += "Synthetic password page blocked locally, zero model requests and no circle"
            }
            events += "LIMIT: emulator test gestures, no real-user study; screenshot does not enable invented coordinate targets"
            code = Activity.RESULT_OK; result.putString("stream", "\nPASS\n" + events.joinToString("\n") + "\n")
        } catch (error: Throwable) {
            if (::ui.isInitialized) capture("failure")
            result.putString("stream", "\nFAIL: ${error.message}\n" + events.joinToString("\n") + "\n")
        } finally { stop(); saved.restore() }
        runner.finish(code, result)
    }
}
