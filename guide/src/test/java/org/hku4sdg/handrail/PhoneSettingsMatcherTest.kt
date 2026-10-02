package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

/** Synthetic control structures exercise ambiguity; they are not vivo device evidence. */
class PhoneSettingsMatcherTest {
    private fun range(id: String = "", parent: Int = -1, y: Int = 120, visible: Boolean = true) =
        SettingsUiNode(id = id, parent = parent, visible = visible, adjustable = true,
            left = 20, top = y, right = 300, bottom = y + 40)
    @Test fun selectsFontSizeRatherThanWeightOrDisplaySize() {
        val nodes = listOf(SettingsUiNode(text = "字体大小与粗细"),
            range("com.android.settings:id/font_size"), range("com.android.settings:id/font_weight", y = 240),
            range("com.android.settings:id/display_size", y = 360))
        val match = PhoneSettingsMatcher.font(nodes)!!
        assertEquals(GuideStep.SLIDER, match.step)
        assertEquals(1, match.index)
        assertTrue(match.controlPage)
    }
    @Test fun uniqueFieldLabelInContainerCanIdentifyAnOemControl() {
        val nodes = listOf(SettingsUiNode(text = "字体大小与粗细"), SettingsUiNode(parent = 0),
            SettingsUiNode(text = "字体大小", parent = 1), range(parent = 1),
            SettingsUiNode(parent = 0), SettingsUiNode(text = "字体粗细", parent = 4), range(parent = 4, y = 240))
        assertEquals(3, PhoneSettingsMatcher.font(nodes)!!.index)
    }
    @Test fun proximityUsesTheNearestFieldLabelAndRejectsFarAwayLabels() {
        val nodes = listOf(SettingsUiNode(text = "字体大小", left = 20, top = 70, right = 300, bottom = 100),
            range(), SettingsUiNode(text = "字体粗细", left = 20, top = 190, right = 300, bottom = 220), range(y = 240))
        assertEquals(1, PhoneSettingsMatcher.font(nodes)!!.index)
        val far = nodes.take(1) + range(y = 900) + range("font_weight", y = 1100)
        assertNull(PhoneSettingsMatcher.font(far)!!.index)
    }
    @Test fun duplicateFontControlsAndHiddenOrDisabledRangesAreNotCircled() {
        assertNull(PhoneSettingsMatcher.font(listOf(SettingsUiNode(text = "字体大小"),
            range("font_size"), range("font_size", y = 240))))
        val hidden = PhoneSettingsMatcher.font(listOf(SettingsUiNode(text = "字体大小"), range("font_size", visible = false)))!!
        assertNull(hidden.index)
        assertEquals(GuideStep.SLIDER_SCROLL, hidden.step)
        assertNull(PhoneSettingsMatcher.brightness(listOf(range("brightness").copy(enabled = false))))
    }
    @Test fun displayOverviewSelectsTheClickableFontRowAndUsesItsLabel() {
        val nodes = listOf(SettingsUiNode(text = "显示、亮度与护眼"),
            SettingsUiNode(parent = 0, clickable = true, left = 0, top = 300, right = 320, bottom = 370),
            SettingsUiNode(text = "字体大小与粗细", parent = 1))
        val match = PhoneSettingsMatcher.font(nodes)!!
        assertEquals(GuideStep.DISPLAY_SIZE, match.step)
        assertEquals(1, match.index)
        assertEquals("字体大小与粗细", match.label)
        assertFalse(match.controlPage)
    }
    @Test fun brightnessCannotChooseAutomaticOrHdrControlsOrUnlabeledSoundSliders() {
        val nodes = listOf(range("brightness"), range("adaptive_brightness", y = 240), range("hdr_brightness", y = 360))
        assertEquals(0, PhoneSettingsMatcher.brightness(nodes)!!.index)
        assertNull(PhoneSettingsMatcher.brightness(listOf(SettingsUiNode(text = "音量"), range("media_volume"))))
        assertNull(PhoneSettingsMatcher.brightness(listOf(range("brightness"), range("brightness", y = 240))))
    }
    @Test fun compatibilityExportIncludesOnlyWhitelistedLabels() {
        assertEquals("字体大小", PhoneSettingsMatcher.safeLabel("字体大小，4"))
        assertEquals("显示、亮度与护眼", PhoneSettingsMatcher.safeLabel("显示、亮度与护眼"))
        listOf("Volatura 的 vivo X300s", "13812345678", "个人账号", "银行卡", "张三").forEach {
            assertEquals("", PhoneSettingsMatcher.safeLabel(it))
        }
    }
    @Test fun brightnessPageTitleWithoutARecognizedControlDoesNotOfferACircledSlider() {
        assertNull(PhoneSettingsMatcher.brightness(listOf(SettingsUiNode(text = "亮度"))))
    }
    @Test fun excludedBrightnessControlsCannotInheritANearbyBrightnessOrFontLabel() {
        val label = SettingsUiNode(text = "亮度", left = 20, top = 70, right = 300, bottom = 100)
        assertNull(PhoneSettingsMatcher.brightness(listOf(label, range("hdr_brightness"))))
        assertNull(PhoneSettingsMatcher.font(listOf(SettingsUiNode(text = "字体大小"), range("adaptive_brightness")))!!.index)
    }
}
