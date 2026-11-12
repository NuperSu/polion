package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRefreshOutcome
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.model.NetworkId
import io.valroot.core.sync.JobState

/** Bybit accounts are tracked by their user ID on this network. */
val BYBIT_NETWORK = NetworkId("bybit")

const val BYBIT_EXCHANGE = "bybit"

/** Bybit accounts through a read-only API key: balances, both balance logs, deposits, withdrawals and converts. */
val BYBIT_MANIFEST =
    IntegrationManifest(
        "bybit-api",
        setOf(BYBIT_NETWORK),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One history stream: its request window, how far back Bybit keeps it, and when reading back may stop early. */
private data class BybitStream(
    val name: String,
    val windowMillis: Long,
    val retentionMillis: Long,
    /** Stop reading back after this many windows in a row without a record; null reads back to [retentionMillis]. */
    val stopAfterEmpty: Int?,
)

/**
 * Refreshes every tracked Bybit account through [get], which signs and sends a request with the account's API key.
 *
 * Each refresh checks the key (it must be read-only and belong to the tracked account), reads the balances, then each
 * history stream: forward from where it stopped (one day of overlap, so late records are caught), then further back
 * until Bybit's retention ends or the account's start is passed. At most [MAX_REQUESTS] requests are made per account
 * and refresh; the rest continues next time. Every record is saved as it arrives, so an interrupted refresh resumes.
 * Then the account's activities are derived again from all saved records ([BybitDecoder]).
 */
class BybitConnector(
    private val get: (account: AccountId, request: BybitRequest) -> String,
) : SourceConnector {
    override val manifest = BYBIT_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        app.bybitTargets().forEach { (account, uid) ->
            if (!app.hasBybitKey(account)) {
                notes += "A Bybit account needs its API key again"
                return@forEach
            }
            options.onProgress("Bybit")
            jobs +=
                try {
                    if (options.recoverHistory) app.forgetExchangeHistory(account)
                    sync(app, account, uid, options)?.let { notes += it }
                    JobState.COMPLETED
                } catch (failure: Exception) {
                    app.recordExchangeError(account, message(failure))
                    JobState.FAILED
                }
        }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }

    /** Returns a note when older history is still being read. */
    private fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): String? {
        val key = Bybit.parseKeyInfo(get(account, Bybit.keyInfo()))
        require(key.uid == uid) { "This API key belongs to another Bybit account" }
        require(key.readOnly) { "This API key can trade or withdraw; Valroot accepts only read-only keys" }
        val now = app.clockMillis()
        val balances =
            (if (key.unified) Bybit.parseUnifiedBalance(get(account, Bybit.unifiedBalance())) else emptyList()) +
                Bybit.parseFundingBalance(get(account, Bybit.fundingBalance())) +
                Bybit.parseOverview(get(account, Bybit.overview()))
        val budget = Budget(MAX_REQUESTS) { get(account, it) }
        val streams =
            listOfNotNull(
                BybitStream(BybitDecoder.LOG, Bybit.LOG_WINDOW_MILLIS, Bybit.LOG_RETENTION_MILLIS, null).takeIf { key.unified },
                BybitStream(BybitDecoder.FUNDING, Bybit.LOG_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 26),
                BybitStream(BybitDecoder.DEPOSITS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
                BybitStream(BybitDecoder.WITHDRAWALS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
            )
        val states = app.exchangeStreams(account)
        var unfinished = false
        streams.forEach { stream ->
            options.onProgress("Bybit · ${STREAM_LABELS.getValue(stream.name)}")
            var state = states[stream.name] ?: ExchangeStreamState(stream.name, now, now, false, 0)
            // Forward from the last read, with a day of overlap for records Bybit adds late.
            var from = maxOf(state.fromMillis, state.toMillis - Bybit.DAY_MILLIS)
            while (from < now) {
                val to = minOf(from + stream.windowMillis, now)
                if (readWindow(app, account, uid, stream.name, from, to, budget) == null) {
                    unfinished = true
                    return@forEach
                }
                state = state.copy(toMillis = to)
                app.saveExchangeStream(account, state)
                from = to
            }
            // Back towards the account's start.
            val limit = now - stream.retentionMillis
            while (!state.complete) {
                val start = maxOf(state.fromMillis - stream.windowMillis, limit)
                val found =
                    if (start <
                        state.fromMillis
                    ) {
                        readWindow(app, account, uid, stream.name, start, state.fromMillis, budget) ?: break
                    } else {
                        0
                    }
                val empty = if (found == 0) state.emptyWindows + 1 else 0
                state =
                    state.copy(
                        fromMillis = start,
                        emptyWindows = empty,
                        complete = start <= limit || (stream.stopAfterEmpty != null && empty >= stream.stopAfterEmpty),
                    )
                app.saveExchangeStream(account, state)
            }
            if (!state.complete) unfinished = true
        }
        readConverts(app, account, uid, budget)
        val covered = app.exchangeStreams(account).filterKeys { it == BybitDecoder.LOG || it == BybitDecoder.FUNDING }
        app.applyBybitHistory(
            account,
            uid,
            balances,
            now,
            covered
                .map { (stream, state) ->
                    (if (stream == BybitDecoder.LOG) BybitDecoder.UNIFIED else BybitDecoder.FUND) to
                        state.fromMillis
                }.toMap(),
            key.expiresAtMillis,
        )
        return if (unfinished) "Older Bybit history is still being read; it continues with the next refresh" else null
    }

    /** Reads every page of one window and saves its records; returns how many there were, or null when the budget ran out. */
    private fun readWindow(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        stream: String,
        from: Long,
        to: Long,
        budget: Budget,
    ): Int? {
        var cursor: String? = null
        var count = 0
        val rows = mutableListOf<ExchangeRow>()
        do {
            if (budget.left == 0) return null
            val request = budget::send
            val (page, next) =
                when (stream) {
                    BybitDecoder.LOG ->
                        Bybit.parseTransactionLog(request(Bybit.transactionLog(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.key, it.atMillis, it.raw, "") } to page.next
                        }
                    BybitDecoder.FUNDING ->
                        Bybit.parseFundingHistory(request(Bybit.fundingHistory(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                    BybitDecoder.DEPOSITS ->
                        Bybit.parseDeposits(request(Bybit.deposits(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                    else ->
                        Bybit.parseWithdrawals(request(Bybit.withdrawals(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                }
            rows += page
            count += page.size
            cursor = next.takeIf { page.isNotEmpty() && it != cursor }
        } while (cursor != null)
        app.saveExchangeRows(account, uid, rows)
        return count
    }

    /** Convert history has no time filter: pages are read, newest first, until one holds only known converts. */
    private fun readConverts(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        budget: Budget,
    ) {
        val known = app.exchangeRowKeys(account, BybitDecoder.CONVERTS)
        var page = 1
        while (page <= MAX_CONVERT_PAGES && budget.left > 0) {
            val converts = Bybit.parseConverts(budget.send(Bybit.converts(page)))
            app.saveExchangeRows(account, uid, converts.map { ExchangeRow(BybitDecoder.CONVERTS, it.id, it.atMillis, it.raw, "") })
            if (converts.size < CONVERT_PAGE || converts.all { it.id in known }) return
            page++
        }
    }

    /** Requests one refresh may still send for an account. */
    private class Budget(
        var left: Int,
        private val get: (BybitRequest) -> String,
    ) {
        fun send(request: BybitRequest): String {
            check(left > 0)
            left--
            return get(request)
        }
    }

    private fun message(failure: Exception): String =
        when {
            failure is BybitException && failure.rateLimited -> "Bybit asked to slow down; it is tried again later"
            failure is BybitException && failure.invalidKey -> "Bybit did not accept this API key"
            // Validation and the host's transport give short, fixed messages without keys or addresses.
            failure is IllegalArgumentException || failure is IllegalStateException || failure is BybitException ->
                failure.message ?: "Could not read this Bybit account"
            else -> "Could not reach Bybit"
        }

    companion object {
        /** Requests per account and refresh; a first refresh of two years of history takes a few refreshes. */
        const val MAX_REQUESTS = 400
        const val MAX_RETENTION_MILLIS = 5 * 365 * Bybit.DAY_MILLIS
        const val MAX_CONVERT_PAGES = 50
        const val CONVERT_PAGE = 100
        private val STREAM_LABELS =
            mapOf(
                BybitDecoder.LOG to "trading history",
                BybitDecoder.FUNDING to "funding history",
                BybitDecoder.DEPOSITS to "deposits",
                BybitDecoder.WITHDRAWALS to "withdrawals",
            )
    }
}
