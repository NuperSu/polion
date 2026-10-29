package io.polion.core.application

import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
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
import io.polion.core.model.Rational
import io.polion.core.model.SourceRecordId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingScopeTest {
    private val btc = AssetId("bitcoin:btc")
    private val first = AccountId("first")
    private val second = AccountId("second")
    private val destination = AccountId("destination")
    private val selected = PortfolioId("selected")

    @Test fun unrelatedOwnerMissingOrForeignQuoteDoesNotSuppressSelectedInventory() {
        listOf(null, "EUR", "invalid").forEach { otherQuote ->
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setup(db)
                buy(app, "own", first, "USD")
                buy(app, "other", second, otherQuote?.let { if (it == "invalid") "USD" else it })
                if (otherQuote == "invalid") {
                    db.execute("UPDATE accounting_treatments SET quote_asset=NULL WHERE activity_id='other'")
                }
                val view = app.accountingReport(selected)
                assertEquals(
                    Rational.of("10"),
                    view.report.lots
                        .single()
                        .basis,
                )
                assertTrue(view.missingTreatments.isEmpty())
                assertTrue(view.invalidTreatments.isEmpty())
                // The default view must also keep separate owners' completeness pools independent.
                assertEquals(
                    Rational.of("10"),
                    app
                        .accountingReport()
                        .report.lots
                        .single { it.account == first }
                        .basis,
                )
            }
        }
    }

    @Test fun destinationPortfolioReplaysSameOwnerSourceLots() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            transfer(app, first, destination)
            app.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
            val view = app.accountingReport(selected)
            val lot = view.report.lots.single()
            assertEquals(destination, lot.account)
            assertEquals(Rational.of("10"), lot.basis)
            assertEquals(1L, lot.acquiredAt)
            assertTrue(view.invalidTreatments.isEmpty())
        }
    }

    @Test fun destinationPortfolioReportsMissingAndInvalidSourceDependencies() {
        listOf(null, "EUR").forEach { sourceQuote ->
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setup(db)
                buy(app, "source", first, sourceQuote)
                transfer(app, first, destination)
                app.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
                val view = app.accountingReport(selected)
                assertEquals(if (sourceQuote == null) listOf(ActivityId("source")) else emptyList(), view.missingTreatments)
                assertEquals(if (sourceQuote == null) emptyList() else listOf(ActivityId("source")), view.invalidTreatments)
                assertTrue(view.report.lots.isEmpty())
                assertTrue(view.report.disposals.isEmpty())
                assertTrue(view.report.valuations.isEmpty())
            }
        }
    }

    @Test fun destinationDiagnosticsExcludeUnrelatedAssetInSourceAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            transfer(app, first, destination)
            val unrelated =
                movement("unrelated", first, "1").copy(
                    amount = AtomicAmount(AssetId("token:other"), ExactInteger.parse("1")),
                )
            app.saveManualActivity(activity("unrelated", 3, listOf(unrelated)))
            app.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
            val view = app.accountingReport(selected)
            assertEquals(
                Rational.of("10"),
                view.report.lots
                    .single()
                    .basis,
            )
            assertTrue(view.missingTreatments.isEmpty())
            assertTrue(view.invalidTreatments.isEmpty())
        }
    }

    @Test fun sameAccountInternalTransferIsInvalidWithoutCreatingBasis() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            transfer(app, first, first)
            val view = app.accountingReport(selected)
            assertEquals(listOf(ActivityId("transfer")), view.invalidTreatments)
            assertTrue(view.report.lots.isEmpty())
            assertTrue(view.report.disposals.isEmpty())
        }
    }

    @Test fun unrelatedOwnerUnverifiedBitcoinDoesNotMakeSelectedBasisUnknown() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            ActivityRepository(db).save(activity("other", 2, listOf(movement("other", second, "1")), false, "bitcoin-v1"))
            app.reload()
            app.saveTreatments(ActivityId("other"), listOf(Treatment.Acquire(EventId("other"), quote("USD"))))
            val view = app.accountingReport(selected)
            assertEquals(
                Rational.of("10"),
                view.report.lots
                    .single()
                    .basis,
            )
            assertTrue(view.orderingUncertain.isEmpty())
            val all = app.accountingReport()
            assertEquals(
                Rational.of("10"),
                all.report.lots
                    .single { it.account == first }
                    .basis,
            )
            assertEquals(listOf(ActivityId("other")), all.orderingUncertain)
        }
    }

    @Test fun crossOwnerInternalTransferIsInvalidWithoutCreatingBasis() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            transfer(app, first, second)
            val view = app.accountingReport()
            assertEquals(listOf(ActivityId("transfer")), view.invalidTreatments)
            assertTrue(view.report.lots.isEmpty())
            assertTrue(view.report.disposals.isEmpty())
        }
    }

    private fun setup(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1000L }
        val owner = OwnerId("first-owner")
        val other = OwnerId("second-owner")
        app.saveOwner(Owner(owner, "First owner"))
        app.saveOwner(Owner(other, "Second owner"))
        app.saveAccount(Account(first, owner, "First", AccountKind.ACCOUNT))
        app.saveAccount(Account(destination, owner, "Destination", AccountKind.ACCOUNT))
        app.saveAccount(Account(second, other, "Second", AccountKind.ACCOUNT))
        app.savePortfolio(Portfolio(selected, "First", "USD", ownerIds = setOf(owner)))
        return app
    }

    private fun buy(
        app: PortfolioApplication,
        id: String,
        account: AccountId,
        currency: String?,
    ) {
        app.saveManualActivity(activity(id, 1, listOf(movement(id, account, "1"))))
        if (currency != null) app.saveTreatments(ActivityId(id), listOf(Treatment.Acquire(EventId(id), quote(currency))))
    }

    private fun transfer(
        app: PortfolioApplication,
        from: AccountId,
        to: AccountId,
    ) {
        app.saveManualActivity(activity("transfer", 2, listOf(movement("out", from, "-1"), movement("in", to, "1"))))
        app.saveTreatments(ActivityId("transfer"), listOf(Treatment.InternalTransfer(EventId("out"), EventId("in"))))
    }

    private fun quote(currency: String) = QuoteValue(AssetId(currency), Rational.of("10"))

    private fun movement(
        id: String,
        account: AccountId,
        units: String,
    ) = Movement(EventId(id), account, AtomicAmount(btc, ExactInteger.parse(units)), MovementRole.TRANSFER)

    private fun activity(
        id: String,
        at: Long,
        movements: List<Movement>,
        manual: Boolean = true,
        decoder: String? = null,
    ) = Activity(
        ActivityId(id),
        at,
        id,
        ActivityKind.OTHER,
        ActivityStatus.CONFIRMED,
        movements,
        if (manual) emptySet() else setOf(SourceRecordId("source:$id")),
        decoder,
        manual,
    )
}
