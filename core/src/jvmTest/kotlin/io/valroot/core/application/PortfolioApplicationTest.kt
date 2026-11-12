package io.valroot.core.application

import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PortfolioApplicationTest {
    @Test
    fun refreshAndMarketPreferencesSurvivePortableBackup() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            assertEquals(null, app.settings.automaticRefresh())
            assertEquals("dark", app.settings.theme())
            app.settings.setRefreshPreferences(automatic = false, wifiOnly = true)
            app.market.setFavorites(setOf(AssetId("btc"), AssetId("eth")))
            val backup = app.backup.export("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "password".toCharArray())
                val restored = PortfolioApplication(staging) { 1000L }
                assertEquals(false, restored.settings.automaticRefresh())
                assertTrue(restored.settings.wifiOnlyRefresh())
                assertEquals(setOf(AssetId("btc"), AssetId("eth")), restored.market.favorites())
            }
        }
    }

    @Test
    fun assetListPreferencePersistsInBackupWithoutChangingBalances() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.demo.seed()
            val before = app.snapshot().balances
            assertEquals(AssetListPreference(), app.settings.assetList())
            db.execute("INSERT OR REPLACE INTO application_settings VALUES (?, ?)", listOf("asset_list_preference", "NAME:1"))
            assertEquals(AssetListPreference(AssetSort.NAME, hideEmpty = true), app.settings.assetList())
            val preference = AssetListPreference(AssetSort.NAME, hideEmpty = true, hideSmall = true)
            app.settings.setAssetList(preference)
            assertEquals(preference, PortfolioApplication(db) { 1000L }.settings.assetList())
            assertEquals(before, app.snapshot().balances)
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals(preference, PortfolioApplication(staging) { 1000L }.settings.assetList())
            }
        }
    }

    @Test
    fun dashboardWidgetsPersistOrderVisibilityAndBackup() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val defaults = app.settings.dashboardWidgets()
            assertEquals(DashboardWidget.entries, defaults.map { it.widget })
            assertTrue(defaults.all { it.visible })
            val changed = defaults.reversed().map { if (it.widget == DashboardWidget.HEALTH) it.copy(visible = false) else it }
            app.settings.setDashboardWidgets(changed)
            assertEquals(changed, PortfolioApplication(db) { 1000L }.settings.dashboardWidgets())
            assertFails { app.settings.setDashboardWidgets(changed.drop(1)) }
            assertEquals(changed, app.settings.dashboardWidgets())
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals(changed, PortfolioApplication(staging) { 1000L }.settings.dashboardWidgets())
            }
        }
    }

    @Test
    fun appearancePreferencePersistsAndRejectsUnknownValues() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            assertEquals("dark", app.settings.theme())
            app.settings.setTheme("light")
            assertEquals("light", PortfolioApplication(db) { 1000L }.settings.theme())
            assertFails { app.settings.setTheme("unrecognized") }
            assertEquals("light", app.settings.theme())
            app.settings.setTheme("dark")
            assertEquals("dark", app.settings.theme())
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals("dark", PortfolioApplication(staging) { 1000L }.settings.theme())
            }
        }
    }

    @Test
    fun generalPreferencesPersistThroughRestartAndBackup() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            assertEquals(GeneralPreferences(), app.settings.general())
            app.settings.setLandingPage(LandingPage.INSIGHTS)
            app.settings.setSecondaryCurrency(AssetId("EUR"))
            app.settings.setShowPortfolioChart(false)
            app.settings.setZeroBasisRewards(true)
            app.settings.setDeviceName("  Anton's Pixel  ")
            app.settings.setHideAmounts(true)
            assertFails { app.settings.setSecondaryCurrency(AssetId("JPY")) }
            assertFails { app.settings.setDeviceName("two\nlines") }
            assertFails { app.settings.setDeviceName("x".repeat(41)) }
            val expected = GeneralPreferences(LandingPage.INSIGHTS, AssetId("EUR"), false, true, "Anton's Pixel", hideAmounts = true)
            assertEquals(expected, PortfolioApplication(db) { 1000L }.settings.general())
            val backup = app.backup.export("fixture-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, backup, "fixture-password".toCharArray())
                assertEquals(expected, PortfolioApplication(staging) { 1000L }.settings.general())
            }
            app.settings.setSecondaryCurrency(null)
            app.settings.setDeviceName(" ")
            assertEquals(expected.copy(secondaryCurrency = null, deviceName = null), app.settings.general())
        }
    }

    @Test
    fun secondaryCurrencyIsRefreshedAndValuesTheSameBalances() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.assets.setCurrency(AssetId("USD"))
            app.settings.setSecondaryCurrency(AssetId("EUR"))
            assertEquals(listOf("USD", "EUR"), app.prices.automaticCurrencies().map { it.value })
            val snapshot = app.snapshot()
            assertEquals(AssetId("EUR"), snapshot.secondaryCurrency)
            assertEquals(snapshot.valuations.map { it.amount }, snapshot.secondaryValuations.map { it.amount })
            app.settings.setSecondaryCurrency(AssetId("USD"))
            assertEquals(null, app.snapshot().secondaryCurrency)
        }
    }

    @Test
    fun csvEscapesSpreadsheetFormulasWithoutChangingSignedAmounts() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.demo.seed()
            app.transactions.saveOverlay(ActivityOverlay(ActivityId("demo-deposit"), label = "  =2+2", note = "@formula,\"quoted\""))
            val csv = app.csv.export()
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
            app.demo.seed()
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
            assertTrue(app.analytics.history(15552000000L - 91L * 86400000L).isEmpty())
            app.transactions.saveOverlay(
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
                    .analytics
                    .history(15552000000L)
                    .single()
                    .value
                    .toString(),
            )
            assertTrue(reopened.csv.export().contains("Corrected"))
            val accounting = reopened.accounting.report()
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
            app.demo.seed()
            assertTrue(app.snapshot().sampleData)
            assertEquals(1, app.snapshot(ActivityFilter(limit = 1)).pendingCount)
            val current = app.analytics.report(at)
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
            val deposit = current.categories.single { it.kind == io.valroot.core.ledger.ActivityKind.DEPOSIT }
            assertEquals("165000000", deposit.incoming.toString())
            assertEquals(2, deposit.activities)
            val earlier = app.analytics.report(at - 30L * 86400000L)
            assertEquals(
                "40000000",
                earlier.allocation
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(1, earlier.categories.single().activities)
            app.backup.wipe()
            assertEquals(false, app.snapshot().sampleData)
            app.prices.recordManual(
                io.valroot.core.market.PriceObservation(
                    "after-wipe",
                    io.valroot.core.model
                        .Price(
                            AssetId("bitcoin:btc"),
                            AssetId("USD"),
                            io.valroot.core.model.ExactDecimal
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
            app.demo.seed()
            val owner =
                io.valroot.core.model
                    .OwnerId("other")
            val account =
                io.valroot.core.model
                    .AccountId("other-account")
            app.accounts.saveOwner(
                io.valroot.core.ownership
                    .Owner(owner, "Other"),
            )
            app.accounts.saveAccount(
                io.valroot.core.ownership
                    .Account(account, owner, "Other account", io.valroot.core.ownership.AccountKind.ACCOUNT),
            )
            app.accounts.savePortfolio(
                io.valroot.core.ownership
                    .Portfolio(
                        io.valroot.core.model
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
                            io.valroot.core.model
                                .PortfolioId("other-only"),
                    ),
                )
            assertTrue(scoped.activities.isEmpty())
            assertTrue(scoped.valuations.isEmpty())
            assertEquals(
                null,
                app.analytics.firstHistoryActivity(
                    io.valroot.core.model
                        .PortfolioId("other-only"),
                ),
            )
            assertTrue(app.analytics.firstHistoryActivity() != null)
            assertTrue(
                app
                    .analytics
                    .history(
                        15552000000L,
                        io.valroot.core.model
                            .PortfolioId("other-only"),
                    ).isEmpty(),
            )
            assertTrue(
                app
                    .accounting
                    .report(
                        io.valroot.core.model
                            .PortfolioId("other-only"),
                    ).report.lots
                    .isEmpty(),
            )
        }
    }

    @Test fun reloadInvalidatesProjectionAfterAnotherInstanceWrites() {
        JvmSqliteDatabase(":memory:").use { db ->
            val first = PortfolioApplication(db) { 15552000000L }
            first.demo.seed()
            first.snapshot()
            val second = PortfolioApplication(db) { 15552000000L }
            second.transactions.saveOverlay(ActivityOverlay(ActivityId("demo-deposit"), label = "External update"))
            assertEquals(0, first.snapshot(ActivityFilter(search = "External update")).activityTotal)
            first.reload()
            assertEquals(1, first.snapshot(ActivityFilter(search = "External update")).activityTotal)
        }
    }

    @Test fun accountAndInvalidTargetAreNotPartiallySaved() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner =
                io.valroot.core.model
                    .OwnerId("owner")
            val account =
                io.valroot.core.model
                    .AccountId("account")
            app.accounts.saveOwner(
                io.valroot.core.ownership
                    .Owner(owner, "Owner"),
            )
            val target =
                io.valroot.core.ownership.TrackingTarget(
                    io.valroot.core.model
                        .TrackingTargetId("target"),
                    account,
                    io.valroot.core.ownership
                        .TargetIdentity(io.valroot.core.bitcoin.BITCOIN_MAINNET, "address", "invalid"),
                    io.valroot.core.ownership.TargetRole.LEAF,
                    "Address",
                )
            assertFails {
                app.accounts.saveAccountAndTarget(
                    io.valroot.core.ownership
                        .Account(account, owner, "Account", io.valroot.core.ownership.AccountKind.ACCOUNT),
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
                io.valroot.core.model
                    .OwnerId("owner")
            val account =
                io.valroot.core.model
                    .AccountId("account")
            app.accounts.saveOwner(
                io.valroot.core.ownership
                    .Owner(owner, "Owner"),
            )
            app.accounts.saveAccount(
                io.valroot.core.ownership
                    .Account(account, owner, "Account", io.valroot.core.ownership.AccountKind.ACCOUNT),
            )
            app.accounts.saveTarget(
                io.valroot.core.ownership.TrackingTarget(
                    io.valroot.core.model
                        .TrackingTargetId("target"),
                    account,
                    io.valroot.core.ownership.TargetIdentity(
                        io.valroot.core.bitcoin.BITCOIN_MAINNET,
                        "address",
                        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                    ),
                    io.valroot.core.ownership.TargetRole.LEAF,
                    "Address",
                ),
            )
            var requests = 0
            val transport =
                io.valroot.core.bitcoin.EsploraTransport { path ->
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
            assertFails { app.bitcoin.refresh(transport) }
            assertEquals(0, requests)
            app.sources.setConsent(true)
            app.sources.setEndpoint("https://example.org/api")
            assertEquals("https://example.org/api", app.snapshot().sourceEndpoint)
            assertTrue(app.snapshot().sourceConsent)
            val progress = mutableListOf<BitcoinScanProgress>()
            assertEquals(listOf(io.valroot.core.sync.JobState.COMPLETED), app.bitcoin.refresh(transport) { progress.add(it) })
            assertEquals(BitcoinScanProgress(1, 1, 1, 0), progress.first())
            assertEquals("apply", progress.last().phase)
            assertEquals(false, app.snapshot().sourceStatus.interrupted)
            assertEquals(BalanceCoverage.PARTIAL, app.snapshot().coverage)
            assertEquals(15552000000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
            assertFails {
                app.bitcoin.refresh(
                    io.valroot.core.bitcoin.EsploraTransport { path ->
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
                listOf(io.valroot.core.sync.JobState.COMPLETED),
                app.bitcoin.refresh(
                    io.valroot.core.bitcoin.EsploraTransport { path ->
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
                app.bitcoin.refresh(
                    io.valroot.core.bitcoin.EsploraTransport { path ->
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
                io.valroot.core.bitcoin.BitcoinProviderFailure.REORG_RECOVERY_REQUIRED.userMessage,
                app.snapshot().sourceStatus.lastError,
            )
            assertEquals(15552000000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
        }
    }

    @Test fun archivedAccountsCanBeRestoredWithoutLosingHistoryOrTreatments() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.demo.seed()
            val before = app.snapshot()
            val report = app.accounting.report()
            app.accounts.archiveAccount(
                io.valroot.core.model
                    .AccountId("demo-wallet"),
            )
            check(app.snapshot().valuations.isEmpty())
            check(app.snapshot().activities.isEmpty())
            check(
                "valroot:archived" in
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .tags,
            )
            app.accounts.archiveAccount(
                io.valroot.core.model
                    .AccountId("demo-wallet"),
                false,
            )
            assertEquals(before.valuations, app.snapshot().valuations)
            assertEquals(before.activityTotal, app.snapshot().activityTotal)
            assertEquals(report, app.accounting.report())
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
                app.demo.seed()
                val bytes = app.backup.export("password".toCharArray())
                app.backup.restoreToStaging(target, bytes, "password".toCharArray())
                assertEquals("de_DE", target.query("SELECT locale FROM android_metadata").single().single())
                assertEquals(4, PortfolioApplication(target) { 15552000000L }.snapshot().activityTotal)
            }
        }
    }

    @Test fun backupFromBeforeScanTablesRestoresIntoCurrentSchema() {
        JvmSqliteDatabase(":memory:").use { oldDb ->
            val oldApp = PortfolioApplication(oldDb) { 15552000000L }
            oldApp.demo.seed()
            val expected = oldApp.snapshot()
            oldDb.execute("DROP TABLE bitcoin_scan_present")
            oldDb.execute("DROP TABLE bitcoin_scan_stage")
            oldDb.execute("DROP TABLE bitcoin_scan_blocks")
            val archive = oldApp.backup.export("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                oldApp.backup.restoreToStaging(staging, archive, "password".toCharArray())
                assertEquals(expected, PortfolioApplication(staging) { 15552000000L }.snapshot())
            }
        }
    }

    @Test fun encryptedStagingRestoreAndWipe() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            app.demo.seed()
            app.sources.setConsent(true)
            val archive = app.backup.export("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                assertFails { app.backup.restoreToStaging(staging, archive, "wrong".toCharArray()) }
                app.backup.restoreToStaging(staging, archive, "password".toCharArray())
                val restored = PortfolioApplication(staging) { 15552000000L }
                assertEquals(app.snapshot(), restored.snapshot())
                assertTrue(restored.sources.consent())
            }
            app.backup.wipe()
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
