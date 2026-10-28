package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.PromptSections

/**
 * [T-prompt-sections] Every block of the system prompt the user is allowed to
 * rewrite, plus a language switch covering all of them at once.
 *
 * The distro-specific sandbox text follows the rootfs flavour, so the preview
 * below is shared by every sandbox; it is not tied to one distro.
 */
@Composable
fun PromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<PromptSections.Id?>(null) }
    var draft by remember { mutableStateOf("") }

    val lang = remember(version) { PromptSections.lang(context) }
    val zh = remember(version, lang) { PromptSections.useChinese(context) }

    // [T-prompt-flash-fix] SettingsScaffold already wraps its content in a
    // verticalScroll (scrollable = true). Nesting another verticalScroll here
    // measured the inner column with infinite height constraints and Compose
    // threw "Vertically scrollable component was measured with an infinity
    // maximum height", which killed the app the moment the row was tapped.
    SettingsScaffold(title = "系统提示词", onBack = onBack) {
            SectionBox(title = "语言", footer = "切换后立即生效，覆盖下面所有段落（含沙箱环境说明）。") {
                PromptSections.Lang.entries.forEachIndexed { i, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                PromptSections.setLang(context, option)
                                version++
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = option.label(), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (i != PromptSections.Lang.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            SectionsList(
                context = context,
                version = version,
                zh = zh,
                onEdit = { id -> editing = id; draft = PromptSections.text(context, id, zh) },
                onChange = { version++ },
            )
    }

    editing?.let { id ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(com.openminis.app.agent.PromptSections.title(id, zh)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "留空即使用默认文本。当前编辑的是${if (zh) "中文" else "英文"}版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PromptSections.setText(context, id, zh, draft)
                    version++
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    PromptSections.clearOverride(context, id, zh)
                    version++
                    editing = null
                }) { Text("恢复默认") }
            },
        )
    }
}

@Composable
private fun SectionsList(
    context: android.content.Context,
    version: Int,
    zh: Boolean,
    onEdit: (PromptSections.Id) -> Unit,
    onChange: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") version
    SectionBox(
        title = "段落",
        footer = "关闭的段落不会进入系统提示词，模型看不到其中任何内容。",
    ) {
        PromptSections.Id.entries.forEachIndexed { i, id ->
            val enabled = PromptSections.isEnabled(context, id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onEdit(id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(com.openminis.app.agent.PromptSections.title(id, zh), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (PromptSections.isCustomised(context, id, zh)) "已自定义 · 点击编辑"
                        else "默认文本 · 点击编辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        PromptSections.setEnabled(context, id, it)
                        onChange()
                    },
                )
            }
            if (i != PromptSections.Id.entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    Spacer16()
    SectionBox(title = "预览", footer = "与实际发送的一致，只省略了 SOUL 与工具清单。") {
        Text(
            text = PromptSections.preview(context),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Spacer16() {
    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
}

@Composable
private fun SectionBox(
    title: String,
    footer: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}
