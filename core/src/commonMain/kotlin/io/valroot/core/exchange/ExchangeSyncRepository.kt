package io.valroot.core.exchange

import io.valroot.core.model.AccountId
import io.valroot.core.storage.SqliteDatabase

/**
 * How far back and forward one history stream of an exchange account has been read. [fromMillis] until [toMillis] is
 * covered without gaps; [complete] means reading further back stopped for good (the exchange keeps nothing older, or
 * nothing older was found).
 */
data class ExchangeStreamState(
    val stream: String,
    val fromMillis: Long,
    val toMillis: Long,
    val complete: Boolean,
    /** Windows read back in a row without any record, to stop at the account's start. */
    val emptyWindows: Int,
)

/** One saved exchange record: Bybit's row as received, with when it happened and its raw-observation source. */
data class ExchangeRow(
    val stream: String,
    val key: String,
    val atMillis: Long,
    val payload: String,
    val source: String,
)

/** The state of one exchange account: its last refresh, a failure if any, and what history does not cover. */
data class ExchangeAccountStatus(
    val checkedAtMillis: Long?,
    val error: String?,
    /** Earliest moment the history covers; balances before it are one opening amount per coin. */
    val historyFromMillis: Long?,
    val keyExpiresAtMillis: Long?,
)

/**
 * Exchange sync progress, the records read so far and each account's status. Records are kept per account, stream and
 * key, so reading a window again replaces a record with Bybit's latest version of it (a withdrawal that completed).
 * Part of backups; API keys are not.
 */
class ExchangeSyncRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_sync (account TEXT NOT NULL, stream TEXT NOT NULL, covered_from INTEGER NOT NULL, " +
                "covered_to INTEGER NOT NULL, complete INTEGER NOT NULL, empty_windows INTEGER NOT NULL, PRIMARY KEY(account, stream))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_rows (account TEXT NOT NULL, stream TEXT NOT NULL, key TEXT NOT NULL, " +
                "at INTEGER NOT NULL, payload TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(account, stream, key))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_cursors (account TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(account, key))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_transfers (activity_id TEXT PRIMARY KEY, account TEXT NOT NULL, chain TEXT NOT NULL, " +
                "tx_id TEXT NOT NULL)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_accounts (account TEXT PRIMARY KEY, checked_at INTEGER, error TEXT, " +
                "history_from INTEGER, key_expires INTEGER)",
        )
    }

    fun streams(account: AccountId): Map<String, ExchangeStreamState> =
        db
            .query(
                "SELECT stream, covered_from, covered_to, complete, empty_windows FROM exchange_sync WHERE account=?",
                listOf(account.value),
            ).associate { it[0]!! to ExchangeStreamState(it[0]!!, it[1]!!.toLong(), it[2]!!.toLong(), it[3] == "1", it[4]!!.toInt()) }

    fun saveStream(
        account: AccountId,
        state: ExchangeStreamState,
    ) = db.execute(
        "INSERT OR REPLACE INTO exchange_sync VALUES (?, ?, CAST(? AS INTEGER), CAST(? AS INTEGER), ?, ?)",
        listOf(
            account.value,
            state.stream,
            state.fromMillis.toString(),
            state.toMillis.toString(),
            if (state.complete) "1" else "0",
            state.emptyWindows.toString(),
        ),
    )

    fun rows(account: AccountId): List<ExchangeRow> =
        db
            .query(
                "SELECT stream, key, at, payload, source FROM exchange_rows WHERE account=? ORDER BY at, stream, key",
                listOf(account.value),
            ).map { ExchangeRow(it[0]!!, it[1]!!, it[2]!!.toLong(), it[3]!!, it[4]!!) }

    fun saveRow(
        account: AccountId,
        row: ExchangeRow,
    ) = db.execute(
        "INSERT OR REPLACE INTO exchange_rows VALUES (?, ?, ?, CAST(? AS INTEGER), ?, ?)",
        listOf(account.value, row.stream, row.key, row.atMillis.toString(), row.payload, row.source),
    )

    fun status(account: AccountId): ExchangeAccountStatus =
        db
            .query("SELECT checked_at, error, history_from, key_expires FROM exchange_accounts WHERE account=?", listOf(account.value))
            .singleOrNull()
            ?.let { ExchangeAccountStatus(it[0]?.toLong(), it[1], it[2]?.toLong(), it[3]?.toLong()) }
            ?: ExchangeAccountStatus(null, null, null, null)

    fun saveStatus(
        account: AccountId,
        status: ExchangeAccountStatus,
    ) = db.execute(
        "INSERT OR REPLACE INTO exchange_accounts VALUES (?, CAST(? AS INTEGER), ?, CAST(? AS INTEGER), CAST(? AS INTEGER))",
        listOf(
            account.value,
            status.checkedAtMillis?.toString(),
            status.error,
            status.historyFromMillis?.toString(),
            status.keyExpiresAtMillis?.toString(),
        ),
    )

    /** On-chain transactions behind an account's deposits and withdrawals, by activity. */
    fun transfers(): Map<io.valroot.core.model.ActivityId, ExchangeTransfer> =
        db
            .query("SELECT activity_id, chain, tx_id FROM exchange_transfers")
            .associate {
                io.valroot.core.model
                    .ActivityId(it[0]!!) to ExchangeTransfer(it[1]!!, it[2]!!)
            }

    fun replaceTransfers(
        account: AccountId,
        transfers: Map<io.valroot.core.model.ActivityId, ExchangeTransfer>,
    ) {
        db.execute("DELETE FROM exchange_transfers WHERE account=?", listOf(account.value))
        transfers.forEach { (activity, transfer) ->
            db.execute(
                "INSERT OR REPLACE INTO exchange_transfers VALUES (?, ?, ?, ?)",
                listOf(activity.value, account.value, transfer.chain, transfer.txId),
            )
        }
    }

    /** Small per-account progress values, such as the next trade ID of each Binance symbol. */
    fun cursors(account: AccountId): Map<String, String> =
        db.query("SELECT key, value FROM exchange_cursors WHERE account=?", listOf(account.value)).associate { it[0]!! to it[1]!! }

    fun saveCursor(
        account: AccountId,
        key: String,
        value: String,
    ) = db.execute("INSERT OR REPLACE INTO exchange_cursors VALUES (?, ?, ?)", listOf(account.value, key, value))

    /** Forgets an account's history, so the next refresh reads it again from the exchange. */
    fun forget(account: AccountId) {
        db.execute("DELETE FROM exchange_sync WHERE account=?", listOf(account.value))
        db.execute("DELETE FROM exchange_rows WHERE account=?", listOf(account.value))
        db.execute("DELETE FROM exchange_transfers WHERE account=?", listOf(account.value))
        db.execute("DELETE FROM exchange_cursors WHERE account=? AND key NOT LIKE 'userpair:%'", listOf(account.value))
    }
}
