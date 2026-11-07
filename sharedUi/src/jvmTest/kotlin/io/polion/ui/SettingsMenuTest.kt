package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetListPreference
import io.polion.core.application.GeneralPreferences
import io.polion.core.application.LandingPage
import io.polion.core.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsMenuTest {
    @Test fun menuOpensSectionsAutoSyncWorksAndPlannedRowsAreInert() {
        val calls = mutableListOf<String>()
        val actions =
            SettingsActions(
                onAutomatic = { calls += "automatic:$it" },
                onWifi = { calls += "wifi:$it" },
                onCurrency = { calls += "currency:$it" },
                onAppearance = { calls += "appearance:${it.key}" },
                onAssetPreference = { calls += "hide-empty:${it.hideEmpty}" },
                onDialog = { calls += "dialog:$it" },
                onExportCsv = { calls += "export" },
                onConnect = { calls += "connect" },
                onLockNow = { calls += "lock-now" },
                onRemoveLock = { calls += "remove-lock" },
                onSetup = { calls += "setup:$it" },
            )
        runDesktopComposeUiTest(width = 390, height = 1600) {
            setContent {
                PolionTheme(AppearanceTheme.DARK) {
                    Column {
                        ProductSettings(null, RefreshPreferences(), false, AppearanceTheme.DARK, AssetListPreference(), false, {}, actions)
                    }
                }
            }
            onNodeWithText("Subscriptions").assertDoesNotExist()
            onNodeWithTag("automatic-refresh").performClick()
            onNodeWithTag("settings-portfolio").performClick()
            onNodeWithTag("settings-hide-empty").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-connections").performClick()
            onNodeWithTag("settings-add-connection").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-data").performClick()
            onNodeWithText("CSV Import History").performClick()
            onNodeWithTag("settings-export").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-security").performClick()
            onNodeWithTag("settings-lock-now").assertIsNotEnabled()
            onNodeWithTag("settings-biometric").performClick()
            onNodeWithText("Not available on this device. Unlock with your password.").assertExists()
            assertEquals(listOf("automatic:true", "hide-empty:true", "connect", "export"), calls)
        }
    }

    @Test fun themeIsOnTheMainListAndGeneralRowsSaveTheirChoice() {
        val calls = mutableListOf<String>()
        val saved = mutableListOf<GeneralPreferences>()
        var general by mutableStateOf(GeneralPreferences())
        val actions =
            SettingsActions(
                onAutomatic = {},
                onWifi = {},
                onCurrency = {},
                onAppearance = { calls += "appearance:${it.key}" },
                onAssetPreference = {},
                onDialog = {},
                onExportCsv = {},
                onConnect = {},
                onLockNow = {},
                onRemoveLock = {},
                onSetup = {},
                onGeneral = {
                    saved += it
                    general = it
                },
                onBiometric = { calls += "biometric:$it" },
                onCoingeckoKey = { calls += "coingecko:$it" },
                onLockDelay = { calls += "lock-delay:$it" },
            )
        runDesktopComposeUiTest(width = 390, height = 1600) {
            setContent {
                PolionTheme(AppearanceTheme.DARK) {
                    Column {
                        ProductSettings(
                            null,
                            RefreshPreferences(),
                            false,
                            AppearanceTheme.DARK,
                            AssetListPreference(),
                            true,
                            {},
                            actions,
                            general = general,
                            device = SettingsDevice("Pixel", BiometricUnlock.OFF),
                        )
                    }
                }
            }
            onNodeWithTag("appearance-light").performClick()
            onNodeWithText("View Options").assertDoesNotExist()
            onNodeWithTag("settings-general").performClick()
            onNodeWithText("Language").assertDoesNotExist()
            onNodeWithTag("settings-secondary-currency").performClick()
            onNodeWithTag("settings-secondary-EUR").performClick()
            onNodeWithText("Euro (EUR)").assertExists()
            onNodeWithTag("settings-landing").performClick()
            onNodeWithTag("settings-landing-portfolio").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-portfolio").performClick()
            onNodeWithTag("settings-show-chart").performClick()
            onNodeWithTag("settings-zero-basis").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-data").performClick()
            onNodeWithText("Pixel").assertExists()
            onNodeWithTag("settings-device-name").performClick()
            onNodeWithTag("settings-device-name-field").performTextReplacement("Work phone")
            onNodeWithTag("settings-device-name-save").performClick()
            onNodeWithText("Work phone").assertExists()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-security").performClick()
            onNodeWithTag("settings-biometric").performClick()
            onNodeWithText("Immediately").assertExists()
            onNodeWithTag("settings-lock-delay").performClick()
            onNodeWithTag("settings-lock-delay-900000").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-keys").performClick()
            onNodeWithText("Not set · public request limit").assertExists()
            onNodeWithTag("settings-coingecko-key").performClick()
            onNodeWithTag("settings-coingecko-key-field").performTextReplacement("bad key")
            onNodeWithTag("settings-coingecko-key-save").assertIsNotEnabled()
            onNodeWithTag("settings-coingecko-key-field").performTextReplacement("CG-abcdefghijklmnop")
            onNodeWithTag("settings-coingecko-key-save").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-support").performClick()
            onNodeWithTag("settings-help").performClick()
            onNodeWithTag("help-markets").assertExists()
            onNodeWithTag("help-wallets").assertExists()
            onNodeWithTag("help-backups").assertExists()
            assertEquals(listOf("appearance:light", "biometric:true", "lock-delay:900000", "coingecko:CG-abcdefghijklmnop"), calls)
            assertEquals(
                GeneralPreferences(LandingPage.PORTFOLIO, AssetId("EUR"), false, true, "Work phone"),
                saved.last(),
            )
        }
    }
}
