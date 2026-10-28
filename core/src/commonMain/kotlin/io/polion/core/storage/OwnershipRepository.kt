package io.polion.core.storage

import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.OwnershipCatalog
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget

data class OwnershipSnapshot(
    val owners: List<Owner>,
    val accounts: List<Account>,
    val targets: List<TrackingTarget>,
    val portfolios: List<Portfolio>,
)

class OwnershipRepository(
    private val db: SqliteDatabase,
) {
    fun replace(snapshot: OwnershipSnapshot) {
        OwnershipCatalog(snapshot.owners, snapshot.accounts, snapshot.targets, snapshot.portfolios)
        db.transaction {
            listOf("portfolio_owners", "portfolio_accounts", "ownership_tags", "targets", "accounts", "portfolios", "owners").forEach {
                db.execute("DELETE FROM $it")
            }

            fun tags(
                type: String,
                id: String,
                tags: Set<String>,
            ) {
                tags.forEach { db.execute("INSERT INTO ownership_tags VALUES (?, ?, ?)", listOf(type, id, it)) }
            }
            snapshot.owners.forEach {
                db.execute("INSERT INTO owners VALUES (?, ?)", listOf(it.id.value, it.label))
                tags("owner", it.id.value, it.tags)
            }
            snapshot.accounts.forEach {
                db.execute(
                    "INSERT INTO accounts VALUES (?, ?, ?, ?, ?)",
                    listOf(it.id.value, it.ownerId.value, it.label, it.kind.name, it.parentId?.value),
                )
                tags("account", it.id.value, it.tags)
            }
            snapshot.targets.forEach {
                db.execute(
                    "INSERT INTO targets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(
                        it.id.value,
                        it.accountId.value,
                        it.identity.networkId.value,
                        it.identity.kind,
                        it.identity.canonicalKey,
                        it.role.name,
                        it.label,
                        if (it.enabled) "1" else "0",
                        it.walletId?.value,
                    ),
                )
                tags("target", it.id.value, it.tags)
            }
            snapshot.portfolios.forEach {
                db.execute("INSERT INTO portfolios VALUES (?, ?, ?)", listOf(it.id.value, it.label, it.valuationCurrency))
                it.ownerIds.forEach { owner -> db.execute("INSERT INTO portfolio_owners VALUES (?, ?)", listOf(it.id.value, owner.value)) }
                it.accountIds.forEach { account ->
                    db.execute("INSERT INTO portfolio_accounts VALUES (?, ?)", listOf(it.id.value, account.value))
                }
                tags("portfolio", it.id.value, it.tags)
            }
        }
    }

    fun load(): OwnershipSnapshot = db.transaction { loadInTransaction() }

    /** Reads within the caller’s existing transaction. */
    internal fun loadInTransaction(): OwnershipSnapshot {
        fun tags(
            type: String,
            id: String,
        ): Set<String> =
            db.query("SELECT tag FROM ownership_tags WHERE entity_type=? AND entity_id=?", listOf(type, id)).map { it[0]!! }.toSet()
        val owners =
            db
                .query(
                    "SELECT id, label FROM owners ORDER BY id",
                ).map { Owner(OwnerId(it[0]!!), it[1]!!, tags("owner", it[0]!!)) }
        val accounts =
            db.query("SELECT id, owner_id, label, kind, parent_id FROM accounts ORDER BY id").map {
                Account(
                    AccountId(it[0]!!),
                    OwnerId(it[1]!!),
                    it[2]!!,
                    AccountKind.valueOf(it[3]!!),
                    it[4]?.let(::AccountId),
                    tags("account", it[0]!!),
                )
            }
        val targets =
            db
                .query(
                    "SELECT id, account_id, network, kind, canonical_key, role, label, enabled, wallet_id FROM targets ORDER BY id",
                ).map {
                    TrackingTarget(
                        TrackingTargetId(it[0]!!),
                        AccountId(it[1]!!),
                        TargetIdentity(NetworkId(it[2]!!), it[3]!!, it[4]!!),
                        TargetRole.valueOf(it[5]!!),
                        it[6]!!,
                        tags("target", it[0]!!),
                        it[7] == "1",
                        it[8]?.let(::TrackingTargetId),
                    )
                }
        val portfolios =
            db.query("SELECT id, label, currency FROM portfolios ORDER BY id").map { row ->
                val id = row[0]!!
                Portfolio(
                    PortfolioId(
                        id,
                    ),
                    row[1]!!,
                    row[2]!!,
                    db
                        .query("SELECT owner_id FROM portfolio_owners WHERE portfolio_id=?", listOf(id))
                        .map {
                            OwnerId(it[0]!!)
                        }.toSet(),
                    db
                        .query("SELECT account_id FROM portfolio_accounts WHERE portfolio_id=?", listOf(id))
                        .map {
                            AccountId(it[0]!!)
                        }.toSet(),
                    tags("portfolio", id),
                )
            }
        return OwnershipSnapshot(owners, accounts, targets, portfolios)
    }
}
