package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinBlockAnchor
import io.polion.core.bitcoin.BitcoinUtxoReviewPage
import kotlinx.coroutines.withContext

@Composable
internal fun UtxoReviewDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    onDismiss: () -> Unit,
) {
    var offset by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<BitcoinUtxoReviewPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(app, offset) {
        loading = true
        failed = false
        try {
            val loaded = withContext(host.dispatcher) { app.bitcoinUtxoReview(offset, 10, page?.snapshotId) }
            page = loaded
            offset = loaded.offset
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("UTXO comparison details") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Advisory provider comparison. History coverage remains partial; reviewing differences does not change balances.")
                when {
                    loading -> Text("Loading comparison…")
                    failed -> Text("Could not read saved comparison. Close and try again.")
                    else ->
                        page?.let { saved ->
                            Text("Scope: all ${saved.addressCount} currently enabled Bitcoin addresses, across accounts.")
                            if (saved.snapshotId == null) {
                                Text(
                                    "Evidence unknown for this active scope. Refresh to obtain a comparison; older backups may contain no graph evidence.",
                                )
                            } else {
                                Text("Snapshot ${saved.snapshotId} · ${saved.observedAt}")
                                Text("Provider: ${saved.providerSourceId}")
                                Text("Chain checkpoint: ${saved.anchor?.height} · ${saved.anchor?.hash}")
                                Text("Scope fingerprint: ${saved.scopeKey}")
                                when {
                                    !saved.compared -> Text("No provider UTXO comparison was saved for this snapshot.")
                                    saved.total == 0 ->
                                        Text(
                                            "No differences found. Provider history and UTXOs are not independent verification.",
                                        )
                                    else ->
                                        Text(
                                            "${saved.offset + 1}–${saved.offset + saved.rows.size} of ${saved.total} differences or missing parents",
                                        )
                                }
                                saved.rows.forEach { row ->
                                    HorizontalDivider()
                                    Text(row.kind.lowercase().replace('_', ' '))
                                    Text("Account: ${row.account?.value ?: "unknown / outside watched scope"}")
                                    Text("Address: ${row.address ?: row.providerAddress ?: "unknown"}")
                                    Text("Outpoint: ${row.outpoint.txid}:${row.outpoint.vout}")
                                    Text("Reconstructed: ${row.reconstructedValue?.let { "$it sat" } ?: "absent / unknown"}")
                                    Text("Reconstructed state: ${row.reconstructedStatus ?: "absent"}")
                                    Text("Reconstructed confirmation: ${confirmation(row.reconstructedBlock, row.reconstructedStatus)}")
                                    Text("Provider: ${row.providerValue?.let { "$it sat" } ?: "absent / unknown"}")
                                    Text("Provider address: ${row.providerAddress ?: "absent / unknown"}")
                                    Text("Provider confirmation: ${confirmation(row.providerBlock, row.providerValue?.let { "known" })}")
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { offset = (saved.offset - 10).coerceAtLeast(0) }, enabled = saved.offset > 0) {
                                        Text("Previous")
                                    }
                                    TextButton(onClick = { offset = saved.offset + 10 }, enabled = saved.hasNext) { Text("Next") }
                                }
                            }
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close comparison") } },
    )
}

private fun confirmation(
    block: BitcoinBlockAnchor?,
    status: String?,
): String =
    when {
        block != null -> "confirmed at ${block.height} · ${block.hash}"
        status == null || status == "UNKNOWN_PARENT" -> "unknown / absent"
        else -> "unconfirmed"
    }
