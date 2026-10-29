package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.polion.core.sources.SourceChoice
import io.polion.core.sources.SourceWorkflow
import io.polion.core.sources.SupportedSources

internal fun SourceWorkflow.productDialog(): ProductDialog =
    when (this) {
        SourceWorkflow.BITCOIN_ADDRESS -> ProductDialog.ACCOUNT
        SourceWorkflow.BITCOIN_PUBLIC_WALLET -> ProductDialog.WALLET
        SourceWorkflow.MANUAL_ACTIVITY -> ProductDialog.ACTIVITY
    }

@Composable
internal fun SupportedSourceDialog(
    onSelect: (SourceChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to your portfolio") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(SupportedSources.choices, key = { it.id }) { choice ->
                    OutlinedButton(
                        onClick = { onSelect(choice) },
                        modifier = Modifier.fillMaxWidth().testTag("source-choice-${choice.id}"),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.titleSmall)
                            Text(choice.description, style = MaterialTheme.typography.bodySmall)
                            if (choice.requiresQueryConsent) {
                                Text("Provider queries require your consent.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
