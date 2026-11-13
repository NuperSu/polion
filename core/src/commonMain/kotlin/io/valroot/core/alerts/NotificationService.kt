package io.valroot.core.alerts

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.NOTIFICATION_HISTORY_SIZE
import io.valroot.core.application.NOTIFICATION_MARKET_AGE_MILLIS
import io.valroot.core.application.SIGNIFICANT_MOVE_PERCENT
import io.valroot.core.model.ExactDecimal

/** Price alerts and notifications: preferences, alert rules, evaluation and delivery history. */
class NotificationService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun preferences(): NotificationPreferences =
        NotificationPreferences(
            dailyUpdate = setting("notify_daily") == "true",
            priceMoves = setting("notify_moves") == "true",
            highsAndLows = setting("notify_extremes") == "true",
        )

    fun setPreferences(value: NotificationPreferences) =
        db.transaction {
            putSetting("notify_daily", value.dailyUpdate.toString())
            putSetting("notify_moves", value.priceMoves.toString())
            putSetting("notify_extremes", value.highsAndLows.toString())
        }

    /** True when any alert or notification setting is on, so hosts schedule checks and request notification permission. */
    fun active(): Boolean =
        preferences().let { it.dailyUpdate || it.priceMoves || it.highsAndLows } || notifications.alerts().any { it.enabled }

    fun alerts(coinId: String? = null): List<PriceAlert> = notifications.alerts().filter { coinId == null || it.coinId == coinId }

    /**
     * Saves a new alert, or replaces the condition of [id]. The coin must be in the cached top-250 list for the display
     * currency, whose price sets the starting state (see [AlertRules.start]).
     */
    fun saveAlert(
        coinId: String,
        condition: AlertCondition,
        threshold: ExactDecimal,
        id: String? = null,
    ): PriceAlert {
        require(threshold.coefficient.sign > 0) { "Enter a positive number" }
        require(condition != AlertCondition.MOVE || threshold <= ExactDecimal.parse("1000")) { "Use a move of at most 1000%" }
        val code =
            app.assets
                .currency()
                .value
                .lowercase()
        val feed = marketFeeds.markets(code)
        val listing = requireNotNull(feed?.items?.firstOrNull { it.coinId == coinId }) { "Alerts need this coin's market price" }
        val fresh = feed.observedAtMillis.takeIf { now() - it <= AlertRules.MAX_PRICE_AGE_MILLIS }
        val existing =
            id?.let { key ->
                notifications.alerts().firstOrNull { it.id == key }
                    ?: throw IllegalArgumentException("Unknown alert")
            }
        val draft =
            PriceAlert(
                id = existing?.id ?: "alert-${now()}-${kotlin.random.Random.nextLong().toULong().toString(16)}",
                coinId = coinId,
                symbol = listing.symbol,
                currency = code,
                condition = condition,
                threshold = threshold,
                enabled = existing?.enabled ?: true,
                armed = true,
                reference = null,
                referenceAtMillis = null,
                lastObservedMillis = null,
                lastFiredMillis = null,
                createdAtMillis = existing?.createdAtMillis ?: now(),
            )
        val started = AlertRules.start(draft, fresh?.let { listing.price }, fresh)
        notifications.save(started)
        return started
    }

    fun setAlertEnabled(
        id: String,
        enabled: Boolean,
    ) = db.transaction {
        val alert = requireNotNull(notifications.alerts().firstOrNull { it.id == id }) { "Unknown alert" }
        notifications.save(alert.copy(enabled = enabled))
    }

    fun deleteAlert(id: String) = notifications.delete(id)

    fun history(limit: Int = 50): List<NotificationMessage> = notifications.history(limit)

    /**
     * Evaluates alerts and notification settings against the cached market list, and returns the notifications to show
     * now. Each is recorded before it is returned, in the same transaction as the alert states, so no event is delivered
     * twice, even across restarts or when two processes evaluate at once. [localDay] (for example "2026-11-08") and
     * [localHour] come from the device clock: the daily update is sent once per local day, from 9:00.
     */
    fun evaluate(
        localDay: String,
        localHour: Int,
    ): List<NotificationMessage> {
        val nowMillis = now()
        val code =
            app.assets
                .currency()
                .value
                .lowercase()
        val feed = marketFeeds.markets(code)?.takeIf { nowMillis - it.observedAtMillis <= AlertRules.MAX_PRICE_AGE_MILLIS }
        val listings = feed?.items.orEmpty().associateBy { it.coinId }
        val preferences = preferences()
        val daily =
            if (preferences.dailyUpdate && localHour >= 9 && !notifications.delivered("daily:$localDay")) dailyUpdate(localDay) else null
        val followed =
            if (preferences.priceMoves || preferences.highsAndLows) {
                (
                    app.market.favorites() +
                        app
                            .snapshot()
                            .valuations
                            .filter { it.amount.atomicUnits.sign > 0 }
                            .map { it.asset }
                ).mapNotNull(app.market::coinId)
                    .toSet()
            } else {
                emptySet()
            }
        return db.transaction {
            val delivered = mutableListOf<NotificationMessage>()

            fun deliver(message: NotificationMessage) {
                if (notifications.record(message)) delivered += message
            }
            if (feed != null) {
                notifications.alerts().filter { it.currency == code }.forEach { alert ->
                    val listing = listings[alert.coinId] ?: return@forEach
                    val step = AlertRules.step(alert, listing.price, feed.observedAtMillis, nowMillis)
                    if (step.alert != alert) notifications.save(step.alert)
                    if (step.fired) {
                        val title =
                            when (alert.condition) {
                                AlertCondition.ABOVE -> "${alert.symbol} is above ${NotificationText.price(alert.threshold, code)}"
                                AlertCondition.BELOW -> "${alert.symbol} is below ${NotificationText.price(alert.threshold, code)}"
                                AlertCondition.MOVE ->
                                    "${alert.symbol} moved " +
                                        NotificationText.percent(AlertRules.percentChange(alert.reference!!, listing.price))
                            }
                        deliver(
                            NotificationMessage(
                                "alert:${alert.id}:${feed.observedAtMillis}",
                                NotificationKind.PRICE_ALERT,
                                title,
                                "${listing.name} is ${NotificationText.price(listing.price, code)} on CoinGecko.",
                                nowMillis,
                                alert.id,
                                alert.coinId,
                            ),
                        )
                    }
                }
                followed.mapNotNull { listings[it] }.forEach { listing ->
                    val change = listing.change24h
                    if (preferences.priceMoves && change != null && kotlin.math.abs(change) >= SIGNIFICANT_MOVE_PERCENT) {
                        val direction = if (change > 0) "up" else "down"
                        deliver(
                            NotificationMessage(
                                "move:${listing.coinId}:$localDay:$direction",
                                NotificationKind.PRICE_MOVE,
                                "${listing.symbol} is $direction ${NotificationText.percent(change).trimStart('+', '-')} in 24 hours",
                                "${listing.name} is ${NotificationText.price(listing.price, code)} on CoinGecko.",
                                nowMillis,
                                coinId = listing.coinId,
                            ),
                        )
                    }
                    if (preferences.highsAndLows) {
                        val recent = { at: Long? -> at != null && feed.observedAtMillis - at in 0..86_400_000L }
                        if (recent(listing.allTimeHighAtMillis)) {
                            deliver(
                                NotificationMessage(
                                    "ath:${listing.coinId}:${listing.allTimeHighAtMillis!! / 86_400_000L}",
                                    NotificationKind.ALL_TIME_HIGH,
                                    "${listing.symbol} reached an all-time high",
                                    "${listing.name} reached ${NotificationText.price(listing.allTimeHigh!!, code)} on CoinGecko.",
                                    nowMillis,
                                    coinId = listing.coinId,
                                ),
                            )
                        }
                        if (recent(listing.allTimeLowAtMillis)) {
                            deliver(
                                NotificationMessage(
                                    "atl:${listing.coinId}:${listing.allTimeLowAtMillis!! / 86_400_000L}",
                                    NotificationKind.ALL_TIME_LOW,
                                    "${listing.symbol} reached an all-time low",
                                    "${listing.name} fell to ${NotificationText.price(listing.allTimeLow!!, code)} on CoinGecko.",
                                    nowMillis,
                                    coinId = listing.coinId,
                                ),
                            )
                        }
                    }
                }
            }
            daily?.let(::deliver)
            if (delivered.isNotEmpty()) notifications.trim(NOTIFICATION_HISTORY_SIZE)
            delivered
        }
    }

    /**
     * One scheduled or on-demand notification check: refreshes the market list when it is older than
     * [marketMaxAgeMillis] (a failed refresh keeps the cached list), then evaluates. Does nothing while no alert or
     * notification setting is on.
     */
    fun runCheck(
        marketTransport: (url: String, apiKey: String?) -> String,
        localDay: String,
        localHour: Int,
        marketMaxAgeMillis: Long = NOTIFICATION_MARKET_AGE_MILLIS,
    ): List<NotificationMessage> {
        if (!active()) return emptyList()
        if (app.market.consent()) runCatching { app.market.refreshFeeds(marketTransport, marketMaxAgeMillis) }
        return evaluate(localDay, localHour)
    }

    /** The day's portfolio summary; amounts are left out while amounts are hidden, as notifications show on the lock screen. */
    private fun dailyUpdate(localDay: String): NotificationMessage? {
        val end = now()
        val result = app.analytics.moneyWeightedReturn(null, end - 86_400_000L, end)
        val value = result.endValue ?: return null
        val change = result.periodReturn?.let { "${NotificationText.percent(it * 100)} in 24 hours" }
        val body =
            if (app.settings.general().hideAmounts) {
                change?.let { "Your portfolio is $it." } ?: "Your portfolio's 24-hour change is not available."
            } else {
                "Your portfolio is worth ${NotificationText.price(value, result.currency.value)}" + (change?.let { ", $it." } ?: ".")
            }
        return NotificationMessage("daily:$localDay", NotificationKind.DAILY_UPDATE, "Daily portfolio update", body, end)
    }
}
