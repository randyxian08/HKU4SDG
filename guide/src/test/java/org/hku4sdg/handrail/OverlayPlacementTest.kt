package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    @Test fun placementAvoidsBothTargetAndSystemBarsAcrossTargetLocations() {
        for (target in 100..2100 step 40) {
            val p = OverlayPlacement.place(80, 2300, target, target + 130, 30, 800, 240)!!
            assertTrue(p.top >= 80 && p.top + p.height <= 2300)
            assertTrue(p.top + p.height <= target - 30 || p.top >= target + 160)
        }
    }
    @Test fun preferredSideIsHonouredOnlyWhenFixedControlsCanFit() {
        assertEquals(OverlaySide.TOP, OverlayPlacement.place(80, 2300, 1200, 1350, 30, 700, 240, OverlaySide.TOP)!!.side)
        assertEquals(OverlaySide.BOTTOM, OverlayPlacement.place(80, 2300, 130, 260, 30, 700, 240, OverlaySide.TOP)!!.side)
    }
    @Test fun impossiblePlacementDoesNotCoverTargetAndTallContentIsCapped() {
        assertNull(OverlayPlacement.place(80, 2300, 100, 2250, 30, 700, 240))
        val p = OverlayPlacement.place(80, 2300, 1000, 1400, 30, 1600, 240)!!
        assertTrue(p.height < 1600)
    }
}
