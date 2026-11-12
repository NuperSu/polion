package io.valroot.core.application

import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.storage.sha256

/** CSV import (preview, import, receipts, reversal) and CSV export of activities. */
class CsvService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /**
     * Reads a CSV file and checks every row without saving anything. Each row becomes a manual entry in
     * [CsvImportOptions.account] with an ID derived from the account and the row (or its reference), so a row imported
     * before is recognised in any later file. Identical rows in one file are kept as separate entries.
     */
    fun preview(
        fileName: String,
        text: String,
        options: CsvImportOptions,
        mapping: CsvMapping? = null,
    ): CsvPreview {
        app.assets.ensureDefaults()
        val table = CsvImport.read(text)
        require(table.isNotEmpty()) { "The file is empty" }
        val header = table.first()
        val columns = mapping ?: CsvImport.detect(header)
        require(columns.missing.isEmpty()) { "Choose columns for: " + columns.missing.joinToString { it.header } }
        require(columns.columns.values.all { it in header.indices }) { "A chosen column is not in the file" }
        val accounts = app.assets.scopedAccounts(ownership.load(), null)
        require(options.account in accounts) { "Select an active account" }
        val known = market.assetIds().associateWith { market.metadataAt(it, Long.MAX_VALUE) }
        val unresolved = mutableSetOf<String>()

        fun asset(text: String): AssetId? {
            val key = text.trim()
            options.assets[key]?.let { return it }
            val matches =
                known.filter { (id, metadata) -> id.value.equals(key, true) || metadata?.symbol?.equals(key, true) == true }.keys
            return matches.singleOrNull().also { if (it == null) unresolved += key }
        }
        val occurrences = mutableMapOf<String, Int>()
        val rows =
            table.drop(1).mapIndexed { index, cells ->
                val line = index + 2

                fun cell(field: CsvField) =
                    columns.columns[field]
                        ?.let { cells.getOrNull(it) }
                        ?.trim()
                        .orEmpty()
                try {
                    val (type, incoming) = requireNotNull(CsvImport.type(cell(CsvField.TYPE))) { "Unknown type \"${cell(CsvField.TYPE)}\"" }
                    val assetText = cell(CsvField.ASSET)
                    val assetId = requireNotNull(asset(assetText)) { "Choose which asset \"$assetText\" is" }
                    val quote =
                        cell(CsvField.CURRENCY).takeIf { it.isNotEmpty() }?.let {
                            requireNotNull(asset(it)) { "Choose which asset \"$it\" is" }
                        } ?: options.currency
                    val fee = cell(CsvField.FEE).takeIf { it.isNotEmpty() }?.let(CsvImport::number)?.takeIf { it.coefficient.sign > 0 }
                    val feeAsset =
                        fee?.let {
                            cell(CsvField.FEE_CURRENCY).takeIf { it.isNotEmpty() }?.let { text ->
                                requireNotNull(asset(text)) { "Choose which asset \"$text\" is" }
                            } ?: if (options.feeDefault == CsvFeeDefault.CURRENCY) quote else assetId
                        }
                    val draft =
                        TransactionDraft(
                            ActivityId("pending"),
                            type,
                            assetId,
                            options.account,
                            CsvImport.number(cell(CsvField.QUANTITY)),
                            CsvImport.date(cell(CsvField.DATE), options.localToMillis),
                            quote,
                            cell(CsvField.PRICE).takeIf { it.isNotEmpty() }?.let(CsvImport::number),
                            incomingExternal = incoming,
                            feeAsset = feeAsset,
                            feeQuantity = fee,
                            notes = cell(CsvField.NOTES).take(4000),
                        )
                    val reference = cell(CsvField.REFERENCE)
                    val identity =
                        if (reference.isNotEmpty()) {
                            "ref|$reference"
                        } else {
                            val content = draft.copy(notes = "").encode()
                            val n = occurrences.getOrElse(content) { 0 } + 1
                            occurrences[content] = n
                            "row|$content|$n"
                        }
                    val id = ActivityId("csv-" + sha256("${options.account.value}|$identity").take(32))
                    val identified = draft.copy(id = id)
                    val previous = activities.history(id).lastOrNull()
                    when {
                        previous == null -> {
                            app.transactions.prepareTransaction(identified, editing = false)
                            CsvRowResult(line, CsvRowStatus.NEW, identified)
                        }
                        previous.status == io.valroot.core.ledger.ActivityStatus.REVERSED -> {
                            app.transactions.prepareTransaction(identified, editing = false, restoring = true)
                            CsvRowResult(line, CsvRowStatus.RESTORE, identified)
                        }
                        else -> CsvRowResult(line, CsvRowStatus.ALREADY_IMPORTED, identified, "Imported before")
                    }
                } catch (failure: IllegalArgumentException) {
                    CsvRowResult(line, CsvRowStatus.INVALID, null, failure.message ?: "This row is not valid")
                }
            }
        val duplicateIds =
            rows
                .mapNotNull { it.draft?.id }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val checked =
            rows.map {
                if (it.draft?.id in duplicateIds && it.status != CsvRowStatus.INVALID) {
                    it.copy(status = CsvRowStatus.INVALID, draft = null, message = "Another row has the same reference")
                } else {
                    it
                }
            }
        val hash = CsvImportRepository.hash(text)
        return CsvPreview(
            fileName,
            hash,
            header,
            columns,
            checked,
            unresolved,
            csvImports.receipts().filter { it.fileHash == hash && it.reversedAtMillis == null },
        )
    }

    /**
     * Saves every NEW and RESTORE row of [preview] in one transaction with its receipt; if any row fails, nothing is
     * saved. Rows are checked again inside the transaction, so a row saved meanwhile is skipped rather than duplicated.
     */
    fun importFile(preview: CsvPreview): CsvImportReceipt {
        require(preview.importable > 0) { "There are no new rows to import" }
        val account = preview.rows.firstNotNullOf { it.draft?.account }
        // Validation reads accounts and precision outside the write transaction; the ID check is repeated inside it.
        val prepared =
            preview.rows
                .filter { it.status == CsvRowStatus.NEW || it.status == CsvRowStatus.RESTORE }
                .filter { row ->
                    activities
                        .history(row.draft!!.id)
                        .lastOrNull()
                        ?.status
                        .let { it == null || it == ActivityStatus.REVERSED }
                }.associate { it.draft!!.id to app.transactions.prepareTransaction(it.draft!!, editing = false, restoring = true) }
        val receipt =
            db.transaction {
                val outcomes =
                    preview.rows.map { row ->
                        val draft = row.draft
                        if (draft == null || row.status !in setOf(CsvRowStatus.NEW, CsvRowStatus.RESTORE)) {
                            CsvReceiptRow(row.line, row.status, draft?.id, row.message)
                        } else {
                            val previous = activities.history(draft.id).lastOrNull()
                            if (draft.id !in prepared || previous != null && previous.status != ActivityStatus.REVERSED) {
                                CsvReceiptRow(row.line, CsvRowStatus.ALREADY_IMPORTED, draft.id, "Imported before")
                            } else {
                                app.transactions.writeTransaction(draft, prepared.getValue(draft.id))
                                CsvReceiptRow(row.line, if (previous == null) CsvRowStatus.NEW else CsvRowStatus.RESTORE, draft.id, null)
                            }
                        }
                    }
                val saved =
                    CsvImportReceipt(
                        "import-${now()}-${kotlin.random.Random.nextLong().toULong().toString(16)}",
                        preview.fileName.take(200),
                        preview.fileHash,
                        now(),
                        account,
                        outcomes.size,
                        outcomes.count { it.status == CsvRowStatus.NEW || it.status == CsvRowStatus.RESTORE },
                        outcomes.count { it.status == CsvRowStatus.ALREADY_IMPORTED },
                        outcomes.count { it.status == CsvRowStatus.INVALID },
                        null,
                    )
                csvImports.save(saved, outcomes)
                saved
            }
        invalidate()
        return receipt
    }

    fun receipts(): List<CsvImportReceipt> = csvImports.receipts()

    fun rows(id: String): List<CsvReceiptRow> = csvImports.rows(id)

    /**
     * Undoes an import: every entry it saved that is still active becomes reversed, in one transaction. Entries edited
     * since are reversed too. Importing the same rows again brings them back. Returns how many entries were reversed.
     */
    fun reverse(id: String): Int {
        val reversed =
            db.transaction {
                val receipt = requireNotNull(csvImports.receipts().firstOrNull { it.id == id }) { "Unknown import" }
                require(receipt.reversedAtMillis == null) { "This import was already undone" }
                var count = 0
                csvImports
                    .rows(id)
                    .filter { it.status == CsvRowStatus.NEW || it.status == CsvRowStatus.RESTORE }
                    .mapNotNull { it.activity }
                    .forEach { activity ->
                        val previous = activities.history(activity).lastOrNull()
                        if (previous != null && previous.manual && previous.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED) {
                            activities.saveInTransaction(previous.copy(status = io.valroot.core.ledger.ActivityStatus.REVERSED))
                            count++
                        }
                    }
                csvImports.markReversed(id, now())
                count
            }
        invalidate()
        return reversed
    }

    /** Exports all matching activity movements in the requested scope, independently of on-screen pagination. */
    fun export(
        filter: ActivityFilter = ActivityFilter(),
        activityIds: Set<ActivityId>? = null,
    ): String {
        fun cell(
            value: String,
            text: Boolean,
        ): String {
            val first = value.dropWhile { it.isWhitespace() || it < ' ' }.firstOrNull()
            val unsafe = first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
            val safe = if (text && unsafe) "'" + value else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        val lines = mutableListOf("activity_id,occurred_at,kind,status,account,asset,atomic_units,role,label,note")
        val selected = app.assets.scopedAccounts(ownership.load(), filter.portfolio)
        app.filterActivitiesByNetwork(overlays.replayForLedger(activities.current()).activities, filter.network).forEach { item ->
            val activity = item.activity
            if (activityIds != null && activity.id !in activityIds) return@forEach
            if (filter.kind != null && activity.kind != filter.kind) return@forEach
            if (filter.status != null && activity.status != filter.status) return@forEach
            if (filter.search.isNotBlank() &&
                listOfNotNull(item.label, item.note, activity.id.value, activity.kind.name)
                    .none { it.contains(filter.search, ignoreCase = true) }
            ) {
                return@forEach
            }
            activity.movements
                .filter { movement ->
                    movement.accountId in selected &&
                        (filter.account == null || movement.accountId == filter.account) &&
                        (filter.asset == null || movement.amount.assetId == filter.asset)
                }.forEach { movement ->
                    lines +=
                        listOf(
                            item.activity.id.value,
                            item.activity.occurredAtEpochMillis.toString(),
                            item.activity.kind.name,
                            item.activity.status.name,
                            movement.accountId.value,
                            movement.amount.assetId.value,
                            movement.amount.atomicUnits.toString(),
                            movement.role.name,
                            item.label.orEmpty(),
                            item.note.orEmpty(),
                        ).mapIndexed { index, value -> cell(value, index in setOf(0, 4, 5, 8, 9)) }.joinToString(",")
                }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }
}
