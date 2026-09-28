package org.hku4sdg.handrail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMatcherTest {
    @Test fun acceptsThreeLanguageExamples() {
        assertTrue(TaskMatcher.matchesFontSize("把字体调大"))
        assertTrue(TaskMatcher.matchesFontSize("手机啲字太细"))
        assertTrue(TaskMatcher.matchesFontSize("Make the text bigger"))
    }

    @Test fun rejectsUnapprovedTask() {
        assertFalse(TaskMatcher.matchesFontSize("帮我预约医生"))
        assertFalse(TaskMatcher.matchesFontSize("帮我发短信给儿子"))
        assertFalse(TaskMatcher.matchesFontSize("Send a text message"))
        assertFalse(TaskMatcher.matchesFontSize("Make the text bigger in WhatsApp"))
        assertFalse(TaskMatcher.matchesFontSize("把字体调小"))
    }

    @Test fun distinguishesBrightnessFromFontAndOtherContent() {
        assertEquals(GuideTask.SCREEN_BRIGHTNESS, TaskMatcher.match("屏幕太暗了，请调高亮度"))
        assertEquals(GuideTask.SCREEN_BRIGHTNESS, TaskMatcher.match("Make the screen brighter"))
        assertEquals(GuideTask.FONT_SIZE, TaskMatcher.match("把字体调大"))
        assertNull(TaskMatcher.match("Please lower the screen brightness"))
        assertNull(TaskMatcher.match("Make the photo brighter"))
    }

    @Test fun keepsSensitiveGoalOutOfCloudIntent() {
        assertTrue(SensitiveGoalGuard.shouldKeepLocal("帮我输入银行卡号 123456789"))
        assertTrue(SensitiveGoalGuard.shouldKeepLocal("What is my password?"))
        assertTrue(SensitiveGoalGuard.shouldKeepLocal("帮我预约医生"))
        assertFalse(SensitiveGoalGuard.shouldKeepLocal("手机的字体太小"))
    }
}
