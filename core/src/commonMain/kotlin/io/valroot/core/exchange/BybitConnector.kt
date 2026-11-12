package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.model.NetworkId

/** Bybit accounts are tracked by their user ID on this network. */
val BYBIT_NETWORK: NetworkId = ExchangeKind.BYBIT.network

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
 * Symbols not matched to a coin yet get their token contracts from Bybit's coin information, which CoinGecko's
 * contract map turns into coins. Then the account's activities are derived again from all saved records
 * ([BybitDecoder]).
 */
class BybitConnector(
    private val get: (account: AccountId, request: BybitRequest) -> String,
) : ExchangeConnector(ExchangeKind.BYBIT) {
    override val manifest = BYBIT_MANIFEST

    override fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): Boolean {
        val key = Bybit.parseKeyInfo(get(account, Bybit.keyInfo()))
        require(key.uid == uid) { "This API key belongs to another Bybit account" }
        require(key.readOnly) { "This API key can trade or withdraw; Valroot accepts only read-only keys" }
        val now = app.clockMillis()
        val balances =
            (if (key.unified) Bybit.parseUnifiedBalance(get(account, Bybit.unifiedBalance())) else emptyList()) +
                Bybit.parseFundingBalance(get(account, Bybit.fundingBalance())) +
                Bybit.parseOverview(get(account, Bybit.overview()))
        val budget = RequestBudget<BybitRequest>(MAX_REQUESTS) { get(account, it) }
        val streams =
            listOfNotNull(
                BybitStream(BybitDecoder.LOG, Bybit.LOG_WINDOW_MILLIS, Bybit.LOG_RETENTION_MILLIS, null).takeIf { key.unified },
                // The Funding history can be quiet for months while deposits and withdrawals happened, so it is read
                // back fully, until Bybit refuses older windows.
                BybitStream(BybitDecoder.FUNDING, Bybit.LOG_WINDOW_MILLIS, MAX_RETENTION_MILLIS, null),
                BybitStream(BybitDecoder.DEPOSITS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
                BybitStream(BybitDecoder.WITHDRAWALS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
            )
        var unfinished = false
        streams.forEach { stream ->
            options.onProgress("Bybit · ${STREAM_LABELS.getValue(stream.name)}")
            val windows = StreamWindows(stream.name, stream.windowMillis, now - stream.retentionMillis, stream.stopAfterEmpty)
            val complete =
                readStream(app, account, windows, now) { from, to -> readWindow(app, account, uid, stream.name, from, to, budget) }
            if (!complete) unfinished = true
        }
        readConverts(app, account, uid, budget)
        // Symbols not matched to a coin yet: Bybit's token contracts let CoinGecko's contract map name the coin.
        val held = balances.filter { it.amount.coefficient.sign != 0 }.map { it.coin }
        val unmatched = app.exchanges.unmatchedSymbols(BYBIT_EXCHANGE, app.exchanges.rowSymbols(ExchangeKind.BYBIT, account) + held)
        val contracts = mutableMapOf<String, List<ExchangeContract>>()
        unmatched.sorted().take(MAX_COIN_LOOKUPS).forEach { symbol ->
            if (!budget.spent) contracts += Bybit.parseCoinContracts(budget.send(Bybit.coin(symbol)))
        }
        val covered = app.exchanges.streams(account).filterKeys { it == BybitDecoder.LOG || it == BybitDecoder.FUNDING }
        val coveredFrom =
            covered
                .map { (stream, state) ->
                    (
                        if (stream ==
                            BybitDecoder.LOG
                        ) {
                            BybitDecoder.UNIFIED
                        } else {
                            BybitDecoder.FUND
                        }
                    ) to state.fromMillis
                }.toMap()
        val snapshot =
            ExchangeSnapshot(
                now,
                balances.map { ExchangeBalance(it.accountType, it.coin, it.amount) },
                contracts = contracts,
                coveredFrom = coveredFrom,
                keyExpiresAtMillis = key.expiresAtMillis,
            )
        app.exchanges.applyHistory(ExchangeKind.BYBIT, account, uid, snapshot)
        return unfinished
    }

    /** Reads every page of one window and saves its records; returns how many there were, or null when the budget ran out. */
    private fun readWindow(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        stream: String,
        from: Long,
        to: Long,
        budget: RequestBudget<BybitRequest>,
    ): Int? {
        var cursor: String? = null
        var count = 0
        val rows = mutableListOf<ExchangeRow>()
        do {
            if (budget.spent) return null
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
        app.exchanges.saveRows(ExchangeKind.BYBIT, account, uid, rows, BybitDecoder.VERSION)
        return count
    }

    /** Convert history has no time filter: pages are read, newest first, until one holds only known converts. */
    private fun readConverts(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        budget: RequestBudget<BybitRequest>,
    ) {
        val known = app.exchanges.rowKeys(account, BybitDecoder.CONVERTS)
        var page = 1
        while (page <= MAX_CONVERT_PAGES && !budget.spent) {
            val converts = Bybit.parseConverts(budget.send(Bybit.converts(page)))
            app.exchanges.saveRows(
                ExchangeKind.BYBIT,
                account,
                uid,
                converts.map {
                    ExchangeRow(BybitDecoder.CONVERTS, it.id, it.atMillis, it.raw, "")
                },
                BybitDecoder.VERSION,
            )
            if (converts.size < CONVERT_PAGE || converts.all { it.id in known }) return
            page++
        }
    }

    companion object {
        /** Requests per account and refresh; a first refresh of two years of history takes a few refreshes. */
        const val MAX_REQUESTS = 400
        const val MAX_RETENTION_MILLIS = 5 * 365 * Bybit.DAY_MILLIS
        const val MAX_CONVERT_PAGES = 50
        const val CONVERT_PAGE = 100

        /** Coins whose contracts are looked up per refresh; the rest follow on later refreshes. */
        const val MAX_COIN_LOOKUPS = 50
        private val STREAM_LABELS =
            mapOf(
                BybitDecoder.LOG to "trading history",
                BybitDecoder.FUNDING to "funding history",
                BybitDecoder.DEPOSITS to "deposits",
                BybitDecoder.WITHDRAWALS to "withdrawals",
            )
    }
}

/** Bybit: Unified and Funding wallets with both balance logs; keys report their permissions and expiry. */
object BybitProvider : ExchangeProvider<BybitRequest> {
    override val kind = ExchangeKind.BYBIT
    override val manifest = BYBIT_MANIFEST
    override val history: ExchangeHistory = BybitDecoder

    override fun connector(transport: ExchangeTransport<BybitRequest>) = BybitConnector(transport::get)

    override fun checkKey(send: (BybitRequest) -> String): ExchangeKeyInfo =
        Bybit.parseKeyInfo(send(Bybit.keyInfo())).let { ExchangeKeyInfo(it.uid, it.readOnly, it.expiresAtMillis) }
}
