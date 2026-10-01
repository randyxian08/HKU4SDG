package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {
    @Test fun leavingAndReconnectingCannotResumeOrConfirmAnAttempt() {
        val running = SessionState(SessionPhase.RUNNING)
        val paused = running.pause(PauseReason.LEFT_APP)
        assertTrue(paused.active)
        assertFalse(paused.canConfirm)
        assertEquals(paused, paused.pause(PauseReason.SERVICE_RESTART))
        assertEquals(paused, paused.finish(PracticeOutcome.CONFIRMED))
        assertEquals(running, paused.resume())
        assertEquals(SessionState(), paused.resume().finish(PracticeOutcome.CONFIRMED))
    }

    @Test fun endingPausedAttemptDoesNotBecomeConfirmedOrResumeAgain() {
        val paused = SessionState(SessionPhase.RUNNING).pause(PauseReason.USER)
        val ended = paused.finish(PracticeOutcome.ENDED)
        assertFalse(ended.active)
        assertFalse(ended.canConfirm)
        assertEquals(ended, ended.resume())
    }

    @Test fun idleCallbacksCannotCreateAnAttempt() {
        val idle = SessionState()
        assertEquals(idle, idle.pause(PauseReason.SYSTEM_INTERRUPT).resume())
        assertEquals(idle, idle.finish(PracticeOutcome.CONFIRMED))
    }
}
