package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256

/**
 * Disk-backed graph reconstruction for a serialized scan. Each payload is parsed separately; graph-wide
 * validation, conflict propagation and ownership projection operate on indexed SQLite scratch rows.
 * The caller must not clear or rebuild these tables while reading the resulting graph.
 */
class BitcoinStagedGraph(
    private val db: SqliteDatabase,
) {
    init {
        createTables(db)
    }

    fun rebuild(
        payloads: Iterable<String>,
        ownedAddresses: Map<String, AccountId>,
    ) = db.transaction {
        SCRATCH_TABLES.forEach { db.execute("DELETE FROM $it") }
        ownedAddresses.forEach { (address, account) ->
            db.execute(
                "INSERT OR REPLACE INTO bitcoin_graph_work_owners VALUES (?, ?)",
                listOf(bitcoinMainnetAddress(address), account.value),
            )
        }
        payloads.forEach { payload -> stage(payload) }
        validateParents()
        reject(
            "SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id " +
                "WHERE i.parent_id IS NOT NULL AND t.height IS NOT NULL GROUP BY i.parent_id, i.parent_vout HAVING COUNT(*)>1 LIMIT 1",
            "Provider reported conflicting confirmed transactions",
        )
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_work_queue SELECT i.tx_id FROM bitcoin_graph_work_inputs i " +
                "JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id WHERE t.height IS NULL AND EXISTS " +
                "(SELECT 1 FROM bitcoin_graph_work_inputs w JOIN bitcoin_graph_work_transactions c ON c.id=w.tx_id " +
                "WHERE w.parent_id=i.parent_id AND w.parent_vout=i.parent_vout AND c.height IS NOT NULL)",
        )
        propagate(BitcoinTransactionState.REPLACED)
        // A competition is unresolved only when at least two of its spenders survived replacement.
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_work_queue SELECT i.tx_id FROM bitcoin_graph_work_inputs i " +
                "JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id WHERE t.state<>'REPLACED' AND i.parent_id IS NOT NULL " +
                "AND (SELECT COUNT(*) FROM bitcoin_graph_work_inputs s JOIN bitcoin_graph_work_transactions st ON st.id=s.tx_id " +
                "WHERE s.parent_id=i.parent_id AND s.parent_vout=i.parent_vout AND st.state<>'REPLACED')>1 " +
                "AND NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_inputs c JOIN bitcoin_graph_work_transactions ct ON ct.id=c.tx_id " +
                "WHERE c.parent_id=i.parent_id AND c.parent_vout=i.parent_vout AND ct.height IS NOT NULL)",
        )
        propagate(BitcoinTransactionState.CONFLICTING)
        projectOwnedOutputs()
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_work_missing SELECT i.parent_id, i.parent_vout " +
                "FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_owners a ON a.address=i.address " +
                "WHERE i.parent_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_transactions t WHERE t.id=i.parent_id)",
        )
    }

    /** Reads one transaction, without retaining other transactions or their payloads. */
    fun transaction(id: String): BitcoinGraphTransaction? =
        db
            .query("SELECT payload FROM bitcoin_graph_work_transactions WHERE id=?", listOf(id))
            .singleOrNull()
            ?.let { parseBitcoinGraphTransaction(it.single()!!) }

    fun state(id: String): BitcoinTransactionState? =
        db
            .query("SELECT state FROM bitcoin_graph_work_transactions WHERE id=?", listOf(id))
            .singleOrNull()
            ?.let { BitcoinTransactionState.valueOf(it.single()!!) }

    private fun stage(payload: String) {
        val tx = parseBitcoinGraphTransaction(payload)
        val checksum = sha256(payload)
        val previous = db.query("SELECT checksum FROM bitcoin_graph_work_transactions WHERE id=?", listOf(tx.id)).singleOrNull()
        require(previous == null || previous.single() == checksum) { "Transaction has conflicting observations" }
        if (previous != null) return
        db.execute(
            "INSERT INTO bitcoin_graph_work_transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                tx.id,
                checksum,
                payload,
                tx.fee.toString(),
                if (tx.coinbase) "1" else "0",
                tx.block?.height?.toString(),
                tx.block?.hash,
                tx.blockTime?.toString(),
                if (tx.confirmed) "CONFIRMED" else "PENDING",
            ),
        )
        tx.inputs.forEach { input ->
            db.execute(
                "INSERT INTO bitcoin_graph_work_inputs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    tx.id,
                    input.index.toString(),
                    input.spends?.txid,
                    input.spends?.vout?.toString(),
                    input.prevout?.value?.toString(),
                    input.prevout?.scriptPubKey,
                    input.prevout?.scriptType,
                    input.prevout?.address,
                    input.sequence?.toString(),
                ),
            )
        }
        tx.outputs.forEach { output ->
            db.execute(
                "INSERT INTO bitcoin_graph_work_outputs VALUES (?, ?, ?, ?, ?, ?)",
                listOf(
                    tx.id,
                    output.outpoint.vout.toString(),
                    output.output.value.toString(),
                    output.output.scriptPubKey,
                    output.output.scriptType,
                    output.output.address,
                ),
            )
        }
    }

    private fun validateParents() {
        reject(
            "SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions p ON p.id=i.parent_id " +
                "LEFT JOIN bitcoin_graph_work_outputs o ON o.tx_id=i.parent_id AND o.vout=i.parent_vout " +
                "WHERE o.tx_id IS NULL OR i.value<>o.value OR i.address IS NOT o.address " +
                "OR (i.script IS NOT NULL AND o.script IS NOT NULL AND i.script<>o.script) LIMIT 1",
            "Input prevout disagrees with its creating transaction",
        )
        reject(
            "SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id " +
                "JOIN bitcoin_graph_work_transactions p ON p.id=i.parent_id " +
                "WHERE t.height IS NOT NULL AND (p.height IS NULL OR p.height>t.height) LIMIT 1",
            "Confirmed transaction spends a later output",
        )
        reject(
            "SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id " +
                "JOIN bitcoin_graph_work_transactions p ON p.id=i.parent_id " +
                "WHERE p.coinbase=1 AND t.height IS NOT NULL AND t.height<p.height+100 LIMIT 1",
            "Immature coinbase spend",
        )
    }

    private fun reject(
        sql: String,
        message: String,
    ) {
        require(db.query(sql).isEmpty()) { message }
    }

    private fun propagate(state: BitcoinTransactionState) {
        while (true) {
            val id = db.query("SELECT id FROM bitcoin_graph_work_queue ORDER BY id LIMIT 1").singleOrNull()?.single() ?: break
            db.execute("DELETE FROM bitcoin_graph_work_queue WHERE id=?", listOf(id))
            val current = this.state(id)!!
            require(current != BitcoinTransactionState.CONFIRMED) { "Confirmed transaction depends on an invalid transaction" }
            if (current == state || current == BitcoinTransactionState.REPLACED) continue
            db.execute("UPDATE bitcoin_graph_work_transactions SET state=? WHERE id=?", listOf(state.name, id))
            db.execute(
                "INSERT OR IGNORE INTO bitcoin_graph_work_queue SELECT DISTINCT tx_id FROM bitcoin_graph_work_inputs WHERE parent_id=?",
                listOf(id),
            )
        }
    }

    private fun projectOwnedOutputs() {
        db.execute(
            "INSERT INTO bitcoin_graph_work_owned SELECT o.tx_id, o.vout, a.account_id, o.address, " +
                "CASE WHEN t.state='REPLACED' THEN 'INVALIDATED' WHEN t.state='CONFLICTING' THEN 'CONFLICTING' " +
                "WHEN EXISTS (SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions s ON s.id=i.tx_id " +
                "WHERE i.parent_id=o.tx_id AND i.parent_vout=o.vout AND s.state='CONFIRMED') THEN 'SPENT' " +
                "WHEN EXISTS (SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions s ON s.id=i.tx_id " +
                "WHERE i.parent_id=o.tx_id AND i.parent_vout=o.vout AND s.state='PENDING') THEN 'SPEND_PENDING' " +
                "WHEN EXISTS (SELECT 1 FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions s ON s.id=i.tx_id " +
                "WHERE i.parent_id=o.tx_id AND i.parent_vout=o.vout AND s.state='CONFLICTING') THEN 'SPEND_CONFLICTING' " +
                "ELSE 'UNSPENT' END FROM bitcoin_graph_work_outputs o JOIN bitcoin_graph_work_owners a ON a.address=o.address " +
                "JOIN bitcoin_graph_work_transactions t ON t.id=o.tx_id",
        )
    }

    companion object {
        /** Normalized rows are transient and are never part of a portable backup. */
        val SCRATCH_TABLES =
            listOf(
                "bitcoin_graph_work_transactions",
                "bitcoin_graph_work_inputs",
                "bitcoin_graph_work_outputs",
                "bitcoin_graph_work_owners",
                "bitcoin_graph_work_owned",
                "bitcoin_graph_work_missing",
                "bitcoin_graph_work_queue",
            ) + BitcoinStagedUtxoComparison.SCRATCH_TABLES

        internal fun createTables(db: SqliteDatabase) {
            BitcoinStagedUtxoComparison.createTables(db)
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_transactions (id TEXT PRIMARY KEY, checksum TEXT NOT NULL, payload TEXT NOT NULL, fee TEXT NOT NULL, coinbase INTEGER NOT NULL, height INTEGER, hash TEXT, block_time INTEGER, state TEXT NOT NULL)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_inputs (tx_id TEXT NOT NULL, input_index INTEGER NOT NULL, parent_id TEXT, parent_vout INTEGER, value TEXT, script TEXT, script_type TEXT, address TEXT, sequence INTEGER, PRIMARY KEY(tx_id, input_index))",
            )
            db.execute(
                "CREATE INDEX IF NOT EXISTS bitcoin_graph_work_spenders ON bitcoin_graph_work_inputs(parent_id, parent_vout, tx_id)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_outputs (tx_id TEXT NOT NULL, vout INTEGER NOT NULL, value TEXT NOT NULL, script TEXT, script_type TEXT, address TEXT, PRIMARY KEY(tx_id, vout))",
            )
            db.execute("CREATE TABLE IF NOT EXISTS bitcoin_graph_work_owners (address TEXT PRIMARY KEY, account_id TEXT NOT NULL)")
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_owned (tx_id TEXT NOT NULL, vout INTEGER NOT NULL, account_id TEXT NOT NULL, address TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(tx_id, vout))",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_missing (tx_id TEXT NOT NULL, vout INTEGER NOT NULL, PRIMARY KEY(tx_id, vout))",
            )
            db.execute("CREATE TABLE IF NOT EXISTS bitcoin_graph_work_queue (id TEXT PRIMARY KEY)")
        }
    }
}
