package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.Price
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun ColumnScope.PriceWorkflow(
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    host: PlatformActions,
    scope: CoroutineScope,
    notify: (String) -> Unit,
    execute: (String, () -> Unit) -> Unit,
    close: () -> Unit,
    amountState: MutableState<String>,
    addressState: MutableState<String>,
    assetState: MutableState<AssetId>,
    errorState: MutableState<String?>,
    priceConsentState: MutableState<Boolean>,
    automaticPriceConsentState: MutableState<Boolean>,
    automaticPortfolioPricesState: MutableState<Boolean>,
    automaticPriceStatusState: MutableState<String?>,
    priceDaysState: MutableIntState,
    fetchingPriceState: MutableState<Boolean>,
    priceMessageState: MutableState<String?>,
    priceRefreshStatusState: State<PriceRefreshPhase>,
) {
    var amount by amountState
    var address by addressState
    var asset by assetState
    var error by errorState
    var priceConsent by priceConsentState
    var automaticPriceConsent by automaticPriceConsentState
    var automaticPortfolioPrices by automaticPortfolioPricesState
    val automaticPriceStatus by automaticPriceStatusState
    var priceDays by priceDaysState
    var fetchingPrice by fetchingPriceState
    var priceMessage by priceMessageState
    val priceRefreshStatus by priceRefreshStatusState

    Text(
        "A manual quote is a saved observation, not a market feed. It is used for valuations from its effective time, and remains traceable in asset details.",
        fontSize = 13.sp,
    )
    val assets =
        (
            (data.valuations + data.marketCoins.map { it.detailValuation() }).map {
                it.asset to
                    (it.metadata?.name ?: it.asset.value)
            } +
                (AssetId("bitcoin:btc") to "Bitcoin")
        ).distinctBy { it.first }.filter { it.first != data.currency }
    Choice("Asset", assets, asset) { asset = it }
    Field("Price per whole coin in ${data.currency.value}", amount) { amount = it }
    Field("Effective date (UTC, YYYY-MM-DD; empty = now)", address) { address = it }
    Button(onClick = {
        val price = runCatching { ExactDecimal.parse(amount) }.getOrNull()
        val at =
            if (address.isBlank()) {
                Clock.System.now().toEpochMilliseconds()
            } else {
                runCatching {
                    kotlin.time.Instant
                        .parse("${address}T00:00:00Z")
                        .toEpochMilliseconds()
                }.getOrNull()
            }
        if (price == null ||
            price.coefficient.sign < 0 ||
            at == null ||
            at > Clock.System.now().toEpochMilliseconds()
        ) {
            error =
                "Enter a nonnegative price and a valid date in the past or today."
            return@Button
        }
        execute("Manual price saved") {
            app.prices.recordManual(
                PriceObservation(
                    uniqueId("manual-price"),
                    Price(asset, data.currency, price),
                    null,
                    at,
                    Clock.System.now().toEpochMilliseconds(),
                    true,
                ),
            )
        }
        close()
    }, modifier = Modifier.fillMaxWidth()) { Text("Save price observation") }
    HorizontalDivider()
    Text("Automatic Bitcoin prices", fontWeight = FontWeight.SemiBold)
    Text(
        "Kraken receives Bitcoin pairs for your USD, EUR and GBP portfolio currencies and your IP address; wallet addresses " +
            "are not sent. This switch also turns on automatic wallet history refresh. " +
            "Closed hourly and daily prices refresh while scheduling is available. No API key is needed.",
        fontSize = 13.sp,
    )
    Row(
        Modifier
            .toggleable(value = automaticPriceConsent, role = Role.Checkbox) { accepted ->
                automaticPriceConsent = accepted
                if (accepted) automaticPortfolioPrices = true
                execute("Refresh settings saved") { applyAutomaticRefresh(app, host, accepted) }
            }.testTag("automatic-price-consent"),
    ) {
        Checkbox(checked = automaticPriceConsent, onCheckedChange = null)
        Text("Allow automatic Kraken prices and wallet refresh", modifier = Modifier.padding(top = 12.dp))
    }
    if (automaticPriceConsent) {
        Row(
            Modifier
                .toggleable(value = automaticPortfolioPrices, role = Role.Checkbox) { accepted ->
                    automaticPortfolioPrices = accepted
                    execute("Price currencies saved") { app.prices.setAutomaticPortfolioConsent(accepted) }
                }.testTag("automatic-portfolio-price-consent"),
        ) {
            Checkbox(checked = automaticPortfolioPrices, onCheckedChange = null)
            Text("Include other portfolio currencies", modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            automaticPriceStatus ?: "Waiting for the first price refresh.",
            color = Muted,
            fontSize = 12.sp,
        )
        when (priceRefreshStatus) {
            PriceRefreshPhase.QUEUED -> Text("Price refresh queued.", color = Muted, fontSize = 12.sp)
            PriceRefreshPhase.RUNNING -> Text("Refreshing prices…", color = Muted, fontSize = 12.sp)
            PriceRefreshPhase.IDLE -> Unit
        }
        OutlinedButton(
            onClick = {
                host.refreshAutomaticPrices { message ->
                    priceMessage = message
                    notify(message)
                }
            },
            enabled = priceRefreshStatus == PriceRefreshPhase.IDLE,
            modifier = Modifier.testTag("refresh-prices-now"),
        ) { Text("Refresh prices now") }
    }
    Text("Bitcoin price history", fontWeight = FontWeight.SemiBold)
    Text(
        "CoinGecko receives the coin, currency and time range, plus your IP address. Wallet addresses are not sent. Saved quotes are available offline and do not establish acquisition cost. The 90-day range includes hourly points; the 365-day range uses daily points.",
        fontSize = 13.sp,
    )
    Row(
        Modifier
            .toggleable(value = priceConsent, enabled = !fetchingPrice, role = Role.Checkbox) { accepted ->
                priceConsent = accepted
                scope.launch { withContext(host.dispatcher) { app.prices.setSourceConsent(accepted) } }
            }.testTag("price-source-consent"),
    ) {
        Checkbox(checked = priceConsent, onCheckedChange = null, enabled = !fetchingPrice)
        Text("Allow CoinGecko price requests", modifier = Modifier.padding(top = 12.dp))
    }
    Text("An optional CoinGecko API key from Settings › API Keys raises the request limit.", fontSize = 13.sp)
    Choice("Price history range", listOf(90 to "90 days · hourly", 365 to "365 days · daily"), priceDays) { priceDays = it }
    Button(
        onClick = {
            fetchingPrice = true
            scope.launch {
                val apiKey =
                    withContext(host.dispatcher) {
                        app.prices.setSourceConsent(priceConsent)
                        app.market.coingeckoApiKey().orEmpty()
                    }
                host.fetchPriceHistory(AssetId("bitcoin:btc"), data.currency, priceDays, apiKey) { message ->
                    fetchingPrice = false
                    priceMessage = message
                    notify(message)
                }
            }
        },
        enabled = priceConsent && !fetchingPrice,
        modifier = Modifier.fillMaxWidth().testTag("fetch-historical-price"),
    ) { Text(if (fetchingPrice) "Fetching price history…" else "Fetch Bitcoin price history") }
    priceMessage?.let { Text(it, fontSize = 13.sp) }
}
