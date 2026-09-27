package org.hku4sdg.handrail

import org.junit.Assert.assertFalse
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
    }
}
