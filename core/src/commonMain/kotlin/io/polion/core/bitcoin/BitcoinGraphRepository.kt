package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Evidence of competing spenders. A confirmed winner proves exclusion; pending competition is only possible replacement. */
enum class BitcoinReplacementEvidenceKind { POSSIBLE, CONFIRMED }

data class BitcoinReplacementEvidence(
    val outpoint: BitcoinOutpoint,
    val previousTransaction: String,
    val competingTransaction: String,
    val kind: BitcoinReplacementEvidenceKind,
)

data class BitcoinGraphSnapshot(
    val id: Long,
    val providerSourceId: String,
    val anchor: BitcoinBlockAnchor,
    val observedAt: String,
    val graph: BitcoinUtxoGraph,
    val rawChecksums: Map<String, String>,
    val comparison: BitcoinUtxoComparison?,
    val replacementEvidence: List<BitcoinReplacementEvidence>,
)

internal val bitcoinGraphTables =
    listOf(
        "bitcoin_graph_snapshots",
        "bitcoin_graph_scope",
        "bitcoin_graph_transactions",
        "bitcoin_graph_inputs",
        "bitcoin_graph_outputs",
        "bitcoin_graph_owned_outputs",
        "bitcoin_graph_conflicts",
        "bitcoin_graph_missing_parents",
        "bitcoin_graph_utxo_diffs",
        "bitcoin_graph_replacements",
    )

/** Additive Store schema; graph evidence is empty when opening a pre-graph archive. */
internal fun createBitcoinGraphTables(db: SqliteDatabase) {
    db.execute(
        "CREATE TABLE bitcoin_graph_snapshots (id INTEGER PRIMARY KEY, scope_key TEXT NOT NULL, provider_source_id TEXT NOT NULL, anchor_height INTEGER NOT NULL, anchor_hash TEXT NOT NULL, observed_at TEXT NOT NULL, compared INTEGER NOT NULL)",
    )
    db.execute("CREATE INDEX bitcoin_graph_scope_latest ON bitcoin_graph_snapshots(scope_key, id)")
    db.execute(
        "CREATE TABLE bitcoin_graph_scope (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), address TEXT NOT NULL, account_id TEXT NOT NULL, PRIMARY KEY(snapshot_id,address))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_transactions (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), txid TEXT NOT NULL, state TEXT NOT NULL, raw_checksum TEXT NOT NULL, fee TEXT NOT NULL, coinbase INTEGER NOT NULL, block_height INTEGER, block_hash TEXT, block_time INTEGER, PRIMARY KEY(snapshot_id,txid))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_inputs (snapshot_id INTEGER NOT NULL, txid TEXT NOT NULL, position INTEGER NOT NULL, parent_txid TEXT, parent_vout INTEGER, sequence INTEGER, value TEXT, script TEXT, script_type TEXT, address TEXT, PRIMARY KEY(snapshot_id,txid,position), FOREIGN KEY(snapshot_id,txid) REFERENCES bitcoin_graph_transactions(snapshot_id,txid))",
    )
    db.execute("CREATE INDEX bitcoin_graph_spend_lookup ON bitcoin_graph_inputs(snapshot_id,parent_txid,parent_vout)")
    db.execute(
        "CREATE TABLE bitcoin_graph_outputs (snapshot_id INTEGER NOT NULL, txid TEXT NOT NULL, vout INTEGER NOT NULL, value TEXT NOT NULL, script TEXT, script_type TEXT, address TEXT, PRIMARY KEY(snapshot_id,txid,vout), FOREIGN KEY(snapshot_id,txid) REFERENCES bitcoin_graph_transactions(snapshot_id,txid))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_owned_outputs (snapshot_id INTEGER NOT NULL, txid TEXT NOT NULL, vout INTEGER NOT NULL, account_id TEXT NOT NULL, address TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(snapshot_id,txid,vout), FOREIGN KEY(snapshot_id,txid,vout) REFERENCES bitcoin_graph_outputs(snapshot_id,txid,vout))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_conflicts (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), parent_txid TEXT NOT NULL, parent_vout INTEGER NOT NULL, spender TEXT NOT NULL, confirmed_winner TEXT, PRIMARY KEY(snapshot_id,parent_txid,parent_vout,spender))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_missing_parents (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), kind TEXT NOT NULL, txid TEXT NOT NULL, vout INTEGER NOT NULL, PRIMARY KEY(snapshot_id,kind,txid,vout))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_utxo_diffs (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), position INTEGER NOT NULL, kind TEXT NOT NULL, txid TEXT NOT NULL, vout INTEGER NOT NULL, reconstructed INTEGER NOT NULL, provider_address TEXT, provider_value TEXT, provider_height INTEGER, provider_hash TEXT, PRIMARY KEY(snapshot_id,position))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_replacements (snapshot_id INTEGER NOT NULL REFERENCES bitcoin_graph_snapshots(id), parent_txid TEXT NOT NULL, parent_vout INTEGER NOT NULL, previous_txid TEXT NOT NULL, competing_txid TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(snapshot_id,parent_txid,parent_vout,previous_txid,competing_txid))",
    )
}

/** Append-only scan evidence. Call [saveInTransaction] inside the ledger/raw-observation apply transaction. */
class BitcoinGraphRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    @OptIn(ExperimentalTime::class)
    fun saveInTransaction(
        graph: BitcoinUtxoGraph,
        providerSourceId: String,
        anchor: BitcoinBlockAnchor,
        observedAt: String,
        rawChecksums: Map<String, String>,
        comparison: BitcoinUtxoComparison? = null,
        recoveryBaseline: Boolean = false,
    ): Long {
        require(providerSourceId.isNotBlank() && observedAt.endsWith("Z"))
        Instant.parse(observedAt)
        require(rawChecksums.keys == graph.transactions.keys) { "Every graph transaction requires its raw checksum" }
        require(rawChecksums.values.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid raw checksum" }
        val previousId =
            db
                .query(
                    "SELECT id FROM bitcoin_graph_snapshots WHERE scope_key=? ORDER BY id DESC LIMIT 1",
                    listOf(scopeKey(graph.ownedAddresses)),
                ).singleOrNull()
                ?.single()
        val spenders =
            graph.transactions.values
                .flatMap { tx ->
                    tx.inputs.mapNotNull { input -> input.spends?.let { it to tx } }
                }.groupBy({ it.first }, { it.second })
        val id = (db.query("SELECT COALESCE(MAX(id),0)+1 FROM bitcoin_graph_snapshots").single().single()!!).toLong()
        insert(
            "bitcoin_graph_snapshots",
            listOf(
                id.toString(),
                scopeKey(graph.ownedAddresses),
                providerSourceId,
                anchor.height.toString(),
                anchor.hash,
                observedAt,
                if (comparison ==
                    null
                ) {
                    "0"
                } else {
                    "1"
                },
            ),
        )
        graph.ownedAddresses.toSortedMap().forEach { (address, account) ->
            insert("bitcoin_graph_scope", listOf(id.toString(), address, account.value))
        }
        graph.transactions.values.forEach { tx ->
            insert(
                "bitcoin_graph_transactions",
                listOf(
                    id.toString(),
                    tx.id,
                    graph.states.getValue(tx.id).name,
                    rawChecksums.getValue(tx.id),
                    tx.fee.toString(),
                    if (tx.coinbase) "1" else "0",
                    tx.block?.height?.toString(),
                    tx.block?.hash,
                    tx.blockTime?.toString(),
                ),
            )
            tx.inputs.forEach { input ->
                insert(
                    "bitcoin_graph_inputs",
                    listOf(
                        id.toString(),
                        tx.id,
                        input.index.toString(),
                        input.spends?.txid,
                        input.spends?.vout?.toString(),
                        input.sequence?.toString(),
                        input.prevout?.value?.toString(),
                        input.prevout?.scriptPubKey,
                        input.prevout?.scriptType,
                        input.prevout?.address,
                    ),
                )
            }
            tx.outputs.forEach { output ->
                insert(
                    "bitcoin_graph_outputs",
                    listOf(
                        id.toString(),
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
        graph.ownedOutputs.forEach { output ->
            insert(
                "bitcoin_graph_owned_outputs",
                listOf(
                    id.toString(),
                    output.outpoint.txid,
                    output.outpoint.vout.toString(),
                    output.account.value,
                    output.address,
                    output.status.name,
                ),
            )
        }
        graph.conflicts.forEach { conflict ->
            conflict.spenders.forEach { spender ->
                insert(
                    "bitcoin_graph_conflicts",
                    listOf(id.toString(), conflict.outpoint.txid, conflict.outpoint.vout.toString(), spender, conflict.confirmedSpender),
                )
            }
        }

        fun missing(
            kind: String,
            points: List<BitcoinOutpoint>,
        ) = points.distinct().forEach {
            insert("bitcoin_graph_missing_parents", listOf(id.toString(), kind, it.txid, it.vout.toString()))
        }
        missing("GRAPH", graph.missingParents)
        comparison?.let { compared ->
            missing("COMPARISON", compared.missingParents)
            compared.mismatches.forEachIndexed { index, diff ->
                insert(
                    "bitcoin_graph_utxo_diffs",
                    listOf(
                        id.toString(),
                        index.toString(),
                        diff.kind.name,
                        diff.outpoint.txid,
                        diff.outpoint.vout.toString(),
                        if (diff.reconstructed ==
                            null
                        ) {
                            "0"
                        } else {
                            "1"
                        },
                        diff.provider?.address,
                        diff.provider?.value?.toString(),
                        diff.provider
                            ?.block
                            ?.height
                            ?.toString(),
                        diff.provider?.block?.hash,
                    ),
                )
            }
        }
        val evidence = mutableSetOf<BitcoinReplacementEvidence>()
        graph.conflicts.forEach { conflict ->
            conflict.spenders.forEach { old ->
                conflict.spenders
                    .filter {
                        it != old &&
                            (conflict.confirmedSpender == null || it == conflict.confirmedSpender)
                    }.forEach { newer ->
                        evidence.add(
                            BitcoinReplacementEvidence(
                                conflict.outpoint,
                                old,
                                newer,
                                if (conflict.confirmedSpender ==
                                    null
                                ) {
                                    BitcoinReplacementEvidenceKind.POSSIBLE
                                } else {
                                    BitcoinReplacementEvidenceKind.CONFIRMED
                                },
                            ),
                        )
                    }
            }
        }
        if (previousId != null) {
            db
                .query(
                    "SELECT i.txid,i.parent_txid,i.parent_vout FROM bitcoin_graph_inputs i JOIN bitcoin_graph_transactions t ON t.snapshot_id=i.snapshot_id AND t.txid=i.txid WHERE i.snapshot_id=? AND t.state<>'CONFIRMED' AND i.parent_txid IS NOT NULL",
                    listOf(previousId),
                ).forEach { row ->
                    if (row[0] !in graph.transactions) {
                        val point = BitcoinOutpoint(row[1]!!, row[2]!!.toLong())
                        spenders[point].orEmpty().forEach { newer ->
                            evidence.add(
                                BitcoinReplacementEvidence(
                                    point,
                                    row[0]!!,
                                    newer.id,
                                    if (newer.confirmed) {
                                        BitcoinReplacementEvidenceKind.CONFIRMED
                                    } else {
                                        BitcoinReplacementEvidenceKind.POSSIBLE
                                    },
                                ),
                            )
                        }
                    }
                }
            db
                .query(
                    "SELECT parent_txid,parent_vout,previous_txid,competing_txid FROM bitcoin_graph_replacements WHERE snapshot_id=?",
                    listOf(previousId),
                ).forEach { row ->
                    val competing = graph.transactions[row[3]]
                    if (competing != null && row[2] !in graph.transactions) {
                        evidence.add(
                            BitcoinReplacementEvidence(
                                BitcoinOutpoint(row[0]!!, row[1]!!.toLong()),
                                row[2]!!,
                                competing.id,
                                if (competing.confirmed) {
                                    BitcoinReplacementEvidenceKind.CONFIRMED
                                } else {
                                    BitcoinReplacementEvidenceKind.POSSIBLE
                                },
                            ),
                        )
                    }
                }
        }
        evidence.forEach {
            insert(
                "bitcoin_graph_replacements",
                listOf(
                    id.toString(),
                    it.outpoint.txid,
                    it.outpoint.vout.toString(),
                    it.previousTransaction,
                    it.competingTransaction,
                    it.kind.name,
                ),
            )
        }
        if (recoveryBaseline) {
            db.execute(
                "INSERT OR REPLACE INTO application_settings VALUES (?, ?)",
                listOf("bitcoin_graph_baseline:${scopeKey(graph.ownedAddresses)}", id.toString()),
            )
        }
        return id
    }

    /** Resets paging when the accepted snapshot changes; never falls back to a different address scope. */
    fun utxoReview(
        ownedAddresses: Map<String, AccountId>,
        offset: Int = 0,
        limit: Int = 25,
        expectedSnapshotId: Long? = null,
    ): BitcoinUtxoReviewPage =
        db.transaction {
            queryUtxoReview(db, scopeKey(ownedAddresses), ownedAddresses.size, offset, limit, expectedSnapshotId)
        }

    fun snapshots(): List<BitcoinGraphSnapshot> =
        db.query("SELECT id FROM bitcoin_graph_snapshots ORDER BY id").map {
            snapshot(it.single()!!.toLong())!!
        }

    fun latest(ownedAddresses: Map<String, AccountId>): BitcoinGraphSnapshot? =
        db
            .query(
                "SELECT id FROM bitcoin_graph_snapshots WHERE scope_key=? ORDER BY id DESC LIMIT 1",
                listOf(scopeKey(ownedAddresses)),
            ).singleOrNull()
            ?.single()
            ?.toLong()
            ?.let(::snapshot)

    fun latest(): BitcoinGraphSnapshot? =
        db
            .query("SELECT id FROM bitcoin_graph_snapshots ORDER BY id DESC LIMIT 1")
            .singleOrNull()
            ?.single()
            ?.toLong()
            ?.let(::snapshot)

    /** Accepted anchors at or after each scope's recovery baseline, highest first. */
    fun anchors(
        ownedAddresses: Map<String, AccountId>? = null,
        limit: Int = 100,
    ): List<BitcoinBlockAnchor> {
        require(limit > 0)
        val baseline =
            "id>=COALESCE((SELECT CAST(value AS INTEGER) FROM application_settings " +
                "WHERE key='bitcoin_graph_baseline:'||scope_key),0)"
        val where = " WHERE $baseline" + if (ownedAddresses == null) "" else " AND scope_key=?"
        val args = ownedAddresses?.let { listOf(scopeKey(it)) } ?: emptyList()
        val candidates =
            """
            SELECT anchor_height AS height,anchor_hash AS hash,id AS snapshot_id FROM bitcoin_graph_snapshots$where
            UNION ALL SELECT block_height AS height,block_hash AS hash,snapshot_id FROM bitcoin_graph_transactions
            WHERE block_height IS NOT NULL AND snapshot_id=(SELECT MAX(id) FROM bitcoin_graph_snapshots$where)
            """.trimIndent()
        // SQLite associates bare columns with the row providing the single MAX aggregate.
        return db
            .query(
                "SELECT height,hash,MAX(snapshot_id) FROM ($candidates) GROUP BY height ORDER BY height DESC LIMIT $limit",
                args + args,
            ).map { BitcoinBlockAnchor(it[0]!!.toLong(), it[1]!!) }
    }

    fun snapshot(id: Long): BitcoinGraphSnapshot? {
        val head =
            db
                .query(
                    "SELECT provider_source_id,anchor_height,anchor_hash,observed_at,compared FROM bitcoin_graph_snapshots WHERE id=?",
                    listOf(id.toString()),
                ).singleOrNull()
                ?: return null

        fun rows(sql: String) = db.query(sql, listOf(id.toString()))
        val owners =
            rows("SELECT address,account_id FROM bitcoin_graph_scope WHERE snapshot_id=? ORDER BY address").associate {
                it[0]!! to
                    AccountId(it[1]!!)
            }
        val txs =
            rows(
                "SELECT txid,raw_checksum,state,fee,coinbase,block_height,block_hash,block_time FROM bitcoin_graph_transactions WHERE snapshot_id=? ORDER BY txid",
            )

        fun block(
            height: String?,
            hash: String?,
        ): BitcoinBlockAnchor? = height?.let { BitcoinBlockAnchor(it.toLong(), hash!!) }
        val transactions =
            txs.associate { row ->
                val txid = row[0]!!
                val inputs =
                    db
                        .query(
                            "SELECT position,parent_txid,parent_vout,sequence,value,script,script_type,address FROM bitcoin_graph_inputs WHERE snapshot_id=? AND txid=? ORDER BY position",
                            listOf(id.toString(), txid),
                        ).map { input ->
                            BitcoinTxInput(
                                input[0]!!.toInt(),
                                input[1]?.let {
                                    BitcoinOutpoint(it, input[2]!!.toLong())
                                },
                                input[4]?.let { BitcoinScriptOutput(ExactInteger.parse(it), input[5], input[6], input[7]) },
                                input[3]?.toLong(),
                            )
                        }
                val outputs =
                    db
                        .query(
                            "SELECT vout,value,script,script_type,address FROM bitcoin_graph_outputs WHERE snapshot_id=? AND txid=? ORDER BY vout",
                            listOf(id.toString(), txid),
                        ).map { output ->
                            BitcoinTxOutput(
                                BitcoinOutpoint(txid, output[0]!!.toLong()),
                                BitcoinScriptOutput(ExactInteger.parse(output[1]!!), output[2], output[3], output[4]),
                            )
                        }
                txid to
                    BitcoinGraphTransaction(
                        txid,
                        inputs,
                        outputs,
                        ExactInteger.parse(row[3]!!),
                        row[4] == "1",
                        block(row[5], row[6]),
                        row[7]?.toLong(),
                    )
            }
        val states = txs.associate { it[0]!! to BitcoinTransactionState.valueOf(it[2]!!) }
        val conflicts =
            rows(
                "SELECT parent_txid,parent_vout,spender,confirmed_winner FROM bitcoin_graph_conflicts WHERE snapshot_id=? ORDER BY parent_txid,parent_vout,spender",
            ).groupBy {
                BitcoinOutpoint(it[0]!!, it[1]!!.toLong())
            }.map { (point, spenders) ->
                BitcoinSpendConflict(point, spenders.map { it[2]!! }, spenders.first()[3])
            }
        val loadedSpenders =
            transactions.values
                .flatMap { tx ->
                    tx.inputs.mapNotNull { input -> input.spends?.let { it to tx.id } }
                }.groupBy({ it.first }, { it.second })
        val outputByPoint = transactions.values.flatMap { it.outputs }.associateBy { it.outpoint }
        val owned =
            rows(
                "SELECT txid,vout,account_id,address,status FROM bitcoin_graph_owned_outputs WHERE snapshot_id=? ORDER BY txid,vout",
            ).map { output ->
                val tx = transactions.getValue(output[0]!!)
                val point = BitcoinOutpoint(tx.id, output[1]!!.toLong())
                val coin = outputByPoint.getValue(point).output
                BitcoinOwnedOutput(
                    point,
                    AccountId(output[2]!!),
                    output[3]!!,
                    coin.value,
                    coin.scriptType,
                    tx.block,
                    tx.coinbase,
                    loadedSpenders[point].orEmpty().sorted(),
                    BitcoinOutputStatus.valueOf(output[4]!!),
                )
            }
        val missing =
            rows(
                "SELECT txid,vout FROM bitcoin_graph_missing_parents WHERE snapshot_id=? AND kind='GRAPH' ORDER BY txid,vout",
            ).map {
                BitcoinOutpoint(it[0]!!, it[1]!!.toLong())
            }
        val graph = BitcoinUtxoGraph(owners, transactions, states, conflicts, owned, missing)
        val comparison =
            if (head[4] == "0") {
                null
            } else {
                BitcoinUtxoComparison(
                    rows(
                        "SELECT kind,txid,vout,reconstructed,provider_address,provider_value,provider_height,provider_hash FROM bitcoin_graph_utxo_diffs WHERE snapshot_id=? ORDER BY position",
                    ).map { row ->
                        val point = BitcoinOutpoint(row[1]!!, row[2]!!.toLong())
                        BitcoinUtxoMismatch(
                            BitcoinUtxoMismatchKind.valueOf(row[0]!!),
                            point,
                            if (row[3] ==
                                "0"
                            ) {
                                null
                            } else {
                                graph.ownedOutputs.single { it.outpoint == point }
                            },
                            row[4]?.let { address ->
                                EsploraUtxo(
                                    address,
                                    point,
                                    ExactInteger.parse(row[5]!!),
                                    row[6]?.let { BitcoinBlockAnchor(it.toLong(), row[7]!!) },
                                )
                            },
                        )
                    },
                    rows(
                        "SELECT txid,vout FROM bitcoin_graph_missing_parents WHERE snapshot_id=? AND kind='COMPARISON' ORDER BY txid,vout",
                    ).map {
                        BitcoinOutpoint(it[0]!!, it[1]!!.toLong())
                    },
                )
            }
        val replacements =
            rows(
                "SELECT parent_txid,parent_vout,previous_txid,competing_txid,kind FROM bitcoin_graph_replacements WHERE snapshot_id=? ORDER BY parent_txid,parent_vout,previous_txid,competing_txid",
            ).map {
                BitcoinReplacementEvidence(
                    BitcoinOutpoint(it[0]!!, it[1]!!.toLong()),
                    it[2]!!,
                    it[3]!!,
                    BitcoinReplacementEvidenceKind.valueOf(it[4]!!),
                )
            }
        return BitcoinGraphSnapshot(
            id,
            head[0]!!,
            BitcoinBlockAnchor(head[1]!!.toLong(), head[2]!!),
            head[3]!!,
            graph,
            txs.associate {
                it[0]!! to
                    it[1]!!
            },
            comparison,
            replacements,
        )
    }

    private fun insert(
        table: String,
        values: List<String?>,
    ) = db.execute("INSERT INTO $table VALUES (${values.joinToString(",") { "?" }})", values)

    private fun scopeKey(owners: Map<String, AccountId>): String =
        sha256(
            owners.toSortedMap().entries.joinToString(
                "",
            ) { (address, account) -> "${address.length}:$address${account.value.length}:${account.value}" },
        )
}
