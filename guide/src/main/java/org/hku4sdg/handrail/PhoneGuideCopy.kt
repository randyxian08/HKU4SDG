package org.hku4sdg.handrail

/** Phone trial copy names runtime controls; it does not claim an OEM device has been verified. */
object PhoneGuideCopy {
    private fun c(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun notice(l: GuideLanguage) = c(l,
        "手机实验引导：可以开始尝试，识别到当前控件才圈画。这台设备尚未实测；页面不同会保留文字提示。",
        "手机实验指引：可以开始试，认到而家嘅控件先圈画。呢台设备未实测；页面唔同时会保留文字提示。",
        "Phone trial: you can try this guide. A highlight appears only when the current control is identified. This device has not been tested; text stays available if its layout differs.")
    fun instruction(step: GuideStep, label: String, l: GuideLanguage): String = when (step) {
        GuideStep.DISPLAY_SCROLL -> c(l, "请在提示卡外向上滑动，找到“字体大小”或“字体大小与粗细”。", "请喺提示卡外向上扫，搵「字体大小」或者「字体大小与粗细」。", "Swipe up outside this card to find the text-size settings.")
        GuideStep.DISPLAY_SIZE, GuideStep.BRIGHTNESS_ROW -> c(l, "请点圈住的“$label”。", "请㩒圈住嘅「$label」。", "Tap the circled “$label” row.")
        GuideStep.SLIDER -> c(l, "请亲手把圈住的字体大小滑块向右拖动，查看文字预览。", "请自己将圈住嘅字体大小滑桿向右拖，睇下文字预览。", "Drag the circled text-size slider to the right and check the preview.")
        GuideStep.SLIDER_SCROLL -> c(l, "已进入字体页面。请在提示卡外滑动，找到字体大小滑块；尚未确认的控件不会圈画。", "已经入咗字体页面。请喺提示卡外滑动，搵字体大小滑桿；未确认嘅控件唔会圈画。", "You are on the text-size page. Scroll outside this card to find the font-size slider. Unidentified controls are not highlighted.")
        GuideStep.BRIGHTNESS_SLIDER -> c(l, "请亲手把圈住的亮度滑块向右拖动，调到看着舒服的程度。", "请自己将圈住嘅亮度滑桿向右拖，调到睇得舒服嘅程度。", "Drag the circled brightness slider to the right until it feels comfortable.")
        else -> GuideContent.instruction(step, l)
    }
    fun atMaximum(task: GuideTask, l: GuideLanguage) = if (task == GuideTask.FONT_SIZE)
        c(l, "这个字体滑块已到上限。你可以自行选择合适字号，或结束本次尝试。", "呢个字体滑桿已经到上限。你可以自己拣合适字号，或者结束今次尝试。", "This text-size slider is at its limit. Choose a comfortable size yourself, or end this attempt.")
        else c(l, "这个亮度滑块已到上限。你可以自行调整到舒服的亮度，或结束本次尝试。", "呢个光度滑桿已经到上限。你可以自己调到舒服嘅光度，或者结束今次尝试。", "This brightness slider is at its limit. Choose a comfortable brightness yourself, or end this attempt.")
    fun unknown(task: GuideTask, l: GuideLanguage) = if (task == GuideTask.FONT_SIZE)
        c(l, "请在设置中找“字体大小”或“字体大小与粗细”。当前控件尚未识别，暂不圈画；你仍可自行调整，或返回任务。", "请喺设置搵「字体大小」或者「字体大小与粗细」。而家嘅控件未认到，暂时唔圈画；你仲可以自己调，或者返任务。", "Find Font size in Settings. The current control is not identified, so no highlight is shown. You can adjust it yourself or return to the task.")
        else c(l, "请在“显示与亮度”或“显示、亮度与护眼”中找亮度滑块。当前控件尚未识别，暂不圈画；你仍可自行调整，或返回任务。", "请喺「显示与亮度」或者「显示、亮度与护眼」搵光度滑桿。而家嘅控件未认到，暂时唔圈画；你仲可以自己调，或者返任务。", "Find the brightness slider in Display settings. The current control is not identified, so no highlight is shown. You can adjust it yourself or return to the task.")
    fun steps(task: GuideTask, l: GuideLanguage) = if (task == GuideTask.FONT_SIZE)
        listOf(c(l, "在设置中找到字体大小", "喺设置搵字体大小", "Find text size in Settings"), c(l, "调整字体滑块，检查预览", "调字体滑桿，睇下预览", "Adjust the text-size slider and preview"))
        else listOf(c(l, "在显示设置中找到亮度", "喺显示设置搵光度", "Find brightness in Display settings"), c(l, "调整亮度，确认是否舒服", "调光度，确认睇得舒唔舒服", "Adjust brightness and check comfort"))
    fun scope(l: GuideLanguage) = c(l,
        "Android 8–16 · 中文或英文系统页面。手机实验适配尚未逐机验收，只在唯一匹配到实际字体或亮度控件时圈画；页面变化就撤圈。语音使用本机可用服务，文字始终保留。",
        "Android 8–16 · 中文或者英文系统页面。手机实验适配未逐台验收，唯一认到实际字体或者光度控件先圈画；页面变咗就撤圈。语音用本机可用服务，文字一直保留。",
        "Android 8–16 · Chinese or English system pages. This phone trial has not been verified on each device. Only uniquely identified text-size or brightness controls are highlighted; page changes remove the highlight. Speech uses available device services; text remains available.")
}
