package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.storage.RawObservation
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Disk-backed working set for one fresh Bitcoin scan. A caller must serialize scans per database. */
class BitcoinScanStage(
    private val db: SqliteDatabase,
    private val store: Store,
    private val activities: ActivityRepository,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_stage (id TEXT PRIMARY KEY, namespace TEXT NOT NULL, external_id TEXT NOT NULL, network TEXT NOT NULL, source_id TEXT NOT NULL, retrieved_at TEXT NOT NULL, payload TEXT NOT NULL, payload_version TEXT NOT NULL, checksum TEXT NOT NULL, context TEXT NOT NULL, block_hash TEXT, block_height INTEGER, block_index INTEGER)",
        )
        db.execute("CREATE INDEX IF NOT EXISTS bitcoin_scan_stage_block ON bitcoin_scan_stage(block_hash, id)")
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_blocks (hash TEXT PRIMARY KEY, height INTEGER NOT NULL, resolved INTEGER NOT NULL)",
        )
        db.execute("CREATE TABLE IF NOT EXISTS bitcoin_scan_present (id TEXT PRIMARY KEY)")
    }

    /** Discard any incomplete scan after process death before starting a new scan. */
    fun reset() = clear()

    fun clear() =
        db.transaction {
            db.execute("DELETE FROM bitcoin_scan_present")
            db.execute("DELETE FROM bitcoin_scan_stage")
            db.execute("DELETE FROM bitcoin_scan_blocks")
        }

    fun addPage(observations: List<RawObservation>) =
        db.transaction {
            observations.forEach { observation ->
                require(observation.namespace == "bitcoin" && observation.network == BITCOIN_MAINNET.value)
                require(observation.id == "bitcoin:${observation.externalId}" && observation.checksum == sha256(observation.payload))
                val previous = db.query("SELECT payload FROM bitcoin_scan_stage WHERE id=?", listOf(observation.id)).singleOrNull()
                require(previous == null || previous.single() == observation.payload) { "Transaction changed during address scan" }
                if (previous == null) {
                    val status =
                        Json
                            .parseToJsonElement(observation.payload)
                            .jsonObject
                            .getValue("status")
                            .jsonObject
                    val confirmed = status.getValue("confirmed").jsonPrimitive.boolean
                    val hash = if (confirmed) status.getValue("block_hash").jsonPrimitive.content else null
                    val height = if (confirmed) status.getValue("block_height").jsonPrimitive.long else null
                    if (hash != null) {
                        require(hash.matches(Regex("[0-9a-f]{64}")) && height != null && height >= 0)
                        db.execute("INSERT OR IGNORE INTO bitcoin_scan_blocks VALUES (?, ?, 0)", listOf(hash, height.toString()))
                        val recorded =
                            db
                                .query(
                                    "SELECT height FROM bitcoin_scan_blocks WHERE hash=?",
                                    listOf(hash),
                                ).single()
                                .single()!!
                                .toLong()
                        require(recorded == height) { "Conflicting block height" }
                    }
                    db.execute(
                        "INSERT INTO bitcoin_scan_stage VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                        listOf(
                            observation.id,
                            observation.namespace,
                            observation.externalId,
                            observation.network,
                            observation.sourceId,
                            observation.retrievedAt,
                            observation.payload,
                            observation.payloadVersion,
                            observation.checksum,
                            observation.context,
                            hash,
                            height?.toString(),
                        ),
                    )
                }
            }
        }

    /** Fetch each block list once; keep one block and a bounded number of transaction payloads in memory. */
    fun resolveBlocks(
        transport: EsploraTransport,
        onBlock: (resolved: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val total =
            db
                .query("SELECT COUNT(*) FROM bitcoin_scan_blocks")
                .single()
                .single()!!
                .toInt()
        var resolved =
            db
                .query("SELECT COUNT(*) FROM bitcoin_scan_blocks WHERE resolved=1")
                .single()
                .single()!!
                .toInt()
        while (true) {
            val block =
                db.query("SELECT hash, height FROM bitcoin_scan_blocks WHERE resolved=0 ORDER BY hash LIMIT 1").singleOrNull() ?: break
            val hash = block[0]!!
            val height = block[1]!!.toLong()
            val payload = transport.get("/block/$hash/txids")
            val txids = Json.parseToJsonElement(payload).jsonArray.map { it.jsonPrimitive.content }
            require(txids.isNotEmpty() && txids.all { it.matches(Regex("[0-9a-f]{64}")) } && txids.distinct().size == txids.size)
            val indices = txids.withIndex().associate { it.value to it.index }
            var after = ""
            var blockSaved = false
            while (true) {
                val rows =
                    db.query(
                        "SELECT id, namespace, external_id, network, source_id, retrieved_at, payload, payload_version, checksum, context FROM bitcoin_scan_stage WHERE block_hash=? AND id>? ORDER BY id LIMIT 1",
                        listOf(hash, after),
                    )
                if (rows.isEmpty()) break
                db.transaction {
                    rows.forEach { row ->
                        val raw = row.toObservation()
                        val index = requireNotNull(indices[raw.externalId]) { "Transaction absent from claimed block" }
                        if (!blockSaved) {
                            store.ingestInTransaction(
                                RawObservation(
                                    "bitcoin-block:$hash",
                                    "bitcoin-block",
                                    hash,
                                    BITCOIN_MAINNET.value,
                                    raw.sourceId,
                                    raw.retrievedAt,
                                    payload,
                                    "esplora-txids-1",
                                    sha256(payload),
                                    "esplora-block-txids",
                                ),
                            )
                            blockSaved = true
                        }
                        val resolved =
                            raw.copy(
                                sourceId =
                                    if (raw.context.startsWith("bitcoin-order:")) {
                                        raw.sourceId
                                    } else {
                                        raw.sourceId +
                                            ":block-order"
                                    },
                                context = "bitcoin-order:$height:$index:$hash",
                            )
                        store.ingestInTransaction(resolved)
                        db.execute(
                            "UPDATE bitcoin_scan_stage SET source_id=?, context=?, block_index=? WHERE id=?",
                            listOf(resolved.sourceId, resolved.context, index.toString(), raw.id),
                        )
                    }
                }
                after = rows.last()[0]!!
            }
            require(blockSaved)
            db.execute("UPDATE bitcoin_scan_blocks SET resolved=1 WHERE hash=?", listOf(hash))
            resolved++
            runCatching { onBlock(resolved, total) }
        }
    }

    /** Confirm every recorded block is still on the provider's active chain. */
    fun validateBlocks(transport: EsploraTransport) {
        db.query("SELECT hash, height FROM bitcoin_scan_blocks ORDER BY height").forEach { row ->
            require(transport.get("/block-height/${row[1]}").trim() == row[0]) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
        }
    }

    /** Replace only the ordering provenance of matching active activities. */
    fun applyVerifiedOrder(ownedAddresses: Map<String, AccountId>) {
        val decoder = BitcoinDecoder(ownedAddresses)
        db.transaction {
            var after = ""
            while (true) {
                val row =
                    db
                        .query(
                            "SELECT id, namespace, external_id, network, source_id, retrieved_at, payload, payload_version, checksum, context FROM bitcoin_scan_stage WHERE id>? ORDER BY id LIMIT 1",
                            listOf(after),
                        ).singleOrNull() ?: break
                val decoded = decoder.decode(row.toObservation()).single()
                val previous = activities.history(decoded.id).last()
                require(
                    previous.status == ActivityStatus.CONFIRMED &&
                        previous.copy(orderingKey = decoded.orderingKey, sources = decoded.sources) == decoded,
                ) {
                    "Saved Bitcoin transaction no longer matches its decoded activity; refresh history"
                }
                activities.saveInTransaction(decoded)
                after = row[0]!!
            }
        }
    }

    /** Apply all staged transactions and reversals together after the caller's scan validation. */
    fun applyComplete(
        ownedAddresses: Map<String, AccountId>,
        reconciledAccounts: Set<AccountId> = ownedAddresses.values.toSet(),
        requireBlockPositions: Boolean = true,
    ) {
        require(ownedAddresses.isNotEmpty())
        require(!requireBlockPositions || db.query("SELECT hash FROM bitcoin_scan_blocks WHERE resolved=0 LIMIT 1").isEmpty()) {
            "Block positions are incomplete"
        }
        val decoder = BitcoinDecoder(ownedAddresses)
        db.transaction {
            db.execute("DELETE FROM bitcoin_scan_present")
            var after = ""
            while (true) {
                val rows =
                    db.query(
                        "SELECT id, namespace, external_id, network, source_id, retrieved_at, payload, payload_version, checksum, context FROM bitcoin_scan_stage WHERE id>? ORDER BY id LIMIT 1",
                        listOf(after),
                    )
                if (rows.isEmpty()) break
                rows.forEach { row ->
                    decoder.decode(row.toObservation()).forEach { activity ->
                        db.execute("INSERT INTO bitcoin_scan_present VALUES (?)", listOf(activity.id.value))
                        activities.saveInTransaction(activity)
                    }
                }
                after = rows.last()[0]!!
            }
            val missing =
                db.query(
                    "SELECT a.id FROM ledger_activities a WHERE a.manual=0 AND a.decoder=? AND a.status<>'REVERSED' AND a.revision=(SELECT a2.revision FROM ledger_activities a2 WHERE a2.id=a.id ORDER BY CAST(a2.revision AS INTEGER) DESC LIMIT 1) AND NOT EXISTS (SELECT 1 FROM bitcoin_scan_present p WHERE p.id=a.id)",
                    listOf(decoder.version),
                )
            missing.forEach { row ->
                val latest = activities.history(ActivityId(row.single()!!)).last()
                if (latest.movements.any { it.accountId in reconciledAccounts }) {
                    activities.saveInTransaction(
                        latest.copy(status = ActivityStatus.REVERSED),
                    )
                }
            }
        }
    }
}

private fun List<String?>.toObservation() =
    RawObservation(this[0]!!, this[1]!!, this[2]!!, this[3]!!, this[4]!!, this[5]!!, this[6]!!, this[7]!!, this[8]!!, this[9]!!)
