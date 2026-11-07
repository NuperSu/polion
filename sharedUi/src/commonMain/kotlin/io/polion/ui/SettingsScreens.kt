package io.polion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.AssetListPreference
import io.polion.core.application.PortfolioSnapshot
import io.polion.ui.model.SettingsActionRow
import io.polion.ui.model.SettingsIcon
import io.polion.ui.model.SettingsMenuRow
import io.polion.ui.model.SettingsNote
import io.polion.ui.model.SettingsSectionLabel
import io.polion.ui.model.SettingsSubHeader
import io.polion.ui.model.SettingsSwitchRow
import io.polion.ui.model.SettingsValueRow
import kotlin.time.Clock

/** Everything the Settings screens can change; the caller owns persistence, dialogs and navigation. */
internal class SettingsActions(
    val onAutomatic: (Boolean) -> Unit,
    val onWifi: (Boolean) -> Unit,
    val onCurrency: (String) -> Unit,
    val onAppearance: (AppearanceTheme) -> Unit,
    val onAssetPreference: (AssetListPreference) -> Unit,
    val onDialog: (String) -> Unit,
    val onExportCsv: () -> Unit,
    val onConnect: () -> Unit,
    val onLockNow: () -> Unit,
    val onRemoveLock: () -> Unit,
    val onSetup: (Boolean) -> Unit,
    val onRemoveExample: () -> Unit = {},
)

private val currencies = listOf("USD" to "US dollar (USD)", "EUR" to "Euro (EUR)", "GBP" to "British pound (GBP)")

/** Settings as a list of destinations; each opens its own page. Rows for features that do not exist yet are dimmed. */
@Composable
internal fun ColumnScope.ProductSettings(
    data: PortfolioSnapshot?,
    preferences: RefreshPreferences,
    wifiOnlyRefreshSupported: Boolean,
    appearance: AppearanceTheme,
    assetPreference: AssetListPreference,
    hasLock: Boolean,
    onAdvanced: (Page) -> Unit,
    actions: SettingsActions,
    connections: @Composable () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf("") }
    val back = { section = "" }
    when (section) {
        "general" -> {
            SettingsSubHeader("General", back)
            SettingsValueRow("Language", "English", enabled = false)
            var currencyMenu by remember { mutableStateOf(false) }
            Box {
                SettingsValueRow(
                    "Local Fiat Currency",
                    currencies.firstOrNull { it.first == data?.currency?.value }?.second ?: data?.currency?.value.orEmpty(),
                    tag = "settings-currency",
                ) { currencyMenu = true }
                DropdownMenu(currencyMenu, { currencyMenu = false }) {
                    currencies.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            currencyMenu = false
                            actions.onCurrency(code)
                        })
                    }
                }
            }
            SettingsValueRow("Secondary Fiat Currency", "None", enabled = false)
            SettingsValueRow("Default Landing", "Main", enabled = false)
            SettingsActionRow("Prices and price sources", tag = "settings-prices") { actions.onDialog("price") }
        }
        "view" -> {
            SettingsSubHeader("View Options", back)
            Text("Theme", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            AppearanceSelector(appearance, actions.onAppearance)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 12.dp))
            SettingsSwitchRow("Monochrome Asset Icons", checked = false, enabled = false)
            SettingsSwitchRow("Invert Red/Green", checked = false, enabled = false)
        }
        "portfolio" -> {
            SettingsSubHeader("Portfolio", back)
            SettingsSwitchRow("Hide 0 holdings", assetPreference.hideEmpty, tag = "settings-hide-empty") {
                actions.onAssetPreference(assetPreference.copy(hideEmpty = it))
            }
            SettingsSwitchRow("Hide small holdings", assetPreference.hideSmall, tag = "settings-hide-small") {
                actions.onAssetPreference(assetPreference.copy(hideSmall = it))
            }
            SettingsSwitchRow("Show portfolio chart", checked = true, enabled = false)
            SettingsSwitchRow(
                "Use 0 cost basis for forks, mining and airdrops",
                checked = false,
                enabled = false,
                description = "Profit calculations would use zero cost basis for these receipts instead of their market value.",
            )
            SettingsActionRow("Classification rules", tag = "settings-rules") { actions.onDialog("rule") }
        }
        "notifications" -> {
            SettingsSubHeader("Notifications", back)
            SettingsNote("Notifications and price alerts are not available yet.")
            SettingsSectionLabel("Portfolio")
            SettingsSwitchRow("Daily Portfolio Update", false, enabled = false, description = "Daily portfolio performance and top movers")
            SettingsSectionLabel("Market Data")
            SettingsSwitchRow("Significant Price Changes", false, enabled = false, description = "Large moves for coins you follow")
            SettingsSwitchRow(
                "High/Low Prices",
                false,
                enabled = false,
                description = "All-time and 52-week highs and lows for coins you follow",
            )
        }
        "connections" -> {
            SettingsSubHeader("Connections", back)
            SettingsSectionLabel("All connections")
            connections()
            SettingsSectionLabel("More")
            SettingsActionRow("Add New Connection", tag = "settings-add-connection") { actions.onConnect() }
            SettingsSwitchRow(
                "Auto-sync",
                preferences.automaticRefresh,
                description = "Prices hourly and wallets every 30 minutes",
            ) { actions.onAutomatic(it) }
            SettingsSwitchRow(
                "Wi-Fi only in background",
                preferences.wifiOnly,
                enabled = wifiOnlyRefreshSupported,
                tag = "wifi-only-refresh",
            ) { actions.onWifi(it) }
            SettingsActionRow("Connections FAQ", enabled = false)
        }
        "security" -> {
            SettingsSubHeader("Security", back)
            SettingsSwitchRow(
                "Passcode Lock",
                hasLock,
                description = "Hides Polion behind a password. Use device encryption to protect the stored data.",
                tag = "settings-lock",
            ) { if (it) actions.onDialog("lock") else actions.onRemoveLock() }
            SettingsActionRow("Lock now", enabled = hasLock, tag = "settings-lock-now") { actions.onLockNow() }
            SettingsSwitchRow("Fingerprint Authentication", false, enabled = false)
        }
        "data" -> {
            SettingsSubHeader("Devices & Data", back)
            SettingsSectionLabel("Devices")
            SettingsValueRow("Device Name", "This device", enabled = false)
            SettingsActionRow("Transfer Data", enabled = false)
            SettingsActionRow("Sync Data", enabled = false)
            SettingsSectionLabel("Data")
            SettingsActionRow("Create Encrypted Backup", tag = "settings-backup") { actions.onDialog("backup") }
            SettingsActionRow("Restore From Backup", tag = "settings-restore") { actions.onDialog("restore") }
            SettingsActionRow("Full Transaction History", tag = "settings-history") { onAdvanced(Page.ACTIVITY) }
            SettingsActionRow("Export Data (CSV)", tag = "settings-export") { actions.onExportCsv() }
            SettingsActionRow("CSV Import History", enabled = false)
            if (data?.sampleData == true) {
                var confirm by remember { mutableStateOf(false) }
                SettingsActionRow("Remove example data", tag = "settings-remove-example") { confirm = true }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Remove example data?") },
                        text = {
                            Text(
                                "Example transactions are reversed and kept in history, example prices stop valuing your coins, " +
                                    "and the example account and portfolio are removed from view. Your own records are unchanged.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirm = false
                                actions.onRemoveExample()
                            }, modifier = Modifier.testTag("settings-remove-example-confirm")) { Text("Remove") }
                        },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
            SettingsActionRow("Erase local portfolio data", tag = "settings-erase", color = MaterialTheme.colorScheme.error) {
                actions.onDialog("wipe")
            }
        }
        "support" -> {
            SettingsSubHeader("Support", back)
            SettingsActionRow("Resume setup", tag = "settings-resume-setup") { actions.onSetup(false) }
            SettingsActionRow("Restart setup", tag = "settings-restart-setup") { actions.onSetup(true) }
            SettingsActionRow("Help and FAQ", enabled = false)
            SettingsActionRow("Contact support", enabled = false)
        }
        else -> {
            SettingsMenuRow(SettingsIcon.GENERAL, "General", "settings-general") { section = "general" }
            SettingsMenuRow(SettingsIcon.VIEW, "View Options", "settings-view") { section = "view" }
            SettingsMenuRow(SettingsIcon.PORTFOLIO, "Portfolio", "settings-portfolio") { section = "portfolio" }
            SettingsMenuRow(SettingsIcon.NOTIFICATIONS, "Notifications", "settings-notifications") { section = "notifications" }
            SettingsMenuRow(SettingsIcon.CONNECTIONS, "Connections", "settings-connections") { section = "connections" }
            SettingsMenuRow(SettingsIcon.SECURITY, "Security", "settings-security") { section = "security" }
            SettingsMenuRow(SettingsIcon.DATA, "Devices & Data", "settings-data") { section = "data" }
            SettingsMenuRow(SettingsIcon.SUPPORT, "Support", "settings-support") { section = "support" }
            SettingsSwitchRow(
                "Auto-sync",
                preferences.automaticRefresh,
                description = "Refresh prices and wallet history automatically",
                tag = "automatic-refresh",
            ) { actions.onAutomatic(it) }
        }
    }
}

/** "just now", "5 minutes ago", "3 hours ago" or "2 days ago" for a past epoch-millis time. */
internal fun relativeAgo(millis: Long): String {
    val minutes = ((Clock.System.now().toEpochMilliseconds() - millis) / 60_000L).coerceAtLeast(0)

    fun plural(
        count: Long,
        unit: String,
    ) = "$count $unit${if (count == 1L) "" else "s"} ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> plural(minutes, "minute")
        minutes < 48 * 60 -> plural(minutes / 60, "hour")
        else -> plural(minutes / (24 * 60), "day")
    }
}
