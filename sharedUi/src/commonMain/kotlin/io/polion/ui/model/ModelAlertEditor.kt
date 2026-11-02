package io.polion.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModelAlertEditorState(
    val threshold: String,
)

@Composable
fun ModelAlertEditor(
    state: ModelAlertEditorState,
    onThreshold: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New price alert", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.threshold,
            onValueChange = onThreshold,
            label = { Text("Price change (%)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onSave) { Text("Done") }
        }
    }
}
