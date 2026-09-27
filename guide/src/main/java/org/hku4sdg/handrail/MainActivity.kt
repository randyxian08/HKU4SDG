package org.hku4sdg.handrail

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var preferences: GuidePreferences
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GuidePreferences(this)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = if (serviceEnabled())
            "引导服务已就绪 · 可以开始练习"
        else "开始前，先开启引导服务"
    }

    private fun showHome() {
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(36))
            setBackgroundColor(Design.background)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(HandrailMark(this), LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(text("数字扶手", 20f, Design.ink, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text("体验版", 12f, Design.forest, true, Gravity.CENTER).apply {
            letterSpacing = 0.03f
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
        }, LinearLayout.LayoutParams(dp(64), dp(32)))
        body.addView(header)
        space(body, 34)

        body.addView(text("一段安心的手机练习", 14f, Design.orange, true).apply { letterSpacing = 0.07f })
        space(body, 10)
        body.addView(text("手机操作，\n慢慢来。", 36f, Design.ink, true).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        space(body, 14)
        body.addView(text("你亲手操作，我们圈出下一步。\n想看文字，或听一遍，都可以。", 17f, Design.secondary).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        })
        space(body, 26)

        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
            background = Design.shape(Design.surface, dp(20).toFloat(), Design.line, dp(1))
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        serviceRow.addView(text("●", 16f, Design.forest, true), LinearLayout.LayoutParams(dp(28), -2))
        status = text("", 16f, Design.ink, true)
        serviceRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        serviceRow.addView(text("设置  ›", 14f, Design.forest, true))
        body.addView(serviceRow)
        space(body, 16)

        val feature = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = Design.shape(Design.forest, dp(28).toFloat())
        }
        feature.addView(text("今天先试这一件事", 14f, 0xFFBCE6D3.toInt(), true).apply { letterSpacing = 0.06f })
        space(feature, 16)
        feature.addView(text("把字调大", 29f, Design.surface, true))
        space(feature, 8)
        feature.addView(text("跟着圈画走 3 步，在练习场试一试。", 16f, 0xFFE0EFE8.toInt()).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(feature, 17)
        feature.addView(text("01   ───   02   ───   03", 13f, 0xFFBCE6D3.toInt(), true))
        space(feature, 20)
        feature.addView(action("开始练习     ↗", Design.surface, Design.forest) { startPractice() })
        body.addView(feature)
        space(body, 10)
        body.addView(text("练习场是独立的模拟 App，不会改动真实手机设置。", 13f, Design.secondary))
        space(body, 30)

        body.addView(text("也可以告诉我你想做什么", 21f, Design.ink, true))
        space(body, 8)
        body.addView(text("目前只支持上面的练习任务。", 14f, Design.secondary))
        space(body, 14)
        val askCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "例如：手机啲字太细"
            textSize = 17f
            setTextColor(Design.ink)
            setHintTextColor(Design.secondary)
            background = null
            minHeight = dp(54)
            isSingleLine = true
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        inputRow.addView(action("说话", Design.paleGreen, Design.forest, 54) { beginListening() },
            LinearLayout.LayoutParams(dp(76), dp(54)))
        askCard.addView(inputRow)
        space(askCard, 12)
        askCard.addView(action("按输入的内容开始", Design.paleGreen, Design.forest, 54) {
            if (TaskMatcher.matchesFontSize(input.text.toString())) startPractice()
            else Toast.makeText(this, "目前只支持“把字调大”，可点击上方练习卡片。", Toast.LENGTH_LONG).show()
        })
        body.addView(askCard)
        space(body, 32)

        body.addView(text("按你习惯的语言来", 21f, Design.ink, true))
        space(body, 8)
        body.addView(text("输入、屏幕文字、语音播报，可以分别选择。", 14f, Design.secondary))
        space(body, 14)
        val languageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        languageRow(languageCard, "入", "你说 / 输入", { preferences.inputLanguage }) { preferences.inputLanguage = it }
        divider(languageCard)
        languageRow(languageCard, "文", "屏幕文字", { preferences.textLanguage }) { preferences.textLanguage = it }
        divider(languageCard)
        languageRow(languageCard, "声", "语音播报", { preferences.voiceLanguage }) { preferences.voiceLanguage = it }
        body.addView(languageCard)
        space(body, 26)
        body.addView(text("你决定每一步。遇到陌生或私人页面，引导会停下来；语音输入会在使用前单独说明。", 14f, Design.secondary).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Design.background)
            addView(body)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottomInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            body.setPadding(dp(24), dp(26) + topInset, dp(24), dp(36))
            scroll.setPadding(0, 0, 0, bottomInset)
            insets
        }
        setContentView(scroll)
        onResume()
    }

    private fun action(label: String, fill: Int, color: Int, height: Int = 58, click: () -> Unit) =
        text(label, 17f, color, true, Gravity.CENTER).apply {
            minHeight = dp(height)
            background = Design.shape(fill, dp(16).toFloat())
            setOnClickListener { click() }
        }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(Design.line) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun languageRow(
        parent: LinearLayout, symbol: String, label: String,
        current: () -> GuideLanguage, onSelect: (GuideLanguage) -> Unit
    ) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(67) }
        row.addView(text(symbol, 15f, Design.forest, true, Gravity.CENTER).apply {
            background = Design.shape(Design.paleGreen, dp(13).toFloat())
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(text(label, 16f, Design.ink, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val value = text("${current().label}  ⌄", 15f, Design.forest, true)
        row.addView(value)
        row.setOnClickListener {
            PopupMenu(this, value).apply {
                GuideLanguage.entries.forEachIndexed { index, language -> menu.add(0, index, index, language.label) }
                setOnMenuItemClickListener { item ->
                    val selected = GuideLanguage.entries[item.itemId]
                    onSelect(selected)
                    value.text = "${selected.label}  ⌄"
                    true
                }
                show()
            }
        }
        parent.addView(row)
    }

    private fun serviceEnabled(): Boolean {
        val id = ComponentName(this, GuideAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == id }
    }

    private fun startPractice() {
        if (!serviceEnabled()) {
            Toast.makeText(this, "请先开启引导服务", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent().setClassName("org.hku4sdg.demotarget", "org.hku4sdg.demotarget.PracticeActivity")
        try {
            preferences.active = true
            preferences.collapsed = false
            startActivity(intent)
        } catch (_: Exception) {
            preferences.active = false
            Toast.makeText(this, "请先安装同仓库的 demo-target 应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginListening() {
        if (!preferences.speechConsent) {
            AlertDialog.Builder(this)
                .setTitle("启用语音输入？")
                .setMessage("点按说话后，音频会交给设备提供的语音识别服务处理；该服务可能联网，具体保留方式取决于设备设置。你也可以一直使用文字或任务卡。")
                .setNegativeButton("不用语音", null)
                .setPositiveButton("继续") { _, _ -> preferences.speechConsent = true; beginListening() }
                .show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此设备没有可用语音识别，请使用文字或任务卡", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "正在聆听…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { status.text = "没有听清（错误 $error），请改用文字或任务卡。" }
                override fun onResults(results: Bundle?) {
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (transcript != null) {
                        input.setText(transcript)
                        status.text = "识别为：$transcript。请确认后再开始。"
                    } else status.text = "没有听清，请改用文字或任务卡。"
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferences.inputLanguage.tag)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
