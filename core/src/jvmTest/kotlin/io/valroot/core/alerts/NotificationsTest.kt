package io.valroot.core.alerts

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.CoingeckoMarkets
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsTest {
    private fun d(value: String) = ExactDecimal.parse(value)

    private fun alert(
        condition: AlertCondition,
        threshold: String,
    ) = PriceAlert("a", "bitcoin", "BTC", "usd", condition, d(threshold), true, true, null, null, null, null, 0)

    @Test fun aCrossingAlertsOnceAndReArmsOnlyAfterThePriceReturns() {
        var rule = AlertRules.start(alert(AlertCondition.ABOVE, "100"), d("90"), 1)
        assertTrue(rule.armed)

        fun step(
            price: String,
            observed: Long,
            now: Long = observed,
        ): Boolean = AlertRules.step(rule, d(price), observed, now).also { rule = it.alert }.fired
        assertFalse(step("99", 2))
        assertTrue(step("100", 3))
        // The same snapshot, and later snapshots still above, do not alert again.
        assertFalse(AlertRules.step(rule, d("150"), 3, 4).fired)
        assertFalse(step("120", 4))
        assertFalse(step("95", 5))
        // Back above within the hour: the cooldown holds it, and it fires once the hour has passed.
        assertFalse(step("105", 6, 3 + 1_000))
        assertTrue(rule.armed)
        assertTrue(step("106", 7, 3 + AlertRules.COOLDOWN_MILLIS))
    }

    @Test fun aConditionThatAlreadyHoldsWaitsForTheNextCrossing() {
        var rule = AlertRules.start(alert(AlertCondition.BELOW, "100"), d("90"), 1)
        assertFalse(rule.armed)
        assertFalse(AlertRules.step(rule, d("80"), 2, 2).also { rule = it.alert }.fired)
        assertFalse(AlertRules.step(rule, d("101"), 3, 3).also { rule = it.alert }.fired)
        assertTrue(AlertRules.step(rule, d("99"), 4, 4).fired)
    }

    @Test fun aMoveResetsItsReferenceAfterEachAlert() {
        var rule = AlertRules.start(alert(AlertCondition.MOVE, "10"), d("100"), 1)
        assertFalse(AlertRules.step(rule, d("109"), 2, 2).also { rule = it.alert }.fired)
        assertTrue(AlertRules.step(rule, d("89"), 3, 3).also { rule = it.alert }.fired)
        assertEquals(d("89"), rule.reference)
        assertFalse(AlertRules.step(rule, d("95"), 4, AlertRules.COOLDOWN_MILLIS + 4).also { rule = it.alert }.fired)
        assertTrue(AlertRules.step(rule, d("98"), 5, AlertRules.COOLDOWN_MILLIS + 5).fired)
        assertEquals("+2.5%", NotificationText.percent(2.46))
        assertEquals("70,120.50 USD", NotificationText.price(d("70120.5"), "usd"))
        assertEquals("0.00001234 EUR", NotificationText.price(d("0.000012341"), "eur"))
    }

    private val start = 1_800_000_000_000L

    private fun markets(
        btc: String,
        change: Double = 1.0,
        athAt: Long = 0,
    ) = """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","market_cap_rank":1,"current_price":$btc,
        |"price_change_percentage_24h_in_currency":$change,"ath":$btc,
        |"ath_date":"${kotlin.time.Instant.fromEpochMilliseconds(athAt)}"}]
        """.trimMargin()

    private fun refresh(
        app: PortfolioApplication,
        payload: String,
    ) = app.market.refreshFeeds({ url, _ -> if (url.contains("/coins/markets")) payload else """{"coins":[]}""" })

    @Test fun alertsAreDeliveredOncePerEventAcrossRestarts() {
        val file = Files.createTempFile("valroot-alerts", ".sqlite")
        var now = start
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { now }
                app.market.setConsent(true)
                assertFails { app.notifications.saveAlert("bitcoin", AlertCondition.ABOVE, d("100000")) }
                refresh(app, markets("90000"))
                assertFails { app.notifications.saveAlert("unknown-coin", AlertCondition.ABOVE, d("1")) }
                assertFails { app.notifications.saveAlert("bitcoin", AlertCondition.ABOVE, d("0")) }
                app.notifications.saveAlert("bitcoin", AlertCondition.ABOVE, d("100000"))
                assertTrue(app.notifications.active())
                assertTrue(app.notifications.evaluate("2027-01-15", 12).isEmpty())
                now += 120_000
                refresh(app, markets("100500"))
                val first = app.notifications.evaluate("2027-01-15", 12)
                assertEquals(listOf("BTC is above 100,000.00 USD"), first.map { it.title })
                assertTrue(app.notifications.evaluate("2027-01-15", 12).isEmpty())
            }
            // A new process reads the same snapshot and the recorded state: nothing is delivered again.
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { now }
                assertTrue(app.notifications.evaluate("2027-01-15", 12).isEmpty())
                assertEquals(1, app.notifications.history().size)
                // A list older than an hour is never evaluated.
                now += 120_000
                refresh(app, markets("90000"))
                now += AlertRules.MAX_PRICE_AGE_MILLIS + 1
                assertTrue(app.notifications.evaluate("2027-01-15", 12).isEmpty())
                assertFalse(
                    app.notifications
                        .alerts()
                        .single()
                        .armed,
                )
                app.notifications.setAlertEnabled(
                    app.notifications
                        .alerts()
                        .single()
                        .id,
                    false,
                )
                assertFalse(app.notifications.active())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun settingsSendTheDailyUpdateMovesAndHighsOnce() {
        JvmSqliteDatabase(":memory:").use { db ->
            val now = start
            val app = PortfolioApplication(db) { now }
            app.market.setConsent(true)
            app.market.setFavorites(setOf(io.valroot.core.bitcoin.BITCOIN_ASSET))
            refresh(app, markets("100000", change = -12.3, athAt = start - 3_600_000))
            assertTrue(app.notifications.evaluate("2027-01-15", 12).isEmpty())
            app.notifications.setPreferences(NotificationPreferences(dailyUpdate = true, priceMoves = true, highsAndLows = true))
            assertTrue(app.notifications.evaluate("2027-01-15", 8).none { it.kind == NotificationKind.DAILY_UPDATE })
            assertTrue(
                app.notifications.history().map { it.kind }.containsAll(
                    listOf(NotificationKind.PRICE_MOVE, NotificationKind.ALL_TIME_HIGH),
                ),
            )
            val later = app.notifications.evaluate("2027-01-15", 9)
            assertEquals(listOf(NotificationKind.DAILY_UPDATE), later.map { it.kind })
            assertTrue(app.notifications.evaluate("2027-01-15", 10).isEmpty())
            assertEquals(
                "BTC is down 12.3% in 24 hours",
                app.notifications
                    .history()
                    .first { it.kind == NotificationKind.PRICE_MOVE }
                    .title,
            )
            // The next day's update hides amounts when amounts are hidden.
            app.settings.setHideAmounts(true)
            val next = app.notifications.evaluate("2027-01-16", 9).single { it.kind == NotificationKind.DAILY_UPDATE }
            assertFalse(next.body.contains("worth"))
            assertTrue(CoingeckoMarkets.parseMarkets(markets("1", athAt = 5)).single().allTimeHighAtMillis == 5L)
        }
    }
}
