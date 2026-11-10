package io.valroot.core.backup

import io.valroot.core.alerts.AlertCondition
import io.valroot.core.alerts.NotificationKind
import io.valroot.core.alerts.NotificationPreferences
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetSort
import io.valroot.core.application.CsvFeeDefault
import io.valroot.core.application.CsvImport
import io.valroot.core.application.CsvImportOptions
import io.valroot.core.application.GeneralPreferences
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.application.TransactionDraft
import io.valroot.core.application.TransactionType
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.Price
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.reconcile.ClassificationOrigin
import io.valroot.core.reconcile.ClassificationRule
import io.valroot.core.reconcile.LinkState
import io.valroot.core.reconcile.OverlayRepository
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An encrypted backup must restore every kind of user record. Each restore goes into a fresh staging database, and every
 * application clock is a [TestClock] that only moves when a test moves it, so all timestamps and expected values are fixed.
 */
class RestorePreservationTest {
    private class TestClock(
        var millis: Long,
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        /** 2027-01-15T08:00:00Z. */
        const val T0 = 1_800_000_000_000L

        /** The second market snapshot. It moves Bitcoin above its alert threshold and Ethereum 10 percent past its reference. */
        const val T1 = T0 + 3 * HOUR

        /** The local day passed to notification checks. The daily update is keyed by this day. */
        const val LOCAL_DAY = "2027-01-15"
    }

    private val owner = OwnerId("alice")
    private val walletA = AccountId("wallet-a")
    private val walletB = AccountId("wallet-b")
    private val usd = AssetId("USD")
    private val eur = AssetId("EUR")
    private val csvHeader = "Date,Type,Asset,Quantity,Price,Fee,Notes\r\n"
    private val csvKept =
        csvHeader +
            "2027-01-01T10:00:00Z,deposit,USD,1000,,,Opening cash\r\n" +
            "2027-01-02T10:00:00Z,buy,BTC,0.02,41000,,CSV purchase\r\n"
    private val csvUndone = csvHeader + "2027-01-03T10:00:00Z,sell,BTC,0.01,42000,,CSV sale\r\n"

    private fun password() = "restore-test-password".toCharArray()

    private fun csvOptions() =
        CsvImportOptions(walletB, usd, CsvFeeDefault.CURRENCY, { CsvImport.utcMillis(it) }, mapOf("BTC" to BITCOIN_ASSET))

    /** Answers the markets request with one Bitcoin and one Ethereum listing; the trending request gets no coins. */
    private fun market(
        btcPrice: String,
        btcChange24h: String,
        ethPrice: String,
    ): (String, String?) -> String {
        val markets =
            """
            [
              {"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":$btcPrice,"price_change_percentage_24h_in_currency":$btcChange24h},
              {"id":"ethereum","symbol":"eth","name":"Ethereum","current_price":$ethPrice}
            ]
            """.trimIndent()
        return { url, _ -> if (url.contains("/coins/markets")) markets else """{"coins":[]}""" }
    }

    private fun refresh(
        app: PortfolioApplication,
        btcPrice: String,
        btcChange24h: String,
        ethPrice: String,
    ) {
        assertNull(app.refreshMarketFeeds(market(btcPrice, btcChange24h, ethPrice), maxAgeMillis = 0L).failure)
    }

    /** Decrypts an archive to its plain body, using the same streaming read as BackupTest. */
    private fun plainBody(archive: ByteArray): ByteArray {
        val source = BackupEncryption.decrypt(ByteArraySource(archive), password())
        val out = ByteArraySink()
        val buffer = ByteArray(1000)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /**
     * Builds all user state: ownership, manual and CSV activity, reviewed links and duplicates, overlays, rules, price
     * alerts, notification history, CSV receipts, manual prices and every preference. The clock ends at [T1].
     */
    private fun populate(
        db: JvmSqliteDatabase,
        clock: TestClock,
    ): PortfolioApplication {
        clock.millis = T0
        val app = PortfolioApplication(db) { clock.millis }
        app.saveOwner(Owner(owner, "Alice"))
        app.saveAccount(Account(walletA, owner, "Wallet A", AccountKind.WALLET))
        app.saveAccount(Account(walletB, owner, "Wallet B", AccountKind.WALLET))
        app.savePortfolio(Portfolio(PortfolioId("main"), "Main", "USD"))

        app.saveTransaction(
            TransactionDraft(
                id = ActivityId("tx-buy"),
                type = TransactionType.BUY,
                asset = BITCOIN_ASSET,
                account = walletA,
                quantity = ExactDecimal.parse("0.5"),
                atMillis = T0 - 10 * DAY,
                quote = usd,
                unitPrice = ExactDecimal.parse("40000"),
                notes = "Exchange purchase",
            ),
        )
        // Withdrawal and deposit with the same quantity, opposite sides, and different accounts: a transfer link candidate.
        app.saveTransaction(
            TransactionDraft(
                id = ActivityId("tx-withdraw"),
                type = TransactionType.WITHDRAWAL,
                asset = BITCOIN_ASSET,
                account = walletA,
                quantity = ExactDecimal.parse("0.1"),
                atMillis = T0 - 6 * DAY,
                quote = usd,
                notes = "Sent to cold storage",
            ),
        )
        app.saveTransaction(
            TransactionDraft(
                id = ActivityId("tx-receive"),
                type = TransactionType.DEPOSIT,
                asset = BITCOIN_ASSET,
                account = walletB,
                quantity = ExactDecimal.parse("0.1"),
                atMillis = T0 - 6 * DAY,
                quote = usd,
            ),
        )
        app.saveTransaction(
            TransactionDraft(
                id = ActivityId("tx-transfer"),
                type = TransactionType.TRANSFER,
                asset = BITCOIN_ASSET,
                account = walletA,
                quantity = ExactDecimal.parse("0.05"),
                atMillis = T0 - 5 * DAY,
                quote = usd,
                destination = walletB,
                notes = "Moved between wallets",
            ),
        )
        // Two identical deposits: a duplicate pair, where the second is suppressed by the accepted decision.
        for (id in listOf("tx-dup-a", "tx-dup-b")) {
            app.saveTransaction(
                TransactionDraft(
                    id = ActivityId(id),
                    type = TransactionType.DEPOSIT,
                    asset = BITCOIN_ASSET,
                    account = walletB,
                    quantity = ExactDecimal.parse("0.01"),
                    atMillis = T0 - 3 * DAY,
                    quote = usd,
                    notes = id,
                ),
            )
        }
        app.saveTransaction(
            TransactionDraft(
                id = ActivityId("tx-reversed"),
                type = TransactionType.BUY,
                asset = BITCOIN_ASSET,
                account = walletA,
                quantity = ExactDecimal.parse("0.01"),
                atMillis = T0 - 4 * DAY,
                quote = usd,
                unitPrice = ExactDecimal.parse("42000"),
                notes = "Entered by mistake",
            ),
        )
        app.reverseManualTransaction(ActivityId("tx-reversed"))

        // A label and note with a corrected quantity, and a classification kind override on a different entry.
        app.saveOverlay(
            ActivityOverlay(
                activityId = ActivityId("tx-buy"),
                label = "Exchange buy",
                note = "Quantity corrected from statement",
                amounts = mapOf(EventId("tx-buy-asset") to AtomicAmount(BITCOIN_ASSET, ExactInteger.parse("49000000"))),
            ),
        )
        app.saveOverlay(ActivityOverlay(activityId = ActivityId("tx-receive"), kind = ActivityKind.OTHER, note = "Reviewed by hand"))

        // Rules are stored as revisions: saving an existing ID again adds one. rules() reads the latest; the backup keeps all.
        app.saveRule(ClassificationRule("rule-bitcoin-deposits", 5, ActivityKind.TRANSFER, ActivityKind.DEPOSIT, BITCOIN_ASSET))
        app.saveRule(ClassificationRule("rule-bitcoin-deposits", 7, ActivityKind.TRANSFER, ActivityKind.DEPOSIT, BITCOIN_ASSET))
        app.saveRule(ClassificationRule("rule-disabled-other", 1, ActivityKind.OTHER, enabled = false))

        app.setDecision(ActivityId("tx-withdraw"), ActivityId("tx-receive"), LinkState.ACCEPTED)
        app.setDuplicateDecision(ActivityId("tx-dup-a"), ActivityId("tx-dup-b"), ActivityId("tx-dup-a"), LinkState.ACCEPTED)

        app.recordManualPrice(
            PriceObservation("manual-btc-usd", Price(BITCOIN_ASSET, usd, ExactDecimal.parse("90500")), null, T0 - DAY, T0 - DAY, true),
        )

        // Two imports: the second is undone, so its entry is kept as a reversed entry.
        app.importCsv(app.previewCsvImport("statement.csv", csvKept, csvOptions()))
        val undone = app.importCsv(app.previewCsvImport("statement-undone.csv", csvUndone, csvOptions()))
        assertEquals(1, app.reverseCsvImport(undone.id))

        app.setMarketDataConsent(true)
        refresh(app, "90000", "1.0", "3000")
        app.savePriceAlert("bitcoin", AlertCondition.ABOVE, ExactDecimal.parse("100000"))
        app.savePriceAlert("ethereum", AlertCondition.MOVE, ExactDecimal.parse("5"))
        val below = app.savePriceAlert("bitcoin", AlertCondition.BELOW, ExactDecimal.parse("50000"))
        app.setPriceAlertEnabled(below.id, false)

        app.setLandingPage(LandingPage.INSIGHTS)
        app.setSecondaryCurrency(eur)
        app.setShowPortfolioChart(false)
        app.setZeroBasisRewards(true)
        app.setDeviceName("Anton's Pixel")
        app.setHideAmounts(true)
        app.setCandleCharts(true)
        app.setAppearanceTheme("light")
        app.setMarketFavorites(setOf(BITCOIN_ASSET, AssetId("coingecko:ethereum")))
        app.setOrderBookConsent(true)
        app.setAssetListPreference(AssetListPreference(AssetSort.NAME, hideEmpty = true, hideSmall = true))
        app.setRefreshPreferences(automatic = false, wifiOnly = true)
        app.setSourceConsent(true)
        app.setNotificationPreferences(NotificationPreferences(dailyUpdate = true, priceMoves = true, highsAndLows = false))

        // The first snapshot after the alerts were set: the ABOVE and MOVE alerts fire, and so do the price move and daily update.
        // The daily update needs a fresh end value, and the market feed does not supply valuations, so a manual quote is saved at T1.
        clock.millis = T1
        app.recordManualPrice(
            PriceObservation("manual-btc-usd-t1", Price(BITCOIN_ASSET, usd, ExactDecimal.parse("110000")), null, T1, T1, true),
        )
        refresh(app, "110000", "12.5", "3300")
        val delivered = app.evaluateNotifications(LOCAL_DAY, 10)
        assertEquals(2, delivered.count { it.kind == NotificationKind.PRICE_ALERT })
        assertEquals(1, delivered.count { it.kind == NotificationKind.PRICE_MOVE })
        assertEquals(1, delivered.count { it.kind == NotificationKind.DAILY_UPDATE })
        return app
    }

    @Test
    fun everyUserRecordSurvivesEncryptedRestore() {
        val clock = TestClock(T0)
        JvmSqliteDatabase(":memory:").use { source ->
            val app = populate(source, clock)
            // Fixture check: each record kind is present before export, so an empty restore cannot pass.
            assertEquals(3, app.priceAlerts().size)
            assertEquals(2, app.priceAlerts().count { it.enabled })
            assertEquals(2, app.csvImportReceipts().size)
            assertEquals(1, app.csvImportReceipts().count { it.reversedAtMillis != null })
            assertEquals(2, app.rules().size)
            assertEquals(1, OverlayRepository(source).replayForLedger(ActivityRepository(source).current()).transferLinks.size)

            val archive = app.exportBackup(password())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, archive, password())
                val restored = PortfolioApplication(staging) { clock.millis }

                assertEquals(app.snapshot(), restored.snapshot())

                fun balances(snapshot: PortfolioSnapshot) = snapshot.valuations.map { it.asset to it.amount.atomicUnits }
                assertEquals(balances(app.snapshot()), balances(restored.snapshot()))
                // The Bitcoin value uses the manual quote saved at T1, which the restored database must still hold.
                assertEquals(
                    "manual-btc-usd-t1",
                    restored
                        .snapshot()
                        .valuations
                        .single { it.asset == BITCOIN_ASSET }
                        .price
                        ?.id,
                )

                // Ledger and reviewed decisions: the transfer link and duplicate suppression are part of the effective ledger.
                val sourceLedger = OverlayRepository(source).replayForLedger(ActivityRepository(source).current())
                val restoredLedger = OverlayRepository(staging).replayForLedger(ActivityRepository(staging).current())
                assertEquals(sourceLedger, restoredLedger)
                assertEquals(1, restoredLedger.suppressed.size)

                val entries = app.transactionEntries(ActivityFilter())
                assertEquals(entries, restored.transactionEntries(ActivityFilter()))
                val byId = restored.transactionEntries(ActivityFilter()).associateBy { it.activity.id.value }
                assertEquals("Exchange buy", byId.getValue("tx-buy").label)
                assertEquals("Quantity corrected from statement", byId.getValue("tx-buy").note)
                assertEquals(
                    AtomicAmount(BITCOIN_ASSET, ExactInteger.parse("49000000")),
                    byId
                        .getValue("tx-buy")
                        .activity.movements
                        .single { it.id == EventId("tx-buy-asset") }
                        .amount,
                )
                assertEquals(ActivityStatus.REVERSED, byId.getValue("tx-reversed").activity.status)
                assertEquals(ActivityKind.OTHER, byId.getValue("tx-receive").activity.kind)
                assertEquals(ClassificationOrigin.USER, byId.getValue("tx-receive").classificationOrigin)
                assertEquals(ActivityKind.TRANSFER, byId.getValue("tx-dup-a").activity.kind)
                assertEquals(ClassificationOrigin.RULE, byId.getValue("tx-dup-a").classificationOrigin)
                assertNull(byId["tx-dup-b"])

                assertEquals(app.rules(), restored.rules())
                assertEquals(7, restored.rules().single { it.id == "rule-bitcoin-deposits" }.priority)
                assertEquals(app.overlay(ActivityId("tx-buy")), restored.overlay(ActivityId("tx-buy")))
                assertEquals(app.overlay(ActivityId("tx-receive")), restored.overlay(ActivityId("tx-receive")))

                assertEquals(app.priceAlerts(), restored.priceAlerts())
                assertEquals(app.notificationPreferences(), restored.notificationPreferences())
                assertEquals(app.notificationHistory(), restored.notificationHistory())
                assertTrue(restored.notificationHistory().any { it.kind == NotificationKind.PRICE_MOVE })

                assertEquals(app.csvImportReceipts(), restored.csvImportReceipts())
                for (receipt in app.csvImportReceipts()) {
                    assertEquals(app.csvImportRows(receipt.id), restored.csvImportRows(receipt.id))
                }

                val expectedPreferences =
                    GeneralPreferences(
                        landing = LandingPage.INSIGHTS,
                        secondaryCurrency = eur,
                        showPortfolioChart = false,
                        zeroBasisRewards = true,
                        deviceName = "Anton's Pixel",
                        hideAmounts = true,
                        candleCharts = true,
                    )
                assertEquals(expectedPreferences, restored.generalPreferences())
                assertEquals(app.generalPreferences(), restored.generalPreferences())
                assertEquals("light", restored.appearanceTheme())
                assertEquals(app.marketFavorites(), restored.marketFavorites())
                assertEquals(setOf(BITCOIN_ASSET, AssetId("coingecko:ethereum")), restored.marketFavorites())
                assertTrue(restored.orderBookConsent())
                assertTrue(restored.sourceConsent())
                assertTrue(restored.marketDataConsent())
                assertEquals(AssetListPreference(AssetSort.NAME, hideEmpty = true, hideSmall = true), restored.assetListPreference())
                assertEquals(false, restored.automaticRefreshPreference())
                assertTrue(restored.wifiOnlyRefreshPreference())

                assertEquals(app.exportCsv(), restored.exportCsv())
                assertEquals(app.accountingReport(), restored.accountingReport())
                assertEquals(app.snapshot(), restored.snapshot())
            }
        }
    }

    @Test
    fun restoredAlertsDoNotRedeliver() {
        val clock = TestClock(T0)
        JvmSqliteDatabase(":memory:").use { source ->
            val app = populate(source, clock)
            val alerts = app.priceAlerts()
            val history = app.notificationHistory()
            val above = alerts.single { it.condition == AlertCondition.ABOVE }
            val archive = app.exportBackup(password())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, archive, password())
                val restoredClock = TestClock(T1)
                val restored = PortfolioApplication(staging) { restoredClock.millis }
                assertEquals(alerts, restored.priceAlerts())
                assertEquals(history, restored.notificationHistory())

                // The same snapshot at the same clock time. Each enabled alert's lastObservedMillis equals this observedAt, and
                // AlertRules.step ignores a snapshot that is not newer than lastObservedMillis. The history is a second guard:
                // a price alert's key is its ID plus observedAt, and the price move and daily update keys are already recorded,
                // so NotificationRepository.record refuses them.
                refresh(restored, "110000", "12.5", "3300")
                assertEquals(emptyList(), restored.evaluateNotifications(LOCAL_DAY, 10))
                assertEquals(alerts, restored.priceAlerts())
                assertEquals(history, restored.notificationHistory())

                // A later snapshot where the ABOVE alert still holds: it fired and is disarmed, so the crossing is not repeated.
                restoredClock.millis = T1 + 2 * HOUR
                refresh(restored, "110000", "12.5", "3300")
                assertEquals(emptyList(), restored.evaluateNotifications(LOCAL_DAY, 10))

                // The restored alert still works: a fall below the threshold re-arms it, and the next crossing fires once.
                restoredClock.millis = T1 + 3 * HOUR
                refresh(restored, "95000", "-3.0", "3300")
                assertEquals(emptyList(), restored.evaluateNotifications(LOCAL_DAY, 10))
                restoredClock.millis = T1 + 5 * HOUR
                refresh(restored, "105000", "12.5", "3300")
                val fired = restored.evaluateNotifications(LOCAL_DAY, 10)
                assertEquals(listOf(NotificationKind.PRICE_ALERT), fired.map { it.kind })
                assertEquals(above.id, fired.single().alertId)
                assertEquals("alert:${above.id}:${T1 + 5 * HOUR}", fired.single().key)
            }
        }
    }

    @Test
    fun secondExportOfARestoredDatabaseIsIdentical() {
        val clock = TestClock(T0)
        JvmSqliteDatabase(":memory:").use { source ->
            val app = populate(source, clock)
            val firstArchive = app.exportBackup(password())
            val firstBody = plainBody(firstArchive)
            assertTrue(firstBody.decodeToString().contains("Quantity corrected from statement"))
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, firstArchive, password())
                val second = PortfolioApplication(staging) { clock.millis }.exportBackup(password())
                assertContentEquals(firstBody, plainBody(second))
            }
        }
    }
}
