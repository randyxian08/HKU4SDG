package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream

/** Observes only the explicitly chosen app while a user-started session is active. */
internal class ScreenGuideController(private val service: AccessibilityService, private val overlay: GuideOverlay,
    private val preferences: GuidePreferences, private val speak: (String, GuideLanguage) -> Unit,
    private val stopSpeech: () -> Unit) {
    private val client = ScreenGuideClient(service)
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId = -1
    private var lastSnapshot: ScreenSnapshot? = null
    private var advice: ScreenAdvice? = null
    private var message = ""
    private var busy = false
    private var seenTarget = false
    private var ending = false
    private var calls = 0
    private var lastCallAt = 0L
    private var lastKeyboard = ""
    private var receiptPackage: String? = null
    private var scheduled: Runnable? = null
    var state = "IDLE"
        private set
    var imageUsed = false
        private set
    val currentAdvice get() = advice
    val requestCount get() = calls
    val lastFailure get() = client.lastFailure
    val hasSelectedWindow get() = currentRoot() != null
    val currentTarget: ScreenNode? get() = lastSnapshot?.let { s -> advice?.let { ScreenGuidePolicy.target(s, it) } }

    fun onEvent(event: AccessibilityEvent?): Boolean {
        val run = ScreenGuideSession.current
        val root = if (run == null) service.rootInActiveWindow else currentRoot()
        if (run == null) {
            if (receiptPackage != null && root?.packageName?.toString() == receiptPackage) return true
            if (receiptPackage != null) { receiptPackage = null; overlay.clear() }
            return false
        }
        if (sessionId != run.id) {
            sessionId = run.id; client.cancel(); busy = false; calls = 0; lastCallAt = 0; advice = null
            lastSnapshot = null; lastKeyboard = ""; seenTarget = false; ending = false; overlay.resetPosition()
        }
        if (root?.packageName?.toString() != run.packageName) {
            if (seenTarget) {
                run.paused = true; cancelPending()
                if (state != "WAITING_APP") android.widget.Toast.makeText(service,
                    ScreenGuideCopy.choose(preferences.textLanguage, "屏幕引导已暂停。请先自行处理系统提示，或回到刚才的 App 再继续。", "屏幕指引暂停咗。请先自己处理系统提示，或者返刚才嘅 App 再继续。", "Screen guidance paused. Handle any system prompt yourself, or return to the selected app to continue."), android.widget.Toast.LENGTH_LONG).show()
            }
            overlay.clear(); stopSpeech(); state = "WAITING_APP"
            return true
        }
        seenTarget = true
        if (ending || event?.packageName?.toString() == service.packageName) return true
        val keyboard = keyboardAreas().joinToString()
        val keyboardChanged = keyboard != lastKeyboard
        lastKeyboard = keyboard
        if (run.paused) { if (state != "PAUSED" || keyboardChanged) present(); return true }
        // Remove stale circles immediately, before the next debounced model request.
        val read = readPage()
        if (read == null) {
            overlay.invalidateScreenTarget(); advice = null; stopSpeech()
            if (state != "UNREADABLE") schedule()
        } else if (read.snapshot.id != lastSnapshot?.id) {
            overlay.invalidateScreenTarget(); advice = null; state = "STALE"; stopSpeech()
            schedule()
        } else if (keyboardChanged) {
            present()
        }
        return true
    }

    private fun schedule() {
        // A stream of content/window events must not postpone analysis forever.
        if (scheduled != null) return
        val update = Runnable { scheduled = null; refresh() }
        scheduled = update
        handler.postDelayed(update, maxOf(500L, 1800L - (android.os.SystemClock.elapsedRealtime() - lastCallAt)))
    }

    fun refresh(force: Boolean = false) {
        val run = ScreenGuideSession.current ?: return
        if (currentRoot() == null) {
            cancelPending(); overlay.clear(); return
        }
        if (sessionId != run.id) { onEvent(null); return }
        if (run.paused) { present(); return }
        val read = readPage() ?: run { unavailable("UNREADABLE"); return }
        if (read.blocked || ScreenGuidePolicy.privateText(run.goal) || ScreenGuidePolicy.risky(run.goal)) {
            cancelPending(); advice = null; lastSnapshot = read.snapshot; state = "PRIVATE"
            message = ScreenGuideCopy.privatePage(preferences.textLanguage); present(); return
        }
        if (read.snapshot.nodes.isEmpty()) { unavailable("NO_CONTROLS"); return }
        if (read.snapshot.id == lastSnapshot?.id && !force) { if (!busy) present(); return }
        if (busy && read.snapshot.id == lastSnapshot?.id) return
        if (calls >= 24) { unavailable("LIMIT"); return }
        cancelPending(); lastSnapshot = read.snapshot; advice = null; busy = true; state = "ANALYZING"
        message = ScreenGuideCopy.thinking(preferences.textLanguage); present()
        val id = run.id
        val send: (String) -> Unit = send@ { image ->
            val current = ScreenGuideSession.current
            val fresh = readPage()
            if (current?.id != id || current.paused || fresh == null || fresh.blocked || fresh.snapshot.id != read.snapshot.id) {
                busy = false; lastSnapshot = null; schedule(); return@send
            }
            calls++; lastCallAt = android.os.SystemClock.elapsedRealtime(); imageUsed = image.isNotEmpty()
            client.plan(current.goal, read.snapshot, preferences.textLanguage, preferences.voiceLanguage, image) { result ->
                busy = false
                val now = ScreenGuideSession.current
                val page = readPage()
                if (now?.id != id || now.paused) return@plan
                if (page == null || page.blocked || page.snapshot.id != read.snapshot.id) {
                    advice = null; lastSnapshot = null; overlay.invalidateScreenTarget(); state = "STALE"; schedule(); return@plan
                }
                if (result == null) { unavailable("UNAVAILABLE"); return@plan }
                advice = result; state = result.action.uppercase(); message = result.instruction
                present()
                if (!preferences.muted) speak(result.spokenInstruction, preferences.voiceLanguage)
            }
        }
        if (run.includeImage && Build.VERSION.SDK_INT >= 34) captureImage(read.windowId, send) else send("")
    }

    private fun cancelPending() { client.cancel(); busy = false; stopSpeech() }
    private fun unavailable(reason: String) {
        cancelPending(); advice = null; state = reason; message = ScreenGuideCopy.unavailable(preferences.textLanguage); present()
    }

    private fun present() {
        val run = ScreenGuideSession.current ?: return
        if (currentRoot() == null) { overlay.clear(); return }
        val l = preferences.textLanguage
        val paused = run.paused
        if (paused) state = "PAUSED"
        val current = if (!busy && !paused) readPage() else null
        if (!paused && !busy && advice != null && (current == null || current.blocked || current.snapshot.id != advice?.snapshotId)) {
            advice = null; lastSnapshot = null; state = "STALE"
            message = ScreenGuideCopy.thinking(l); schedule()
        }
        val target = current?.takeUnless { it.blocked }?.snapshot?.let { s -> advice?.let { ScreenGuidePolicy.target(s, it) } }
        val words = if (paused) ScreenGuideCopy.choose(l, "引导已暂停。继续后会重新查看当前页面。", "指引暂停咗。继续后会重新睇当前页面。", "Guide paused. Resume to check the current page again.") else message
        runCatching { overlay.showScreenAdvice(words, target?.takeUnless { advice?.action?.startsWith("scroll_") == true }?.rect(),
            target?.takeIf { advice?.action?.startsWith("scroll_") == true }?.let { advice?.action == "scroll_forward" },
            l, preferences.collapsed, busy, paused, advice?.action == "complete" && !paused && !busy,
            onReplay = { speak(if (paused) words else advice?.spokenInstruction ?: words, preferences.voiceLanguage) },
            onNext = { run.paused = false; refresh(true) },
            onCollapse = { preferences.collapsed = !preferences.collapsed; present() }, onRedraw = ::present,
            onMove = { overlay.move(); present() },
            onPause = { run.paused = !run.paused; cancelPending(); if (run.paused) present() else refresh(true) },
            onEdit = { run.paused = true; cancelPending(); overlay.clear(); openApp(false) },
            onEnd = {
                ending = true; cancelPending()
                overlay.showExitConfirmation(l, { ending = false; present() }, { finish(false) })
            }, onConfirm = {
                val fresh = readPage()
                if (advice?.action == "complete" && fresh?.blocked == false && fresh.snapshot.id == advice?.snapshotId) finish(true)
                else refresh(true)
            }, onLanguages = { run.paused = true; cancelPending(); overlay.clear(); openApp(true) },
            onUnavailable = {
                run.paused = true; advice = null; state = "NO_ROOM"
                handler.post { present() }
            }, avoidAreas = keyboardAreas())
        }.onFailure { run.paused = true; advice = null; state = "WINDOW_UNAVAILABLE"; overlay.clear() }
    }

    private fun finish(confirmed: Boolean) {
        val pkg = ScreenGuideSession.current?.packageName ?: return
        cancelPending(); ScreenGuideSession.stop(); advice = null; lastSnapshot = null; message = ""
        receiptPackage = pkg; state = if (confirmed) "CONFIRMED" else "ENDED"
        overlay.showReceipt(confirmed, preferences.textLanguage,
            onStay = { receiptPackage = null; overlay.clear() },
            onReturn = { receiptPackage = null; overlay.clear(); openApp(false) }, recorded = false)
    }

    private fun openApp(languages: Boolean) {
        service.startActivity(Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("screen_guide_setup", true).putExtra("screen_guide_languages", languages))
    }

    private data class Page(val snapshot: ScreenSnapshot, val blocked: Boolean, val windowId: Int)
    private fun keyboardAreas() = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .map { window -> Rect().also(window::getBoundsInScreen) }
    private fun currentRoot(): AccessibilityNodeInfo? {
        val run = ScreenGuideSession.current ?: return null
        val active = service.rootInActiveWindow ?: return null
        if (active.packageName?.toString() == run.packageName) return active
        // The keyboard may be active, but never inspect a background app behind a system dialog.
        val windows = service.windows
        if (windows.firstOrNull { it.id == active.windowId }?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        return windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?.takeIf { it.packageName?.toString() == run.packageName }
    }
    private fun readPage(): Page? = runCatching {
        val run = ScreenGuideSession.current ?: return null
        if (Build.VERSION.SDK_INT >= 33) service.clearCache()
        val root = currentRoot() ?: return null
        if (root.packageName?.toString() != run.packageName) return null
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val result = mutableListOf<ScreenNode>(); var visited = 0; var blocked = false; var truncated = false
        fun label(node: AccessibilityNodeInfo, depth: Int = 0): String {
            if (node.isEditable || node.isPassword) return node.hintText?.toString().orEmpty()
            val own = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            if (own.isNotBlank() || depth >= 2) return own
            return (0 until minOf(node.childCount, 8)).mapNotNull { node.getChild(it) }
                .map { label(it, depth + 1) }.filter { it.isNotBlank() }.distinct().take(4).joinToString(" · ")
        }
        fun visit(node: AccessibilityNodeInfo) {
            if (++visited > 450) { truncated = true; return }
            if (!node.isVisibleToUser) return
            val raw = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.hintText?.toString().orEmpty()).joinToString(" ")
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.intersect(0, 0, width, height)) {
                if (node.isPassword || ScreenGuidePolicy.privateText(raw)) blocked = true
                val control = node.isClickable || node.isEditable || node.isScrollable
                val words = (if (control) label(node) else listOf(node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(" · ")).take(120)
                if (words.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                    result += ScreenNode("n${result.size}", words, node.className?.toString().orEmpty().take(80),
                        node.isClickable, node.isEditable, node.isScrollable, node.isEnabled,
                        rect.left, rect.top, rect.right, rect.bottom,
                        ("selected=${node.isSelected};focused=${node.isFocused}" +
                            (if (node.isEditable) ";has_text=${!node.isShowingHintText && !node.text.isNullOrBlank()}" else "") +
                            (if (node.isCheckable) ";checked=${node.isChecked}" else "") +
                            (node.rangeInfo?.let { ";range=${it.min},${it.current},${it.max}" } ?: "")).take(80))
                }
            }
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(root)
        if (truncated) return null
        val selected = result.sortedBy { if (it.clickable || it.editable || it.scrollable) 0 else 1 }.take(80)
        Page(ScreenSnapshot(run.packageName, selected, root.windowId), blocked, root.windowId)
    }.getOrNull()

    private fun ScreenNode.rect() = Rect(left, top, right, bottom)

    @android.annotation.TargetApi(34)
    private fun captureImage(windowId: Int, callback: (String) -> Unit) {
        try {
            service.takeScreenshotOfWindow(windowId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val image = runCatching {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching ""
                        val software = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle()
                        val ratio = minOf(1f, 720f / software.width)
                        val scaled = Bitmap.createScaledBitmap(software, (software.width * ratio).toInt(), (software.height * ratio).toInt(), true)
                        val stream = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                        if (scaled !== software) scaled.recycle()
                        software.recycle()
                        stream.toByteArray().takeIf { it.size <= 500000 }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()
                    }.getOrDefault("")
                    buffer.close(); callback(image)
                }
                override fun onFailure(errorCode: Int) { callback("") }
            })
        } catch (_: Exception) { callback("") }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null); client.close(); ScreenGuideSession.stop(); receiptPackage = null
        advice = null; lastSnapshot = null; message = ""
        overlay.clear(); stopSpeech()
    }
}
