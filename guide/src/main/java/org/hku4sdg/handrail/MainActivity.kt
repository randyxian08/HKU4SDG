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
    private enum class Screen { HOME, TASK, LANGUAGES }
    private var screen = Screen.HOME
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
            "引导服务已开启"
        else "开启引导服务"
    }

    private fun showHome() {
        screen = Screen.HOME
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
        header.addView(text("语言", 14f, Design.forest, true, Gravity.CENTER).apply {
            letterSpacing = 0.03f
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
            setOnClickListener { showLanguages() }
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
        body.addView(header)
        space(body, 34)

        body.addView(text("今天想在手机上\n做什么？", 32f, Design.ink, true).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        space(body, 8)
        body.addView(text("说出来，或选择下面的任务。你自己动手，我们陪你走下一步。", 16f, Design.secondary).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(body, 22)

        val askCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
            background = Design.shape(Design.surface, dp(24).toFloat(), Design.line, dp(1))
        }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "例如：我想把字调大"
            textSize = 17f
            setTextColor(Design.ink)
            setHintTextColor(Design.secondary)
            background = null
            minHeight = dp(56)
            isSingleLine = true
            contentDescription = "输入你想完成的手机任务"
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        inputRow.addView(action("说话", Design.paleGreen, Design.forest, 54) { beginListening() },
            LinearLayout.LayoutParams(dp(74), dp(54)))
        askCard.addView(inputRow)
        space(askCard, 6)
        askCard.addView(action("查看可用引导  →", Design.forest, Design.surface) {
            if (TaskMatcher.matchesFontSize(input.text.toString())) showTask()
            else Toast.makeText(this, "目前可引导“把字调大”。也可以直接点下面的任务卡。", Toast.LENGTH_LONG).show()
        })
        body.addView(askCard)
        space(body, 27)
        body.addView(text("现在可以做", 20f, Design.ink, true))
        space(body, 13)

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
        // Service status stays visible, but setup no longer dominates the home screen.

        val feature = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = Design.shape(Design.forest, dp(28).toFloat())
        }
        feature.addView(text("手机设置  ·  约 1 分钟", 13f, 0xFFBCE6D3.toInt(), true))
        space(feature, 12)
        feature.addView(text("把字调大", 28f, Design.surface, true))
        space(feature, 8)
        feature.addView(text("在手机的真实设置里完成。每一步都有圈画、文字和语音。", 16f, 0xFFE0EFE8.toInt()).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(feature, 20)
        feature.addView(action("查看任务  →", Design.surface, Design.forest) { showTask() })
        body.addView(feature)
        space(body, 16)
        body.addView(serviceRow)
        space(body, 12)
        body.addView(text("当前仅验证 Android 15 / Pixel 7 模拟器的英文系统设置；其他设备会在无法确认页面时停下。", 13f, Design.secondary).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
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
        status.text = if (serviceEnabled()) "引导服务已开启" else "开启引导服务"
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

    private fun showTask() {
        screen = Screen.TASK
        val body = page("任务说明")
        body.addView(text("把字调大", 32f, Design.ink, true))
        space(body, 12)
        body.addView(text("让手机上的文字更容易阅读。数字扶手会打开系统设置，圈出下一步；真正的点击和拖动由你完成。", 18f, Design.secondary).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        })
        space(body, 28)
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(20), dp(21), dp(20))
            background = Design.shape(Design.surface, dp(24).toFloat(), Design.line, dp(1))
        }
        steps.addView(text("接下来会做", 15f, Design.forest, true))
        space(steps, 18)
        steps.addView(text("01   找到 Display size and text", 18f, Design.ink, true))
        space(steps, 14)
        steps.addView(text("02   调整 Font size 滑块", 18f, Design.ink, true))
        body.addView(steps)
        space(body, 20)
        body.addView(text("适用范围：已在 Android 15 / Pixel 7 模拟器的英文系统设置验证。不同手机页面可能不同；认不出时会停下，不会乱指。", 15f, Design.secondary).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(body, 26)
        body.addView(action("打开系统设置并开始  →", Design.forest, Design.surface, 62) { startSettingsGuide() })
        space(body, 12)
        body.addView(text("你随时可以在引导卡片上结束。", 14f, Design.secondary, gravity = Gravity.CENTER))
    }

    private fun showLanguages() {
        screen = Screen.LANGUAGES
        val body = page("语言设置")
        body.addView(text("按你的习惯来", 31f, Design.ink, true))
        space(body, 10)
        body.addView(text("输入、屏幕文字、语音播报可以分别选择。换语言不会改变引导的操作步骤。", 17f, Design.secondary))
        space(body, 24)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        languageRow(card, "入", "你说 / 输入", { preferences.inputLanguage }) { preferences.inputLanguage = it }
        divider(card)
        languageRow(card, "文", "屏幕文字", { preferences.textLanguage }) { preferences.textLanguage = it }
        divider(card)
        languageRow(card, "声", "语音播报", { preferences.voiceLanguage }) { preferences.voiceLanguage = it }
        body.addView(card)
        space(body, 20)
        body.addView(text("语音识别和播报由设备提供；某种语言不可用时，文字和任务卡仍可使用。", 15f, Design.secondary))
    }

    private fun page(title: String): LinearLayout {
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(36))
            setBackgroundColor(Design.background)
        }
        body.addView(text("←   $title", 17f, Design.forest, true).apply {
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showHome() }
        })
        space(body, 30)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Design.background)
            addView(body)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            body.setPadding(dp(24), dp(26) + top, dp(24), dp(36))
            scroll.setPadding(0, 0, 0, bottom)
            insets
        }
        setContentView(scroll)
        return body
    }

    private fun startSettingsGuide() {
        if (!serviceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("先开启引导服务")
                .setMessage("数字扶手需要无障碍服务才能在系统设置上圈出下一步。开启后回到这里继续；操作始终由你完成。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去设置") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .show()
            return
        }
        try {
            preferences.initialFontScale = Settings.System.getFloat(contentResolver, "font_scale", 1f)
            preferences.active = true
            preferences.collapsed = false
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        } catch (_: Exception) {
            preferences.active = false
            Toast.makeText(this, "无法打开系统显示设置，请稍后重试。", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity back callback used for this small native-view prototype")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else showHome()
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
