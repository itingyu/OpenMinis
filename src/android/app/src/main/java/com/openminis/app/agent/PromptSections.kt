package com.openminis.app.agent

import android.content.Context
import com.openminis.app.sandbox.RootfsFlavor
import com.openminis.app.sandbox.RootfsFlavorPrefs

/**
 * [T-prompt-sections] The user-editable halves of the system prompt.
 *
 * Everything here is prose the model reads: the sandbox contract (per distro),
 * the Android CLI inventory, file-search rules and output style. Each section
 * can be edited, switched off entirely, and is available in both Chinese and
 * English — the language follows the app UI unless the user pins one.
 *
 * The engine keeps its own copy of every default, so "reset" is exact and
 * works after an update that changes the wording.
 */
object PromptSections {

    enum class Lang(val key: String) {
        AUTO("auto"), ZH("zh"), EN("en");
        fun label(): String = when (this) {
            AUTO -> "自动（跟随界面语言）"
            ZH -> "中文"
            EN -> "English"
        }
    }

    enum class Id(val key: String, val titleZh: String, val titleEn: String, val defaultOn: Boolean) {
        SANDBOX("sandbox", "沙箱环境", "Sandbox", true),
        ANDROID_CLI("android_cli", "Android 命令行工具", "Android CLI tools", true),
        FILES("files", "文件与检索", "Files & search", true),
        COLLAB("collab", "协作与输出", "Collaboration", true),
    }

    private const val PREFS = "prompt_sections"

    fun lang(context: Context): Lang =
        Lang.entries.firstOrNull { it.key == prefs(context).getString("lang", "auto") } ?: Lang.AUTO

    fun setLang(context: Context, lang: Lang) {
        prefs(context).edit().putString("lang", lang.key).apply()
    }

    /** True when the prompt should be written in Chinese. */
    fun useChinese(context: Context): Boolean = when (lang(context)) {
        Lang.ZH -> true
        Lang.EN -> false
        Lang.AUTO -> java.util.Locale.getDefault().language.startsWith("zh")
    }

    fun isEnabled(context: Context, id: Id): Boolean =
        prefs(context).getBoolean("${id.key}.on", id.defaultOn)

    fun setEnabled(context: Context, id: Id, on: Boolean) {
        prefs(context).edit().putBoolean("${id.key}.on", on).apply()
    }

    /** The stored override for [id] in the active language, if the user wrote one. */
    fun override(context: Context, id: Id, zh: Boolean): String? =
        prefs(context).getString("${id.key}.${if (zh) "zh" else "en"}", null)

    fun setOverride(context: Context, id: Id, zh: Boolean, text: String) {
        val e = prefs(context).edit()
        if (text.isBlank()) e.remove("${id.key}.${if (zh) "zh" else "en"}")
        else e.putString("${id.key}.${if (zh) "zh" else "en"}", text)
        e.apply()
    }

    fun clearOverride(context: Context, id: Id, zh: Boolean) {
        prefs(context).edit().remove("${id.key}.${if (zh) "zh" else "en"}").apply()
    }

    fun default(context: Context, id: Id, zh: Boolean): String {
        return when (id) {
            Id.SANDBOX -> if (zh) SANDBOX_UBUNTU_ZH else SANDBOX_UBUNTU
            Id.ANDROID_CLI -> if (zh) ANDROID_CLI_ZH else ANDROID_CLI
            Id.FILES -> if (zh) FILES_ZH else FILES
            Id.COLLAB -> if (zh) COLLAB_ZH else COLLAB
        }
    }

    fun text(context: Context, id: Id, zh: Boolean): String =
        override(context, id, zh) ?: default(context, id, zh)

    /** Every enabled section, in prompt order, joined for the system prompt. */
    fun promptBlock(context: Context): String {
        val zh = useChinese(context)
        return Id.entries
            .filter { isEnabled(context, it) }
            .mapNotNull { text(context, it, zh).trim().takeIf(String::isNotEmpty) }
            .joinToString("\n\n")
            .let { if (it.isEmpty()) "" else "\n\n$it\n\n" }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Sandbox contract (per distro, per language) ──────────────────────────

private val SANDBOX_UBUNTU = """
        |Distro: Ubuntu 24.04 LTS (glibc, aarch64, PRoot). Package manager: `apt-get`.
        |- Install packages with `apt-get install -y X`; remove with `apt-get remove -y X`;
        |  search with `apt-cache search X`.
        |- bash, git, curl, wget, rg, python3, jq and ca-certificates are preinstalled;
        |  `python3-NAME` is the native package for a Python module.
        |- pip is PEP 668 managed: a bare `pip install X` fails with
        |  "externally-managed-environment". Use `pip install --break-system-packages X`, or a
        |  venv (`python3 -m venv /tmp/v && . /tmp/v/bin/activate`) for anything bigger.
        |- Java: `apt-get install -y openjdk-17-jdk`.
        |- For matplotlib always call `matplotlib.use('Agg')` before importing pyplot — no display.
        |- ICMP is blocked: `ping` hangs forever. Test connectivity with curl/wget.
        |- Prefer `rg` for every file search; never grep/awk/sed/find.
    """.trimMargin()

private val SANDBOX_UBUNTU_ZH = """
        |发行版：Ubuntu 24.04 LTS（glibc，aarch64，PRoot）。包管理器：`apt-get`。
        |- 安装软件用 `apt-get install -y X`，卸载用 `apt-get remove -y X`，
        |  搜索用 `apt-cache search X`。
        |- 已预装 bash、git、curl、wget、rg、python3、jq、ca-certificates；
        |  Python 模块对应的包名是 `python3-名称`。
        |- pip 受 PEP 668 限制，直接 `pip install X` 会报 externally-managed-environment。
        |  用 `pip install --break-system-packages X`，规模大的装到 venv：
        |  `python3 -m venv /tmp/v && . /tmp/v/bin/activate`。
        |- Java：`apt-get install -y openjdk-17-jdk`。
        |- 用 matplotlib 时先调用 `matplotlib.use('Agg')`：没有显示服务。
        |- ICMP 被禁用，`ping` 会一直挂住。测试网络用 curl/wget。
        |- 文件搜索一律用 `rg`，不要用 grep/awk/sed/find。
    """.trimMargin()

    // ── Files & collaboration ────────────────────────────────────────────────

    private val FILES = """
        |Paths: /var/minis/{workspace,attachments,shared,offloads,memory}. Drafts go to
        |workspace/, deliverables to shared/<project>/, archives never compress. Link files as
        |minis:// URLs (percent-encoded); show images/audio/video inline with ![]().
        |Read with offset/lines instead of loading whole files; scan with `rg -n`. Keep writes
        |under ~500 chars per call — bigger content goes to a script that emits the file.
    """.trimMargin()

    private val FILES_ZH = """
        |路径约定：/var/minis/{workspace,attachments,shared,offloads,memory}。草稿放
        |workspace/，交付物放 shared/<项目>/，归档不压缩。文件用 minis:// 链接（需百分号编码）；
        |图片/音频/视频用 ![]() 内嵌。
        |读文件带 offset/lines，不要整篇灌入；扫描用 `rg -n`。单次写入控制在 500 字符内，
        |长内容写脚本生成文件。
    """.trimMargin()

    private val COLLAB = """
        |Report results, not process: no change logs, no version numbers, no intermediate
        |artifacts. Diagrams as mermaid. Verify before delivering — never invent data; refresh
        |stale numbers you notice. When a task fails, state the real error and what you tried.
    """.trimMargin()

    private val COLLAB_ZH = """
        |只交付结果，不写变更史、版本号、中间产物；图用 mermaid。
        |交付前自查：不编造数据，发现旧数据主动更新。失败时说明真实报错和已尝试的做法。
    """.trimMargin()

    // ── Android CLI section (toggleable, bilingual) ──────────────────────────

    private val ANDROID_CLI = """
        |Android framework commands (run via shell_execute; all live in /usr/local/bin and
        |print JSON, `--help` for details). Shizuku/Accessibility-gated ones return
        |permission_denied — report it and point at settings, don't retry blindly.
        |- android-alarm (Clock alarms/timers), android-calendar (list/create events),
        |  android-clipboard (get/set), android-contacts (list/search/get/delete),
        |- android-device (model/battery/storage), android-location (current/geocode),
        |  android-notification (send/list/clear), android-photos (list/near), android-weather <lat> <lon>,
        |- android-open <url> (system handler), android-speak (TTS), android-speech (STT),
        |  android-player (audio sessions), android-shizuku-cli (privileged APIs / exec),
        |  android-a11y-cli (read screen, tap, type, swipe via AccessibilityService),
        |- minis-open <path|url> (preview inside the app, preferred over android-open),
        |  minis-sessions-cli (search/read chat history), minis-model-use (other models),
        |  minis-config (settings read/write), minis-scheduled (scheduled tasks),
        |  minis-open_terminal is a deep link for interactive stdin sessions only.
        |Env vars: never print values; tell the user which one is missing and link
        |[Set NAME](minis://settings/environments?create_key=NAME&create_value=).
        |Scheduling: crontab/nohup die when the app is suspended — use `minis-scheduled create`
        |(fires through a system alarm) or android-alarm for plain reminders. Never end a turn
        |promising future action: either keep polling with shell_execute `delay`, register a
        |minis-scheduled follow-up, or say the result arrives on the next message.
    """.trimMargin()

    private val ANDROID_CLI_ZH = """
        |Android 命令（用 shell_execute 调用，都在 /usr/local/bin，输出 JSON，`--help` 看详情）。
        |需要 Shizuku/无障碍权限的会返回 permission_denied：如实说明并指向设置页，不要盲目重试。
        |- android-alarm（时钟闹钟/计时器）、android-calendar（查/建日程）、
        |  android-clipboard（读写剪贴板）、android-contacts（列/搜/取/删联系人）、
        |- android-device（机型/电量/存储）、android-location（定位/逆地理/正地理）、
        |  android-notification（发/列/清通知）、android-photos（相册/附近照片）、
        |  android-weather <纬度> <经度>（Open-Meteo 天气）、
        |- android-open <url>（交给系统处理）、android-speak（TTS 朗读）、android-speech（语音识别）、
        |  android-player（音频播放会话）、android-shizuku-cli（特权 API/exec）、
        |  android-a11y-cli（读屏、点击、输入、滑动）、
        |- minis-open <路径|url>（App 内预览，优先于 android-open）、
        |  minis-sessions-cli（检索/读取历史会话）、minis-model-use（调用其他模型）、
        |  minis-config（读写设置）、minis-scheduled（定时任务）。
        |环境变量：绝不输出其值；缺哪个就告诉用户，并给
        |[设置 NAME](minis://settings/environments?create_key=NAME&create_value=) 链接。
        |定时：crontab/nohup 在 App 被挂起后会停，用 `minis-scheduled create`（走系统闹钟）
        |或 android-alarm（纯提醒）。不要在一轮结束时只说"稍后继续"：要么用 shell_execute
        |的 `delay` 轮询到位，要么登记 minis-scheduled，要么明说下次消息才能看到结果。
    """.trimMargin()

    // ── API ──────────────────────────────────────────────────────────────────


    /** Section name in the active language. */
    fun title(id: Id, zh: Boolean): String = if (zh) id.titleZh else id.titleEn

    /** Exactly what the model will receive, for the preview dialog. */
    fun preview(context: Context): String = promptBlock(context)

    /** True when the user edited this section in the given language. */
    fun isCustomised(context: Context, id: Id, zh: Boolean): Boolean =
        override(context, id, zh) != null

    /** Straightforward setter used by the settings editor. */
    fun setText(context: Context, id: Id, zh: Boolean, value: String) =
        setOverride(context, id, zh, value)

}
