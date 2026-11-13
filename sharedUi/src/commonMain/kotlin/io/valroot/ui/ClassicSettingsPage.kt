package io.valroot.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId

/** Classic Settings page: sync, appearance, privacy, data, rules, and setup. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.classicSettingsItems(
    setDialog: (String?) -> Unit,
    setMessage: (String?) -> Unit,
    preferences: RefreshPreferences,
    /** Applies a change to the current preferences and returns them. */
    updatePreferences: ((RefreshPreferences) -> RefreshPreferences) -> RefreshPreferences,
    onRevision: () -> Unit,
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    appearance: AppearanceTheme,
    onAppearance: (AppearanceTheme) -> Unit,
    execute: (String, () -> Unit) -> Unit,
    onAutomaticSwitch: (Boolean) -> Unit,
    onCurrency: (AssetId) -> Unit,
    onSetup: (Boolean) -> Unit,
    setupError: String?,
) {
    item {
        Panel {
            Text("Sync settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier
                    .toggleable(
                        value = preferences.automaticRefresh,
                        role = Role.Switch,
                    ) { enabled ->
                        onAutomaticSwitch(enabled)
                    }.testTag("automatic-refresh"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Automatic refresh", Modifier.weight(1f))
                Switch(checked = preferences.automaticRefresh, onCheckedChange = null)
            }
            Text(
                "Prices hourly and wallet history every 30 minutes, subject to platform " +
                    "scheduling. Unchanged wallets are only checked, not downloaded again.",
                color = Muted,
            )
            Row(
                Modifier
                    .toggleable(value = preferences.wifiOnly, role = Role.Switch) { enabled ->
                        val updated = updatePreferences { it.copy(wifiOnly = enabled) }
                        execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                    }.testTag("wifi-only-refresh"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Wi-Fi-only background refresh", Modifier.weight(1f))
                Switch(checked = preferences.wifiOnly, onCheckedChange = null)
            }
            Text(
                if (host.wifiOnlyRefreshSupported) {
                    "Background jobs use an unmetered network. Manual refresh uses the current connection."
                } else {
                    "The Wi-Fi restriction applies to Android background jobs. Desktop " +
                        "refresh uses the current connection."
                },
                color = Muted,
            )
            Text(
                "Pull down to refresh prices across configured portfolios. Wallet history " +
                    "uses the current full scan behavior.",
                color = Muted,
            )
            OutlinedButton(onClick = { setDialog("sync") }) { Text("Refresh wallet history") }
        }
    }

    item {
        Panel {
            Text("Make it yours", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("Appearance", color = Muted, fontSize = 12.sp)
            AppearanceSelector(appearance) { selected ->
                execute("Appearance saved") {
                    app.settings.setTheme(selected.key)
                    onAppearance(selected)
                }
            }
            Text(
                "System follows your device’s light or dark appearance.",
                color = Muted,
                fontSize = 13.sp,
            )
            Text("Display currency", color = Muted, fontSize = 12.sp)
            WrapActions(
                listOf(
                    "USD" to { onCurrency(AssetId("USD")) },
                    "EUR" to { onCurrency(AssetId("EUR")) },
                    "GBP" to { onCurrency(AssetId("GBP")) },
                ),
            )
            Text(
                "Current: ${data.currency.value}. Add a manual quote for each asset and currency you use.",
                color = Muted,
                fontSize = 13.sp,
            )
            TextButton(onClick = { setDialog("price") }) { Text("Manage manual prices →") }
        }
    }
    item {
        Panel {
            Text("Privacy & sync", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                "Public indexers can associate queried addresses with your IP address. " +
                    "Choose your own endpoint or stay offline.",
                color = Muted,
                lineHeight = 22.sp,
            )
            OutlinedButton(onClick = { setDialog("sync") }) { Text("Source & background updates") }
            OutlinedButton(onClick = { setDialog("lock") }) { Text("Set app lock") }
            TextButton(onClick = host::lock, enabled = host.hasLock) { Text("Lock now") }
            if (host.hasLock) {
                TextButton(onClick = {
                    host.removeLockPassword {
                        setMessage(it)
                        onRevision()
                    }
                }) { Text("Remove privacy lock") }
            }
            Text(
                "App lock hides the screen. The local database is stored in plaintext in " +
                    "your app data directory; use device or disk encryption for protection at rest.",
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
    item {
        Panel {
            Text("Your data, in your hands", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                "Create an encrypted backup before replacing or removing local data. Your " +
                    "password cannot be recovered.",
                color = Muted,
                lineHeight = 22.sp,
            )
            WrapActions(
                listOf(
                    "Encrypted backup" to { setDialog("backup") },
                    "Restore backup" to { setDialog("restore") },
                    "All active activity CSV" to
                        {
                            execute("Export ready") {
                                host.saveFile(
                                    "valroot-activities.csv",
                                    app.csv.export().encodeToByteArray(),
                                ) {
                                    setMessage(it)
                                }
                            }
                        },
                ),
            )
            TextButton(
                onClick = { setDialog("wipe") },
            ) { Text("Erase local portfolio data", color = MaterialTheme.colorScheme.error) }
        }
    }
    item {
        Panel {
            Text("Classification rules", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                "Apply a repeatable classification to matching activity. Per-activity " +
                    "corrections take precedence.",
                color = Muted,
            )
            OutlinedButton(onClick = { setDialog("rule") }) { Text("Manage rules") }
        }
    }
    item {
        Panel {
            Text("Your setup", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("Review your local preferences and supported source guidance.", color = Muted)
            OutlinedButton(onClick = { onSetup(false) }) { Text("Resume setup") }
            TextButton(onClick = { onSetup(true) }) { Text("Restart setup") }
            setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (data.ownership.owners.isEmpty()) {
        item {
            TextButton(onClick = {
                execute("Example portfolio loaded. This is sample data.") { app.demo.seed() }
            }) { Text("Explore with sample data") }
        }
    }
    item { PrivacyFootnote() }
}
