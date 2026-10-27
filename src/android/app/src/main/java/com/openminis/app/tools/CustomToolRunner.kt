package com.openminis.app.tools

import android.content.Context
import com.openminis.app.sandbox.ShellExecutor
import org.json.JSONObject

/**
 * [T-custom-tools] Runs a [CustomTool] inside the shared PRoot sandbox.
 *
 * Templating: `{{name}}` → the shell-escaped argument, so
 * `webtool search {{query}} --limit {{limit}}` is safe for quotes, spaces and
 * CJK text without the user writing a single quote. Every argument is also
 * exported as `TOOL_ARG_<NAME>` for commands that prefer reading the
 * environment (handy when the value should not appear in the visible command).
 *
 * A call missing a required argument, or one the schema does not declare, is
 * rejected before anything runs — the same contract built-in tools honour.
 */
object CustomToolRunner {

    private const val MAX_OUTPUT_CHARS = 24_000

    suspend fun execute(
        context: Context,
        tool: CustomTool,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: arguments are not valid JSON", false)
        }

        val declared = tool.params.associateBy { it.name }
        val missing = tool.params.filter { it.required }
            .map { it.name }
            .filter { args.optString(it).isBlank() }
        if (missing.isNotEmpty()) {
            return ToolExecutionResult(
                "Error: missing required argument(s): ${missing.joinToString()}", false,
            )
        }

        val values = LinkedHashMap<String, String>()
        declared.keys.forEach { key ->
            val v = args.optString(key, "")
            if (v.isNotBlank()) values[key] = v
        }
        // Declared params only — an undeclared key never reaches the shell.
        var command = tool.command
        values.forEach { (k, v) ->
            command = command.replace("{{$k}}", shellQuote(v))
        }
        // Any placeholder left over would otherwise be passed through literally
        // and the model would get a confusing empty result.
        val leftover = Regex("""\{\{([A-Za-z0-9_]+)\}\}""").findAll(command)
            .map { it.groupValues[1] }.toSet()
        if (leftover.isNotEmpty()) {
            return ToolExecutionResult(
                "Error: no value for placeholder(s): ${leftover.joinToString()}", false,
            )
        }

        val env = values.mapKeys { (k, _) -> "TOOL_ARG_" + envName(k) }

        // [T-custom-tools-boot] A custom tool can be the first thing that runs
        // after a cold start; the sandbox is booted lazily by the normal shell
        // path, which this one bypasses — ShellExecutor's `check(isBooted)`
        // then aborts the call with nothing the user can act on.
        runCatching { com.openminis.app.sandbox.PRootKernel.boot(context.applicationContext) }
            .onFailure { return ToolExecutionResult("Error: sandbox failed to start: ${it.message}", false) }

        val result = try {
            ShellExecutor.execute(
                context = context,
                command = command,
                timeout = tool.timeoutSeconds * 1000L,
                environment = env,
            )
        } catch (e: Exception) {
            return ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
        }

        val body = result.output.trim().ifEmpty {
            if (result.exitCode == 0) "(no output)" else "(no output)"
        }
        val trimmed = if (body.length > MAX_OUTPUT_CHARS) {
            body.take(MAX_OUTPUT_CHARS) +
                "\n\n[truncated: ${body.length - MAX_OUTPUT_CHARS} more characters]"
        } else {
            body
        }
        val ok = result.exitCode == 0
        val text = if (ok) trimmed else "$trimmed\n\nexit code: ${result.exitCode}"
        return ToolExecutionResult(text, ok)
    }

    private fun envName(key: String): String =
        key.replace(Regex("[^A-Za-z0-9]"), "_").uppercase()

    /** POSIX single-quote escaping: safe for any byte except NUL. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
