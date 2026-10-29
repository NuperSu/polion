package io.polion.core.application

import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.CoingeckoTransport
import io.polion.core.market.PriceObservation
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Price
import io.polion.core.model.Rational
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoricalPriceWorkflowTest {
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
        val file = Files.createTempFile("polion-historical-price", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { now }
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
                val app = PortfolioApplication(db) { now }
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
                app.recordManualPrice(PriceObservation("manual", Price(btc, usd, ExactDecimal.parse("43000")), null, day, now, true))
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
