package org.hku4sdg.handrail

import android.content.Context
import java.util.Locale

enum class GuideLanguage(val label: String, val tag: String) {
    MANDARIN("普通话", "zh-CN"),
    CANTONESE("粤语", "yue-HK"),
    ENGLISH("English", "en-US");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromName(value: String?) = entries.firstOrNull { it.name == value } ?: MANDARIN
    }
}

enum class GuideTask(val id: String) {
    FONT_SIZE("font_size"), SCREEN_BRIGHTNESS("screen_brightness"),
    CHROME_BOOKMARK("chrome_bookmark"), MAPS_LIBRARY_ADDRESS("maps_library_address");

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

enum class GuideStep(val task: GuideTask, val number: Int) {
    DISPLAY_SIZE(GuideTask.FONT_SIZE, 1),
    SLIDER(GuideTask.FONT_SIZE, 2),
    COMPLETE(GuideTask.FONT_SIZE, 2),
    BRIGHTNESS_ROW(GuideTask.SCREEN_BRIGHTNESS, 1),
    BRIGHTNESS_SLIDER(GuideTask.SCREEN_BRIGHTNESS, 2),
    BRIGHTNESS_COMPLETE(GuideTask.SCREEN_BRIGHTNESS, 2),
    CHROME_MENU(GuideTask.CHROME_BOOKMARK, 1),
    CHROME_SAVE(GuideTask.CHROME_BOOKMARK, 2),
    CHROME_VERIFY(GuideTask.CHROME_BOOKMARK, 3),
    CHROME_COMPLETE(GuideTask.CHROME_BOOKMARK, 3),
    MAPS_PLACE(GuideTask.MAPS_LIBRARY_ADDRESS, 1),
    MAPS_ADDRESS(GuideTask.MAPS_LIBRARY_ADDRESS, 2);

    val isComplete get() = this == COMPLETE || this == BRIGHTNESS_COMPLETE ||
        this == CHROME_COMPLETE || this == MAPS_ADDRESS
}

/** Fixed copy for the reviewed Android 15 / Pixel 7 emulator flow. Native-speaker review is pending. */
object GuideContent {
    fun intentSearching(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "正在查找已审核的任务…"
        GuideLanguage.CANTONESE -> "搵紧已经审核嘅任务…"
        GuideLanguage.ENGLISH -> "Looking for a reviewed task…"
    }

    fun intentClarify(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "你想调大手机的字、调亮屏幕、收藏长者服务网页，还是查看中央图书馆地址？请说得具体一点。"
        GuideLanguage.CANTONESE -> "你系想调大手机啲字、调光啲个屏幕、收藏长者服务网页，定系睇中央图书馆地址？请讲清楚啲。"
        GuideLanguage.ENGLISH -> "Do you want larger text, a brighter screen, the elderly-services bookmark, or the Central Library address? Please be more specific."
    }

    fun intentUnsupported(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "暂时没有这项引导。请选择上方可用任务。"
        GuideLanguage.CANTONESE -> "暂时未有呢项引导。请选择上面可用嘅任务。"
        GuideLanguage.ENGLISH -> "This guide is not available yet. Choose one of the tasks above."
    }

    fun intentUnavailable(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "AI 暂时不可用。请使用上方已审核的任务卡。"
        GuideLanguage.CANTONESE -> "AI 暂时用唔到。请用上面已经审核嘅任务卡。"
        GuideLanguage.ENGLISH -> "AI is unavailable right now. Please use a reviewed task card above."
    }

    fun intentMatched(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "根据你的描述，找到这项已审核任务。请确认后再开始。"
        GuideLanguage.CANTONESE -> "根据你嘅描述，搵到呢项已审核任务。请确认之后先开始。"
        GuideLanguage.ENGLISH -> "We found this reviewed task. Please confirm before starting."
    }

    fun instruction(step: GuideStep, language: GuideLanguage): String = when (language) {
        GuideLanguage.MANDARIN -> when (step) {
            GuideStep.DISPLAY_SIZE -> "请点圈住的“Display size and text”。"
            GuideStep.SLIDER -> "请亲手把圈住的“Font size”滑块向右拖动。"
            GuideStep.COMPLETE -> "已检测到系统字体变大。请看看现在的文字是否更容易阅读。"
            GuideStep.BRIGHTNESS_ROW -> "请点圈住的“Brightness level”。"
            GuideStep.BRIGHTNESS_SLIDER -> "请亲手把圈住的亮度滑块向右拖动。"
            GuideStep.BRIGHTNESS_COMPLETE -> "已检测到屏幕亮度提高。请看看现在是否更容易看清。"
            GuideStep.CHROME_MENU -> "请点圈住的 Chrome 右上角菜单。"
            GuideStep.CHROME_SAVE -> "请点圈住的“Bookmark”星标，收藏这个公开网页。"
            GuideStep.CHROME_VERIFY -> "请再次打开圈住的菜单，检查书签是否已保存。"
            GuideStep.CHROME_COMPLETE -> "已确认 Chrome 显示“Edit bookmark”，这个网页已收藏。"
            GuideStep.MAPS_PLACE -> "请点圈住的“Hong Kong Central Library”地点卡，展开详情。"
            GuideStep.MAPS_ADDRESS -> "地图已显示香港中央图书馆的地址。请看圈住的地址行；出行前也可向图书馆核对。"
        }
        GuideLanguage.CANTONESE -> when (step) {
            GuideStep.DISPLAY_SIZE -> "请㩒圈住嘅「Display size and text」。"
            GuideStep.SLIDER -> "请自己将圈住嘅「Font size」滑桿向右拖。"
            GuideStep.COMPLETE -> "系统字体已经变大。睇下而家啲字系咪易睇咗。"
            GuideStep.BRIGHTNESS_ROW -> "请㩒圈住嘅「Brightness level」。"
            GuideStep.BRIGHTNESS_SLIDER -> "请自己将圈住嘅光度滑桿向右拖。"
            GuideStep.BRIGHTNESS_COMPLETE -> "屏幕光度已经提高。睇下而家系咪清楚啲。"
            GuideStep.CHROME_MENU -> "请㩒圈住嘅 Chrome 右上角選單。"
            GuideStep.CHROME_SAVE -> "请㩒圈住嘅「Bookmark」星號，收藏呢個公開網頁。"
            GuideStep.CHROME_VERIFY -> "请再打开圈住嘅選單，检查書籤係咪已儲存。"
            GuideStep.CHROME_COMPLETE -> "已確認 Chrome 顯示「Edit bookmark」，呢個網頁已收藏。"
            GuideStep.MAPS_PLACE -> "请㩒圈住嘅「Hong Kong Central Library」地点卡，打开详情。"
            GuideStep.MAPS_ADDRESS -> "地图已经显示香港中央图书馆嘅地址。请睇圈住嘅地址；出发前亦可以向图书馆核对。"
        }
        GuideLanguage.ENGLISH -> when (step) {
            GuideStep.DISPLAY_SIZE -> "Tap the circled “Display size and text” row."
            GuideStep.SLIDER -> "Drag the circled “Font size” slider to the right."
            GuideStep.COMPLETE -> "The system font is larger. Check whether the text is easier to read."
            GuideStep.BRIGHTNESS_ROW -> "Tap the circled “Brightness level” row."
            GuideStep.BRIGHTNESS_SLIDER -> "Drag the circled brightness slider to the right."
            GuideStep.BRIGHTNESS_COMPLETE -> "Screen brightness has increased. Check whether it is easier to see."
            GuideStep.CHROME_MENU -> "Tap the circled Chrome menu in the top right."
            GuideStep.CHROME_SAVE -> "Tap the circled Bookmark star to save this public page."
            GuideStep.CHROME_VERIFY -> "Open the circled menu again to check that the bookmark was saved."
            GuideStep.CHROME_COMPLETE -> "Chrome now shows “Edit bookmark”, confirming this page was saved."
            GuideStep.MAPS_PLACE -> "Tap the circled Hong Kong Central Library place card to open its details."
            GuideStep.MAPS_ADDRESS -> "Maps now shows the library address. Read the circled address and confirm it with the library before travelling if needed."
        }
    }

    fun pause(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "辅助已暂停。请自行完成私人步骤，返回普通页面后再点“继续”。"
        GuideLanguage.CANTONESE -> "辅助已暂停。请自己完成私人步骤，返到普通页面再㩒「继续」。"
        GuideLanguage.ENGLISH -> "Guidance is paused. Finish the private step yourself, return to a regular page, then tap Continue."
    }

    fun unknown(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "无法确认当前页面，已停止圈画。请返回数字扶手重新开始。"
        GuideLanguage.CANTONESE -> "确认唔到而家嘅頁面，已经停止圈畫。请返数字扶手重新开始。"
        GuideLanguage.ENGLISH -> "This page is not recognized. Highlighting has stopped. Return to Digital Handrail and start again."
    }
}

class GuidePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(value) { prefs.edit().putBoolean("active", value).apply() }
    var initialFontScale: Float
        get() = prefs.getFloat("initialFontScale", 1f)
        set(value) { prefs.edit().putFloat("initialFontScale", value).apply() }
    var initialBrightness: Int
        get() = prefs.getInt("initialBrightness", -1)
        set(value) { prefs.edit().putInt("initialBrightness", value).apply() }
    var activeTask: GuideTask
        get() = GuideTask.fromId(prefs.getString("activeTask", "") ?: "") ?: GuideTask.FONT_SIZE
        set(value) { prefs.edit().putString("activeTask", value.id).apply() }
    var guideSessionId: Int
        get() = prefs.getInt("guideSessionId", 0)
        set(value) { prefs.edit().putInt("guideSessionId", value).apply() }
    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) { prefs.edit().putBoolean("muted", value).apply() }
    var collapsed: Boolean
        get() = prefs.getBoolean("collapsed", false)
        set(value) { prefs.edit().putBoolean("collapsed", value).apply() }
    var speechConsent: Boolean
        get() = prefs.getBoolean("speechConsent", false)
        set(value) { prefs.edit().putBoolean("speechConsent", value).apply() }
    var cloudAsrConsent: Boolean
        get() = prefs.getBoolean("cloudAsrConsent", false)
        set(value) { prefs.edit().putBoolean("cloudAsrConsent", value).apply() }
    var inputLanguage: GuideLanguage
        get() = language("input")
        set(value) = setLanguage("input", value)
    var textLanguage: GuideLanguage
        get() = language("text")
        set(value) = setLanguage("text", value)
    var voiceLanguage: GuideLanguage
        get() = language("voice")
        set(value) = setLanguage("voice", value)

    private fun language(key: String) = GuideLanguage.fromName(prefs.getString(key, null))
    private fun setLanguage(key: String, value: GuideLanguage) { prefs.edit().putString(key, value.name).apply() }
}

/** Conservative local fallback for reviewed tasks; never presented as AI. */
object TaskMatcher {
    fun match(input: String): GuideTask? = when {
        matchesFontSize(input) -> GuideTask.FONT_SIZE
        matchesBrightness(input) -> GuideTask.SCREEN_BRIGHTNESS
        matchesChromeBookmark(input) -> GuideTask.CHROME_BOOKMARK
        matchesMapsLibraryAddress(input) -> GuideTask.MAPS_LIBRARY_ADDRESS
        else -> null
    }

    fun matchesFontSize(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT)
        if (listOf("短信", "微信", "whatsapp", "message", "send a text", "text me", "照片", "图片",
                "photo", "zoom", "调小", "缩小", "smaller", "decrease", "颜色", "colour", "color")
                .any { it in text }) return false
        return listOf("把字调大", "字体调大", "字体变大", "字太小", "啲字太细", "放大啲字",
            "increase font", "larger font", "font size bigger", "make the text bigger",
            "text size bigger", "larger text", "bigger text").any { it in text }
    }

    fun matchesBrightness(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT)
        if (listOf("自动亮度", "adaptive brightness", "调暗", "降低亮度", "太亮", "dimmer",
                "decrease brightness", "brightness down", "font", "字体", "短信", "微信",
                "whatsapp", "照片", "photo", "password", "payment").any { it in text }) return false
        return listOf("调高亮度", "提高亮度", "屏幕太暗", "屏幕好暗", "光度太暗", "加光啲",
            "increase brightness", "raise brightness", "screen too dim", "screen is too dark",
            "make the screen brighter").any { it in text }
    }

    fun matchesChromeBookmark(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT)
        val save = listOf("收藏", "书签", "書籤", "bookmark", "save this page").any { it in text }
        val source = listOf("政府一站通", "govhk", "gov.hk").any { it in text }
        val page = listOf("长者服务", "長者服務", "老人服务", "elderly services",
            "elderly service page").any { it in text }
        return save && source && page && !SensitiveGoalGuard.shouldKeepLocal(text)
    }

    fun matchesMapsLibraryAddress(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT)
        val place = listOf("香港中央图书馆", "香港中央圖書館", "hong kong central library").any { it in text }
        val address = listOf("地址", "位置", "address", "where is").any { it in text }
        val excluded = listOf("路线", "路線", "导航", "導航", "怎么去", "點樣去", "directions",
            "route", "opening hours", "开放时间", "開放時間", "电话", "電話", "phone").any { it in text }
        val otherPlace = listOf("exhibition gallery", "展览馆", "展覽館").any { it in text }
        return place && address && !excluded && !otherPlace && !SensitiveGoalGuard.shouldKeepLocal(text)
    }
}

/** Stop obvious sensitive goals before the user's text reaches the model bridge. */
object SensitiveGoalGuard {
    private val terms = listOf("密码", "验证码", "银行卡", "身份证", "支付", "付款", "转账", "病历",
        "医生", "医院", "password", "one-time code", "verification code", "credit card",
        "bank account", "payment", "transfer", "medical", "doctor")

    fun shouldKeepLocal(input: String): Boolean {
        val text = input.lowercase(Locale.ROOT)
        return terms.any { it in text } || Regex("[0-9]{6,}").containsMatchIn(text) || '@' in text
    }
}
