package com.openminis.app.data.prefs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-title-date-prefix] Optional "MMdd" stamp on a chat opened by a scheduled
 * task ("0901 早报"), so the session list shows which day that run belongs to.
 *
 * The switch lives on the task itself ([com.openminis.app.scheduled.ScheduledTask.titleDatePrefix]);
 * chats the user starts never get a stamp.
 */
object SessionTitleDatePrefix {
    private const val PATTERN = "MMdd"

    /** "0901" for the given day. */
    fun stamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(PATTERN, Locale.US).format(Date(now))

    /** Prefixed title, or [title] unchanged when the task has the stamp off. */
    fun decorate(title: String, enabled: Boolean, now: Long = System.currentTimeMillis()): String {
        if (!enabled) return title
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return stamp(now)
        // Already stamped (the model renamed the chat and the prefix was kept).
        if (PREFIXED.containsMatchIn(trimmed)) return trimmed
        return "${stamp(now)} $trimmed"
    }

    /** A leading MMdd followed by whitespace. */
    private val PREFIXED = Regex("""^\d{4}(\s|$)""")
}
