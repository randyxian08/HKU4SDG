package org.hku4sdg.handrail

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Screen { HOME, TASK, LANGUAGES, PRACTICE, SETUP, RECOVERY }
    private var screen = Screen.HOME
    private lateinit var preferences: GuidePreferences
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var serviceDot: TextView
    private lateinit var serviceRowView: LinearLayout
    private lateinit var speechStatus: TextView
    private lateinit var speechButton: TextView
    private lateinit var intentClient: IntentClient
    private lateinit var cloudAsr: CloudAsr
    private var selectedTask = GuideTask.FONT_SIZE
    private var taskSuggestedByAi = false
    private var selectedHelp = HelpMode.FULL
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GuidePreferences(this)
        intentClient = IntentClient(this)
        cloudAsr = CloudAsr(this)
        selectedTask = savedInstanceState?.getString("selectedTask")?.let(GuideTask::fromId)
            ?: GuideTask.FONT_SIZE
        taskSuggestedByAi = savedInstanceState?.getBoolean("taskSuggestedByAi") ?: false
        selectedHelp = savedInstanceState?.getString("help")?.let {
            runCatching { HelpMode.valueOf(it) }.getOrNull()
        } ?: HelpMode.FULL
        if (intent.getBooleanExtra("return_to_task", false)) {
            intent.removeExtra("return_to_task")
            selectedTask = preferences.activeTask
            taskSuggestedByAi = false
            showTask()
            return
        }
        if (preferences.setupTask != null) {
            selectedTask = preferences.setupTask!!
            selectedHelp = preferences.setupHelp
            showSetup()
            return
        }
        when (savedInstanceState?.getString("screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull() }) {
            Screen.TASK -> showTask()
            Screen.LANGUAGES -> showLanguages()
            Screen.PRACTICE -> showPractice()
            Screen.SETUP -> showSetup()
            Screen.RECOVERY -> showRecovery()
            else -> {
                showHome()
                savedInstanceState?.getString("goal")?.let(input::setText)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putBoolean("taskSuggestedByAi", taskSuggestedByAi)
        outState.putString("selectedTask", selectedTask.id)
        outState.putString("help", selectedHelp.name)
        if (::input.isInitialized) outState.putString("goal", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::preferences.isInitialized && preferences.receiptPending) {
            preferences.receiptPending = false
            showPractice()
        }
        if (::preferences.isInitialized && preferences.active) {
            preferences.pause(PauseReason.USER)
            if (screen != Screen.SETUP) showRecovery()
        } else if (screen == Screen.SETUP) showSetup()
        if (::status.isInitialized) updateServiceStatus()
        // Binding can finish just after returning from Settings. Refresh without starting a task.
        listOf(500L, 1500L).forEach { delay ->
            window.decorView.postDelayed({
                if (!isFinishing && hasWindowFocus()) {
                    if (screen == Screen.HOME && ::status.isInitialized) updateServiceStatus()
                    if (screen == Screen.SETUP && serviceEnabled() && GuideAccessibilityService.connected) showSetup()
                }
            }, delay)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("return_to_task", false)) {
            intent.removeExtra("return_to_task")
            selectedTask = preferences.activeTask
            taskSuggestedByAi = false
            showTask()
        }
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
        topBar.addView(action("记录", Design.background, Design.forest, 48) { showPractice() }.apply {
            minWidth = dp(52)
            contentDescription = "查看练习记录"
        })
        topBar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        topBar.addView(text("语言", 15f, Design.forest, true, Gravity.CENTER).apply {
            minHeight = dp(48)
            minWidth = dp(56)
            contentDescription = "语言设置"
            background = Design.ripple(Design.paleGreen, dp(16).toFloat())
            markAsButton()
            setOnClickListener { showLanguages() }
        })
        body.addView(topBar)
        if (preferences.active) {
            space(body, 18)
            body.addView(action(SupportCopy.recoveryTitle(preferences.textLanguage), Design.paleGreen, Design.forest, 52) { showRecovery() })
        }
        space(body, 28)
        body.addView(text("今天想做什么？", 27f, Design.ink, true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
        })
        space(body, 11)
        body.addView(text("选一个任务，跟着提示自己完成。", 16f, Design.secondary))
        space(body, 24)

        val section = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        section.addView(text("可用引导", 18f, Design.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(text("4 项", 14f, Design.secondary))
        body.addView(section)
        space(body, 14)

        val task = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(18), dp(16), dp(18))
            background = Design.ripple(Design.surface, dp(22).toFloat(), Design.line)
            markAsButton()
            contentDescription = "把字调大，系统设置，约一分钟。查看任务"
            setOnClickListener { selectedTask = GuideTask.FONT_SIZE; taskSuggestedByAi = false; showTask() }
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
        space(body, 12)
        val brightnessTask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(18), dp(16), dp(18))
            background = Design.ripple(Design.surface, dp(22).toFloat(), Design.line)
            markAsButton()
            contentDescription = "调高屏幕亮度，系统设置，约一分钟。查看任务"
            setOnClickListener {
                selectedTask = GuideTask.SCREEN_BRIGHTNESS
                taskSuggestedByAi = false
                showTask()
            }
        }
        brightnessTask.addView(text("☀", 24f, Design.forest, true, Gravity.CENTER).apply {
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brightnessCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brightnessCopy.addView(text("调高屏幕亮度", 22f, Design.ink, true))
        space(brightnessCopy, 4)
        brightnessCopy.addView(text("系统设置 · 约 1 分钟", 15f, Design.secondary))
        brightnessTask.addView(brightnessCopy, LinearLayout.LayoutParams(0, -2, 1f))
        brightnessTask.addView(text("›", 28f, Design.forest, gravity = Gravity.CENTER).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        body.addView(brightnessTask)
        space(body, 12)
        val chromeTask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(18), dp(16), dp(18))
            background = Design.ripple(Design.surface, dp(22).toFloat(), Design.line)
            markAsButton()
            contentDescription = "收藏长者服务网页，在 Chrome 操作，约一分钟。查看任务"
            setOnClickListener {
                selectedTask = GuideTask.CHROME_BOOKMARK
                taskSuggestedByAi = false
                showTask()
            }
        }
        chromeTask.addView(text("☆", 25f, Design.forest, true, Gravity.CENTER).apply {
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val chromeCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        chromeCopy.addView(text("收藏长者服务网页", 21f, Design.ink, true))
        space(chromeCopy, 4)
        chromeCopy.addView(text("Chrome · 约 1 分钟", 15f, Design.secondary))
        chromeTask.addView(chromeCopy, LinearLayout.LayoutParams(0, -2, 1f))
        chromeTask.addView(text("›", 28f, Design.forest, gravity = Gravity.CENTER).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        body.addView(chromeTask)
        space(body, 12)
        val mapsTask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(18), dp(16), dp(18))
            background = Design.ripple(Design.surface, dp(22).toFloat(), Design.line)
            markAsButton()
            contentDescription = "查看香港中央图书馆地址，在 Google Maps 操作，约一分钟。查看任务"
            setOnClickListener {
                selectedTask = GuideTask.MAPS_LIBRARY_ADDRESS
                taskSuggestedByAi = false
                showTask()
            }
        }
        mapsTask.addView(text("⌖", 26f, Design.forest, true, Gravity.CENTER).apply {
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val mapsCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        mapsCopy.addView(text("查看中央图书馆地址", 21f, Design.ink, true))
        space(mapsCopy, 4)
        mapsCopy.addView(text("Google Maps · 约 1 分钟", 15f, Design.secondary))
        mapsTask.addView(mapsCopy, LinearLayout.LayoutParams(0, -2, 1f))
        mapsTask.addView(text("›", 28f, Design.forest, gravity = Gravity.CENTER).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        body.addView(mapsTask)
        space(body, 18)

        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(6), 0, dp(6), 0)
            background = Design.ripple(Design.background, dp(12).toFloat())
            markAsButton()
            setOnClickListener { showSetup() }
        }
        serviceRowView = serviceRow
        serviceDot = text("●", 14f, Design.forest, true)
        serviceRow.addView(serviceDot, LinearLayout.LayoutParams(dp(25), -2))
        status = text("", 15f, Design.secondary, true)
        serviceRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        serviceRow.addView(text("查看设置  ›", 14f, Design.forest, true))
        body.addView(serviceRow)

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
        speechButton = action("说话", Design.paleGreen, Design.forest, 52) { beginListening() }.apply {
            contentDescription = "语音输入任务"
        }
        composerRow.addView(speechButton, LinearLayout.LayoutParams(dp(64), dp(52)))
        composerRow.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        composerRow.addView(action("→", Design.forest, Design.surface, 52) { submitGoal() }.apply {
            contentDescription = "查看输入对应的任务"
            textSize = 24f
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        composer.addView(composerRow)
        space(composer, 7)
        composer.addView(text("只发送你提交的目标，不发送屏幕。", 14f, Design.secondary))
        space(composer, 4)
        speechStatus = text("", 16f, Design.ink, true).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Design.shape(Design.paleGreen, dp(14).toFloat())
        }
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
        status.text = if (enabled && GuideAccessibilityService.connected) "引导服务已开启"
            else if (enabled) "引导服务正在连接" else "开启引导服务"
        serviceRowView.visibility = if (enabled && GuideAccessibilityService.connected) View.GONE else View.VISIBLE
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
        val goal = input.text.toString().trim()
        if (goal.isEmpty()) {
            setInputStatus("先输入想完成的事，或选择上方任务。")
            return
        }
        if (goal.length > 160) {
            setInputStatus("目标描述太长，请用一句话说明。")
            return
        }
        if (SensitiveGoalGuard.shouldKeepLocal(goal)) {
            setInputStatus("这段文字可能涉及私人内容，已停止发送。请使用上方任务卡。")
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        setInputStatus(GuideContent.intentSearching(preferences.textLanguage))
        intentClient.classify(goal, preferences.inputLanguage) { result ->
            if (screen != Screen.HOME || input.text.toString().trim() != goal) return@classify
            when (result?.decision) {
                IntentClient.Decision.MATCH -> {
                    selectedTask = GuideTask.fromId(result.taskId) ?: return@classify
                    taskSuggestedByAi = true
                    showTask()
                }
                IntentClient.Decision.CLARIFY -> setInputStatus(GuideContent.intentClarify(preferences.textLanguage))
                IntentClient.Decision.UNSUPPORTED -> setInputStatus(GuideContent.intentUnsupported(preferences.textLanguage))
                null -> {
                    val fallback = TaskMatcher.match(goal)
                    if (fallback != null) {
                        selectedTask = fallback
                        taskSuggestedByAi = false
                        showTask()
                    } else setInputStatus(GuideContent.intentUnavailable(preferences.textLanguage))
                }
            }
        }
    }

    private fun setInputStatus(message: String) {
        speechStatus.visibility = View.VISIBLE
        speechStatus.text = message
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
        val brightness = selectedTask == GuideTask.SCREEN_BRIGHTNESS
        val chrome = selectedTask == GuideTask.CHROME_BOOKMARK
        val maps = selectedTask == GuideTask.MAPS_LIBRARY_ADDRESS
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
        body.addView(text(when {
            chrome -> "Chrome  ·  约 1 分钟"
            maps -> "Google Maps  ·  约 1 分钟"
            else -> "系统设置  ·  约 1 分钟"
        }, 14f, Design.forest, true))
        space(body, 9)
        body.addView(text(when {
            chrome -> "收藏长者服务网页"
            maps -> "查看中央图书馆地址"
            brightness -> "调高屏幕亮度"
            else -> "把字调大"
        }, 30f, Design.ink, true))
        space(body, 16)
        val previous = preferences.records().firstOrNull {
            it.task == selectedTask && it.outcome == PracticeOutcome.CONFIRMED
        }
        if (previous != null) {
            body.addView(text("上次你确认完成过。今天想要多少帮助？", 16f, Design.forest, true))
            space(body, 9)
        }
        body.addView(action("${SupportCopy.help(preferences.textLanguage)}：${SupportCopy.mode(selectedHelp, preferences.textLanguage)}  ⌄", Design.paleGreen, Design.forest, 52) {
            AlertDialog.Builder(this).setTitle(SupportCopy.helpTitle(preferences.textLanguage))
                .setSingleChoiceItems(HelpMode.entries.map { "${SupportCopy.mode(it, preferences.textLanguage)}：${SupportCopy.modeDescription(it, preferences.textLanguage)}" }.toTypedArray(),
                    selectedHelp.ordinal) { dialog, which ->
                    selectedHelp = HelpMode.entries[which]
                    dialog.dismiss()
                    showTask()
                }.setNegativeButton(SupportCopy.keepChoice(preferences.textLanguage), null).show()
        })
        space(body, 12)
        if (taskSuggestedByAi) {
            body.addView(text(GuideContent.intentMatched(preferences.textLanguage), 15f, Design.forest, true))
            space(body, 10)
        }
        body.addView(text(when {
            chrome -> "接下来会在 Chrome 打开香港政府一站通的公开长者服务网页。数字扶手只圈出下一步，收藏由你亲手完成。"
            maps -> "接下来会在 Google Maps 打开香港中央图书馆的地点卡。你亲手打开详情，数字扶手只指出地址在哪一行；数字扶手不会请求你的位置。"
            else -> "接下来会打开手机设置。数字扶手只圈出下一步，点击和拖动都由你完成。"
        },
            17f, Design.secondary).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        space(body, 31)
        body.addView(text(if (chrome) "你会做三步" else "你会做两步", 18f, Design.ink, true))
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
        step("1", when {
            chrome -> "打开 Chrome 右上角菜单"
            maps -> "点开图书馆地点详情"
            brightness -> "找到 Brightness level"
            else -> "找到 Display size and text"
        })
        space(steps, 6)
        step("2", when {
            chrome -> "点 Bookmark 星标收藏"
            maps -> "查看地址行"
            brightness -> "调整屏幕亮度滑块"
            else -> "调整 Font size 滑块"
        })
        if (chrome) {
            space(steps, 6)
            step("3", "再次打开菜单，确认已收藏")
        }
        body.addView(steps)
        space(body, 22)
        val scope = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
        }
        scope.addView(text("已验证的设备", 14f, Design.forest, true))
        space(scope, 6)
        scope.addView(text(when {
            chrome -> "Android 15 / Pixel 7 模拟器，Chrome 124 和政府一站通公开网页。浏览器或网页改变时会停止圈画；这不是政府合作服务。"
            maps -> "Android 15 / Pixel 7 模拟器，Google Maps 11.129 和香港中央图书馆地点卡。地址信息来自地图，出行前可再向图书馆核对；这不是 Google 或图书馆合作服务。"
            else -> "Android 15 / Pixel 7 模拟器，英文系统设置。其他手机的页面不同；认不出时会停下。"
        },
            15f, Design.ink).apply {
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
        footer.addView(text(when {
            chrome -> "这会在 Chrome 保存一个公开网页书签"
            maps -> "这只会打开公开地点信息，不会发送消息或导航"
            brightness -> "这会实际改变手机的屏幕亮度"
            else -> "这会实际改变手机的字体大小"
        },
            14f, Design.secondary, gravity = Gravity.CENTER))
        space(footer, 10)
        footer.addView(action(when {
            chrome -> "打开 Chrome，开始引导"
            maps -> "打开地图，开始引导"
            else -> "打开系统设置，开始引导"
        },
            Design.forest, Design.surface, 60) { startGuide() })
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
        body.addView(text("输入、引导文字与 AI 提示、语音播报可以分别选择。换语言不会改变引导的操作步骤。", 17f, Design.secondary))
        space(body, 24)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line, dp(1))
        }
        languageRow(card, "入", "你说 / 输入", { preferences.inputLanguage }) { preferences.inputLanguage = it }
        divider(card)
        languageRow(card, "文", "引导文字 / AI 提示", { preferences.textLanguage }) { preferences.textLanguage = it }
        divider(card)
        languageRow(card, "声", "语音播报", { preferences.voiceLanguage }) { preferences.voiceLanguage = it }
        body.addView(card)
        space(body, 20)
        body.addView(action(SupportCopy.setupTitle(preferences.textLanguage), Design.paleGreen, Design.forest, 52) { showSetup() })
        space(body, 20)
        body.addView(text("开发版模拟器中，你主动录制的语音经本机代理交给火山引擎识别；其他版本使用设备识别。播报可能把固定引导文字交给云端服务。文字和任务卡始终可用。", 15f, Design.secondary))
    }

    private fun page(title: String, onBack: () -> Unit = ::showHome): LinearLayout {
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
            contentDescription = SupportCopy.backHome(preferences.textLanguage)
            markAsButton()
            background = Design.ripple(Design.background, dp(16).toFloat())
            setOnClickListener { onBack() }
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

    private fun startGuide() {
        if (!serviceEnabled() || !GuideAccessibilityService.connected) {
            preferences.setupTask = selectedTask
            preferences.setupHelp = selectedHelp
            showSetup()
            return
        }
        try {
            if (selectedTask == GuideTask.SCREEN_BRIGHTNESS) {
                val mode = Settings.System.getInt(contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    AlertDialog.Builder(this).setTitle("当前使用自动亮度")
                        .setMessage("这项演示只验证了手动亮度模式。你仍可以自行调整亮度；数字扶手会停止圈画。")
                        .setPositiveButton("知道了", null).show()
                    return
                }
                preferences.initialBrightness = Settings.System.getInt(contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS, -1)
                if (preferences.initialBrightness < 0) error("Brightness unavailable")
                if (preferences.initialBrightness >= 250) {
                    AlertDialog.Builder(this).setTitle("屏幕亮度已接近最高")
                        .setMessage("现在无法再明显调高亮度。请先在系统设置中调低亮度，之后再开始这项引导。")
                        .setPositiveButton("知道了", null).show()
                    return
                }
            } else if (selectedTask == GuideTask.FONT_SIZE) {
                preferences.initialFontScale = Settings.System.getFloat(contentResolver, "font_scale", 1f)
                if (preferences.initialFontScale >= 1.99f) {
                    AlertDialog.Builder(this).setTitle("字体已经是最大一档")
                        .setMessage("这台演示设备现在无法再把字调大。你可以去设置中按阅读习惯自行调整。")
                        .setNegativeButton("返回", null)
                        .setPositiveButton("去自己调整") { _, _ -> startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
                        .show()
                    return
                }
            }
            if (preferences.active) preferences.finish(PracticeOutcome.ENDED)
            preferences.activeTask = selectedTask
            preferences.guideSessionId += 1
            preferences.sessionStart = System.currentTimeMillis()
            preferences.receiptPending = false
            preferences.helpMode = selectedHelp
            preferences.active = true
            preferences.collapsed = selectedHelp == HelpMode.LIGHT
            openTask(preferences.activeTask)
        } catch (_: Exception) {
            preferences.pause(PauseReason.SYSTEM_INTERRUPT)
            showRecovery()
            Toast.makeText(this, "无法打开目标应用，请稍后重试。", Toast.LENGTH_LONG).show()
        }
    }

    private fun openTask(task: GuideTask) {
        when (task) {
            GuideTask.CHROME_BOOKMARK -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.gov.hk/tc/residents/housing/socialservices/elderly/index.htm"))
                .setPackage("com.android.chrome"))
            GuideTask.MAPS_LIBRARY_ADDRESS -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=Hong%20Kong%20Central%20Library"))
                .setPackage("com.google.android.apps.maps"))
            else -> startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }

    private fun openServiceSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure {
            Toast.makeText(this, SupportCopy.notReady(preferences.textLanguage), Toast.LENGTH_LONG).show()
        }
    }

    private fun leaveSetup() {
        val task = preferences.setupTask
        if (task != null) {
            selectedTask = task
            selectedHelp = preferences.setupHelp
            preferences.setupTask = null
            taskSuggestedByAi = false
            if (preferences.active) showRecovery() else showTask()
        } else showHome()
    }

    private fun showSetup() {
        screen = Screen.SETUP
        val l = preferences.textLanguage
        val switchedOn = serviceEnabled()
        val enabled = switchedOn && GuideAccessibilityService.connected
        val body = page(SupportCopy.setupTitle(l), ::leaveSetup)
        body.addView(text(SupportCopy.setupHeading(l, enabled), 28f, Design.ink, true))
        space(body, 14)
        body.addView(text(SupportCopy.setupWhy(l), 17f, Design.secondary))
        space(body, 22)
        if (!switchedOn) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = Design.shape(Design.surface, dp(22).toFloat(), Design.line)
            }
            SupportCopy.setupSteps(l).forEachIndexed { index, copy ->
                card.addView(text("${index + 1}.  $copy", 17f, Design.ink, true))
                if (index < 2) space(card, 16)
            }
            body.addView(card)
            space(body, 18)
        }
        body.addView(text(SupportCopy.setupPrivacy(l), 15f, Design.secondary))
        space(body, 22)
        body.addView(text(if (enabled) SupportCopy.ready(l) else if (switchedOn) SupportCopy.connecting(l) else SupportCopy.notReady(l), 17f,
            if (enabled) Design.forest else Design.secondary, true))
        space(body, 14)
        body.addView(action(if (enabled) {
            if (preferences.setupTask != null) {
                if (preferences.active) SupportCopy.returnTask(l) else SupportCopy.backToTask(l)
            } else SupportCopy.backHome(l)
        } else if (switchedOn) SupportCopy.checkAgain(l) else SupportCopy.openSettings(l), Design.forest, Design.surface, 56) {
            if (enabled) leaveSetup() else if (switchedOn) showSetup() else openServiceSettings()
        })
        if (!enabled) {
            space(body, 10)
            body.addView(action(if (preferences.setupTask != null) SupportCopy.backToTask(l) else SupportCopy.backHome(l),
                Design.background, Design.secondary, 52) { leaveSetup() })
        }
    }

    private fun showRecovery() {
        if (!preferences.active) { showHome(); return }
        preferences.pause(PauseReason.USER)
        screen = Screen.RECOVERY
        val l = preferences.textLanguage
        val body = page(SupportCopy.recoveryTitle(l))
        body.addView(text(SupportCopy.recoveryHeading(l), 28f, Design.ink, true))
        space(body, 14)
        body.addView(text(SupportCopy.recoveryDetail(l), 17f, Design.secondary))
        space(body, 24)
        body.addView(text(SupportCopy.task(preferences.activeTask, l), 23f, Design.ink, true))
        space(body, 10)
        body.addView(text("${SupportCopy.help(l)} · ${SupportCopy.mode(preferences.helpMode, l)}", 16f, Design.forest, true))
        space(body, 26)
        body.addView(action(if (serviceEnabled()) SupportCopy.continueTask(l) else SupportCopy.setupContinue(l),
            Design.forest, Design.surface, 56) {
            if (!serviceEnabled() || !GuideAccessibilityService.connected) {
                preferences.setupTask = preferences.activeTask
                preferences.setupHelp = preferences.helpMode
                showSetup()
            } else {
                preferences.resume()
                try { openTask(preferences.activeTask) } catch (_: Exception) {
                    preferences.pause(PauseReason.SYSTEM_INTERRUPT)
                    showRecovery()
                }
            }
        })
        space(body, 10)
        body.addView(action(SupportCopy.restart(l), Design.paleGreen, Design.forest, 52) {
            selectedTask = preferences.activeTask
            selectedHelp = preferences.helpMode
            preferences.finish(PracticeOutcome.ENDED)
            taskSuggestedByAi = false
            showTask()
        })
        space(body, 10)
        body.addView(action(ExperienceCopy.unconfirmed(l), Design.background, Design.secondary, 52) {
            preferences.finish(PracticeOutcome.ENDED)
            showHome()
        })
    }

    private fun taskName(task: GuideTask) = when (task) {
        GuideTask.FONT_SIZE -> "把字调大"
        GuideTask.SCREEN_BRIGHTNESS -> "调高屏幕亮度"
        GuideTask.CHROME_BOOKMARK -> "收藏长者服务网页"
        GuideTask.MAPS_LIBRARY_ADDRESS -> "查看中央图书馆地址"
    }

    private fun showPractice() {
        screen = Screen.PRACTICE
        val body = page("练习记录")
        body.addView(text("你的每一次尝试", 25f, Design.ink, true))
        space(body, 12)
        body.addView(text("再做一次时，按需要选择帮助。记录只留在这台设备，最多 20 次。", 16f, Design.secondary))
        space(body, 22)
        val records = preferences.records()
        if (records.isEmpty()) {
            body.addView(text("还没有练习记录", 21f, Design.ink, true))
            space(body, 10)
            body.addView(text("从一项你需要的任务开始，完成后亲自确认。", 17f, Design.secondary))
            space(body, 20)
            body.addView(action("去选一个任务", Design.forest, Design.surface, 56) { showHome() })
            return
        }
        records.forEach { record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = Design.shape(Design.surface, dp(22).toFloat(), Design.line)
            }
            card.addView(text(taskName(record.task), 21f, Design.ink, true))
            space(card, 8)
            card.addView(text(if (record.outcome == PracticeOutcome.CONFIRMED)
                "你已确认完成" else "已结束 · 尚未确认完成", 16f, Design.forest, true))
            space(card, 7)
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(record.timestamp))
            card.addView(text("$time · ${record.mode.label}", 14f, Design.secondary))
            space(card, 12)
            card.addView(action("再试一次，自选帮助", Design.paleGreen, Design.forest, 52) {
                selectedTask = record.task
                selectedHelp = HelpMode.FULL
                taskSuggestedByAi = false
                showTask()
            })
            body.addView(card)
            space(body, 12)
        }
        space(body, 10)
        body.addView(text("完成一次不代表已经学会。记录不含页面、输入内容或录音。", 14f, Design.secondary))
        space(body, 12)
        body.addView(action("清除所有练习记录", Design.background, Design.secondary, 52) {
            AlertDialog.Builder(this).setTitle("清除这台设备的练习记录？")
                .setMessage("任务和语言设置会保留，练习记录将全部删除。")
                .setNegativeButton("保留", null)
                .setPositiveButton("清除记录") { _, _ -> preferences.clearRecords(); showPractice() }.show()
        })
    }

    @Deprecated("Activity back callback used for this small native-view prototype")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else if (screen == Screen.SETUP) leaveSetup() else showHome()
    }

    private fun beginListening() {
        if (cloudAsr.isRecording()) {
            cloudAsr.stop()
            speechButton.text = "说话"
            setInputStatus(speechMessage("正在识别…", "识别紧…", "Recognizing…"))
            return
        }
        if (if (cloudAsr.enabled) !preferences.cloudAsrConsent else !preferences.speechConsent) {
            AlertDialog.Builder(this)
                .setTitle("启用语音输入？")
                .setMessage(if (cloudAsr.enabled)
                    "仅在你点按说话后录音；点结束后，录音会经本机开发代理发送到火山引擎识别。应用和代理不会保存音频。识别文字会先显示给你确认。也可以使用文字或任务卡。"
                    else "点按说话后，音频会交给设备提供的语音识别服务处理；该服务可能联网，具体保留方式取决于设备设置。你也可以一直使用文字或任务卡。")
                .setNegativeButton("不用语音", null)
                .setPositiveButton("继续") { _, _ ->
                    if (cloudAsr.enabled) preferences.cloudAsrConsent = true
                    else preferences.speechConsent = true
                    beginListening()
                }
                .show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (cloudAsr.enabled) {
            val started = cloudAsr.start(preferences.inputLanguage,
                onCaptured = {
                    if (::speechButton.isInitialized) speechButton.text = "说话"
                    setInputStatus(speechMessage("正在识别…", "识别紧…", "Recognizing…"))
                },
                onResult = { transcript ->
                    if (!transcript.isNullOrBlank()) {
                        input.setText(transcript)
                        setInputStatus(speechMessage("识别为：$transcript。确认后点箭头。",
                            "识别到：$transcript。确认之后㩒箭嘴。",
                            "Heard: $transcript. Check it, then tap the arrow."))
                    } else setInputStatus(speechMessage("没有听清，请改用文字或任务卡。",
                        "听唔清楚，请用文字或者任务卡。", "Could not recognize speech. Try text or a task card."))
                })
            if (started) {
                speechButton.text = "结束"
                setInputStatus(speechMessage("正在录音，完成后点“结束”。最多 8 秒。",
                    "录音紧，讲完㩒「结束」。最多 8 秒。", "Recording. Tap Stop when done, up to 8 seconds."))
            } else setInputStatus(speechMessage("麦克风暂时不可用，请使用文字或任务卡。",
                "麦克风暂时用唔到，请用文字或者任务卡。", "Microphone unavailable. Try text or a task card."))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此设备没有可用语音识别，请使用文字或任务卡", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setInputStatus("正在聆听…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { setInputStatus("没有听清，请改用文字或任务卡。（错误 $error）") }
                override fun onResults(results: Bundle?) {
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (transcript != null) {
                        input.setText(transcript)
                        setInputStatus("识别为：$transcript。确认后点箭头。")
                    } else setInputStatus("没有听清，请改用文字或任务卡。")
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

    private fun speechMessage(mandarin: String, cantonese: String, english: String) = when (preferences.textLanguage) {
        GuideLanguage.MANDARIN -> mandarin
        GuideLanguage.CANTONESE -> cantonese
        GuideLanguage.ENGLISH -> english
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        cloudAsr.close()
        intentClient.close()
        super.onDestroy()
    }
}
