package io.polion.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.polion.core.alerts.AlertCondition

/**
 * The alert editor dialog. [alertId] is null for a new alert. [threshold] is a price in [currency] for ABOVE and BELOW, and a
 * percentage for MOVE. [error] explains why the last save was refused, next to the field.
 */
data class ModelAlertEditorState(
    val alertId: String?,
    val currency: String,
    /** The coin's CoinGecko price in the display currency, or null when it is not on the top 250 list. */
    val currentPrice: String?,
    val condition: AlertCondition,
    val threshold: String,
    val error: String? = null,
    val saving: Boolean = false,
)

private fun AlertCondition.label(): String =
    when (this) {
        AlertCondition.ABOVE -> "Above"
        AlertCondition.BELOW -> "Below"
        AlertCondition.MOVE -> "Moves by %"
    }

@Composable
fun ModelAlertEditor(
    state: ModelAlertEditorState,
    onCondition: (AlertCondition) -> Unit,
    onThreshold: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (!state.saving) onCancel() },
        modifier = Modifier.testTag("alert-editor"),
        title = { Text(if (state.alertId == null) "New price alert" else "Edit price alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    state.currentPrice?.let { "Current price: $it" } ?: "No current price. This coin is not on CoinGecko's top 250 list.",
                    color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                AlertConditions.forEach { condition ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = condition == state.condition,
                                role = Role.RadioButton,
                                enabled = !state.saving,
                            ) { onCondition(condition) }
                            .testTag("alert-condition-${condition.name.lowercase()}")
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = condition == state.condition, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(condition.label())
                    }
                }
                OutlinedTextField(
                    value = state.threshold,
                    onValueChange = onThreshold,
                    label = { Text(if (state.condition == AlertCondition.MOVE) "Move (%)" else "Price (${state.currency})") },
                    singleLine = true,
                    isError = state.error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("alert-threshold"),
                )
                state.error?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodySmall) }
                if (state.alertId != null) {
                    TextButton(
                        onClick = onDelete,
                        enabled = !state.saving,
                        modifier = Modifier.testTag("alert-delete"),
                    ) { Text("Delete alert", color = c.error, fontWeight = FontWeight.SemiBold) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.saving, modifier = Modifier.testTag("alert-save")) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
        },
    )
}

private val AlertConditions = listOf(AlertCondition.ABOVE, AlertCondition.BELOW, AlertCondition.MOVE)
