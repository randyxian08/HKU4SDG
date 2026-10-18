package org.hku4sdg.demotarget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** A separate, fictional app so the guide exercises a genuine cross-app boundary. */
class PracticeActivity : Activity() {
    private var page = 0
    private var progress = 0
    private val background = 0xFFF7F7F3.toInt()
    private val ink = 0xFF23312E.toInt()
    private val secondary = 0xFF6F7974.toInt()
    private val green = 0xFF245D4E.toInt()
    private val line = 0xFFE5E9E3.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        progress = savedInstanceState?.getInt("progress") ?: 0
        showPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putInt("progress", progress)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun showPage() {
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(38))
            setBackgroundColor(this@PracticeActivity.background)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("练习场", 18f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("安全演示", 12f, green, true).apply {
            gravity = Gravity.CENTER
            background = shape(0xFFE8F1EA.toInt(), 14)
        }, LinearLayout.LayoutParams(dp(76), dp(30)))
        body.addView(top)
        space(body, 35)

        when (page) {
            0 -> home(body)
            1 -> display(body)
            2 -> fontSize(body)
            3 -> privateStep(body)
            else -> unknown(body)
        }
        if (page != 0) {
            space(body, 30)
            body.addView(label("‹  返回上一页", 17f, green, true).apply {
                minHeight = dp(54)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onBackPressed() }
            })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@PracticeActivity.background)
            addView(body)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottomInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            body.setPadding(dp(24), dp(26) + topInset, dp(24), dp(38))
            scroll.setPadding(0, 0, 0, bottomInset)
            insets
        }
        setContentView(scroll)
    }

    private fun home(body: LinearLayout) {
        body.addView(label("跟着提示，\n完成一件小事。", 32f, ink, true).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        space(body, 12)
        body.addView(label("这是独立的模拟应用，用来体验数字扶手如何在另一个 App 上圈出下一步。", 16f, secondary).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        })
        space(body, 25)
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = shape(0xFFFFF0E6.toInt(), 17)
        }
        note.addView(label("✦", 20f, 0xFFB76035.toInt(), true), LinearLayout.LayoutParams(dp(30), -2))
        note.addView(label("只是练习，不会更改手机设置", 15f, 0xFF805238.toInt(), true))
        body.addView(note)
        space(body, 34)
        body.addView(label("开始练习", 15f, secondary, true))
        space(body, 11)
        body.addView(row(R.id.open_display, "Aa", "显示设置", "前往字体大小", true) { page = 1; showPage() })
        space(body, 28)
        body.addView(label("看看引导何时会停下", 15f, secondary, true))
        space(body, 11)
        body.addView(row(R.id.private_entry, "◌", "模拟私人步骤", "这里不展示操作提示", false) { page = 3; showPage() })
        space(body, 10)
        body.addView(row(R.id.unknown_entry, "?", "模拟陌生页面", "测试未识别页面的处理", false) { page = 4; showPage() })
    }

    private fun display(body: LinearLayout) {
        body.addView(label("练习  02 / 03", 14f, green, true))
        space(body, 15)
        body.addView(label("显示设置", 32f, ink, true))
        space(body, 9)
        body.addView(label("下一步，打开字体大小。", 17f, secondary))
        space(body, 32)
        body.addView(row(R.id.open_font_size, "Aa", "字体大小", "调整屏幕文字显示", true) { page = 2; showPage() })
    }

    private fun fontSize(body: LinearLayout) {
        body.addView(label("练习  03 / 03", 14f, green, true))
        space(body, 15)
        body.addView(label("字体大小", 32f, ink, true))
        space(body, 9)
        body.addView(label("亲手拖动滑块试一试。", 17f, secondary))
        space(body, 30)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(23), dp(21), dp(22))
            background = shape(0xFFFFFFFF.toInt(), 22, line)
        }
        panel.addView(label("预览文字", 14f, secondary, true))
        space(panel, 10)
        val preview = label("清楚地看见每一步。", 23f + progress * 2, ink, true)
        panel.addView(preview)
        space(panel, 28)
        panel.addView(SeekBar(this).apply {
            id = R.id.font_slider
            max = 4
            progress = this@PracticeActivity.progress
            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(green)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        this@PracticeActivity.progress = value
                        preview.textSize = 23f + value * 2
                        result.visibility = if (value >= 2) View.VISIBLE else View.GONE
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        space(panel, 7)
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scale.addView(label("小字", 13f, secondary), LinearLayout.LayoutParams(0, -2, 1f))
        scale.addView(label("大字", 13f, secondary))
        panel.addView(scale)
        body.addView(panel)
        space(body, 17)
        body.addView(result.apply { visibility = if (progress >= 2) View.VISIBLE else View.GONE })
    }

    private fun privateStep(body: LinearLayout) {
        body.addView(label("边界测试", 14f, green, true))
        space(body, 15)
        body.addView(label("私人步骤", 32f, ink, true))
        space(body, 13)
        body.addView(label("这一页只用来验证数字扶手是否暂停。请不要输入真实资料。", 18f, secondary).apply {
            id = R.id.private_panel
            setLineSpacing(dp(5).toFloat(), 1f)
        })
        space(body, 18)
        body.addView(android.widget.EditText(this).apply {
            hint = "虚构密码测试，不填写"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
    }

    private fun unknown(body: LinearLayout) {
        body.addView(label("边界测试", 14f, green, true))
        space(body, 15)
        body.addView(label("陌生页面", 32f, ink, true))
        space(body, 13)
        body.addView(label("这里没有登记下一步。数字扶手应该停止圈画。", 18f, secondary))
    }

    private fun row(id: Int, symbol: String, title: String, subtitle: String, emphasized: Boolean, click: () -> Unit) =
        LinearLayout(this).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = shape(0xFFFFFFFF.toInt(), 21, line)
            isClickable = true
            isFocusable = true
            contentDescription = "$title，$subtitle"
            setOnClickListener { click() }
            addView(label(symbol, 20f, if (emphasized) green else secondary, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (emphasized) 0xFFE6F1EB.toInt() else this@PracticeActivity.background, 14)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val words = LinearLayout(this@PracticeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, 0, 0)
            }
            words.addView(label(title, 19f, ink, true))
            space(words, 5)
            words.addView(label(subtitle, 14f, secondary))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 25f, secondary))
        }

    private val result: TextView by lazy {
        label("✓  练习完成：你把练习文字调大了", 16f, green, true).apply {
            id = R.id.done_label
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = shape(0xFFE6F1EB.toInt(), 16)
        }
    }

    override fun onBackPressed() {
        if (page > 0) { page = if (page >= 3) 0 else page - 1; showPage() }
        else super.onBackPressed()
    }
}
