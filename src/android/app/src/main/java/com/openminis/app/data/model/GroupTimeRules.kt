package com.openminis.app.data.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * [T-android-group-time-routing] One "use these models during this window"
 * rule attached to a [ModelGroup].
 *
 * Rules are evaluated in list order and the FIRST match wins, so the list is
 * also the priority order. A rule that matches but whose members are all gone
 * falls through to the next rule rather than starving the group.
 *
 * [startMinute] is inclusive, [endMinute] exclusive, both minutes from local
 * midnight (0..1440). `start > end` means the window crosses midnight and the
 * tail (00:00..end) is attributed to the day the window STARTED on — a Friday
 * 22:00-06:00 rule therefore also covers Saturday 02:00.
 *
 * [days] uses ISO weekday numbers (1 = Monday … 7 = Sunday) so it round-trips
 * to `DayOfWeek.value` unchanged; an empty list means "every day". If
 * [startMinute] == [endMinute] the rule is degenerate and never matches (the
 * editor refuses to save one).
 */
@Serializable
data class GroupTimeRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val days: List<Int> = emptyList(),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val entryIds: List<String> = emptyList(),
) {
    /** "22:00-06:00" for display / ConfigBridge. */
    fun windowLabel(): String = "${formatMinute(startMinute)}-${formatMinute(endMinute)}"

    fun dayLabel(): String = when {
        days.isEmpty() || days.size == 7 -> "每天"
        days.sorted() == listOf(1, 2, 3, 4, 5) -> "工作日"
        days.sorted() == listOf(6, 7) -> "周末"
        else -> days.sorted().joinToString("") { DAY_LABELS.getOrElse(it - 1) { "?" } }
    }

    fun matches(at: LocalDateTime): Boolean {
        if (entryIds.isEmpty()) return false
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY)
        if (start == end) return false
        val minute = at.hour * 60 + at.minute
        val today = at.dayOfWeek.value
        return if (start < end) {
            minute in start until end && dayAllowed(today)
        } else {
            when {
                minute >= start -> dayAllowed(today)
                minute < end -> dayAllowed(if (today == 1) 7 else today - 1)
                else -> false
            }
        }
    }

    private fun dayAllowed(isoDay: Int): Boolean = days.isEmpty() || isoDay in days

    companion object {
        const val MINUTES_PER_DAY = 1440
        private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

        fun formatMinute(minute: Int): String {
            val m = minute.coerceIn(0, MINUTES_PER_DAY)
            return "%02d:%02d".format(m / 60, m % 60)
        }

        /** Parses "9:30", "09:30" or "0930"; null when out of range / malformed. */
        fun parseMinute(text: String): Int? {
            val t = text.trim()
            val parts = when {
                t.contains(":") -> t.split(":")
                t.length == 4 && t.all { it.isDigit() } -> listOf(t.substring(0, 2), t.substring(2))
                else -> return null
            }
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }
    }
}

/**
 * Pure resolution rules for time-based group routing. No I/O, no Android
 * types, so it is unit-testable and shared by the repository (routing), the
 * settings UI (preview of "what is active now") and ChatViewModel (the
 * per-send switch).
 */
object GroupTimeRouting {
    /**
     * [T-android-group-time-routing] Windows are wall-clock CST (UTC+8),
     * regardless of the device's own time zone: "22:00 之后用夜间模型" means
     * 22:00 in China even on a phone still set to another zone, and a rule must
     * not silently shift when the user travels. One constant, one meaning.
     */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun now(): LocalDateTime = LocalDateTime.now(ZONE)

    /** The rule governing [at], or null when the group's normal order applies. */
    fun activeRule(group: ModelGroup, at: LocalDateTime = now()): GroupTimeRule? {
        if (group.timeRules.isEmpty()) return null
        val members = group.memberEntryIds.toSet()
        return group.timeRules.firstOrNull { rule ->
            rule.matches(at) && rule.entryIds.any { it in members }
        }
    }

    /**
     * Member order in effect at [at]. Falls back to [ModelGroup.memberEntryIds]
     * when no rule matches, so a group without rules behaves exactly as before.
     */
    fun activeMemberIds(group: ModelGroup, at: LocalDateTime = now()): List<String> {
        val rule = activeRule(group, at) ?: return group.memberEntryIds.toList()
        val members = group.memberEntryIds.toSet()
        return rule.entryIds.filter { it in members }
    }

    /** Local time only (used by callers that carry a clock-of-day, not a date). */
    fun activeMemberIdsAt(group: ModelGroup, time: LocalTime): List<String> =
        activeMemberIds(group, LocalDateTime.of(LocalDate.now(ZONE), time))

    fun isoDayOfWeek(day: DayOfWeek): Int = day.value
}
