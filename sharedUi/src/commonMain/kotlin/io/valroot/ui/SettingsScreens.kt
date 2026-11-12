package io.valroot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.GeneralPreferences
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId
import io.valroot.ui.model.SettingsActionRow
import io.valroot.ui.model.SettingsIcon
import io.valroot.ui.model.SettingsMenuRow
import io.valroot.ui.model.SettingsNote
import io.valroot.ui.model.SettingsSectionLabel
import io.valroot.ui.model.SettingsSubHeader
import io.valroot.ui.model.SettingsSwitchRow
import io.valroot.ui.model.SettingsValueRow
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
    val onGeneral: (GeneralPreferences) -> Unit = {},
    val onBiometric: (Boolean) -> Unit = {},
    /** Null removes the key. */
    val onCoingeckoKey: (String?) -> Unit = {},
    /** Null removes the key. Returns the rejection message, or null when the key was saved or removed. */
    val onAlchemyKey: suspend (String?) -> String? = { null },
    /** Null removes the key. Returns the rejection message, or null when the key was saved or removed. */
    val onOneInchKey: suspend (String?) -> String? = { null },
    val onLockDelay: (Long) -> Unit = {},
)

/** Device facts Settings shows but does not own. */
internal data class SettingsDevice(
    val defaultName: String = "This device",
    val biometric: BiometricUnlock = BiometricUnlock.UNSUPPORTED,
    val hasCoingeckoKey: Boolean = false,
    val lockDelayMillis: Long = 0L,
    val hasAlchemyKey: Boolean = false,
    val hasOneInchKey: Boolean = false,
)

private fun lockDelayLabel(millis: Long) =
    when (millis) {
        0L -> "Immediately"
        3_600_000L -> "After 1 hour"
        else -> "After ${millis / 60_000L} minute${if (millis == 60_000L) "" else "s"}"
    }

private val currencies = listOf("USD" to "US dollar (USD)", "EUR" to "Euro (EUR)", "GBP" to "British pound (GBP)")

private fun currencyLabel(code: String?) = currencies.firstOrNull { it.first == code }?.second ?: code.orEmpty()

private val LandingPage.label get() =
    when (this) {
        LandingPage.MAIN -> "Main"
        LandingPage.PORTFOLIO -> "Portfolio"
        LandingPage.INSIGHTS -> "Insights"
    }

/** Settings as a list of destinations; each opens its own page. Rows for features scheduled for a later phase are dimmed. */
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
    general: GeneralPreferences = GeneralPreferences(),
    device: SettingsDevice = SettingsDevice(),
    notifications: @Composable () -> Unit = {},
    csvImports: @Composable () -> Unit = {},
    connections: @Composable () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf("") }
    val back = { section = "" }
    when (section) {
        "general" -> {
            SettingsSubHeader("General", back)
            var currencyMenu by remember { mutableStateOf(false) }
            Box {
                SettingsValueRow("Local Fiat Currency", currencyLabel(data?.currency?.value), tag = "settings-currency") {
                    currencyMenu = true
                }
                DropdownMenu(currencyMenu, { currencyMenu = false }) {
                    currencies.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            currencyMenu = false
                            actions.onCurrency(code)
                        })
                    }
                }
            }
            var secondaryMenu by remember { mutableStateOf(false) }
            Box {
                SettingsValueRow(
                    "Secondary Fiat Currency",
                    general.secondaryCurrency?.let { currencyLabel(it.value) } ?: "None",
                    tag = "settings-secondary-currency",
                ) { secondaryMenu = true }
                DropdownMenu(secondaryMenu, { secondaryMenu = false }) {
                    (listOf<Pair<String?, String>>(null to "None") + currencies.filter { it.first != data?.currency?.value })
                        .forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    secondaryMenu = false
                                    actions.onGeneral(general.copy(secondaryCurrency = code?.let(::AssetId)))
                                },
                                modifier = Modifier.testTag("settings-secondary-${code ?: "none"}"),
                            )
                        }
                }
            }
            var landingMenu by remember { mutableStateOf(false) }
            Box {
                SettingsValueRow("Default Landing", general.landing.label, tag = "settings-landing") { landingMenu = true }
                DropdownMenu(landingMenu, { landingMenu = false }) {
                    LandingPage.entries.forEach { landing ->
                        DropdownMenuItem(
                            text = { Text(landing.label) },
                            onClick = {
                                landingMenu = false
                                actions.onGeneral(general.copy(landing = landing))
                            },
                            modifier = Modifier.testTag("settings-landing-${landing.name.lowercase()}"),
                        )
                    }
                }
            }
            SettingsActionRow("Prices and price sources", tag = "settings-prices") { actions.onDialog("price") }
        }
        "portfolio" -> {
            SettingsSubHeader("Portfolio", back)
            SettingsSwitchRow("Hide 0 holdings", assetPreference.hideEmpty, tag = "settings-hide-empty") {
                actions.onAssetPreference(assetPreference.copy(hideEmpty = it))
            }
            SettingsSwitchRow("Hide small holdings", assetPreference.hideSmall, tag = "settings-hide-small") {
                actions.onAssetPreference(assetPreference.copy(hideSmall = it))
            }
            SettingsSwitchRow("Show portfolio chart", general.showPortfolioChart, tag = "settings-show-chart") {
                actions.onGeneral(general.copy(showPortfolioChart = it))
            }
            SettingsSwitchRow(
                "Use 0 cost basis for rewards",
                general.zeroBasisRewards,
                description =
                    "Transactions recorded as rewards, such as mining, staking, forks and airdrops, count with zero cost " +
                        "instead of their recorded value. Their recorded value is kept.",
                tag = "settings-zero-basis",
            ) { actions.onGeneral(general.copy(zeroBasisRewards = it)) }
            SettingsActionRow("Classification rules", tag = "settings-rules") { actions.onDialog("rule") }
        }
        "notifications" -> {
            SettingsSubHeader("Notifications", back)
            notifications()
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
            SettingsActionRow("Connections FAQ", tag = "settings-connections-help") { section = "help" }
        }
        "keys" -> {
            SettingsSubHeader("API Keys", back)
            SettingsSectionLabel("Exchanges")
            SettingsNote(
                "Each Bybit or Binance account has its own read-only key: add the account from Add, and replace " +
                    "its key from the account. Kraken prices need no key.",
            )
            SettingsActionRow("MEXC", enabled = false)
            SettingsSectionLabel("External services")
            var editing by remember { mutableStateOf(false) }
            SettingsValueRow(
                "CoinGecko",
                if (device.hasCoingeckoKey) "Demo key added" else "Not set · public request limit",
                tag = "settings-coingecko-key",
            ) { editing = true }
            SettingsNote(
                "Market lists and price history work without a key. A free CoinGecko Demo key raises the request limit. " +
                    "Keys stay on this device and are not included in backups.",
            )
            if (editing) {
                var key by remember { mutableStateOf("") }
                val valid = key.trim().matches(Regex("[A-Za-z0-9_-]{10,100}"))
                AlertDialog(
                    onDismissRequest = { editing = false },
                    title = { Text("CoinGecko API key") },
                    text = {
                        OutlinedTextField(
                            key,
                            { key = it },
                            singleLine = true,
                            label = { Text("Demo API key") },
                            isError = key.isNotEmpty() && !valid,
                            visualTransformation =
                                androidx.compose.ui.text.input
                                    .PasswordVisualTransformation(),
                            keyboardOptions = SecretKeyboard,
                            modifier = Modifier.testTag("settings-coingecko-key-field"),
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = valid, onClick = {
                            editing = false
                            actions.onCoingeckoKey(key.trim())
                        }, modifier = Modifier.testTag("settings-coingecko-key-save")) { Text("Save") }
                    },
                    dismissButton = {
                        Row {
                            if (device.hasCoingeckoKey) {
                                TextButton(onClick = {
                                    editing = false
                                    actions.onCoingeckoKey(null)
                                }, modifier = Modifier.testTag("settings-coingecko-key-remove")) { Text("Remove") }
                            }
                            TextButton(onClick = { editing = false }) { Text("Cancel") }
                        }
                    },
                )
            }
            var editingAlchemy by remember { mutableStateOf(false) }
            SettingsValueRow(
                "Alchemy",
                if (device.hasAlchemyKey) "Set" else "Not set",
                tag = "settings-alchemy-key",
            ) { editingAlchemy = true }
            SettingsNote(ALCHEMY_KEY_HELP)
            if (editingAlchemy) {
                AlchemyKeyDialog(device.hasAlchemyKey, actions.onAlchemyKey) { editingAlchemy = false }
            }
            var editingOneInch by remember { mutableStateOf(false) }
            SettingsValueRow(
                "1inch",
                if (device.hasOneInchKey) "Set" else "Not set",
                tag = "settings-oneinch-key",
            ) { editingOneInch = true }
            SettingsNote(ONE_INCH_KEY_HELP)
            if (editingOneInch) {
                AlchemyKeyDialog(
                    device.hasOneInchKey,
                    actions.onOneInchKey,
                    "1inch",
                    ONE_INCH_KEY_HELP,
                    "oneinch",
                ) { editingOneInch = false }
            }
        }
        "security" -> {
            SettingsSubHeader("Security", back)
            SettingsSwitchRow(
                "Passcode Lock",
                hasLock,
                description = "Hides Valroot behind a password. Use device encryption to protect the stored data.",
                tag = "settings-lock",
            ) { if (it) actions.onDialog("lock") else actions.onRemoveLock() }
            var delayMenu by remember { mutableStateOf(false) }
            Box {
                SettingsValueRow(
                    "Lock after leaving Valroot",
                    lockDelayLabel(device.lockDelayMillis),
                    enabled = hasLock,
                    tag = "settings-lock-delay",
                ) { delayMenu = true }
                DropdownMenu(delayMenu, { delayMenu = false }) {
                    io.valroot.core.application.LOCK_DELAYS.forEach { millis ->
                        DropdownMenuItem(
                            text = { Text(lockDelayLabel(millis)) },
                            onClick = {
                                delayMenu = false
                                actions.onLockDelay(millis)
                            },
                            modifier = Modifier.testTag("settings-lock-delay-$millis"),
                        )
                    }
                }
            }
            SettingsActionRow("Lock now", enabled = hasLock, tag = "settings-lock-now") { actions.onLockNow() }
            SettingsSwitchRow(
                "Fingerprint Authentication",
                device.biometric == BiometricUnlock.ON,
                enabled = hasLock && device.biometric in setOf(BiometricUnlock.ON, BiometricUnlock.OFF),
                description =
                    when {
                        device.biometric == BiometricUnlock.UNSUPPORTED -> "Not available on this device. Unlock with your password."
                        device.biometric == BiometricUnlock.NOT_ENROLLED -> "Add a fingerprint in your device settings first."
                        !hasLock -> "Turn on Passcode Lock first."
                        else -> "Unlock with a fingerprint; the password still works."
                    },
                tag = "settings-biometric",
            ) { actions.onBiometric(it) }
        }
        "data" -> {
            SettingsSubHeader("Devices & Data", back)
            SettingsSectionLabel("Devices")
            var naming by remember { mutableStateOf(false) }
            SettingsValueRow("Device Name", general.deviceName ?: device.defaultName, tag = "settings-device-name") { naming = true }
            if (naming) {
                var name by remember { mutableStateOf(general.deviceName ?: device.defaultName) }
                val valid = name.trim().length <= 40 && name.none { it.isISOControl() }
                AlertDialog(
                    onDismissRequest = { naming = false },
                    title = { Text("Device name") },
                    text = {
                        OutlinedTextField(
                            name,
                            { name = it },
                            singleLine = true,
                            isError = !valid,
                            supportingText = { Text("Up to 40 characters. Leave empty to use \"${device.defaultName}\".") },
                            modifier = Modifier.testTag("settings-device-name-field"),
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = valid, onClick = {
                            naming = false
                            actions.onGeneral(general.copy(deviceName = name.trim().takeIf { it.isNotEmpty() && it != device.defaultName }))
                        }, modifier = Modifier.testTag("settings-device-name-save")) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
                )
            }
            SettingsActionRow("Transfer Data", enabled = false)
            SettingsActionRow("Sync Data", enabled = false)
            SettingsSectionLabel("Data")
            SettingsActionRow("Create Encrypted Backup", tag = "settings-backup") { actions.onDialog("backup") }
            SettingsActionRow("Restore From Backup", tag = "settings-restore") { actions.onDialog("restore") }
            SettingsActionRow("Full Transaction History", tag = "settings-history") { onAdvanced(Page.ACTIVITY) }
            SettingsActionRow("Export Data (CSV)", tag = "settings-export") { actions.onExportCsv() }
            SettingsActionRow("CSV Import History", tag = "settings-csv-history") { section = "csv" }
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
            SettingsActionRow("Help", tag = "settings-help") { section = "help" }
            SettingsActionRow("Resume setup", tag = "settings-resume-setup") { actions.onSetup(false) }
            SettingsActionRow("Restart setup", tag = "settings-restart-setup") { actions.onSetup(true) }
        }
        "csv" -> {
            SettingsSubHeader("CSV Import History") { section = "data" }
            csvImports()
        }
        "help" -> {
            SettingsSubHeader("Help", back)
            SettingsHelp()
        }
        else -> {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    "Theme",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                AppearanceSelector(appearance, actions.onAppearance)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SettingsMenuRow(SettingsIcon.GENERAL, "General", "settings-general") { section = "general" }
            SettingsMenuRow(SettingsIcon.PORTFOLIO, "Portfolio", "settings-portfolio") { section = "portfolio" }
            SettingsMenuRow(SettingsIcon.NOTIFICATIONS, "Notifications", "settings-notifications") { section = "notifications" }
            SettingsMenuRow(SettingsIcon.CONNECTIONS, "Connections", "settings-connections") { section = "connections" }
            SettingsMenuRow(SettingsIcon.KEYS, "API Keys", "settings-keys") { section = "keys" }
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
