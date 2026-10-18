package org.hku4sdg.handrail

import java.security.MessageDigest

data class ScreenNode(val id: String, val label: String, val role: String,
    val clickable: Boolean, val editable: Boolean, val scrollable: Boolean, val enabled: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val state: String = "")

data class ScreenSnapshot(val packageName: String, val nodes: List<ScreenNode>, val windowId: Int = 0) {
    val id: String get() = MessageDigest.getInstance("SHA-256").digest(
        (packageName + "/" + windowId + "\n" + nodes.joinToString("\n")).toByteArray()).joinToString("") { "%02x".format(it) }
}

data class ScreenAdvice(val snapshotId: String, val action: String, val nodeId: String,
    val instruction: String, val spokenInstruction: String)

/** Model advice can only refer to an enabled control in this exact live snapshot. */
object ScreenGuidePolicy {
    private val privateText = Regex("密码(?!管理器|管理工具)|验证码|银行卡|身份证|付款|支付|转账|病历|私信|聊天记录|\\bpassword\\b(?!\\s+(?:manager|store|generator)\\b)|verification code|one.time code|credit card|bank account|payment|checkout|medical record|private message", RegexOption.IGNORE_CASE)
    private val personal = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+|\\b\\d{6,}\\b")
    private val risky = Regex("删除|清空|卸载|安装|重置|发送|提交|购买|订阅|登录|授权|delete|erase|uninstall|reset|send|submit|purchase|subscribe|sign.in|log.in|permission|install|\\b(?:buy|pay|grant)\\b", RegexOption.IGNORE_CASE)
    fun privateText(value: String) = privateText.containsMatchIn(value) || personal.containsMatchIn(value)
    fun risky(value: String) = risky.containsMatchIn(value)
    fun target(snapshot: ScreenSnapshot, advice: ScreenAdvice): ScreenNode? {
        if (snapshot.id != advice.snapshotId) return null
        val node = snapshot.nodes.singleOrNull { it.id == advice.nodeId } ?: return null
        if (!node.enabled || risky(node.label) || privateText(node.label) || node.left >= node.right || node.top >= node.bottom) return null
        return node.takeIf { when (advice.action) {
            "tap" -> it.clickable && !(it.editable && ";focused=true;" in ";${it.state};")
            "type" -> it.editable && ";focused=true;" in ";${it.state};"
            "scroll_forward", "scroll_backward" -> it.scrollable
            else -> false
        } }
    }
}

/** The goal, screen data and AI reply live only in this process, never in preferences. */
object ScreenGuideSession {
    data class Run(val id: Int, val goal: String, val packageName: String, val appName: String,
        val includeImage: Boolean, var paused: Boolean = false)
    private var nextId = 0
    var current: Run? = null
        private set
    fun start(goal: String, packageName: String, appName: String, includeImage: Boolean) {
        current = Run(++nextId, goal, packageName, appName, includeImage)
    }
    fun stop() { current = null }
}

object ScreenGuideCopy {
    fun choose(l: GuideLanguage, zh: String, yue: String, en: String) = ExperienceCopy.choose(l, zh, yue, en)
    fun title(l: GuideLanguage) = choose(l, "AI 屏幕引导", "AI 屏幕指引", "AI screen guide")
    fun thinking(l: GuideLanguage) = choose(l, "正在看当前页面，寻找下一步…", "睇紧而家嘅页面，搵下一步…", "Looking at this page for the next step…")
    fun privatePage(l: GuideLanguage) = choose(l, "这个页面可能含私人资料或敏感操作，已停止发送和圈画。请离开该页面后再试。", "呢页可能有私人资料或敏感操作，停止发送同圈画。请离开呢页再试。", "This page may contain private information or sensitive actions. Nothing was sent. Leave this page before retrying.")
    fun unavailable(l: GuideLanguage) = choose(l, "这次没能取得可靠建议，已撤下圈画。可以重试、补充目标，或结束引导。", "今次攞唔到可靠建议，已经收起圈画。可以再试、补充目标，或者结束指引。", "No reliable advice was received. Highlighting stopped. Retry, refine your goal, or end the guide.")
}
