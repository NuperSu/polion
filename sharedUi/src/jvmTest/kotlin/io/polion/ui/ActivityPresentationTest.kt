package io.polion.ui

import io.polion.core.application.ActivityFilter
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
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.storage.OwnershipSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityPresentationTest {
    private val owner = OwnerId("owner")
    private val a = AccountId("a")
    private val b = AccountId("b")
    private val asset = AssetId("bitcoin")
    private val portfolio = PortfolioId("b-only")
    private val ownership =
        OwnershipSnapshot(
            listOf(Owner(owner, "Owner")),
            listOf(account(a), account(b)),
            emptyList(),
            listOf(Portfolio(portfolio, "B only", "fiat:USD", accountIds = setOf(b))),
        )

    @Test
    fun portfolioTransferShowsItsIncomingLegAndKeepsCompleteDetails() {
        val transfer = transfer()
        val summary = activityRowAmounts(transfer, activityAccountScope(ownership, ActivityFilter(portfolio = portfolio)))
        assertEquals(mapOf(asset to ExactInteger.parse("90")), summary.quantities)
        assertEquals("↙", summary.directionSymbol)
        assertEquals(listOf("-100", "90"), transfer.movements.map { it.amount.atomicUnits.toString() })
    }

    @Test
    fun accountTransferShowsItsIncomingLegAndIntersectsPortfolioScope() {
        val transfer = transfer()
        val incoming = activityRowAmounts(transfer, activityAccountScope(ownership, ActivityFilter(account = b)))
        assertEquals(mapOf(asset to ExactInteger.parse("90")), incoming.quantities)
        assertEquals("↙", incoming.directionSymbol)
        val outgoing = activityRowAmounts(transfer, activityAccountScope(ownership, ActivityFilter(account = a)))
        assertEquals(mapOf(asset to ExactInteger.parse("-100")), outgoing.quantities)
        assertEquals("↗", outgoing.directionSymbol)
        assertEquals(emptySet(), activityAccountScope(ownership, ActivityFilter(portfolio = portfolio, account = a)))
        val all = activityRowAmounts(transfer, activityAccountScope(ownership, ActivityFilter()))
        assertEquals(mapOf(asset to ExactInteger.parse("-10")), all.quantities)
        assertEquals("↗", all.directionSymbol)
    }

    @Test
    fun scopeIncludesDescendantsAndRespectsOwnerAndArchiveFilters() {
        val child = AccountId("child")
        val archived = AccountId("archived")
        val otherOwner = OwnerId("other")
        val otherAccount = AccountId("other-account")
        val catalog =
            ownership.copy(
                owners = ownership.owners + Owner(otherOwner, "Other"),
                accounts =
                    ownership.accounts +
                        listOf(
                            account(child, parent = b),
                            account(archived, parent = b, tags = setOf("polion:archived")),
                            Account(otherAccount, otherOwner, "Other", AccountKind.WALLET),
                        ),
                portfolios =
                    ownership.portfolios +
                        Portfolio(PortfolioId("owner-only"), "Owner only", "fiat:USD", ownerIds = setOf(owner)),
            )
        assertEquals(setOf(b, child), activityAccountScope(catalog, ActivityFilter(portfolio = portfolio)))
        assertEquals(
            setOf(a, b, child),
            activityAccountScope(catalog, ActivityFilter(portfolio = PortfolioId("owner-only"))),
        )
        assertEquals(setOf(a, b, child, otherAccount), activityAccountScope(catalog, ActivityFilter()))
        assertEquals(emptySet(), activityAccountScope(catalog, ActivityFilter(account = archived)))
    }

    @Test
    fun mixedAssetAndZeroNetRowsUseNeutralDirection() {
        val mixed = transfer().copy(movements = listOf(movement(a, "-100"), movement(b, "90", AssetId("other"))))
        assertEquals("↔", activityRowAmounts(mixed, setOf(a, b)).directionSymbol)
        val zero = transfer().copy(movements = listOf(movement(a, "-90"), movement(b, "90")))
        assertEquals("↔", activityRowAmounts(zero, setOf(a, b)).directionSymbol)
    }

    private fun account(
        id: AccountId,
        parent: AccountId? = null,
        tags: Set<String> = emptySet(),
    ) = Account(id, owner, id.value, AccountKind.WALLET, parent, tags)

    private fun movement(
        account: AccountId,
        quantity: String,
        assetId: AssetId = asset,
    ) = Movement(EventId(account.value), account, AtomicAmount(assetId, ExactInteger.parse(quantity)), MovementRole.TRANSFER)

    private fun transfer() =
        Activity(
            ActivityId("transfer"),
            1L,
            "transfer",
            ActivityKind.TRANSFER,
            ActivityStatus.CONFIRMED,
            listOf(movement(a, "-100"), movement(b, "90")),
            emptySet(),
            null,
            manual = true,
        )
}
