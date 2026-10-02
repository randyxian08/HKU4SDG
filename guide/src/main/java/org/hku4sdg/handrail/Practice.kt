package org.hku4sdg.handrail

enum class HelpMode(val label: String, val description: String) {
    FULL("完整指引", "圈画、文字和可选播报，一步一步陪你做。"),
    LIGHT("轻提示", "先看圈画；需要时展开文字或再听一遍。"),
    ON_DEMAND("自己先试试", "先不圈画；随时点“给我提示”恢复帮助。")
}

enum class PracticeOutcome { CONFIRMED, ENDED }

/** No goal text, page content, audio, or screenshots are part of this record. */
data class PracticeRecord(
    val session: Int, val task: GuideTask, val mode: HelpMode,
    val outcome: PracticeOutcome, val timestamp: Long, val durationSeconds: Long
)

object PracticeLedger {
    const val LIMIT = 20
    fun append(records: List<PracticeRecord>, record: PracticeRecord): List<PracticeRecord> =
        if (records.any { it.session == record.session }) records
        else (listOf(record) + records).take(LIMIT)

    fun encode(records: List<PracticeRecord>): String = records.joinToString("\n") {
        "${it.session}|${it.task.id}|${it.mode.name}|${it.outcome.name}|${it.timestamp}|${it.durationSeconds}"
    }

    fun decode(value: String): List<PracticeRecord> = value.lineSequence().mapNotNull { line ->
        runCatching {
            val fields = line.split('|')
            require(fields.size == 6)
            val session = fields[0].toInt().also { require(it > 0) }
            val task = requireNotNull(GuideTask.fromId(fields[1]))
            val time = fields[4].toLong().also { require(it > 0) }
            val duration = fields[5].toLong().also { require(it in 0..86_400) }
            PracticeRecord(session, task, HelpMode.valueOf(fields[2]),
                PracticeOutcome.valueOf(fields[3]), time, duration)
        }.getOrNull()
    }.distinctBy { it.session }.take(LIMIT).toList()
}
