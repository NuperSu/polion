package io.polion.core.application

import io.polion.core.holdings.BalanceCoverage
import io.polion.core.market.PriceStatus
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PortfolioApplicationTest {
    @Test
    fun assetListPreferencePersistsInBackupWithoutChangingBalances() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.seedDemo()
            val before = app.snapshot().balances
            assertEquals(AssetListPreference(), app.assetListPreference())
            val preference = AssetListPreference(AssetSort.NAME, hideEmpty = true)
            app.setAssetListPreference(preference)
            assertEquals(preference, PortfolioApplication(db) { 1000L }.assetListPreference())
            assertEquals(before, app.snapshot().balances)
            val backup = app.exportBackup("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals(preference, PortfolioApplication(staging) { 1000L }.assetListPreference())
            }
        }
    }

    @Test
    fun dashboardWidgetsPersistOrderVisibilityAndBackup() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val defaults = app.dashboardWidgets()
            assertEquals(DashboardWidget.entries, defaults.map { it.widget })
            assertTrue(defaults.all { it.visible })
            val changed = defaults.reversed().map { if (it.widget == DashboardWidget.HEALTH) it.copy(visible = false) else it }
            app.setDashboardWidgets(changed)
            assertEquals(changed, PortfolioApplication(db) { 1000L }.dashboardWidgets())
            assertFails { app.setDashboardWidgets(changed.drop(1)) }
            assertEquals(changed, app.dashboardWidgets())
            val backup = app.exportBackup("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals(changed, PortfolioApplication(staging) { 1000L }.dashboardWidgets())
            }
        }
    }

    @Test
    fun appearancePreferencePersistsAndRejectsUnknownValues() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            assertEquals("system", app.appearanceTheme())
            app.setAppearanceTheme("light")
            assertEquals("light", PortfolioApplication(db) { 1000L }.appearanceTheme())
            assertFails { app.setAppearanceTheme("unrecognized") }
            assertEquals("light", app.appearanceTheme())
            app.setAppearanceTheme("dark")
            assertEquals("dark", app.appearanceTheme())
            val backup = app.exportBackup("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals("dark", PortfolioApplication(staging) { 1000L }.appearanceTheme())
            }
        }
    }

    @Test
    fun csvEscapesSpreadsheetFormulasWithoutChangingSignedAmounts() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.seedDemo()
            app.saveOverlay(ActivityOverlay(ActivityId("demo-deposit"), label = "  =2+2", note = "@formula,\"quoted\""))
            val csv = app.exportCsv()
            assertTrue(csv.contains("\"'  =2+2\""))
            assertTrue(csv.contains("\"'@formula,\"\"quoted\"\"\""))
            assertTrue(csv.contains("\"-"))
            assertTrue(!csv.contains("\"'-"))
        }
    }

    @Test fun persistedCorrectionsHistoryAndPagination() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            assertTrue(app.snapshot().activities.isEmpty())
            app.seedDemo()
            val initial = app.snapshot()
            assertEquals(BalanceCoverage.UNKNOWN, initial.coverage)
            assertEquals(
                "97500.00000000",
                initial.valuations
                    .single()
                    .value
                    .toString(),
            )
            assertEquals(PriceStatus.FRESH, initial.valuations.single().priceStatus)
            assertTrue(app.history(15552000000L - 91L * 86400000L).isEmpty())
            app.saveOverlay(
                ActivityOverlay(
                    ActivityId("demo-deposit"),
                    label = "Corrected",
                    amounts =
                        mapOf(
                            EventId("demo-movement") to AtomicAmount(AssetId("bitcoin:btc"), ExactInteger.parse("100000000")),
                        ),
                ),
            )
            val reopened = PortfolioApplication(db) { 15552000000L }
            assertEquals(
                "125000000",
                reopened
                    .snapshot()
                    .valuations
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(1, reopened.snapshot(ActivityFilter(search = "corrected")).activityTotal)
            assertEquals(3, reopened.snapshot(ActivityFilter(offset = 1)).activities.size)
            assertEquals(
                "81250.00000000",
                reopened
                    .history(15552000000L)
                    .single()
                    .value
                    .toString(),
            )
            assertTrue(reopened.exportCsv().contains("Corrected"))
            val accounting = reopened.accountingReport()
            assertTrue(accounting.missingTreatments.isEmpty())
            assertEquals(
                "7200/1",
                accounting.report.disposals
                    .single()
                    .basis
                    .toString(),
            )
            assertEquals(
                "1800/1",
                accounting.report.disposals
                    .single()
                    .gain
                    .toString(),
            )
        }
    }

    @Test fun historicalAnalyticsAndSampleFlagsUseAuthoritativeLedger() {
        JvmSqliteDatabase(":memory:").use { db ->
            val at = 15552000000L
            val app = PortfolioApplication(db) { at }
            assertEquals(false, app.snapshot().sampleData)
            app.seedDemo()
            assertTrue(app.snapshot().sampleData)
            assertEquals(1, app.snapshot(ActivityFilter(limit = 1)).pendingCount)
            val current = app.analytics(at)
            assertEquals(app.snapshot().valuations, current.allocation)
            assertEquals(
                "150000000",
                current.accounts
                    .single()
                    .valuations
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            val deposit = current.categories.single { it.kind == io.polion.core.ledger.ActivityKind.DEPOSIT }
            assertEquals("165000000", deposit.incoming.toString())
            assertEquals(2, deposit.activities)
            val earlier = app.analytics(at - 30L * 86400000L)
            assertEquals(
                "40000000",
                earlier.allocation
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(1, earlier.categories.single().activities)
            app.wipe()
            assertEquals(false, app.snapshot().sampleData)
            app.recordManualPrice(
                io.polion.core.market.PriceObservation(
                    "after-wipe",
                    io.polion.core.model
                        .Price(
                            AssetId("bitcoin:btc"),
                            AssetId("USD"),
                            io.polion.core.model.ExactDecimal
                                .parse("100"),
                        ),
                    null,
                    at,
                    at,
                    true,
                ),
            )
        }
    }

    @Test fun portfolioScopeFiltersProjectionAndHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.seedDemo()
            val owner =
                io.polion.core.model
                    .OwnerId("other")
            val account =
                io.polion.core.model
                    .AccountId("other-account")
            app.saveOwner(
                io.polion.core.ownership
                    .Owner(owner, "Other"),
            )
            app.saveAccount(
                io.polion.core.ownership
                    .Account(account, owner, "Other account", io.polion.core.ownership.AccountKind.ACCOUNT),
            )
            app.savePortfolio(
                io.polion.core.ownership
                    .Portfolio(
                        io.polion.core.model
                            .PortfolioId("other-only"),
                        "Other",
                        "USD",
                        ownerIds = setOf(owner),
                    ),
            )
            val scoped =
                app.snapshot(
                    ActivityFilter(
                        portfolio =
                            io.polion.core.model
                                .PortfolioId("other-only"),
                    ),
                )
            assertTrue(scoped.activities.isEmpty())
            assertTrue(scoped.valuations.isEmpty())
            assertEquals(
                null,
                app.firstHistoryActivity(
                    io.polion.core.model
                        .PortfolioId("other-only"),
                ),
            )
            assertTrue(app.firstHistoryActivity() != null)
            assertTrue(
                app
                    .history(
                        15552000000L,
                        io.polion.core.model
                            .PortfolioId("other-only"),
                    ).isEmpty(),
            )
            assertTrue(
                app
                    .accountingReport(
                        io.polion.core.model
                            .PortfolioId("other-only"),
                    ).report.lots
                    .isEmpty(),
            )
        }
    }

    @Test fun reloadInvalidatesProjectionAfterAnotherInstanceWrites() {
        JvmSqliteDatabase(":memory:").use { db ->
            val first = PortfolioApplication(db) { 15552000000L }
            first.seedDemo()
            first.snapshot()
            val second = PortfolioApplication(db) { 15552000000L }
            second.saveOverlay(ActivityOverlay(ActivityId("demo-deposit"), label = "External update"))
            assertEquals(0, first.snapshot(ActivityFilter(search = "External update")).activityTotal)
            first.reload()
            assertEquals(1, first.snapshot(ActivityFilter(search = "External update")).activityTotal)
        }
    }

    @Test fun accountAndInvalidTargetAreNotPartiallySaved() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner =
                io.polion.core.model
                    .OwnerId("owner")
            val account =
                io.polion.core.model
                    .AccountId("account")
            app.saveOwner(
                io.polion.core.ownership
                    .Owner(owner, "Owner"),
            )
            val target =
                io.polion.core.ownership.TrackingTarget(
                    io.polion.core.model
                        .TrackingTargetId("target"),
                    account,
                    io.polion.core.ownership
                        .TargetIdentity(io.polion.core.bitcoin.BITCOIN_MAINNET, "address", "invalid"),
                    io.polion.core.ownership.TargetRole.LEAF,
                    "Address",
                )
            assertFails {
                app.saveAccountAndTarget(
                    io.polion.core.ownership
                        .Account(account, owner, "Account", io.polion.core.ownership.AccountKind.ACCOUNT),
                    target,
                )
            }
            assertTrue(
                app
                    .snapshot()
                    .ownership.accounts
                    .isEmpty(),
            )
        }
    }

    @Test fun bitcoinScanRequiresConsentAndOnlyRecordsSuccessfulStableScan() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner =
                io.polion.core.model
                    .OwnerId("owner")
            val account =
                io.polion.core.model
                    .AccountId("account")
            app.saveOwner(
                io.polion.core.ownership
                    .Owner(owner, "Owner"),
            )
            app.saveAccount(
                io.polion.core.ownership
                    .Account(account, owner, "Account", io.polion.core.ownership.AccountKind.ACCOUNT),
            )
            app.saveTarget(
                io.polion.core.ownership.TrackingTarget(
                    io.polion.core.model
                        .TrackingTargetId("target"),
                    account,
                    io.polion.core.ownership.TargetIdentity(
                        io.polion.core.bitcoin.BITCOIN_MAINNET,
                        "address",
                        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                    ),
                    io.polion.core.ownership.TargetRole.LEAF,
                    "Address",
                ),
            )
            var requests = 0
            val transport =
                io.polion.core.bitcoin.EsploraTransport { path ->
                    requests++
                    if (path ==
                        "/blocks/tip/hash"
                    ) {
                        "a".repeat(64)
                    } else if (path.startsWith("/block-height/")) {
                        "a".repeat(64)
                    } else if (path.startsWith("/block/")) {
                        """{"id":"${"a".repeat(64)}","height":900000}"""
                    } else if (path.endsWith("/txs/mempool") ||
                        path.endsWith("/txs/chain") ||
                        path.endsWith("/utxo")
                    ) {
                        "[]"
                    } else {
                        """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                    }
                }
            assertFails { app.refreshBitcoin(transport) }
            assertEquals(0, requests)
            app.setSourceConsent(true)
            app.setSourceEndpoint("https://example.org/api")
            assertEquals("https://example.org/api", app.snapshot().sourceEndpoint)
            assertTrue(app.snapshot().sourceConsent)
            val progress = mutableListOf<BitcoinScanProgress>()
            assertEquals(listOf(io.polion.core.sync.JobState.COMPLETED), app.refreshBitcoin(transport) { progress.add(it) })
            assertEquals(BitcoinScanProgress(1, 1, 1, 0), progress.first())
            assertEquals("apply", progress.last().phase)
            assertEquals(false, app.snapshot().sourceStatus.interrupted)
            assertEquals(BalanceCoverage.PARTIAL, app.snapshot().coverage)
            assertEquals(15552000000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
            assertFails {
                app.refreshBitcoin(
                    io.polion.core.bitcoin.EsploraTransport { path ->
                        if (path ==
                            "/blocks/tip/hash"
                        ) {
                            "a".repeat(64)
                        } else if (path.startsWith("/block-height/")) {
                            "a".repeat(64)
                        } else if (path.startsWith("/block/")) {
                            """{"id":"${"a".repeat(64)}","height":900000}"""
                        } else if (path.endsWith("/txs/mempool") ||
                            path.endsWith("/txs/chain") ||
                            path.endsWith("/utxo")
                        ) {
                            "[]"
                        } else {
                            """{"chain_stats":{"tx_count":1},"mempool_stats":{"tx_count":0}}"""
                        }
                    },
                )
            }
            assertEquals(15552000000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
            var tipReads = 0
            assertEquals(
                listOf(io.polion.core.sync.JobState.COMPLETED),
                app.refreshBitcoin(
                    io.polion.core.bitcoin.EsploraTransport { path ->
                        when {
                            path == "/blocks/tip/hash" -> {
                                tipReads++
                                (if (tipReads == 1) "a" else "b").repeat(64)
                            }
                            path.startsWith("/block-height/") -> "a".repeat(64)
                            path.startsWith("/block/") -> """{"id":"${"a".repeat(64)}","height":900000}"""
                            path.endsWith("/txs/mempool") || path.endsWith("/txs/chain") || path.endsWith("/utxo") -> "[]"
                            else -> """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                        }
                    },
                ),
            )
            var tips = 0
            assertFails {
                app.refreshBitcoin(
                    io.polion.core.bitcoin.EsploraTransport { path ->
                        if (path ==
                            "/blocks/tip/hash"
                        ) {
                            tips++
                            (
                                if (tips ==
                                    1
                                ) {
                                    "a"
                                } else {
                                    "b"
                                }
                            ).repeat(64)
                        } else if (path.startsWith("/block-height/")) {
                            "b".repeat(64)
                        } else if (path.startsWith("/block/")) {
                            """{"id":"${"a".repeat(64)}","height":900000}"""
                        } else if (path.endsWith("/txs/mempool") ||
                            path.endsWith("/txs/chain") ||
                            path.endsWith("/utxo")
                        ) {
                            "[]"
                        } else {
                            """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                        }
                    },
                )
            }
            assertEquals("https://example.org/api", app.snapshot().sourceEndpoint)
            assertTrue(app.snapshot().sourceConsent)
            assertEquals(false, app.snapshot().sourceStatus.interrupted)
            db.execute("INSERT INTO application_settings VALUES ('source_in_progress','true')")
            assertTrue(PortfolioApplication(db) { 15552000000L }.snapshot().sourceStatus.interrupted)
            db.execute("DELETE FROM application_settings WHERE key='source_in_progress'")
            assertEquals(
                io.polion.core.bitcoin.BitcoinProviderFailure.REORG_RECOVERY_REQUIRED.userMessage,
                app.snapshot().sourceStatus.lastError,
            )
            assertEquals(15552000000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
        }
    }

    @Test fun archivedAccountsCanBeRestoredWithoutLosingHistoryOrTreatments() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.seedDemo()
            val before = app.snapshot()
            val report = app.accountingReport()
            app.archiveAccount(
                io.polion.core.model
                    .AccountId("demo-wallet"),
            )
            check(app.snapshot().valuations.isEmpty())
            check(app.snapshot().activities.isEmpty())
            check(
                "polion:archived" in
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .tags,
            )
            app.archiveAccount(
                io.polion.core.model
                    .AccountId("demo-wallet"),
                false,
            )
            assertEquals(before.valuations, app.snapshot().valuations)
            assertEquals(before.activityTotal, app.snapshot().activityTotal)
            assertEquals(report, app.accountingReport())
        }
    }

    @Test fun portableBackupPreservesTheTargetsOwnAndroidLocaleMetadata() {
        JvmSqliteDatabase(":memory:").use { source ->
            JvmSqliteDatabase(":memory:").use { target ->
                source.execute("CREATE TABLE android_metadata (locale TEXT)")
                source.execute("INSERT INTO android_metadata VALUES ('en_US')")
                target.execute("CREATE TABLE android_metadata (locale TEXT)")
                target.execute("INSERT INTO android_metadata VALUES ('de_DE')")
                val app = PortfolioApplication(source) { 15552000000L }
                app.seedDemo()
                val bytes = app.exportBackup("password".toCharArray())
                app.restoreToStaging(target, bytes, "password".toCharArray())
                assertEquals("de_DE", target.query("SELECT locale FROM android_metadata").single().single())
                assertEquals(4, PortfolioApplication(target) { 15552000000L }.snapshot().activityTotal)
            }
        }
    }

    @Test fun backupFromBeforeScanTablesRestoresIntoCurrentSchema() {
        JvmSqliteDatabase(":memory:").use { oldDb ->
            val oldApp = PortfolioApplication(oldDb) { 15552000000L }
            oldApp.seedDemo()
            val expected = oldApp.snapshot()
            oldDb.execute("DROP TABLE bitcoin_scan_present")
            oldDb.execute("DROP TABLE bitcoin_scan_stage")
            oldDb.execute("DROP TABLE bitcoin_scan_blocks")
            val archive = oldApp.exportBackup("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                oldApp.restoreToStaging(staging, archive, "password".toCharArray())
                assertEquals(expected, PortfolioApplication(staging) { 15552000000L }.snapshot())
            }
        }
    }

    @Test fun encryptedStagingRestoreAndWipe() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.seedDemo()
            app.setSourceConsent(true)
            val archive = app.exportBackup("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                assertFails { app.restoreToStaging(staging, archive, "wrong".toCharArray()) }
                app.restoreToStaging(staging, archive, "password".toCharArray())
                val restored = PortfolioApplication(staging) { 15552000000L }
                assertEquals(app.snapshot(), restored.snapshot())
                assertTrue(restored.sourceConsent())
            }
            app.wipe()
            assertTrue(app.snapshot().activities.isEmpty())
            assertTrue(
                app
                    .snapshot()
                    .ownership.owners
                    .isEmpty(),
            )
        }
    }
}
