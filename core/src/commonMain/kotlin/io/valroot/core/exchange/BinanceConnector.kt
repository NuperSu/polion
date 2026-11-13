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

/** Binance accounts through a read-only API key: balances, trades and every other record Binance keeps. */
val BINANCE_MANIFEST =
    IntegrationManifest(
        "binance-api",
        setOf(ExchangeKind.BINANCE.network),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One windowed history stream: its window, how far back it goes, and when reading back may stop early. */
private data class BinanceStream(
    val name: String,
    val windowMillis: Long,
    /** Earliest moment to read, relative to now when positive is false. */
    val oldest: (now: Long) -> Long,
    /** Stop reading back after this many windows in a row without a record; null reads back to [oldest]. */
    val stopAfterEmpty: Int?,
    val read: (send: (BinanceRequest) -> String, from: Long, to: Long) -> List<ExchangeRow>,
)

/**
 * Refreshes every tracked Binance account through [get], which signs and sends a request with the account's key.
 *
 * Each refresh checks the key (it may only read, and must belong to the tracked account), reads the balances of every
 * wallet, then each record stream forward from where it stopped (one day of overlap) and further back until Binance's
 * launch, its retention, or a stretch without records. Trades are read per symbol, as in rotki: from the first trade
 * ID, then from the last one seen. The symbols are those Binance lists that pair two coins the account has touched,
 * or a coin with a common quote coin, plus symbols the user adds. A symbol is read again when one of its coins'
 * balance changed, or after a day. At most [MAX_REQUESTS] requests are made per account and refresh; the rest follows
 * next time. Then the account's activities are derived again from all saved records ([BinanceDecoder]).
 */
class BinanceConnector(
    private val get: (account: AccountId, request: BinanceRequest) -> String,
) : SourceConnector {
    override val manifest = BINANCE_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        app.exchangeTargets(ExchangeKind.BINANCE).forEach { (account, uid) ->
            if (!app.hasExchangeKey(ExchangeKind.BINANCE, account)) {
                notes += "A Binance account needs its API key again"
                return@forEach
            }
            options.onProgress("Binance")
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
        val budget = Budget(MAX_REQUESTS) { get(account, it) }
        require(Binance.parseReadOnly(budget.send(Binance.restrictions()))) {
            "This API key can trade, transfer or withdraw; Valroot accepts only keys that can read"
        }
        val (keyUid, spot) = Binance.parseAccount(budget.send(Binance.account()))
        require(keyUid == uid) { "This API key belongs to another Binance account" }
        val now = app.clockMillis()
        val balances = balances(budget, spot)

        fun save(rows: List<ExchangeRow>) = app.saveExchangeRows(ExchangeKind.BINANCE, account, uid, rows, BinanceDecoder.VERSION)
        var unfinished = false
        STREAMS.forEach { stream ->
            options.onProgress("Binance · ${stream.name}")
            if (!readStream(app, account, stream, now, budget, ::save)) unfinished = true
        }
        if (budget.left >
            0
        ) {
            save(
                Binance.parseDust(budget.send(Binance.dust())).map { ExchangeRow(BinanceDecoder.DUST, it.id, it.atMillis, it.raw, "") },
            )
        }

        options.onProgress("Binance · trades")
        val pairs = if (budget.left > 0) readTrades(app, account, balances, now, budget, ::save) else app.exchangePairs(account)
        if (pairs == null) unfinished = true

        // Symbols not matched to a coin yet: Binance's token contracts let CoinGecko's contract map name the coin.
        val known = pairs ?: app.exchangePairs(account)
        val unmatched =
            app.unmatchedExchangeSymbols(
                ExchangeKind.BINANCE.id,
                app.exchangeRowSymbols(ExchangeKind.BINANCE, account) + balances.keys,
            )
        val contracts =
            if (unmatched.isNotEmpty() &&
                budget.left > 0
            ) {
                Binance.parseCoinContracts(budget.send(Binance.coins())).filterKeys { it in unmatched }
            } else {
                emptyMap()
            }
        app.applyBinanceHistory(account, uid, balances, now, known, contracts)
        app.saveExchangeCursor(account, BALANCES_CURSOR, balances.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" })
        return if (unfinished) "Older Binance history is still being read; it continues with the next refresh" else null
    }

    /**
     * Each coin's total over every wallet. Binance's wallet list gives coins per wallet; Spot, and Earn when it has a
     * value but no coin detail, come from their own requests instead.
     */
    private fun balances(
        budget: Budget,
        spot: List<BinanceBalance>,
    ): Map<String, ExactDecimal> {
        val payload = budget.send(Binance.walletBalance())
        val detailed = Binance.parseWalletBalances(payload)
        val withValue = Binance.parseWalletsWithBalance(payload)
        val all = detailed.toMutableList()
        if (detailed.none { it.wallet == "Spot" }) all += spot
        if (detailed.none { it.wallet.startsWith("Earn") } && withValue.any { it.startsWith("Earn") }) {
            for (locked in listOf(false, true)) {
                var page = 1
                while (true) {
                    val (rows, total) =
                        Binance.parseEarnPositions(
                            budget.send(if (locked) Binance.lockedPositions(page) else Binance.flexiblePositions(page)),
                            locked,
                        )
                    all += rows
                    if (rows.isEmpty() || page * Binance.EARN_PAGE >= total) break
                    page++
                }
            }
        }
        return all
            .filter { it.amount.coefficient.sign != 0 }
            .groupBy { it.coin }
            .mapValues { (_, list) -> list.map { it.amount }.reduce { a, b -> a + b } }
    }

    /** Reads one stream forward and back; false when the budget ran out before it was complete. */
    private fun readStream(
        app: PortfolioApplication,
        account: AccountId,
        stream: BinanceStream,
        now: Long,
        budget: Budget,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        var state = app.exchangeStreams(account)[stream.name] ?: ExchangeStreamState(stream.name, now, now, false, 0)
        var from = maxOf(state.fromMillis, state.toMillis - Binance.DAY_MILLIS)
        while (from < now) {
            if (budget.left <= 0) return false
            val to = minOf(from + stream.windowMillis, now)
            save(stream.read(budget::send, from, to))
            state = state.copy(toMillis = to)
            app.saveExchangeStream(account, state)
            from = to
        }
        val limit = maxOf(stream.oldest(now), Binance.LAUNCH_MILLIS)
        while (!state.complete) {
            if (budget.left <= 0) return false
            val start = maxOf(state.fromMillis - stream.windowMillis, limit)
            val found =
                try {
                    if (start < state.fromMillis) stream.read(budget::send, start, state.fromMillis).also(save).size else 0
                } catch (refused: BinanceException) {
                    // Binance refuses windows older than it keeps: the history ends here.
                    if (refused.rateLimited || refused.invalidKey || refused.clockSkew) throw refused
                    state = state.copy(complete = true)
                    app.saveExchangeStream(account, state)
                    break
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
        return true
    }

    /**
     * Reads trades of every candidate symbol that is due; returns the symbols with their base and quote coins, or null
     * when the budget ran out first.
     */
    private fun readTrades(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
        now: Long,
        budget: Budget,
        save: (List<ExchangeRow>) -> Unit,
    ): Map<String, Pair<String, String>>? {
        val listed = Binance.parseSymbols(budget.send(Binance.tickers()))
        val cursors = app.exchangeCursors(account)
        val coins =
            app.exchangeRowSymbols(ExchangeKind.BINANCE, account) + balances.keys +
                app.exchangePairs(account).values.flatMap { listOf(it.first, it.second) }
        val candidates = mutableMapOf<String, Pair<String, String>>()
        coins.forEach { base ->
            (coins + QUOTES).forEach { quote ->
                if (base != quote && base + quote in listed) candidates[base + quote] = base to quote
            }
        }
        cursors.filterKeys { it.startsWith(USER_PAIR) }.forEach { (key, value) ->
            val symbol = key.removePrefix(USER_PAIR)
            value.split('/').takeIf { it.size == 2 }?.let { candidates[symbol] = it[0] to it[1] }
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
            val cursor = cursors[TRADE_CURSOR + symbol]?.split('|')
            val nextId = cursor?.getOrNull(0)?.toLongOrNull() ?: 0L
            val checked = cursor?.getOrNull(1)?.toLongOrNull()
            val due = checked == null || now - checked >= Binance.DAY_MILLIS || pair.first in changed || pair.second in changed
            if (!due) continue
            var fromId = nextId
            while (true) {
                if (budget.left <= 0) return null
                val trades = Binance.parseTrades(budget.send(Binance.trades(symbol, fromId)))
                save(trades.map { ExchangeRow(BinanceDecoder.TRADES, "$symbol:${it.id}", it.atMillis, it.raw, "") })
                trades.maxOfOrNull { it.id }?.let { fromId = it + 1 }
                app.saveExchangeCursor(
                    account,
                    TRADE_CURSOR + symbol,
                    "$fromId|${if (trades.size < Binance.TRADE_PAGE) now else checked ?: 0}",
                )
                if (trades.size < Binance.TRADE_PAGE) break
            }
        }
        return app.exchangePairs(account)
    }

    private fun message(failure: Exception): String =
        when {
            failure is BinanceException && failure.rateLimited -> "Binance asked to slow down; it is tried again later"
            failure is BinanceException && failure.invalidKey -> "Binance did not accept this API key"
            // Validation and the host's transport give short, fixed messages without keys or addresses.
            failure is IllegalArgumentException || failure is IllegalStateException || failure is BinanceException ->
                failure.message ?: "Could not read this Binance account"
            else -> "Could not reach Binance"
        }

    /**
     * Requests one refresh may still send for an account. Loops stop when it is spent; a window being read finishes
     * its pages first, so the count can go slightly below zero.
     */
    private class Budget(
        var left: Int,
        private val get: (BinanceRequest) -> String,
    ) {
        fun send(request: BinanceRequest): String {
            left--
            return get(request)
        }
    }

    companion object {
        /** Requests per account and refresh; a first refresh takes a few. */
        const val MAX_REQUESTS = 600

        /** Cursor keys: a symbol's base and quote, its next trade ID and last full read, symbols the user added. */
        const val PAIR = "pair:"
        const val TRADE_CURSOR = "trades:"
        const val USER_PAIR = "userpair:"
        const val BALANCES_CURSOR = "balances"

        /** Coins Binance quotes markets in. */
        val QUOTES = listOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "BRL", "JPY", "DAI", "XRP")

        private val STREAMS =
            listOf(
                BinanceStream(BinanceDecoder.DEPOSITS, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    paged(1000) { offset ->
                        Binance
                            .parseDeposits(
                                send(Binance.deposits(from, to, offset)),
                            ).map { ExchangeRow(BinanceDecoder.DEPOSITS, it.id, it.atMillis, it.raw, "") }
                    }
                },
                BinanceStream(BinanceDecoder.WITHDRAWALS, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    paged(1000) { offset ->
                        Binance
                            .parseWithdrawals(send(Binance.withdrawals(from, to, offset)))
                            .map { ExchangeRow(BinanceDecoder.WITHDRAWALS, it.id, it.atMillis, it.raw, "") }
                    }
                },
                BinanceStream(BinanceDecoder.DIVIDENDS, Binance.DIVIDEND_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    Binance.parseDividends(send(Binance.dividends(from, to))).first.map {
                        ExchangeRow(BinanceDecoder.DIVIDENDS, it.id, it.atMillis, it.raw, "")
                    }
                },
                BinanceStream(BinanceDecoder.CONVERTS, Binance.MONTH_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
                    val rows = mutableListOf<ExchangeRow>()
                    var start = from
                    while (true) {
                        val (converts, more) = Binance.parseConverts(send(Binance.converts(start, to)))
                        rows += converts.map { ExchangeRow(BinanceDecoder.CONVERTS, it.id, it.atMillis, it.raw, "") }
                        val last = converts.maxOfOrNull { it.atMillis } ?: break
                        if (!more || last + 1 >= to) break
                        start = last + 1
                    }
                    rows
                },
                rewards(BinanceDecoder.FLEXIBLE_REWARDS, locked = false),
                rewards(BinanceDecoder.LOCKED_REWARDS, locked = true),
                BinanceStream(
                    BinanceDecoder.PAY,
                    Binance.RECORD_WINDOW_MILLIS,
                    { it - Binance.PAY_RETENTION_MILLIS },
                    null,
                ) { send, from, to ->
                    Binance.parsePay(send(Binance.pay(from, to))).map { ExchangeRow(BinanceDecoder.PAY, it.id, it.atMillis, it.raw, "") }
                },
                card(BinanceDecoder.CARD_BUYS, buy = true),
                card(BinanceDecoder.CARD_SALES, buy = false),
                p2p(BinanceDecoder.P2P_BUYS, buy = true),
                p2p(BinanceDecoder.P2P_SALES, buy = false),
            )

        private fun rewards(
            name: String,
            locked: Boolean,
        ) = BinanceStream(name, Binance.MONTH_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
            val rows = mutableListOf<ExchangeRow>()
            var page = 1
            while (true) {
                val request = if (locked) Binance.lockedRewards(from, to, page) else Binance.flexibleRewards(from, to, page)
                val (rewards, total) = Binance.parseRewards(send(request), locked)
                rows += rewards.map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
                if (rewards.isEmpty() || page * Binance.EARN_PAGE >= total) break
                page++
            }
            rows
        }

        private fun card(
            name: String,
            buy: Boolean,
        ) = BinanceStream(name, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
            paged(500, start = 1, step = 1) { page ->
                Binance
                    .parseFiatPayments(
                        send(Binance.fiatPayments(buy, from, to, page)),
                        buy,
                    ).map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
            }
        }

        private fun p2p(
            name: String,
            buy: Boolean,
        ) = BinanceStream(name, Binance.MONTH_WINDOW_MILLIS, { it - Binance.P2P_RETENTION_MILLIS }, null) { send, from, to ->
            paged(100, start = 1, step = 1) { page ->
                Binance.parseP2p(send(Binance.p2p(buy, from, to, page))).map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
            }
        }

        /** Reads pages until one is not full: by offset (step = page size) or by page number. */
        private fun paged(
            size: Int,
            start: Int = 0,
            step: Int = size,
            page: (Int) -> List<ExchangeRow>,
        ): List<ExchangeRow> {
            val rows = mutableListOf<ExchangeRow>()
            var position = start
            while (true) {
                val items = page(position)
                rows += items
                if (items.size < size) break
                position += step
            }
            return rows
        }
    }
}
