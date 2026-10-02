package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** A non-touchable highlight and a bounded card with fixed controls. */
internal class GuideOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var ring: View? = null
    private var card: View? = null
    private var preferred: OverlaySide? = null
    private var lastSide = OverlaySide.BOTTOM
    private var moveRequested = false
    private var picker: String? = null
    private var onNoRoom: (() -> Unit)? = null
    private fun dp(value: Int) = service.dp(value)
    private fun c(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun resetPosition() { preferred = null; picker = null; moveRequested = false }
    fun move() { preferred = if (lastSide == OverlaySide.TOP) OverlaySide.BOTTOM else OverlaySide.TOP; moveRequested = true }
    fun clear() {
        ring?.let { runCatching { wm.removeViewImmediate(it) } }
        card?.let { runCatching { wm.removeViewImmediate(it) } }
        ring = null; card = null
    }
    private fun column() = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private fun gap(parent: LinearLayout, h: Int = 8) { parent.addView(View(service), LinearLayout.LayoutParams(1, dp(h))) }
    private fun label(value: String, size: Float, color: Int = Design.ink, bold: Boolean = false) = service.text(value, size, color, bold)
    private fun tap(value: String, fill: Int = Design.paleGreen, color: Int = Design.forest, click: () -> Unit) =
        label(value, 16f, color, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10)); isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                }
            }
            background = Design.ripple(fill, dp(14).toFloat()); setOnClickListener { click() }
        }
    private fun pair(parent: LinearLayout, left: View, right: View) {
        parent.addView(LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f)); addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    fun showStep(step: GuideStep, bounds: Rect?, message: String,
        textLanguage: GuideLanguage, voiceLanguage: GuideLanguage,
        collapsed: Boolean, muted: Boolean, independent: Boolean, adjusting: Boolean,
        toolsExpanded: Boolean, onTools: () -> Unit, onRefresh: () -> Unit, onMove: () -> Unit, onUnavailable: () -> Unit,
        onCollapse: () -> Unit, onTextLanguage: (GuideLanguage) -> Unit, onVoiceLanguage: (GuideLanguage) -> Unit,
        onReplay: () -> Unit, onMute: () -> Unit, onExit: () -> Unit, onPause: () -> Unit,
        onHint: () -> Unit, onConfirm: () -> Unit, onAdjust: () -> Unit) {
        clear()
        val l = textLanguage
        onNoRoom = onUnavailable
        if (bounds != null && (!independent || adjusting || step.isComplete)) addRing(bounds, step)
        val content = column(); val controls = column()
        if (step.isComplete) {
            content.addView(label(ExperienceCopy.status(l), 14f, Design.forest, true))
            gap(content, 12); content.addView(label(ExperienceCopy.question(step.task, l), 22f, bold = true).heading())
            controls.addView(tap(ExperienceCopy.confirm(l), Design.forest, Design.surface, onConfirm))
            if (step.task in setOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS)) {
                gap(controls); controls.addView(tap(ExperienceCopy.adjust(l), click = onAdjust))
            }
            gap(controls); pair(controls, tap(SupportCopy.pause(l), click = onPause),
                tap(ExperienceCopy.unconfirmed(l), Design.background, Design.secondary, onExit))
            showCard(content, controls, bounds, l); return
        }
        if (independent && !adjusting) {
            content.addView(label(ExperienceCopy.own(l), 19f, bold = true))
            controls.addView(tap(ExperienceCopy.hint(l), Design.forest, Design.surface, onHint))
            gap(controls); pair(controls, tap(SupportCopy.pause(l), click = onPause), tap(ExperienceCopy.end(l), Design.background, Design.secondary, onExit))
            showCard(content, controls, bounds, l); return
        }
        val total = if (step.task == GuideTask.CHROME_BOOKMARK) 3 else 2
        val header = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(c(l, "第 ${step.number} / $total 步", "第 ${step.number} / $total 步", "Step ${step.number} / $total"), 14f, Design.forest, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!step.isScrollHint) header.addView(tap(if (collapsed) c(l, "查看本步文字", "睇本步文字", "Show step text") else c(l, "收起提示", "收起提示", "Collapse"), click = onCollapse).apply {
            if (Build.VERSION.SDK_INT >= 30) stateDescription = c(l, if (collapsed) "已收起" else "已展开", if (collapsed) "已收起" else "已展开", if (collapsed) "Collapsed" else "Expanded")
        }, LinearLayout.LayoutParams(0, -2, 1.65f))
        content.addView(header)
        if (!collapsed || step.isScrollHint) {
            gap(content, 12)
            content.addView(label(message, 21f, bold = true).apply {
                setLineSpacing(dp(3).toFloat(), 1f); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }
        val primaryChildCount = content.childCount
        if (toolsExpanded && !collapsed) {
            gap(content, 16); content.addView(label(c(l, "语言和声音", "语言同声音", "Language and sound"), 16f, Design.forest, true).heading())
            gap(content)
            content.addView(tap("${SupportCopy.text(l)} · ${textLanguage.label}") { picker = if (picker == "text") null else "text"; onRefresh() })
            if (picker == "text") languageChoices(content, textLanguage, onTextLanguage)
            gap(content)
            content.addView(tap("${SupportCopy.voice(l)} · ${voiceLanguage.label}") { picker = if (picker == "voice") null else "voice"; onRefresh() })
            if (picker == "voice") languageChoices(content, voiceLanguage, onVoiceLanguage)
            gap(content); content.addView(tap(SupportCopy.sound(l, muted), Design.background, Design.ink, onMute))
            gap(content); content.addView(tap(ExperienceCopy.unconfirmed(l), Design.background, Design.secondary, onExit))
        }
        val replay = tap(if (muted) c(l, "听一次", "听一次", "Play once") else SupportCopy.replay(l), Design.forest, Design.surface, onReplay)
        val pause = tap(SupportCopy.pause(l), click = onPause)
        if (collapsed) {
            replay.text = if (muted) c(l, "听一次", "听一次", "Play") else c(l, "再听", "再听", "Replay")
            replay.contentDescription = if (muted) c(l, "听一次", "听一次", "Play once") else SupportCopy.replay(l)
            pause.text = c(l, "暂停", "暂停", "Pause")
            pause.contentDescription = SupportCopy.pause(l)
            controls.addView(LinearLayout(service).apply {
                gravity = Gravity.CENTER_VERTICAL
                val move = tap(c(l, "换位置", "换位", "Move"), Design.surface, Design.forest, onMove).apply {
                    contentDescription = c(l, "换个位置", "换个位", "Move card")
                }
                listOf(replay, pause, move).forEachIndexed { index, button ->
                    if (index > 0) addView(View(service), LinearLayout.LayoutParams(dp(6), 1))
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        } else pair(controls, replay, pause)
        if (!collapsed) {
            gap(controls, 6)
            pair(controls, tap(if (toolsExpanded) c(l, "收起更多", "收起更多", "Close options") else c(l, "更多", "更多", "More"), Design.surface, Design.forest) { picker = null; onTools() }.apply {
                if (Build.VERSION.SDK_INT >= 30) stateDescription = c(l, if (toolsExpanded) "已展开" else "已收起", if (toolsExpanded) "已展开" else "已收起", if (toolsExpanded) "Expanded" else "Collapsed")
            }, tap(c(l, "换个位置", "换个位", "Move card"), Design.surface, Design.forest, onMove))
        }
        if (adjusting) { gap(controls); controls.addView(tap(ExperienceCopy.confirm(l), Design.forest, Design.surface, onConfirm)) }
        showCard(content, controls, bounds, l, primaryChildCount)
    }
    private fun languageChoices(parent: LinearLayout, selected: GuideLanguage, change: (GuideLanguage) -> Unit) {
        GuideLanguage.entries.forEach { language ->
            gap(parent, 6)
            parent.addView(tap(language.label, if (selected == language) Design.forest else Design.background,
                if (selected == language) Design.surface else Design.ink) { picker = null; change(language) }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = selected == language
                    }
                }
            })
        }
    }
    fun showStatus(message: String, l: GuideLanguage, onResume: () -> Unit, onExit: () -> Unit) {
        clear(); val content = column(); val controls = column()
        content.addView(label(SupportCopy.unknown(l), 14f, Design.secondary, true))
        gap(content, 12); content.addView(label(message, 20f, bold = true))
        controls.addView(tap(SupportCopy.returnTask(l), Design.forest, Design.surface, onResume))
        gap(controls); controls.addView(tap(ExperienceCopy.end(l), Design.background, Design.secondary, onExit))
        showCard(content, controls, null, l)
    }
    fun showPaused(message: String, l: GuideLanguage, onResume: () -> Unit, onReturn: () -> Unit, onExit: () -> Unit) {
        clear(); val content = column(); val controls = column()
        content.addView(label(SupportCopy.paused(l), 14f, Design.forest, true))
        gap(content, 12); content.addView(label(message, 20f, bold = true))
        controls.addView(tap(SupportCopy.resume(l), Design.forest, Design.surface, onResume))
        gap(controls); pair(controls, tap(SupportCopy.returnTask(l), click = onReturn), tap(ExperienceCopy.unconfirmed(l), Design.background, Design.secondary, onExit))
        showCard(content, controls, null, l)
    }
    fun showExitConfirmation(l: GuideLanguage, onKeep: () -> Unit, onEnd: () -> Unit) {
        clear(); val content = column(); val controls = column()
        content.addView(label(c(l, "结束这次引导？", "结束今次指引？", "End this guide?"), 22f, bold = true).heading())
        gap(content, 12); content.addView(label(c(l, "本次不会记为完成。以后可以重新开始。", "今次唔会记为完成。之后可以再试。", "This task will not be marked complete. You can try again later."), 17f, Design.secondary))
        controls.addView(tap(c(l, "继续这次任务", "继续今次任务", "Keep this task"), Design.forest, Design.surface, onKeep))
        gap(controls); controls.addView(tap(c(l, "结束引导", "结束指引", "End guide"), Design.background, Design.secondary, onEnd))
        showCard(content, controls, null, l)
    }
    private fun showCard(content: LinearLayout, controls: LinearLayout, bounds: Rect?, l: GuideLanguage, primaryChildCount: Int = 0) {
        val frame: Rect
        val insets: Rect
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            frame = metrics.bounds
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insets = Rect(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            // Guides are reviewed for API 35; keep unavailable/recovery messages safe on older installs.
            frame = Rect(0, 0, service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
            insets = Rect(0, dp(32), 0, dp(32))
        }
        val width = frame.width() - insets.left - insets.right - dp(24)
        content.setPadding(dp(18), dp(14), dp(18), dp(8)); controls.setPadding(dp(18), dp(8), dp(18), dp(12))
        val spec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(spec, free); controls.measure(spec, free)
        val headerHeight = if (primaryChildCount > 0) content.getChildAt(0).measuredHeight + content.paddingTop + content.paddingBottom else dp(56)
        val minimum = controls.measuredHeight + minOf(headerHeight, content.measuredHeight)
        val desired = (content.measuredHeight + controls.measuredHeight).coerceAtMost((frame.height() * .45f).toInt().coerceAtLeast(minimum))
        val primaryHeight = content.paddingTop + content.paddingBottom + (0 until primaryChildCount).sumOf { content.getChildAt(it).measuredHeight }
        val readableMinimum = maxOf(minimum, controls.measuredHeight + primaryHeight)
        // Prefer a side that shows the whole instruction. A manual move must not squeeze it away.
        val position = OverlayPlacement.place(frame.top + insets.top + dp(12), frame.bottom - insets.bottom - dp(12),
            bounds?.top, bounds?.bottom, dp(30), maxOf(desired, readableMinimum), readableMinimum, preferred)
            ?: OverlayPlacement.place(frame.top + insets.top + dp(12), frame.bottom - insets.bottom - dp(12),
                bounds?.top, bounds?.bottom, dp(30), desired, minimum, preferred)
        if (position == null) {
            ring?.let { runCatching { wm.removeViewImmediate(it) } }; ring = null
            onNoRoom?.invoke()
            return
        }
        if (moveRequested && preferred != position.side) Toast.makeText(service, c(l, "另一侧空间不足，已保留可操作的位置。", "另一边唔够位，保留返可以操作嘅位置。", "The other side is too small. The card stays clear of the target."), Toast.LENGTH_SHORT).show()
        if (moveRequested) preferred = position.side
        moveRequested = false; lastSide = position.side
        val container = column().apply {
            background = Design.shape(Design.surface, dp(24).toFloat(), Design.line, dp(1)); clipToOutline = true; elevation = dp(4).toFloat()
            addView(ScrollView(service).apply { addView(content); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        val params = WindowManager.LayoutParams(width, position.height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = frame.left + insets.left + dp(12); y = position.top
        }
        wm.addView(container, params); card = container
    }
    private fun addRing(bounds: Rect, step: GuideStep) {
        val padding = dp(15)
        val view = object : View(service) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.orange
                style = Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AC25825 }
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.orange }
            val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.surface
                textSize = dp(13).toFloat()
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = padding.toFloat()
                val top = padding.toFloat()
                val right = (width - padding).toFloat()
                val bottom = (height - padding).toFloat()
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), fill)
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), stroke)
                if (!step.isComplete) {
                    canvas.drawCircle(left, top, dp(14).toFloat(), badge)
                    canvas.drawText(step.number.toString(), left, top - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
                }
            }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        val margin = dp(6)
        val ringWidth = (bounds.width() + padding * 2).coerceAtMost(screenWidth - margin * 2)
        val ringHeight = (bounds.height() + padding * 2).coerceAtMost(screenHeight - margin * 2)
        val params = WindowManager.LayoutParams(ringWidth, ringHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.left - padding).coerceIn(margin, screenWidth - ringWidth - margin)
            y = (bounds.top - padding).coerceIn(margin, screenHeight - ringHeight - margin)
        }
        wm.addView(view, params)
        ring = view
    }


}
