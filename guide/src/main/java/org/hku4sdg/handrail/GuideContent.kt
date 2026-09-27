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

enum class GuideStep { DISPLAY_SIZE, SLIDER, COMPLETE }

/** Fixed copy for the reviewed Android 15 / Pixel 7 emulator flow. Native-speaker review is pending. */
object GuideContent {
    fun instruction(step: GuideStep, language: GuideLanguage): String = when (language) {
        GuideLanguage.MANDARIN -> when (step) {
            GuideStep.DISPLAY_SIZE -> "请点圈住的“Display size and text”。"
            GuideStep.SLIDER -> "请亲手把圈住的“Font size”滑块向右拖动。"
            GuideStep.COMPLETE -> "已检测到系统字体变大。请看看现在的文字是否更容易阅读。"
        }
        GuideLanguage.CANTONESE -> when (step) {
            GuideStep.DISPLAY_SIZE -> "请㩒圈住嘅「Display size and text」。"
            GuideStep.SLIDER -> "请自己将圈住嘅「Font size」滑桿向右拖。"
            GuideStep.COMPLETE -> "系统字体已经变大。睇下而家啲字系咪易睇咗。"
        }
        GuideLanguage.ENGLISH -> when (step) {
            GuideStep.DISPLAY_SIZE -> "Tap the circled “Display size and text” row."
            GuideStep.SLIDER -> "Drag the circled “Font size” slider to the right."
            GuideStep.COMPLETE -> "The system font is larger. Check whether the text is easier to read."
        }
    }

    fun pause(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "辅助已暂停。请自行完成私人步骤，返回普通页面后再点“继续”。"
        GuideLanguage.CANTONESE -> "辅助已暂停。请自己完成私人步骤，返到普通页面再㩒「继续」。"
        GuideLanguage.ENGLISH -> "Guidance is paused. Finish the private step yourself, return to a regular page, then tap Continue."
    }

    fun unknown(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "无法确认当前设置页面，已停止圈画。请返回数字扶手重新开始。"
        GuideLanguage.CANTONESE -> "确认唔到而家嘅设定页面，已经停止圈画。请返数字扶手重新开始。"
        GuideLanguage.ENGLISH -> "This Settings page is not recognized. Highlighting has stopped. Return to Digital Handrail and start again."
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
    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) { prefs.edit().putBoolean("muted", value).apply() }
    var collapsed: Boolean
        get() = prefs.getBoolean("collapsed", false)
        set(value) { prefs.edit().putBoolean("collapsed", value).apply() }
    var speechConsent: Boolean
        get() = prefs.getBoolean("speechConsent", false)
        set(value) { prefs.edit().putBoolean("speechConsent", value).apply() }
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

/** A bounded, deterministic fallback for the demo. It is not presented as AI. */
object TaskMatcher {
    fun matchesFontSize(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT)
        return listOf("字", "字体", "啲字", "font", "text", "larger", "bigger").any { it in text }
    }
}
