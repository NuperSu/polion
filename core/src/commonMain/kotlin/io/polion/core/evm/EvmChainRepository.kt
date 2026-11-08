package io.polion.core.evm

import io.polion.core.storage.SqliteDatabase

/** Sync state of one tracked EVM address on one chain. */
data class EvmChainState(
    val address: String,
    val chainId: Long,
    /** The newest transaction, internal transfer and token transfer last seen; equal heads mean nothing changed. */
    val head: String?,
    val lastSuccessMillis: Long?,
    /** Null when balances matched the provider at the last check; otherwise a short description of the differences. */
    val balanceDifference: String?,
    val error: String?,
)

/** The chains each tracked EVM address is used on, and their sync state. Part of encrypted backups. */
class EvmChainRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS evm_chains (address TEXT NOT NULL, chain_id INTEGER NOT NULL, head TEXT, last_success INTEGER, " +
                "balance_difference TEXT, error TEXT, PRIMARY KEY(address, chain_id))",
        )
    }

    fun states(address: String? = null): List<EvmChainState> =
        db
            .query(
                "SELECT address, chain_id, head, last_success, balance_difference, error FROM evm_chains " +
                    (if (address == null) "" else "WHERE address=? ") + "ORDER BY address, chain_id",
                listOfNotNull(address),
            ).map { EvmChainState(it[0]!!, it[1]!!.toLong(), it[2], it[3]?.toLong(), it[4], it[5]) }

    /** Adds chains to track for [address]; existing state is kept. */
    fun addChains(
        address: String,
        chains: Collection<Long>,
    ) = chains.forEach { db.execute("INSERT OR IGNORE INTO evm_chains (address, chain_id) VALUES (?, ?)", listOf(address, it.toString())) }

    fun recordSuccess(
        address: String,
        chainId: Long,
        head: String,
        atMillis: Long,
    ) = db.execute(
        "UPDATE evm_chains SET head=?, last_success=?, error=NULL WHERE address=? AND chain_id=?",
        listOf(head, atMillis.toString(), address, chainId.toString()),
    )

    fun recordError(
        address: String,
        chainId: Long,
        message: String,
    ) = db.execute("UPDATE evm_chains SET error=? WHERE address=? AND chain_id=?", listOf(message.take(300), address, chainId.toString()))

    fun recordBalanceDifference(
        address: String,
        chainId: Long,
        difference: String?,
    ) = db.execute(
        "UPDATE evm_chains SET balance_difference=? WHERE address=? AND chain_id=?",
        listOf(difference?.take(1000), address, chainId.toString()),
    )
}
