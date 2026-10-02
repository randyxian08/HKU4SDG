package org.hku4sdg.handrail

import org.junit.Assert.*
import org.junit.Test

class PracticeLedgerTest {
    private fun record(id: Int) = PracticeRecord(id, GuideTask.FONT_SIZE, HelpMode.LIGHT,
        PracticeOutcome.CONFIRMED, 1_790_000_000_000, 42)

    @Test fun repeatsCannotDoubleCountAndOnlyTwentyAreRetained() {
        var records = emptyList<PracticeRecord>()
        for (id in 1..25) records = PracticeLedger.append(records, record(id))
        assertEquals(20, records.size)
        assertEquals(25, records.first().session)
        assertEquals(records, PracticeLedger.append(records, record(25)))
    }

    @Test fun serializationPreservesConfirmedAndEndedOutcomes() {
        val records = listOf(record(1), record(2).copy(outcome = PracticeOutcome.ENDED, mode = HelpMode.ON_DEMAND))
        assertEquals(records, PracticeLedger.decode(PracticeLedger.encode(records)))
    }

    @Test fun corruptOrUnknownEntriesAreSkippedWithoutLosingValidRecords() {
        val value = "bad\n2|unknown|FULL|CONFIRMED|1790000000000|42\n" +
            "3|font_size|LIGHT|ENDED|1790000000000|-1\n" + PracticeLedger.encode(listOf(record(1)))
        assertEquals(listOf(record(1)), PracticeLedger.decode(value))
    }
}
