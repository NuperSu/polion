package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceAddressScan
import io.polion.core.ownership.TrackingTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun SourceScanDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    target: TrackingTarget,
    close: () -> Unit,
) {
    var rows by remember(target.id) { mutableStateOf<List<SourceAddressScan>?>(null) }
    var error by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(app, target.id) {
        try {
            rows = withContext(host.dispatcher) { app.sourceScanJobs(target.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    Dialog(onDismissRequest = close) {
        Surface(
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source scan details", style = MaterialTheme.typography.headlineSmall)
                Text(target.label, fontWeight = FontWeight.SemiBold)
                Text(
                    "These are saved fetch jobs from the most recent scan. A completed fetch does not by itself mean the full scan was accepted.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                when {
                    error -> Text("Scan details could not be loaded.", color = MaterialTheme.colorScheme.error)
                    rows == null -> Text("Loading scan details…", color = Muted)
                    else -> SourceScanContent(requireNotNull(rows))
                }
                TextButton(onClick = close) { Text("Close") }
            }
        }
    }
}

@Composable
internal fun SourceScanContent(rows: List<SourceAddressScan>) {
    var page by remember(rows) { mutableIntStateOf(0) }
    if (rows.isEmpty()) Text("No supported address is available for this source.", color = Muted)
    rows.drop(page * 10).take(10).forEach { row ->
        Text(row.target.label, fontWeight = FontWeight.Medium)
        Text(row.target.identity.canonicalKey, color = Muted, fontSize = 11.sp)
        val job = row.job
        if (job == null) {
            Text("No saved fetch job", color = Muted, fontSize = 12.sp)
        } else {
            Text("Fetch ${job.state.lowercase()} · ${job.pages} pages · provider coverage ${job.coverage.lowercase()}", fontSize = 12.sp)
            job.provider?.let { Text("Provider $it", color = Muted, fontSize = 11.sp) }
        }
    }
    if (rows.size > 10) {
        TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous addresses") }
        TextButton(enabled = (page + 1) * 10 < rows.size, onClick = { page++ }) { Text("Next addresses") }
        Text("${page + 1} / ${(rows.size + 9) / 10}", fontSize = 12.sp)
    }
}
