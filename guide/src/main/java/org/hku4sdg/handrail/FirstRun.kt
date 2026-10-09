package org.hku4sdg.handrail

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Remembered, optional setup. Input, written replies and spoken replies stay independent. */
internal class FirstRun(
    private val activity: Activity,
    private val preferences: GuidePreferences,
    private val serviceOn: () -> Boolean,
    private val openAccess: () -> Unit,
    private val onDone: () -> Unit,
    private val onHome: () -> Unit,
    private val onSetup: () -> Unit,
    private val onDiagnostics: () -> Unit
) {
    private fun c(zh: String, yue: String, en: String) = ExperienceCopy.choose(preferences.textLanguage, zh, yue, en)
    private fun sync() {
        Design.appScale = preferences.appTextScale
        Design.displayScript = preferences.displayScript ?: DisplayScript.HANS
    }
    fun showWizard() {
        sync()
        when (preferences.initStep) {
            0 -> script()
            1 -> size()
            2 -> languagePage(input = true)
            3 -> languagePage(input = false)
            4 -> permission()
            else -> practice()
        }
    }
    fun showSettings() {
        sync()
        page { body ->
            heading(body, c("字和声音", "文字同声音", "Text and voice"))
            gap(body, 16)
            body.addView(button(c("引导服务设置", "指引服务设置", "Guide service settings"), false, click = onSetup))
            gap(body, 10)
            body.addView(button(c("服务状态与诊断", "服务状态同诊断", "Service status and diagnostics"), false, click = onDiagnostics))
            section(body, c("界面文字与 AI 回复", "界面文字同 AI 提示", "App text and AI replies"))
            languageChoices(body, preferences.textLanguage) { preferences.textLanguage = it; showSettings() }
            section(body, c("中文显示方式", "中文显示方式", "Chinese writing style"))
            scriptChoices(body) { showSettings() }
            section(body, c("这个应用里的字号", "呢个应用嘅字大细", "Text size in this app"))
            sizeControl(body)
            section(body, c("你说话或输入的语言", "你讲嘢或者输入嘅语言", "Your input language"))
            languageChoices(body, preferences.inputLanguage) { preferences.inputLanguage = it; showSettings() }
            section(body, c("语音播报语言", "语音播报语言", "Spoken guidance language"))
            languageChoices(body, preferences.voiceLanguage) { preferences.voiceLanguage = it; showSettings() }
            gap(body, 20)
            body.addView(button(c("完成", "完成", "Done"), true, click = onHome))
        }
    }
    private fun script() {
        page { body ->
            body.addView(activity.text("请选择语言 / 請選擇語言\nChoose your language", 26f, bold = true, convert = false).heading())
            gap(body, 24)
            scriptChoices(body, initialLanguage = true) {
                preferences.initStep = 1
                showWizard()
            }
        }
    }
    private fun scriptChoices(body: LinearLayout, initialLanguage: Boolean = false, refresh: () -> Unit) {
        val choices = mutableListOf("简体中文" to DisplayScript.HANS, "繁體中文" to DisplayScript.HANT)
        if (initialLanguage) choices += "English" to DisplayScript.EN
        choices.forEach { (label, script) ->
            body.addView(button(label, preferences.displayScript == script, convert = false) {
                if (initialLanguage) preferences.applyLook(script) else preferences.displayScript = script
                refresh()
            }.radio(preferences.displayScript == script))
            gap(body, 10)
        }
    }
    private fun size() {
        page { body ->
            heading(body, c("先选一个舒服的字号", "先拣个舒服嘅字大细", "Choose a comfortable text size"))
            body.addView(activity.text(c("只改变数字扶手里的文字。以后随时可以调整。", "只改数字扶手嘅文字，之后随时可以再调。", "This changes only Digital Handrail. You can adjust it anytime."), 16f, Design.secondary))
            gap(body, 24)
            sizeControl(body)
            next(body, 2)
        }
    }
    private fun sizeControl(body: LinearLayout) {
        val preview = activity.text(c("这句话用来看看字是否合适。", "睇下呢句话嘅字大细系咪舒服。", "Check whether this text feels comfortable."), 24f, bold = true)
        body.addView(preview)
        gap(body, 14)
        body.addView(SeekBar(activity).apply {
            max = 60
            progress = ((preferences.appTextScale.coerceIn(.9f, 1.5f) - .9f) * 100).toInt()
            minimumHeight = activity.dp(48)
            contentDescription = c("数字扶手文字大小", "数字扶手字大细", "Digital Handrail text size")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = .9f + value / 100f
                    preferences.appTextScale = scale
                    preview.textSize = (24f * scale).coerceAtLeast(18f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
    }
    private fun languagePage(input: Boolean) {
        page { body ->
            heading(body, if (input) c("你会用什么语言说话？", "你会用咩语言讲嘢？", "What language will you use?")
                else c("希望用什么语言听提示？", "想用咩语言听提示？", "Which language should we speak?"))
            body.addView(activity.text(c("输入、文字和播报可以用不同语言。", "输入、文字同播报可以用唔同语言。", "Input, text and voice can use different languages."), 16f, Design.secondary))
            gap(body, 20)
            languageChoices(body, if (input) preferences.inputLanguage else preferences.voiceLanguage) {
                if (input) preferences.inputLanguage = it else preferences.voiceLanguage = it
                showWizard()
            }
            next(body, if (input) 3 else 4)
        }
    }
    private fun languageChoices(body: LinearLayout, selected: GuideLanguage, change: (GuideLanguage) -> Unit) {
        GuideLanguage.entries.forEach { language ->
            val label = when (language) {
                GuideLanguage.MANDARIN -> "普通话 · Mandarin"
                GuideLanguage.CANTONESE -> "粤语 · Cantonese"
                GuideLanguage.ENGLISH -> "English"
            }
            body.addView(button(label, selected == language) { change(language) }.radio(selected == language))
            gap(body, 10)
        }
    }
    private fun permission() {
        page { body ->
            val l = preferences.textLanguage
            heading(body, SupportCopy.setupHeading(l, serviceOn()))
            body.addView(activity.text(SupportCopy.setupWhy(l), 18f))
            gap(body, 14)
            body.addView(activity.text(SupportCopy.setupPrivacy(l), 16f, Design.secondary))
            gap(body, 20)
            if (serviceOn()) {
                body.addView(button(c("试一次引导", "试一次指引", "Try a guide"), true) {
                    preferences.initStep = 5; showWizard()
                })
            } else {
                body.addView(activity.text(c("找到“数字扶手 · 手机引导”，开启后回到这里。", "搵「数字扶手 · 手机引导」，开启之后返嚟。", "Choose “数字扶手 · 手机引导” in Settings, enable it, then return here."), 18f))
                gap(body, 18)
                body.addView(button(SupportCopy.openSettings(l), true) {
                    preferences.accessAgreed = true
                    preferences.accessLeft = true
                    openAccess()
                })
            }
            gap(body, 12)
            body.addView(button(c("稍后设置，先看看", "迟啲设置，先睇下", "Set up later, explore first"), false) { finishSetup() })
        }
    }
    private fun practice() {
        page { body ->
            heading(body, c("圈出的地方，就是下一步", "圈住嘅地方，就系下一步", "The highlight shows your next step"))
            body.addView(activity.text(c("试着点一下圈里的按钮。", "试下㩒圈入面嘅掣。", "Try tapping the highlighted button."), 18f))
            gap(body, 28)
            val ring = object : View(activity) {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.forest; style = Paint.Style.STROKE; strokeWidth = activity.dp(5).toFloat() }
                override fun onDraw(canvas: Canvas) {
                    val pad = activity.dp(12).toFloat()
                    canvas.drawRoundRect(pad, pad, width - pad, height - pad, activity.dp(22).toFloat(), activity.dp(22).toFloat(), stroke)
                }
            }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            body.addView(FrameLayout(activity).apply {
                addView(ring, FrameLayout.LayoutParams(-1, activity.dp(104)))
                addView(button(c("点这里", "㩒呢度", "Tap here"), true) { finishSetup() }, FrameLayout.LayoutParams(-1, activity.dp(64)).apply {
                    leftMargin = activity.dp(24); rightMargin = activity.dp(24); topMargin = activity.dp(20)
                })
            }, LinearLayout.LayoutParams(-1, activity.dp(104)))
        }
    }
    private fun finishSetup() { preferences.initDone = true; preferences.initStep = 6; onDone() }
    private fun next(body: LinearLayout, step: Int) {
        gap(body, 20)
        body.addView(button(c("继续", "继续", "Continue"), true) { preferences.initStep = step; showWizard() })
    }
    private fun heading(body: LinearLayout, label: String) { body.addView(activity.text(label, 26f, bold = true).heading()); gap(body, 14) }
    private fun section(body: LinearLayout, label: String) { gap(body, 22); body.addView(activity.text(label, 19f, bold = true).heading()); gap(body, 12) }
    private fun button(label: String, filled: Boolean, convert: Boolean = true, click: () -> Unit) =
        activity.text(label, 18f, if (filled) Design.surface else Design.ink, true, Gravity.CENTER, convert).apply {
            minHeight = activity.dp(64); setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = Design.ripple(if (filled) Design.forest else Design.surface, activity.dp(16).toFloat(), if (filled) null else Design.line)
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener { click() }
        }
    private fun TextView.radio(selected: Boolean) = apply {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = selected
            }
        }
    }
    private fun page(content: (LinearLayout) -> Unit) {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(22), activity.dp(28), activity.dp(22), activity.dp(28)) }
        content(body)
        val root = ScrollView(activity).apply { setBackgroundColor(Design.background); addView(body) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            root.setPadding(0, top, 0, bottom); insets
        }
        activity.setContentView(root)
    }
    private fun gap(body: LinearLayout, size: Int) { body.addView(View(activity), LinearLayout.LayoutParams(1, activity.dp(size))) }
}
