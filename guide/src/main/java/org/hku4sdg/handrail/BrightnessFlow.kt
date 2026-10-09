package org.hku4sdg.handrail

enum class BrightnessGoal { MANUAL, ADAPTIVE }
enum class BrightnessAction { TURN_AUTO_ON, TURN_AUTO_OFF, ADJUST, READY, UNKNOWN }

/** A device setting, not the model, decides which action is currently needed. */
object BrightnessFlow {
    fun action(goal: BrightnessGoal, automatic: Boolean?, increased: Boolean): BrightnessAction = when {
        automatic == null -> BrightnessAction.UNKNOWN
        goal == BrightnessGoal.ADAPTIVE -> if (automatic) BrightnessAction.READY else BrightnessAction.TURN_AUTO_ON
        automatic -> BrightnessAction.TURN_AUTO_OFF
        increased -> BrightnessAction.READY
        else -> BrightnessAction.ADJUST
    }
}
