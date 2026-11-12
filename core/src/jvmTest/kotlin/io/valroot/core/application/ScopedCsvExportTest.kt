package io.valroot.core.application

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedCsvExportTest {
    @Test fun networkFilterUsesCurrentRawSourceEvidenceNotAssetOrManualInput() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            val store =
                io.valroot.core.storage
                    .Store(db)

            fun source(
                id: String,
                network: String,
            ) {
                val payload = "payload-$id"
                store.ingest(
                    io.valroot.core.storage.RawObservation(
                        id,
                        "fixture",
                        id,
                        network,
                        "provider",
                        "2024-01-01T00:00:00Z",
                        payload,
                        "1",
                        io.valroot.core.storage
                            .sha256(payload),
                        "fixture",
                    ),
                )
            }
            source("source-a", "network-a")
            source("source-b", "network-b")
            val repository =
                io.valroot.core.ledger
                    .ActivityRepository(db)

            fun save(
                id: String,
                source: String,
            ) {
                repository.save(
                    activity(id, ActivityKind.DEPOSIT, listOf(movement(id, first, btc, "1"))).copy(
                        manual = false,
                        decoderVersion = "fixture",
                        sources =
                            setOf(
                                io.valroot.core.model
                                    .SourceRecordId(source),
                            ),
                    ),
                )
            }
            save("a", "source-a")
            save("b", "source-b")
            app.transactions.saveManualActivity(activity("manual", ActivityKind.DEPOSIT, listOf(movement("manual", first, btc, "1"))))
            val filter = ActivityFilter(portfolio = selected, network = "network-a", includeNetworkChoices = true)
            val result = app.snapshot(filter)
            assertEquals(listOf("network-a", "network-b"), result.activityNetworks)
            assertEquals(listOf("a"), result.activities.map { it.activity.id.value })
            assertEquals(listOf("a"), rows(app.csv.export(filter)).map { it[0] })
            assertEquals(3, app.snapshot(ActivityFilter(portfolio = selected)).activityTotal)
            save("a", "source-b")
            app.reload()
            assertEquals(0, app.snapshot(filter).activityTotal)
            assertEquals(
                setOf("a", "b"),
                app
                    .snapshot(filter.copy(network = "network-b"))
                    .activities
                    .map { it.activity.id.value }
                    .toSet(),
            )
        }
    }

    @Test fun statusFilterMatchesSnapshotAndExportAcrossPages() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            app.transactions.saveManualActivity(activity("confirmed", ActivityKind.DEPOSIT, listOf(movement("confirmed", first, btc, "5"))))
            app.transactions.saveManualActivity(
                activity(
                    "pending-1",
                    ActivityKind.DEPOSIT,
                    listOf(movement("pending-1", first, btc, "2")),
                ).copy(status = ActivityStatus.PENDING),
            )
            app.transactions.saveManualActivity(
                activity(
                    "pending-2",
                    ActivityKind.DEPOSIT,
                    listOf(movement("pending-2", first, btc, "3")),
                ).copy(status = ActivityStatus.PENDING),
            )
            val filter = ActivityFilter(portfolio = selected, status = ActivityStatus.PENDING, offset = 1, limit = 1)
            val snapshot = app.snapshot(filter)
            assertEquals(2, snapshot.activityTotal)
            assertEquals(1, snapshot.activities.size)
            assertEquals(
                ActivityStatus.PENDING,
                snapshot.activities
                    .single()
                    .activity.status,
            )
            assertEquals(setOf("pending-1", "pending-2"), rows(app.csv.export(filter)).map { it[0] }.toSet())
            assertEquals(1, app.snapshot(ActivityFilter(portfolio = selected, status = ActivityStatus.CONFIRMED)).activityTotal)
        }
    }

    private val first = AccountId("first-account")
    private val sibling = AccountId("sibling-account")
    private val foreign = AccountId("foreign-account")
    private val selected = PortfolioId("selected")
    private val otherPortfolio = PortfolioId("other")
    private val btc = AssetId("bitcoin:btc")
    private val otherAsset = AssetId("token:other")

    @Test fun selectedPortfolioExportsOnlyItsLegOfCrossScopeTransfer() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            app.transactions.saveManualActivity(
                activity(
                    "cross-scope",
                    ActivityKind.TRANSFER,
                    listOf(
                        movement("selected-leg", first, btc, "-7"),
                        movement("sibling-leg", sibling, btc, "3"),
                        movement("foreign-leg", foreign, otherAsset, "987654321"),
                    ),
                ),
            )

            val selectedCsv = app.csv.export(ActivityFilter(portfolio = selected))
            val row = rows(selectedCsv).single()
            assertEquals("cross-scope", row[0])
            assertEquals(first.value, row[4])
            assertEquals(btc.value, row[5])
            assertEquals("-7", row[6])
            assertFalse(selectedCsv.contains(sibling.value))
            assertFalse(selectedCsv.contains(foreign.value))
            assertFalse(selectedCsv.contains(otherAsset.value))
            assertFalse(selectedCsv.contains("987654321"))
            assertEquals(foreign.value, rows(app.csv.export(ActivityFilter(portfolio = otherPortfolio))).single()[4])
            assertTrue(rows(app.csv.export(ActivityFilter(portfolio = selected, account = foreign))).isEmpty())
        }
    }

    @Test fun accountAssetKindAndSearchFiltersSelectMatchingMovements() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            app.transactions.saveManualActivity(
                activity(
                    "mixed-transfer",
                    ActivityKind.TRANSFER,
                    listOf(
                        movement("btc", first, btc, "-7"),
                        movement("other", first, otherAsset, "9"),
                        movement("sibling", sibling, btc, "7"),
                    ),
                ),
            )
            app.transactions.saveOverlay(ActivityOverlay(ActivityId("mixed-transfer"), label = "Chosen transfer", note = "Receipt needle"))
            app.transactions.saveManualActivity(activity("deposit", ActivityKind.DEPOSIT, listOf(movement("deposit", first, btc, "11"))))

            assertEquals(3, rows(app.csv.export(ActivityFilter(account = first))).size)
            assertEquals(setOf(first.value), rows(app.csv.export(ActivityFilter(account = first))).map { it[4] }.toSet())
            assertEquals(1, rows(app.csv.export(ActivityFilter(asset = otherAsset))).size)
            assertEquals(3, rows(app.csv.export(ActivityFilter(kind = ActivityKind.TRANSFER))).size)
            assertEquals(3, rows(app.csv.export(ActivityFilter(search = "cHoSeN"))).size)
            assertEquals(3, rows(app.csv.export(ActivityFilter(search = "NEEDLE"))).size)
            val combined =
                rows(
                    app.csv.export(
                        ActivityFilter(portfolio = selected, account = first, asset = btc, kind = ActivityKind.TRANSFER, search = "needle"),
                    ),
                ).single()
            assertEquals(listOf("mixed-transfer", first.value, btc.value, "-7"), listOf(combined[0], combined[4], combined[5], combined[6]))
            assertTrue(rows(app.csv.export(ActivityFilter(search = "absent"))).isEmpty())
            assertTrue(
                rows(app.csv.export(ActivityFilter(portfolio = selected, asset = otherAsset, kind = ActivityKind.DEPOSIT))).isEmpty(),
            )
        }
    }

    @Test fun exportOmitsArchivedAccountsAndIncludesEveryMatchDespitePagination() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            repeat(60) { index ->
                val id = "match-$index"
                app.transactions.saveManualActivity(activity(id, ActivityKind.DEPOSIT, listOf(movement(id, first, btc, "1"))))
            }
            app.transactions.saveManualActivity(
                activity("archived", ActivityKind.DEPOSIT, listOf(movement("archived", sibling, btc, "999"))),
            )
            app.accounts.archiveAccount(sibling)
            val filter = ActivityFilter(portfolio = selected, search = "match-", offset = 55, limit = 1)

            assertEquals(1, app.snapshot(filter).activities.size)
            val exported = rows(app.csv.export(filter))
            assertEquals((0 until 60).map { "match-$it" }.toSet(), exported.map { it[0] }.toSet())
            assertEquals(60, exported.size)
            assertFalse(app.csv.export().contains("archived"))
            assertTrue(rows(app.csv.export(ActivityFilter(account = sibling))).isEmpty())
        }
    }

    @Test fun scopedExportEscapesFormulaTextAndPreservesSignedAmounts() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            app.transactions.saveManualActivity(activity("formula", ActivityKind.PAYMENT, listOf(movement("formula", first, btc, "-7"))))
            app.transactions.saveOverlay(ActivityOverlay(ActivityId("formula"), label = "  =2+2", note = "@formula,\"quoted\""))

            val csv = app.csv.export(ActivityFilter(portfolio = selected, search = "=2+2"))
            assertTrue(csv.contains("\"'  =2+2\""))
            assertTrue(csv.contains("\"'@formula,\"\"quoted\"\"\""))
            assertTrue(csv.contains("\"-7\""))
            assertFalse(csv.contains("\"'-7\""))
        }
    }

    private fun fixture(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1000L }
        val owner = OwnerId("first-owner")
        val otherOwner = OwnerId("other-owner")
        app.accounts.saveOwner(Owner(owner, "First owner"))
        app.accounts.saveOwner(Owner(otherOwner, "Other owner"))
        app.accounts.saveAccount(Account(first, owner, "First", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(sibling, owner, "Sibling", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(foreign, otherOwner, "Foreign", AccountKind.ACCOUNT))
        app.accounts.savePortfolio(Portfolio(selected, "Selected", "USD", ownerIds = setOf(owner), accountIds = setOf(first)))
        app.accounts.savePortfolio(Portfolio(otherPortfolio, "Other", "USD", ownerIds = setOf(otherOwner)))
        return app
    }

    private fun movement(
        id: String,
        account: AccountId,
        asset: AssetId,
        units: String,
    ) = Movement(EventId(id), account, AtomicAmount(asset, ExactInteger.parse(units)), MovementRole.TRANSFER)

    private fun activity(
        id: String,
        kind: ActivityKind,
        movements: List<Movement>,
    ) = Activity(ActivityId(id), 1L, id, kind, ActivityStatus.CONFIRMED, movements, emptySet(), null, manual = true)

    private fun rows(csv: String): List<List<String>> =
        csv
            .lineSequence()
            .drop(1)
            .filter { it.isNotEmpty() }
            .map { row -> row.split(",").map { it.removeSurrounding("\"") } }
            .toList()
}
