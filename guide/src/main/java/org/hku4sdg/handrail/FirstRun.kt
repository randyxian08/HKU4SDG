package org.hku4sdg.handrail

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** First-open path, and the later “字和声音” page. White page, dark green type, one filled button. */
internal class FirstRun(
    private val activity: Activity,
    private val preferences: GuidePreferences,
    private val serviceOn: () -> Boolean,
    private val openAccess: () -> Unit,
    private val onDone: () -> Unit,
    private val onHome: () -> Unit
) {
    private var settings = false

    fun showWizard() {
        settings = false
        sync()
        when (preferences.initStep) {
            0 -> script()
            1 -> size()
            2 -> speak()
            3 -> hear()
            4 -> permission()
            else -> practice()
        }
    }

    fun showSettings() {
        settings = true
        sync()
        page(bodyOf = {
            val page = it
            page.addView(activity.text("字和声音", 28f, Design.ink, true))
            gap(page, 18)
            page.addView(activity.text("看的字", 22f, Design.ink, true))
            gap(page, 10)
            scriptChoices(page) { showSettings() }
            gap(page, 22)
            page.addView(activity.text("这个应用里的字号", 22f, Design.ink, true))
            gap(page, 10)
            sizeControl(page)
            gap(page, 22)
            page.addView(activity.text("你跟应用说话时，会用什么语言？", 22f, Design.ink, true))
            gap(page, 10)
            speakChoices(page) { showSettings() }
            gap(page, 22)
            page.addView(activity.text("你希望应用用什么语言回你？", 22f, Design.ink, true))
            gap(page, 10)
            hearChoices(page) { showSettings() }
            gap(page, 28)
            page.addView(filled("完成") { onHome() })
        })
    }

    private fun sync() {
        Design.appScale = preferences.appTextScale
        Design.displayScript = preferences.displayScript ?: DisplayScript.HANS
    }

    private fun script() {
        val root = white()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(28), activity.dp(22), activity.dp(24))
        }
        listOf(
            Triple("请选择你的语言", "简体中文", DisplayScript.HANS),
            Triple("請選擇你的語言", "繁體中文", DisplayScript.HANT),
            Triple("Please choose your language", "English", DisplayScript.EN)
        ).forEach { (question, label, script) ->
            body.addView(activity.text(question, 26f, Design.ink, true, convert = false))
            gap(body, 10)
            val chosen = preferences.displayScript == script
            body.addView(choice(label, chosen, convert = false) {
                preferences.applyLook(script)
                preferences.appTextScale = 1.35f
                preferences.initStep = 1
                showWizard()
            })
            gap(body, 28)
        }
        root.addView(scroll(body))
        activity.setContentView(root)
    }

    private fun scriptChoices(parent: LinearLayout, refresh: () -> Unit) {
        listOf(
            "简体中文" to DisplayScript.HANS,
            "繁體中文" to DisplayScript.HANT,
            "English" to DisplayScript.EN
        ).forEach { (label, script) ->
            parent.addView(choice(label, preferences.displayScript == script, convert = false) {
                preferences.applyLook(script)
                refresh()
            })
            gap(parent, 12)
        }
    }

    private fun size() {
        page {
            it.addView(activity.text("拖一拖，字会马上变大变小。", 26f, Design.ink, true))
            gap(it, 8)
            it.addView(activity.text("这里只调这个应用里的字。", 18f, Design.ink))
            gap(it, 18)
            sizeControl(it)
            gap(it, 28)
            it.addView(filled("继续") {
                preferences.initStep = 2
                showWizard()
            })
        }
    }

    private fun sizeControl(parent: LinearLayout) {
        val preview = activity.text("这句话用来看字大不大。", 28f, Design.ink, true)
        parent.addView(preview)
        gap(parent, 16)
        val seek = SeekBar(activity).apply {
            max = 60
            val scale = preferences.appTextScale.coerceIn(0.9f, 1.5f)
            progress = (((scale - 0.9f) / 0.6f) * 60).toInt()
            minimumHeight = activity.dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val next = 0.9f + value / 60f * 0.6f
                    preferences.appTextScale = next
                    preview.textSize = (28f * next).coerceAtLeast(22f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        parent.addView(seek)
    }

    private fun speak() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21)
        page {
            it.addView(activity.text("你跟应用说话时，会用什么语言？", 26f, Design.ink, true))
            gap(it, 8)
            it.addView(activity.text("可以多选。不允许麦克风，也可以继续，以后用打字。", 18f, Design.ink))
            gap(it, 16)
            speakChoices(it) { showWizard() }
            gap(it, 28)
            it.addView(filled("继续") {
                val chosen = preferences.speakLanguages
                preferences.inputLanguage = when {
                    GuideLanguage.CANTONESE in chosen -> GuideLanguage.CANTONESE
                    GuideLanguage.MANDARIN in chosen -> GuideLanguage.MANDARIN
                    GuideLanguage.ENGLISH in chosen -> GuideLanguage.ENGLISH
                    else -> preferences.inputLanguage
                }
                preferences.initStep = 3
                showWizard()
            })
        }
    }

    private fun speakChoices(parent: LinearLayout, refresh: () -> Unit) {
        listOf(
            GuideLanguage.CANTONESE to "粤语",
            GuideLanguage.MANDARIN to "普通话",
            GuideLanguage.ENGLISH to "英语"
        ).forEach { (language, label) ->
            val on = language in preferences.speakLanguages
            parent.addView(choice(if (on) "$label  ✓" else label, on) {
                val next = preferences.speakLanguages.toMutableSet()
                if (on) next.remove(language) else next.add(language)
                preferences.speakLanguages = next
                refresh()
            })
            gap(parent, 12)
        }
    }

    private fun hear() {
        page {
            it.addView(activity.text("你希望应用用什么语言回你？", 26f, Design.ink, true))
            gap(it, 16)
            hearChoices(it) { showWizard() }
            gap(it, 28)
            it.addView(filled("继续") {
                preferences.initStep = 4
                showWizard()
            })
        }
    }

    private fun hearChoices(parent: LinearLayout, refresh: () -> Unit) {
        listOf(
            GuideLanguage.CANTONESE to "粤语",
            GuideLanguage.MANDARIN to "普通话",
            GuideLanguage.ENGLISH to "英语"
        ).forEach { (language, label) ->
            parent.addView(choice(if (preferences.voiceLanguage == language) "$label  ✓" else label, preferences.voiceLanguage == language) {
                preferences.voiceLanguage = language
                refresh()
            })
            gap(parent, 12)
        }
    }

    private fun permission() {
        val on = serviceOn()
        page {
            if (!preferences.accessAgreed) {
                it.addView(activity.text("圆圈要浮在别的应用上面。", 26f, Design.ink, true))
                gap(it, 12)
                it.addView(activity.text("只提示，不替你点，不替你输入。", 22f, Design.ink))
                gap(it, 28)
                it.addView(filled("同意") {
                    preferences.accessAgreed = true
                    showWizard()
                })
                return@page
            }
            if (on) {
                it.addView(activity.text("开好了", 32f, Design.ink, true))
                gap(it, 12)
                it.addView(activity.text("来完成你的第一次引导吧。", 22f, Design.ink))
                gap(it, 28)
                it.addView(filled("来完成你的第一次引导吧") {
                    preferences.initStep = 5
                    showWizard()
                })
                return@page
            }
            if (preferences.accessLeft)
                it.addView(activity.text("还没打开，圆圈出不来。", 28f, Design.ink, true))
            else {
                it.addView(activity.text("接下来是手机系统的页面，不是我们的页面。", 24f, Design.ink, true))
                gap(it, 12)
                it.addView(activity.text("找到「数字扶手 · 手机引导」，把开关打开，再回到这里。", 20f, Design.ink))
                gap(it, 12)
                it.addView(activity.text("如果开关是灰的：先打开这个应用的「应用信息」，点右上角三个点，点「允许受限制的设置」，再回来把开关打开。", 18f, Design.ink))
            }
            gap(it, 28)
            it.addView(filled(if (preferences.accessLeft) "再去打开" else "去打开") {
                preferences.accessLeft = true
                openAccess()
            })
        }
    }

    private fun practice() {
        val root = white()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(22), activity.dp(48), activity.dp(22), activity.dp(24))
        }
        body.addView(activity.text("请点这里", 34f, Design.ink, true, Gravity.CENTER))
        gap(body, 28)
        val button = filled("点这里") {
            preferences.initDone = true
            preferences.initStep = 6
            done()
        }
        val ring = object : View(activity) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Design.forest
                style = Paint.Style.STROKE
                strokeWidth = activity.dp(8).toFloat()
            }
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.forest }
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = activity.dp(22).toFloat()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            override fun onDraw(canvas: Canvas) {
                val pad = activity.dp(20).toFloat()
                canvas.drawRoundRect(pad, pad, width - pad, height - pad, activity.dp(18).toFloat(), activity.dp(18).toFloat(), stroke)
                canvas.drawCircle(pad, pad, activity.dp(18).toFloat(), badge)
                canvas.drawText("1", pad, pad - (number.ascent() + number.descent()) / 2f, number)
            }
        }
        val wrap = FrameLayout(activity)
        wrap.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(120)))
        wrap.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(72)).apply {
            leftMargin = activity.dp(28)
            rightMargin = activity.dp(28)
            topMargin = activity.dp(24)
        })
        body.addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(120)))
        root.addView(body)
        activity.setContentView(root)
    }

    private fun done() {
        page {
            it.addView(activity.text("完成了", 36f, Design.ink, true, Gravity.CENTER))
            gap(it, 28)
            it.addView(filled("进入首页") { onDone() })
        }
    }

    private fun choice(label: String, chosen: Boolean, convert: Boolean = true, click: () -> Unit): TextView =
        activity.text(label, 22f, if (chosen) Design.surface else Design.ink, true, Gravity.CENTER, convert).apply {
            minHeight = activity.dp(72)
            background = if (chosen) Design.ripple(Design.forest, activity.dp(16).toFloat())
                else Design.ripple(Design.surface, activity.dp(16).toFloat(), Design.forest, activity.dp(3))
            setOnClickListener { click() }
        }

    private fun filled(label: String, click: () -> Unit): TextView =
        activity.text(label, 22f, Design.surface, true, Gravity.CENTER).apply {
            minHeight = activity.dp(72)
            background = Design.ripple(Design.forest, activity.dp(16).toFloat())
            setOnClickListener { click() }
        }

    private fun page(bodyOf: (LinearLayout) -> Unit) {
        val root = white()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(28), activity.dp(22), activity.dp(28))
        }
        bodyOf(body)
        root.addView(scroll(body))
        activity.setContentView(root)
    }

    private fun white() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Design.background)
        setPadding(0, activity.dp(36), 0, activity.dp(12))
    }

    private fun scroll(child: View) = ScrollView(activity).apply { addView(child) }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun gap(parent: LinearLayout, height: Int) {
        parent.addView(View(activity), LinearLayout.LayoutParams(1, activity.dp(height)))
    }
}
