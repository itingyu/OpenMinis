package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.tools.AgentToolSwitch

/**
 * [T-tools-granular-switches] Settings › Agent Runtime › Tools — port of iOS
 * `ToolsSettingsView`.
 *
 * One switch per optional capability. The switches gate the tool SCHEMA, so a
 * disabled tool is not merely refused at call time — the model never sees it
 * and cannot plan around it. The remaining tools (shell, files, memory) are
 * core to the agent and deliberately have no switch.
 *
 * The title stays "Tools" in English to sit alongside Skills and Memory in the
 * Agent Runtime section; locales whose bare "tools" reads as hardware render
 * it as "available tools" instead.
 */
@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }
    var userAskEnabled by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.isEnabled(context))
    }
    var userAskTimeout by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.timeoutMinutes(context))
    }
    var userAskNotify by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.notifyInBackground(context))
    }
    var showWaitPicker by remember { mutableStateOf(false) }

    // [T-tool-toggles] Recompose after a switch flip; the store is not observable.
    var togglesVersion by remember { mutableStateOf(0) }
    // [T-tool-rename] Which built-in tool the rename dialog is editing.
    var editingTool by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        // [T-tool-toggles] Core tools shipped with the app — the user could not
        // shape these at all before, only the optional ones below.
        SettingsSection(
            header = "内置工具",
            footer = "关闭后该工具不再出现在模型的工具列表里，下一轮请求生效。",
        ) {
            @Suppress("UNUSED_EXPRESSION") togglesVersion
            com.openminis.app.tools.ToolToggleStore.TOGGLES.forEachIndexed { i, t ->
                // [T-tool-rename] Tap the row to rename / re-describe what the
                // model sees. The switch stays a plain on/off.
                val o = com.openminis.app.tools.ToolToggleStore.override(context, t.name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = editingTool == null) {
                            editingTool = t.name
                            editName = o?.name ?: t.name
                            editDesc = o?.description ?: ""
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = o?.name ?: t.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (o != null) "原名 ${t.name}" else t.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = com.openminis.app.tools.ToolToggleStore.isEnabled(context, t.name),
                        onCheckedChange = {
                            com.openminis.app.tools.ToolToggleStore.setEnabled(context, t.name, it)
                            togglesVersion++
                        },
                    )
                }
                if (i != com.openminis.app.tools.ToolToggleStore.TOGGLES.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // [T-port-user-ask] user_ask: on/off plus how long a question waits
        // (iOS ToolsSettingsView.userAskSections), and whether a question asked
        // while the app is away is posted as a notification.
        SettingsSection(footer = stringResource(R.string.user_ask_setting_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.user_ask_setting_title),
                checked = userAskEnabled,
                onCheckedChange = {
                    userAskEnabled = it
                    com.openminis.app.agent.userask.UserAskSettings.setEnabled(context, it)
                },
                showDivider = userAskEnabled,
            )
            if (userAskEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.user_ask_setting_wait),
                    value = userAskWaitLabel(userAskTimeout),
                    onClick = { showWaitPicker = true },
                    showDivider = true,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.user_ask_setting_notify),
                    checked = userAskNotify,
                    onCheckedChange = {
                        userAskNotify = it
                        com.openminis.app.agent.userask.UserAskSettings.setNotifyInBackground(context, it)
                    },
                    showDivider = false,
                )
            }
        }
        if (showWaitPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showWaitPicker = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.user_ask_setting_wait)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        for (m in com.openminis.app.agent.userask.UserAskSettingsSpec.TIMEOUT_CHOICES) {
                            SettingsChoiceRow(
                                title = userAskWaitLabel(m),
                                selected = m == userAskTimeout,
                                onSelect = {
                                    userAskTimeout = m
                                    com.openminis.app.agent.userask.UserAskSettings.setTimeoutMinutes(context, m)
                                    showWaitPicker = false
                                },
                                showDivider = false,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showWaitPicker = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.user_ask_cancel))
                    }
                },
            )
        }
        // [T-tool-rename] Rename / re-describe a built-in tool.
        editingTool?.let { tool ->
            AlertDialog(
                onDismissRequest = { editingTool = null },
                title = { Text("自定义工具名与描述") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { if (it.length <= 64) editName = it },
                            label = { Text("工具名（模型看到的）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "只能用小写字母、数字、下划线；留空恢复原名 $tool。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text("描述（留空用内置描述）") },
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    val nameOk = editName.isBlank() || Regex("[a-z][a-z0-9_]{0,40}").matches(editName.trim())
                    androidx.compose.material3.TextButton(
                        enabled = nameOk,
                        onClick = {
                            com.openminis.app.tools.ToolToggleStore.setOverride(
                                context, tool, editName.trim(), editDesc,
                            )
                            togglesVersion++
                            editingTool = null
                        },
                    ) { Text(androidx.compose.ui.res.stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        com.openminis.app.tools.ToolToggleStore.clearOverride(context, tool)
                        togglesVersion++
                        editingTool = null
                    }) { Text("恢复默认") }
                },
            )
        }
        // [T-android-subagent-settings-parity] The agents switch is NOT here.
        //
        // It lives on Settings > Agents > Sub Agents, which is also where the
        // roster is configured — one place to look, and no second copy of the
        // switch to drift out of sync with it. Mirrors iOS, whose
        // ToolsSettingsView likewise only migrates the legacy key and shows no
        // agents row.
    }
}

/** "5 min", or "Indefinitely" for 0. */
@Composable
private fun userAskWaitLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.user_ask_setting_indefinitely)
    else stringResource(R.string.user_ask_setting_minutes, minutes)
