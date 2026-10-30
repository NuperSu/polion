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
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.Rational
import io.polion.core.model.SourceRecordId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
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
            app.saveTarget(target)
            assertNull(app.sourceScanJobs(target.id).single().job)
            db.execute(
                "INSERT INTO sync_jobs VALUES (?, 'esplora:fixture', ?, ?, NULL, NULL, ?, 0, 0, 0, 0, ?, ?, NULL, ?, NULL)",
                listOf("bitcoin-1-${sha256(address).take(16)}", "bitcoin-mainnet", first.value, "COMPLETED", "2", "COMPLETE", "provider"),
            )
            val job = app.sourceScanJobs(target.id).single().job!!
            assertEquals("COMPLETED", job.state)
            assertEquals(2, job.pages)
            assertEquals("COMPLETE", job.coverage)
            assertEquals("provider", job.provider)
            db.execute("UPDATE sync_jobs SET account=?", listOf(second.value))
            assertNull(app.sourceScanJobs(target.id).single().job)
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
            val record = app.activitySourceEvidence(ActivityId("own"), selected)!!.single()
            assertEquals("bitcoin-mainnet", record.network)
            assertEquals(10, record.observations.size)
            assertEquals("2024-01-11T00:00:00Z", record.observations.first().retrievedAt)
            assertEquals("provider", record.observations.first().source)
            assertEquals(sha256(payload), record.observations.first().checksum)
            assertTrue(record.hasMoreObservations)
            app.savePortfolio(Portfolio(PortfolioId("other-scope"), "Other", "USD", accountIds = setOf(second)))
            assertNull(app.activitySourceEvidence(ActivityId("own"), PortfolioId("other-scope")))
        }
    }

    @Test fun effectiveActivityLookupRespectsPortfolioScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setup(db)
            buy(app, "own", first, "USD")
            buy(app, "other", second, "USD")
            assertEquals(ActivityId("own"), app.effectiveActivity(ActivityId("own"), selected)?.activity?.id)
            assertNull(app.effectiveActivity(ActivityId("other"), selected))
            assertEquals(ActivityId("other"), app.effectiveActivity(ActivityId("other"))?.activity?.id)
            assertNull(app.effectiveActivity(ActivityId("missing"), selected))
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
