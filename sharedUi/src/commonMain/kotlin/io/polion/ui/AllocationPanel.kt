package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.AllocationExclusionReason
import io.polion.core.application.AssetAllocationProjection
import io.polion.core.holdings.BalanceCoverage

@Composable
internal fun AllocationPanel(allocation: AssetAllocationProjection) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("asset-allocation")
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Asset allocation", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Positive priced holdings in the selected portfolio. Shares use the included subtotal.", color = Muted, fontSize = 12.sp)
        Text(
            when (allocation.coverage) {
                BalanceCoverage.COMPLETE -> "History coverage: complete"
                BalanceCoverage.PARTIAL -> "History coverage: partial · allocation may be incomplete"
                BalanceCoverage.UNKNOWN -> "History coverage: unknown · allocation may be incomplete"
            },
            fontSize = 12.sp,
            color = if (allocation.coverage == BalanceCoverage.COMPLETE) Muted else Orange,
        )
        if (allocation.slices.isEmpty()) {
            Text("No positive priced holdings", fontWeight = FontWeight.Medium)
            Text("Allocation is unavailable until a positive holding has a positive price.", fontSize = 12.sp, color = Muted)
        } else {
            Text(
                "Included subtotal · ${allocation.displayedPricedSubtotal} ${allocation.currency.value}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("allocation-subtotal"),
            )
            allocation.slices.forEach { slice ->
                val valuation = slice.valuation
                Column(
                    Modifier.fillMaxWidth().testTag("allocation-${valuation.asset.value}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(valuation.metadata?.let { "${it.name} (${it.symbol})" } ?: valuation.asset.value, fontWeight = FontWeight.Medium)
                    Text("${slice.percentage}% · ${slice.displayedValue} ${allocation.currency.value}", fontSize = 13.sp)
                    Text(
                        "${valuation.priceStatus.name.lowercase()} price${if (valuation.price?.manual == true) " · manual observation" else ""}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    LinearProgressIndicator(
                        // Floating point is confined to drawing; financial labels come from the exact projection.
                        progress = { (slice.percentage.toString().toFloat() / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Teal,
                        trackColor = Mint,
                    )
                }
            }
            Text("Displayed shares total 100.00%; rounding is distributed across holdings.", fontSize = 11.sp, color = Muted)
        }
        if (allocation.excluded.isNotEmpty()) {
            HorizontalDivider()
            Text("Excluded from allocation", fontWeight = FontWeight.SemiBold)
            allocation.excluded.forEach { exclusion ->
                val valuation = exclusion.valuation
                val reason =
                    when (exclusion.reason) {
                        AllocationExclusionReason.MISSING_PRICE -> "price unavailable"
                        AllocationExclusionReason.NON_POSITIVE_AMOUNT -> "zero or negative holding"
                        AllocationExclusionReason.NON_POSITIVE_VALUE -> "zero or negative priced value"
                    }
                Text("${valuation.metadata?.symbol ?: valuation.asset.value} · $reason", fontSize = 12.sp, color = Muted)
            }
            Text("Excluded holdings have no share in this subtotal. Unpriced values remain unknown.", fontSize = 11.sp, color = Muted)
        }
    }
}
