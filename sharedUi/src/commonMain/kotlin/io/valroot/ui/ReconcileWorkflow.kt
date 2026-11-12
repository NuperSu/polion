package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.ActivityId
import io.valroot.core.reconcile.LinkState
import io.valroot.core.reconcile.ReconciliationCandidate
import io.valroot.core.reconcile.TransferLink
import io.valroot.core.reconcile.TransferLinkConflict

@Composable
internal fun ColumnScope.ReconcileWorkflow(
    app: PortfolioApplication,
    execute: (String, () -> Unit) -> Unit,
    close: () -> Unit,
    candidatesState: MutableState<List<ReconciliationCandidate>>,
    transferLinksState: MutableState<List<TransferLink>>,
    summariesState: MutableState<Map<ActivityId, String>>,
    transferConflictsState: MutableState<List<TransferLinkConflict>>,
    transfersLoadedState: MutableState<Boolean>,
) {
    var candidates by candidatesState
    var transferLinks by transferLinksState
    var summaries by summariesState
    var transferConflicts by transferConflictsState
    var transfersLoaded by transfersLoadedState

    Text(
        "Link a withdrawal from one of your accounts with the matching deposit into another, for example an exchange " +
            "withdrawal and the wallet that received it. Linked coins keep their original cost and age instead of " +
            "counting as a sale and a new purchase. Both records stay as they are, and a link can be removed.",
        fontSize = 13.sp,
    )
    Text("Accepted relationships", fontWeight = FontWeight.SemiBold)
    if (!transfersLoaded) Text("Loading transfer review…")
    if (transfersLoaded && transferLinks.isEmpty()) Text("No accepted transfer relationships.")
    transferLinks.forEach { link ->
        HorizontalDivider()
        Text("Out: ${summaries[link.outgoingActivity] ?: link.outgoingActivity.value}", fontSize = 12.sp)
        Text("In: ${summaries[link.incomingActivity] ?: link.incomingActivity.value}", fontSize = 12.sp)
        TextButton(onClick = {
            execute("Transfer link removed; decision marked rejected") {
                app.transactions.setDecision(link.outgoingActivity, link.incomingActivity, LinkState.REJECTED)
                transferLinks = app.accounting.transferLinks()
                transferConflicts = app.accounting.transferLinkConflicts()
                candidates = app.accounting.reconciliationCandidates(86_400_000)
            }
        }, modifier = Modifier.testTag("remove-transfer-${link.outgoingActivity.value}-${link.incomingActivity.value}")) {
            Text("Remove link")
        }
    }
    Text("Accepted decisions needing review", fontWeight = FontWeight.SemiBold)
    if (transfersLoaded && transferConflicts.isEmpty()) Text("No transfer link conflicts.")
    transferConflicts.forEach { conflict ->
        HorizontalDivider()
        Text(
            "${summaries[conflict.first] ?: conflict.first.value} ↔ ${summaries[conflict.second] ?: conflict.second.value}",
            fontSize = 12.sp,
        )
        Text("Unresolved: ${conflict.reason}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        TextButton(onClick = {
            execute("Transfer decision rejected") {
                app.transactions.setDecision(conflict.first, conflict.second, LinkState.REJECTED)
                transferLinks = app.accounting.transferLinks()
                transferConflicts = app.accounting.transferLinkConflicts()
                candidates = app.accounting.reconciliationCandidates(86_400_000)
            }
        }, modifier = Modifier.testTag("reject-transfer-conflict-${conflict.first.value}-${conflict.second.value}")) {
            Text("Reject decision")
        }
    }
    Text("Suggestions", fontWeight = FontWeight.SemiBold)
    if (transfersLoaded && candidates.isEmpty()) Text("No transfer candidates in the current history.")
    candidates.take(50).forEach { candidate ->
        HorizontalDivider()
        Text(summaries[candidate.first] ?: candidate.first.value, fontSize = 12.sp)
        Text("↔ ${summaries[candidate.second] ?: candidate.second.value}", fontSize = 12.sp)
        Text(
            "Match ${candidate.score}%${if (candidate.ambiguous) " · several possible matches; check the amounts and dates" else ""}",
            fontSize = 12.sp,
        )
        Row {
            TextButton(
                onClick = {
                    execute(
                        "Transfer link accepted",
                    ) { app.transactions.setDecision(candidate.first, candidate.second, LinkState.ACCEPTED) }
                    close()
                },
                modifier =
                    Modifier.testTag(
                        "accept-transfer-${candidate.first.value}-${candidate.second.value}",
                    ),
            ) { Text("Link") }
            TextButton(onClick = {
                execute(
                    "Suggestion rejected",
                ) { app.transactions.setDecision(candidate.first, candidate.second, LinkState.REJECTED) }
                close()
            }) { Text("Reject") }
        }
    }
}
