package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.valroot.core.application.CSV_MAX_CHARS
import io.valroot.core.application.CsvFeeDefault
import io.valroot.core.application.CsvField
import io.valroot.core.application.CsvImport
import io.valroot.core.application.CsvImportOptions
import io.valroot.core.application.CsvImportReceipt
import io.valroot.core.application.CsvMapping
import io.valroot.core.application.CsvPreview
import io.valroot.core.application.CsvRowStatus
import io.valroot.core.application.LocalStamp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.ui.model.SettingsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREVIEW_DELAY_MILLIS = 300L
private const val PROBLEMS_SHOWN = 50
private const val TEMPLATE_FILE_NAME = "valroot-import-template.csv"
private const val UNREADABLE_FILE = "The file could not be read. Use a UTF-8 CSV file up to 5 MB."
private const val NOTHING_SAVED = "Nothing was saved. Try again."
private val FIAT_CURRENCIES = listOf("USD", "EUR", "GBP")

/** A chosen file: its name, its text and its first row. */
private class CsvFile(
    val name: String,
    val text: String,
    val header: List<String>,
)

/** Choices for the whole file. [columns] has no entry for a field the file does not supply. */
private data class CsvChoices(
    val account: AccountId?,
    val currency: AssetId,
    val feeDefault: CsvFeeDefault,
    val columns: Map<CsvField, Int>,
    val assets: Map<String, AssetId>,
)

/** One check of a file with complete choices. */
private data class CsvRequest(
    val file: CsvFile,
    val choices: CsvChoices,
)

private data class CsvOutcome(
    val request: CsvRequest,
    val preview: CsvPreview?,
    val error: String?,
)

/** Accounts and assets the choices offer, read once when the dialog opens. */
private class CsvCatalog(
    val accounts: List<Account>,
    val assets: List<Pair<AssetId, AssetMetadata>>,
    val currency: AssetId,
) {
    val currencies: List<AssetId> =
        (listOf(currency) + FIAT_CURRENCIES.map { AssetId(it) }.filter { id -> assets.any { it.first == id } }).distinct()

    fun symbol(id: AssetId): String = assets.firstOrNull { it.first == id }?.second?.symbol ?: id.value
}

private val CsvField.label: String
    get() =
        when (this) {
            CsvField.DATE -> "Date"
            CsvField.TYPE -> "Type"
            CsvField.ASSET -> "Asset"
            CsvField.QUANTITY -> "Quantity"
            CsvField.PRICE -> "Price"
            CsvField.CURRENCY -> "Currency"
            CsvField.FEE -> "Fee"
            CsvField.FEE_CURRENCY -> "Fee currency"
            CsvField.NOTES -> "Notes"
            CsvField.REFERENCE -> "Reference"
        }

private fun CsvRequest.options() =
    CsvImportOptions(
        account = requireNotNull(choices.account),
        currency = choices.currency,
        feeDefault = choices.feeDefault,
        localToMillis = { stamp: LocalStamp -> localToEpochMillis(stamp) },
        assets = choices.assets,
    )

internal fun transactions(count: Int) = if (count == 1) "1 transaction" else "$count transactions"

/**
 * Imports a CSV file: pick it, choose what its columns and currencies mean, check the preview and import. Nothing is
 * saved until Import. [onChanged] runs after an import so the rest of the app shows the new transactions.
 */
@Composable
internal fun CsvImportFlow(
    app: PortfolioApplication,
    host: PlatformActions,
    onClose: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<CsvCatalog?>(null) }
    var catalogFailed by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<CsvFile?>(null) }
    var choices by remember { mutableStateOf<CsvChoices?>(null) }
    var outcome by remember { mutableStateOf<CsvOutcome?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<CsvImportReceipt?>(null) }

    LaunchedEffect(app) {
        try {
            val loaded =
                withContext(host.dispatcher) {
                    CsvCatalog(
                        app
                            .snapshot()
                            .ownership.accounts
                            .filter { it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags },
                        app.importAssets(),
                        app.currency(),
                    )
                }
            catalog = loaded
            // An account is preselected only when there is just one to choose from.
            choices = CsvChoices(loaded.accounts.singleOrNull()?.id, loaded.currency, CsvFeeDefault.CURRENCY, emptyMap(), emptyMap())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogFailed = true
        }
    }

    fun update(change: (CsvChoices) -> CsvChoices) {
        choices = choices?.let(change)
    }

    fun chooseFile() {
        host.openTextFile(CSV_MAX_CHARS) { name, text ->
            // A cancelled dialog returns an empty name; a name without text means the file could not be read.
            if (text == null) {
                if (name.isNotEmpty()) notice = UNREADABLE_FILE
                return@openTextFile
            }
            scope.launch {
                try {
                    val header = withContext(host.dispatcher) { CsvImport.read(text).firstOrNull() }
                    if (header == null) {
                        notice = "The file is empty."
                    } else {
                        file = CsvFile(name, text, header)
                        choices = choices?.copy(columns = CsvImport.detect(header).columns, assets = emptyMap())
                        outcome = null
                        notice = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IllegalArgumentException) {
                    notice = failure.message ?: UNREADABLE_FILE
                }
            }
        }
    }

    val picked = file
    val current = choices
    val loaded = catalog
    val request: CsvRequest? =
        picked?.let { f ->
            current?.takeIf { it.account != null && CsvMapping(it.columns).missing.isEmpty() }?.let { CsvRequest(f, it) }
        }

    LaunchedEffect(request) {
        val checking = request ?: return@LaunchedEffect
        // Waits so that a run of quick changes checks once; a change restarts this effect and cancels the wait.
        delay(PREVIEW_DELAY_MILLIS)
        outcome =
            try {
                val preview =
                    withContext(host.dispatcher) {
                        app.previewCsvImport(
                            checking.file.name,
                            checking.file.text,
                            checking.options(),
                            CsvMapping(checking.choices.columns),
                        )
                    }
                CsvOutcome(checking, preview, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                CsvOutcome(checking, null, failure.message ?: "This file cannot be imported.")
            } catch (_: Exception) {
                CsvOutcome(checking, null, "The file could not be checked. Try again.")
            }
    }

    val latest = outcome
    // The last check stays on screen while a new one runs; only a check of the current choices can be imported.
    val fresh = latest != null && request != null && latest.request == request
    val preview = latest?.preview

    fun importRows(checked: CsvPreview) {
        scope.launch {
            importing = true
            importError = null
            try {
                receipt = withContext(host.dispatcher) { app.importCsv(checked) }
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                importError = failure.message ?: NOTHING_SAVED
            } catch (_: Exception) {
                importError = NOTHING_SAVED
            } finally {
                importing = false
            }
        }
    }

    val assetChoices =
        remember(loaded) {
            (loaded?.assets ?: emptyList()).sortedBy { it.second.symbol }.map { (id, meta) -> id to "${meta.symbol} – ${meta.name}" }
        }
    val done = receipt

    AccountFlowDialog(title = "Import CSV file", busy = importing, close = onClose) {
        if (done != null) {
            Text(
                "Imported ${transactions(done.imported)}. Skipped ${done.alreadyImported} already imported and " +
                    "${done.invalid} that can't be imported.",
                modifier = Modifier.testTag("csv-receipt"),
            )
            Button(onClick = onClose, modifier = Modifier.testTag("csv-done")) { Text("Done") }
            return@AccountFlowDialog
        }
        if (picked == null) {
            Text(
                "Import transactions from a CSV file. Each row becomes a transaction in one account. Valroot's template has these " +
                    "columns: date, type, asset, quantity, price, currency, fee, fee_currency, notes, reference.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = ::chooseFile,
                enabled = loaded != null && !importing,
                modifier = Modifier.testTag("csv-choose-file"),
            ) {
                Text(if (picked == null) "Choose file" else "Choose another file")
            }
            OutlinedButton(
                onClick = {
                    host.saveFile(TEMPLATE_FILE_NAME, CsvImport.TEMPLATE.encodeToByteArray()) { notice = it }
                },
                enabled = !importing,
                modifier = Modifier.testTag("csv-save-template"),
            ) { Text("Save template") }
        }
        if (catalogFailed) {
            SettingsNote("Accounts could not be loaded. Close this and try again.")
        } else if (loaded == null) {
            SettingsNote("Loading accounts…")
        }
        notice?.let { SettingsNote(it) }

        if (picked != null && current != null && loaded != null) {
            Text(picked.name, style = MaterialTheme.typography.titleSmall)
            Text("Times without a time zone are read as ${localZoneName()}.", style = MaterialTheme.typography.bodySmall)
            if (loaded.accounts.isEmpty()) {
                Text("Add an account before importing. Each row goes to one account.")
            } else {
                Choice(
                    "Account",
                    listOf<Pair<AccountId?, String>>(null to "Choose an account") + loaded.accounts.map { it.id to it.label },
                    current.account,
                ) { id -> update { it.copy(account = id) } }
            }
            Choice("Price currency", loaded.currencies.map { it to loaded.symbol(it) }, current.currency) { id ->
                update { it.copy(currency = id) }
            }
            Text("Used when a row has no currency.", style = MaterialTheme.typography.bodySmall)
            Choice(
                "Fee without a currency",
                listOf(CsvFeeDefault.CURRENCY to "In the price currency", CsvFeeDefault.ASSET to "In the coin"),
                current.feeDefault,
            ) { value -> update { it.copy(feeDefault = value) } }

            Text("Columns", style = MaterialTheme.typography.titleSmall)
            CsvField.entries.forEach { field ->
                val options =
                    buildList<Pair<Int?, String>> {
                        add(null to (if (field.required) "Choose a column" else "Not in file"))
                        picked.header.forEachIndexed { index, name ->
                            add(index to "${index + 1}. ${name.trim().ifEmpty { "(no name)" }}")
                        }
                    }
                Choice(field.label, options, current.columns[field]) { index ->
                    update { it.copy(columns = if (index == null) it.columns - field else it.columns + (field to index)) }
                }
            }
            if (current.account == null && loaded.accounts.isNotEmpty()) {
                Text("Choose an account to check the rows.")
            }
            val missing = CsvMapping(current.columns).missing
            if (missing.isNotEmpty()) {
                Text("Choose a column for: ${missing.joinToString { it.label }}.")
            }

            (latest?.preview?.unresolvedAssets ?: emptySet()).sorted().forEach { text ->
                Choice(
                    "What is “$text”?",
                    listOf<Pair<AssetId?, String>>(null to "Choose an asset") + assetChoices,
                    current.assets[text],
                ) { id ->
                    update { it.copy(assets = if (id == null) it.assets - text else it.assets + (text to id)) }
                }
            }
        }

        if (request != null && !fresh) SettingsNote("Checking rows…")
        latest?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        preview?.let { CsvPreviewSummary(it) }

        val readyToImport = fresh && preview != null && preview.importable > 0 && !importing
        val label = if (preview != null && fresh) "Import ${transactions(preview.importable)}" else "Import transactions"
        Button(
            onClick = { if (preview != null) importRows(preview) },
            enabled = readyToImport,
            modifier = Modifier.testTag("csv-import"),
        ) { Text(label) }
        importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CsvPreviewSummary(preview: CsvPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("csv-preview")) {
        Text("${preview.count(CsvRowStatus.NEW)} new")
        Text("${preview.count(CsvRowStatus.ALREADY_IMPORTED)} already imported")
        if (preview.count(CsvRowStatus.RESTORE) > 0) {
            Text("${preview.count(CsvRowStatus.RESTORE)} will be restored")
        }
        Text("${preview.count(CsvRowStatus.INVALID)} can't be imported")
        preview.previousImports.firstOrNull()?.let { earlier ->
            Text(
                "This file was already imported on ${chartTime(earlier.importedAtMillis, 0L)}. Rows imported then are skipped.",
            )
        }
        val problems = preview.rows.filter { it.status == CsvRowStatus.INVALID }
        problems.take(PROBLEMS_SHOWN).forEach { row ->
            Text("Line ${row.line}: ${row.message ?: "This row is not valid."}")
        }
        if (problems.size > PROBLEMS_SHOWN) {
            Text("and ${problems.size - PROBLEMS_SHOWN} more")
        }
    }
}
