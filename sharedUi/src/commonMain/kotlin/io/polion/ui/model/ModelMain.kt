package io.polion.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ModelMarketCoin(
    val identity: ModelCoinIdentity,
    val price: String,
    val change: String,
    val sparkline: List<Float?> = emptyList(),
    val smoothChart: Boolean = false,
    val favorite: Boolean = false,
)

data class ModelMainState(
    val topCoins: List<ModelMarketCoin> = emptyList(),
    val favorites: List<ModelMarketCoin> = emptyList(),
    val trending: List<ModelMarketCoin> = emptyList(),
    val gainers: List<ModelMarketCoin> = emptyList(),
    val losers: List<ModelMarketCoin> = emptyList(),
    val topCoinsTitle: String = "Top Coins",
    val topCoinsEyebrow: String = "CRYPTO MARKET",
    val topCoinsEmptyMessage: String = "Unavailable",
    val favoritesEmptyMessage: String = "Unavailable",
    val trendingEmptyMessage: String = "Unavailable",
    val gainersEmptyMessage: String = "Unavailable",
    val losersEmptyMessage: String = "Unavailable",
    val canToggleFavorites: Boolean = false,
    /** Names the ranked universe and window behind Top Gainers and Top Losers. */
    val moversEyebrow: String? = null,
    /** Feed freshness, failures and attribution, shown under the sections. */
    val status: String? = null,
    /** Label of the button that turns market data on; null when it is already on. */
    val loadAction: String? = null,
)

sealed interface ModelMainAction {
    data class OpenCoin(
        val identity: ModelCoinIdentity,
    ) : ModelMainAction

    data class ToggleFavorite(
        val identity: ModelCoinIdentity,
    ) : ModelMainAction

    data object LoadMarkets : ModelMainAction
}

@Composable
fun ModelMain(
    state: ModelMainState,
    wide: Boolean,
    onAction: (ModelMainAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        state.loadAction?.let { label ->
            MarketCard {
                Text(
                    "Top coins, trending coins and daily movers come from CoinGecko. CoinGecko sees your IP address and " +
                        "display currency; no wallet data is sent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clickable(role = Role.Button) { onAction(ModelMainAction.LoadMarkets) }
                            .testTag("market-load")
                            .padding(vertical = 4.dp),
                )
            }
        }
        MarketSection(state.topCoinsTitle, state.topCoinsEyebrow) {
            if (state.topCoins.isEmpty()) {
                MarketEmpty(state.topCoinsEmptyMessage)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.topCoins.forEach { coin ->
                        MarketCard(
                            Modifier
                                .width(if (wide) 210.dp else 142.dp)
                                .clickable { onAction(ModelMainAction.OpenCoin(coin.identity)) }
                                .testTag("market-top-${coin.identity.id}"),
                        ) {
                            ModelCoinLogo(coin.identity)
                            Text(coin.identity.ticker, fontWeight = FontWeight.Bold)
                            Text(coin.price, fontWeight = FontWeight.Bold)
                            MarketChange(coin.change)
                            MarketChart(
                                coin.sparkline,
                                Modifier.fillMaxWidth().height(38.dp),
                                MaterialTheme.colorScheme.primary,
                                labels = false,
                                smooth = coin.smoothChart,
                            )
                        }
                    }
                }
            }
        }
        MarketSection("Favorites") {
            MarketCoinList(state.favorites, state.favoritesEmptyMessage, onAction, state.canToggleFavorites, "favorites")
        }
        MarketSection("Trending", "DISCOVER WHAT'S HOT") {
            if (state.trending.isEmpty()) {
                MarketEmpty(state.trendingEmptyMessage)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.trending.forEach { coin ->
                        MarketCard(
                            Modifier
                                .width(156.dp)
                                .clickable { onAction(ModelMainAction.OpenCoin(coin.identity)) }
                                .testTag("market-trending-${coin.identity.id}"),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                ModelCoinLogo(coin.identity)
                                FavoriteStar(coin, state.canToggleFavorites, onAction)
                            }
                            Spacer(Modifier.height(4.dp))
                            Column {
                                Text(coin.identity.ticker, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(coin.identity.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            MarketChange(coin.change, false)
                        }
                    }
                }
            }
        }
        MarketSection("Top Gainers", state.moversEyebrow) {
            MarketCoinList(state.gainers, state.gainersEmptyMessage, onAction, state.canToggleFavorites, "gainers")
        }
        MarketSection("Top Losers", state.moversEyebrow) {
            MarketCoinList(state.losers, state.losersEmptyMessage, onAction, state.canToggleFavorites, "losers")
        }
        state.status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.testTag("market-status"),
            )
        }
    }
}

@Composable
private fun FavoriteStar(
    coin: ModelMarketCoin,
    enabled: Boolean,
    onAction: (ModelMainAction) -> Unit,
) {
    Text(
        if (coin.favorite) "★" else "☆",
        fontSize = 24.sp,
        color = if (coin.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier =
            if (enabled) {
                Modifier
                    .semantics { contentDescription = "Favorite ${coin.identity.name}" }
                    .clickable(role = Role.Button, onClickLabel = "Toggle favorite ${coin.identity.name}") {
                        onAction(ModelMainAction.ToggleFavorite(coin.identity))
                    }.testTag("favorite-${coin.identity.id}")
            } else {
                Modifier
            },
    )
}

@Composable
internal fun MarketSection(
    title: String,
    eyebrow: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MarketHeading(title, eyebrow)
        content()
    }
}

@Composable
internal fun MarketCard(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    gap: Int = 14,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}

@Composable
internal fun MarketHeading(
    title: String,
    eyebrow: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        if (eyebrow !=
            null
        ) {
            Text(eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
    }
}

@Composable
private fun MarketEmpty(message: String) {
    MarketCard { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun MarketChange(
    value: String,
    badge: Boolean = true,
) {
    val tint =
        when {
            value == "—" || value.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
            value.startsWith("−") ||
                value.startsWith("-") -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
    Text(
        value,
        color = tint,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            if (badge) {
                Modifier
                    .clip(
                        RoundedCornerShape(3.dp),
                    ).background(tint.copy(alpha = .17f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            } else {
                Modifier
            },
    )
}

@Composable
private fun MarketCoinList(
    coins: List<ModelMarketCoin>,
    emptyMessage: String,
    onAction: (ModelMainAction) -> Unit,
    canToggleFavorites: Boolean = false,
    section: String = "list",
) {
    MarketCard(gap = 0) {
        if (coins.isEmpty()) Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        coins.forEachIndexed { index, coin ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onAction(ModelMainAction.OpenCoin(coin.identity))
                    }.testTag("market-$section-${coin.identity.id}")
                    .padding(
                        vertical = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                ModelCoinLogo(coin.identity)
                Column(Modifier.weight(1f)) {
                    Text(coin.identity.ticker, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        coin.identity.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(coin.price, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    MarketChange(coin.change)
                }
                if (canToggleFavorites) FavoriteStar(coin, true, onAction)
            }
            if (index < coins.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
        }
    }
}
