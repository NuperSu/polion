package io.polion.core.holdings

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.ProviderId
import io.polion.core.ownership.OwnershipCatalog
import io.polion.core.storage.OwnershipSnapshot
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256

enum class BalanceCoverage { UNKNOWN, PARTIAL, COMPLETE }

data class BalanceKey(
    val account: AccountId,
    val asset: AssetId,
)

data class BalanceView(
    val quantities: Map<BalanceKey, ExactInteger>,
    val coverage: BalanceCoverage,
    val atMillis: Long,
)

data class ReportedBalance(
    val account: AccountId,
    val asset: AssetId,
    val quantity: ExactInteger,
    val atMillis: Long,
    val provider: ProviderId,
)

data class BalanceComparison(
    val reported: ReportedBalance,
    val reconstructed: ExactInteger?,
    val difference: ExactInteger?,
    val coverage: BalanceCoverage,
)

/** Materialized account deltas are reversible when an activity is reclassified or reorganized. */
class HoldingsRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.transaction {
            db.execute("CREATE TABLE IF NOT EXISTS holdings_schema (version INTEGER PRIMARY KEY CHECK(version=1))")
            db.execute("INSERT OR IGNORE INTO holdings_schema VALUES (1)")
            require(db.query("SELECT version FROM holdings_schema").single().single() == "1")
            db.execute("CREATE TABLE IF NOT EXISTS holdings_events (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
            db.execute(
                "CREATE TABLE IF NOT EXISTS holdings_deltas (id TEXT NOT NULL REFERENCES holdings_events(id) ON DELETE CASCADE, account TEXT NOT NULL, asset TEXT NOT NULL, units TEXT NOT NULL, PRIMARY KEY(id, account, asset))",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS holdings_balances (account TEXT NOT NULL, asset TEXT NOT NULL, units TEXT NOT NULL, PRIMARY KEY(account, asset))",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS balance_observations (account TEXT NOT NULL, asset TEXT NOT NULL, at_millis INTEGER NOT NULL, provider TEXT NOT NULL, units TEXT NOT NULL, PRIMARY KEY(account,asset,at_millis,provider))",
            )
        }
    }

    /** The input is the complete current revision set, not a page or a partial update. */
    fun refresh(
        activities: List<Activity>,
        forceReplay: Boolean = false,
    ) = db.transaction {
        require(activities.map { it.id }.distinct().size == activities.size)
        if (forceReplay) {
            db.execute("DELETE FROM holdings_deltas")
            db.execute("DELETE FROM holdings_events")
            db.execute("DELETE FROM holdings_balances")
        }
        val old = db.query("SELECT id,fingerprint FROM holdings_events").associate { it[0]!! to it[1]!! }
        val current = activities.associateBy { it.id.value }
        val changed = current.filter { (id, activity) -> old[id] != fingerprint(activity) }
        (old.keys - current.keys + changed.keys).forEach { id ->
            db.query("SELECT account,asset,units FROM holdings_deltas WHERE id=?", listOf(id)).forEach {
                add(it[0]!!, it[1]!!, -ExactInteger.parse(it[2]!!))
            }
            db.execute("DELETE FROM holdings_deltas WHERE id=?", listOf(id))
            db.execute("DELETE FROM holdings_events WHERE id=?", listOf(id))
        }
        changed.forEach { (id, activity) ->
            db.execute("INSERT INTO holdings_events VALUES (?,?)", listOf(id, fingerprint(activity)))
            if (activity.status == ActivityStatus.CONFIRMED) {
                activity.movements.groupBy { BalanceKey(it.accountId, it.amount.assetId) }.forEach { (key, movements) ->
                    val units = movements.fold(ExactInteger.ZERO) { sum, m -> sum + m.amount.atomicUnits }
                    db.execute(
                        "INSERT INTO holdings_deltas VALUES (?,?,?,?)",
                        listOf(id, key.account.value, key.asset.value, units.toString()),
                    )
                    add(key.account.value, key.asset.value, units)
                }
            }
        }
    }

    fun current(
        coverage: BalanceCoverage,
        atMillis: Long,
    ): BalanceView =
        BalanceView(
            db.query("SELECT account,asset,units FROM holdings_balances").associate {
                BalanceKey(AccountId(it[0]!!), AssetId(it[1]!!)) to
                    ExactInteger.parse(it[2]!!)
            },
            coverage,
            atMillis,
        )

    fun historical(
        activities: List<Activity>,
        atMillis: Long,
        coverage: BalanceCoverage,
    ): BalanceView {
        require(activities.map { it.id }.distinct().size == activities.size)
        val amounts = mutableMapOf<BalanceKey, ExactInteger>()
        activities.filter { it.occurredAtEpochMillis <= atMillis && it.status == ActivityStatus.CONFIRMED }.forEach { a ->
            a.movements.forEach { m ->
                val key = BalanceKey(m.accountId, m.amount.assetId)
                amounts[key] = (amounts[key] ?: ExactInteger.ZERO) + m.amount.atomicUnits
            }
        }
        return BalanceView(amounts, coverage, atMillis)
    }

    fun observe(observation: ReportedBalance) {
        db.execute(
            "INSERT OR REPLACE INTO balance_observations VALUES (?,?,?,?,?)",
            listOf(
                observation.account.value,
                observation.asset.value,
                observation.atMillis.toString(),
                observation.provider.value,
                observation.quantity.toString(),
            ),
        )
    }

    fun reconcile(
        observation: ReportedBalance,
        historical: BalanceView,
    ): BalanceComparison {
        require(observation.atMillis == historical.atMillis)
        val known =
            historical.quantities[BalanceKey(observation.account, observation.asset)]
                ?: if (historical.coverage == BalanceCoverage.COMPLETE) ExactInteger.ZERO else null
        return BalanceComparison(observation, known, known?.let { observation.quantity - it }, historical.coverage)
    }

    private fun add(
        account: String,
        asset: String,
        units: ExactInteger,
    ) {
        val previous =
            db
                .query(
                    "SELECT units FROM holdings_balances WHERE account=? AND asset=?",
                    listOf(account, asset),
                ).singleOrNull()
                ?.single()
                ?.let(ExactInteger::parse)
                ?: ExactInteger.ZERO
        db.execute(
            "INSERT OR REPLACE INTO holdings_balances VALUES (?,?,?)",
            listOf(
                account,
                asset,
                (
                    previous +
                        units
                ).toString(),
            ),
        )
    }

    private fun fingerprint(activity: Activity): String {
        fun field(value: String) = "${value.length}:$value"
        return sha256(
            field(activity.id.value) + field(activity.status.name) +
                activity.movements.joinToString("") {
                    field(it.accountId.value) + field(it.amount.assetId.value) + field(it.amount.atomicUnits.toString())
                },
        )
    }
}

object HoldingsScopes {
    fun accounts(
        view: BalanceView,
        selected: Set<AccountId>,
    ): Map<AssetId, ExactInteger> =
        view.quantities
            .filterKeys {
                it.account in
                    selected
            }.entries
            .groupBy { it.key.asset }
            .mapValues { (_, rows) -> rows.fold(ExactInteger.ZERO) { sum, row -> sum + row.value } }

    fun owner(
        view: BalanceView,
        snapshot: OwnershipSnapshot,
        id: OwnerId,
    ) = accounts(
        view,
        snapshot.accounts
            .filter {
                it.ownerId == id
            }.map { it.id }
            .toSet(),
    )

    fun wallet(
        view: BalanceView,
        snapshot: OwnershipSnapshot,
        root: AccountId,
    ): Map<AssetId, ExactInteger> = accounts(view, descendants(snapshot, root))

    fun portfolio(
        view: BalanceView,
        snapshot: OwnershipSnapshot,
        id: PortfolioId,
    ): Map<AssetId, ExactInteger> {
        OwnershipCatalog(snapshot.owners, snapshot.accounts, snapshot.targets, snapshot.portfolios)
        val portfolio = snapshot.portfolios.single { it.id == id }
        val scope =
            if (portfolio.accountIds.isEmpty()) {
                snapshot.accounts
                    .map {
                        it.id
                    }.toSet()
            } else {
                portfolio.accountIds.flatMap { descendants(snapshot, it) }.toSet()
            }
        return accounts(
            view,
            snapshot.accounts
                .filter {
                    it.id in scope && (portfolio.ownerIds.isEmpty() || it.ownerId in portfolio.ownerIds)
                }.map { it.id }
                .toSet(),
        )
    }

    private fun descendants(
        snapshot: OwnershipSnapshot,
        root: AccountId,
    ): Set<AccountId> {
        require(snapshot.accounts.any { it.id == root })
        val result = mutableSetOf(root)
        do {
            val before = result.size
            snapshot.accounts.filter { it.parentId in result }.forEach { result.add(it.id) }
        } while (result.size !=
            before
        )
        return result
    }
}
