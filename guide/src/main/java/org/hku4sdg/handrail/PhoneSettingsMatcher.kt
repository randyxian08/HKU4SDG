package org.hku4sdg.handrail

/** Only visible, adjustable controls with unambiguous Settings labels can become targets. */
data class SettingsUiNode(
    val text: String = "", val description: String = "", val id: String = "",
    val parent: Int = -1, val visible: Boolean = true, val enabled: Boolean = true,
    val clickable: Boolean = false, val adjustable: Boolean = false,
    val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0,
    val checkable: Boolean = false
)

data class SettingsUiMatch(val step: GuideStep, val index: Int?, val label: String, val controlPage: Boolean)

object PhoneSettingsMatcher {
    private enum class Kind { FONT, WEIGHT, DISPLAY, BRIGHTNESS, UNRELATED, OTHER }
    private fun norm(value: String) = value.trim().lowercase().replace(Regex("[\\s、，&]+"), "")
    private val fontNames = setOf("字体大小", "字體大小", "字型大小", "字号", "字號", "font size").map(::norm).toSet()
    private val fontPages = (fontNames + setOf("字体大小与粗细", "字体大小和粗细", "字体与显示大小",
        "显示大小和文字", "显示大小与文字", "显示大小和文本", "display size and text").map(::norm)).toSet()
    private val brightnessNames = setOf("亮度", "屏幕亮度", "显示亮度", "亮度级别", "brightness", "brightness level", "display brightness").map(::norm).toSet()
    private val displayPages = setOf("显示、亮度与护眼", "显示与亮度", "显示和亮度", "显示与触摸", "显示和触摸", "显示和触控", "显示与触控", "显示", "display", "display & touch").map(::norm).toSet()
    private val automaticNames = setOf("自动亮度", "自動亮度", "自动调节屏幕亮度", "自動調節螢幕亮度",
        "自动调节亮度", "自動調節亮度", "adaptive brightness", "automatic brightness", "auto brightness").map(::norm).toSet()

    private fun kind(value: String): Kind {
        val key = norm(value.substringBefore(',').substringBefore('，').substringBefore(':').substringBefore('：'))
        return when (key) {
            in fontNames -> Kind.FONT
            in brightnessNames -> Kind.BRIGHTNESS
            "字体粗细", "字體粗細", "fontweight", "fontboldness" -> Kind.WEIGHT
            "显示大小", "顯示大小", "displaysize" -> Kind.DISPLAY
            else -> Kind.OTHER
        }
    }
    private fun labels(node: SettingsUiNode) = listOf(node.text, node.description).filter(String::isNotBlank)
    fun safeLabel(value: String): String {
        val base = value.substringBefore(',').substringBefore('，').substringBefore(':').substringBefore('：').trim()
        return if (norm(base) in fontPages + brightnessNames + displayPages + automaticNames || kind(base) != Kind.OTHER) base else ""
    }
    private fun name(wanted: Kind, nodes: List<SettingsUiNode>): String = nodes.filter { it.visible }
        .flatMap(::labels).firstOrNull { kind(it) == wanted }?.let(::safeLabel)
        ?: if (wanted == Kind.FONT) "字体大小 / Font size" else "亮度 / Brightness"
    private fun clickableAncestor(index: Int, nodes: List<SettingsUiNode>): Int? {
        var current = index
        val seen = mutableSetOf<Int>()
        while (current in nodes.indices && seen.add(current)) {
            if (nodes[current].clickable && nodes[current].enabled && nodes[current].visible &&
                nodes[current].right > nodes[current].left && nodes[current].bottom > nodes[current].top) return current
            current = nodes[current].parent
        }
        return null
    }
    private fun descendant(index: Int, ancestor: Int, nodes: List<SettingsUiNode>): Boolean {
        var current = index
        val seen = mutableSetOf<Int>()
        while (current in nodes.indices && seen.add(current)) {
            if (current == ancestor) return true
            current = nodes[current].parent
        }
        return false
    }
    private fun controlKind(index: Int, nodes: List<SettingsUiNode>, density: Float): Kind {
        val node = nodes[index]
        val id = node.id.substringAfterLast('/').lowercase()
        if (listOf("weight", "bold", "thickness").any(id::contains)) return Kind.WEIGHT
        if (id.contains("display_size")) return Kind.DISPLAY
        if (listOf("font_size", "fontsize", "font_scale", "text_size").any(id::contains)) return Kind.FONT
        if (id.contains("brightness")) return if (listOf("auto", "adaptive", "hdr", "extra", "temperature").none(id::contains))
            Kind.BRIGHTNESS else Kind.UNRELATED
        labels(node).map(::kind).firstOrNull { it != Kind.OTHER }?.let { return it }
        var ancestor = node.parent
        for (depth in 0..2) {
            if (ancestor !in nodes.indices) break
            val kinds = nodes.indices.filter { nodes[it].visible && descendant(it, ancestor, nodes) }
                .flatMap { labels(nodes[it]) }.map(::kind).filter { it != Kind.OTHER }.toSet()
            if (kinds.size == 1) return kinds.single()
            if (kinds.size > 1) break
            ancestor = nodes[ancestor].parent
        }
        // Some OEMs place the field label beside the slider rather than inside its container.
        // Competing labels (including weight/display size) participate in the same comparison.
        val preceding = nodes.indices.filter { i ->
            val n = nodes[i]
            n.visible && labels(n).any { kind(it) != Kind.OTHER } && n.bottom <= node.top &&
                n.right > node.left && n.left < node.right && n.bottom > 0 &&
                node.top - n.bottom <= 180 * density.coerceAtLeast(1f)
        }.groupBy { nodes[it].bottom }.maxByOrNull { it.key }?.value.orEmpty()
        val kinds = preceding.flatMap { labels(nodes[it]) }.map(::kind).filter { it != Kind.OTHER }.toSet()
        return kinds.singleOrNull() ?: Kind.OTHER
    }
    private fun ranges(nodes: List<SettingsUiNode>) = nodes.indices.filter { i ->
        val n = nodes[i]
        n.visible && n.enabled && n.adjustable && n.right > n.left && n.bottom > n.top
    }
    private fun targets(kind: Kind, nodes: List<SettingsUiNode>, density: Float) = ranges(nodes)
        .filter { controlKind(it, nodes, density) == kind }
        .distinctBy { listOf(nodes[it].left, nodes[it].top, nodes[it].right, nodes[it].bottom) }
    private fun staticTitle(names: Set<String>, nodes: List<SettingsUiNode>) = nodes.indices.firstOrNull { i ->
        nodes[i].visible && labels(nodes[i]).any { norm(it) in names } && clickableAncestor(i, nodes) == null
    }
    private fun row(names: Set<String>, nodes: List<SettingsUiNode>): Pair<Int, String>? {
        val rows = nodes.indices.filter { nodes[it].visible && labels(nodes[it]).any { v -> norm(v) in names } }
            .mapNotNull { i -> clickableAncestor(i, nodes)?.let { it to labels(nodes[i]).first { v -> norm(v) in names } } }
            .distinctBy { it.first }
        return rows.singleOrNull()
    }
    fun font(nodes: List<SettingsUiNode>, density: Float = 1f): SettingsUiMatch? {
        val sliders = targets(Kind.FONT, nodes, density)
        if (sliders.size > 1) return null
        val title = staticTitle(fontPages, nodes)
        if (sliders.size == 1) return SettingsUiMatch(GuideStep.SLIDER, sliders.single(), name(Kind.FONT, nodes), true)
        // A single unlabeled adjustable control is accepted only on an exact Font size page,
        // with no weight/display-size fields and no conflicting semantic control IDs.
        val raw = ranges(nodes)
        if (title != null && labels(nodes[title]).any { norm(it) in fontNames } && raw.size == 1 &&
            controlKind(raw.single(), nodes, density) == Kind.OTHER &&
            nodes.filter { it.visible }.flatMap(::labels).none { kind(it) in setOf(Kind.WEIGHT, Kind.DISPLAY) })
            return SettingsUiMatch(GuideStep.SLIDER, raw.single(), labels(nodes[title]).first(), true)
        if (title != null) return SettingsUiMatch(GuideStep.SLIDER_SCROLL, null, "字体大小 / Font size", true)
        val row = row(fontPages, nodes) ?: return if (staticTitle(displayPages, nodes) != null)
            SettingsUiMatch(GuideStep.DISPLAY_SCROLL, null, "字体大小 / Font size", false) else null
        return SettingsUiMatch(GuideStep.DISPLAY_SIZE, row.first, row.second, false)
    }
    fun brightness(nodes: List<SettingsUiNode>, density: Float = 1f): SettingsUiMatch? {
        val sliders = targets(Kind.BRIGHTNESS, nodes, density)
        if (sliders.size > 1) return null
        if (sliders.size == 1) return SettingsUiMatch(GuideStep.BRIGHTNESS_SLIDER, sliders.single(), name(Kind.BRIGHTNESS, nodes), true)
        val row = row(brightnessNames, nodes)
        if (row != null && staticTitle(displayPages + brightnessNames, nodes) != null)
            return SettingsUiMatch(GuideStep.BRIGHTNESS_ROW, row.first, row.second, false)
        return null
    }
    fun automaticBrightness(nodes: List<SettingsUiNode>): SettingsUiMatch? {
        val labels = nodes.indices.filter { nodes[it].visible && PhoneSettingsMatcher.labels(nodes[it]).any { norm(it) in automaticNames } }
        val switches = labels.flatMap { label ->
            if (nodes[label].checkable) listOf(label) else {
                val row = clickableAncestor(label, nodes) ?: nodes[label].parent
                if (row !in nodes.indices) emptyList() else nodes.indices.filter {
                    nodes[it].checkable && descendant(it, row, nodes)
                }
            }
        }.filter { nodes[it].visible && nodes[it].enabled && nodes[it].right > nodes[it].left && nodes[it].bottom > nodes[it].top }
            .distinctBy { listOf(nodes[it].left, nodes[it].top, nodes[it].right, nodes[it].bottom) }
        if (switches.size > 1) return null
        if (switches.size == 1) return SettingsUiMatch(GuideStep.BRIGHTNESS_AUTO, switches.single(), "自动亮度 / Adaptive brightness", true)
        return if (staticTitle(displayPages, nodes) != null)
            SettingsUiMatch(GuideStep.BRIGHTNESS_AUTO_SCROLL, null, "自动亮度 / Adaptive brightness", false) else null
    }
}
