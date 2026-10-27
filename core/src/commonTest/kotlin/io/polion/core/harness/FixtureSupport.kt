package io.polion.core.harness

import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.storage.RawObservation
import io.polion.core.storage.sha256
import io.polion.core.sync.SyncClock

/** Deterministic fixtures shared by connector and financial replay tests. */
class FixtureClock(
    var now: Long = 1000,
) : SyncClock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        require(millis >= 0)
        now += millis
    }
}

class FixtureHistory(
    private val family: String,
    private val payload: String,
    private val corrupted: Boolean = false,
) : HistoryFetcher {
    override fun fetch(request: HistoryRequest): HistoryPage =
        HistoryPage(
            listOf(
                RawObservation(
                    family,
                    family,
                    "tx",
                    request.network.value,
                    request.source.value,
                    "2026-01-01T00:00:00Z",
                    payload,
                    "1",
                    if (corrupted) "invalid" else sha256(payload),
                    "fixture",
                ),
            ),
            null,
            Coverage.COMPLETE,
        )
}
