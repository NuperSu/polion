package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioSnapshot

@Composable
internal fun HealthCard(
    data: PortfolioSnapshot,
    onSync: () -> Unit,
    onUtxoReview: () -> Unit,
) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Data health", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("◎", color = Teal, fontSize = 22.sp)
        }
        Text("Clarity before confidence.", color = Muted, fontSize = 13.sp)
        data.sourceStatus.lastSuccessfulRefresh?.let { Text("Last saved sync: ${date(it)} UTC", fontSize = 11.sp, color = Muted) }
        if (data.sourceStatus.utxoMismatchCount > 0) {
            Text(
                "UTXO comparison needs review: ${data.sourceStatus.utxoMismatchCount} differences or missing parents. " +
                    "History coverage remains partial.",
                color = Orange,
                fontSize = 12.sp,
            )
        }
        if (data.sourceStatus.pendingCapped > 0) {
            Text(
                "${data.sourceStatus.pendingCapped} address(es) have more unconfirmed transactions than the provider lists; " +
                    "50 are shown and the rest appear once they confirm.",
                color = Orange,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onUtxoReview, contentPadding = PaddingValues(0.dp)) {
            Text("Review UTXO comparison →", fontSize = 12.sp)
        }
        data.sourceStatus.lastError?.let { Text("Sync incomplete · saved data retained", color = Orange, fontSize = 12.sp) }
        HorizontalDivider(color = Line)
        HealthLine("History", if (data.coverage.name == "COMPLETE") "Complete" else "Unverified", data.coverage.name == "COMPLETE")
        HealthLine("Pending activity", data.pendingCount.toString(), false)
        val missing = pricingSummary(data.valuations).missing
        HealthLine("Unpriced assets", missing.toString(), missing == 0)
        TextButton(onClick = onSync, contentPadding = PaddingValues(0.dp)) { Text("Review sources & sync →", fontSize = 12.sp) }
    }
}

@Composable private fun HealthLine(
    label: String,
    value: String,
    positive: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (positive) Teal else Orange, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
