package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.NetworkId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.Rational
import io.valroot.core.model.SourceRecordId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountingScopeTest {
    @Test fun sourceJobsMatchAddressAndAccountWithoutInventingAcceptance() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            val address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
            val target =
                TrackingTarget(
                    TrackingTargetId("target"),
                    first,
                    TargetIdentity(NetworkId("bitcoin-mainnet"), "address", address),
                    TargetRole.LEAF,
                    "Watch",
                )
            app.accounts.saveTarget(target)
            assertNull(
                app.sources
                    .scanJobs(target.id)
                    .single()
                    .job,
            )
            db.execute(
                "INSERT INTO sync_jobs VALUES (?, 'esplora:fixture', ?, ?, NULL, NULL, ?, 0, 0, 0, 0, ?, ?, NULL, ?, NULL)",
                listOf("bitcoin-1-${sha256(address).take(16)}", "bitcoin-mainnet", first.value, "COMPLETED", "2", "COMPLETE", "provider"),
            )
            val job =
                app.sources
                    .scanJobs(target.id)
                    .single()
                    .job!!
            assertEquals("COMPLETED", job.state)
            assertEquals(2, job.pages)
            assertEquals("COMPLETE", job.coverage)
            assertEquals("provider", job.provider)
            db.execute("UPDATE sync_jobs SET account=?", listOf(second.value))
            assertNull(
                app.sources
                    .scanJobs(target.id)
                    .single()
                    .job,
            )
        }
    }

    @Test fun savedEvidenceIsScopedBoundedAndContainsRevisionMetadata() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            val store = Store(db)
            val payload = "fixture transaction"
            repeat(11) { index ->
                store.ingest(
                    RawObservation(
                        "source:own",
                        "bitcoin",
                        "txid",
                        "bitcoin-mainnet",
                        "provider",
                        "2024-01-${(index + 1).toString().padStart(2, '0')}T00:00:00Z",
                        payload,
                        "v1",
                        sha256(payload),
                        "private-context",
                    ),
                )
            }
            ActivityRepository(db).save(activity("own", 1, listOf(movement("own", first, "1")), false, "decoder"))
            val record = app.sources.evidence(ActivityId("own"), selected)!!.single()
            assertEquals("bitcoin-mainnet", record.network)
            assertEquals(10, record.observations.size)
            assertEquals("2024-01-11T00:00:00Z", record.observations.first().retrievedAt)
            assertEquals("provider", record.observations.first().source)
            assertEquals(sha256(payload), record.observations.first().checksum)
            assertTrue(record.hasMoreObservations)
            app.accounts.savePortfolio(Portfolio(PortfolioId("other-scope"), "Other", "USD", accountIds = setOf(second)))
            assertNull(app.sources.evidence(ActivityId("own"), PortfolioId("other-scope")))
        }
    }

    @Test fun effectiveActivityLookupRespectsPortfolioScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            buy(app, "other", second, "USD")
            assertEquals(
                ActivityId("own"),
                app.accounting
                    .effectiveActivity(ActivityId("own"), selected)
                    ?.activity
                    ?.id,
            )
            assertNull(app.accounting.effectiveActivity(ActivityId("other"), selected))
            assertEquals(
                ActivityId("other"),
                app.accounting
                    .effectiveActivity(ActivityId("other"))
                    ?.activity
                    ?.id,
            )
            assertNull(app.accounting.effectiveActivity(ActivityId("missing"), selected))
        }
    }

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
                val view = app.accounting.report(selected)
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
                        .accounting
                        .report()
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
            app.accounts.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
            val view = app.accounting.report(selected)
            val lot = view.report.lots.single()
            assertEquals(destination, lot.account)
            assertEquals(Rational.of("10"), lot.basis)
            assertEquals(1L, lot.acquiredAt)
            assertTrue(view.invalidTreatments.isEmpty())
        }
    }

    @Test fun destinationPortfolioReportsMissingAndCurrencyMismatchedSourceDependencies() {
        listOf(null, "EUR").forEach { sourceQuote ->
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setup(db)
                buy(app, "source", first, sourceQuote)
                transfer(app, first, destination)
                app.accounts.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
                val view = app.accounting.report(selected)
                if (sourceQuote == null) {
                    // Missing treatments drop the dependent inventory: no lot is invented.
                    assertEquals(listOf(ActivityId("source")), view.missingTreatments)
                    assertTrue(view.invalidTreatments.isEmpty())
                    assertTrue(view.currencyMismatch.isEmpty())
                    assertTrue(view.report.lots.isEmpty())
                } else {
                    // A treatment priced in another currency keeps its lot with unknown basis, marked as a mismatch.
                    assertTrue(view.missingTreatments.isEmpty())
                    assertTrue(view.invalidTreatments.isEmpty())
                    assertEquals(listOf(ActivityId("source")), view.currencyMismatch)
                    val lot = view.report.lots.single()
                    assertEquals(destination, lot.account)
                    assertEquals(null, lot.basis)
                }
                assertTrue(view.report.disposals.isEmpty())
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
            app.transactions.saveManualActivity(activity("unrelated", 3, listOf(unrelated)))
            app.accounts.savePortfolio(Portfolio(selected, "Destination", "USD", accountIds = setOf(destination)))
            val view = app.accounting.report(selected)
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
            val view = app.accounting.report(selected)
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
            app.accounting.saveTreatments(ActivityId("other"), listOf(Treatment.Acquire(EventId("other"), quote("USD"))))
            val view = app.accounting.report(selected)
            assertEquals(
                Rational.of("10"),
                view.report.lots
                    .single()
                    .basis,
            )
            assertTrue(view.orderingUncertain.isEmpty())
            val all = app.accounting.report()
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
            val view = app.accounting.report()
            assertEquals(listOf(ActivityId("transfer")), view.invalidTreatments)
            assertTrue(view.report.lots.isEmpty())
            assertTrue(view.report.disposals.isEmpty())
        }
    }

    private fun setup(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1000L }
        val owner = OwnerId("first-owner")
        val other = OwnerId("second-owner")
        app.accounts.saveOwner(Owner(owner, "First owner"))
        app.accounts.saveOwner(Owner(other, "Second owner"))
        app.accounts.saveAccount(Account(first, owner, "First", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(destination, owner, "Destination", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(second, other, "Second", AccountKind.ACCOUNT))
        app.accounts.savePortfolio(Portfolio(selected, "First", "USD", ownerIds = setOf(owner)))
        return app
    }

    private fun buy(
        app: PortfolioApplication,
        id: String,
        account: AccountId,
        currency: String?,
    ) {
        app.transactions.saveManualActivity(activity(id, 1, listOf(movement(id, account, "1"))))
        if (currency != null) app.accounting.saveTreatments(ActivityId(id), listOf(Treatment.Acquire(EventId(id), quote(currency))))
    }

    private fun transfer(
        app: PortfolioApplication,
        from: AccountId,
        to: AccountId,
    ) {
        app.transactions.saveManualActivity(activity("transfer", 2, listOf(movement("out", from, "-1"), movement("in", to, "1"))))
        app.accounting.saveTreatments(ActivityId("transfer"), listOf(Treatment.InternalTransfer(EventId("out"), EventId("in"))))
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
