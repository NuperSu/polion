package io.valroot.ui

import io.valroot.core.alerts.AlertCondition
import io.valroot.core.alerts.NotificationText
import io.valroot.core.alerts.PriceAlert
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.ui.model.ModelAlertRule
import kotlinx.coroutines.withContext

/** What the coin screens need from the database for one asset, loaded on the host dispatcher. */
internal data class CoinMarketLoad(
    val asset: AssetId,
    /** The CoinGecko coin ID, or null when the asset has none. */
    val coinId: String?,
    val marketConsent: Boolean,
    val orderConsent: Boolean,
    val alerts: List<PriceAlert>,
)

internal suspend fun loadCoinMarket(
    app: PortfolioApplication,
    host: PlatformActions,
    asset: AssetId,
): CoinMarketLoad =
    withContext(host.dispatcher) {
        val coinId = app.marketCoinId(asset)
        CoinMarketLoad(
            asset = asset,
            coinId = coinId,
            marketConsent = app.marketDataConsent(),
            orderConsent = app.orderBookConsent(),
            alerts = coinId?.let { app.priceAlerts(it) }.orEmpty(),
        )
    }

internal const val ALERT_TOP_250_NOTE = "Alerts are available for CoinGecko's top 250 coins."
internal const val ALERT_CONSENT_NOTE = "Alerts use CoinGecko's top 250 coins. Turn on market data on Main first."

/**
 * Why a new alert cannot be added, or null when it can. [listed] says whether the coin is in the cached top-250 list for the
 * display currency. Without a CoinGecko ID the top-250 wording applies too, since market data cannot change that.
 */
internal fun alertBlockReason(
    coinId: String?,
    marketConsent: Boolean,
    listed: Boolean,
): String? =
    when {
        coinId == null -> ALERT_TOP_250_NOTE
        !marketConsent -> ALERT_CONSENT_NOTE
        !listed -> ALERT_TOP_250_NOTE
        else -> null
    }

/**
 * Parses what the alert editor typed: a plain number, or a number with comma thousands separators. Throws
 * IllegalArgumentException with the message the editor shows.
 */
internal fun parseAlertThreshold(text: String): ExactDecimal {
    val trimmed = text.trim()
    val plain = Regex("[0-9]+(\\.[0-9]+)?")
    val grouped = Regex("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")
    require(trimmed.matches(plain) || trimmed.matches(grouped)) { "Enter a number, like 100000 or 2.5." }
    return ExactDecimal.parse(trimmed.replace(",", ""))
}

internal fun alertTitle(alert: PriceAlert): String =
    when (alert.condition) {
        AlertCondition.ABOVE -> "Above ${NotificationText.price(alert.threshold, alert.currency)}"
        AlertCondition.BELOW -> "Below ${NotificationText.price(alert.threshold, alert.currency)}"
        AlertCondition.MOVE -> "Moves ${alert.threshold}%"
    }

/** One alert as a row in the coin's Alerts tab. */
internal fun coinAlertRule(alert: PriceAlert): ModelAlertRule {
    val description =
        buildString {
            append(NotificationText.describe(alert))
            if (alert.condition != AlertCondition.MOVE && !alert.armed) append(" · waits for the price to cross back")
            alert.lastFiredMillis?.let { append(" · last alert ${chartTime(it, DAY_MILLIS)}") }
        }
    return ModelAlertRule(
        id = alert.id,
        title = alertTitle(alert),
        description = description,
        enabled = alert.enabled,
        source = "CoinGecko · ${alert.symbol.uppercase()}/${alert.currency.uppercase()}",
    )
}

internal const val DAY_MILLIS = 86_400_000L
