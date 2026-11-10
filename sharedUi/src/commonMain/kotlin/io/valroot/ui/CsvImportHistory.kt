package io.valroot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.valroot.core.application.CsvImportReceipt
import io.valroot.core.application.CsvReceiptRow
import io.valroot.core.application.CsvRowStatus
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AccountId
import io.valroot.ui.model.SettingsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class CsvHistory(
    val receipts: List<CsvImportReceipt>,
    val accounts: Map<AccountId, String>,
)

private val CsvRowStatus.label: String
    get() =
        when (this) {
            CsvRowStatus.NEW -> "New"
            CsvRowStatus.ALREADY_IMPORTED -> "Already imported"
            CsvRowStatus.RESTORE -> "Restored"
            CsvRowStatus.INVALID -> "Can't import"
        }

private fun undoText(imported: Int): String {
    val added =
        when (imported) {
            0 -> "This import added no transactions."
            1 -> "The 1 transaction it added is reversed."
            else -> "The $imported transactions it added are reversed."
        }
    return "$added Importing the same file again brings them back."
}

/**
 * Settings › Devices & Data › CSV Import History: every import with its counts, the rows of an import when opened, and
 * undo. [onChanged] runs after an undo so the rest of the app shows the reversed transactions.
 */
@Composable
internal fun CsvImportHistorySection(
    app: PortfolioApplication,
    host: PlatformActions,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<CsvHistory?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var rowsByImport by remember { mutableStateOf(emptyMap<String, List<CsvReceiptRow>>()) }
    var undoing by remember { mutableStateOf<CsvImportReceipt?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(app, reload) {
        try {
            history =
                withContext(host.dispatcher) {
                    CsvHistory(
                        app.csvImportReceipts(),
                        app
                            .snapshot()
                            .ownership.accounts
                            .associate { it.id to it.label },
                    )
                }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    fun toggle(id: String) {
        if (expanded == id) {
            expanded = null
            return
        }
        expanded = id
        if (id in rowsByImport) return
        scope.launch {
            try {
                val rows = withContext(host.dispatcher) { app.csvImportRows(id) }
                rowsByImport = rowsByImport + (id to rows)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "The rows of this import could not be loaded."
            }
        }
    }

    fun undo(receipt: CsvImportReceipt) {
        scope.launch {
            busy = true
            try {
                val count = withContext(host.dispatcher) { app.reverseCsvImport(receipt.id) }
                notice = "${transactions(count)} undone."
                reload++
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "This import could not be undone. Nothing was changed."
            } finally {
                busy = false
            }
        }
    }

    undoing?.let { receipt ->
        AlertDialog(
            onDismissRequest = { if (!busy) undoing = null },
            title = { Text("Undo this import?") },
            text = { Text(undoText(receipt.imported)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        undoing = null
                        undo(receipt)
                    },
                    modifier = Modifier.testTag("csv-undo-confirm"),
                ) { Text("Undo") }
            },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text("Cancel") } },
        )
    }

    val current = history
    if (current == null) {
        SettingsNote(if (loadFailed) "Imports could not be loaded." else "Loading imports…")
        return
    }
    if (current.receipts.isEmpty()) {
        SettingsNote("No imports yet.")
    }
    current.receipts.forEach { receipt ->
        val account = current.accounts[receipt.account] ?: "Removed account"
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { toggle(receipt.id) }
                .semantics { stateDescription = if (expanded == receipt.id) "Expanded" else "Collapsed" }
                .testTag("csv-receipt-${receipt.id}")
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(receipt.fileName, style = MaterialTheme.typography.titleSmall)
            Text("${chartTime(receipt.importedAtMillis, 0L)} · $account", style = MaterialTheme.typography.bodySmall)
            Text("${receipt.imported} imported · ${receipt.alreadyImported} skipped · ${receipt.invalid} not importable")
            val reversedAt = receipt.reversedAtMillis
            if (reversedAt != null) {
                Text("Undone ${chartTime(reversedAt, 0L)}")
            } else {
                OutlinedButton(
                    onClick = { undoing = receipt },
                    modifier = Modifier.testTag("csv-undo-${receipt.id}"),
                ) { Text("Undo import") }
            }
            if (expanded == receipt.id) {
                val rows = rowsByImport[receipt.id]
                if (rows == null) {
                    SettingsNote("Loading rows…")
                } else {
                    rows.forEach { row ->
                        Text(
                            listOfNotNull("Line ${row.line}", row.status.label, row.message).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
    notice?.let { SettingsNote(it) }
}
