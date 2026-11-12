package io.valroot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetSort
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.market.PriceStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssetsCard(
    data: PortfolioSnapshot,
    preference: AssetListPreference,
    busy: Boolean,
    onPreference: (AssetListPreference) -> Unit,
    onAsset: (AssetValuation) -> Unit,
    onPrice: () -> Unit,
) {
    val sortByValue = preference.sort == AssetSort.VALUE
    val sorted =
        remember(data.valuations, preference) {
            displayedAssets(data.valuations, preference)
        }
    Panel {
        SectionTitle("Your assets", "Prices") { onPrice() }
        if (data.valuations.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sortByValue, enabled = !busy, onClick = {
                    onPreference(preference.copy(sort = AssetSort.VALUE))
                }, label = { Text("Value") })
                FilterChip(selected = !sortByValue, enabled = !busy, onClick = {
                    onPreference(preference.copy(sort = AssetSort.NAME))
                }, label = { Text("Name") })
                FilterChip(selected = preference.hideEmpty, enabled = !busy, onClick = {
                    onPreference(preference.copy(hideEmpty = !preference.hideEmpty))
                }, label = { Text("Hide empty") }, modifier = Modifier.testTag("hide-empty-assets"))
                FilterChip(selected = preference.hideSmall, enabled = !busy, onClick = {
                    onPreference(preference.copy(hideSmall = !preference.hideSmall))
                }, label = { Text("Hide under 1 ${data.currency.value}") }, modifier = Modifier.testTag("hide-small-assets"))
            }
            if (preference.hideSmall) {
                Text(
                    "Small positions with fresh prices are hidden from this list only. Stale and unpriced positions remain visible.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
        if (data.valuations.isEmpty()) {
            Text(
                "Confirmed assets will appear after you add activity or sync an account.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 21.sp,
            )
        }
        if (data.valuations.isNotEmpty() && sorted.isEmpty()) {
            Text("No positions match these display filters. Turn off a filter to see them.", color = Muted, fontSize = 13.sp)
        }
        sorted.forEach { asset ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) {
                        onAsset(asset)
                    }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoinBadge(if (asset.metadata?.symbol == "BTC") "₿" else asset.metadata?.symbol?.take(1) ?: "?")
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        asset.metadata?.name ?: asset.asset.value,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${asset.metadata?.let {
                            "${formatUnits(
                                asset.amount.atomicUnits,
                                it.decimals,
                            )} ${it.symbol}"
                        } ?: "${asset.amount.atomicUnits} atomic units"}",
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                    asset.price?.let { quote ->
                        Text("${quotePrice(quote.price.value, quote.price.quoteAssetId)} per unit", color = Muted, fontSize = 11.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(asset.value?.let { money(it, data.currency) } ?: "Unpriced", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        asset.priceStatus.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() } +
                            if (asset.price?.manual == true) " · manual quote" else "",
                        fontSize = 10.sp,
                        color =
                            if (asset.priceStatus ==
                                PriceStatus.FRESH
                            ) {
                                Teal
                            } else {
                                Orange
                            },
                    )
                }
            }
        }
    }
}
