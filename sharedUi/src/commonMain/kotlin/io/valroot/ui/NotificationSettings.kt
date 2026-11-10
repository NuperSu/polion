package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.alerts.NotificationKind
import io.valroot.core.alerts.NotificationMessage
import io.valroot.core.alerts.NotificationPreferences
import io.valroot.core.application.PortfolioApplication
import io.valroot.ui.model.SettingsNote
import io.valroot.ui.model.SettingsSectionLabel
import io.valroot.ui.model.SettingsSwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HISTORY_SHOWN = 20

private data class NotificationSettingsLoad(
    val preferences: NotificationPreferences,
    val marketConsent: Boolean,
    val alertsOn: Int,
    val history: List<NotificationMessage>,
)

/** The Notifications page of Settings: the three notification switches, alert count and recent notifications. */
@Composable
internal fun NotificationSettingsSection(
    app: PortfolioApplication,
    host: PlatformActions,
) {
    val scope = rememberCoroutineScope()
    var loaded by remember(app) { mutableStateOf<NotificationSettingsLoad?>(null) }
    var loadFailed by remember(app) { mutableStateOf(false) }
    var reload by remember(app) { mutableStateOf(0) }
    var notice by remember(app) { mutableStateOf<String?>(null) }
    LaunchedEffect(app, reload) {
        try {
            loaded =
                withContext(host.dispatcher) {
                    NotificationSettingsLoad(
                        preferences = app.notificationPreferences(),
                        marketConsent = app.marketDataConsent(),
                        alertsOn = app.priceAlerts().count { it.enabled },
                        history = app.notificationHistory(HISTORY_SHOWN),
                    )
                }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    fun change(update: (NotificationPreferences) -> NotificationPreferences) {
        val current = loaded?.preferences ?: return
        val updated = update(current)
        scope.launch {
            try {
                withContext(host.dispatcher) { app.setNotificationPreferences(updated) }
                notice = null
                host.notificationSettingsChanged()
                reload++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "Could not save notification settings. Try again."
            }
        }
    }

    val current = loaded
    if (current == null) {
        SettingsNote(if (loadFailed) "Notification settings could not be loaded." else "Loading notification settings…")
        return
    }
    val prefs = current.preferences
    SettingsSwitchRow(
        "Daily Portfolio Update",
        prefs.dailyUpdate,
        description = "Once a day from 9:00: your portfolio's value and 24-hour return. Amounts stay hidden while hidden in Valroot.",
        tag = "notify-daily",
    ) { on -> change { it.copy(dailyUpdate = on) } }
    SettingsSwitchRow(
        "Significant Price Changes",
        prefs.priceMoves,
        description = "24-hour moves of 10% or more for coins you hold or favorite.",
        tag = "notify-moves",
    ) { on -> change { it.copy(priceMoves = on) } }
    SettingsSwitchRow(
        "All-Time Highs and Lows",
        prefs.highsAndLows,
        description = "When a coin you hold or favorite sets a new all-time high or low.",
        tag = "notify-extremes",
    ) { on -> change { it.copy(highsAndLows = on) } }
    if (!current.marketConsent) {
        SettingsNote("Price notifications need market data on Main. The daily update still works.")
    }

    SettingsSectionLabel("Price alerts")
    SettingsNote(
        when (current.alertsOn) {
            0 -> "No price alerts are on."
            1 -> "1 price alert is on."
            else -> "${current.alertsOn} price alerts are on."
        } + " Add alerts from a coin's Alerts tab.",
    )

    SettingsSectionLabel("Recent notifications")
    TextButton(
        onClick = {
            host.checkNotifications { count ->
                notice =
                    when (count) {
                        0 -> "No new notifications."
                        1 -> "1 new notification."
                        else -> "$count new notifications."
                    }
                reload++
            }
        },
        modifier = Modifier.testTag("notifications-check"),
    ) { Text("Check now", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
    notice?.let { SettingsNote(it) }
    if (current.history.isEmpty()) {
        SettingsNote("No notifications yet.")
    }
    val amountsHidden = LocalAmountsHidden.current
    current.history.forEachIndexed { index, message ->
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("notification-$index")) {
            Text(message.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            // A daily update saved before amounts were hidden still carries the portfolio value, so it is hidden here too.
            val body = if (message.kind == NotificationKind.DAILY_UPDATE) message.body.hiddenIf(amountsHidden) else message.body
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(
                chartTime(message.atMillis, DAY_MILLIS),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}
