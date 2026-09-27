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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
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
    private lateinit var serviceDot: TextView
    private lateinit var serviceRowView: LinearLayout
    private lateinit var speechStatus: TextView
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GuidePreferences(this)
        when (savedInstanceState?.getString("screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull() }) {
            Screen.TASK -> showTask()
            Screen.LANGUAGES -> showLanguages()
            else -> {
                showHome()
                savedInstanceState?.getString("goal")?.let(input::setText)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        if (::input.isInitialized) outState.putString("goal", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateServiceStatus()
    }

    private fun showHome() {
        screen = Screen.HOME
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.background)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(28))
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(HandrailMark(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(38), dp(38)))
        topBar.addView(text("数字扶手", 18f, Design.ink, true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(text("语言", 15f, Design.forest, true, Gravity.CENTER).apply {
            minHeight = dp(48)
            minWidth = dp(56)
            contentDescription = "语言设置"
            background = Design.ripple(Design.paleGreen, dp(16).toFloat())
            markAsButton()
            setOnClickListener { showLanguages() }
        })
        body.addView(topBar)
        space(body, 30)
        body.addView(text("你想在手机上\n完成什么？", 28f, Design.ink, true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
        })
        space(body, 11)
        body.addView(text("选一个任务，跟着提示自己完成。", 16f, Design.secondary))
        space(body, 33)

        val section = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        section.addView(text("可用引导", 18f, Design.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(text("1 项", 14f, Design.secondary))
        body.addView(section)
        space(body, 14)

        val task = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(18), dp(16), dp(18))
            background = Design.ripple(Design.surface, dp(22).toFloat(), Design.line)
            markAsButton()
            contentDescription = "把字调大，系统设置，约一分钟。查看任务"
            setOnClickListener { showTask() }
        }
        task.addView(text("Aa", 21f, Design.forest, true, Gravity.CENTER).apply {
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val taskCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        taskCopy.addView(text("把字调大", 22f, Design.ink, true))
        space(taskCopy, 4)
        taskCopy.addView(text("系统设置 · 约 1 分钟", 15f, Design.secondary))
        task.addView(taskCopy, LinearLayout.LayoutParams(0, -2, 1f))
        task.addView(text("›", 28f, Design.forest, gravity = Gravity.CENTER).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        body.addView(task)
        space(body, 18)

        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(6), 0, dp(6), 0)
            background = Design.ripple(Design.background, dp(12).toFloat())
            markAsButton()
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        serviceRowView = serviceRow
        serviceDot = text("●", 14f, Design.forest, true)
        serviceRow.addView(serviceDot, LinearLayout.LayoutParams(dp(25), -2))
        status = text("", 15f, Design.secondary, true)
        serviceRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        serviceRow.addView(text("查看设置  ›", 14f, Design.forest, true))
        body.addView(serviceRow)
        space(body, 16)
        body.addView(text("目前只支持上面的任务。无法确认的页面会停止指引。", 14f, Design.secondary).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Design.background)
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundColor(Design.background)
        }
        composer.addView(text("或者，告诉我你想做什么", 15f, Design.ink, true))
        space(composer, 10)
        val composerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(6), dp(6), dp(6))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        input = EditText(this).apply {
            hint = "例如：把字调大"
            textSize = 17f
            setTextColor(Design.ink)
            setHintTextColor(Design.secondary)
            background = null
            minHeight = dp(52)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            contentDescription = "输入你想完成的手机任务"
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    submitGoal()
                    true
                } else false
            }
        }
        composerRow.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        composerRow.addView(action("说话", Design.paleGreen, Design.forest, 52) { beginListening() }.apply {
            contentDescription = "语音输入任务"
        }, LinearLayout.LayoutParams(dp(64), dp(52)))
        composerRow.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        composerRow.addView(action("→", Design.forest, Design.surface, 52) { submitGoal() }.apply {
            contentDescription = "查看输入对应的任务"
            textSize = 24f
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        composer.addView(composerRow)
        space(composer, 7)
        speechStatus = text("", 13f, Design.secondary)
        composer.addView(speechStatus)
        root.addView(composer)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottomInset = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
            else insets.systemWindowInsetBottom
            root.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        setContentView(root)
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val enabled = serviceEnabled()
        status.text = if (enabled) "引导服务已开启" else "开启引导服务"
        serviceDot.setTextColor(if (enabled) Design.forest else Design.orange)
        serviceRowView.contentDescription = if (enabled) "引导服务已开启，查看设置" else "引导服务未开启，去设置"
    }

    private fun View.markAsButton() {
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    private fun submitGoal() {
        if (TaskMatcher.matchesFontSize(input.text.toString())) showTask()
        else Toast.makeText(this, "目前只支持“把字调大”。你也可以点上方任务。", Toast.LENGTH_LONG).show()
    }

    private fun action(label: String, fill: Int, color: Int, height: Int = 58, click: () -> Unit) =
        text(label, 17f, color, true, Gravity.CENTER).apply {
            minHeight = dp(height)
            background = Design.ripple(fill, dp(16).toFloat())
            markAsButton()
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
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(67)
            markAsButton()
            contentDescription = "$label，当前${current().label}，选择语言"
            background = Design.ripple(Design.surface, dp(12).toFloat())
        }
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
                    row.contentDescription = "$label，当前${selected.label}，选择语言"
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
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.background)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(13), dp(22), dp(28))
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(text("‹", 31f, Design.forest, gravity = Gravity.CENTER).apply {
            contentDescription = "返回首页"
            background = Design.ripple(Design.background, dp(16).toFloat())
            markAsButton()
            setOnClickListener { showHome() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(text("任务确认", 17f, Design.ink, true).apply { setPadding(dp(7), 0, 0, 0) })
        body.addView(topBar)
        space(body, 31)
        body.addView(text("系统设置  ·  约 1 分钟", 14f, Design.forest, true))
        space(body, 9)
        body.addView(text("把字调大", 30f, Design.ink, true))
        space(body, 12)
        body.addView(text("接下来会打开手机设置。数字扶手只圈出下一步，点击和拖动都由你完成。", 17f, Design.secondary).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(body, 31)
        body.addView(text("你会做两步", 18f, Design.ink, true))
        space(body, 14)
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        fun step(number: String, copy: String) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(55) }
            row.addView(text(number, 14f, Design.forest, true, Gravity.CENTER).apply {
                background = Design.shape(Design.paleGreen, dp(15).toFloat())
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(text(copy, 17f, Design.ink, true).apply { setPadding(dp(13), 0, 0, 0) })
            steps.addView(row)
        }
        step("1", "找到 Display size and text")
        space(steps, 6)
        step("2", "调整 Font size 滑块")
        body.addView(steps)
        space(body, 22)
        val scope = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
        }
        scope.addView(text("已验证的设备", 14f, Design.forest, true))
        space(scope, 6)
        scope.addView(text("Android 15 / Pixel 7 模拟器，英文系统设置。其他手机的页面不同；认不出时会停下。", 15f, Design.ink).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        body.addView(scope)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Design.background)
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(13))
            setBackgroundColor(Design.background)
        }
        footer.addView(text("这会实际改变手机的字体大小", 14f, Design.secondary, gravity = Gravity.CENTER))
        space(footer, 10)
        footer.addView(action("打开系统设置，开始引导", Design.forest, Design.surface, 60) { startSettingsGuide() })
        root.addView(footer)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            root.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)
    }

    private fun showLanguages() {
        screen = Screen.LANGUAGES
        val body = page("语言设置")
        body.addView(text("按你的习惯来", 28f, Design.ink, true))
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
        body.addView(text("语音识别由设备提供。播报可能将固定引导文字交给云端语音服务；连接失败时使用设备播报。文字和任务卡始终可用。", 15f, Design.secondary))
    }

    private fun page(title: String): LinearLayout {
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(13), dp(22), dp(28))
            setBackgroundColor(Design.background)
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(text("‹", 31f, Design.forest, gravity = Gravity.CENTER).apply {
            contentDescription = "返回首页"
            markAsButton()
            background = Design.ripple(Design.background, dp(16).toFloat())
            setOnClickListener { showHome() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(text(title, 17f, Design.ink, true).apply { setPadding(dp(7), 0, 0, 0) })
        body.addView(topBar)
        space(body, 31)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Design.background)
            addView(body)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            body.setPadding(dp(22), dp(13) + top, dp(22), dp(28))
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
                override fun onReadyForSpeech(params: Bundle?) { speechStatus.text = "正在聆听…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { speechStatus.text = "没有听清，请改用文字或任务卡。（错误 $error）" }
                override fun onResults(results: Bundle?) {
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (transcript != null) {
                        input.setText(transcript)
                        speechStatus.text = "识别为：$transcript。确认后点箭头。"
                    } else speechStatus.text = "没有听清，请改用文字或任务卡。"
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
