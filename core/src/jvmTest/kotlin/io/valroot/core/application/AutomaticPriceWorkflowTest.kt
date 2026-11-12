package io.valroot.core.application

import io.valroot.core.market.CoingeckoProviderException
import io.valroot.core.market.CoingeckoProviderFailure
import io.valroot.core.market.KrakenRateLimitedException
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.Portfolio
import io.valroot.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AutomaticPriceWorkflowTest {
    @Test fun cooldownSurvivesDatabaseCloseAndReopen() {
        val path = Files.createTempFile("price-cooldown", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val app = PortfolioApplication(db) { now }
                app.prices.setAutomaticConsent(true)
                assertFailsWith<KrakenRateLimitedException> {
                    app.prices.refreshAutomatic({ throw KrakenRateLimitedException(120_000) })
                }
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                var clock = now + 60_000
                val app = PortfolioApplication(db) { clock }
                assertFailsWith<KrakenRateLimitedException> { app.prices.refreshAutomatic({ error("unexpected request") }) }
                clock = now + 120_000
                var requested = false
                assertFailsWith<IllegalArgumentException> {
                    app.prices.refreshAutomatic({
                        requested = true
                        "invalid"
                    })
                }
                assertEquals(true, requested)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun rateLimitStopsTheWholePriceBatchAndPreservesSavedData() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.accounts.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.prices.setAutomaticConsent(true)
            val current = now / hour * hour
            app.prices.refreshAutomatic({
                """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, "40000")},${candle(current, "41000")}],"last":0}}"""
            })
            val saved = db.query("SELECT id,value,valid_at FROM market_prices ORDER BY id")
            var requests = 0
            assertFailsWith<KrakenRateLimitedException> {
                app.prices.refreshAutomaticSet(transport = {
                    requests++
                    throw KrakenRateLimitedException(120_000)
                })
            }
            assertEquals(1, requests)
            assertEquals(saved, db.query("SELECT id,value,valid_at FROM market_prices ORDER BY id"))
            app.prices.recordAutomaticStatus(false, rateLimited = true)
            assertEquals(
                "Kraken is rate limiting requests; retry after ${kotlin.time.Instant.fromEpochMilliseconds(
                    now + 120_000,
                )}. Saved prices remain available",
                app.prices.automaticStatus(),
            )
            var clock = now + 60_000
            val reopened = PortfolioApplication(db) { clock }
            assertFailsWith<KrakenRateLimitedException> { reopened.prices.refreshAutomatic({ error("cooldown must prevent requests") }) }
            assertEquals(
                (now + 120_000).toString(),
                db.query("SELECT value FROM application_settings WHERE key='kraken_retry_after'").single().single(),
            )
            clock = now + 120_000
            var retried = false
            assertFailsWith<IllegalArgumentException> {
                reopened.prices.refreshAutomatic({
                    retried = true
                    "invalid"
                })
            }
            assertEquals(true, retried)
        }
    }

    @Test fun restoreInvalidationCancelsInFlightRequestWithoutChangingConsentScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.prices.setAutomaticConsent(true)
            val current = now / hour * hour
            assertFailsWith<CoingeckoProviderException> {
                app.prices.refreshAutomatic({
                    app.prices.invalidateAutomaticRequests()
                    """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, "40000")},${candle(current, "41000")}],"last":0}}"""
                })
            }
            assertEquals(true, app.prices.automaticConsent())
            assertEquals(true, app.prices.automaticPortfolioConsent())
            assertEquals("0", db.query("SELECT COUNT(*) FROM market_prices").single().single())
        }
    }

    @Test fun revokedConsentStopsRemainingCurrencyRequests() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.accounts.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.prices.setAutomaticConsent(true)
            var requests = 0
            val current = now / hour * hour
            assertFailsWith<CoingeckoProviderException> {
                app.prices.refreshAutomaticSet({
                    requests++
                    app.prices.setAutomaticConsent(false)
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
            app.accounts.savePortfolio(Portfolio(PortfolioId("euro"), "Euro", "EUR"))
            app.prices.setAutomaticConsent(true)
            db.execute("DELETE FROM application_settings WHERE key='automatic_price_scope'")
            assertEquals(
                listOf(
                    io.valroot.core.model
                        .AssetId("USD"),
                ),
                app.prices.automaticCurrencies(),
            )
            app.prices.setAutomaticPortfolioConsent(true)
            assertEquals(
                setOf(
                    io.valroot.core.model
                        .AssetId("USD"),
                    io.valroot.core.model
                        .AssetId("EUR"),
                ),
                app.prices.automaticCurrencies().toSet(),
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
            assertFailsWith<IllegalStateException> { app.prices.refreshAutomaticSet(transport) }
            assertEquals(4, urls.size)
            assertEquals(setOf("USD", "EUR"), db.query("SELECT DISTINCT quote_asset FROM market_prices").map { it.single() }.toSet())
            assertEquals("1", db.query("SELECT COUNT(*) FROM market_prices WHERE quote_asset='USD'").single().single())
        }
    }

    @Test fun automaticRefreshSwitchSetsPreferenceAndPriceConsentTogether() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            assertEquals(null, app.settings.automaticRefresh())
            assertEquals(false, app.prices.automaticConsent())

            val before = app.prices.automaticGeneration()
            app.prices.setAutomaticRefresh(true)
            assertEquals(true, app.settings.automaticRefresh())
            assertEquals(true, app.prices.automaticConsent())
            assertEquals(true, app.prices.automaticPortfolioConsent())
            val enabledGeneration = app.prices.automaticGeneration()
            assertNotEquals(before, enabledGeneration)

            app.prices.setAutomaticRefresh(false)
            assertEquals(false, app.settings.automaticRefresh())
            assertEquals(false, app.prices.automaticConsent())
            assertNotEquals(enabledGeneration, app.prices.automaticGeneration())
        }
    }

    @Test fun everyFinishedPriceRefreshStartsTheCooldown() {
        JvmSqliteDatabase(":memory:").use { db ->
            var clock = now
            val app = PortfolioApplication(db) { clock }
            app.prices.setAutomaticRefresh(true)
            assertEquals(null, app.prices.automaticLastRunMillis())
            app.prices.recordAutomaticStatus(success = true)
            assertEquals(now, app.prices.automaticLastRunMillis())
            clock = now + 1000
            app.prices.recordAutomaticStatus(success = false)
            assertEquals(now + 1000, app.prices.automaticLastRunMillis())
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
            app.prices.setAutomaticConsent(true)
            val transport: (String) -> String = {
                """{"error":[],"result":{"XXBTZUSD":[${candle(current - hour, price)},${candle(current, "1")}],"last":0}}"""
            }
            app.prices.refreshAutomatic(transport)
            clock++
            app.prices.refreshAutomatic(transport)
            assertEquals("1", db.query("SELECT COUNT(*) FROM market_prices").single().single())
            price = "41000"
            clock++
            app.prices.refreshAutomatic(transport)
            price = "40000"
            clock++
            app.prices.refreshAutomatic(transport)
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
            app.prices.setAutomaticConsent(true)
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
            assertEquals(2, app.prices.refreshAutomaticDailyIfDue(transport))
            assertEquals(0, app.prices.refreshAutomaticDailyIfDue(transport))
            assertEquals(1, requests)
            val values = db.query("SELECT value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf(listOf("39000"), listOf("40000")), values)
            clock += day
            assertEquals(2, app.prices.refreshAutomaticDailyIfDue(transport))
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
            assertFailsWith<IllegalArgumentException> { app.prices.refreshAutomatic(transport) }
            assertEquals(0, requests)
            app.prices.setAutomaticConsent(true)
            assertEquals(2, app.prices.refreshAutomatic(transport))
            val stored = db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at")
            assertEquals(listOf((current - hour).toString(), "40000.10"), stored[0])
            assertEquals(listOf(current.toString(), "41000.20"), stored[1])
            var calls = 0
            assertFailsWith<CoingeckoProviderException> {
                app.prices.refreshAutomatic(transport) {
                    if (++calls ==
                        3
                    ) {
                        throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                    }
                }
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
            assertFailsWith<IllegalArgumentException> {
                app.prices.refreshAutomatic(transport = { """{"error":["EGeneral:Invalid arguments"],"result":{}}""" })
            }
            assertEquals(stored, db.query("SELECT valid_at,value FROM market_prices ORDER BY valid_at"))
        }
    }
}
