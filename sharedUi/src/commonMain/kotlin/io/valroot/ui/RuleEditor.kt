package io.valroot.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.model.AssetId
import io.valroot.core.model.SourceRecordId
import io.valroot.core.reconcile.ClassificationRule

@Composable
internal fun RuleEditor(
    existing: ClassificationRule?,
    existingIds: Set<String>,
    cancel: () -> Unit,
    save: (ClassificationRule) -> Unit,
) {
    var id by remember(existing) { mutableStateOf(existing?.id.orEmpty()) }
    var priority by remember(existing) { mutableStateOf((existing?.priority ?: 100).toString()) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var result by remember(existing) { mutableStateOf(existing?.result ?: ActivityKind.DEPOSIT) }
    var matchKind by remember(existing) { mutableStateOf(existing?.matchKind) }
    var matchAsset by remember(existing) { mutableStateOf(existing?.matchAsset?.value.orEmpty()) }
    var matchSource by remember(existing) { mutableStateOf(existing?.matchSource?.value.orEmpty()) }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    if (existing == null) Field("Rule ID", id) { id = it } else Text("Rule ID: ${existing.id}")
    Field("Priority", priority) { priority = it }
    Row(Modifier.toggleable(value = enabled, role = Role.Switch) { enabled = it }.testTag("rule-enabled")) {
        Text("Enabled")
        Switch(checked = enabled, onCheckedChange = null)
    }
    Choice(
        "Match original classification",
        listOf(null to "Any classification") +
            ActivityKind.entries.map {
                it to it.name.lowercase()
            },
        matchKind,
    ) {
        matchKind =
            it
    }
    Field("Match asset ID (blank for any)", matchAsset) { matchAsset = it }
    Field("Match source record ID (blank for any)", matchSource) { matchSource = it }
    Choice("Classify as", ActivityKind.entries.map { it to it.name.lowercase() }, result) { result = it }
    Text(
        "Filters all apply together. Rules change classification; source evidence and accounting treatments remain explicit.",
        fontSize = 12.sp,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(onClick = cancel) { Text("Back to rules") }
        Button(onClick = {
            val parsedPriority = priority.toIntOrNull()
            if (id.isBlank() || parsedPriority == null) {
                error = "Enter a rule ID and a whole-number priority."
            } else if (existing == null && id.trim() in existingIds) {
                error = "That rule ID exists. Edit it from the rule list."
            } else {
                save(
                    ClassificationRule(
                        existing?.id ?: id.trim(),
                        parsedPriority,
                        result,
                        matchKind,
                        matchAsset.takeIf { it.isNotBlank() }?.let(::AssetId),
                        matchSource.takeIf { it.isNotBlank() }?.let(::SourceRecordId),
                        enabled,
                    ),
                )
            }
        }) { Text("Save rule") }
    }
}
