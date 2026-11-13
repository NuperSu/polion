package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotificationSettingsTest {
    @Test fun notificationSwitchesPersistAndTellTheHostOnEachChange() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                var notified = 0
                val host =
                    object : PlatformActions by delegate {
                        override fun notificationSettingsChanged() {
                            notified++
                        }
                    }
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { Column { NotificationSettingsSection(app, host) } }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("notify-daily").fetchSemanticsNodes().isNotEmpty() }
                    // Market data is off, so price notifications say what they need.
                    onNodeWithText("Price notifications need market data on Main. The daily update still works.").assertIsDisplayed()

                    onNodeWithTag("notify-daily").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.notifications.preferences().dailyUpdate }
                    onNodeWithTag("notify-moves").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.notifications.preferences().priceMoves }
                    onNodeWithTag("notify-extremes").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.notifications.preferences().highsAndLows }
                    onNodeWithTag("notify-daily").performClick()
                    waitUntil(timeoutMillis = 10_000) { !app.notifications.preferences().dailyUpdate }
                    // The host hears about each change after it is saved.
                    waitUntil(timeoutMillis = 10_000) { notified == 4 }
                    assertEquals(4, notified)

                    onNodeWithText("No price alerts are on. Add alerts from a coin's Alerts tab.").assertIsDisplayed()
                    onNodeWithText("No notifications yet.").assertIsDisplayed()
                    onNodeWithTag("notifications-check").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No new notifications.").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(4, notified, "Checking for notifications does not change settings")
                }
            }
        }
    }
}
