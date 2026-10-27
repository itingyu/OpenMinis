package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.openminis.app.R
import com.openminis.app.tools.CustomTool
import com.openminis.app.tools.CustomToolParam
import com.openminis.app.tools.CustomToolStore

/**
 * [T-custom-tools] Settings › Agent Runtime › Custom Tools.
 *
 * A custom tool is a name + description + JSON-schema-ish parameter list plus a
 * shell command. It is exposed to the model exactly like a built-in tool and
 * runs inside the same PRoot sandbox, so anything the shell can do becomes a
 * tool — `webtool`, `curl`, a repo script, an `rg` one-liner.
 *
 * The presets at the bottom exist because the built-in set has no web search:
 * `webtool` already ships in the sandbox, it just had no way to reach the model.
 */
@Composable
fun CustomToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tools by remember { mutableStateOf(CustomToolStore.all(context)) }
    var editing by remember { mutableStateOf<CustomTool?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.custom_tools_title), onBack = onBack) {
        if (tools.isEmpty()) {
            SettingsSection(footer = stringResource(R.string.custom_tools_empty_footer)) {
                Text(
                    text = stringResource(R.string.custom_tools_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingsSection(footer = stringResource(R.string.custom_tools_footer)) {
                tools.forEachIndexed { index, tool ->
                    SettingsSwitchRow(
                        title = tool.name,
                        checked = tool.enabled,
                        onCheckedChange = { on ->
                            CustomToolStore.setEnabled(context, tool.name, on)
                            tools = CustomToolStore.all(context)
                        },
                        showDivider = index != tools.lastIndex,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.custom_tools_actions),
            footer = stringResource(R.string.custom_tools_actions_footer),
        ) {
            SettingsValueRow(
                title = stringResource(R.string.custom_tools_add),
                value = "",
                onClick = { creating = true },
                showDivider = true,
            )
            SettingsValueRow(
                title = stringResource(R.string.custom_tools_manage),
                value = "${tools.size}",
                onClick = { if (tools.isNotEmpty()) picking = true },
                showDivider = false,
            )
        }

        // One-tap presets for the CLIs the sandbox already carries.
        val missingPresets = CustomToolStore.PRESETS.filter { p -> tools.none { it.name == p.name } }
        if (missingPresets.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.custom_tools_presets),
                footer = stringResource(R.string.custom_tools_presets_footer),
            ) {
                missingPresets.forEachIndexed { index, preset ->
                    SettingsValueRow(
                        title = preset.name,
                        value = stringResource(R.string.custom_tools_import),
                        onClick = {
                            CustomToolStore.upsert(context, preset)?.let { error = it }
                            tools = CustomToolStore.all(context)
                        },
                        showDivider = index != missingPresets.lastIndex,
                    )
                }
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.custom_tools_manage)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    tools.forEach { tool ->
                        SettingsChoiceRow(
                            title = tool.name,
                            selected = false,
                            onSelect = { picking = false; editing = tool },
                            showDivider = true,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (creating || editing != null) {
        val initial = editing ?: CustomTool(name = "", description = "", command = "")
        CustomToolEditorDialog(
            initial = initial,
            isNew = editing == null,
            onDismiss = { creating = false; editing = null },
            onDelete = {
                CustomToolStore.delete(context, initial.name)
                tools = CustomToolStore.all(context)
                editing = null
            },
            onSave = { edited ->
                val problem = if (editing == null) {
                    CustomToolStore.upsert(context, edited)
                } else {
                    CustomToolStore.replace(context, initial.name, edited)
                }
                if (problem != null) {
                    error = problem
                } else {
                    tools = CustomToolStore.all(context)
                    creating = false
                    editing = null
                }
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.custom_tools_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun CustomToolEditorDialog(
    initial: CustomTool,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (CustomTool) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var command by remember { mutableStateOf(initial.command) }
    var timeout by remember { mutableStateOf(initial.timeoutSeconds.toString()) }
    // Parameters edited as text lines: "name: type : description [: required]"
    var paramsText by remember {
        mutableStateOf(
            initial.params.joinToString("\n") { p ->
                buildString {
                    append(p.name).append(": ").append(p.type)
                    if (p.description.isNotBlank()) append(" : ").append(p.description)
                    if (p.required) append(" : required")
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.custom_tools_new else R.string.custom_tools_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    // [T-custom-tools] Renaming is allowed: the store replaces
                    // the entry in place, so the model just sees the new name
                    // from the next request on.
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_tools_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.custom_tools_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.custom_tools_command)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = paramsText,
                    onValueChange = { paramsText = it },
                    label = { Text(stringResource(R.string.custom_tools_params)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.custom_tools_timeout)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.custom_tools_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    CustomTool(
                        name = name.trim(),
                        description = description.trim(),
                        command = command.trim(),
                        timeoutSeconds = timeout.toIntOrNull() ?: 120,
                        params = parseParams(paramsText),
                    ).copy(enabled = initial.enabled),
                )
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.custom_tools_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

/** `name: type : description : required` per line. Unknown fields are ignored. */
internal fun parseParams(text: String): List<CustomToolParam> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(":", limit = 4).map { it.trim() }
            val pName = parts.getOrNull(0).orEmpty()
            if (pName.isEmpty()) return@mapNotNull null
            val type = parts.getOrNull(1).orEmpty().ifBlank { "string" }
            val desc = parts.getOrNull(2).orEmpty()
            val required = parts.getOrNull(3).orEmpty()
                .let { it.equals("required", true) || it.equals("true", true) }
            CustomToolParam(name = pName, type = type, description = desc, required = required)
        }
        .toList()
