package com.openminis.app.tools

import android.content.Context

/**
 * [T-tool-toggles] Per-tool on/off switches for the tools that ship in the app
 * (shell_execute, file_read, file_write, file_edit, read_image, browser_use …),
 * plus optional user overrides for the name and description the model sees.
 *
 * The optional tools that already had a switch (browser, sub agents, user_ask,
 * memory) keep their own store so their labels/behaviour stay put; this one
 * covers the core list, which the user could previously not shape at all.
 *
 * Disabled tools are removed from the schema the model receives, so it cannot
 * call them — the same contract as the existing switches. Nothing is deleted:
 * turning one back on restores it on the next request.
 *
 * [T-tool-rename] Renaming only changes what the MODEL sees. Dispatch still
 * resolves the call back to the built-in tool via [realName], so a custom name
 * never needs a new executor and can never shadow another tool.
 */
object ToolToggleStore {
    private const val PREFS = "tool_toggles"
    private const val K_NAME = "name:"
    private const val K_DESC = "desc:"

    /** Tools worth exposing a switch for, in the order the settings page shows them. */
    val TOGGLES: List<Toggle> = listOf(
        Toggle("shell_execute", "Shell 命令", "在沙箱里执行 shell 命令、装包、跑脚本"),
        Toggle("file_read", "读文件", "读取沙箱内的文件内容"),
        Toggle("file_write", "写文件", "创建或覆盖文件"),
        Toggle("file_edit", "改文件", "按精确匹配局部修改文件"),
        Toggle("read_image", "读图片", "查看图片（需要模型或视觉组支持）"),
        Toggle("browser_use", "浏览器", "打开网页、截图、读取页面内容"),
        Toggle("generate_image", "生成图片", "调用图片生成模型"),
        Toggle("memory_write", "写记忆", "把内容写入长期记忆"),
        Toggle("memory_get", "读记忆", "读取长期记忆"),
    )

    data class Toggle(val name: String, val label: String, val description: String)

    /** What the model should see for [name]; falls back to the built-in values. */
    data class Override(val name: String, val description: String?)

    fun isEnabled(context: Context, name: String): Boolean =
        prefs(context).getBoolean(name, true)

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(name, enabled).apply()
    }

    /** Names the user turned off — what [AgentTools] filters out. */
    fun disabledNames(context: Context): Set<String> =
        TOGGLES.map { it.name }.filterNot { isEnabled(context, it) }.toSet()

    fun override(context: Context, name: String): Override? {
        val p = prefs(context)
        val custom = p.getString(K_NAME + name, null)?.takeIf { it.isNotBlank() }
        val desc = p.getString(K_DESC + name, null)?.takeIf { it.isNotBlank() }
        return if (custom == null && desc == null) null else Override(custom ?: name, desc)
    }

    fun overrides(context: Context): Map<String, Override> =
        TOGGLES.mapNotNull { t -> override(context, t.name)?.let { t.name to it } }.toMap()

    fun setOverride(context: Context, name: String, customName: String, description: String) {
        prefs(context).edit()
            .putString(K_NAME + name, customName.trim().takeIf { it.isNotBlank() && it != name })
            .putString(K_DESC + name, description.trim().takeIf { it.isNotBlank() })
            .apply()
    }

    fun clearOverride(context: Context, name: String) {
        prefs(context).edit().remove(K_NAME + name).remove(K_DESC + name).apply()
    }

    /**
     * Maps the name the model called back to the built-in tool. Anything not
     * renamed passes through unchanged.
     */
    fun realName(context: Context, called: String): String =
        TOGGLES.firstOrNull { override(context, it.name)?.name == called }?.name ?: called

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
