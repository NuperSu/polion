package io.valroot.ui

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
import io.valroot.core.sources.SourceChoice
import io.valroot.core.sources.SourceWorkflow
import io.valroot.core.sources.SupportedSources

internal fun SourceWorkflow.productDialog(): ProductDialog =
    when (this) {
        SourceWorkflow.BITCOIN_ADDRESS -> ProductDialog.ACCOUNT
        SourceWorkflow.BITCOIN_PUBLIC_WALLET -> ProductDialog.WALLET
        SourceWorkflow.MANUAL_ACTIVITY -> ProductDialog.ACTIVITY
    }

@Composable
internal fun SupportedSourceDialog(
    onSelect: (SourceChoice) -> Unit,
    onImportCsv: () -> Unit,
    onDismiss: () -> Unit,
    /** Opens the EVM address flow; null hides the choice. */
    onEvm: (() -> Unit)? = null,
    /** Opens an exchange account flow; null hides the choices. */
    onExchange: ((io.valroot.core.exchange.ExchangeKind) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to your portfolio") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Choose how to add transactions", style = MaterialTheme.typography.titleMedium) }
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
                onEvm?.let { openEvm ->
                    item {
                        OutlinedButton(
                            onClick = openEvm,
                            modifier = Modifier.fillMaxWidth().testTag("source-choice-evm"),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("EVM address", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Ethereum, Base, Arbitrum and other chains. Public address only.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("Provider queries require your consent.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                onExchange?.let { openExchange ->
                    items(io.valroot.core.exchange.ExchangeKind.entries, key = { it.id }) { kind ->
                        OutlinedButton(
                            onClick = { openExchange(kind) },
                            modifier = Modifier.fillMaxWidth().testTag("source-choice-${kind.id}"),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${kind.label} account", style = MaterialTheme.typography.titleSmall)
                                Text("Balances and history through a read-only API key.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onImportCsv,
                        modifier = Modifier.fillMaxWidth().testTag("source-choice-csv"),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Import CSV File", style = MaterialTheme.typography.titleSmall)
                            Text("Import transactions from a file", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
