package org.hku4sdg.handrail

/** Vertical placement in physical screen coordinates. Never cover the reviewed target. */
enum class OverlaySide { TOP, BOTTOM }
data class OverlayPosition(val top: Int, val height: Int, val side: OverlaySide)
data class ProtectedRegion(val top: Int, val bottom: Int)
object OverlayPlacement {
    /** Protect the control and its feedback; keep the card still while the same step scrolls. */
    fun placeAvoiding(safeTop: Int, safeBottom: Int, regions: List<ProtectedRegion>, gap: Int,
        desiredHeight: Int, minimumHeight: Int, preferred: OverlaySide? = null,
        previousTop: Int? = null): OverlayPosition? {
        val blocked = regions.filter { it.bottom > safeTop && it.top < safeBottom && it.bottom > it.top }
            .map { ProtectedRegion((it.top - gap).coerceAtLeast(safeTop), (it.bottom + gap).coerceAtMost(safeBottom)) }
            .sortedBy { it.top }
        val free = mutableListOf<ProtectedRegion>()
        var cursor = safeTop
        blocked.forEach { if (it.top > cursor) free += ProtectedRegion(cursor, it.top); cursor = maxOf(cursor, it.bottom) }
        if (cursor < safeBottom) free += ProtectedRegion(cursor, safeBottom)
        val candidates = free.filter { it.bottom - it.top >= minimumHeight }
        if (candidates.isEmpty()) return null
        val anchor = regions.firstOrNull()?.top ?: ((safeTop + safeBottom) / 2)
        fun side(r: ProtectedRegion) = if (r.bottom <= anchor) OverlaySide.TOP else OverlaySide.BOTTOM
        val sticky = previousTop?.let { top -> candidates.firstOrNull { top >= it.top && top + desiredHeight <= it.bottom } }
        val chosen = candidates.firstOrNull { side(it) == preferred }
            ?: sticky ?: candidates.lastOrNull { it.bottom - it.top >= desiredHeight }
            ?: candidates.maxBy { it.bottom - it.top }
        val height = desiredHeight.coerceAtMost(chosen.bottom - chosen.top)
        val top = if (chosen == sticky) previousTop!! else if (side(chosen) == OverlaySide.TOP) chosen.top else chosen.bottom - height
        return OverlayPosition(top, height, side(chosen))
    }
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
