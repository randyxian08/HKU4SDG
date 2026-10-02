package org.hku4sdg.handrail

/** App chrome and task previews. Text language never changes recognition or voice language. */
object AppCopy {
    private fun c(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun shortTask(task: GuideTask, l: GuideLanguage) = when (task) {
        GuideTask.FONT_SIZE -> SupportCopy.task(task, l)
        GuideTask.SCREEN_BRIGHTNESS -> c(l, "调亮屏幕", "调光个画面", "Brighten the screen")
        GuideTask.CHROME_BOOKMARK -> c(l, "收藏长者服务网页", "收藏长者服务网页", "Save the services page")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "查图书馆地址", "查图书馆地址", "Find the library address")
    }
    fun app(task: GuideTask, l: GuideLanguage) = when (task) {
        GuideTask.CHROME_BOOKMARK -> "Chrome"
        GuideTask.MAPS_LIBRARY_ADDRESS -> "Google Maps"
        else -> c(l, "手机设置", "手机设置", "Settings")
    }
    fun time(l: GuideLanguage) = c(l, "约 1 分钟", "约 1 分钟", "About 1 min")
    fun intro(task: GuideTask, l: GuideLanguage) = when (task) {
        GuideTask.FONT_SIZE -> c(l, "让手机上的字更容易看清。我们指出下一步，你自己点击和拖动。", "等手机啲字易啲睇清。我哋指出下一步，你自己㩒同拖。", "Make text easier to read. We show the next step; you make every tap and drag.")
        GuideTask.SCREEN_BRIGHTNESS -> c(l, "把屏幕调到看着舒服的亮度。每一步都由你自己操作。", "将个画面调到睇得舒服嘅光度。每一步都由你自己做。", "Find a comfortable screen brightness. You make each adjustment yourself.")
        GuideTask.CHROME_BOOKMARK -> c(l, "保存政府一站通的长者服务网页，下次更容易找到。", "储存政府一站通嘅长者服务网页，下次易啲搵返。", "Save GovHK’s elderly services page so you can find it again.")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "找到香港中央图书馆的地址，不需要提供你的位置。", "搵香港中央图书馆嘅地址，唔使提供你嘅位置。", "Find Hong Kong Central Library’s address without sharing your location.")
    }
    fun steps(task: GuideTask, l: GuideLanguage): List<String> = when(task) {
        GuideTask.FONT_SIZE -> listOf(c(l, "打开 Display size and text", "打开 Display size and text", "Open Display size and text"), c(l, "拖动 Font size，检查预览", "拖动 Font size，睇下预览", "Adjust Font size and check the preview"))
        GuideTask.SCREEN_BRIGHTNESS -> listOf(c(l, "打开 Brightness level", "打开 Brightness level", "Open Brightness level"), c(l, "调整亮度，确认是否舒服", "调光度，确认睇得舒唔舒服", "Adjust brightness and check your comfort"))
        GuideTask.CHROME_BOOKMARK -> listOf(c(l, "打开右上角菜单", "打开右上角菜单", "Open the top-right menu"), c(l, "点 Bookmark 星标收藏", "㩒 Bookmark 星标收藏", "Tap the Bookmark star"), c(l, "再开菜单，核对收藏结果", "再开菜单，核对收藏结果", "Reopen the menu to check the bookmark"))
        GuideTask.MAPS_LIBRARY_ADDRESS -> listOf(c(l, "打开中央图书馆地点详情", "打开中央图书馆地点详情", "Open the Central Library place details"), c(l, "查看圈出的地址行", "睇圈住嘅地址行", "Read the highlighted address"))
    }
    fun effect(task: GuideTask, l: GuideLanguage) = when(task) {
        GuideTask.FONT_SIZE -> c(l, "会实际改变系统字体大小", "会改变系统字体大小", "Changes the system text size")
        GuideTask.SCREEN_BRIGHTNESS -> c(l, "会实际改变屏幕亮度", "会改变屏幕光度", "Changes the screen brightness")
        GuideTask.CHROME_BOOKMARK -> c(l, "会在 Chrome 保存公开网页书签", "会喺 Chrome 储存公开网页书签", "Saves a public page bookmark in Chrome")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "只查看公开地点，不开始导航", "只睇公开地点，唔开始导航", "Opens a public place; does not start navigation")
    }
    fun start(task: GuideTask, l: GuideLanguage) = when (task) {
        GuideTask.CHROME_BOOKMARK -> c(l, "打开 Chrome，开始", "打开 Chrome，开始", "Open Chrome and start")
        GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "打开地图，开始", "打开地图，开始", "Open Maps and start")
        else -> c(l, "打开设置，开始", "打开设置，开始", "Open Settings and start")
    }
    fun scope(task: GuideTask, l: GuideLanguage): String {
        val entry = TaskCatalog.entry(task)
        val device = "Android 15 · Pixel 7 · " + c(l, "英文系统页面", "英文系统页面", "English system pages")
        val version = entry.appVersion?.let { "\n${app(task, l)} $it" } ?: ""
        val common = c(l, "页面变化或无法识别时会撤圈并停播。", "页面变咗或者认唔到时会撤圈同停播。", "Highlights and speech stop when the page changes or cannot be recognized.")
        val note = when (task) {
            GuideTask.CHROME_BOOKMARK -> c(l, "仅支持指定 GovHK 长者网页；这不是政府合作服务。", "只支持指定 GovHK 长者网页；呢个唔系政府合作服务。", "Only the reviewed GovHK elderly page is supported. This is not a government partnership.")
            GuideTask.MAPS_LIBRARY_ADDRESS -> c(l, "仅支持中央图书馆地点卡；地址来自地图，出行前请核对。", "只支持中央图书馆地点卡；地址来自地图，出发前请核对。", "Only the Central Library place is supported. The address comes from Maps; check before travelling.")
            else -> c(l, "模拟器为本次演示设备。", "模拟器系今次演示设备。", "The emulator is the device for this demo.")
        }
        return "$device$version\n$common $note"
    }
}
