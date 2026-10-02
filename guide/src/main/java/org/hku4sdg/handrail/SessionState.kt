package org.hku4sdg.handrail

enum class PauseReason { USER, LEFT_APP, SERVICE_RESTART, SYSTEM_INTERRUPT, NOT_ENOUGH_SPACE }
enum class SessionPhase { IDLE, RUNNING, PAUSED }

/** A paused attempt remains the same attempt; only an explicit resume can run it again. */
data class SessionState(val phase: SessionPhase = SessionPhase.IDLE, val reason: PauseReason? = null) {
    val active get() = phase != SessionPhase.IDLE
    val canConfirm get() = phase == SessionPhase.RUNNING
    fun pause(reason: PauseReason) = if (phase == SessionPhase.RUNNING)
        SessionState(SessionPhase.PAUSED, reason) else this
    fun resume() = if (phase == SessionPhase.PAUSED) SessionState(SessionPhase.RUNNING) else this
    fun finish(outcome: PracticeOutcome) = if (outcome == PracticeOutcome.CONFIRMED && !canConfirm)
        this else SessionState()
}
