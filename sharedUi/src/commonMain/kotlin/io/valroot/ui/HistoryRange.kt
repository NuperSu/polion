package io.valroot.ui

import kotlin.time.Instant

/** Zero denotes all history; -1 is one hour and -2 is the UTC calendar year to date. */
internal fun historyRangeStart(
    period: Int,
    end: Long,
    first: Long?,
): Long =
    when (period) {
        0 -> (first ?: end).coerceIn(0, end)
        -1 -> (end - 3_600_000L).coerceAtLeast(0)
        -2 -> Instant.parse("${Instant.fromEpochMilliseconds(end).toString().take(4)}-01-01T00:00:00Z").toEpochMilliseconds()
        else -> (end - period.toLong() * 86_400_000L).coerceAtLeast(0)
    }
