package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRefreshOutcome
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.sync.JobState

/** MEXC spot accounts through an API key with read permissions. */
val MEXC_MANIFEST =
    IntegrationManifest(
        "mexc-api",
        setOf(ExchangeKind.MEXC.network),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One weekly history stream and how far back MEXC keeps it. */
private data class MexcStream(
    val name: String,
    val retentionMillis: Long,
    val read: (send: (MexcRequest) -> String, from: Long, to: Long) -> List<ExchangeRow>,
)

/**
 * Refreshes every tracked MEXC account through [get], which signs and sends a request with the account's key.
 *
 * MEXC does not report a key's permissions; the key is trusted to be read-only, and only GET requests are ever sent.
 * Each refresh reads the account's user ID (it must be the tracked one) and spot balances, then each record stream in
 * 7-day windows forward from where it stopped and back as far as MEXC keeps it, dust conversions, and trades per
 * symbol. MEXC keeps a month of trades, so each symbol is read again when one of its coins' balance changed or after a
 * day, well before MEXC drops them; a window with a full page is split in half.
 * Every record is kept, so history grows from the first refresh on.
 */
class MexcConnector(
    private val get: (account: AccountId, request: MexcRequest) -> String,
) : SourceConnector {
    override val manifest = MEXC_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        app.exchangeTargets(ExchangeKind.MEXC).forEach { (account, uid) ->
            if (!app.hasExchangeKey(ExchangeKind.MEXC, account)) {
                notes += "A MEXC account needs its API key again"
                return@forEach
            }
            options.onProgress("MEXC")
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

    private fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): String? {
        val budget = Budget(MAX_REQUESTS) { get(account, it) }
        require(Mexc.parseUid(budget.send(Mexc.uid())) == uid) { "This API key belongs to another MEXC account" }
        val now = app.clockMillis()
        val balances = Mexc.parseBalances(budget.send(Mexc.account()))

        fun save(rows: List<ExchangeRow>) = app.saveExchangeRows(ExchangeKind.MEXC, account, uid, rows, MexcDecoder.VERSION)
        var unfinished = false
        STREAMS.forEach { stream ->
            options.onProgress("MEXC · ${stream.name}")
            if (!readStream(app, account, stream, now, budget, ::save)) unfinished = true
        }
        if (budget.left >
            0
        ) {
            save(Mexc.parseDust(budget.send(Mexc.dust())).first.map { ExchangeRow(MexcDecoder.DUST, it.id, it.atMillis, it.raw, "") })
        }
        options.onProgress("MEXC · trades")
        if (!readTrades(app, account, balances, now, budget, ::save)) unfinished = true

        val pairs = app.exchangePairs(account)
        val unmatched =
            app.unmatchedExchangeSymbols(
                ExchangeKind.MEXC.id,
                app.exchangeRowSymbols(ExchangeKind.MEXC, account) + balances.keys,
            )
        val contracts =
            if (unmatched.isNotEmpty() &&
                budget.left > 0
            ) {
                Mexc.parseCoinContracts(budget.send(Mexc.coins())).filterKeys { it in unmatched }
            } else {
                emptyMap()
            }
        app.applyMexcHistory(account, uid, balances, now, pairs, contracts)
        app.saveExchangeCursor(account, BALANCES_CURSOR, balances.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" })
        return if (unfinished) "Older MEXC history is still being read; it continues with the next refresh" else null
    }

    /** Reads one stream forward and back in 7-day windows; false when the budget ran out first. */
    private fun readStream(
        app: PortfolioApplication,
        account: AccountId,
        stream: MexcStream,
        now: Long,
        budget: Budget,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        var state = app.exchangeStreams(account)[stream.name] ?: ExchangeStreamState(stream.name, now, now, false, 0)
        var from = maxOf(state.fromMillis, state.toMillis - Mexc.DAY_MILLIS)
        while (from < now) {
            if (budget.left <= 0) return false
            val to = minOf(from + Mexc.WEEK_MILLIS, now)
            save(stream.read(budget::send, from, to))
            state = state.copy(toMillis = to)
            app.saveExchangeStream(account, state)
            from = to
        }
        val limit = now - stream.retentionMillis
        while (!state.complete) {
            if (budget.left <= 0) return false
            val start = maxOf(state.fromMillis - Mexc.WEEK_MILLIS, limit)
            val found =
                try {
                    if (start < state.fromMillis) stream.read(budget::send, start, state.fromMillis).also(save).size else 0
                } catch (refused: MexcException) {
                    // MEXC refuses windows older than it keeps: the history ends here.
                    if (refused.rateLimited || refused.invalidKey || refused.clockSkew) throw refused
                    state = state.copy(complete = true)
                    app.saveExchangeStream(account, state)
                    break
                }
            state = state.copy(fromMillis = start, emptyWindows = if (found == 0) state.emptyWindows + 1 else 0, complete = start <= limit)
            app.saveExchangeStream(account, state)
        }
        return true
    }

    /** Reads trades of every candidate symbol that is due; false when the budget ran out first. */
    private fun readTrades(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
        now: Long,
        budget: Budget,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        if (budget.left <= 0) return false
        val listed = Mexc.parseSymbols(budget.send(Mexc.tickers()))
        val cursors = app.exchangeCursors(account)
        val coins =
            app.exchangeRowSymbols(ExchangeKind.MEXC, account) + balances.keys +
                app.exchangePairs(account).values.flatMap { listOf(it.first, it.second) }
        val candidates = mutableMapOf<String, Pair<String, String>>()
        coins.forEach { base ->
            (coins + QUOTES).forEach { quote ->
                if (base != quote && base + quote in listed) candidates[base + quote] = base to quote
            }
        }
        cursors.filterKeys { it.startsWith(USER_PAIR) }.forEach { (key, value) ->
            value.split('/').takeIf { it.size == 2 }?.let { candidates[key.removePrefix(USER_PAIR)] = it[0] to it[1] }
        }
        val previous =
            cursors[BALANCES_CURSOR]
                .orEmpty()
                .split(';')
                .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        val changed = (balances.keys + previous.keys).filter { previous[it] != balances[it]?.toString() }.toSet()
        candidates.forEach { (symbol, pair) -> app.saveExchangeCursor(account, PAIR + symbol, "${pair.first}/${pair.second}") }
        for ((symbol, pair) in candidates.entries.sortedBy { it.key }) {
            val read = cursors[TRADE_CURSOR + symbol]?.toLongOrNull()
            val due = read == null || now - read >= Mexc.DAY_MILLIS || pair.first in changed || pair.second in changed
            if (!due) continue
            if (budget.left <= 0) return false
            val from = maxOf(read?.minus(Mexc.DAY_MILLIS) ?: 0L, now - Mexc.TRADE_RETENTION_MILLIS + Mexc.DAY_MILLIS / 24)
            if (!readTradeWindow(symbol, from, now, budget, save)) return false
            app.saveExchangeCursor(account, TRADE_CURSOR + symbol, "$now")
        }
        return true
    }

    /** Reads one symbol's trades in a window, halving it while a page comes back full; false when the budget ran out. */
    private fun readTradeWindow(
        symbol: String,
        from: Long,
        to: Long,
        budget: Budget,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        if (budget.left <= 0) return false
        val trades = Mexc.parseTrades(budget.send(Mexc.trades(symbol, from, to)))
        if (trades.size >= Mexc.TRADE_PAGE && to - from > MIN_TRADE_WINDOW_MILLIS) {
            val middle = from + (to - from) / 2
            return readTradeWindow(symbol, from, middle, budget, save) && readTradeWindow(symbol, middle, to, budget, save)
        }
        save(trades.map { ExchangeRow(MexcDecoder.TRADES, "$symbol:${it.id}", it.atMillis, it.raw, "") })
        return true
    }

    private fun message(failure: Exception): String =
        when {
            failure is MexcException && failure.rateLimited -> "MEXC asked to slow down; it is tried again later"
            failure is MexcException && failure.invalidKey -> "MEXC did not accept this API key"
            // Validation and the host's transport give short, fixed messages without keys or addresses.
            failure is IllegalArgumentException || failure is IllegalStateException || failure is MexcException ->
                failure.message ?: "Could not read this MEXC account"
            else -> "Could not reach MEXC"
        }

    /** Requests one refresh may still send; a window being read finishes its pages first. */
    private class Budget(
        var left: Int,
        private val get: (MexcRequest) -> String,
    ) {
        fun send(request: MexcRequest): String {
            left--
            return get(request)
        }
    }

    companion object {
        const val MAX_REQUESTS = 300

        /** A window is not split below a minute; MEXC returns at most 100 trades in it. */
        const val MIN_TRADE_WINDOW_MILLIS = 60_000L

        const val PAIR = BinanceConnector.PAIR
        const val TRADE_CURSOR = "mexc-trades:"
        const val USER_PAIR = BinanceConnector.USER_PAIR
        const val BALANCES_CURSOR = BinanceConnector.BALANCES_CURSOR

        /** Coins MEXC quotes markets in. */
        val QUOTES = listOf("USDT", "USDC", "USDE", "BTC", "ETH", "MX", "EUR")

        private val STREAMS =
            listOf(
                MexcStream(MexcDecoder.DEPOSITS, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    Mexc
                        .parseDeposits(
                            send(Mexc.deposits(from, to)),
                        ).map { ExchangeRow(MexcDecoder.DEPOSITS, it.id, it.atMillis, it.raw, "") }
                },
                MexcStream(MexcDecoder.WITHDRAWALS, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    Mexc
                        .parseWithdrawals(
                            send(Mexc.withdrawals(from, to)),
                        ).map { ExchangeRow(MexcDecoder.WITHDRAWALS, it.id, it.atMillis, it.raw, "") }
                },
                MexcStream(MexcDecoder.INTERNAL, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    val rows = mutableListOf<ExchangeRow>()
                    var page = 1
                    while (true) {
                        val (transfers, pages) = Mexc.parseInternalTransfers(send(Mexc.internalTransfers(from, to, page)))
                        rows += transfers.map { ExchangeRow(MexcDecoder.INTERNAL, it.id, it.atMillis, it.raw, "") }
                        if (transfers.isEmpty() || page >= pages) break
                        page++
                    }
                    rows
                },
                futures(MexcDecoder.TO_FUTURES, fromSpot = true),
                futures(MexcDecoder.FROM_FUTURES, fromSpot = false),
            )

        private fun futures(
            name: String,
            fromSpot: Boolean,
        ) = MexcStream(name, Mexc.TRANSFER_RETENTION_MILLIS) { send, from, to ->
            val rows = mutableListOf<ExchangeRow>()
            var page = 1
            while (true) {
                val (transfers, total) = Mexc.parseUniversalTransfers(send(Mexc.universalTransfers(fromSpot, from, to, page)))
                rows += transfers.map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
                if (transfers.isEmpty() || page * Mexc.TRANSFER_PAGE >= total) break
                page++
            }
            rows
        }
    }
}
