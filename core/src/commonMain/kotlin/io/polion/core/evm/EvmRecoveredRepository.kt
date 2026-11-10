package io.polion.core.evm

import io.polion.core.model.ExactInteger
import io.polion.core.storage.SqliteDatabase

/** Provenance of sent transactions found by searching the chain for their nonce. */
const val NONCE_SOURCE = "nonce-search"

/** The tokens 1inch's history of one address on one chain moves, as of [atMillis]. */
data class OneInchListing(
    val atMillis: Long,
    val contracts: Set<String>,
)

/**
 * What Polion recovered beyond Alchemy's transfer lists, per tracked address and chain. Part of encrypted backups, so
 * a restore does not repeat the lookups.
 *
 * - **trace:** native coin moved by contracts in one transaction, read from its 1inch trace (possibly none).
 * - **nonce:** a sent transaction found by its nonce, or none when the search found no transaction with it.
 * - **listing:** the token contracts 1inch's history moves, for spam detection.
 */
class EvmRecoveredRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS evm_recovered (address TEXT NOT NULL, chain_id INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "item TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(address, chain_id, kind, item))",
        )
    }

    private fun rows(
        address: String,
        chainId: Long,
        kind: String,
    ): List<Pair<String, String>> =
        db
            .query(
                "SELECT item, payload FROM evm_recovered WHERE address=? AND chain_id=? AND kind=? ORDER BY item",
                listOf(address, chainId.toString(), kind),
            ).map { it[0]!! to it[1]!! }

    private fun put(
        address: String,
        chainId: Long,
        kind: String,
        item: String,
        payload: String,
    ) = db.execute(
        "INSERT OR REPLACE INTO evm_recovered (address, chain_id, kind, item, payload) VALUES (?, ?, ?, ?, ?)",
        listOf(address, chainId.toString(), kind, item, payload),
    )

    /** Traced contract payments by transaction hash; an empty list means the trace found none. */
    fun traces(
        address: String,
        chainId: Long,
    ): Map<String, List<EvmInternalTransfer>> =
        rows(address, chainId, "trace").associate { (hash, payload) ->
            hash to
                payload.lines().filter { it.isNotEmpty() }.map { line ->
                    val f = line.split('|')
                    EvmInternalTransfer(
                        hash,
                        f[0].toLong(),
                        f[1].toInt(),
                        f[2].toLong(),
                        f[3],
                        f[4].ifEmpty { null },
                        ExactInteger.parse(f[5]),
                        f[6] == "1",
                        OneInch.SOURCE,
                    )
                }
        }

    fun saveTrace(
        address: String,
        chainId: Long,
        hash: String,
        transfers: List<EvmInternalTransfer>,
    ) = put(
        address,
        chainId,
        "trace",
        hash,
        transfers.joinToString("\n") {
            "${it.blockNumber}|${it.index}|${it.timestampMillis}|${it.from}|${it.to.orEmpty()}|${it.value}|${if (it.success) 1 else 0}"
        },
    )

    /** Sent transactions found by nonce, with fee and status; null where the search found no transaction. */
    fun nonces(
        address: String,
        chainId: Long,
    ): Map<Long, EvmTransaction?> =
        rows(address, chainId, "nonce").associate { (nonce, payload) ->
            nonce.toLong() to
                payload.takeIf { it.isNotEmpty() }?.split('|')?.let { f ->
                    EvmTransaction(
                        f[0],
                        f[1].toLong(),
                        f[2].toInt(),
                        f[3].toLong(),
                        address,
                        f[4].ifEmpty { null },
                        ExactInteger.parse(f[5]),
                        ExactInteger.parse(f[6]),
                        f[7] == "1",
                        NONCE_SOURCE,
                    )
                }
        }

    fun saveNonce(
        address: String,
        chainId: Long,
        nonce: Long,
        transaction: EvmTransaction?,
    ) = put(
        address,
        chainId,
        "nonce",
        nonce.toString(),
        transaction?.let {
            "${it.hash}|${it.blockNumber}|${it.position}|${it.timestampMillis}|${it.to.orEmpty()}|${it.value}|${it.fee}|" +
                if (it.success) "1" else "0"
        } ?: "",
    )

    fun listing(
        address: String,
        chainId: Long,
    ): OneInchListing? =
        rows(address, chainId, "listing").singleOrNull()?.second?.let { payload ->
            val lines = payload.lines()
            OneInchListing(lines.first().toLong(), lines.drop(1).filter { it.isNotEmpty() }.toSet())
        }

    fun saveListing(
        address: String,
        chainId: Long,
        listing: OneInchListing,
    ) = put(address, chainId, "listing", "", (listOf(listing.atMillis.toString()) + listing.contracts.sorted()).joinToString("\n"))

    /** Every saved listing by chain, for all tracked addresses. */
    fun listings(): Map<Long, List<OneInchListing>> =
        db
            .query("SELECT chain_id, payload FROM evm_recovered WHERE kind='listing'")
            .groupBy({ it[0]!!.toLong() }) { row ->
                val lines = row[1]!!.lines()
                OneInchListing(lines.first().toLong(), lines.drop(1).filter { it.isNotEmpty() }.toSet())
            }
}
