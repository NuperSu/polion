package io.polion.core.application

import io.polion.core.market.CoingeckoProviderException
import io.polion.core.market.CoingeckoProviderFailure
import io.polion.core.market.KrakenRateLimitedException
import io.polion.core.model.PortfolioId
import io.polion.core.ownership.Portfolio
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomaticPriceWorkflowTest {
    @Test fun rateLimitStopsTheWholePriceBatchAndPreservesSavedData() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.setAutomaticPriceConsent(true)
            val current = now / hour * hour
            app.refreshAutomaticPrices({
                """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, "40000")},${candle(current, "41000")}],"last":0}}"""
            })
            val saved = db.query("SELECT id,value,valid_at FROM market_prices ORDER BY id")
            var requests = 0
            assertFailsWith<KrakenRateLimitedException> {
                app.refreshAutomaticPriceSet(transport = {
                    requests++
                    throw KrakenRateLimitedException()
                })
            }
            assertEquals(1, requests)
            assertEquals(saved, db.query("SELECT id,value,valid_at FROM market_prices ORDER BY id"))
            app.recordAutomaticPriceStatus(false, rateLimited = true)
            assertEquals(
                "Kraken is rate limiting requests; wait a minute before retrying. Saved prices remain available",
                app.automaticPriceStatus(),
            )
        }
    }

    @Test fun restoreInvalidationCancelsInFlightRequestWithoutChangingConsentScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.setAutomaticPriceConsent(true)
            val current = now / hour * hour
            assertFailsWith<CoingeckoProviderException> {
                app.refreshAutomaticPrices({
                    app.invalidateAutomaticPriceRequests()
                    """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, "40000")},${candle(current, "41000")}],"last":0}}"""
                })
            }
            assertEquals(true, app.automaticPriceConsent())
            assertEquals(true, app.automaticPortfolioPriceConsent())
            assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
        }
    }

    @Test fun revokedConsentStopsRemainingCurrencyRequests() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.setAutomaticPriceConsent(true)
            var requests = 0
            val current = now / hour * hour
            assertFailsWith<CoingeckoProviderException> {
                app.refreshAutomaticPriceSet({
                    requests++
                    app.setAutomaticPriceConsent(false)
                    """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, "40000")},${candle(current, "41000")}],"last":0}}"""
                })
            }
            assertEquals(1, requests)
            assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
        }
    }

    @Test fun refreshCoversPortfolioCurrenciesAndContinuesAfterOneMarketFails() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.setAutomaticPriceConsent(true)
            db.execute("DELETE FROM application_settings WHERE key='automatic_price_scope'")
            assertEquals(
                listOf(
                    io.polion.core.model
                        .AssetId("USD"),
                ),
                app.automaticPriceCurrencies(),
            )
            app.setAutomaticPortfolioPriceConsent(true)
            assertEquals(
                setOf(
                    io.polion.core.model
                        .AssetId("USD"),
                    io.polion.core.model
                        .AssetId("EUR"),
                ),
                app.automaticPriceCurrencies().toSet(),
            )
            val urls = mutableListOf<String>()
            val transport: (String) -> String = { url ->
                urls += url
                if (url.contains("XBTUSD") && url.endsWith("interval=60")) error("fixture failure")
                val interval = if (url.endsWith("interval=1440")) 86_400_000L else hour
                val current = now / interval * interval
                val pair = if (url.contains("XBTEUR")) "XXBTZEUR" else "XXBTZUSD"
                """{"error":[],"result":{"$pair":[${candle(current - interval, "40000")},${candle(current, "41000")}],"last":0}}"""
            }
            assertFailsWith<IllegalStateException> { app.refreshAutomaticPriceSet(transport) }
            assertEquals(4, urls.size)
            assertEquals(setOf("USD", "EUR"), db.query("SELECT DISTINCT quote_asset FROM market_prices").map { it.single() }.toSet())
            assertEquals("1", db.query("SELECT COUNT(*) FROM market_prices WHERE quote_asset='USD'").single().single())
        }
    }

    private val hour = 3_600_000L
    private val now = 1_700_002_800_000L / hour * hour + 100_000

    private fun candle(
        start: Long,
        price: String,
    ) = "[${start / 1000},\"1\",\"2\",\"1\",\"$price\",\"1\",\"1\",1]"

    @Test fun repeatedHourlyFetchDoesNotDuplicateButPreservesRevisionsAndReversions() {
        JvmSqliteDatabase(":memory:").use { db ->
            var clock = now
            var price = "40000"
            val current = now / hour * hour
            val app = PortfolioApplication(db) { clock }
            app.setAutomaticPriceConsent(true)
            val transport: (String) -> String = {
                """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, price)},${candle(current, "1")}],"last":0}}"""
            }
            app.refreshAutomaticPrices(transport)
            clock++
            app.refreshAutomaticPrices(transport)
            assertEquals("1", db.query("SELECT COUNT(*) FROM market_prices").single().single())
            price = "41000"
            clock++
            app.refreshAutomaticPrices(transport)
            price = "40000"
            clock++
            app.refreshAutomaticPrices(transport)
            assertEquals(
                listOf(listOf("40000"), listOf("41000"), listOf("40000")),
                db.query("SELECT value FROM market_prices ORDER BY observed_at"),
            )
        }
    }

    @Test fun dailyHistoryIsFetchedOncePerUtcDayAndKeepsClosedPrices() {
        JvmSqliteDatabase(":memory:").use { db ->
            var clock = now
            val day = 86_400_000L
            val app = PortfolioApplication(db) { clock }
            app.setAutomaticPriceConsent(true)
            var requests = 0
            val transport: (String) -> String = { url ->
                requests++
                assertEquals("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=1440", url)
                val current = clock / day * day
                """{"error":[],"result":{"XXBTZUSD":[${candle(
                    current - 2 * day,
                    "39000",
                )},${candle(current - day, "40000")},${candle(current, "99999")}],"last":0}}"""
            }
            assertEquals(2, app.refreshAutomaticDailyPricesIfDue(transport))
            assertEquals(0, app.refreshAutomaticDailyPricesIfDue(transport))
            assertEquals(1, requests)
            val values = db.query("SELECT value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf(listOf("39000"), listOf("40000")), values)
            clock += day
            assertEquals(2, app.refreshAutomaticDailyPricesIfDue(transport))
            assertEquals(2, requests)
        }
    }

    @Test fun fetchRequiresConsentStoresClosedHoursAndRollsBackOnCancellation() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            val current = now / hour * hour
            val payload = """{"error":[],"result":{"XXBTZUSD":[${candle(
                current - 2 * hour,
                "40000.10",
            )},${candle(current - hour, "41000.20")},${candle(current, "99999")}],"last":0}}"""
            var requests = 0
            val transport: (String) -> String = { url ->
                requests++
                assertEquals("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=60", url)
                payload
            }
            assertFailsWith<IllegalArgumentException> { app.refreshAutomaticPrices(transport) }
            assertEquals(0, requests)
            app.setAutomaticPriceConsent(true)
            assertEquals(2, app.refreshAutomaticPrices(transport))
            val stored = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf((current - hour).toString(), "40000.10"), stored[0])
            assertEquals(listOf(current.toString(), "41000.20"), stored[1])
            var calls = 0
            assertFailsWith<CoingeckoProviderException> {
                app.refreshAutomaticPrices(transport) {
                    if (++calls ==
                        3
                    ) {
                        throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                    }
                }
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
            assertFailsWith<IllegalArgumentException> {
                app.refreshAutomaticPrices(transport = { """{"error":["EGeneral:Invalid arguments"],"result":{}}""" })
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
        }
    }
}
