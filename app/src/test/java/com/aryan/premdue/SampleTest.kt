package com.aryan.premdue

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleTest {
    private val month = YearMonth.of(2026, 11)
    private val list = DueListParser.parse(Sample.text(month))

    @Test fun readsEveryRow() {
        assertEquals(month, list.month)
        assertEquals(Sample.AGENT, list.agent)
        assertEquals(18, list.policies.size)
        assertEquals((1..18).toList(), list.policies.map { it.sno })
    }

    @Test fun totalsReconcile() {
        assertEquals(list.policies.sumOf { it.total }, list.premium)
        assertEquals(list.policies.sumOf { it.com }, list.commission, 0.05)
    }

    @Test fun coversEverySection() {
        val sections = list.policies.map { list.section(it) }.toSet()
        assertTrue(sections.containsAll(Section.values().toList()))
    }

    @Test fun rejectsAListThatDoesNotAddUp() {
        val broken = Sample.text(month).replace(Regex("Premium : (\\d+)")) { "Premium : ${it.groupValues[1].toLong() + 1}" }
        val e = runCatching { DueListParser.parse(broken) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("Grand Total"))
    }
}
