package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.polion.core.application.DashboardWidget
import io.polion.core.application.DashboardWidgetPreference

@Composable
internal fun DashboardWidgetDialog(
    draft: List<DashboardWidgetPreference>,
    onChange: (List<DashboardWidgetPreference>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<DashboardWidgetPreference>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize dashboard") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose and order the sections shown below your portfolio value.")
                draft.forEachIndexed { index, preference ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = preference.visible,
                            onCheckedChange = { visible ->
                                onChange(draft.toMutableList().apply { set(index, preference.copy(visible = visible)) })
                            },
                            modifier =
                                Modifier
                                    .testTag("widget-visible-${preference.widget.name}")
                                    .semantics { contentDescription = "Show ${preference.widget.label()}" },
                        )
                        Text(preference.widget.label(), modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = index > 0,
                            modifier =
                                Modifier
                                    .testTag("widget-up-${preference.widget.name}")
                                    .semantics { contentDescription = "Move ${preference.widget.label()} up" },
                            onClick = { onChange(draft.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        ) { Text("↑") }
                        TextButton(
                            enabled = index < draft.lastIndex,
                            modifier =
                                Modifier
                                    .testTag("widget-down-${preference.widget.name}")
                                    .semantics { contentDescription = "Move ${preference.widget.label()} down" },
                            onClick = {
                                onChange(draft.toMutableList().apply { add(index + 1, removeAt(index)) })
                            },
                        ) { Text("↓") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun DashboardWidget.label(): String =
    when (this) {
        DashboardWidget.ALLOCATION -> "Allocation"
        DashboardWidget.ASSETS -> "Assets"
        DashboardWidget.HEALTH -> "Data health"
        DashboardWidget.ACTIVITY -> "Recent activity"
    }
