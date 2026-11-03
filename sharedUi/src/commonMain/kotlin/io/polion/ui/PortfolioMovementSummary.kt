package io.polion.ui

import io.polion.core.application.AnalyticsSnapshot
import io.polion.core.ledger.ActivityKind
import io.polion.core.model.ExactInteger

/** Native ledger movements explain contributions without inventing historic quote conversions. */
internal fun portfolioMovementSummary(
    before: AnalyticsSnapshot,
    after: AnalyticsSnapshot,
): String {
    val kinds = setOf(ActivityKind.DEPOSIT, ActivityKind.PAYMENT)
    val lines =
        after.categories.filter { it.kind in kinds }.mapNotNull { row ->
            val previous = before.categories.find { it.kind == row.kind && it.asset == row.asset }
            val incoming = row.incoming - (previous?.incoming ?: ExactInteger.ZERO)
            val outgoing = row.outgoing - (previous?.outgoing ?: ExactInteger.ZERO)
            if (incoming.sign == 0 && outgoing.sign == 0) {
                null
            } else {
                "${row.kind.name.lowercase()} · ${row.asset.value}: $incoming incoming / $outgoing outgoing atomic units"
            }
        }
    return if (lines.isEmpty()) {
        "No deposit or payment movements in the accepted records for this period."
    } else {
        lines.joinToString("\n") + "\nNative movements include recorded fees; they are not quote-currency returns."
    }
}
