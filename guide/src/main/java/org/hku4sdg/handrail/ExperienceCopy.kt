package org.hku4sdg.handrail

object ExperienceCopy {
    fun choose(language: GuideLanguage, mandarin: String, cantonese: String, english: String) = when (language) {
        GuideLanguage.MANDARIN -> mandarin
        GuideLanguage.CANTONESE -> cantonese
        GuideLanguage.ENGLISH -> english
    }
    fun question(task: GuideTask, language: GuideLanguage) = when (task) {
        GuideTask.FONT_SIZE -> choose(language, "现在的字读起来合适吗？", "而家啲字睇得舒服吗？", "Is the text comfortable to read now?")
        GuideTask.SCREEN_BRIGHTNESS -> choose(language, "现在的亮度舒服吗？", "而家嘅光度舒服吗？", "Is the brightness comfortable now?")
        GuideTask.CHROME_BOOKMARK -> choose(language, "网页已收藏，确认结束吗？", "网页已收藏，要结束吗？", "The page is bookmarked. Finish this guide?")
        GuideTask.MAPS_LIBRARY_ADDRESS -> choose(language, "地址在圈画处，你看到了吗？", "地址喺圈住嘅地方，你睇到吗？", "Can you see the circled address?")
    }
    fun confirm(language: GuideLanguage) = choose(language, "确认完成", "确认完成", "Confirm and finish")
    fun adjust(language: GuideLanguage) = choose(language, "继续自己调整", "继续自己调", "Keep adjusting")
    fun adjustment(task: GuideTask, language: GuideLanguage) = when (task) {
        GuideTask.FONT_SIZE -> choose(language, "按你的习惯调整大小，看看预览。读起来合适了，再确认完成。", "按你嘅习惯调大小，睇下预览。睇得舒服，再确认完成。", "Adjust the size and check the preview. Confirm when the text feels comfortable.")
        else -> choose(language, "按你的习惯调整亮度。眼睛舒服了，再确认完成。", "按你嘅习惯调光度。眼睛舒服，再确认完成。", "Adjust the brightness. Confirm when it feels comfortable.")
    }
    fun end(language: GuideLanguage) = choose(language, "结束", "结束", "End guide")
    fun unconfirmed(language: GuideLanguage) = choose(language, "结束，不确认", "结束，唔确认", "End without confirming")
    fun hint(language: GuideLanguage) = choose(language, "给我提示", "俾我提示", "Show a hint")
    fun own(language: GuideLanguage) = choose(language, "先自己试试，需要时再叫我。", "先自己试下，需要时再叫我。", "Try it yourself. Ask for a hint anytime.")
    fun status(language: GuideLanguage) = choose(language, "已看到结果 · 等你确认", "已见到结果 · 等你确认", "Result detected · Your confirmation")
    fun returnTask(language: GuideLanguage) = choose(language, "返回任务，重新开始", "返任务，重新开始", "Return to the task")
}
