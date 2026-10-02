package org.hku4sdg.handrail

/** Versioned review records. A match in this catalog does not replace live page checks. */
data class ReviewedTask(
    val task: GuideTask,
    val revision: Int,
    val packageName: String,
    val appVersion: String?,
    val reviewedOn: String,
    val source: String,
    val evidence: String,
    val prerequisites: String,
    val result: String
)

data class DeviceProfile(
    val sdk: Int,
    val hardware: String,
    val model: String,
    val systemLanguage: String,
    val installedVersions: Map<String, String>
)

enum class TaskBlock { SYSTEM, LANGUAGE, MISSING_APP, VERSION }

object TaskCatalog {
    val tasks = listOf(
        ReviewedTask(GuideTask.FONT_SIZE, 1, "com.android.settings", null, "2026-10-02",
            "https://developer.android.com/reference/android/provider/Settings#ACTION_DISPLAY_SETTINGS",
            "docs/screenshots/android15-oct2c-font-result.png", "font_scale < 1.99; Display size and text + Font size",
            "font_scale increases; user confirms reading comfort"),
        ReviewedTask(GuideTask.SCREEN_BRIGHTNESS, 1, "com.android.settings", null, "2026-10-02",
            "https://developer.android.com/reference/android/provider/Settings.System#SCREEN_BRIGHTNESS",
            "docs/screenshots/android15-oct2c-brightness-result.png", "manual brightness; value < 250; Brightness level + slider",
            "brightness increases; user confirms comfort"),
        ReviewedTask(GuideTask.CHROME_BOOKMARK, 1, "com.android.chrome", "124.0.6367.219", "2026-10-02",
            "https://www.gov.hk/tc/residents/housing/socialservices/elderly/index.htm",
            "docs/screenshots/android15-oct2c-chrome-result.png", "reviewed GovHK elderly page; matching URL/title + Chrome menu",
            "Edit bookmark or Bookmarked detected; user confirms"),
        ReviewedTask(GuideTask.MAPS_LIBRARY_ADDRESS, 1, "com.google.android.apps.maps", "11.129.0103", "2026-10-02",
            "https://www.google.com/maps/search/?api=1&query=Hong%20Kong%20Central%20Library",
            "docs/screenshots/android15-oct2c-maps-result.png", "exact Central Library place; matching name/address",
            "reviewed address row detected; user confirms seeing it")
    )
    fun entry(task: GuideTask) = tasks.single { it.task == task }
    fun reviewedDevice(profile: DeviceProfile) = profile.sdk == 35 &&
        profile.hardware in setOf("ranchu", "goldfish") && profile.model == "sdk_gphone64_arm64" &&
        profile.systemLanguage == "en"

    /** Phone Settings use runtime semantic checks; availability is not a device review claim. */
    fun experimental(task: GuideTask, profile: DeviceProfile) = !reviewedDevice(profile) &&
        task in setOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS)

    fun block(task: GuideTask, profile: DeviceProfile): TaskBlock? {
        if (task in setOf(GuideTask.FONT_SIZE, GuideTask.SCREEN_BRIGHTNESS)) {
            if (profile.sdk !in 26..36) return TaskBlock.SYSTEM
            if (profile.systemLanguage !in setOf("en", "zh")) return TaskBlock.LANGUAGE
        } else {
            if (profile.sdk != 35 || profile.hardware !in setOf("ranchu", "goldfish") ||
                profile.model != "sdk_gphone64_arm64") return TaskBlock.SYSTEM
            if (profile.systemLanguage != "en") return TaskBlock.LANGUAGE
        }
        val entry = entry(task)
        val version = profile.installedVersions[entry.packageName] ?: return TaskBlock.MISSING_APP
        if (entry.appVersion != null && version != entry.appVersion) return TaskBlock.VERSION
        return null
    }
}
