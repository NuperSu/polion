package io.polion.core.holdings

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceRecordId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.OwnershipSnapshot
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoldingsTest {
    private val asset = AssetId("asset")

    private fun event(
        id: String,
        time: Long,
        vararg values: Pair<String, String>,
    ) = Activity(
        ActivityId(id),
        time,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        values.mapIndexed { index, (account, value) ->
            Movement(
                EventId("$id:$index"),
                AccountId(account),
                AtomicAmount(asset, ExactInteger.parse(value)),
                MovementRole.TRANSFER,
            )
        },
        setOf(SourceRecordId(id)),
        "1",
    )

    @Test fun incrementalReorgHistoricalAndReopen() {
        val path = Files.createTempFile("holdings", ".db")
        val deposit = event("deposit", 100, "a" to "1000")
        val spend = event("spend", 200, "a" to "-210")
        val transfer = event("transfer", 300, "a" to "-300", "b" to "300")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                val repo = HoldingsRepository(db)
                repo.refresh(listOf(deposit, spend))
                repo.refresh(listOf(deposit, spend, transfer))
                assertEquals("490", repo.current(BalanceCoverage.COMPLETE, 300).quantities[BalanceKey(AccountId("a"), asset)].toString())
                assertEquals(
                    "790",
                    repo
                        .historical(
                            listOf(deposit, spend, transfer),
                            299,
                            BalanceCoverage.COMPLETE,
                        ).quantities[BalanceKey(AccountId("a"), asset)]
                        .toString(),
                )
                val reorg = spend.copy(status = ActivityStatus.REVERSED)
                repo.refresh(listOf(deposit, reorg, transfer))
                val incremental = repo.current(BalanceCoverage.COMPLETE, 300)
                repo.refresh(listOf(deposit, reorg, transfer), forceReplay = true)
                assertEquals(incremental, repo.current(BalanceCoverage.COMPLETE, 300))
                val owner = OwnerId("owner")
                val snapshot =
                    OwnershipSnapshot(
                        listOf(Owner(owner, "owner")),
                        listOf(
                            Account(AccountId("wallet"), owner, "wallet", AccountKind.WALLET),
                            Account(AccountId("a"), owner, "a", AccountKind.ACCOUNT, AccountId("wallet")),
                            Account(AccountId("b"), owner, "b", AccountKind.ACCOUNT, AccountId("wallet")),
                        ),
                        emptyList(),
                        listOf(Portfolio(PortfolioId("p"), "p", "USD", accountIds = setOf(AccountId("wallet"), AccountId("a")))),
                    )
                assertEquals("1000", HoldingsScopes.wallet(incremental, snapshot, AccountId("wallet"))[asset].toString())
                assertEquals(
                    HoldingsScopes.owner(incremental, snapshot, owner),
                    HoldingsScopes.portfolio(incremental, snapshot, PortfolioId("p")),
                )
                val observed = ReportedBalance(AccountId("missing"), asset, ExactInteger.parse("10"), 300, ProviderId("provider"))
                repo.observe(observed)
                assertNull(repo.reconcile(observed, incremental.copy(coverage = BalanceCoverage.UNKNOWN)).reconstructed)
                assertEquals("10", repo.reconcile(observed, incremental).difference.toString())
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                assertEquals(
                    "700",
                    HoldingsRepository(db).current(BalanceCoverage.COMPLETE, 300).quantities[BalanceKey(AccountId("a"), asset)].toString(),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
