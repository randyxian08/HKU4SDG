package org.hku4sdg.handrail

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    private var arrow: View? = null
    private var card: View? = null
    private var preferred: OverlaySide? = null
    private var lastSide = OverlaySide.BOTTOM
    private var moveRequested = false
    private var onNoRoom: (() -> Unit)? = null
    private fun dp(value: Int) = service.dp(value)
    private fun c(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun resetPosition() { preferred = null; moveRequested = false }
    fun move() { preferred = if (lastSide == OverlaySide.TOP) OverlaySide.BOTTOM else OverlaySide.TOP; moveRequested = true }
    fun clear() {
        ring?.let { runCatching { wm.removeViewImmediate(it) } }
        arrow?.let { runCatching { wm.removeViewImmediate(it) } }
        card?.let { runCatching { wm.removeViewImmediate(it) } }
        ring = null; arrow = null; card = null
    }
    private fun column() = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private fun gap(parent: LinearLayout, h: Int = 8) { parent.addView(View(service), LinearLayout.LayoutParams(1, dp(h))) }
    private fun label(value: String, size: Float, color: Int = Design.ink, bold: Boolean = false) = service.text(value, size, color, bold)
    private fun tap(value: String, fill: Int = Design.surface, color: Int = Design.ink, click: () -> Unit) =
        label(value, 16f, if (fill == Design.forest) Design.surface else color, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(52); minWidth = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                }
            }
            background = if (fill == Design.forest) Design.ripple(Design.forest, dp(14).toFloat())
                else Design.ripple(Design.surface, dp(14).toFloat(), Design.line, dp(1))
            setOnClickListener { click() }
        }
    private fun pair(parent: LinearLayout, left: View, right: View) {
        parent.addView(LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f)); addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private var picker: String? = null
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
        val screenHeight = service.resources.displayMetrics.heightPixels
        val offTop = bounds != null && bounds.bottom <= dp(8)
        val offBottom = bounds != null && bounds.top >= screenHeight - dp(8)
        val showArrow = step.isScrollHint || offTop || offBottom
        val pointDown = !offTop
        if (!independent || adjusting || step.isComplete) {
            if (showArrow) {
                val words = if (pointDown) c(l, "向上滑，下面还有", "向上扫，下面仲有", "Swipe up. More is below.")
                    else c(l, "向下滑，上面还有", "向下扫，上面仲有", "Swipe down. More is above.")
                addArrow(pointDown, if (Design.displayScript == DisplayScript.HANT) Han.convert(words) else words)
            } else if (bounds != null) addRing(bounds)
        }
        val content = column()
        val controls = column()
        val mandatory = step.isComplete || step.isScrollHint || adjusting
        if (step.isComplete) {
            content.addView(label(ExperienceCopy.question(step.task, l), 20f, bold = true).heading())
        } else if (independent && !adjusting) {
            content.addView(label(ExperienceCopy.own(l), 18f))
        } else if (!collapsed || mandatory) {
            content.addView(label(message, 18f, bold = true).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }
        val primaryChildCount = content.childCount
        if (toolsExpanded) {
            gap(content, 12)
            if (!mandatory && !independent) {
                content.addView(tap(if (collapsed) c(l, "查看本步文字", "睇本步文字", "Show step text") else c(l, "收起提示", "收起提示", "Collapse"), click = onCollapse))
                gap(content)
            }
            pair(content, tap(SupportCopy.pause(l), click = onPause), tap(c(l, "换个位置", "换个位", "Move card"), click = onMove))
            gap(content)
            content.addView(tap(SupportCopy.sound(l, muted), click = onMute))
            gap(content)
            content.addView(tap("${SupportCopy.text(l)} · ${textLanguage.label}") { picker = if (picker == "text") null else "text"; onRefresh() })
            if (picker == "text") languageChoices(content, textLanguage, onTextLanguage)
            gap(content)
            content.addView(tap("${SupportCopy.voice(l)} · ${voiceLanguage.label}") { picker = if (picker == "voice") null else "voice"; onRefresh() })
            if (picker == "voice") languageChoices(content, voiceLanguage, onVoiceLanguage)
            if (step.isComplete && step.task in setOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS)) {
                gap(content); content.addView(tap(ExperienceCopy.adjust(l), click = onAdjust))
            }
            gap(content); content.addView(tap(ExperienceCopy.unconfirmed(l), click = onExit))
        }
        val options = tap(if (toolsExpanded) c(l, "收起更多", "收起更多", "Close options") else c(l, "更多", "更多", "More")) { picker = null; onTools() }
        when {
            step.isComplete || adjusting -> {
                controls.addView(tap(ExperienceCopy.confirm(l), Design.forest, Design.surface, onConfirm))
                gap(controls, 6); pair(controls, tap(SupportCopy.replay(l), click = onReplay), options)
            }
            independent -> pair(controls, tap(ExperienceCopy.hint(l), Design.forest, Design.surface, onHint), options)
            collapsed && !mandatory -> pair(controls, tap(c(l, "查看本步文字", "睇本步文字", "Show step text"), click = onCollapse), options)
            else -> pair(controls, tap(if (muted) c(l, "听一次", "听一次", "Play once") else SupportCopy.replay(l), click = onReplay), options)
        }
        val avoid = if (!showArrow) bounds else if (pointDown)
            Rect(0, screenHeight - dp(8), 1, screenHeight) else Rect(0, 0, 1, dp(88))
        showCard(content, controls, avoid, l, primaryChildCount)
    }
    private fun languageChoices(parent: LinearLayout, selected: GuideLanguage, change: (GuideLanguage) -> Unit) {
        GuideLanguage.entries.forEach { language ->
            gap(parent, 6)
            parent.addView(tap(language.label, if (selected == language) Design.forest else Design.surface) { picker = null; change(language) }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = selected == language
                    }
                }
            })
        }
    }
    fun showStatus(message: String, l: GuideLanguage, onResume: () -> Unit, onExit: () -> Unit, onDiagnostics: (() -> Unit)? = null) {
        clear(); val content = column(); val controls = column()
        content.addView(label(SupportCopy.unknown(l), 14f, Design.secondary, true))
        gap(content, 12); content.addView(label(message, 20f, bold = true))
        controls.addView(tap(SupportCopy.returnTask(l), Design.forest, Design.surface, onResume))
        gap(controls); controls.addView(tap(ExperienceCopy.end(l), Design.background, Design.secondary, onExit))
        onDiagnostics?.let { copy ->
            gap(controls); controls.addView(tap(c(l, "复制适配信息", "复制适配资料", "Copy compatibility info"), click = copy))
        }
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
        if (content.childCount == 0) content.setPadding(0, 0, 0, 0) else content.setPadding(dp(18), dp(14), dp(18), dp(8))
        if (controls.childCount == 0) controls.setPadding(0, 0, 0, 0) else controls.setPadding(dp(18), dp(8), dp(18), dp(12))
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
            arrow?.let { runCatching { wm.removeViewImmediate(it) } }; arrow = null
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
    private fun addRing(bounds: Rect) {
        val padding = dp(15)
        val view = object : View(service) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.forest
                style = Paint.Style.STROKE
                strokeWidth = dp(8).toFloat()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2217483E }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = padding.toFloat()
                val top = padding.toFloat()
                val right = (width - padding).toFloat()
                val bottom = (height - padding).toFloat()
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), fill)
                canvas.drawRoundRect(left, top, right, bottom, dp(15).toFloat(), dp(15).toFloat(), stroke)
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

    private fun addArrow(pointDown: Boolean, caption: String) {
        val view = object : View(service) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.forest }
            val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.surface }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.forest
                style = Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.forest
                textSize = dp(22).toFloat()
                textAlign = Paint.Align.LEFT
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val radius = dp(16).toFloat()
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, chip)
                canvas.drawRoundRect(dp(2).toFloat(), dp(2).toFloat(), (width - dp(2)).toFloat(), (height - dp(2)).toFloat(), radius, radius, edge)
                val path = Path()
                val cx = dp(36).toFloat()
                val cy = height / 2f
                if (pointDown) {
                    path.moveTo(cx - dp(16), cy - dp(12))
                    path.lineTo(cx + dp(16), cy - dp(12))
                    path.lineTo(cx, cy + dp(16))
                } else {
                    path.moveTo(cx - dp(16), cy + dp(12))
                    path.lineTo(cx + dp(16), cy + dp(12))
                    path.lineTo(cx, cy - dp(16))
                }
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawText(caption, dp(68).toFloat(), height / 2f - (words.ascent() + words.descent()) / 2f, words)
            }
        }
        view.contentDescription = caption
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        val width = (screenWidth - dp(24)).coerceAtMost(dp(420))
        val height = dp(72)
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - width) / 2
            y = if (pointDown) screenHeight - height - dp(24) else dp(24)
        }
        wm.addView(view, params)
        arrow = view
    }
}
