package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class TaskCatalogTest {
    private val reviewed = DeviceProfile(35, "ranchu", "sdk_gphone64_arm64", "en", mapOf(
        "com.android.settings" to "15", "com.android.chrome" to "124.0.6367.219",
        "com.google.android.apps.maps" to "11.129.0103"))
    @Test fun everyTaskHasExactlyOneReviewAndWorksOnTheReviewedProfile() {
        assertEquals(GuideTask.entries.toSet(), TaskCatalog.tasks.map { it.task }.toSet())
        assertEquals(GuideTask.entries.size, TaskCatalog.tasks.size)
        GuideTask.entries.forEach { assertNull(TaskCatalog.block(it, reviewed)) }
    }
    @Test fun changedPatchVersionOrMissingAppCannotStartThirdPartyGuide() {
        assertEquals(TaskBlock.VERSION, TaskCatalog.block(GuideTask.CHROME_BOOKMARK,
            reviewed.copy(installedVersions = reviewed.installedVersions + ("com.android.chrome" to "124.0.6367.220"))))
        assertEquals(TaskBlock.MISSING_APP, TaskCatalog.block(GuideTask.MAPS_LIBRARY_ADDRESS,
            reviewed.copy(installedVersions = reviewed.installedVersions - "com.google.android.apps.maps")))
        assertNull(TaskCatalog.block(GuideTask.FONT_SIZE,
            reviewed.copy(installedVersions = reviewed.installedVersions - "com.google.android.apps.maps")))
    }
    @Test fun thirdPartyGuidesStillRequireTheReviewedDeviceAndLanguage() {
        listOf(GuideTask.CHROME_BOOKMARK, GuideTask.MAPS_LIBRARY_ADDRESS).forEach { task ->
            assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(task, reviewed.copy(sdk = 36)))
            assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(task, reviewed.copy(model = "another model")))
            assertEquals(TaskBlock.LANGUAGE, TaskCatalog.block(task, reviewed.copy(systemLanguage = "zh")))
        }
    }
    @Test fun android16ChinesePhoneCanTryOnlyTheTwoSettingsGuides() {
        val phone = DeviceProfile(36, "synthetic_hardware", "synthetic_phone", "zh", mapOf("com.android.settings" to "16"))
        listOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS).forEach {
            assertNull(TaskCatalog.block(it, phone))
            assertTrue(TaskCatalog.experimental(it, phone))
            assertEquals(TaskBlock.MISSING_APP, TaskCatalog.block(it, phone.copy(installedVersions = emptyMap())))
            assertEquals(TaskBlock.LANGUAGE, TaskCatalog.block(it, phone.copy(systemLanguage = "fr")))
            assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(it, phone.copy(sdk = 25)))
            assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(it, phone.copy(sdk = 37)))
        }
        assertFalse(TaskCatalog.reviewedDevice(phone))
        assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(GuideTask.CHROME_BOOKMARK, phone))
        assertEquals(TaskBlock.SYSTEM, TaskCatalog.block(GuideTask.MAPS_LIBRARY_ADDRESS, phone))
        assertFalse(TaskCatalog.experimental(GuideTask.FONT_SIZE, reviewed))
    }
}
