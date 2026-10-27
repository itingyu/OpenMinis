package com.openminis.app.data.prefs

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-title-date-prefix] Optional date stamp on new chat titles.
 *
 * Two effects, deliberately kept in one place so the editor, the chat screen
 * and the async title generator cannot disagree:
 *  - a brand-new chat opens with the stamp as its title (instead of leaving a
 *    blank that the list renders as "New chat");
 *  - once the model writes a real title, the stamp is re-applied so the date
 *    is not silently lost a few seconds after creation.
 */
object SessionTitleDatePrefix {

    private const val PREFS = "session_title_prefs"
    private const val KEY_MODE = "date_prefix_mode"

    /** Nothing / "2026-10-27" / "2026-10-27 14:30". */
    enum class Mode(val label: String, val pattern: String?) {
        OFF("关闭", null),
        DATE("日期", "yyyy-MM-dd"),
        DATE_TIME("日期 + 时间", "yyyy-MM-dd HH:mm"),
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode {
        val raw = prefs(context).getString(KEY_MODE, Mode.OFF.name) ?: Mode.OFF.name
        return Mode.entries.firstOrNull { it.name == raw } ?: Mode.OFF
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun stamp(mode: Mode): String? = mode.pattern?.let {
        SimpleDateFormat(it, Locale.getDefault()).format(Date())
    }

    /** Title for a freshly created session, or null to keep the current behaviour. */
    fun initialTitle(context: Context): String? = stamp(mode(context))

    /** Prefixes [title] with today's stamp unless it is already there. */
    fun decorate(context: Context, title: String): String {
        val s = stamp(mode(context)) ?: return title
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(s)) return trimmed.ifEmpty { s }
        return "$s · $trimmed"
    }
}
