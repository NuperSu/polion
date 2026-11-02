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
)

sealed interface ModelMainAction {
    data class OpenCoin(
        val identity: ModelCoinIdentity,
    ) : ModelMainAction

    data class ToggleFavorite(
        val identity: ModelCoinIdentity,
    ) : ModelMainAction
}

@Composable
fun ModelMain(
    state: ModelMainState,
    wide: Boolean,
    onAction: (ModelMainAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        MarketHeading(state.topCoinsTitle, state.topCoinsEyebrow)
        if (state.topCoins.isEmpty()) {
            MarketEmpty(state.topCoinsEmptyMessage)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.topCoins.forEach { coin ->
                    MarketCard(
                        Modifier.width(if (wide) 210.dp else 142.dp).clickable { onAction(ModelMainAction.OpenCoin(coin.identity)) },
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
        MarketHeading("Favorites")
        MarketCoinList(state.favorites, state.favoritesEmptyMessage, onAction)
        MarketHeading("Trending", "DISCOVER WHAT'S HOT")
        if (state.trending.isEmpty()) {
            MarketEmpty(state.trendingEmptyMessage)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.trending.forEach { coin ->
                    MarketCard(Modifier.width(156.dp).clickable { onAction(ModelMainAction.OpenCoin(coin.identity)) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ModelCoinLogo(coin.identity)
                            Text(
                                "☆",
                                fontSize = 24.sp,
                                modifier =
                                    if (state.canToggleFavorites) {
                                        Modifier.semantics { contentDescription = "Favorite ${coin.identity.name}" }.clickable(
                                            role = Role.Button,
                                            onClickLabel = "Toggle favorite ${coin.identity.name}",
                                        ) { onAction(ModelMainAction.ToggleFavorite(coin.identity)) }
                                    } else {
                                        Modifier
                                    },
                            )
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
        MarketHeading("Top Gainers")
        MarketCoinList(state.gainers, state.gainersEmptyMessage, onAction)
        MarketHeading("Top Losers")
        MarketCoinList(state.losers, state.losersEmptyMessage, onAction)
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
) {
    MarketCard(gap = 0) {
        if (coins.isEmpty()) Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        coins.forEachIndexed { index, coin ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onAction(ModelMainAction.OpenCoin(coin.identity))
                    }.padding(
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
            }
            if (index < coins.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
        }
    }
}
