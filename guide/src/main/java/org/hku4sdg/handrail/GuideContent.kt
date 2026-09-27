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

enum class GuideStep { DISPLAY, FONT_SIZE, SLIDER, COMPLETE }

/** Fixed draft copy for this fictional task; native-speaker review is still pending. */
object GuideContent {
    fun instruction(step: GuideStep, language: GuideLanguage): String = when (language) {
        GuideLanguage.MANDARIN -> when (step) {
            GuideStep.DISPLAY -> "第 1 步：请点圈住的“显示设置”。"
            GuideStep.FONT_SIZE -> "第 2 步：请点圈住的“字体大小”。"
            GuideStep.SLIDER -> "第 3 步：请亲手拖动圈住的滑块，把字调大。"
            GuideStep.COMPLETE -> "练习完成：你已经把字调大了。"
        }
        GuideLanguage.CANTONESE -> when (step) {
            GuideStep.DISPLAY -> "第 1 步：请㩒圈住嘅「显示设置」。"
            GuideStep.FONT_SIZE -> "第 2 步：请㩒圈住嘅「字体大小」。"
            GuideStep.SLIDER -> "第 3 步：请自己拖动圈住嘅滑桿，调大啲字。"
            GuideStep.COMPLETE -> "练习完成：你已经将啲字调大咗。"
        }
        GuideLanguage.ENGLISH -> when (step) {
            GuideStep.DISPLAY -> "Step 1: Tap the circled “显示设置” button."
            GuideStep.FONT_SIZE -> "Step 2: Tap the circled “字体大小” button."
            GuideStep.SLIDER -> "Step 3: Move the circled slider yourself to make the text larger."
            GuideStep.COMPLETE -> "Practice complete: you made the text larger."
        }
    }

    fun pause(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "辅助已暂停。请自行完成私人步骤，返回普通页面后再点“继续”。"
        GuideLanguage.CANTONESE -> "辅助已暂停。请自己完成私人步骤，返到普通页面再㩒「继续」。"
        GuideLanguage.ENGLISH -> "Guidance is paused. Finish the private step yourself, return to a regular page, then tap Continue."
    }

    fun unknown(language: GuideLanguage) = when (language) {
        GuideLanguage.MANDARIN -> "无法确认当前页面，已停止圈画。请返回练习流程。"
        GuideLanguage.CANTONESE -> "确认唔到呢个页面，已经停止圈画。请返去练习流程。"
        GuideLanguage.ENGLISH -> "This page is not recognized. Highlighting has stopped. Return to the practice flow."
    }
}

class GuidePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(value) { prefs.edit().putBoolean("active", value).apply() }
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
