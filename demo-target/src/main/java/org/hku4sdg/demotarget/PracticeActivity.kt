package org.hku4sdg.demotarget

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** A separate, fictional app so the guide exercises a genuine cross-app boundary. */
class PracticeActivity : Activity() {
    private var page = 0
    private var progress = 0

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

    private fun showPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 100, 32, 32)
            setBackgroundColor(0xFFF5F5EF.toInt())
        }
        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 28f
            setTextColor(0xFF182B28.toInt())
            root.addView(this)
        }
        fun button(id: Int, label: String, click: () -> Unit) = Button(this).apply {
            this.id = id
            text = label
            textSize = 22f
            minHeight = 76
            setOnClickListener { click() }
            root.addView(this)
        }
        when (page) {
            0 -> {
                heading("手机设置练习场")
                root.addView(TextView(this).apply { text = "这是虚构练习环境，不会更改真实系统设置。"; textSize = 19f })
                button(R.id.open_display, "显示设置") { page = 1; showPage() }
                button(R.id.private_entry, "模拟私人步骤") { page = 3; showPage() }
                button(R.id.unknown_entry, "模拟陌生页面") { page = 4; showPage() }
            }
            1 -> {
                heading("显示设置")
                button(R.id.open_font_size, "字体大小") { page = 2; showPage() }
            }
            2 -> {
                heading("字体大小")
                root.addView(TextView(this).apply { text = "请亲手拖动滑块"; textSize = 20f })
                root.addView(SeekBar(this).apply {
                    id = R.id.font_slider
                    max = 4
                    progress = this@PracticeActivity.progress
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) {
                                this@PracticeActivity.progress = value
                                result.visibility = if (value >= 2) View.VISIBLE else View.GONE
                            }
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                    })
                })
                root.addView(result.apply { visibility = if (progress >= 2) View.VISIBLE else View.GONE })
            }
            3 -> {
                heading("私人步骤（模拟）")
                root.addView(TextView(this).apply {
                    id = R.id.private_panel
                    text = "此页面只用于验证指导是否暂停。请不要输入真实资料。"
                    textSize = 21f
                })
            }
            else -> heading("陌生页面（模拟）")
        }
        button(View.generateViewId(), "返回上一页") { onBackPressed() }
        setContentView(root)
    }

    private val result: TextView by lazy {
        TextView(this).apply {
            id = R.id.done_label
            text = "练习完成：文字已调大"
            textSize = 24f
        }
    }

    override fun onBackPressed() {
        if (page > 0) { page = if (page >= 3) 0 else page - 1; showPage() }
        else super.onBackPressed()
    }
}
