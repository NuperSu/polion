package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.CoingeckoProviderException
import io.valroot.core.market.CoingeckoProviderFailure
import io.valroot.core.market.CoingeckoTransport
import io.valroot.core.market.PriceObservation
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoricalPriceWorkflowTest {
    @Test fun applicationUsesMostRecentProviderPriceForCurrentAndHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            val clock = 1_710_000_000_000L
            val app = PortfolioApplication(db) { clock }
            app.seedDemo()
            app.useProviderPrices(btc, usd)
            val market =
                io.valroot.core.market
                    .MarketRepository(db)
            market.recordPrice(
                PriceObservation(
                    "kraken-old",
                    Price(btc, usd, ExactDecimal.parse("40000")),
                    io.valroot.core.model
                        .ProviderId("kraken"),
                    clock - 3_600_000,
                    clock,
                ),
            )
            market.recordPrice(
                PriceObservation(
                    "gecko-new",
                    Price(btc, usd, ExactDecimal.parse("41000")),
                    io.valroot.core.model
                        .ProviderId("coingecko"),
                    clock - 1_800_000,
                    clock,
                ),
            )
            assertEquals(
                "gecko-new",
                app
                    .snapshot()
                    .valuations
                    .single()
                    .price
                    ?.id,
            )
            assertEquals(
                "gecko-new",
                app
                    .historySeries(listOf(clock))
                    .single()
                    .valuations
                    .single()
                    .price
                    ?.id,
            )
        }
    }

    @Test fun currentValuationRequiresRecentQuoteWhileHistoricalDailyPricesRemainUsable() {
        JvmSqliteDatabase(":memory:").use { db ->
            var clock = 1_710_000_000_000L
            val app = PortfolioApplication(db) { clock }
            app.seedDemo()
            assertEquals(
                PriceStatus.FRESH,
                app
                    .snapshot()
                    .valuations
                    .single()
                    .priceStatus,
            )
            clock += 2 * 3_600_000L + 1
            assertEquals(
                PriceStatus.STALE,
                app
                    .snapshot()
                    .valuations
                    .single()
                    .priceStatus,
            )
            // The same quote at the same instant has one status on every view.
            assertEquals(PriceStatus.STALE, app.history(clock).single().priceStatus)
            assertEquals(
                PriceStatus.STALE,
                app
                    .historySeries(listOf(clock))
                    .single()
                    .valuations
                    .single()
                    .priceStatus,
            )
            assertTrue(
                app
                    .accountingReport()
                    .report.valuations
                    .all { it.value == null },
            )
        }
    }

    @Test fun rangeImportStoresIntradayPricesExactlyAndRejectsInvalidOrCancelledBatches() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.setPriceSourceConsent(true)
            val payload = """{"prices":[[$day,42000.125],[${day + 3_600_000},4.25e4]]}"""
            val count =
                app.fetchPriceHistory({ url ->
                    assertEquals("https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=90", url)
                    payload
                }, btc, usd, 90)
            assertEquals(2, count)
            val stored = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf(day.toString(), "42000.125"), stored[0])
            assertEquals(listOf((day + 3_600_000).toString(), "42500"), stored[1])
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            app.saveManualActivity(
                Activity(
                    ActivityId("deposit"),
                    day - 1,
                    "deposit",
                    ActivityKind.DEPOSIT,
                    ActivityStatus.CONFIRMED,
                    listOf(
                        Movement(EventId("deposit"), account, AtomicAmount(btc, ExactInteger.parse("100000000")), MovementRole.TRANSFER),
                    ),
                    emptySet(),
                    null,
                    true,
                ),
            )
            val history = app.historySeries(listOf(day, day + 3_600_000))
            assertEquals(ExactDecimal.parse("42000.12500000000"), history[0].valuations.single().value)
            assertEquals(ExactDecimal.parse("42500.00000000"), history[1].valuations.single().value)
            assertFailsWith<CoingeckoProviderException> {
                app.fetchPriceHistory({ """{"prices":[[$day,5],[$day,6]]}""" }, btc, usd, 90)
            }
            var checks = 0
            assertFailsWith<CoingeckoProviderException> {
                app.fetchPriceHistory({ payload }, btc, usd, 90) {
                    if (++checks == 3) throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                }
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
        }
    }

    private val day = 1_709_164_800_000L
    private val now = day + 43_200_000L
    private val btc = AssetId("bitcoin:btc")
    private val usd = AssetId("USD")
    private val response = """{"market_data":{"current_price":{"usd":42000.123456789,"eur":39000,"gbp":33000}}}"""

    @Test fun consentAndInputValidationPrecedeNetworkRequests() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            var requests = 0
            val transport =
                CoingeckoTransport {
                    requests++
                    response
                }
            assertFalse(app.priceSourceConsent())
            app.setSourceConsent(true)
            assertFailsWith<IllegalArgumentException> { app.fetchHistoricalPrice(transport, btc, usd, day) }
            app.setPriceSourceConsent(true)
            app.setSourceConsent(false)
            assertFailsWith<IllegalArgumentException> { app.fetchHistoricalPrice(transport, btc, usd, day + 1) }
            assertFailsWith<IllegalArgumentException> { app.fetchHistoricalPrice(transport, btc, usd, day + 86_400_000L) }
            assertFailsWith<IllegalArgumentException> { app.fetchHistoricalPrice(transport, AssetId("unmapped"), usd, day) }
            assertFailsWith<IllegalArgumentException> { app.fetchHistoricalPrice(transport, btc, usd, day - 366L * 86_400_000L) }
            assertEquals(0, requests)
            val result = app.fetchHistoricalPrice(transport, btc, AssetId("EUR"), day)
            assertEquals(ExactDecimal.parse("39000"), result.observation!!.price.value)
            assertEquals(1, requests)
        }
    }

    @Test fun savedProviderQuoteValuesHoldingsAndOpenLotsOfflineAndManualOverridesWin() {
        val file = Files.createTempFile("valroot-historical-price", ".sqlite")
        val recentNow = day + 3_600_000L
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { recentNow }
                val owner = OwnerId("owner")
                val account = AccountId("account")
                val event = EventId("buy")
                app.saveOwner(Owner(owner, "Owner"))
                app.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
                app.saveManualActivity(
                    Activity(
                        ActivityId("buy"),
                        day - 1,
                        "purchase",
                        ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(event, account, AtomicAmount(btc, ExactInteger.parse("100000000")), MovementRole.TRANSFER)),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                app.saveTreatments(ActivityId("buy"), listOf(Treatment.Acquire(event, QuoteValue(usd, Rational.of("40000")))))
                app.setPriceSourceConsent(true)
                val result =
                    app.fetchHistoricalPrice({ url ->
                        assertEquals("https://api.coingecko.com/api/v3/coins/bitcoin/history?date=29-02-2024&localization=false", url)
                        response
                    }, btc, usd, day)
                assertEquals(PriceStatus.FRESH, result.status)
                assertEquals(
                    ExactDecimal.parse("42000.12345678900000000"),
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .value,
                )
                val valuation =
                    app
                        .accountingReport()
                        .report.valuations
                        .single()
                assertEquals(Rational.fromDecimal(ExactDecimal.parse("42000.123456789")), valuation.value)
                assertEquals(Rational.fromDecimal(ExactDecimal.parse("2000.123456789")), valuation.unrealizedGain)
                app.setPriceSourceConsent(false)
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { recentNow }
                assertFalse(app.priceSourceConsent())
                assertEquals(
                    ExactDecimal.parse("42000.12345678900000000"),
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .value,
                )
                assertTrue(
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .price!!
                        .providerId != null,
                )
                app.recordManualPrice(PriceObservation("manual", Price(btc, usd, ExactDecimal.parse("43000")), null, day, recentNow, true))
                assertEquals(
                    ExactDecimal.parse("43000.00000000"),
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .value,
                )
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun erasureAndConsentReplacementCancelInFlightObservations() {
        for (wipe in listOf(false, true)) {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = PortfolioApplication(db) { now }
                app.setPriceSourceConsent(true)
                val failure =
                    assertFailsWith<CoingeckoProviderException> {
                        app.fetchHistoricalPrice({
                            if (wipe) app.wipe() else app.setPriceSourceConsent(false)
                            app.setPriceSourceConsent(true)
                            response
                        }, btc, usd, day)
                    }
                assertEquals(CoingeckoProviderFailure.CANCELLED, failure.failure)
                assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
                if (wipe) assertEquals("0", db.query("SELECT COUNT(*) FROM market_assets").single().single())
            }
        }
    }

    @Test fun hostInvalidationCancelsAtCommitAndObservedTimeIsResponseCompletion() {
        JvmSqliteDatabase(":memory:").use { db ->
            var clock = now
            val app = PortfolioApplication(db) { clock }
            app.setPriceSourceConsent(true)
            var generation = 0
            val captured = generation
            assertFailsWith<CoingeckoProviderException> {
                app.fetchHistoricalPrice({
                    generation++
                    response
                }, btc, usd, day, authorize = {
                    if (generation != captured) throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                })
            }
            assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
            val first =
                app.fetchHistoricalPrice({
                    clock += 1000
                    response
                }, btc, usd, day)
            assertEquals(now + 1000, first.observation!!.observedAtMillis)
            val second = app.fetchHistoricalPrice({ response }, btc, usd, day)
            assertTrue(first.observation.id != second.observation!!.id)
            assertEquals("2", db.query("SELECT COUNT(*) FROM market_prices").single().single())
        }
    }

    @Test fun missingInvalidAndRateLimitedResultsDoNotRecordPrices() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.setPriceSourceConsent(true)
            assertEquals(PriceStatus.MISSING, app.fetchHistoricalPrice({ "{}" }, btc, usd, day).status)
            assertEquals(
                CoingeckoProviderFailure.INVALID_RESPONSE,
                assertFailsWith<CoingeckoProviderException> {
                    app.fetchHistoricalPrice({ "not JSON" }, btc, usd, day)
                }.failure,
            )
            assertEquals(
                CoingeckoProviderFailure.RATE_LIMITED,
                assertFailsWith<CoingeckoProviderException> {
                    app.fetchHistoricalPrice({ throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED) }, btc, usd, day)
                }.failure,
            )
            assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
        }
    }
}
