package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * [T-android-group-time-routing] Window matching and group member resolution.
 * Pure rules, no Android types.
 */
class GroupTimeRoutingTest {

    private fun group(vararg rules: GroupTimeRule) = ModelGroup(
        id = "g",
        name = "g",
        memberEntryIds = mutableListOf("a", "b", "c"),
        timeRules = rules.toMutableList(),
    )

    private fun at(day: Int, hour: Int, minute: Int = 0): LocalDateTime =
        // 2026-10-26 is a Monday.
        LocalDateTime.of(2026, 10, 26, hour, minute).plusDays((day - 1).toLong())

    @Test
    fun `no rules keeps member order`() {
        val g = group()
        assertEquals(listOf("a", "b", "c"), GroupTimeRouting.activeMemberIds(g, at(1, 12)))
        assertNull(GroupTimeRouting.activeRule(g, at(1, 12)))
    }

    @Test
    fun `weekday daytime rule selects its members`() {
        val rule = GroupTimeRule(id = "r", days = listOf(1, 2, 3, 4, 5), startMinute = 9 * 60, endMinute = 18 * 60, entryIds = listOf("b"))
        val g = group(rule)
        assertEquals(listOf("b"), GroupTimeRouting.activeMemberIds(g, at(3, 10)))
        // 18:00 is exclusive.
        assertEquals(listOf("a", "b", "c"), GroupTimeRouting.activeMemberIds(g, at(3, 18)))
        // Saturday is outside the weekday rule.
        assertEquals(listOf("a", "b", "c"), GroupTimeRouting.activeMemberIds(g, at(6, 10)))
    }

    @Test
    fun `cross midnight window attributes the tail to the starting day`() {
        val rule = GroupTimeRule(id = "n", days = listOf(5), startMinute = 22 * 60, endMinute = 6 * 60, entryIds = listOf("c"))
        val g = group(rule)
        assertEquals(listOf("c"), GroupTimeRouting.activeMemberIds(g, at(5, 23)))
        // Saturday 02:00 belongs to Friday's window.
        assertEquals(listOf("c"), GroupTimeRouting.activeMemberIds(g, at(6, 2)))
        // Saturday 23:00 is not Friday's window.
        assertEquals(listOf("a", "b", "c"), GroupTimeRouting.activeMemberIds(g, at(6, 23)))
    }

    @Test
    fun `first matching rule wins and earlier rule has priority`() {
        val night = GroupTimeRule(id = "n", startMinute = 0, endMinute = 24 * 60, entryIds = listOf("c"))
        val day = GroupTimeRule(id = "d", startMinute = 0, endMinute = 24 * 60, entryIds = listOf("a"))
        val g = group(night, day)
        assertEquals(listOf("c"), GroupTimeRouting.activeMemberIds(g, at(1, 12)))
    }

    @Test
    fun `rule whose members left the group falls through`() {
        val stale = GroupTimeRule(id = "s", startMinute = 0, endMinute = 24 * 60, entryIds = listOf("gone"))
        val g = group(stale)
        assertEquals(listOf("a", "b", "c"), GroupTimeRouting.activeMemberIds(g, at(1, 12)))
    }

    @Test
    fun `degenerate rule never matches`() {
        val zero = GroupTimeRule(id = "z", startMinute = 600, endMinute = 600, entryIds = listOf("a"))
        assertTrue(!zero.matches(at(1, 10)))
    }

    @Test
    fun `parse and format minutes`() {
        assertEquals(570, GroupTimeRule.parseMinute("9:30"))
        assertEquals(0, GroupTimeRule.parseMinute("00:00"))
        assertEquals(1439, GroupTimeRule.parseMinute("23:59"))
        assertEquals(null, GroupTimeRule.parseMinute("24:00"))
        assertEquals("09:05", GroupTimeRule.formatMinute(545))
    }
}
