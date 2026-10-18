package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class ScreenGuidePolicyTest {
    private val node = ScreenNode("n0", "Settings", "Button", true, false, false, true, 10, 20, 100, 80)
    private val page = ScreenSnapshot("org.example.reader", listOf(node))
    private fun advice(action: String = "tap", id: String = "n0") = ScreenAdvice(page.id, action, id, "Open Settings", "Open Settings")
    @Test fun acceptsCurrentVisibleActionableNodeWithoutAnAppAllowlist() {
        assertEquals(node, ScreenGuidePolicy.target(page, advice()))
    }
    @Test fun movedRenamedRemovedOrReplacedNodesInvalidateAdvice() {
        for (changed in listOf(page.copy(packageName = "org.other.app"), page.copy(windowId = 99), page.copy(nodes = emptyList()),
            page.copy(nodes = listOf(node.copy(top = 22))), page.copy(nodes = listOf(node.copy(label = "Delete"))))) {
            assertNull(ScreenGuidePolicy.target(changed, advice()))
        }
    }
    @Test fun forgedDisabledAndWrongKindTargetsNeverBecomeCircles() {
        assertNull(ScreenGuidePolicy.target(page, advice(id = "n999")))
        assertNull(ScreenGuidePolicy.target(page, advice("type")))
        assertNull(ScreenGuidePolicy.target(page, advice("scroll_forward")))
        val disabled = page.copy(nodes = listOf(node.copy(enabled = false)))
        assertNull(ScreenGuidePolicy.target(disabled, advice().copy(snapshotId = disabled.id)))
    }
    @Test fun riskyAndPrivateTargetsAreRejectedEvenIfTheModelSelectsThem() {
        for (label in listOf("Delete all files", "购买", "安装", "Sign in", "user@example.com", "Password", "12345678")) {
            val changed = page.copy(nodes = listOf(node.copy(label = label)))
            assertNull(ScreenGuidePolicy.target(changed, advice().copy(snapshotId = changed.id)))
        }
    }
    @Test fun completeAndClarifyNeverHaveActionTargets() {
        assertNull(ScreenGuidePolicy.target(page, advice("complete")))
        assertNull(ScreenGuidePolicy.target(page, advice("clarify")))
    }
    @Test fun focusedEditorGetsTypingAdviceInsteadOfEndlessNoOpTaps() {
        val focused = page.copy(nodes = listOf(node.copy(editable = true, state = "focused=true;has_text=false")))
        assertNull(ScreenGuidePolicy.target(focused, advice().copy(snapshotId = focused.id)))
        assertNotNull(ScreenGuidePolicy.target(focused, advice("type").copy(snapshotId = focused.id)))
        val unfocused = focused.copy(nodes = listOf(focused.nodes[0].copy(state = "focused=false")))
        assertNull(ScreenGuidePolicy.target(unfocused, advice("type").copy(snapshotId = unfocused.id)))
        assertNotNull(ScreenGuidePolicy.target(unfocused, advice().copy(snapshotId = unfocused.id)))
    }
    @Test fun publicAppNameIsNotTheSameAsAPasswordEntryField() {
        assertFalse(ScreenGuidePolicy.privateText("AIPOS Password Manager"))
        assertTrue(ScreenGuidePolicy.privateText("Password"))
        assertTrue(ScreenGuidePolicy.privateText("Enter your password"))
    }
    @Test fun newSessionHasIndependentIdentityAndEndRemovesGoal() {
        ScreenGuideSession.start("Find settings", "org.example", "Example", false)
        val first = ScreenGuideSession.current!!.id
        ScreenGuideSession.stop(); assertNull(ScreenGuideSession.current)
        ScreenGuideSession.start("Find timer", "org.clock", "Clock", true)
        assertTrue(ScreenGuideSession.current!!.id > first)
        assertEquals("org.clock", ScreenGuideSession.current!!.packageName)
        ScreenGuideSession.stop()
    }
}
