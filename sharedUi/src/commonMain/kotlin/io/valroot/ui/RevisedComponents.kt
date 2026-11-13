package io.valroot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.MarketCoin
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Market observations stay separate from quantities and portfolio valuation. */
@Composable
internal fun RevisedMain(
    app: PortfolioApplication,
    host: PlatformActions,
    snapshot: PortfolioSnapshot,
    onAsset: (MarketCoin) -> Unit,
) {
    var favorites by remember(app) { mutableStateOf(emptySet<AssetId>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app) { favorites = withContext(host.dispatcher) { app.market.favorites() } }
    val assets = snapshot.marketCoins.distinctBy { it.asset }.sortedBy { it.metadata?.symbol ?: it.asset.value }

    fun toggle(asset: MarketCoin) {
        val id = asset.asset
        val next = if (id in favorites) favorites - id else favorites + id
        favorites = next
        scope.launch { withContext(host.dispatcher) { app.market.setFavorites(next) } }
    }
    Column(Modifier.fillMaxWidth().testTag("main-market"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Market", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Saved prices · ${snapshot.currency.value}", color = Muted, fontSize = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MarketHeading("Saved coins", "YOUR LOCAL MARKET DATA")
            if (assets.isEmpty()) {
                MarketPlaceholder("No saved prices", "Prices appear here when available for your tracked assets.")
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    assets.forEach { asset ->
                        Surface(
                            modifier =
                                Modifier
                                    .width(
                                        168.dp,
                                    ).clickable(role = Role.Button) { onAsset(asset) }
                                    .testTag("market-${asset.asset.value}"),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    MarketMark(asset)
                                    Spacer(Modifier.weight(1f))
                                    FavoriteButton(asset.asset in favorites, asset.metadata?.symbol ?: asset.asset.value) { toggle(asset) }
                                }
                                Text(asset.metadata?.symbol ?: asset.asset.value, color = Ink, fontWeight = FontWeight.Bold)
                                Text(marketPrice(asset), color = Ink, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(marketQuoteStatus(asset), color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MarketHeading("Favorites", "YOUR WATCHLIST")
            val selected = assets.filter { it.asset in favorites }
            if (selected.isEmpty()) {
                Text("Tap a star to keep a coin close at hand.", color = Muted, fontSize = 13.sp)
            } else {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        selected.forEachIndexed { index, asset ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { onAsset(asset) }.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MarketMark(asset)
                                Column(Modifier.weight(1f)) {
                                    Text(asset.metadata?.symbol ?: asset.asset.value, color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text(asset.metadata?.name ?: asset.asset.value, color = Muted, fontSize = 12.sp)
                                }
                                Text(marketPrice(asset), color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                FavoriteButton(true, asset.metadata?.symbol ?: asset.asset.value) { toggle(asset) }
                            }
                            if (index != selected.lastIndex) HorizontalDivider(color = Line.copy(alpha = .35f))
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MarketHeading("Discover", "MARKET COVERAGE")
            MarketPlaceholder("Top coins", "Global market rankings are unavailable.")
            MarketPlaceholder("Trending", "Trending market data is unavailable.")
            MarketPlaceholder("Top gainers", "Market-wide price change rankings are unavailable.")
            MarketPlaceholder("Top losers", "Market-wide price change rankings are unavailable.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MarketHeading(
    title: String,
    eyebrow: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(title, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun MarketPlaceholder(
    title: String,
    detail: String,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MarketMark(asset: MarketCoin) {
    Box(Modifier.size(34.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
        Text((asset.metadata?.symbol ?: asset.asset.value).take(3), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FavoriteButton(
    selected: Boolean,
    asset: String,
    onClick: () -> Unit,
) {
    TouchTarget(
        target =
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = if (selected) "Remove $asset from favorites" else "Add $asset to favorites"
                    stateDescription = if (selected) "Favorite" else "Not favorite"
                },
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(if (selected) "★" else "☆", color = if (selected) Teal else Muted, fontSize = 22.sp)
        }
    }
}

private fun marketPrice(asset: MarketCoin): String = quotePrice(asset.price.price.value, asset.price.price.quoteAssetId)

private fun marketQuoteStatus(asset: MarketCoin): String =
    when {
        asset.priceStatus == PriceStatus.STALE -> "Stale saved quote"
        asset.price.manual -> "Manual quote"
        else -> "Saved quote"
    }

@Composable
internal fun RevisedIcon(
    name: String,
    modifier: Modifier,
    tint: Color = Ink,
) {
    // Icons are decorative: each caller pairs them with a text label or a content description on the parent.
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()

        fun line(
            x: Float,
            y: Float,
            endX: Float,
            endY: Float,
        ) = drawLine(tint, Offset(x * w, y * h), Offset(endX * w, endY * h), stroke, StrokeCap.Round)
        when (name.lowercase()) {
            "main", "home" -> {
                val path =
                    Path().apply {
                        moveTo(.08f * w, .44f * h)
                        lineTo(.5f * w, .08f * h)
                        lineTo(.92f * w, .44f * h)
                        moveTo(.2f * w, .35f * h)
                        lineTo(.2f * w, .92f * h)
                        lineTo(.4f * w, .92f * h)
                        lineTo(.4f * w, .62f * h)
                        lineTo(.6f * w, .62f * h)
                        lineTo(.6f * w, .92f * h)
                        lineTo(.8f * w, .92f * h)
                        lineTo(.8f * w, .35f * h)
                    }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "wallet", "portfolio" -> {
                drawRoundRect(tint, Offset(.08f * w, .22f * h), Size(.84f * w, .65f * h), CornerRadius(.07f * w), style = Stroke(stroke))
                drawRoundRect(tint, Offset(.58f * w, .43f * h), Size(.35f * w, .25f * h), CornerRadius(.03f * w), style = Stroke(stroke))
                drawCircle(tint, .025f * w, Offset(.69f * w, .55f * h))
                line(.16f, .22f, .7f, .08f)
                line(.7f, .08f, .82f, .22f)
            }
            "insights", "reports" -> {
                drawCircle(tint, .4f * w, Offset(.5f * w, .5f * h), style = Stroke(stroke))
                line(.5f, .1f, .5f, .5f)
                line(.5f, .5f, .84f, .72f)
                line(.5f, .5f, .13f, .66f)
            }
            "menu", "more" -> repeat(3) { line(.12f, .23f + it * .27f, .88f, .23f + it * .27f) }
        }
    }
}

/** Price and metadata carrier for details without a holding; ownership figures are hidden for market-only assets. */
internal fun MarketCoin.detailValuation(): AssetValuation =
    AssetValuation(asset, AtomicAmount(asset, ExactInteger.ZERO), metadata, priceStatus, null, price)
