package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal val bitcoinGraphDeltaTables =
    listOf(
        "bitcoin_graph_delta_snapshots",
        "bitcoin_graph_delta_scopes",
        "bitcoin_graph_revisions",
        "bitcoin_graph_revision_inputs",
        "bitcoin_graph_revision_outputs",
        "bitcoin_graph_membership",
        "bitcoin_graph_facts",
    )

/** Additive tables preserve the column layout consumed by version-4 and version-5 archives. */
internal fun createBitcoinGraphDeltaTables(db: SqliteDatabase) {
    db.execute(
        "CREATE TABLE bitcoin_graph_delta_snapshots (snapshot_id INTEGER PRIMARY KEY REFERENCES " +
            "bitcoin_graph_snapshots(id))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_delta_scopes (scope_key TEXT NOT NULL, address TEXT NOT NULL, account_id TEXT " +
            "NOT NULL, PRIMARY KEY(scope_key,address))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_revisions (txid TEXT NOT NULL, checksum TEXT NOT NULL, payload TEXT NOT NULL, " +
            "fee TEXT NOT NULL, coinbase INTEGER NOT NULL, block_height INTEGER, block_hash TEXT, block_time INTEGER, " +
            "PRIMARY KEY(txid,checksum))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_revision_inputs (txid TEXT NOT NULL, checksum TEXT NOT NULL, position INTEGER " +
            "NOT NULL, parent_txid TEXT, parent_vout INTEGER, sequence INTEGER, value TEXT, script TEXT, script_type " +
            "TEXT, address TEXT, PRIMARY KEY(txid,checksum,position), FOREIGN KEY(txid,checksum) REFERENCES " +
            "bitcoin_graph_revisions(txid,checksum))",
    )
    db.execute(
        "CREATE INDEX bitcoin_graph_revision_spenders ON " +
            "bitcoin_graph_revision_inputs(parent_txid,parent_vout,txid,checksum)",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_revision_outputs (txid TEXT NOT NULL, checksum TEXT NOT NULL, vout INTEGER " +
            "NOT NULL, value TEXT NOT NULL, script TEXT, script_type TEXT, address TEXT, PRIMARY " +
            "KEY(txid,checksum,vout), FOREIGN KEY(txid,checksum) REFERENCES bitcoin_graph_revisions(txid,checksum))",
    )
    db.execute(
        "CREATE TABLE bitcoin_graph_membership (scope_key TEXT NOT NULL, txid TEXT NOT NULL, valid_from INTEGER " +
            "NOT NULL REFERENCES bitcoin_graph_snapshots(id), valid_to INTEGER REFERENCES " +
            "bitcoin_graph_snapshots(id), checksum TEXT NOT NULL, state TEXT NOT NULL, PRIMARY " +
            "KEY(scope_key,txid,valid_from), FOREIGN KEY(txid,checksum) REFERENCES " +
            "bitcoin_graph_revisions(txid,checksum))",
    )
    db.execute("CREATE INDEX bitcoin_graph_membership_current ON bitcoin_graph_membership(scope_key,valid_to,txid)")
    db.execute(
        "CREATE TABLE bitcoin_graph_facts (scope_key TEXT NOT NULL, fact_key TEXT NOT NULL, valid_from INTEGER " +
            "NOT NULL REFERENCES bitcoin_graph_snapshots(id), valid_to INTEGER REFERENCES " +
            "bitcoin_graph_snapshots(id), kind TEXT NOT NULL, txid TEXT NOT NULL, vout INTEGER NOT NULL, a TEXT, b " +
            "TEXT, c TEXT, d TEXT, e TEXT, f TEXT, PRIMARY KEY(scope_key,fact_key,valid_from))",
    )
    db.execute("CREATE INDEX bitcoin_graph_facts_current ON bitcoin_graph_facts(scope_key,valid_to,fact_key)")
}

/**
 * Accepts indexed scratch rows without materializing a graph. Revision bodies are shared across scopes;
 * membership and diagnostics retain validity ranges so unchanged scans only append a snapshot header.
 * The caller owns the transaction together with ledger application and scratch cleanup.
 */
class BitcoinGraphDeltaRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    @OptIn(ExperimentalTime::class)
    fun saveInTransaction(
        providerSourceId: String,
        anchor: BitcoinBlockAnchor,
        observedAt: String,
        compared: Boolean = true,
        recoveryBaseline: Boolean = false,
    ): Long {
        require(providerSourceId.isNotBlank() && observedAt.endsWith("Z"))
        Instant.parse(observedAt)
        val owners =
            db
                .query("SELECT address,account_id FROM bitcoin_graph_work_owners ORDER BY address")
                .associate { it[0]!! to AccountId(it[1]!!) }
        val scope = scopeKey(owners)
        val previous =
            db
                .query("SELECT id FROM bitcoin_graph_snapshots WHERE scope_key=? ORDER BY id DESC LIMIT 1", listOf(scope))
                .singleOrNull()
                ?.single()
                ?.toLong()
        val id =
            db
                .query("SELECT COALESCE(MAX(id),0)+1 FROM bitcoin_graph_snapshots")
                .single()
                .single()!!
                .toLong()
        db.execute(
            "INSERT INTO bitcoin_graph_snapshots VALUES (?,?,?,?,?,?,?)",
            listOf(id.toString(), scope, providerSourceId, anchor.height.toString(), anchor.hash, observedAt, if (compared) "1" else "0"),
        )
        db.execute("INSERT INTO bitcoin_graph_delta_snapshots VALUES (?)", listOf(id.toString()))
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_delta_scopes SELECT ?,address,account_id FROM bitcoin_graph_work_owners",
            listOf(scope),
        )
        // Insert bodies before membership. Raw checksums identify the exact provider JSON revision, not just txid.
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_revisions SELECT " +
                "id,checksum,payload,fee,coinbase,height,hash,block_time FROM bitcoin_graph_work_transactions",
        )
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_revision_inputs SELECT " +
                "i.tx_id,t.checksum,i.input_index,i.parent_id,i.parent_vout,i.sequence,i.value,i.script,i.script_type,i.address FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_transactions t ON t.id=i.tx_id",
        )
        db.execute(
            "INSERT OR IGNORE INTO bitcoin_graph_revision_outputs SELECT " +
                "o.tx_id,t.checksum,o.vout,o.value,o.script,o.script_type,o.address FROM bitcoin_graph_work_outputs o " +
                "JOIN bitcoin_graph_work_transactions t ON t.id=o.tx_id",
        )
        db.execute(
            "UPDATE bitcoin_graph_membership SET valid_to=? WHERE scope_key=? AND valid_to IS NULL AND NOT EXISTS " +
                "(SELECT 1 FROM bitcoin_graph_work_transactions w WHERE w.id=bitcoin_graph_membership.txid AND " +
                "w.checksum=bitcoin_graph_membership.checksum AND w.state=bitcoin_graph_membership.state)",
            listOf(id.toString(), scope),
        )
        db.execute(
            "INSERT INTO bitcoin_graph_membership SELECT ?,w.id,?,NULL,w.checksum,w.state FROM " +
                "bitcoin_graph_work_transactions w WHERE NOT EXISTS (SELECT 1 FROM bitcoin_graph_membership m WHERE " +
                "m.scope_key=? AND m.txid=w.id AND m.valid_to IS NULL)",
            listOf(scope, id.toString(), scope),
        )
        val candidates = factCandidates(scope, previous, compared)
        val match =
            "n.fact_key=bitcoin_graph_facts.fact_key AND n.kind=bitcoin_graph_facts.kind AND " +
                "n.txid=bitcoin_graph_facts.txid AND n.vout=bitcoin_graph_facts.vout AND n.a IS bitcoin_graph_facts.a AND " +
                "n.b IS bitcoin_graph_facts.b AND n.c IS bitcoin_graph_facts.c AND n.d IS bitcoin_graph_facts.d AND n.e " +
                "IS bitcoin_graph_facts.e AND n.f IS bitcoin_graph_facts.f"
        db.execute(
            "WITH candidates AS ($candidates) UPDATE bitcoin_graph_facts SET valid_to=$id WHERE scope_key='$scope' " +
                "AND valid_to IS NULL AND NOT EXISTS (SELECT 1 FROM candidates n WHERE $match)",
        )
        db.execute(
            "WITH candidates AS ($candidates) INSERT INTO bitcoin_graph_facts SELECT " +
                "'$scope',n.fact_key,$id,NULL,n.kind,n.txid,n.vout,n.a,n.b,n.c,n.d,n.e,n.f FROM candidates n WHERE NOT " +
                "EXISTS (SELECT 1 FROM bitcoin_graph_facts f WHERE f.scope_key='$scope' AND f.fact_key=n.fact_key AND " +
                "f.valid_to IS NULL)",
        )
        if (recoveryBaseline) {
            db.execute(
                "INSERT OR REPLACE INTO application_settings VALUES (?,?)",
                listOf("bitcoin_graph_baseline:$scope", id.toString()),
            )
        }
        return id
    }

    /** Candidates are a disk query. In particular, replacement pairs never form a Kotlin collection. */
    private fun factCandidates(
        scope: String,
        previous: Long?,
        compared: Boolean,
    ): String {
        val current =
            "SELECT 'R:'||i.parent_id||':'||i.parent_vout||':'||i.tx_id||':'||j.tx_id fact_key,'R' kind,i.parent_id " +
                "txid,i.parent_vout vout,i.tx_id a,j.tx_id b,CASE WHEN winner.height IS NULL THEN 'POSSIBLE' ELSE " +
                "'CONFIRMED' END c,NULL d,NULL e,NULL f FROM bitcoin_graph_work_inputs i JOIN bitcoin_graph_work_inputs j " +
                "ON j.parent_id=i.parent_id AND j.parent_vout=i.parent_vout AND j.tx_id<>i.tx_id JOIN " +
                "bitcoin_graph_work_transactions winner ON winner.id=j.tx_id WHERE i.parent_id IS NOT NULL AND " +
                "(winner.height IS NOT NULL OR NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_inputs k JOIN " +
                "bitcoin_graph_work_transactions t ON t.id=k.tx_id WHERE k.parent_id=i.parent_id AND " +
                "k.parent_vout=i.parent_vout AND t.height IS NOT NULL))"
        val prior =
            if (previous == null) {
                ""
            } else {
                val oldInputs =
                    "SELECT i.txid,i.parent_txid,i.parent_vout FROM bitcoin_graph_membership m JOIN " +
                        "bitcoin_graph_revision_inputs i ON i.txid=m.txid AND i.checksum=m.checksum WHERE m.scope_key='$scope' " +
                        "AND m.valid_from<=$previous AND (m.valid_to IS NULL OR m.valid_to>$previous) AND m.state<>'CONFIRMED' " +
                        "UNION ALL SELECT i.txid,i.parent_txid,i.parent_vout FROM bitcoin_graph_inputs i JOIN " +
                        "bitcoin_graph_transactions t ON t.snapshot_id=i.snapshot_id AND t.txid=i.txid WHERE " +
                        "i.snapshot_id=$previous AND t.state<>'CONFIRMED'"
                val oldEvidence =
                    "SELECT txid parent_txid,vout parent_vout,a previous_txid,b competing_txid FROM bitcoin_graph_facts WHERE " +
                        "scope_key='$scope' AND kind='R' AND valid_from<=$previous AND (valid_to IS NULL OR valid_to>$previous) " +
                        "UNION ALL SELECT parent_txid,parent_vout,previous_txid,competing_txid FROM bitcoin_graph_replacements " +
                        "WHERE snapshot_id=$previous"
                " UNION SELECT " +
                    "'R:'||o.parent_txid||':'||o.parent_vout||':'||o.txid||':'||n.tx_id,'R',o.parent_txid,o.parent_vout,o.txid,n.tx_id,CASE WHEN t.height IS NULL THEN 'POSSIBLE' ELSE 'CONFIRMED' END,NULL,NULL,NULL FROM ($oldInputs) o JOIN bitcoin_graph_work_inputs n ON n.parent_id=o.parent_txid AND n.parent_vout=o.parent_vout JOIN bitcoin_graph_work_transactions t ON t.id=n.tx_id WHERE NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_transactions absent WHERE absent.id=o.txid) UNION SELECT 'R:'||o.parent_txid||':'||o.parent_vout||':'||o.previous_txid||':'||o.competing_txid,'R',o.parent_txid,o.parent_vout,o.previous_txid,o.competing_txid,CASE WHEN t.height IS NULL THEN 'POSSIBLE' ELSE 'CONFIRMED' END,NULL,NULL,NULL FROM ($oldEvidence) o JOIN bitcoin_graph_work_transactions t ON t.id=o.competing_txid WHERE NOT EXISTS (SELECT 1 FROM bitcoin_graph_work_transactions absent WHERE absent.id=o.previous_txid)"
            }
        val diffs =
            if (!compared) {
                ""
            } else {
                " UNION SELECT " +
                    "'D:'||d.tx_id||':'||d.vout||':'||d.kind,'D',d.tx_id,d.vout,d.kind,p.address,p.value,CAST(p.height AS " +
                    "TEXT),p.hash,CAST(d.reconstructed AS TEXT) FROM bitcoin_graph_work_diffs d LEFT JOIN " +
                    "bitcoin_graph_work_provider p ON p.tx_id=d.tx_id AND p.vout=d.vout"
            }
        return current + prior + diffs
    }

    /** Explicit diagnostic materialization; refresh and paged review must use SQL instead. */
    fun snapshot(id: Long): BitcoinGraphSnapshot? {
        val head =
            db
                .query(
                    "SELECT s.scope_key,s.provider_source_id,s.anchor_height,s.anchor_hash,s.observed_at,s.compared FROM " +
                        "bitcoin_graph_snapshots s JOIN bitcoin_graph_delta_snapshots d ON d.snapshot_id=s.id WHERE s.id=?",
                    listOf(id.toString()),
                ).singleOrNull()
                ?: return null
        val scope = head[0]!!
        val owners =
            db
                .query(
                    "SELECT address,account_id FROM bitcoin_graph_delta_scopes WHERE scope_key=? ORDER BY address",
                    listOf(scope),
                ).associate {
                    it[0]!! to
                        AccountId(it[1]!!)
                }
        val active = "m.scope_key=? AND m.valid_from<=? AND (m.valid_to IS NULL OR m.valid_to>?)"
        val args = listOf(scope, id.toString(), id.toString())
        val rows =
            db.query(
                "SELECT r.payload,m.txid,m.checksum FROM bitcoin_graph_membership m JOIN bitcoin_graph_revisions r ON " +
                    "r.txid=m.txid AND r.checksum=m.checksum WHERE $active ORDER BY m.txid",
                args,
            )
        val graph = BitcoinUtxoGraph.build(rows.map { it[0]!! }, owners)
        val facts =
            db.query(
                "SELECT kind,txid,vout,a,b,c,d,e,f FROM bitcoin_graph_facts m WHERE $active ORDER BY txid,vout,CASE a " +
                    "WHEN 'VALUE_DIFFERS' THEN 0 WHEN 'ADDRESS_DIFFERS' THEN 1 WHEN 'CONFIRMATION_DIFFERS' THEN 2 ELSE 0 " +
                    "END,a,b",
                args,
            )
        val comparison =
            if (head[5] == "0") {
                null
            } else {
                BitcoinUtxoComparison(
                    facts.filter { it[0] == "D" }.map { r ->
                        val point = BitcoinOutpoint(r[1]!!, r[2]!!.toLong())
                        BitcoinUtxoMismatch(
                            BitcoinUtxoMismatchKind.valueOf(r[3]!!),
                            point,
                            if (r[8] ==
                                "1"
                            ) {
                                graph.ownedOutputs.single { it.outpoint == point }
                            } else {
                                null
                            },
                            r[4]?.let {
                                EsploraUtxo(
                                    it,
                                    point,
                                    ExactInteger.parse(r[5]!!),
                                    r[6]?.let { height ->
                                        BitcoinBlockAnchor(height.toLong(), r[7]!!)
                                    },
                                )
                            },
                        )
                    },
                    graph.missingParents,
                )
            }
        val evidence =
            facts
                .filter {
                    it[0] == "R"
                }.map {
                    BitcoinReplacementEvidence(
                        BitcoinOutpoint(it[1]!!, it[2]!!.toLong()),
                        it[3]!!,
                        it[4]!!,
                        BitcoinReplacementEvidenceKind.valueOf(it[5]!!),
                    )
                }
        return BitcoinGraphSnapshot(
            id,
            head[1]!!,
            BitcoinBlockAnchor(head[2]!!.toLong(), head[3]!!),
            head[4]!!,
            graph,
            rows.associate {
                it[1]!! to
                    it[2]!!
            },
            comparison,
            evidence,
        )
    }

    companion object {
        fun scopeKey(owners: Map<String, AccountId>): String =
            sha256(
                owners.toSortedMap().entries.joinToString(
                    "",
                ) { (address, account) -> "${address.length}:$address${account.value.length}:${account.value}" },
            )
    }
}
