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
    private var inputGeneration = 0
    private var speechBusy = false

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

    private fun c(zh: String, yue: String, en: String) = ExperienceCopy.choose(preferences.textLanguage, zh, yue, en)

    private fun stopInput() {
        val interrupted = speechBusy || cloudAsr.isRecording()
        speechBusy = false
        inputGeneration++
        cloudAsr.cancel()
        recognizer?.cancel()
        if (interrupted && screen == Screen.HOME && ::speechStatus.isInitialized)
            setInputStatus(c("语音已停止。可以重新说话或输入文字。", "语音停咗。可以再讲或者输入文字。", "Speech stopped. You can speak again or type."))
    }

    private fun taskIcon(task: GuideTask) = when(task) {
        GuideTask.FONT_SIZE -> R.drawable.ic_text_increase
        GuideTask.SCREEN_BRIGHTNESS -> R.drawable.ic_brightness_6
        GuideTask.CHROME_BOOKMARK -> R.drawable.ic_bookmark_border
        GuideTask.MAPS_LIBRARY_ADDRESS -> R.drawable.ic_place
    }

    private fun showHome() {
        stopInput()
        screen = Screen.HOME
        val l = preferences.textLanguage
        val root = screenRoot()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), 0)
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(HandrailMark(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        brand.addView(text(c("数字扶手", "数字扶手", "Digital Handrail"), 17f, Design.ink, true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        navigation.addView(action(c("记录", "记录", "History"), Design.background, Design.forest, 48) { showPractice() })
        navigation.addView(action(c("语言", "语言", "Language"), Design.paleGreen, Design.forest, 48) { showLanguages() })
        if (resources.configuration.fontScale > 1.4f) {
            header.addView(brand)
            space(header, 12)
            header.addView(navigation)
        } else {
            brand.addView(navigation)
            header.addView(brand)
        }
        root.addView(header)
        if (preferences.active) {
            space(body, 18)
            body.addView(action(SupportCopy.recoveryTitle(l), Design.paleGreen, Design.forest, 52) { showRecovery() })
        }
        space(body, 28)
        body.addView(text(c("今天想做什么？", "今日想做啲乜？", "What would you like to do?"), 27f, Design.ink, true).heading())
        space(body, 10)
        body.addView(text(c("选一个任务，我们陪你走下一步。", "拣一个任务，我哋陪你行下一步。", "Choose a task. We’ll guide you one step at a time."), 16f, Design.secondary))
        space(body, 24)
        val profile = TaskAvailability.profile(this)
        val count = GuideTask.entries.count { TaskCatalog.block(it, profile) == null }
        val section = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        section.addView(text(c("手机上的日常事", "手机上嘅日常事", "Everyday tasks"), 18f, Design.ink, true).heading(), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(text(if (TaskCatalog.reviewedDevice(profile)) c("$count 项可用", "$count 项可用", "$count ready")
            else c("$count 项可试", "$count 项可以试", "$count to try"), 14f, Design.secondary))
        body.addView(section)
        space(body, 14)
        val tasks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.shape(Design.surface, dp(24).toFloat(), Design.line)
        }
        GuideTask.entries.forEachIndexed { index, task ->
            val block = TaskCatalog.block(task, profile)
            val title = AppCopy.shortTask(task, l)
            val subtitle = if (block == null && TaskCatalog.experimental(task, profile))
                c("手机实验引导 · 可以尝试", "手机实验指引 · 可以试", "Phone trial · Try this guide")
                else if (block == null) "${AppCopy.app(task, l)} · ${AppCopy.time(l)}"
                else c("暂不可用 · 查看原因", "暂时用唔到 · 睇原因", "Unavailable · See why")
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(96)
                setPadding(dp(16), dp(18), dp(12), dp(18))
                background = Design.ripple(Design.surface, dp(24).toFloat())
                markAsButton()
                contentDescription = "$title. $subtitle"
                setOnClickListener { selectedTask = task; taskSuggestedByAi = false; showTask() }
            }
            row.addView(icon(taskIcon(task), 26).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = Design.shape(Design.paleGreen, dp(16).toFloat())
            }, LinearLayout.LayoutParams(dp(50), dp(50)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            copy.addView(text(title, 19f, Design.ink, true))
            space(copy, 5)
            copy.addView(text(subtitle, 14f, Design.secondary))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(icon(R.drawable.ic_chevron_right), LinearLayout.LayoutParams(dp(24), dp(32)))
            tasks.addView(row)
            if (index < GuideTask.entries.lastIndex) divider(tasks)
        }
        body.addView(tasks)
        space(body, 18)
        serviceRowView = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            background = Design.ripple(Design.background, dp(12).toFloat())
            markAsButton()
            setOnClickListener { showSetup() }
        }
        serviceDot = text("●", 14f, Design.forest, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        serviceRowView.addView(serviceDot, LinearLayout.LayoutParams(dp(25), -2))
        status = text("", 15f, Design.secondary, true)
        serviceRowView.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        serviceRowView.addView(icon(R.drawable.ic_chevron_right))
        body.addView(serviceRowView)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundColor(Design.background)
        }
        composer.addView(text(c("也可以说说你想做什么", "亦可以讲下你想做啲乜", "Or tell us what you need"), 15f, Design.ink, true))
        space(composer, 10)
        input = EditText(this).apply {
            hint = c("例如：把字调大", "例如：将啲字调大", "e.g. Make the text larger")
            textSize = 17f
            setTextColor(Design.ink)
            setHintTextColor(Design.secondary)
            background = Design.shape(Design.surface, dp(18).toFloat(), Design.line, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(56)
            maxLines = 2
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
            contentDescription = c("输入想完成的手机任务", "输入想做嘅手机任务", "Enter the phone task you want to do")
            setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_GO) { submitGoal(); true } else false }
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        space(composer, 8)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        speechButton = action(c("说话", "讲嘢", "Speak"), Design.paleGreen, Design.forest, 52) { beginListening() }
        actions.addView(speechButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(action(c("查找引导", "搵指引", "Find a guide"), Design.forest, Design.surface, 52) { submitGoal() },
            LinearLayout.LayoutParams(0, -2, 1.5f))
        composer.addView(actions)
        space(composer, 8)
        composer.addView(text(c("只发送你提交的目标，不发送屏幕。", "只发送你提交嘅目标，唔发送屏幕。", "Only your submitted goal is sent. Your screen stays private."), 13f, Design.secondary))
        speechStatus = text("", 16f, Design.ink, true).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Design.shape(Design.paleGreen, dp(14).toFloat())
        }
        composer.addView(speechStatus)
        root.addView(composer)
        applyInsets(root, includeKeyboard = true)
        setContentView(root)
        updateServiceStatus()
    }

    private fun screenRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Design.background)
        window.statusBarColor = Design.background
        window.navigationBarColor = Design.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun applyInsets(root: View, includeKeyboard: Boolean = false) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars() or
                (if (includeKeyboard) WindowInsets.Type.ime() else 0)).bottom else insets.systemWindowInsetBottom
            root.setPadding(0, top, 0, bottom)
            insets
        }
    }

    private fun updateServiceStatus() {
        val enabled = serviceEnabled()
        status.text = if (enabled && GuideAccessibilityService.connected) SupportCopy.ready(preferences.textLanguage)
            else if (enabled) c("引导服务正在连接", "指引服务连接紧", "Guide service is connecting")
            else SupportCopy.setupTitle(preferences.textLanguage)
        serviceRowView.visibility = if (enabled && GuideAccessibilityService.connected) View.GONE else View.VISIBLE
        serviceDot.setTextColor(if (enabled) Design.forest else Design.orange)
        serviceRowView.contentDescription = status.text
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
        val request = ++inputGeneration
        cloudAsr.cancel()
        speechButton.text = c("说话", "讲嘢", "Speak")
        val goal = input.text.toString().trim()
        if (goal.isEmpty()) {
            setInputStatus(c("先输入想完成的事，或选择上方任务。", "先输入想做嘅事，或者拣上面嘅任务。", "Enter a goal, or choose a task above."))
            return
        }
        if (goal.length > 160) {
            setInputStatus(c("目标描述太长，请用一句话说明。", "描述太长，请用一句话讲清楚。", "The description is too long. Try one sentence."))
            return
        }
        if (SensitiveGoalGuard.shouldKeepLocal(goal)) {
            setInputStatus(c("这段文字可能涉及私人内容，已停止发送。请使用上方任务卡。", "呢段文字可能有私人内容，已经停止发送。请用上面嘅任务卡。", "This may contain private information and was not sent. Please use a task card above."))
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        setInputStatus(GuideContent.intentSearching(preferences.textLanguage))
        intentClient.classify(goal, preferences.inputLanguage) { result ->
            if (request != inputGeneration || screen != Screen.HOME || input.text.toString().trim() != goal) return@classify
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
        if (screen != Screen.HOME) return
        speechStatus.visibility = View.VISIBLE
        speechStatus.text = message
    }

    private fun action(label: String, fill: Int, color: Int, height: Int = 58, click: () -> Unit) =
        text(label, 17f, color, true, Gravity.CENTER).apply {
            minHeight = dp(height)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
        parent: LinearLayout, resource: Int, label: String,
        current: () -> GuideLanguage, onSelect: (GuideLanguage) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(86)
            setPadding(0, dp(14), 0, dp(14))
            markAsButton()
            contentDescription = "$label. ${current().label}"
            background = Design.ripple(Design.surface, dp(12).toFloat())
        }
        row.addView(icon(resource), LinearLayout.LayoutParams(dp(32), dp(40)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        copy.addView(text(label, 16f, Design.ink, true))
        space(copy, 6)
        val value = text(current().label, 15f, Design.forest)
        copy.addView(value)
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(icon(R.drawable.ic_expand_more))
        row.setOnClickListener {
            PopupMenu(this, value).apply {
                GuideLanguage.entries.forEachIndexed { index, language -> menu.add(0, index, index, language.label) }
                setOnMenuItemClickListener { item -> onSelect(GuideLanguage.entries[item.itemId]); showLanguages(); true }
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
        stopInput()
        screen = Screen.TASK
        val l = preferences.textLanguage
        val root = screenRoot()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), dp(24))
        }
        root.addView(topBar(c("准备开始", "准备开始", "Before you start")) { showHome() }.apply {
            setPadding(dp(22), dp(13), dp(22), 0)
        })
        space(body, 24)
        body.addView(icon(taskIcon(selectedTask), 28).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = Design.shape(Design.paleGreen, dp(18).toFloat())
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        space(body, 16)
        body.addView(text(SupportCopy.task(selectedTask, l), 28f, Design.ink, true).heading())
        space(body, 10)
        body.addView(text("${AppCopy.app(selectedTask, l)} · ${AppCopy.time(l)}", 14f, Design.forest, true))
        space(body, 14)
        body.addView(text(AppCopy.intro(selectedTask, l), 17f, Design.secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        if (taskSuggestedByAi) {
            space(body, 12)
            body.addView(text(GuideContent.intentMatched(l), 15f, Design.forest))
        }
        val block = TaskAvailability.block(this, selectedTask)
        val phoneTrial = block == null && TaskAvailability.experimental(this, selectedTask)
        if (block != null) {
            space(body, 18)
            body.addView(text(TaskAvailability.reason(block, l), 17f, Design.ink, true).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = Design.shape(Design.paleOrange, dp(18).toFloat())
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }
        if (phoneTrial) {
            space(body, 18)
            body.addView(text(PhoneGuideCopy.notice(l), 16f, Design.ink).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = Design.shape(Design.paleOrange, dp(18).toFloat())
            })
        }
        space(body, 24)
        body.addView(text(c("这次怎么帮你", "今次点样帮你", "Choose your help"), 18f, Design.ink, true).heading())
        space(body, 10)
        if (preferences.records().any { it.task == selectedTask && it.outcome == PracticeOutcome.CONFIRMED }) {
            body.addView(text(c("你完成过这项任务，也可以试试少一点帮助。", "你做过呢项任务，可以试下少啲帮助。", "You’ve completed this before. You can try less help."), 15f, Design.secondary))
            space(body, 10)
        }
        body.addView(action(SupportCopy.mode(selectedHelp, l), Design.paleGreen, Design.forest, 54) {
            AlertDialog.Builder(this).setTitle(SupportCopy.helpTitle(l))
                .setSingleChoiceItems(HelpMode.entries.map { "${SupportCopy.mode(it, l)}: ${SupportCopy.modeDescription(it, l)}" }.toTypedArray(),
                    selectedHelp.ordinal) { dialog, which -> selectedHelp = HelpMode.entries[which]; dialog.dismiss(); showTask() }
                .setNegativeButton(SupportCopy.keepChoice(l), null).show()
        }.apply { contentDescription = "${SupportCopy.help(l)}. ${SupportCopy.mode(selectedHelp, l)}" })
        space(body, 8)
        body.addView(text(SupportCopy.modeDescription(selectedHelp, l), 15f, Design.secondary))
        space(body, 24)
        body.addView(text(c("接下来你会", "跟住你会", "What you’ll do"), 18f, Design.ink, true).heading())
        space(body, 12)
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Design.shape(Design.surface, dp(20).toFloat(), Design.line)
        }
        (if (phoneTrial) PhoneGuideCopy.steps(selectedTask, l) else AppCopy.steps(selectedTask, l)).forEachIndexed { index, copy ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); setPadding(0, dp(12), 0, dp(12)) }
            row.addView(text((index + 1).toString(), 14f, Design.forest, true, Gravity.CENTER).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = Design.shape(Design.paleGreen, dp(12).toFloat())
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(text(copy, 16f, Design.ink).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            steps.addView(row)
        }
        body.addView(steps)
        space(body, 16)
        val details = text(if (phoneTrial) PhoneGuideCopy.scope(l) else AppCopy.scope(selectedTask, l), 14f, Design.secondary).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(10), dp(4), dp(12))
        }
        val detailsLabel = c("适用说明", "适用说明", "Guide details")
        val expand = action(detailsLabel, Design.background, Design.forest, 48) {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        expand.contentDescription = "$detailsLabel. " + c("已收起", "已收起", "Collapsed")
        expand.setOnClickListener {
            val open = details.visibility == View.GONE
            details.visibility = if (open) View.VISIBLE else View.GONE
            expand.contentDescription = "$detailsLabel. " + if (open) c("已展开", "已展开", "Expanded") else c("已收起", "已收起", "Collapsed")
            expand.announceForAccessibility(expand.contentDescription)
        }
        body.addView(expand)
        body.addView(details)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(13))
        }
        footer.addView(text(AppCopy.effect(selectedTask, l), 13f, Design.secondary, gravity = Gravity.CENTER))
        space(footer, 10)
        footer.addView(action(if (block == null) AppCopy.start(selectedTask, l) else c("返回选择任务", "返去拣任务", "Choose another task"),
            Design.forest, Design.surface, 58) { if (block == null) startGuide() else showHome() })
        root.addView(footer)
        applyInsets(root)
        setContentView(root)
    }

    private fun showLanguages() {
        stopInput()
        screen = Screen.LANGUAGES
        val body = page(c("语言设置", "语言设置", "Language settings"))
        body.addView(text(c("按你的习惯来", "按你嘅习惯嚟", "Make it feel familiar"), 27f, Design.ink, true).heading())
        space(body, 10)
        body.addView(text(c("三种语言，三项独立选择。", "三种语言，三项独立选择。", "Three languages. Three independent choices."), 17f, Design.secondary))
        space(body, 24)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            background = Design.shape(Design.surface, dp(22).toFloat(), Design.line)
        }
        languageRow(card, R.drawable.ic_mic, c("你说 / 输入", "你讲 / 输入", "Your speech and input"), { preferences.inputLanguage }) { preferences.inputLanguage = it }
        divider(card)
        languageRow(card, R.drawable.ic_text_increase, c("界面文字 / AI 提示", "界面文字 / AI 提示", "App text and AI replies"), { preferences.textLanguage }) { preferences.textLanguage = it }
        divider(card)
        languageRow(card, R.drawable.ic_volume_up, c("语音播报", "语音播报", "Spoken guidance"), { preferences.voiceLanguage }) { preferences.voiceLanguage = it }
        body.addView(card)
        space(body, 16)
        body.addView(text(c("换文字语言不会改变你说话或听播报的语言。", "换文字语言唔会改变你讲嘢或者听播报嘅语言。", "Changing app text does not change speech input or the voice you hear."), 15f, Design.secondary))
        space(body, 24)
        body.addView(action(SupportCopy.setupTitle(preferences.textLanguage), Design.paleGreen, Design.forest, 54) { showSetup() })
        space(body, 24)
        body.addView(text(c("语音与隐私", "语音同私隐", "Speech and privacy"), 18f, Design.ink, true).heading())
        space(body, 10)
        val privacy = if (cloudAsr.enabled)
            c("语音输入只在你点按后录制。当前模拟器的录音会送到火山引擎识别，应用和本机代理不保存音频。播报可把固定提示交给云端。你始终可以只用文字。", "语音输入只喺你㩒掣后录音。而家模拟器嘅录音会交俾火山引擎识别，应用同本机代理唔储存音频。播报可将固定提示交俾云端。你一直可以只用文字。", "Recording starts only after you tap Speak. This emulator sends audio to Volcano Engine; the app and local proxy do not save it. Fixed guidance may go to cloud speech. Text is always available.")
        else c("语音输入只在你点按后录制，由设备语音识别服务处理；它可能联网，保留方式取决于该服务。播报使用本机语音服务，支持的语言取决于已安装的语音。当前手机不连接火山开发代理，文字和任务卡始终可用。", "语音输入只喺你㩒掣后录音，由设备语音识别服务处理；佢可能联网，保留方式视乎服务。播报用本机语音服务，支持嘅语言视乎已安装嘅语音。而家手机唔连接火山开发代理，文字同任务卡一直都用得。", "Recording starts only after you tap Speak. The device recognition service may use the internet; its retention rules apply. Speech output uses installed device voices. This phone does not connect to the Volcano development proxy. Text and task cards remain available.")
        body.addView(text(privacy, 15f, Design.secondary))
    }

    private fun topBar(title: String, onBack: () -> Unit): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(R.drawable.ic_arrow_back).apply {
            contentDescription = c("返回", "返回", "Back")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            background = Design.ripple(Design.background, dp(16).toFloat())
            markAsButton()
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(title, 17f, Design.ink, true).apply { setPadding(dp(7), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun page(title: String, onBack: () -> Unit = ::showHome): LinearLayout {
        stopInput()
        val root = screenRoot()
        root.addView(topBar(title, onBack).apply { setPadding(dp(22), dp(13), dp(22), 0) })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(31), dp(22), dp(28))
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        applyInsets(root)
        setContentView(root)
        return body
    }

    private fun startGuide() {
        TaskAvailability.block(this, selectedTask)?.let { showTask(); return }
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
                    AlertDialog.Builder(this).setTitle(c("当前使用自动亮度", "而家用紧自动光度", "Adaptive brightness is on"))
                        .setMessage(c("这项引导只验证了手动亮度。请先在系统设置关闭自动亮度，再开始。", "呢项指引只验证咗手动光度。请先喺系统设置关闭自动光度，再开始。", "This guide covers manual brightness. Turn off adaptive brightness in Settings, then try again."))
                        .setPositiveButton(c("知道了", "知道喇", "Got it"), null).show()
                    return
                }
                preferences.initialBrightness = Settings.System.getInt(contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS, -1)
                if (preferences.initialBrightness < 0) error("Brightness unavailable")
                if (!TaskAvailability.experimental(this, selectedTask) && preferences.initialBrightness >= 250) {
                    AlertDialog.Builder(this).setTitle(c("屏幕亮度已接近最高", "光度已接近最高", "Brightness is near maximum"))
                        .setMessage(c("现在无法再明显调高亮度。请先在系统设置中调低亮度，之后再开始这项引导。", "而家冇办法再明显调光。请先喺系统设置调低光度，再开始呢项指引。", "There is little room to increase brightness. Lower it in Settings first, then start this guide."))
                        .setPositiveButton(c("知道了", "知道喇", "Got it"), null).show()
                    return
                }
            } else if (selectedTask == GuideTask.FONT_SIZE) {
                preferences.initialFontScale = Settings.System.getFloat(contentResolver, "font_scale", resources.configuration.fontScale)
                if (!TaskAvailability.experimental(this, selectedTask) && preferences.initialFontScale >= 1.99f) {
                    AlertDialog.Builder(this).setTitle(c("字体已经是最大一档", "字体已经最大喇", "Text is already at its largest"))
                        .setMessage(c("现在无法再把字调大。你可以去设置中按阅读习惯自行调整。", "而家冇办法再调大啲字。你可以去设置按自己习惯调整。", "The text cannot get larger. You can adjust it yourself in Settings."))
                        .setNegativeButton(c("返回", "返回", "Back"), null)
                        .setPositiveButton(c("去自己调整", "去自己调", "Adjust in Settings")) { _, _ -> startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
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
            Toast.makeText(this, c("无法打开目标应用，请稍后重试。", "打开唔到目标应用，请迟啲再试。", "Could not open the app. Please try again later."), Toast.LENGTH_LONG).show()
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
            } else if (TaskAvailability.block(this, preferences.activeTask) != null) {
                selectedTask = preferences.activeTask
                showTask()
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

    private fun showPractice() {
        screen = Screen.PRACTICE
        val l = preferences.textLanguage
        val body = page(c("练习记录", "练习记录", "Practice history"))
        body.addView(text(c("你的每一次尝试", "你嘅每一次尝试", "Every attempt counts"), 27f, Design.ink, true).heading())
        space(body, 12)
        body.addView(text(c("再做一次时，按需要选择帮助。记录只留在这台设备，最多 20 次。", "再做一次时，按需要拣帮助。记录只储喺呢台设备，最多 20 次。", "Choose the help you need each time. Up to 20 attempts stay on this device."), 16f, Design.secondary))
        space(body, 22)
        val records = preferences.records()
        if (records.isEmpty()) {
            body.addView(text(c("从一项小事开始", "由一项小事开始", "Start with one small task"), 21f, Design.ink, true).heading())
            space(body, 10)
            body.addView(text(c("完成后亲自确认，尝试会记录在这里。", "做完自己确认，尝试会记录喺呢度。", "Confirm after you finish. Your attempt will appear here."), 17f, Design.secondary))
            space(body, 20)
            body.addView(action(c("去选一个任务", "去拣一个任务", "Choose a task"), Design.forest, Design.surface, 56) { showHome() })
            return
        }
        records.forEach { record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = Design.shape(Design.surface, dp(22).toFloat(), Design.line)
            }
            val title = SupportCopy.task(record.task, l)
            card.addView(text(title, 20f, Design.ink, true).heading())
            space(card, 8)
            card.addView(text(if (record.outcome == PracticeOutcome.CONFIRMED)
                c("你已确认完成", "你已确认完成", "You confirmed completion") else
                c("已结束 · 尚未确认完成", "已结束 · 未确认完成", "Ended · Not confirmed"), 16f, Design.forest, true))
            space(card, 7)
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", l.locale).format(java.util.Date(record.timestamp))
            card.addView(text("$time · ${SupportCopy.mode(record.mode, l)}", 14f, Design.secondary))
            space(card, 12)
            card.addView(action(c("再试一次，自选帮助", "再试一次，自己拣帮助", "Try again with your choice of help"), Design.paleGreen, Design.forest, 52) {
                selectedTask = record.task
                selectedHelp = HelpMode.FULL
                taskSuggestedByAi = false
                showTask()
            }.apply { contentDescription = c("再试一次：$title", "再试一次：$title", "Try again: $title") })
            body.addView(card)
            space(body, 12)
        }
        space(body, 10)
        body.addView(text(c("完成一次不代表已经学会。记录不含页面、输入内容或录音。", "做过一次唔代表已经学识。记录冇页面、输入内容或者录音。", "Completing once does not mean you have learned it. Records contain no pages, input or audio."), 14f, Design.secondary))
        space(body, 12)
        body.addView(action(c("清除练习记录", "清除练习记录", "Clear practice history"), Design.background, Design.secondary, 52) {
            AlertDialog.Builder(this).setTitle(c("清除这台设备的练习记录？", "清除呢台设备嘅练习记录？", "Clear this device’s practice history?"))
                .setMessage(c("任务和语言设置会保留，练习记录将全部删除。", "任务同语言设置会保留，练习记录会全部删除。", "All practice records will be deleted. Your task and language choices will stay."))
                .setNegativeButton(c("保留", "保留", "Keep history"), null)
                .setPositiveButton(c("清除记录", "清除记录", "Clear history")) { _, _ -> preferences.clearRecords(); showPractice() }.show()
        })
    }

    @Deprecated("Activity back callback used for this small native-view prototype")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else if (screen == Screen.SETUP) leaveSetup() else showHome()
    }

    private fun beginListening() {
        if (cloudAsr.isRecording()) {
            cloudAsr.stop()
            speechButton.text = c("说话", "讲嘢", "Speak")
            setInputStatus(speechMessage("正在识别…", "识别紧…", "Recognizing…"))
            return
        }
        if (if (cloudAsr.enabled) !preferences.cloudAsrConsent else !preferences.speechConsent) {
            AlertDialog.Builder(this)
                .setTitle(c("启用语音输入？", "用语音输入？", "Use speech input?"))
                .setMessage(if (cloudAsr.enabled)
                    c("点“说话”才录音；点“停止”后，录音经本机开发代理发送到火山引擎识别。应用和代理不保存音频。文字先显示给你核对，也可以直接输入或选任务卡。", "㩒「讲嘢」先录音；㩒「停止」后，录音经本机开发代理交俾火山引擎识别。应用同代理唔储存音频。文字会先俾你核对，你亦可以直接输入或者拣任务卡。", "Recording starts only when you tap Speak. After you tap Stop, audio is sent through the local development proxy to Volcano Engine. The app and proxy do not save audio. Check the transcript before submitting, or use text and task cards.")
                    else c("点按后，音频交给设备语音识别服务；它可能联网，保留方式取决于该服务。文字和任务卡始终可用。", "㩒掣后，音频交俾设备语音识别服务；佢可能联网，保留方式视乎服务。文字同任务卡一直都用得。", "After you tap Speak, audio goes to the device recognition service. It may use the internet; its retention rules apply. Text and task cards are always available."))
                .setNegativeButton(c("不用语音", "唔用语音", "Use text instead"), null)
                .setPositiveButton(c("继续", "继续", "Continue")) { _, _ ->
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
        val request = ++inputGeneration
        if (cloudAsr.enabled) {
            val started = cloudAsr.start(preferences.inputLanguage,
                onCaptured = {
                    if (request != inputGeneration || screen != Screen.HOME) return@start
                    if (::speechButton.isInitialized) speechButton.text = c("说话", "讲嘢", "Speak")
                    setInputStatus(speechMessage("正在识别…", "识别紧…", "Recognizing…"))
                },
                onResult = { transcript ->
                    if (request != inputGeneration || screen != Screen.HOME) return@start
                    speechBusy = false
                    if (!transcript.isNullOrBlank()) {
                        input.setText(transcript)
                        setInputStatus(speechMessage(c("识别为：$transcript。核对后点“查找引导”。", "识别到：$transcript。核对之后㩒「搵指引」。", "Heard: $transcript. Check it, then tap Find a guide."),
                            "识别到：$transcript。核对之后㩒「搵指引」。",
                            "Heard: $transcript. Check it, then tap Find a guide."))
                    } else setInputStatus(speechMessage(c("没有听清，请改用文字或任务卡。", "听唔清楚，请用文字或者任务卡。", "Could not hear you. Use text or a task card."),
                        "听唔清楚，请用文字或者任务卡。", "Could not recognize speech. Try text or a task card."))
                })
            if (started) {
                speechBusy = true
                speechButton.text = c("停止", "停止", "Stop")
                setInputStatus(speechMessage("正在录音，完成后点“停止”。最多 8 秒。",
                    "录音紧，讲完㩒「停止」。最多 8 秒。", "Recording. Tap Stop when done, up to 8 seconds."))
            } else setInputStatus(speechMessage("麦克风暂时不可用，请使用文字或任务卡。",
                "麦克风暂时用唔到，请用文字或者任务卡。", "Microphone unavailable. Try text or a task card."))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, c("语音识别暂不可用，请使用文字或任务卡。", "语音识别暂时用唔到，请用文字或者任务卡。", "Speech recognition is unavailable. Use text or a task card."), Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (request != inputGeneration) return; setInputStatus(c("正在聆听…", "听紧…", "Listening…")) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { if (request != inputGeneration) return; setInputStatus(c("没有听清，请改用文字或任务卡。", "听唔清楚，请用文字或者任务卡。", "Could not hear you. Use text or a task card.")) }
                override fun onResults(results: Bundle?) {
                    if (request != inputGeneration || screen != Screen.HOME) return
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (transcript != null) {
                        input.setText(transcript)
                        setInputStatus(c("识别为：$transcript。核对后点“查找引导”。", "识别到：$transcript。核对之后㩒「搵指引」。", "Heard: $transcript. Check it, then tap Find a guide."))
                    } else setInputStatus(c("没有听清，请改用文字或任务卡。", "听唔清楚，请用文字或者任务卡。", "Could not hear you. Use text or a task card."))
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
        if (requestCode == 10 && screen == Screen.HOME) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
            else setInputStatus(c("麦克风权限未开启，仍可输入文字或选择任务。", "麦克风权限未开，仍然可以输入文字或者拣任务。", "Microphone access is off. You can still type or choose a task."))
        }
    }

    override fun onPause() {
        if (::cloudAsr.isInitialized) stopInput()
        if (::speechButton.isInitialized) speechButton.text = c("说话", "讲嘢", "Speak")
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        cloudAsr.close()
        intentClient.close()
        super.onDestroy()
    }
}
