package io.polion.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val foreground @Composable get() = MaterialTheme.colorScheme.onSurface
private val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val card @Composable get() = MaterialTheme.colorScheme.surface
private val divider @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val green @Composable get() = MaterialTheme.colorScheme.primary

data class ModelAccountRow(
    val name: String,
    val description: String,
    val refreshStatus: String,
)

data class ModelAccountsState(
    val accounts: List<ModelAccountRow>,
    val emptyMessage: String = "No accounts yet",
)

data class ModelSettingsState(
    val automatic: Boolean,
    val wifiOnly: Boolean,
    val description: String,
    val footer: String? = null,
)

@Composable
fun ColumnScope.ModelAccounts(
    state: ModelAccountsState,
    onConnect: () -> Unit,
) {
    Text("Your accounts", color = foreground, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    if (state.accounts.isEmpty()) Text(state.emptyMessage, color = muted)
    state.accounts.forEach { account ->
        Column(
            Modifier.fillMaxWidth().background(card, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(account.name, fontWeight = FontWeight.Bold)
            Text(account.description, color = muted)
            Text(account.refreshStatus, color = green, fontSize = 12.sp)
        }
    }
    Text(
        "＋ Connect wallet",
        color = muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .border(
                    1.dp,
                    divider,
                    RoundedCornerShape(50),
                ).clickable(onClick = onConnect)
                .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@Composable
fun ColumnScope.ModelSettings(
    state: ModelSettingsState,
    onAutomatic: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
) {
    Text("Sync settings", color = foreground, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    Column(
        Modifier.fillMaxWidth().background(card, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic refresh", fontWeight = FontWeight.Bold)
                Text("Prices and wallet history", fontSize = 12.sp, color = muted)
            }
            Switch(state.automatic, onAutomatic, modifier = Modifier.testTag("automatic-refresh"))
        }
        HorizontalDivider(color = divider)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only in background", fontWeight = FontWeight.Bold)
                Text("Manual refresh can use your connection", fontSize = 12.sp, color = muted)
            }
            Switch(state.wifiOnly, onWifi)
        }
    }
    Text(state.description, color = muted, fontSize = 13.sp)
    state.footer?.let { Text(it, color = muted, fontSize = 12.sp) }
}
