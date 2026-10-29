package io.polion.core.bitcoin

import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256

/** Provider UTXOs and ordered comparison evidence for a serialized [BitcoinStagedGraph] scan. */
class BitcoinStagedUtxoComparison(
    private val db: SqliteDatabase,
) {
    init {
        BitcoinStagedGraph.createTables(db)
    }

    /**
     * Holds one address response at a time. [onPayload] runs outside a transaction so callers can save
     * each response's raw provenance immediately, without retaining a map of all provider payloads.
     */
    fun fetchAndCompare(
        transport: EsploraTransport,
        onPayload: (address: String, payload: String) -> Unit = { _, _ -> },
    ): Long {
        clear()
        var after = ""
        while (true) {
            val address =
                db
                    .query("SELECT address FROM bitcoin_graph_work_owners WHERE address>? ORDER BY address LIMIT 1", listOf(after))
                    .singleOrNull()
                    ?.single() ?: break
            val payload = transport.get("/address/$address/utxo")
            addResponse(address, payload)
            onPayload(address, payload)
            after = address
        }
        return compare()
    }

    fun clear() =
        db.transaction {
            SCRATCH_TABLES.forEach { db.execute("DELETE FROM $it") }
        }

    /** Stages one complete Esplora address response; repeated addresses and duplicate outpoints fail. */
    fun addResponse(
        address: String,
        payload: String,
    ) {
        val canonical = bitcoinMainnetAddress(address)
        val utxos = parseEsploraUtxos(canonical, payload)
        db.transaction {
            require(db.query("SELECT 1 FROM bitcoin_graph_work_utxo_responses WHERE address=?", listOf(canonical)).isEmpty()) {
                "Duplicate provider address response"
            }
            utxos.forEach { utxo ->
                val point = listOf(utxo.outpoint.txid, utxo.outpoint.vout.toString())
                require(db.query("SELECT 1 FROM bitcoin_graph_work_provider WHERE tx_id=? AND vout=?", point).isEmpty()) {
                    "Duplicate UTXO across provider responses"
                }
                db.execute(
                    "INSERT INTO bitcoin_graph_work_provider VALUES (?, ?, ?, ?, ?, ?)",
                    point + listOf(utxo.address, utxo.value.toString(), utxo.block?.height?.toString(), utxo.block?.hash),
                )
            }
            db.execute(
                "INSERT INTO bitcoin_graph_work_utxo_responses VALUES (?, ?)",
                listOf(canonical, sha256(payload)),
            )
            db.execute("DELETE FROM bitcoin_graph_work_diffs")
        }
    }

    /** Projects [compareUtxoSets] evidence onto disk and returns discrepancies plus distinct missing parents. */
    fun compare(): Long =
        db.transaction {
            db.execute("DELETE FROM bitcoin_graph_work_diffs")
            val unspent = "w.status IN ('UNSPENT','SPEND_CONFLICTING')"
            db.execute(
                "INSERT INTO bitcoin_graph_work_diffs SELECT NULL, 'UNWATCHED_ADDRESS', p.tx_id, p.vout, 5, " +
                    "CASE WHEN w.tx_id IS NOT NULL AND $unspent THEN 1 ELSE 0 END FROM bitcoin_graph_work_provider p " +
                    "LEFT JOIN bitcoin_graph_work_owned w ON w.tx_id=p.tx_id AND w.vout=p.vout " +
                    "WHERE NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_owners a WHERE a.address=p.address)",
            )
            db.execute(
                "INSERT INTO bitcoin_graph_work_diffs SELECT NULL, 'MISSING_FROM_RECONSTRUCTION', p.tx_id, p.vout, 0, 0 " +
                    "FROM bitcoin_graph_work_provider p JOIN bitcoin_graph_work_owners a ON a.address=p.address " +
                    "WHERE NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_owned w WHERE w.tx_id=p.tx_id AND w.vout=p.vout AND $unspent)",
            )
            db.execute(
                "INSERT INTO bitcoin_graph_work_diffs SELECT NULL, 'MISSING_FROM_PROVIDER', w.tx_id, w.vout, 1, 1 " +
                    "FROM bitcoin_graph_work_owned w WHERE $unspent " +
                    "AND NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_provider p WHERE p.tx_id=w.tx_id AND p.vout=w.vout)",
            )

            fun difference(
                kind: BitcoinUtxoMismatchKind,
                condition: String,
            ) {
                db.execute(
                    "INSERT INTO bitcoin_graph_work_diffs SELECT NULL, ?, w.tx_id, w.vout, ?, 1 " +
                        "FROM bitcoin_graph_work_owned w JOIN bitcoin_graph_work_outputs o ON o.tx_id=w.tx_id AND o.vout=w.vout " +
                        "JOIN bitcoin_graph_work_transactions t ON t.id=w.tx_id " +
                        "JOIN bitcoin_graph_work_provider p ON p.tx_id=w.tx_id AND p.vout=w.vout " +
                        "JOIN bitcoin_graph_work_owners a ON a.address=p.address WHERE $unspent AND ($condition)",
                    listOf(kind.name, kind.ordinal.toString()),
                )
            }
            difference(BitcoinUtxoMismatchKind.VALUE_DIFFERS, "o.value<>p.value")
            difference(BitcoinUtxoMismatchKind.ADDRESS_DIFFERS, "w.address<>p.address")
            difference(BitcoinUtxoMismatchKind.CONFIRMATION_DIFFERS, "t.height IS NOT p.height OR t.hash IS NOT p.hash")
            var position = 0L
            while (true) {
                val row =
                    db
                        .query(
                            "SELECT tx_id, vout, kind_order FROM bitcoin_graph_work_diffs WHERE position IS NULL " +
                                "ORDER BY tx_id, vout, kind_order LIMIT 1",
                        ).singleOrNull() ?: break
                db.execute(
                    "UPDATE bitcoin_graph_work_diffs SET position=? WHERE tx_id=? AND vout=? AND kind_order=?",
                    listOf(position.toString()) + row,
                )
                position++
            }
            position +
                db
                    .query("SELECT COUNT(*) FROM bitcoin_graph_work_missing")
                    .single()
                    .single()!!
                    .toLong()
        }

    companion object {
        val SCRATCH_TABLES =
            listOf("bitcoin_graph_work_provider", "bitcoin_graph_work_utxo_responses", "bitcoin_graph_work_diffs")

        internal fun createTables(db: SqliteDatabase) {
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_provider (tx_id TEXT NOT NULL, vout INTEGER NOT NULL, address TEXT NOT NULL, value TEXT NOT NULL, height INTEGER, hash TEXT, PRIMARY KEY(tx_id,vout))",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_utxo_responses (address TEXT PRIMARY KEY, checksum TEXT NOT NULL)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS bitcoin_graph_work_diffs (position INTEGER UNIQUE, kind TEXT NOT NULL, tx_id TEXT NOT NULL, vout INTEGER NOT NULL, kind_order INTEGER NOT NULL, reconstructed INTEGER NOT NULL, PRIMARY KEY(tx_id,vout,kind_order))",
            )
            db.execute(
                "CREATE INDEX IF NOT EXISTS bitcoin_graph_work_diff_position ON bitcoin_graph_work_diffs(position,tx_id,vout,kind_order)",
            )
        }
    }
}
