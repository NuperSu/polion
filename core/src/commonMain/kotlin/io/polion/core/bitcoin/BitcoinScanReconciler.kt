package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.storage.RawObservation
import io.polion.core.storage.SqliteDatabase

/** Replaces the Bitcoin ledger view only after every watched address was scanned successfully. */
class BitcoinScanReconciler(
    private val db: SqliteDatabase,
    private val activities: ActivityRepository,
) {
    fun applyComplete(
        observations: List<RawObservation>,
        ownedAddresses: Map<String, AccountId>,
    ) {
        require(ownedAddresses.isNotEmpty())
        val grouped = observations.groupBy { it.id }
        require(grouped.values.all { group -> group.map { it.payload }.distinct().size == 1 }) {
            "Transaction changed during address scan"
        }
        val decoder = BitcoinDecoder(ownedAddresses)
        val decoded = grouped.values.flatMap { decoder.decode(it.first()) }
        val present = decoded.map { it.id }.toSet()
        require(present.size == decoded.size)
        db.transaction {
            val missing =
                activities.current().filter {
                    !it.manual && it.decoderVersion == decoder.version && it.id !in present && it.status != ActivityStatus.REVERSED
                }
            decoded.forEach(activities::saveInTransaction)
            missing.forEach { activities.saveInTransaction(it.copy(status = ActivityStatus.REVERSED)) }
        }
    }
}
