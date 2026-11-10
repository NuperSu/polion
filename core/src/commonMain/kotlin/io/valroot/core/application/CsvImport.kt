package io.valroot.core.application

import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.SqliteDatabase
import io.valroot.core.storage.sha256

/** The largest CSV file accepted, in characters, and the most data rows. */
const val CSV_MAX_CHARS = 5 * 1024 * 1024
const val CSV_MAX_ROWS = 20_000

/** Columns of Valroot's import template. DATE, TYPE, ASSET and QUANTITY are required. */
enum class CsvField(
    val header: String,
    val required: Boolean = false,
) {
    DATE("date", true),
    TYPE("type", true),
    ASSET("asset", true),
    QUANTITY("quantity", true),
    PRICE("price"),
    CURRENCY("currency"),
    FEE("fee"),
    FEE_CURRENCY("fee_currency"),
    NOTES("notes"),
    REFERENCE("reference"),
}

/** Which file column holds each field; fields without a column are empty for every row. */
data class CsvMapping(
    val columns: Map<CsvField, Int>,
) {
    val missing: List<CsvField> get() = CsvField.entries.filter { it.required && it !in columns }
}

/** Where a fee without a fee currency is paid. */
enum class CsvFeeDefault {
    /** In the price currency of the row. */
    CURRENCY,

    /** In the row's asset. */
    ASSET,
}

/** A date and time without a time zone, as written in the file. */
data class LocalStamp(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * Choices that apply to the whole file; nothing here is guessed from the data.
 * - Every row goes to [account].
 * - Prices without a currency column are in [currency].
 * - Times without an offset are local to the zone [localToMillis] applies.
 * - Asset text resolves through [assets] first, then to the one known asset with that ID or symbol.
 */
data class CsvImportOptions(
    val account: AccountId,
    val currency: AssetId,
    val feeDefault: CsvFeeDefault,
    val localToMillis: (LocalStamp) -> Long,
    val assets: Map<String, AssetId> = emptyMap(),
)

enum class CsvRowStatus {
    /** A new entry. */
    NEW,

    /** Imported before and still active; skipped. */
    ALREADY_IMPORTED,

    /** Imported before and since undone; importing brings it back. */
    RESTORE,

    /** Not importable; [CsvRowResult.message] says why. */
    INVALID,
}

data class CsvRowResult(
    /** The row's line number in the file, counting the header as line 1. */
    val line: Int,
    val status: CsvRowStatus,
    val draft: TransactionDraft?,
    val message: String? = null,
)

data class CsvPreview(
    val fileName: String,
    val fileHash: String,
    val header: List<String>,
    val mapping: CsvMapping,
    val rows: List<CsvRowResult>,
    /** Asset text that matched no known asset, or more than one. */
    val unresolvedAssets: Set<String>,
    /** Earlier active imports of the same file. */
    val previousImports: List<CsvImportReceipt>,
) {
    fun count(status: CsvRowStatus) = rows.count { it.status == status }

    val importable: Int get() = count(CsvRowStatus.NEW) + count(CsvRowStatus.RESTORE)
}

/** A completed import. Its rows can be undone together; undone rows stay in the history as reversed entries. */
data class CsvImportReceipt(
    val id: String,
    val fileName: String,
    val fileHash: String,
    val importedAtMillis: Long,
    val account: AccountId,
    val rows: Int,
    val imported: Int,
    val alreadyImported: Int,
    val invalid: Int,
    val reversedAtMillis: Long?,
)

data class CsvReceiptRow(
    val line: Int,
    val status: CsvRowStatus,
    val activity: ActivityId?,
    val message: String?,
)

/** RFC 4180 reading with comma, semicolon or tab delimiters, and the template's column names and row rules. */
object CsvImport {
    val TEMPLATE: String =
        CsvField.entries.joinToString(",") { it.header } + "\r\n" +
            "2026-01-15T10:30:00Z,buy,BTC,0.05,42000,USD,1.5,USD,First purchase,\r\n" +
            "2026-02-01 09:00,transfer in,BTC,0.01,,,,,From another wallet,\r\n"

    private val SYNONYMS =
        mapOf(
            CsvField.DATE to listOf("date", "time", "timestamp", "datetime", "date (utc)", "created at"),
            CsvField.TYPE to listOf("type", "kind", "side", "transaction type", "operation"),
            CsvField.ASSET to listOf("asset", "coin", "symbol", "currency code", "base"),
            CsvField.QUANTITY to listOf("quantity", "amount", "qty", "size", "volume"),
            CsvField.PRICE to listOf("price", "unit price", "rate", "price per unit"),
            CsvField.CURRENCY to listOf("currency", "quote", "price currency", "quote currency"),
            CsvField.FEE to listOf("fee", "fees", "fee amount"),
            CsvField.FEE_CURRENCY to listOf("fee_currency", "fee currency", "fee asset"),
            CsvField.NOTES to listOf("notes", "note", "description", "memo", "comment"),
            CsvField.REFERENCE to listOf("reference", "id", "txid", "transaction id", "order id", "ref"),
        )

    /** Rows of cells. The delimiter is the one of `,` `;` and tab that occurs most in the first line outside quotes. */
    fun read(text: String): List<List<String>> {
        require(text.length <= CSV_MAX_CHARS) { "The file is larger than 5 MB" }
        val body = text.removePrefix("")
        val firstLine = body.substringBefore('\n')
        var quoted = false
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        firstLine.forEach { c ->
            if (c == '"') {
                quoted = !quoted
            } else if (!quoted && c in counts) {
                counts[c] = counts.getValue(c) + 1
            }
        }
        val delimiter = counts.maxByOrNull { it.value }!!.takeIf { it.value > 0 }?.key ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endRow() {
            row += cell.toString()
            cell.clear()
            if (row.any { it.isNotBlank() }) rows += row
            row = mutableListOf()
            require(rows.size <= CSV_MAX_ROWS + 1) { "The file has more than $CSV_MAX_ROWS rows" }
        }
        while (i < body.length) {
            val c = body[i]
            if (inQuotes) {
                if (c == '"' && body.getOrNull(i + 1) == '"') {
                    cell.append('"')
                    i++
                } else if (c == '"') {
                    inQuotes = false
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(c)
                    delimiter -> {
                        row += cell.toString()
                        cell.clear()
                    }
                    '\r' -> if (body.getOrNull(i + 1) != '\n') endRow()
                    '\n' -> endRow()
                    else -> cell.append(c)
                }
            }
            i++
        }
        require(!inQuotes) { "A quoted value is not closed" }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Columns whose header names a template field or a common alternative, ignoring case and spaces at the ends. */
    fun detect(header: List<String>): CsvMapping {
        val normalized = header.map { it.trim().lowercase().replace('_', ' ') }
        val columns = mutableMapOf<CsvField, Int>()
        CsvField.entries.forEach { field ->
            val names = SYNONYMS.getValue(field).map { it.replace('_', ' ') }
            val index = normalized.indexOfFirst { it in names && it !in columns.values.map { c -> normalized[c] } }
            if (index >= 0) columns[field] = index
        }
        return CsvMapping(columns)
    }

    /** Transaction type for the type text; transfers in and out are external transfers to and from the account. */
    internal fun type(text: String): Pair<TransactionType, Boolean>? =
        when (text.trim().lowercase()) {
            "buy", "bought", "purchase" -> TransactionType.BUY to false
            "sell", "sold", "sale" -> TransactionType.SELL to false
            "deposit" -> TransactionType.DEPOSIT to false
            "withdrawal", "withdraw" -> TransactionType.WITHDRAWAL to false
            "reward", "staking", "staking reward", "income", "interest", "mining", "airdrop" -> TransactionType.REWARD to false
            "fee" -> TransactionType.FEE to false
            "transfer in", "receive", "received", "incoming" -> TransactionType.TRANSFER to true
            "transfer out", "send", "sent", "outgoing" -> TransactionType.TRANSFER to false
            else -> null
        }

    /** A plain decimal number; thousands separators and decimal commas are rejected rather than guessed. */
    internal fun number(text: String): ExactDecimal {
        val trimmed = text.trim()
        require(
            trimmed.isNotEmpty() && trimmed.length <= 60 && trimmed.all { it in '0'..'9' || it == '.' } && trimmed.count { it == '.' } <= 1,
        ) {
            "\"$trimmed\" is not a number like 1234.56"
        }
        return ExactDecimal.parse(if (trimmed.startsWith('.')) "0$trimmed" else trimmed.removeSuffix("."))
    }

    private val ISO =
        Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?")

    /**
     * Epoch milliseconds for ISO dates (`2026-01-15`, `2026-01-15 10:30`, `2026-01-15T10:30:00Z`, `…+02:00`) and Unix
     * seconds or milliseconds. Times without an offset use [local]. Day-first or month-first dates are rejected.
     */
    internal fun date(
        text: String,
        local: (LocalStamp) -> Long,
    ): Long {
        val trimmed = text.trim()
        if (trimmed.length in 9..13 && trimmed.all { it in '0'..'9' }) {
            val value = trimmed.toLong()
            return if (trimmed.length >= 12) value else value * 1000
        }
        val match = requireNotNull(ISO.matchEntire(trimmed)) { "\"$trimmed\" is not a date like 2026-01-15 10:30" }
        val g = match.groupValues
        val stamp =
            LocalStamp(
                g[1].toInt(),
                g[2].toInt(),
                g[3].toInt(),
                g[4].toIntOrNull() ?: 0,
                g[5].toIntOrNull() ?: 0,
                g[6].toIntOrNull() ?: 0,
            )
        require(
            stamp.month in 1..12 &&
                stamp.day in 1..daysIn(stamp.year, stamp.month) &&
                stamp.hour < 24 &&
                stamp.minute < 60 &&
                stamp.second < 60,
        ) {
            "\"$trimmed\" is not a valid date"
        }
        val fraction = g[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
        val offset = g[8]
        if (offset.isEmpty()) return local(stamp) + fraction
        val utc = utcMillis(stamp) + fraction
        if (offset == "Z") return utc
        val digits = offset.drop(1).replace(":", "")
        val minutes = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
        require(minutes <= 14 * 60) { "\"$offset\" is not a valid time zone offset" }
        return utc - (if (offset[0] == '+') minutes else -minutes) * 60_000L
    }

    private fun daysIn(
        year: Int,
        month: Int,
    ) = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Milliseconds since the epoch for a UTC date and time (proleptic Gregorian calendar). */
    fun utcMillis(stamp: LocalStamp): Long {
        val y = if (stamp.month <= 2) stamp.year - 1L else stamp.year.toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthIndex = (stamp.month + 9) % 12
        val dayOfYear = (153 * monthIndex + 2) / 5 + stamp.day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        val days = era * 146_097 + dayOfEra - 719_468
        return ((days * 24 + stamp.hour) * 60 + stamp.minute) * 60_000L + stamp.second * 1000L
    }
}

/** Import receipts and the outcome of each row; both are part of encrypted backups. */
internal class CsvImportRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS csv_imports (id TEXT PRIMARY KEY, file_name TEXT NOT NULL, file_hash TEXT NOT NULL, " +
                "imported_at INTEGER NOT NULL, account_id TEXT NOT NULL, rows INTEGER NOT NULL, imported INTEGER NOT NULL, " +
                "already_imported INTEGER NOT NULL, invalid INTEGER NOT NULL, reversed_at INTEGER)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS csv_import_rows (import_id TEXT NOT NULL REFERENCES csv_imports(id), line INTEGER NOT NULL, " +
                "status TEXT NOT NULL, activity_id TEXT, message TEXT, PRIMARY KEY(import_id, line))",
        )
    }

    fun receipts(): List<CsvImportReceipt> =
        db.query("SELECT * FROM csv_imports ORDER BY imported_at DESC, id").map { row ->
            CsvImportReceipt(
                row[0]!!,
                row[1]!!,
                row[2]!!,
                row[3]!!.toLong(),
                AccountId(row[4]!!),
                row[5]!!.toInt(),
                row[6]!!.toInt(),
                row[7]!!.toInt(),
                row[8]!!.toInt(),
                row[9]?.toLong(),
            )
        }

    fun rows(id: String): List<CsvReceiptRow> =
        db.query("SELECT line, status, activity_id, message FROM csv_import_rows WHERE import_id=? ORDER BY line", listOf(id)).map {
            CsvReceiptRow(it[0]!!.toInt(), CsvRowStatus.valueOf(it[1]!!), it[2]?.let(::ActivityId), it[3])
        }

    fun save(
        receipt: CsvImportReceipt,
        rows: List<CsvReceiptRow>,
    ) {
        db.execute(
            "INSERT INTO csv_imports VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
            listOf(
                receipt.id,
                receipt.fileName,
                receipt.fileHash,
                receipt.importedAtMillis.toString(),
                receipt.account.value,
                receipt.rows.toString(),
                receipt.imported.toString(),
                receipt.alreadyImported.toString(),
                receipt.invalid.toString(),
            ),
        )
        rows.forEach {
            db.execute(
                "INSERT INTO csv_import_rows VALUES (?, ?, ?, ?, ?)",
                listOf(receipt.id, it.line.toString(), it.status.name, it.activity?.value, it.message),
            )
        }
    }

    fun markReversed(
        id: String,
        atMillis: Long,
    ) = db.execute("UPDATE csv_imports SET reversed_at=? WHERE id=?", listOf(atMillis.toString(), id))

    companion object {
        fun hash(text: String) = sha256(text)
    }
}
