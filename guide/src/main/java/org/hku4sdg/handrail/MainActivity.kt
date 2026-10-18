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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Screen { HOME, TASK, LANGUAGES, PRACTICE, SETUP, RECOVERY, FIRST, CONFIRM, ADJUST, SETUP_HELP, DIAGNOSTICS, RECEIPT, SCREEN_GUIDE }
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
    private enum class GoalSource { SHORTCUT, MODEL, LOCAL }
    private var goalSource = GoalSource.SHORTCUT
    private var selectedHelp = HelpMode.FULL
    private var recognizer: SpeechRecognizer? = null
    private var inputGeneration = 0
    private var speechBusy = false
    private var screenGoal = ""
    private var screenPackage: String? = null
    private var screenAppName = ""
    private var screenImages = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GuidePreferences(this)
        intentClient = IntentClient(this)
        cloudAsr = CloudAsr(this)
        selectedTask = savedInstanceState?.getString("selectedTask")?.let(GuideTask::fromId)
            ?: GuideTask.FONT_SIZE
        goalSource = savedInstanceState?.getString("goalSource")?.let {
            runCatching { GoalSource.valueOf(it) }.getOrNull()
        } ?: GoalSource.SHORTCUT
        selectedHelp = savedInstanceState?.getString("help")?.let {
            runCatching { HelpMode.valueOf(it) }.getOrNull()
        } ?: preferences.helpMode
        Design.appScale = preferences.appTextScale
        Design.displayScript = preferences.displayScript ?: DisplayScript.HANS
        if (!preferences.initDone) {
            showFirstRun()
            return
        }
        if (intent.getBooleanExtra("screen_guide_setup", false)) {
            intent.removeExtra("screen_guide_setup")
            if (intent.getBooleanExtra("screen_guide_languages", false)) showLanguages() else showScreenGuideSetup()
            return
        }
        if (intent.getBooleanExtra("return_to_task", false)) {
            intent.removeExtra("return_to_task")
            selectedTask = preferences.activeTask
            goalSource = GoalSource.SHORTCUT
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
            Screen.FIRST -> showFirstRun()
            Screen.CONFIRM -> showConfirm()
            Screen.ADJUST -> showAdjust()
            Screen.SETUP_HELP -> showSetupHelp()
            Screen.DIAGNOSTICS -> showDiagnostics()
            Screen.RECEIPT -> showReceipt()
            Screen.SCREEN_GUIDE -> showScreenGuideSetup()
            else -> {
                showHome()
                savedInstanceState?.getString("goal")?.let(input::setText)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("goalSource", goalSource.name)
        outState.putString("selectedTask", selectedTask.id)
        outState.putString("help", selectedHelp.name)
        if (screen == Screen.HOME && ::input.isInitialized) outState.putString("goal", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::preferences.isInitialized) {
            Design.appScale = preferences.appTextScale
            Design.displayScript = preferences.displayScript ?: DisplayScript.HANS
        }
        if (::preferences.isInitialized && !preferences.initDone && screen == Screen.FIRST) {
            showFirstRun()
            return
        }
        if (ScreenGuideSession.current != null) {
            ScreenGuideSession.current?.paused = true
            GuideAccessibilityService.refreshScreenGuide()
        }
        if (::preferences.isInitialized && preferences.receiptPending) {
            preferences.receiptPending = false
            showReceipt()
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
        if (intent.getBooleanExtra("screen_guide_setup", false)) {
            intent.removeExtra("screen_guide_setup")
            if (intent.getBooleanExtra("screen_guide_languages", false)) showLanguages() else showScreenGuideSetup()
            return
        }
        if (intent.getBooleanExtra("return_to_task", false)) {
            intent.removeExtra("return_to_task")
            selectedTask = preferences.activeTask
            goalSource = GoalSource.SHORTCUT
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
            setPadding(0, dp(14), 0, 0)
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(HandrailMark(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        brand.addView(text(c("数字扶手", "数字扶手", "Digital Handrail"), 17f, Design.ink, true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        navigation.addView(action(c("记录", "记录", "History"), Design.surface, Design.ink, 56) { showPractice() })
        navigation.addView(action(c("设置", "设置", "Settings"), Design.surface, Design.ink, 64) { showAdjust() })
        if (resources.configuration.fontScale * Design.appScale > 1.5f || l == GuideLanguage.ENGLISH) {
            header.addView(brand)
            space(header, 12)
            header.addView(navigation)
        } else {
            brand.addView(navigation)
            header.addView(brand)
        }
        body.addView(header)
        space(body, 8)
        if (preferences.active) {
            space(body, 18)
            body.addView(action(SupportCopy.recoveryTitle(l), Design.paleGreen, Design.forest, 52) { showRecovery() })
        }
        space(body, 28)
        body.addView(text(c("你想在手机上做什么？", "你想喺手机做啲乜？", "What would you like to do?"), 28f, Design.ink, true).heading())
        space(body, 18)
        body.addView(text(c("说出来，或打字告诉我。", "讲出来，或者打字话我知。", "Tell us by voice or text."), 16f, Design.secondary))
        space(body, 14)
        val composerIndex = body.childCount
        val profile = TaskAvailability.profile(this)
        body.addView(text(c("也可以从这里开始", "亦可以喺呢度开始", "Or start with a task"), 18f, Design.ink, true).heading())
        space(body, 12)
        GuideTask.entries.forEach { task ->
            val block = TaskCatalog.block(task, profile)
            val title = taskTitle(task)
            val subtitle = if (block == null) taskDetail(task) else c("现在还带不了", "现在还带不了", "Not available yet")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(80)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = Design.ripple(Design.surface, dp(16).toFloat(), Design.line, dp(1))
                markAsButton()
                contentDescription = "$title. $subtitle"
                setOnClickListener { selectedTask = task; goalSource = GoalSource.SHORTCUT; showTask() }
            }
            card.addView(text(title, 20f, Design.ink, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            space(card, 8)
            card.addView(text(subtitle, 15f, Design.ink).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            body.addView(card)
            space(body, 12)
        }
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
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
            setBackgroundColor(Design.background)
        }
        input = EditText(this).apply {
            hint = c("例如：把字调大", "例如：把字调大", "e.g. Make the text larger")
            textSize = (19f * Design.appScale).coerceAtLeast(18f)
            setTextColor(Design.ink)
            setHintTextColor(Design.ink)
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
        speechButton = action(c("说话", "讲嘢", "Speak"), Design.forest, Design.surface, 64) { beginListening() }
        actions.addView(speechButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(action(c("查找引导", "搵指引", "Find a guide"), Design.surface, Design.ink, 64) { submitGoal() },
            LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(actions)
        space(composer, 8)
        composer.addView(text(c("查找任务只发送目标；屏幕引导会先另行说明。", "搵任务只发送目标；屏幕指引会先另外说明。", "Finding a task sends only your goal. Screen guidance asks separately."), 13f, Design.secondary))
        speechStatus = text("", 16f, Design.ink, true).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Design.shape(Design.paleGreen, dp(14).toFloat())
        }
        composer.addView(speechStatus)
        space(composer, 12)
        composer.addView(action(c("看屏幕，帮我下一步", "睇屏幕，帮我下一步", "Help with this app's screen"), Design.paleGreen, Design.forest, 54) {
            screenGoal = input.text.toString().trim()
            showScreenGuideSetup()
        })
        body.addView(composer, composerIndex)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, -1))
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
            setInputStatus(c("先输入想完成的事，或选择下方任务。", "先输入想做嘅事，或者拣下面嘅任务。", "Enter a goal, or choose a task below."))
            return
        }
        if (goal.length > 160) {
            setInputStatus(c("目标描述太长，请用一句话说明。", "描述太长，请用一句话讲清楚。", "The description is too long. Try one sentence."))
            return
        }
        if (SensitiveGoalGuard.shouldKeepLocal(goal)) {
            setInputStatus(c("这段文字可能涉及私人内容，已停止发送。请使用下方任务卡。", "呢段文字可能有私人内容，已经停止发送。请用下面嘅任务卡。", "This may contain private information and was not sent. Please use a task card below."))
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
                    goalSource = GoalSource.MODEL
                    showConfirm()
                }
                IntentClient.Decision.CLARIFY -> setInputStatus(GuideContent.intentClarify(preferences.textLanguage))
                IntentClient.Decision.UNSUPPORTED -> { screenGoal = goal; showScreenGuideSetup() }
                null -> {
                    val fallback = TaskMatcher.match(goal)
                    if (fallback != null) {
                        selectedTask = fallback
                        goalSource = GoalSource.LOCAL
                        showConfirm()
                    } else setInputStatus(GuideContent.intentUnavailable(preferences.textLanguage))
                }
            }
        }
    }

    private fun showScreenGuideSetup() {
        val restore = screen != Screen.SCREEN_GUIDE
        stopInput()
        screen = Screen.SCREEN_GUIDE
        ScreenGuideSession.current?.takeIf { restore }?.let { current ->
            current.paused = true
            screenGoal = current.goal; screenPackage = current.packageName
            screenAppName = current.appName; screenImages = current.includeImage
        }
        val l = preferences.textLanguage
        val body = page(ScreenGuideCopy.title(l), ::showHome)
        body.addView(text(c("打开你要用的 App，一步一步来", "打开你要用嘅 App，一步一步嚟", "Open your app. Take one step at a time."), 26f, Design.ink, true).heading())
        space(body, 14)
        body.addView(text(c("AI 会根据当前可见的按钮和文字给出建议。你自己点击或滑动；看不清目标时会停止圈画。", "AI 会按而家睇到嘅按钮同文字提供建议。你自己㩒或者扫；认唔清目标就停止圈画。", "AI suggests a next step from visible controls and text. You tap or swipe. Uncertain targets are not highlighted."), 17f))
        space(body, 20)
        val goal = EditText(this).apply {
            setText(screenGoal); hint = c("例如：找到这个 App 的主题设置", "例如：搵呢个 App 嘅主题设置", "For example: find this app's theme setting")
            textSize = 18f * Design.appScale; minHeight = dp(56)
            setTextColor(Design.ink); setHintTextColor(Design.secondary); setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Design.shape(Design.surface, dp(16).toFloat(), Design.line, dp(1)); maxLines = 3
            contentDescription = c("你希望在 App 里完成什么", "你想喺 App 入面做乜", "What do you want to do in the app?")
        }
        body.addView(goal)
        space(body, 12)
        val pick = action(if (screenPackage == null) c("选择一个 App", "拣一个 App", "Choose an app") else screenAppName,
            Design.surface, Design.ink, 56) {
            screenGoal = goal.text.toString().trim()
            val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
            AlertDialog.Builder(this).setTitle(c("你想使用哪个 App？", "你想用边个 App？", "Which app would you like to use?"))
                .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                    screenPackage = apps[index].activityInfo.packageName
                    screenAppName = apps[index].loadLabel(packageManager).toString(); showScreenGuideSetup()
                }.setNegativeButton(c("取消", "取消", "Cancel"), null).show()
        }
        body.addView(pick)
        space(body, 18)
        val consent = CheckBox(this).apply {
            text = c("本次引导期间，同意把所选 App 当前页面的可见控件文字交给云端 AI 分析。", "今次指引期间，同意将所拣 App 当前页面睇到嘅控件文字交俾云端 AI 分析。", "For this guide, allow visible control text from the selected app to be analyzed by cloud AI.")
            textSize = 16f * Design.appScale; setTextColor(Design.ink); minHeight = dp(56)
        }
        body.addView(consent)
        val images = CheckBox(this).apply {
            text = c("同时提供当前页面截图（可选）", "同时提供当前页面截图（可选）", "Also include a screenshot of the current page (optional)")
            textSize = 16f * Design.appScale; setTextColor(Design.ink); minHeight = dp(52)
            isChecked = screenImages; isEnabled = Build.VERSION.SDK_INT >= 34
            setOnCheckedChangeListener { _, checked -> screenImages = checked }
        }
        body.addView(images)
        space(body, 12)
        body.addView(text(c("请避开私信、账号和付款页面。检测到明显私人内容时会停止发送，但筛查不能保证识别所有资料。页面内容仅用于本次分析，不写入本机练习记录；云端处理遵循服务商政策。", "请避开私信、账号同付款页面。发现明显私人资料会停止发送，但筛查唔保证认到所有资料。页面内容只用于今次分析，唔写入本机练习记录；云端处理跟服务商政策。", "Avoid private messages, accounts and payment pages. Obvious sensitive content is blocked, but filtering is not comprehensive. Page content is not stored in local practice history. Provider policies apply to cloud processing."), 15f, Design.secondary))
        space(body, 20)
        val start = action(c("同意并打开 App", "同意并打开 App", "Agree and open app"), Design.forest, Design.surface, 58) {
            val words = goal.text.toString().trim(); val pkg = screenPackage
            if (!consent.isChecked || words.isBlank() || words.length > 160 || pkg == null) {
                Toast.makeText(this, c("请写一句目标、选择 App，并确认本次分析。", "请写一句目标、拣 App，同确认今次分析。", "Enter a short goal, choose an app and allow this analysis."), Toast.LENGTH_LONG).show()
            } else if (ScreenGuidePolicy.privateText(words) || ScreenGuidePolicy.risky(words)) {
                Toast.makeText(this, ScreenGuideCopy.privatePage(l), Toast.LENGTH_LONG).show()
            } else if (!ScreenGuideClient.available(this)) {
                Toast.makeText(this, c("当前云端连接仅在开发模拟器提供。", "目前云端连接只喺开发模拟器提供。", "The cloud bridge currently runs on the development emulator."), Toast.LENGTH_LONG).show()
            } else if (!serviceEnabled() || !GuideAccessibilityService.connected) {
                screenGoal = words
                AlertDialog.Builder(this).setMessage(c("请先开启数字扶手的无障碍引导服务，再返回这里开始。", "请先开启数字扶手嘅无障碍指引服务，再返嚟开始。", "Enable Digital Handrail's accessibility service, then return here to start."))
                    .setPositiveButton(c("去设置", "去设置", "Open settings")) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .setNegativeButton(c("取消", "取消", "Cancel"), null).show()
            } else {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch == null) Toast.makeText(this, c("这个 App 暂时打不开，请重新选择。", "呢个 App 暂时打开唔到，请再拣。", "This app cannot be opened. Choose another."), Toast.LENGTH_LONG).show()
                else {
                    if (preferences.active) { preferences.finish(PracticeOutcome.ENDED); preferences.receiptPending = false }
                    preferences.setupTask = null
                    screenGoal = words; preferences.collapsed = false
                    ScreenGuideSession.start(words, pkg, screenAppName, screenImages)
                    try { startActivity(launch); GuideAccessibilityService.refreshScreenGuide() }
                    catch (_: Exception) { ScreenGuideSession.stop(); Toast.makeText(this, c("打开失败，请重试。", "打开失败，请再试。", "Could not open the app. Try again."), Toast.LENGTH_LONG).show() }
                }
            }
        }
        body.addView(start)
        ScreenGuideSession.current?.let {
            space(body, 10)
            body.addView(action(c("结束本次屏幕引导", "结束今次屏幕指引", "End this screen guide"), Design.surface, Design.ink, 52) {
                ScreenGuideSession.stop(); GuideAccessibilityService.refreshScreenGuide(); showHome()
            })
        }
    }

    private fun setInputStatus(message: String) {
        if (screen != Screen.HOME) return
        speechStatus.visibility = View.VISIBLE
        speechStatus.text = message
    }

    private fun action(label: String, fill: Int, color: Int, height: Int = 64, click: () -> Unit) =
        text(label, 18f, if (fill == Design.forest) Design.surface else Design.ink, true, Gravity.CENTER).apply {
            minHeight = dp(height.coerceAtLeast(48))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = if (fill == Design.forest) Design.ripple(Design.forest, dp(16).toFloat())
                else Design.ripple(Design.surface, dp(16).toFloat(), Design.line, dp(1))
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
        addGoalSource(body)
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
        if (selectedTask == GuideTask.SCREEN_BRIGHTNESS && block == null) {
            space(body, 24)
            body.addView(text(c("你希望怎样调节？", "你想点样调光度？", "How should brightness work?"), 22f, Design.ink, true).heading())
            space(body, 12)
            BrightnessGoal.entries.forEach { goal ->
                val name = if (goal == BrightnessGoal.MANUAL) c("现在自己调亮一点", "而家自己调光啲", "Adjust it myself now")
                    else c("随环境自动调节", "跟环境自动调节", "Adjust automatically")
                body.addView(action(name, if (preferences.brightnessGoal == goal) Design.forest else Design.surface, Design.ink, 64) {
                    preferences.brightnessGoal = goal; showTask()
                }.apply {
                    isEnabled = goal != BrightnessGoal.ADAPTIVE || TaskAvailability.adaptiveBrightnessAvailable(this@MainActivity) != false
                    if (!isEnabled) alpha = 0.55f
                    isSelected = preferences.brightnessGoal == goal
                    contentDescription = name + if (isSelected) c("，已选择", "，拣咗", ", selected") else ""
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = android.widget.RadioButton::class.java.name
                            info.isCheckable = true; info.isChecked = preferences.brightnessGoal == goal
                        }
                    }
                })
                space(body, 10)
            }
            if (TaskAvailability.adaptiveBrightnessAvailable(this) == false) {
                body.addView(text(c("这台设备没有提供自动亮度功能，可选择手动调节。", "呢台设备冇自动光度功能，可以拣手动调节。", "This device does not provide adaptive brightness. You can adjust it manually."), 18f, Design.ink))
                space(body, 12)
            }
            body.addView(text(if (preferences.brightnessGoal == BrightnessGoal.MANUAL)
                c("如果自动亮度已开启，我们会先带你关闭，再调滑块。", "如果自动光度开咗，我哋会先带你关闭，再调滑桿。", "If adaptive brightness is on, we’ll guide you to turn it off first.")
                else c("我们会带你检查自动亮度开关，不改动其他显示设置。", "我哋会带你检查自动光度开关，唔改其他显示设置。", "We’ll guide you to check the adaptive brightness switch."), 17f, Design.secondary))
        }
        space(body, 24)
        body.addView(text(c("这次怎么帮你", "这次怎么帮你", "How much help this time?"), 22f, Design.ink, true).heading())
        space(body, 10)
        body.addView(text(SupportCopy.modeDescription(selectedHelp, l), 18f, Design.ink))
        space(body, 12)
        HelpMode.entries.forEach { mode ->
            val chosen = selectedHelp == mode
            body.addView(action(SupportCopy.mode(mode, l), if (chosen) Design.forest else Design.surface, Design.ink, 64) {
                selectedHelp = mode
                preferences.helpMode = mode
                showTask()
            })
            space(body, 10)
        }
        space(body, 24)
        body.addView(text(c("接下来你会", "跟住你会", "What you’ll do"), 18f, Design.ink, true).heading())
        space(body, 12)
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Design.shape(Design.surface, dp(20).toFloat(), Design.line)
        }
        (if (selectedTask == GuideTask.SCREEN_BRIGHTNESS) listOf(
            c("检查自动亮度开关", "检查自动光度开关", "Check adaptive brightness"),
            if (preferences.brightnessGoal == BrightnessGoal.MANUAL) c("调整滑块，确认是否舒服", "调滑桿，确认舒唔舒服", "Adjust the slider and check comfort")
            else c("开启自动调节，再确认", "开启自动调节，再确认", "Enable automatic adjustment and confirm"))
            else if (phoneTrial) PhoneGuideCopy.steps(selectedTask, l) else AppCopy.steps(selectedTask, l)).forEachIndexed { index, copy ->
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
        space(body, 24)
        body.addView(action(c("服务状态与诊断", "服务状态同诊断", "Service status and diagnostics"), Design.surface, Design.ink, 54) { showDiagnostics() })
        space(body, 10)
        body.addView(text(ServiceDiagnostics.buildLabel(), 14f, Design.secondary))
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
        if (selectedTask == GuideTask.SCREEN_BRIGHTNESS && preferences.brightnessGoal == BrightnessGoal.ADAPTIVE &&
            TaskAvailability.adaptiveBrightnessAvailable(this) == false) { showTask(); return }
        if (!serviceEnabled() || !GuideAccessibilityService.connected) {
            preferences.setupTask = selectedTask
            preferences.setupHelp = selectedHelp
            showSetup()
            return
        }
        try {
            if (selectedTask == GuideTask.SCREEN_BRIGHTNESS) {
                val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                preferences.initialBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                preferences.brightnessBaselineReady = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                if (preferences.initialBrightness < 0 || mode !in 0..1) error("Brightness unavailable")
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
            goalSource = GoalSource.SHORTCUT
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
            body.addView(action(c("无法开启？查看帮助", "开启唔到？睇下帮助", "Can’t enable it? Get help"), Design.surface, Design.ink, 54) { showSetupHelp() })
            space(body, 10)
            body.addView(action(if (preferences.setupTask != null) SupportCopy.backToTask(l) else SupportCopy.backHome(l),
                Design.background, Design.secondary, 52) { leaveSetup() })
        } else {
            space(body, 16)
            body.addView(action(c("首次开启帮助", "首次开启帮助", "First-time setup help"), Design.surface, Design.ink, 54) { showSetupHelp() })
        }
    }

    private fun showSetupHelp() {
        screen = Screen.SETUP_HELP
        val body = page(c("开启遇到困难", "开启遇到困难", "Help enabling the guide"), ::showSetup)
        body.addView(text(c("开关不可用或提示受限制？", "开关用唔到或者提示受限制？", "Is the switch restricted?"), 26f, Design.ink, true).heading())
        space(body, 14)
        body.addView(text(c("部分安装方式会触发 Android 的敏感权限保护。请先确认这个测试包来自你信任的团队，再决定是否授权。", "部分安装方式会触发 Android 敏感权限保护。请先确认测试包来自你信任嘅团队，再决定授权。", "Some installation methods trigger Android’s protection for sensitive permissions. Confirm that you trust the team supplying this build before allowing access."), 18f))
        space(body, 22)
        listOf(c("打开数字扶手的应用信息。", "打开数字扶手嘅应用资料。", "Open Digital Handrail’s app info."),
            c("查看右上角更多菜单；如果有“允许受限制的设置”或“解除限制”，按系统提示自行决定。不同手机名称可能不同。", "睇右上角更多菜单；如果有「允许受限制的设置」或者「解除限制」，按系统提示自己决定。手机名称可能唔同。", "Check the top-right menu for Allow restricted settings. Names vary by phone; read the system prompt and decide yourself."),
            c("返回无障碍页面，开启“数字扶手 · 手机引导”，然后回到这里检查。", "返无障碍页面，开启「数字扶手 · 手机引导」，再返嚟检查。", "Return to Accessibility, enable Digital Handrail, then come back to check.")).forEachIndexed { i, line ->
            body.addView(text("${i + 1}. $line", 18f, Design.ink, true)); space(body, 16)
        }
        body.addView(action(c("打开应用信息", "打开应用资料", "Open app info"), Design.forest, Design.surface, 56) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        })
        space(body, 12)
        body.addView(action(c("返回检查服务", "返去检查服务", "Check the service again"), Design.surface, Design.ink, 56) { showSetup() })
        space(body, 24)
        body.addView(text(c("已经开启却总是关闭？", "开咗但成日关闭？", "Does it turn off after enabling?"), 22f, Design.ink, true).heading())
        space(body, 10)
        body.addView(text(c("这与首次解除限制不同。请查看服务诊断，记录权限和连接状态，避免反复修改不相关设置。", "呢个同首次解除限制唔同。请睇服务诊断，记录权限同连接状态。", "This differs from restricted setup. Check diagnostics to distinguish permission changes from a disconnected service."), 18f))
        space(body, 12)
        body.addView(action(c("服务状态与诊断", "服务状态同诊断", "Service status and diagnostics"), Design.surface, Design.ink, 54) { showDiagnostics() })
    }

    private fun showDiagnostics() {
        screen = Screen.DIAGNOSTICS
        val body = page(c("服务状态与诊断", "服务状态同诊断", "Service status and diagnostics"), ::showAdjust)
        body.addView(text(c("把问题说清楚", "睇清楚问题", "Understand the service state"), 26f, Design.ink, true).heading())
        space(body, 18)
        val enabled = serviceEnabled()
        body.addView(text(c("系统权限：", "系统权限：", "System permission: ") + if (enabled) c("已开启", "开咗", "On") else c("已关闭", "关咗", "Off"), 20f, Design.ink, true))
        space(body, 12)
        body.addView(text(c("服务连接：", "服务连接：", "Service connection: ") + if (GuideAccessibilityService.connected) c("已连接", "连接咗", "Connected") else c("未连接", "未连接", "Disconnected"), 20f, Design.ink, true))
        space(body, 12)
        body.addView(text(c("本次引导：", "今次指引：", "This guide: ") + when (preferences.sessionState.phase) {
            SessionPhase.RUNNING -> c("正在进行", "进行紧", "Running")
            SessionPhase.PAUSED -> c("已暂停", "暂停咗", "Paused")
            SessionPhase.IDLE -> c("未进行", "未开始", "Inactive")
        }, 20f, Design.ink, true))
        space(body, 18)
        body.addView(text(ServiceDiagnostics.buildLabel(), 16f, Design.secondary))
        space(body, 18)
        body.addView(text(c("诊断仅包含设备版本、服务时间线和进程退出原因，保存在本机。不含页面文字、目标、录音或账号；复制后由你决定是否分享。", "诊断只包括设备版本、服务时间线同进程退出原因，保存在本机。唔包括页面文字、目标、录音或账号；复制后由你决定分享。", "Diagnostics stay on this device and contain versions, service events and exit reasons. No screen text, goals, audio or accounts. You decide whether to share the copy."), 18f))
        space(body, 18)
        body.addView(action(c("复制诊断信息", "复制诊断资料", "Copy diagnostics"), Design.forest, Design.surface, 56) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Handrail diagnostics", ServiceDiagnostics.report(this)))
            Toast.makeText(this, c("已复制，可粘贴给协作者", "复制咗，可以贴俾协作者", "Copied. You can share it with your team."), Toast.LENGTH_SHORT).show()
        })
        space(body, 12)
        body.addView(action(c("刷新状态", "更新状态", "Refresh status"), Design.surface, Design.ink, 54) { showDiagnostics() })
        space(body, 12)
        body.addView(action(SupportCopy.openSettings(preferences.textLanguage), Design.surface, Design.ink, 54) { showSetup() })
    }

    private fun showReceipt() {
        screen = Screen.RECEIPT
        val record = preferences.records().firstOrNull() ?: run { showHome(); return }
        val confirmed = record.outcome == PracticeOutcome.CONFIRMED
        val body = page(c("这次引导", "今次指引", "This guide"), ::showHome)
        body.addView(icon(taskIcon(record.task), 40), LinearLayout.LayoutParams(dp(56), dp(56)))
        space(body, 24)
        body.addView(text(if (confirmed) c("你已确认完成", "你已确认完成", "You confirmed completion")
            else c("这次引导已结束", "今次指引结束咗", "This guide has ended"), 28f, Design.ink, true).heading())
        space(body, 14)
        body.addView(text(SupportCopy.task(record.task, preferences.textLanguage), 22f, Design.ink, true))
        space(body, 14)
        body.addView(text(if (confirmed) c("结果已经由你确认。以后需要帮助，可以再叫我。", "结果由你确认咗。之后需要帮助，可以再叫我。", "You checked the result. You can ask for help again anytime.")
            else c("尚未确认完成，记录也不会算成成功。你可以重新开始。", "未确认完成，记录唔会算成功。你可以重新开始。", "Completion was not confirmed and is not recorded as success. You can try again."), 18f))
        space(body, 28)
        body.addView(action(c("返回首页", "返首页", "Back to home"), Design.forest, Design.surface, 58) { showHome() })
        space(body, 12)
        body.addView(action(c("查看尝试记录", "睇尝试记录", "View attempts"), Design.surface, Design.ink, 56) { showPractice() })
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
            preferences.receiptPending = false
            goalSource = GoalSource.SHORTCUT
            showTask()
        })
        space(body, 10)
        body.addView(action(ExperienceCopy.unconfirmed(l), Design.background, Design.secondary, 52) {
            preferences.finish(PracticeOutcome.ENDED)
            preferences.receiptPending = false
            showReceipt()
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
                goalSource = GoalSource.SHORTCUT
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

    private fun taskTitle(task: GuideTask) = when (task) {
        GuideTask.FONT_SIZE -> c("把字调大", "把字调大", "Make the text larger")
        GuideTask.SCREEN_BRIGHTNESS -> c("调亮屏幕", "调亮屏幕", "Brighten the screen")
        GuideTask.CHROME_BOOKMARK -> c("收藏长者服务网页", "收藏长者服务网页", "Save the elderly services page")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c("查看中央图书馆地址", "查看中央图书馆地址", "See the Central Library address")
    }

    private fun taskDetail(task: GuideTask) = when (task) {
        GuideTask.FONT_SIZE -> c("把手机里的字变大", "把手机里的字变大", "Make the words on the phone bigger")
        GuideTask.SCREEN_BRIGHTNESS -> c("把屏幕调亮一点", "把屏幕调亮一点", "Make the screen brighter")
        GuideTask.CHROME_BOOKMARK -> c("把长者服务网页存下来", "把长者服务网页存下来", "Save the elderly services page")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c("看香港中央图书馆的地址", "看香港中央图书馆的地址", "See the Hong Kong Central Library address")
    }

    private fun runner() = FirstRun(this, preferences, ::serviceEnabled, ::openServiceSettings, { showHome() }, { showHome() }, ::showSetup, ::showDiagnostics)

    private fun showFirstRun() {
        screen = Screen.FIRST
        runner().showWizard()
    }

    private fun showAdjust() {
        stopInput()
        screen = Screen.ADJUST
        runner().showSettings()
    }

    private fun addGoalSource(body: LinearLayout) {
        val explanation = when (goalSource) {
            GoalSource.MODEL -> GuideContent.intentMatched(preferences.textLanguage)
            GoalSource.LOCAL -> GuideContent.intentLocalFallback(preferences.textLanguage)
            GoalSource.SHORTCUT -> return
        }
        space(body, 12)
        body.addView(text(explanation, 16f, Design.secondary))
    }

    private fun showConfirm() {
        stopInput()
        screen = Screen.CONFIRM
        val root = screenRoot()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
        }
        body.addView(text(c("你要做的是：${taskTitle(selectedTask)}。对不对？", "你要做的是：${taskTitle(selectedTask)}。对不对？", "You want to: ${taskTitle(selectedTask)}. Is that right?"), 28f, Design.ink, true).heading())
        addGoalSource(body)
        space(body, 28)
        body.addView(action(c("对", "对", "Yes"), Design.forest, Design.surface, 72) { showTask() })
        space(body, 12)
        body.addView(action(c("不对", "不对", "No"), Design.surface, Design.ink, 72) { showHome() })
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, -1))
        applyInsets(root)
        setContentView(root)
    }

    @Deprecated("Activity back callback used for this small native-view prototype")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.SETUP -> leaveSetup()
            Screen.SETUP_HELP -> showSetup()
            Screen.DIAGNOSTICS -> showAdjust()
            Screen.RECEIPT -> showHome()
            Screen.FIRST -> if (preferences.initStep > 0) { preferences.initStep -= 1; showFirstRun() } else super.onBackPressed()
            Screen.CONFIRM, Screen.ADJUST -> showHome()
            else -> showHome()
        }
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
