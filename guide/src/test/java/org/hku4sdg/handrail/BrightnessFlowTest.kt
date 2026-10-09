package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class BrightnessFlowTest {
    @Test fun manualSelectionFirstDisablesAutomaticAndDoesNotCountItsChangesAsCompletion() {
        assertEquals(BrightnessAction.TURN_AUTO_OFF, BrightnessFlow.action(BrightnessGoal.MANUAL, true, true))
        assertEquals(BrightnessAction.ADJUST, BrightnessFlow.action(BrightnessGoal.MANUAL, false, false))
        assertEquals(BrightnessAction.READY, BrightnessFlow.action(BrightnessGoal.MANUAL, false, true))
    }
    @Test fun adaptiveSelectionDoesNotAskForManualAdjustment() {
        assertEquals(BrightnessAction.TURN_AUTO_ON, BrightnessFlow.action(BrightnessGoal.ADAPTIVE, false, true))
        assertEquals(BrightnessAction.READY, BrightnessFlow.action(BrightnessGoal.ADAPTIVE, true, false))
    }
    @Test fun unreadableModeCannotSelectAnActionOrReportSuccess() {
        BrightnessGoal.entries.forEach { assertEquals(BrightnessAction.UNKNOWN, BrightnessFlow.action(it, null, true)) }
    }
}
