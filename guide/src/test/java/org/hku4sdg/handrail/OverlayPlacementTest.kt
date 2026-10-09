package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    @Test fun fontPreviewAndSliderAreBothProtected() {
        val protected = listOf(ProtectedRegion(780, 850), ProtectedRegion(180, 660))
        val p = OverlayPlacement.placeAvoiding(80, 1280, protected, 12, 240, 180)!!
        protected.forEach { assertTrue(p.top + p.height <= it.top - 12 || p.top >= it.bottom + 12) }
        assertTrue(p.top >= 862)
    }
    @Test fun smallCoordinateChangesDoNotMoveAStableCardOrOverlapAnyProtectedArea() {
        val p = OverlayPlacement.placeAvoiding(80, 1280, listOf(ProtectedRegion(400, 480)), 12, 220, 180)!!
        for (shift in -25..25) {
            val moved = OverlayPlacement.placeAvoiding(80, 1280, listOf(ProtectedRegion(400 + shift, 480 + shift)),
                12, 220, 180, previousTop = p.top)!!
            assertEquals(p.top, moved.top)
        }
    }
    @Test fun overlappingRegionsAndInsufficientSpaceNeverCoverPreview() {
        assertNull(OverlayPlacement.placeAvoiding(80, 1280,
            listOf(ProtectedRegion(100, 600), ProtectedRegion(550, 1260)), 12, 240, 180))
    }
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
