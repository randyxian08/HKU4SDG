package org.hku4sdg.handrail

/** Operation labels follow text language; spoken instructions still follow voice language. */
object SupportCopy {
    private fun c(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun pause(l: GuideLanguage) = c(l, "先停一下", "停一停先", "Pause")
    fun resume(l: GuideLanguage) = c(l, "继续指引", "继续指引", "Continue guide")
    fun paused(l: GuideLanguage) = c(l, "指引已暂停", "指引已暂停", "Guide paused")
    fun pauseMessage(reason: PauseReason?, l: GuideLanguage) = when (reason) {
        PauseReason.USER -> c(l, "先歇一会儿。圈画和播报已停止；想继续时，点下面的按钮。", "抖一阵先。圈画同播报停咗；想继续就㩒下面嘅掣。", "Take a break. Highlights and speech have stopped. Continue when you are ready.")
        PauseReason.LEFT_APP -> c(l, "刚才离开了任务页面。点继续后，我们会重新确认当前页面。", "头先离开咗任务页面。㩒继续之后，我哋会重新确认而家嘅页面。", "You left the task page. Continue to check this page again.")
        else -> c(l, "引导服务刚才中断了。任务还保留着，点继续后重新确认页面。", "指引服务头先中断咗。任务仲喺度，㩒继续后重新确认页面。", "The guide was interrupted. Your task is saved. Continue to check the page again.")
    }
    fun text(l: GuideLanguage) = c(l, "文字", "文字", "Text")
    fun voice(l: GuideLanguage) = c(l, "播报", "播报", "Voice")
    fun replay(l: GuideLanguage) = c(l, "再听一遍", "再听一次", "Hear again")
    fun sound(l: GuideLanguage, muted: Boolean) = if (muted) c(l, "开声音", "开声音", "Sound on") else c(l, "静音", "静音", "Mute")
    fun collapse(l: GuideLanguage, collapsed: Boolean) = if (collapsed)
        c(l, "展开文字 ⌄", "展开文字 ⌄", "Show text ⌄") else c(l, "收起文字 ⌃", "收起文字 ⌃", "Hide text ⌃")
    fun circled(l: GuideLanguage) = c(l, "圈出的地方，就是下一步", "圈住嘅地方，就系下一步", "The highlighted control is your next step")
    fun step(l: GuideLanguage, n: Int, total: Int) = c(l, "正在引导 · 第 $n / $total 步", "指引紧 · 第 $n / $total 步", "Guiding · Step $n of $total")
    fun unknown(l: GuideLanguage) = c(l, "页面暂未识别", "页面暂时未认到", "Page not recognized")
    fun mode(mode: HelpMode, l: GuideLanguage) = when (mode) {
        HelpMode.FULL -> c(l, "完整指引", "完整指引", "Full guidance")
        HelpMode.LIGHT -> c(l, "轻提示", "少量提示", "Light hints")
        HelpMode.ON_DEMAND -> c(l, "自己先试试", "自己试下先", "Try it myself")
    }
    fun modeDescription(mode: HelpMode, l: GuideLanguage) = when (mode) {
        HelpMode.FULL -> c(l, "圈画、文字和可选播报，一步一步陪你做。", "圈画、文字同可选播报，逐步陪你做。", "Highlights, text and optional speech, one step at a time.")
        HelpMode.LIGHT -> c(l, "先看圈画；需要时展开文字或再听一遍。", "先睇圈画；需要时展开文字或者再听一次。", "Start with highlights. Open text or hear the step when needed.")
        HelpMode.ON_DEMAND -> c(l, "先不圈画；随时点“给我提示”恢复帮助。", "先唔圈画；随时㩒「俾我提示」攞返帮助。", "Start without highlights. Ask for a hint anytime.")
    }
    fun helpTitle(l: GuideLanguage) = c(l, "这次想要多少帮助？", "今次想要几多帮助？", "How much help would you like?")
    fun help(l: GuideLanguage) = c(l, "帮助方式", "帮助方式", "Help")
    fun keepChoice(l: GuideLanguage) = c(l, "保留当前选择", "保留而家嘅选择", "Keep my choice")
    fun returnTask(l: GuideLanguage) = c(l, "返回任务", "返去任务", "Return to task")
    fun task(task: GuideTask, l: GuideLanguage) = when(task) {
        GuideTask.FONT_SIZE -> c(l, "把字调大", "将啲字调大", "Make text larger")
        GuideTask.SCREEN_BRIGHTNESS -> c(l, "调高屏幕亮度", "调光个画面", "Make the screen brighter")
        GuideTask.CHROME_BOOKMARK -> c(l, "收藏长者服务网页", "收藏长者服务网页", "Bookmark the elderly services page")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "查看中央图书馆地址", "睇中央图书馆地址", "Find the Central Library address")
    }
    fun setupTitle(l: GuideLanguage) = c(l, "开启手机引导", "开启手机指引", "Set up your guide")
    fun setupHeading(l: GuideLanguage, enabled: Boolean) = if (enabled)
        c(l, "已经准备好了", "准备好喇", "Your guide is ready") else c(l, "让下一步更容易找到", "等你易啲搵到下一步", "Make the next step easier to find")
    fun setupWhy(l: GuideLanguage) = c(l, "开启无障碍服务，数字扶手才能在其他应用的已审核页面上圈出下一步。点击和拖动始终由你完成。", "开启无障碍服务，数字扶手先可以喺其他应用审核过嘅页面圈出下一步。㩒掣同拖动都系你自己做。", "Enable the accessibility service to highlight the next control on reviewed pages in other apps. You make every tap and drag.")
    fun setupPrivacy(l: GuideLanguage) = c(l, "Android 会提示这项权限可读取屏幕并执行操作。数字扶手只匹配支持任务的必要控件，不代点，也不上传屏幕。你可随时在系统设置关闭。", "Android 会提示呢项权限可以读取屏幕同执行操作。数字扶手只配对支持任务要用嘅控件，唔代㩒，亦唔上传屏幕。你随时可以喺系统设置关闭。", "Android warns that this permission can read the screen and perform actions. Digital Handrail matches controls for supported tasks, never taps for you, and never uploads the screen. You can turn it off in Settings.")
    fun setupSteps(l: GuideLanguage) = listOf(
        c(l, "在系统页面选“数字扶手 · 手机引导”", "喺系统页面拣「数字扶手 · 手机引导」", "Choose “数字扶手 · 手机引导” in Settings"),
        c(l, "打开“Use”开关，阅读并确认 Android 提示", "开启「Use」掣，睇同确认 Android 提示", "Turn on “Use”, then read and confirm Android's prompt"),
        c(l, "用系统返回键回到这里，再选择开始", "用系统返回掣返嚟，再选择开始", "Use Back to return here, then choose to start")
    )
    fun openSettings(l: GuideLanguage) = c(l, "去系统设置开启", "去系统设置开启", "Open Settings")
    fun ready(l: GuideLanguage) = c(l, "引导服务已开启", "指引服务开咗", "Guide service is on")
    fun connecting(l: GuideLanguage) = c(l, "服务正在连接。稍等片刻，再检查一次。", "服务连接紧。等一阵，再检查一次。", "The service is connecting. Wait a moment, then check again.")
    fun checkAgain(l: GuideLanguage) = c(l, "重新检查", "再检查一次", "Check again")
    fun notReady(l: GuideLanguage) = c(l, "还没开启，可以稍后再设置", "仲未开启，可以迟啲再设置", "Not enabled yet. You can set it up later")
    fun backToTask(l: GuideLanguage) = c(l, "返回任务确认", "返任务确认", "Return to task confirmation")
    fun backHome(l: GuideLanguage) = c(l, "返回首页", "返首页", "Back to home")
    fun recoveryTitle(l: GuideLanguage) = c(l, "继续刚才的任务", "继续头先嘅任务", "Continue your task")
    fun recoveryHeading(l: GuideLanguage) = c(l, "刚才的任务还在", "头先嘅任务仲喺度", "Your task is still here")
    fun recoveryDetail(l: GuideLanguage) = c(l, "我们已经停止圈画和播报。返回任务后，会重新确认页面再给提示；任务和帮助方式会保留。", "圈画同播报停咗。返任务后会重新确认页面再俾提示；任务同帮助方式会保留。", "Highlights and speech have stopped. Return to the task to check the page again. Your task and help choice are saved.")
    fun continueTask(l: GuideLanguage) = c(l, "回到任务，继续", "返任务，继续", "Return and continue")
    fun restart(l: GuideLanguage) = c(l, "重新开始这项任务", "重新开始呢项任务", "Start this task again")
    fun setupContinue(l: GuideLanguage) = c(l, "开启服务后继续", "开启服务后继续", "Enable service to continue")
}
