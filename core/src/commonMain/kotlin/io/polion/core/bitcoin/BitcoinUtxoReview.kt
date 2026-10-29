package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.SqliteDatabase

/** A bounded view of one accepted comparison for exactly the currently enabled address/account scope. */
data class BitcoinUtxoReviewPage(
    val snapshotId: Long?,
    val providerSourceId: String?,
    val observedAt: String?,
    val anchor: BitcoinBlockAnchor?,
    val scopeKey: String?,
    val addressCount: Int,
    val compared: Boolean,
    val total: Int,
    val offset: Int,
    val rows: List<BitcoinUtxoReviewRow>,
) {
    val hasNext: Boolean get() = offset + rows.size < total
}

data class BitcoinUtxoReviewRow(
    val kind: String,
    val outpoint: BitcoinOutpoint,
    val account: AccountId?,
    val address: String?,
    val reconstructedValue: ExactInteger?,
    val reconstructedBlock: BitcoinBlockAnchor?,
    val reconstructedStatus: String?,
    val providerAddress: String?,
    val providerValue: ExactInteger?,
    val providerBlock: BitcoinBlockAnchor?,
)

internal fun queryUtxoReview(
    db: SqliteDatabase,
    scopeKey: String,
    addressCount: Int,
    offset: Int,
    limit: Int,
    expectedSnapshotId: Long?,
): BitcoinUtxoReviewPage {
    require(offset >= 0 && limit in 1..100)
    val head =
        db
            .query(
                "SELECT id,provider_source_id,observed_at,anchor_height,anchor_hash,compared " +
                    "FROM bitcoin_graph_snapshots WHERE scope_key=? ORDER BY id DESC LIMIT 1",
                listOf(scopeKey),
            ).singleOrNull()
    if (head == null) return BitcoinUtxoReviewPage(null, null, null, null, null, addressCount, false, 0, 0, emptyList())
    val id = head[0]!!
    val pageOffset = if (expectedSnapshotId != null && expectedSnapshotId.toString() != id) 0 else offset
    val differences =
        """
        SELECT position AS ordinal,kind,txid,vout,reconstructed,provider_address,provider_value,provider_height,provider_hash
        FROM bitcoin_graph_utxo_diffs WHERE snapshot_id=?
        UNION ALL
        SELECT 2147483647 AS ordinal,'MISSING_PARENT',txid,vout,0,NULL,NULL,NULL,NULL
        FROM bitcoin_graph_missing_parents WHERE snapshot_id=? AND kind='COMPARISON'
        """.trimIndent()
    val args = listOf(id, id)
    val total =
        db
            .query("SELECT COUNT(*) FROM ($differences)", args)
            .single()
            .single()!!
            .toInt()
    // LIMIT is applied before graph joins. Viewing a page never reconstructs the transaction graph.
    val sql =
        """
        SELECT d.kind,d.txid,d.vout,COALESCE(o.account_id,s.account_id),COALESCE(o.address,i.address,d.provider_address),
        CASE WHEN d.reconstructed=1 THEN v.value WHEN d.kind='MISSING_PARENT' THEN i.value END,
        CASE WHEN d.reconstructed=1 THEN t.block_height END,CASE WHEN d.reconstructed=1 THEN t.block_hash END,
        CASE WHEN d.reconstructed=1 THEN o.status WHEN d.kind='MISSING_PARENT' THEN 'UNKNOWN_PARENT' END,
        d.provider_address,d.provider_value,d.provider_height,d.provider_hash
        FROM (SELECT * FROM ($differences) ORDER BY ordinal,txid,vout LIMIT $limit OFFSET $pageOffset) d
        LEFT JOIN bitcoin_graph_owned_outputs o ON o.snapshot_id=$id AND o.txid=d.txid AND o.vout=d.vout
        LEFT JOIN bitcoin_graph_outputs v ON v.snapshot_id=$id AND v.txid=d.txid AND v.vout=d.vout
        LEFT JOIN bitcoin_graph_transactions t ON t.snapshot_id=$id AND t.txid=d.txid
        LEFT JOIN bitcoin_graph_inputs i ON i.snapshot_id=$id AND i.parent_txid=d.txid AND i.parent_vout=d.vout
            AND (i.txid,i.position)=(SELECT j.txid,j.position FROM bitcoin_graph_inputs j
                WHERE j.snapshot_id=$id AND j.parent_txid=d.txid AND j.parent_vout=d.vout ORDER BY j.txid,j.position LIMIT 1)
        LEFT JOIN bitcoin_graph_scope s ON s.snapshot_id=$id AND s.address=COALESCE(i.address,d.provider_address)
        ORDER BY d.ordinal,d.txid,d.vout
        """.trimIndent()

    fun block(
        height: String?,
        hash: String?,
    ) = height?.let { BitcoinBlockAnchor(it.toLong(), hash!!) }
    val rows =
        db.query(sql, args).map { row ->
            BitcoinUtxoReviewRow(
                row[0]!!,
                BitcoinOutpoint(row[1]!!, row[2]!!.toLong()),
                row[3]?.let(::AccountId),
                row[4],
                row[5]?.let(ExactInteger::parse),
                block(row[6], row[7]),
                row[8],
                row[9],
                row[10]?.let(ExactInteger::parse),
                block(row[11], row[12]),
            )
        }
    return BitcoinUtxoReviewPage(
        id.toLong(),
        head[1],
        head[2],
        block(head[3], head[4]),
        scopeKey,
        addressCount,
        head[5] == "1",
        total,
        pageOffset,
        rows,
    )
}
