package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import io.polion.core.application.ActivitySourceEvidence
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.ActivityId
import io.polion.core.model.PortfolioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun ActivityEvidence(
    app: PortfolioApplication,
    host: PlatformActions,
    id: ActivityId,
    portfolio: PortfolioId?,
) {
    var requested by remember(id, portfolio) { mutableStateOf(false) }
    var loading by remember(id, portfolio) { mutableStateOf(false) }
    var failed by remember(id, portfolio) { mutableStateOf(false) }
    var evidence by remember(id, portfolio) { mutableStateOf<List<ActivitySourceEvidence>?>(null) }
    LaunchedEffect(app, id, portfolio, requested) {
        if (!requested) return@LaunchedEffect
        loading = true
        failed = false
        try {
            evidence = withContext(host.dispatcher) { app.activitySourceEvidence(id, portfolio) }
            if (evidence == null) failed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    if (!requested) OutlinedButton(onClick = { requested = true }) { Text("View saved observations") }
    if (loading) Text("Loading saved observations…", color = Muted, fontSize = 12.sp)
    if (failed) {
        Text(
            "Saved observations could not be loaded for this portfolio.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
    }
    evidence?.let { ActivityEvidenceContent(it) }
}

@Composable
internal fun ActivityEvidenceContent(evidence: List<ActivitySourceEvidence>) {
    Column {
        Text(
            "Saved observations may include multiple revisions; the decoder's exact revision is not identified here.",
            color = Muted,
            fontSize = 12.sp,
        )
        if (evidence.isEmpty()) Text("No source records are linked to this activity.", color = Muted, fontSize = 12.sp)
        evidence.forEach { record ->
            Text("Record ${record.id}", fontSize = 12.sp)
            if (record.namespace == null) {
                Text("Source record unavailable", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } else {
                Text("${record.namespace} · ${record.network ?: ""} · ${record.externalId}", fontSize = 12.sp)
                if (record.observations.isEmpty()) Text("No saved observations", color = Muted, fontSize = 12.sp)
                record.observations.forEach { observation ->
                    Text("Source ${observation.source} · retrieved ${observation.retrievedAt}", fontSize = 12.sp)
                    Text("Revision ${observation.payloadVersion} · checksum ${observation.checksum}", color = Muted, fontSize = 11.sp)
                }
                if (record.hasMoreObservations) Text("Showing the 10 most recent observations.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
