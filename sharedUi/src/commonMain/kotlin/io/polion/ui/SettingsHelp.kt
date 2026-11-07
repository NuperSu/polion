package io.polion.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One help topic; keep the text in step with the behaviour it describes. */
internal data class HelpTopic(
    val title: String,
    val paragraphs: List<String>,
)

internal val helpTopics =
    listOf(
        HelpTopic(
            "Wallets",
            listOf(
                "Polion is watch-only. It reads public Bitcoin addresses and public wallet keys, never a seed phrase or " +
                    "private key, and it cannot move funds.",
                "A wallet can be an xpub or a descriptor: addr, pkh, wpkh or sh(wpkh). A bare xpub is read as a native " +
                    "SegWit (bc1q) wallet with receive and change addresses. If your wallet uses another address type, " +
                    "import a descriptor instead.",
                "Polion finds a wallet's addresses by checking them in order and stops after 20 unused addresses in a row. " +
                    "Funds beyond such a gap are not found.",
            ),
        ),
        HelpTopic(
            "Syncing",
            listOf(
                "Pull down or press Refresh to sync now. With Auto-sync on, prices refresh hourly and wallets every " +
                    "30 minutes; reopening the app soon after a sync does not repeat it.",
                "A wallet with no new activity is only checked, not downloaded again.",
            ),
        ),
        HelpTopic(
            "Privacy",
            listOf(
                "Polion has no account and no server; your data stays on this device.",
                "The wallet provider (blockstream.info unless you set your own) learns the addresses you track and your " +
                    "IP address. Kraken receives Bitcoin price requests and your IP address. CoinGecko is contacted only " +
                    "when you request historical prices with your own key.",
            ),
        ),
        HelpTopic(
            "Backups",
            listOf(
                "An encrypted backup holds your accounts, transactions, classifications and settings. It has its own " +
                    "password, which cannot be recovered.",
                "Restoring replaces everything on this device. The passcode lock and fingerprint unlock belong to the " +
                    "device and are not in backups.",
            ),
        ),
        HelpTopic(
            "Passcode lock",
            listOf(
                "The lock hides Polion on this device but does not encrypt its data; use your device's encryption for that.",
                "If you forget the password, you can erase this device's Polion data and restore a backup.",
            ),
        ),
        HelpTopic(
            "Values and gains",
            listOf(
                "Totals use the latest saved price. Coins without a current price are left out of totals and counted " +
                    "in a note.",
                "Cost basis and gains use first in, first out. The order of transactions within one block is provisional " +
                    "until it has been verified. Polion is not tax advice.",
            ),
        ),
    )

@Composable
internal fun ColumnScope.SettingsHelp() {
    helpTopics.forEach { topic ->
        Text(
            topic.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).testTag("help-${topic.title.lowercase().replace(' ', '-')}"),
        )
        topic.paragraphs.forEach { paragraph ->
            Text(
                paragraph,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
