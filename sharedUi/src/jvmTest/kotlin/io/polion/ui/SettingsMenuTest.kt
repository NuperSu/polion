package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetListPreference
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
                onWalletHistory = { calls += "history" },
                onAccounts = { calls += "accounts" },
                onRefreshHistory = { calls += "refresh" },
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
            assertEquals(listOf("automatic:true", "hide-empty:true", "connect", "export"), calls)
        }
    }
}
