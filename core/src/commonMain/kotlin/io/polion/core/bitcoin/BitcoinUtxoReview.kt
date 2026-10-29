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
                "SELECT id,provider_source_id,observed_at,anchor_height,anchor_hash,compared, " +
                    "EXISTS(SELECT 1 FROM bitcoin_graph_delta_snapshots d WHERE d.snapshot_id=id) " +
                    "FROM bitcoin_graph_snapshots WHERE scope_key=? ORDER BY id DESC LIMIT 1",
                listOf(scopeKey),
            ).singleOrNull()
    if (head == null) return BitcoinUtxoReviewPage(null, null, null, null, null, addressCount, false, 0, 0, emptyList())
    val id = head[0]!!
    val pageOffset = if (expectedSnapshotId != null && expectedSnapshotId.toString() != id) 0 else offset
    val delta = head[6] == "1"
    val differences =
        if (delta) {
            deltaDifferences()
        } else {
            """
            SELECT position AS ordinal,kind,txid,vout,reconstructed,provider_address,provider_value,provider_height,provider_hash
            FROM bitcoin_graph_utxo_diffs WHERE snapshot_id=?
            UNION ALL
            SELECT 2147483647 AS ordinal,'MISSING_PARENT',txid,vout,0,NULL,NULL,NULL,NULL
            FROM bitcoin_graph_missing_parents WHERE snapshot_id=? AND kind='COMPARISON'
            """.trimIndent()
        }

    fun resolve(sql: String) = sql.replace("REVIEW_ID", id).replace("REVIEW_SCOPE", "'$scopeKey'")
    val args = if (delta) listOf(scopeKey, id, id) else listOf(id, id)
    val total =
        db
            .query(resolve((if (delta) deltaReviewTables() else "") + "SELECT COUNT(*) FROM ($differences)"), args)
            .single()
            .single()!!
            .toInt()
    // LIMIT is applied before graph joins. Viewing a page never reconstructs the transaction graph.
    val sql =
        if (delta) {
            deltaReviewSql(differences, limit, pageOffset)
        } else {
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
        }

    fun block(
        height: String?,
        hash: String?,
    ) = height?.let { BitcoinBlockAnchor(it.toLong(), hash!!) }
    val rows =
        db.query(resolve(sql), args).map { row ->
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

/** Validity ranges resolve one historical snapshot directly in SQL. */
private fun deltaReviewTables(): String =
    """
    WITH active AS (
        SELECT m.txid,m.checksum,m.state FROM bitcoin_graph_membership m
        WHERE m.scope_key=? AND m.valid_from<=? AND (m.valid_to IS NULL OR m.valid_to>?)
    ), inputs AS (
        SELECT i.* FROM active m JOIN bitcoin_graph_revision_inputs i ON i.txid=m.txid AND i.checksum=m.checksum
    ), outputs AS (
        SELECT o.*,m.state,r.block_height,r.block_hash FROM active m
        JOIN bitcoin_graph_revision_outputs o ON o.txid=m.txid AND o.checksum=m.checksum
        JOIN bitcoin_graph_revisions r ON r.txid=m.txid AND r.checksum=m.checksum
    ), facts AS (
        SELECT f.* FROM bitcoin_graph_facts f, (SELECT scope_key,id FROM bitcoin_graph_snapshots WHERE id=REVIEW_ID) s
        WHERE f.scope_key=s.scope_key AND f.valid_from<=s.id AND (f.valid_to IS NULL OR f.valid_to>s.id)
    )
    """.trimIndent() + " "

private fun deltaDifferences(): String =
    """
    SELECT 0 ordinal,a kind,txid,vout,CAST(f AS INTEGER) reconstructed,b provider_address,c provider_value,d provider_height,e provider_hash,
        CASE a WHEN 'MISSING_FROM_RECONSTRUCTION' THEN 0 WHEN 'MISSING_FROM_PROVIDER' THEN 1 WHEN 'VALUE_DIFFERS' THEN 2
        WHEN 'ADDRESS_DIFFERS' THEN 3 WHEN 'CONFIRMATION_DIFFERS' THEN 4 ELSE 5 END kind_order
    FROM facts WHERE kind='D'
    UNION ALL
    SELECT DISTINCT 2147483647,'MISSING_PARENT',i.parent_txid,i.parent_vout,0,NULL,NULL,NULL,NULL,0
    FROM inputs i JOIN bitcoin_graph_delta_scopes s ON s.scope_key=REVIEW_SCOPE AND s.address=i.address
    WHERE (SELECT compared FROM bitcoin_graph_snapshots WHERE id=REVIEW_ID)=1
        AND i.parent_txid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM active m WHERE m.txid=i.parent_txid)
    """.trimIndent()

private fun deltaReviewSql(
    differences: String,
    limit: Int,
    offset: Int,
): String =
    deltaReviewTables() +
        """
        SELECT d.kind,d.txid,d.vout,COALESCE(s.account_id,ps.account_id),COALESCE(o.address,i.address,d.provider_address),
        CASE WHEN d.reconstructed=1 THEN o.value WHEN d.kind='MISSING_PARENT' THEN i.value END,
        CASE WHEN d.reconstructed=1 THEN o.block_height END,CASE WHEN d.reconstructed=1 THEN o.block_hash END,
        CASE WHEN d.reconstructed=1 THEN
            CASE WHEN o.state='REPLACED' THEN 'INVALIDATED' WHEN o.state='CONFLICTING' THEN 'CONFLICTING'
            WHEN EXISTS (SELECT 1 FROM inputs j JOIN active a ON a.txid=j.txid
                WHERE j.parent_txid=o.txid AND j.parent_vout=o.vout AND a.state='CONFIRMED') THEN 'SPENT'
            WHEN EXISTS (SELECT 1 FROM inputs j JOIN active a ON a.txid=j.txid
                WHERE j.parent_txid=o.txid AND j.parent_vout=o.vout AND a.state='PENDING') THEN 'SPEND_PENDING'
            WHEN EXISTS (SELECT 1 FROM inputs j JOIN active a ON a.txid=j.txid
                WHERE j.parent_txid=o.txid AND j.parent_vout=o.vout AND a.state='CONFLICTING') THEN 'SPEND_CONFLICTING'
            ELSE 'UNSPENT' END WHEN d.kind='MISSING_PARENT' THEN 'UNKNOWN_PARENT' END,
        d.provider_address,d.provider_value,d.provider_height,d.provider_hash
        FROM (SELECT * FROM ($differences) ORDER BY ordinal,txid,vout,kind_order LIMIT $limit OFFSET $offset) d
        LEFT JOIN outputs o ON o.txid=d.txid AND o.vout=d.vout
            AND EXISTS (SELECT 1 FROM bitcoin_graph_delta_scopes own WHERE own.scope_key=REVIEW_SCOPE AND own.address=o.address)
        LEFT JOIN inputs i ON i.parent_txid=d.txid AND i.parent_vout=d.vout AND (i.txid,i.position)=(
            SELECT j.txid,j.position FROM inputs j WHERE j.parent_txid=d.txid AND j.parent_vout=d.vout ORDER BY j.txid,j.position LIMIT 1)
        LEFT JOIN bitcoin_graph_delta_scopes s ON s.scope_key=REVIEW_SCOPE AND s.address=o.address
        LEFT JOIN bitcoin_graph_delta_scopes ps ON ps.scope_key=REVIEW_SCOPE AND ps.address=COALESCE(i.address,d.provider_address)
        ORDER BY d.ordinal,d.txid,d.vout,d.kind_order
        """.trimIndent()
