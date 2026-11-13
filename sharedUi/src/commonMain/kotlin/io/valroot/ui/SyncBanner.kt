package io.valroot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.SourceStatus
import kotlin.time.Instant

/** Running, failed or interrupted work is shown; a finished success is not kept as a permanent banner. */
internal fun shouldShowSyncBanner(
    status: SyncStatus,
    saved: SourceStatus?,
): Boolean = status.phase == SyncPhase.SYNCING || status.phase == SyncPhase.ERROR || saved?.interrupted == true

@Composable
internal fun SyncBanner(
    status: SyncStatus,
    saved: SourceStatus?,
    onDetails: () -> Unit,
) {
    val running = status.phase == SyncPhase.SYNCING
    val operation =
        when (status.operation) {
            SyncOperation.HISTORY -> "Sync"
            SyncOperation.DISCOVERY -> "Wallet discovery"
            SyncOperation.ORDER -> "Order verification"
            SyncOperation.PRICES -> "Price import"
        }
    val title =
        when (status.phase) {
            SyncPhase.SYNCING -> if (status.operation == SyncOperation.HISTORY) "Syncing…" else "$operation in progress"
            SyncPhase.SUCCESS -> "$operation complete"
            SyncPhase.ERROR -> "$operation needs attention"
            SyncPhase.IDLE ->
                if (saved?.interrupted ==
                    true
                ) {
                    "Sync interrupted"
                } else if (saved?.lastSuccessfulRefresh != null) {
                    "Saved history available"
                } else {
                    "Not synced yet"
                }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (status.phase ==
                    SyncPhase.ERROR
                ) {
                    WarningSurface
                } else {
                    Mint
                },
            ).clickable(role = Role.Button, onClick = onDetails)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
            Text("Details →", fontSize = 11.sp, color = Teal)
        }
        Text(
            if (status.phase !=
                SyncPhase.IDLE
            ) {
                status.message
            } else {
                saved?.takeIf { it.interrupted }?.let { "The previous sync stopped before completion. Tap Sync to retry safely." }
                    ?: saved?.lastSuccessfulRefresh?.let {
                        "Last successful sync: ${Instant.fromEpochMilliseconds(
                            it,
                        ).toString().replace('T', ' ').take(19)} UTC"
                    }
                    ?: "Add an address and allow a provider to fetch public history."
            },
            fontSize = 12.sp,
            color = Muted,
        )
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = MaterialTheme.colorScheme.surface)
            if (status.operation == SyncOperation.HISTORY || status.operation == SyncOperation.DISCOVERY) {
                Text("History is being fetched. Saved balances update after the full scan is checked.", fontSize = 11.sp, color = Muted)
            }
        }
        if (!running &&
            saved?.lastError != null
        ) {
            Text("Previous sync incomplete. Saved history has been retained.", fontSize = 11.sp, color = Orange)
        }
    }
}
