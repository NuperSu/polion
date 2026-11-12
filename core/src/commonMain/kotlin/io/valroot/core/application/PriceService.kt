package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.market.CoingeckoHistoricalResult
import io.valroot.core.market.CoingeckoPriceClient
import io.valroot.core.market.CoingeckoPrices
import io.valroot.core.market.CoingeckoProviderException
import io.valroot.core.market.CoingeckoProviderFailure
import io.valroot.core.market.CoingeckoTransport
import io.valroot.core.market.DefiLlamaPrices
import io.valroot.core.market.KrakenPrices
import io.valroot.core.market.KrakenRateLimitedException
import io.valroot.core.market.PriceObservation
import io.valroot.core.market.PricePolicy
import io.valroot.core.model.AssetId
import io.valroot.core.model.ProviderId
import kotlin.time.Instant

/** Prices: automatic price refresh and its consent, historical price fetches, manual prices and the price policy. */
class PriceService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun sourceConsent(): Boolean = setting("price_source_consent") == "true"

    fun automaticConsent(): Boolean = setting("automatic_price_consent") == "true"

    fun automaticGeneration(): String? = setting("automatic_price_generation")

    /** Cancel in-flight automatic requests without changing the user's saved consent or scope. */
    fun invalidateAutomaticRequests() =
        db.transaction {
            putSetting("automatic_price_generation", requestNonce())
        }

    fun automaticPortfolioConsent(): Boolean = setting("automatic_price_scope") == "all"

    fun automaticStatus(): String? = setting("automatic_price_status")

    /**
     * Epoch millis of the last finished automatic price refresh, successful or not, or null when none has finished.
     * Schedules wait an interval from this time, so a failing provider is not asked again on every app start.
     */
    fun automaticLastRunMillis(): Long? = setting("automatic_price_last_run")?.toLongOrNull()

    fun recordAutomaticSchedulingFailure() =
        db.transaction {
            if (automaticConsent()) putSetting("automatic_price_status", "Automatic price refresh could not be scheduled")
        }

    fun recordAutomaticStatus(
        success: Boolean,
        rateLimited: Boolean = false,
    ) = db.transaction {
        if (automaticConsent()) {
            putSetting("automatic_price_last_run", now().toString())
            putSetting(
                "automatic_price_status",
                if (success) {
                    "Last refresh succeeded at ${Instant.fromEpochMilliseconds(
                        now(),
                    )}"
                } else if (rateLimited) {
                    val until = setting("kraken_retry_after")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
                    if (until == null) {
                        "Kraken is rate limiting requests. Saved prices remain available"
                    } else {
                        "Kraken is rate limiting requests; retry after $until. Saved prices remain available"
                    }
                } else {
                    "Some price requests failed; saved prices remain available"
                },
            )
        }
    }

    fun setAutomaticConsent(accepted: Boolean) = db.transaction { putAutomaticConsent(accepted) }

    private fun putAutomaticConsent(accepted: Boolean) {
        putSetting("automatic_price_generation", requestNonce())
        putSetting("automatic_price_consent", accepted.toString())
        if (accepted) putSetting("automatic_price_scope", "all")
    }

    /**
     * The single automatic-refresh switch. Enabling it grants automatic Kraken price and CoinGecko market consent with the
     * schedule preference; disabling it withdraws the Kraken consent. Both change in one transaction, so prices and wallet history cannot disagree.
     */
    fun setAutomaticRefresh(enabled: Boolean) =
        db.transaction {
            putSetting("refresh_automatic", enabled.toString())
            putAutomaticConsent(enabled)
            // Auto-sync also refreshes market lists; turning it off keeps manual market refresh available.
            if (enabled) app.market.setConsent(true)
        }

    fun setAutomaticPortfolioConsent(accepted: Boolean) =
        db.transaction {
            require(automaticConsent()) { "Price provider consent required" }
            putSetting("automatic_price_generation", requestNonce())
            putSetting("automatic_price_scope", if (accepted) "all" else "default")
        }

    /** Default, secondary and saved portfolio currencies supported by the automatic Bitcoin provider. */
    fun automaticCurrencies(): List<AssetId> =
        (
            listOf(app.assets.currency()) + listOfNotNull(app.settings.general().secondaryCurrency) +
                if (automaticPortfolioConsent()) {
                    db.query("SELECT DISTINCT currency FROM portfolios").map { AssetId(requireNotNull(it.single())) }
                } else {
                    emptyList()
                }
        ).filter { it.value in DISPLAY_CURRENCIES }
            .distinct()

    fun refreshAutomaticSet(
        transport: (String) -> String,
        authorize: () -> Unit = {},
    ): Int {
        require(automaticConsent()) { "Price provider consent required" }
        val generation = requireNotNull(setting("automatic_price_generation"))

        fun checkAuthorization() {
            authorize()
            if (!automaticConsent() || setting("automatic_price_generation") != generation) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
        var count = 0
        var failures = 0
        val currencies = automaticCurrencies()
        require(currencies.isNotEmpty()) { "No supported portfolio currency" }
        for (quote in currencies) {
            for (daily in listOf(false, true)) {
                checkAuthorization()
                try {
                    count +=
                        if (daily) {
                            refreshAutomaticDailyIfDue(transport, quote, ::checkAuthorization)
                        } else {
                            refreshAutomatic(transport, quote, ::checkAuthorization)
                        }
                } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    checkAuthorization()
                    if (failure is KrakenRateLimitedException) throw failure
                    if (failure is CoingeckoProviderException && failure.failure == CoingeckoProviderFailure.CANCELLED) throw failure
                    failures++
                }
            }
        }
        check(failures == 0) { "Some price requests failed" }
        return count
    }

    fun refreshAutomatic(
        transport: (String) -> String,
        quote: AssetId = app.assets.currency(),
        authorize: () -> Unit = {},
    ): Int {
        require(automaticConsent()) { "Price provider consent required" }
        app.assets.ensureDefaults()
        require(quote in automaticCurrencies()) { "Unsupported portfolio currency" }
        val generation = requireNotNull(setting("automatic_price_generation"))
        val observedAt = now()
        return KrakenPrices.fetch(
            guardKrakenRequests(transport, authorize),
            market,
            BITCOIN_ASSET,
            quote,
            observedAt,
            "kraken:${quote.value}:${requestNonce()}",
        ) {
            authorize()
            if (!automaticConsent() || setting("automatic_price_generation") != generation || quote !in automaticCurrencies()) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
    }

    /** Fetch daily history at most once per UTC day, independently of hourly refreshes. */
    fun refreshAutomaticDailyIfDue(
        transport: (String) -> String,
        quote: AssetId = app.assets.currency(),
        authorize: () -> Unit = {},
    ): Int {
        require(automaticConsent()) { "Price provider consent required" }
        app.assets.ensureDefaults()
        require(quote in automaticCurrencies()) { "Unsupported portfolio currency" }
        val generation = requireNotNull(setting("automatic_price_generation"))
        val observedAt = now()
        val day = observedAt / 86_400_000L
        val key = "automatic_price_daily:${quote.value}"
        if (setting(key)?.toLongOrNull() == day) return 0
        val count =
            KrakenPrices.fetch(
                guardKrakenRequests(transport, authorize),
                market,
                BITCOIN_ASSET,
                quote,
                observedAt,
                "kraken:${quote.value}:daily:${requestNonce()}",
                1440,
            ) {
                authorize()
                if (!automaticConsent() ||
                    setting("automatic_price_generation") != generation ||
                    quote !in automaticCurrencies()
                ) {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                }
            }
        db.transaction {
            authorize()
            if (!automaticConsent() || setting("automatic_price_generation") != generation || quote !in automaticCurrencies()) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
            putSetting(key, day.toString())
        }
        return count
    }

    /** A provider cooldown is shared by app instances through the local database. */
    private fun guardKrakenRequests(
        transport: (String) -> String,
        authorize: () -> Unit,
    ): (String) -> String =
        { url ->
            authorize()
            val remaining = (setting("kraken_retry_after")?.toLongOrNull() ?: 0L) - now()
            if (remaining > 0) throw KrakenRateLimitedException(remaining)
            try {
                transport(url)
            } catch (failure: KrakenRateLimitedException) {
                val delay = failure.retryAfterMillis.coerceIn(0L, 86_400_000L)
                db.transaction {
                    val at = now().coerceAtMost(Long.MAX_VALUE - delay) + delay
                    val previous = setting("kraken_retry_after")?.toLongOrNull() ?: 0L
                    putSetting("kraken_retry_after", maxOf(previous, at).toString())
                }
                throw failure
            }
        }

    fun setSourceConsent(accepted: Boolean) =
        db.transaction {
            putSetting("price_source_generation", requestNonce())
            putSetting("price_source_consent", accepted.toString())
        }

    internal fun requestNonce() = "${kotlin.random.Random.nextLong()}:${kotlin.random.Random.nextLong()}"

    fun fetchHistory(
        transport: CoingeckoTransport,
        asset: AssetId,
        quote: AssetId,
        days: Int,
        authorize: () -> Unit = {},
    ): Int {
        require(sourceConsent()) { "Price provider consent required" }
        require(asset == BITCOIN_ASSET && quote.value in setOf("USD", "EUR", "GBP")) { "Unsupported market" }
        require(days in setOf(1, 7, 30, 90, 365))
        app.assets.ensureDefaults()
        val generation = requireNotNull(setting("price_source_generation")) { "Renew price provider consent" }
        return CoingeckoPriceClient(transport, market, now).chart(
            asset,
            "bitcoin",
            quote,
            quote.value.lowercase(),
            days,
            "coingecko:${quote.value}:chart:${requestNonce()}",
        ) {
            authorize()
            if (!sourceConsent() || setting("price_source_generation") != generation) {
                throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
            }
        }
    }

    /** Explicit daily market observations do not classify acquisitions or establish their cost basis. */
    fun fetchHistorical(
        transport: CoingeckoTransport,
        asset: AssetId,
        quote: AssetId,
        dayUtcMillis: Long,
        authorize: () -> Unit = {},
    ): CoingeckoHistoricalResult {
        require(sourceConsent()) { "Price provider consent required" }
        require(asset == BITCOIN_ASSET) { "Historical provider mapping is available for Bitcoin only" }
        require(quote.value in setOf("USD", "EUR", "GBP")) { "Unsupported quote currency" }
        val observedAt = now()
        require(dayUtcMillis >= 0 && dayUtcMillis % 86_400_000L == 0L && dayUtcMillis <= observedAt) { "Choose a past or current UTC date" }
        require(dayUtcMillis >= observedAt - 365L * 86_400_000L) { "Demo historical prices cover the past 365 days" }
        app.assets.ensureDefaults()
        val consentGeneration = requireNotNull(setting("price_source_generation")) { "Renew price provider consent" }
        return CoingeckoPriceClient(transport, market, now).historical(
            asset,
            "bitcoin",
            quote,
            quote.value.lowercase(),
            dayUtcMillis,
            "coingecko:${quote.value}:$dayUtcMillis:${requestNonce()}",
            authorize = {
                authorize()
                if (!sourceConsent() || setting("price_source_generation") != consentGeneration) {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.CANCELLED)
                }
            },
        )
    }

    /** Saved provider quotes remain selectable offline, independently of permission for new requests. */
    internal fun policy(current: Boolean = false) =
        PricePolicy(
            listOf(
                ProviderId(KrakenPrices.PROVIDER_ID),
                ProviderId(DefiLlamaPrices.PROVIDER_ID),
                ProviderId(CoingeckoPrices.PROVIDER_ID),
            ),
            if (current) 7_200_000L else 86_400_000L,
            preferLatest = true,
        )

    fun recordManual(value: PriceObservation) {
        app.assets.ensureDefaults()
        require(value.manual)
        market.recordPrice(value)
    }

    fun useProvider(
        base: AssetId,
        quote: AssetId,
    ): Int = market.withdrawActiveManualPrices(base, quote, now())
}
