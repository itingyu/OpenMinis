package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-custom-tools] User-defined agent tools.
 *
 * Minis ships a fixed tool set; MCP servers can add more but require a server
 * process. This is the missing middle: a tool the user declares in Settings and
 * that runs a shell command inside the existing PRoot sandbox.
 *
 * Contract:
 *  - `name` / `description` / `params` become the model-facing schema, exactly
 *    like a built-in tool's.
 *  - `command` is run through /bin/sh -c in the sandbox. `{{param}}` in the
 *    template is replaced with the shell-escaped argument value, and every
 *    argument is ALSO exported as `TOOL_ARG_<NAME>` (upper-cased, non-alnum →
 *    `_`), so a script can read them without templating.
 *  - stdout+stderr is returned to the model, truncated.
 *
 * Stored as one JSON array in SharedPreferences: a tool is small, and a schema
 * change must not need a database migration.
 */
data class CustomToolParam(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
)

data class CustomTool(
    val name: String,
    val description: String,
    val command: String,
    val params: List<CustomToolParam> = emptyList(),
    val enabled: Boolean = true,
    /** [T-custom-tools] Sandbox may need it; informational only for now. */
    val timeoutSeconds: Int = 120,
) {
    fun toDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = name,
        description = description,
        parameters = buildMap {
            put(
                "tool_title",
                AgentToolParam(
                    "string",
                    "A concise 5-10 word summary of what this tool call does, shown to the user. " +
                        "Use the same language as the user.",
                ),
            )
            params.forEach { p ->
                put(p.name, AgentToolParam(p.type, p.description))
            }
        },
        required = listOf("tool_title") + params.filter { it.required }.map { it.name },
        propertyOrdering = listOf("tool_title") + params.map { it.name },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("command", command)
        put("enabled", enabled)
        put("timeoutSeconds", timeoutSeconds)
        put("params", JSONArray().apply {
            params.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("type", p.type)
                    put("description", p.description)
                    put("required", p.required)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): CustomTool = CustomTool(
            name = o.optString("name"),
            description = o.optString("description"),
            command = o.optString("command"),
            enabled = o.optBoolean("enabled", true),
            timeoutSeconds = o.optInt("timeoutSeconds", 120).coerceIn(5, 3600),
            params = o.optJSONArray("params")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { p ->
                        CustomToolParam(
                            name = p.optString("name"),
                            type = p.optString("type", "string").ifBlank { "string" },
                            description = p.optString("description"),
                            required = p.optBoolean("required", false),
                        )
                    }
                }
            } ?: emptyList(),
        )
    }
}

object CustomToolStore {

    private const val PREFS = "custom_tools"
    private const val KEY = "tools"

    /** Names the built-in tool set already owns; a custom tool may not shadow one. */
    private val RESERVED = setOf(
        "shell_execute", "file_read", "file_write", "file_edit", "read_image",
        "browser_use", "memory_write", "memory_get", "subagent_task",
        "user_ask", "delegate_task", "agent_status",
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<CustomTool> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(CustomTool::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun enabledTools(context: Context): List<CustomTool> =
        all(context).filter { it.enabled && it.name.isNotBlank() && it.name !in RESERVED }

    fun find(context: Context, name: String): CustomTool? =
        all(context).firstOrNull { it.name == name && it.enabled }

    fun save(context: Context, tools: List<CustomTool>) {
        val arr = JSONArray()
        tools.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(context: Context, tool: CustomTool): String? {
        val name = tool.name.trim()
        if (name.isEmpty()) return "Tool name is empty"
        if (!name.matches(Regex("[a-z][a-z0-9_]{1,40}"))) {
            return "Tool name must be lowercase letters, digits or underscore (start with a letter)"
        }
        if (name in RESERVED) return "'$name' is a built-in tool name"
        if (tool.command.isBlank()) return "Command is empty"
        val existing = all(context).toMutableList()
        val idx = existing.indexOfFirst { it.name == name }
        val normalized = tool.copy(name = name)
        if (idx >= 0) existing[idx] = normalized else existing.add(normalized)
        save(context, existing)
        return null
    }

    /**
     * [T-custom-tools] Save an edit to an existing tool, including a RENAME.
     *
     * [upsert] keys on the name, so renaming through it would leave the old
     * entry behind and the model would then see both. Here the entry is
     * replaced in place, keeping its position (and its enabled flag, which the
     * editor does not show).
     */
    fun replace(context: Context, originalName: String, tool: CustomTool): String? {
        val existing = all(context).toMutableList()
        val idx = existing.indexOfFirst { it.name == originalName }
        // The entry vanished (deleted from another screen) — fall back to a
        // plain insert rather than silently dropping the edit.
        if (idx < 0) return upsert(context, tool)

        val name = tool.name.trim()
        if (name.isEmpty()) return "Tool name is empty"
        if (!name.matches(Regex("[a-z][a-z0-9_]{1,40}"))) {
            return "Tool name must be lowercase letters, digits or underscore (start with a letter)"
        }
        if (name in RESERVED) return "'$name' is a built-in tool name"
        if (tool.command.isBlank()) return "Command is empty"
        if (name != originalName && existing.any { it.name == name }) {
            return "'$name' already exists"
        }

        existing[idx] = tool.copy(name = name, enabled = existing[idx].enabled)
        save(context, existing)
        return null
    }

    fun delete(context: Context, name: String) {
        save(context, all(context).filterNot { it.name == name })
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        save(context, all(context).map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    /**
     * [T-custom-tools] Ready-made tools for the CLIs the sandbox already ships.
     * Offered as one-tap presets so the feature is useful before the user
     * authors anything: `webtool` covers web search / page fetch / rankings,
     * which the built-in tool set does not expose at all.
     */
    val PRESETS: List<CustomTool> = listOf(
        CustomTool(
            name = "webtool_search",
            description = "Search the web with multiple engines and return ranked results " +
                "(title, url, snippet). Use for anything current or outside the model's " +
                "knowledge: news, docs, prices, releases. Follow up with webtool_fetch to " +
                "read a page in full.",
            command = "webtool search {{query}}",
            params = listOf(
                CustomToolParam(
                    "query", "string",
                    "Search query, natural language is fine.",
                    required = true,
                ),
            ),
            timeoutSeconds = 120,
        ),
        CustomTool(
            name = "webtool_fetch",
            description = "Fetch a web page and return its main text (readable extraction) " +
                "or a file's contents. Use after webtool_search to read the source instead " +
                "of guessing from a snippet.",
            command = "webtool fetch {{url}}",
            params = listOf(
                CustomToolParam(
                    "url", "string",
                    "Absolute http(s) URL to fetch.",
                    required = true,
                ),
            ),
            timeoutSeconds = 180,
        ),
        CustomTool(
            name = "webtool_rank",
            description = "Get today's hot lists / trending rankings (weibo, zhihu, bilibili, " +
                "hackernews and more). Use when the user asks what is trending.",
            command = "webtool rank {{site}}",
            params = listOf(
                CustomToolParam(
                    "site", "string",
                    "Site key, e.g. weibo / zhihu / bilibili / hackernews.",
                    required = true,
                ),
            ),
            timeoutSeconds = 120,
        ),
    )
}
