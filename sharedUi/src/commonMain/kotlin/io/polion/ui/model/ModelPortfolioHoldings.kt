package io.polion.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ModelHoldingRow(
    val coin: ModelCoinIdentity,
    val quantityAndPrice: String,
    val value: String,
    val change: String,
    val percentChange: String,
    val tone: ModelChangeTone = ModelChangeTone.NEUTRAL,
)

data class ModelPortfolioHoldingsState(
    val holdings: List<ModelHoldingRow>,
    val sortLabel: String,
    val emptyMessage: String = "No holdings yet",
)

@Composable
fun ColumnScope.ModelPortfolioHoldings(
    state: ModelPortfolioHoldingsState,
    onHolding: (ModelCoinIdentity) -> Unit,
    onSort: () -> Unit,
    onView: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "☷",
            color = colors.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clip(
                        CircleShape,
                    ).background(
                        colors.surfaceVariant,
                    ).border(1.dp, colors.surfaceVariant, CircleShape)
                    .clickable(onClick = onView)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
        )
        Text(
            state.sortLabel,
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clip(
                        CircleShape,
                    ).border(1.dp, colors.surfaceVariant, CircleShape)
                    .clickable(onClick = onSort)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
        )
    }
    if (state.holdings.isEmpty()) {
        Text(state.emptyMessage, color = colors.onSurfaceVariant)
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.holdings.forEach { holding ->
                val tint =
                    when (holding.tone) {
                        ModelChangeTone.POSITIVE -> colors.primary
                        ModelChangeTone.NEGATIVE -> colors.error
                        ModelChangeTone.NEUTRAL -> colors.onSurfaceVariant
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onHolding(
                                holding.coin,
                            )
                        }.testTag("coin-${holding.coin.ticker}")
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    ModelCoinLogo(holding.coin)
                    Column(Modifier.weight(1f)) {
                        Text(holding.coin.ticker, fontWeight = FontWeight.Bold, color = colors.onSurface, fontSize = 17.sp)
                        Text(
                            holding.quantityAndPrice,
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(holding.value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(holding.change, color = tint, fontSize = 13.sp)
                            Text(
                                holding.percentChange,
                                color = tint,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier =
                                    Modifier
                                        .clip(
                                            RoundedCornerShape(3.dp),
                                        ).background(tint.copy(alpha = .17f))
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(110.dp))
}
