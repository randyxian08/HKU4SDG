package org.hku4sdg.handrail

/** Vertical placement in physical screen coordinates. Never cover the reviewed target. */
enum class OverlaySide { TOP, BOTTOM }
data class OverlayPosition(val top: Int, val height: Int, val side: OverlaySide)
object OverlayPlacement {
    fun place(safeTop: Int, safeBottom: Int, targetTop: Int?, targetBottom: Int?, gap: Int,
        desiredHeight: Int, minimumHeight: Int, preferred: OverlaySide? = null): OverlayPosition? {
        val topEnd = targetTop?.minus(gap)?.coerceIn(safeTop, safeBottom) ?: safeBottom
        val bottomStart = targetBottom?.plus(gap)?.coerceIn(safeTop, safeBottom) ?: safeTop
        val topRoom = topEnd - safeTop
        val bottomRoom = safeBottom - bottomStart
        val possible = listOf(OverlaySide.TOP to topRoom, OverlaySide.BOTTOM to bottomRoom)
            .filter { it.second >= minimumHeight }
        val choice = possible.firstOrNull { it.first == preferred }
            ?: possible.firstOrNull { it.first == OverlaySide.BOTTOM && it.second >= desiredHeight }
            ?: possible.maxByOrNull { it.second }
            ?: return null
        val height = desiredHeight.coerceAtMost(choice.second)
        return OverlayPosition(if (choice.first == OverlaySide.TOP) safeTop else safeBottom - height, height, choice.first)
    }
}
