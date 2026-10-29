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
        BitcoinStagedGraph.createTables(db)
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_stage (id TEXT PRIMARY KEY, namespace TEXT NOT NULL, external_id TEXT NOT NULL, network TEXT NOT NULL, source_id TEXT NOT NULL, retrieved_at TEXT NOT NULL, payload TEXT NOT NULL, payload_version TEXT NOT NULL, checksum TEXT NOT NULL, context TEXT NOT NULL, block_hash TEXT, block_height INTEGER, block_index INTEGER)",
        )
        db.execute("CREATE INDEX IF NOT EXISTS bitcoin_scan_stage_block ON bitcoin_scan_stage(block_hash, id)")
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_blocks (hash TEXT PRIMARY KEY, height INTEGER NOT NULL, resolved INTEGER NOT NULL)",
        )
        db.execute("CREATE TABLE IF NOT EXISTS bitcoin_scan_present (id TEXT PRIMARY KEY)")
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_session (id INTEGER PRIMARY KEY CHECK (id = 1), generation INTEGER NOT NULL, fingerprint TEXT NOT NULL, anchor_hash TEXT NOT NULL, anchor_height INTEGER NOT NULL)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_addresses (address TEXT PRIMARY KEY, account_id TEXT NOT NULL, mempool_digest TEXT)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS bitcoin_scan_seen (address TEXT NOT NULL, tx_id TEXT NOT NULL, confirmed INTEGER NOT NULL, PRIMARY KEY(address, tx_id))",
        )
    }

    companion object {
        /** Transient scan tables: excluded from backups, empty in restore staging, and cleared when a scan completes or is discarded. */
        val SCRATCH_TABLES =
            listOf(
                "bitcoin_scan_stage",
                "bitcoin_scan_blocks",
                "bitcoin_scan_present",
                "bitcoin_scan_session",
                "bitcoin_scan_addresses",
                "bitcoin_scan_seen",
            ) + BitcoinStagedGraph.SCRATCH_TABLES
    }

    fun clear() = db.transaction { clearInTransaction() }

    fun clearInTransaction() {
        SCRATCH_TABLES.forEach { db.execute("DELETE FROM $it") }
    }

    /** The pending scan whose staged pages and per-address progress may be resumed; null when none is pending. */
    fun session(): BitcoinScanSession? =
        db
            .query("SELECT generation, fingerprint, anchor_hash, anchor_height FROM bitcoin_scan_session WHERE id=1")
            .singleOrNull()
            ?.let { BitcoinScanSession(it[0]!!.toLong(), it[1]!!, it[2]!!, it[3]!!.toLong()) }

    fun createSession(
        session: BitcoinScanSession,
        addresses: List<BitcoinScanAddress>,
    ) = db.transaction {
        SCRATCH_TABLES.forEach { db.execute("DELETE FROM $it") }
        db.execute(
            "INSERT INTO bitcoin_scan_session VALUES (1, ?, ?, ?, ?)",
            listOf(session.generation.toString(), session.fingerprint, session.anchorHash, session.anchorHeight.toString()),
        )
        addresses.forEach { address ->
            db.execute("INSERT INTO bitcoin_scan_addresses VALUES (?, ?, NULL)", listOf(address.address, address.accountId.value))
        }
    }

    fun scanAddresses(): List<BitcoinScanAddress> =
        db
            .query("SELECT address, account_id, mempool_digest FROM bitcoin_scan_addresses ORDER BY address")
            .map { BitcoinScanAddress(it[0]!!, AccountId(it[1]!!), it[2]) }

    /** Staged transaction payloads for the pending scan, one per transaction id. */
    fun payloads(): Iterable<String> =
        Iterable {
            sequence {
                var after = ""
                while (true) {
                    val row =
                        db.query("SELECT id, payload FROM bitcoin_scan_stage WHERE id>? ORDER BY id LIMIT 1", listOf(after)).singleOrNull()
                            ?: break
                    yield(row[1]!!)
                    after = row[0]!!
                }
            }.iterator()
        }

    fun rawChecksums(): Map<String, String> =
        db.query("SELECT external_id, checksum FROM bitcoin_scan_stage ORDER BY id").associate { it[0]!! to it[1]!! }

    /** Records the digest of the unconfirmed-transaction list observed for an address on its first page. */
    fun recordMempool(
        address: String,
        digest: String,
    ) = db.execute("UPDATE bitcoin_scan_addresses SET mempool_digest=? WHERE address=?", listOf(digest, address))

    /** Returns confirmed and unconfirmed transaction counts observed for an address across all resumed pages. */
    fun seenCounts(address: String): Pair<Long, Long> {
        fun count(confirmed: Int) =
            db
                .query("SELECT COUNT(*) FROM bitcoin_scan_seen WHERE address=? AND confirmed=?", listOf(address, confirmed.toString()))
                .single()
                .single()!!
                .toLong()
        return count(1) to count(0)
    }

    /** Stages observations; a non-null [address] also records its transactions for that address's verification. */
    fun addPage(
        observations: List<RawObservation>,
        address: String? = null,
    ) = db.transaction {
        observations.forEach { observation ->
            require(observation.namespace == "bitcoin" && observation.network == BITCOIN_MAINNET.value)
            require(observation.id == "bitcoin:${observation.externalId}" && observation.checksum == sha256(observation.payload))
            val status =
                Json
                    .parseToJsonElement(observation.payload)
                    .jsonObject
                    .getValue("status")
                    .jsonObject
            val confirmed = status.getValue("confirmed").jsonPrimitive.boolean
            val previous = db.query("SELECT payload FROM bitcoin_scan_stage WHERE id=?", listOf(observation.id)).singleOrNull()
            require(previous == null || previous.single() == observation.payload) {
                throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            if (previous == null) {
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
                    require(recorded == height) { throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED) }
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
            address?.let {
                db.execute(
                    "INSERT OR REPLACE INTO bitcoin_scan_seen VALUES (?, ?, ?)",
                    listOf(it, observation.id, if (confirmed) "1" else "0"),
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
                            "SELECT id, namespace, external_id, network, source_id, retrieved_at, payload, payload_version, checksum, context, block_hash, block_height FROM bitcoin_scan_stage WHERE id>? ORDER BY id LIMIT 1",
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
        graph: BitcoinUtxoGraph? = null,
    ) = db.transaction {
        applyCompleteInTransaction(ownedAddresses, reconciledAccounts, requireBlockPositions, graph)
    }

    /** Caller owns the transaction, including durable graph and successful-refresh metadata. */
    fun applyCompleteInTransaction(
        ownedAddresses: Map<String, AccountId>,
        reconciledAccounts: Set<AccountId> = ownedAddresses.values.toSet(),
        requireBlockPositions: Boolean = true,
        graph: BitcoinUtxoGraph? = null,
    ) {
        require(ownedAddresses.isNotEmpty())
        require(!requireBlockPositions || db.query("SELECT hash FROM bitcoin_scan_blocks WHERE resolved=0 LIMIT 1").isEmpty()) {
            "Block positions are incomplete"
        }
        val decoder = BitcoinDecoder(ownedAddresses)
        run {
            db.execute("DELETE FROM bitcoin_scan_present")
            var after = ""
            while (true) {
                val rows =
                    db.query(
                        "SELECT id, namespace, external_id, network, source_id, retrieved_at, payload, payload_version, checksum, context, block_hash, block_height FROM bitcoin_scan_stage WHERE id>? ORDER BY id LIMIT 1",
                        listOf(after),
                    )
                if (rows.isEmpty()) break
                rows.forEach { row ->
                    decoder.decode(row.toObservation()).forEach { decoded ->
                        val state = graph?.states?.get(row[2]!!)
                        val activity =
                            when (state) {
                                BitcoinTransactionState.CONFLICTING -> decoded.copy(status = ActivityStatus.SUPERSEDED)
                                BitcoinTransactionState.REPLACED -> decoded.copy(status = ActivityStatus.REVERSED)
                                else -> decoded
                            }
                        db.execute("INSERT INTO bitcoin_scan_present VALUES (?)", listOf(activity.id.value))
                        val previous = activities.history(activity.id).lastOrNull()
                        val block = row[10]
                        val sameVerifiedPosition =
                            activity.status == ActivityStatus.CONFIRMED &&
                                !activity.orderingKey.startsWith("bitcoin-chain:") &&
                                previous?.orderingKey?.startsWith("bitcoin-chain:") == true &&
                                previous.orderingKey.split(':').getOrNull(1) == row[11]?.padStart(20, '0') &&
                                previous.sources.any { it.value == "bitcoin-block:$block" } &&
                                previous.copy(orderingKey = activity.orderingKey, sources = activity.sources) == activity
                        activities.saveInTransaction(if (sameVerifiedPosition) previous!! else activity)
                    }
                }
                after = rows.last()[0]!!
            }
            val decoderPlaceholders = BitcoinDecoder.compatibleVersions.joinToString(",") { "?" }
            val missing =
                db.query(
                    "SELECT a.id FROM ledger_activities a WHERE a.manual=0 AND a.decoder IN ($decoderPlaceholders) AND a.status<>'REVERSED' AND a.revision=(SELECT a2.revision FROM ledger_activities a2 WHERE a2.id=a.id ORDER BY CAST(a2.revision AS INTEGER) DESC LIMIT 1) AND NOT EXISTS (SELECT 1 FROM bitcoin_scan_present p WHERE p.id=a.id)",
                    BitcoinDecoder.compatibleVersions.sorted(),
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

data class BitcoinScanSession(
    val generation: Long,
    val fingerprint: String,
    val anchorHash: String,
    val anchorHeight: Long,
)

data class BitcoinScanAddress(
    val address: String,
    val accountId: AccountId,
    val mempoolDigest: String?,
)

private fun List<String?>.toObservation() =
    RawObservation(this[0]!!, this[1]!!, this[2]!!, this[3]!!, this[4]!!, this[5]!!, this[6]!!, this[7]!!, this[8]!!, this[9]!!)
