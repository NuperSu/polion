package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRefreshOutcome
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.sync.JobState

/** An error an exchange API answered with, classified so connectors and transports can react the same way. */
abstract class ExchangeApiException(
    val code: Int,
    message: String,
) : Exception(message) {
    abstract val rateLimited: Boolean
    abstract val invalidKey: Boolean
    abstract val clockSkew: Boolean

    /** Errors that stop a refresh; any other refusal of an old window means the exchange keeps no older records. */
    val stopsRefresh: Boolean get() = rateLimited || invalidKey || clockSkew
}

/** Sends one signed read request for an account and returns the response body. */
fun interface ExchangeTransport<R> {
    fun get(
        account: AccountId,
        request: R,
    ): String
}

/**
 * Requests one refresh may still send for an account. Loops stop when it is spent; a reader that finishes a window's
 * pages first can take the count slightly below zero.
 */
class RequestBudget<R>(
    var left: Int,
    private val get: (R) -> String,
) {
    val spent: Boolean get() = left <= 0

    fun send(request: R): String {
        left--
        return get(request)
    }
}

/** A record stream read in windows: [windowMillis] per request, back to [limitMillis] or [stopAfterEmpty] empty windows. */
data class StreamWindows(
    val name: String,
    val windowMillis: Long,
    val limitMillis: Long,
    /** Stop reading back after this many windows in a row without a record; null reads back to [limitMillis]. */
    val stopAfterEmpty: Int? = null,
)

/**
 * The refresh every exchange shares: for each tracked account with a key, [sync] reads and applies its history, and a
 * failure is saved as the account's status with a short message that never contains keys or addresses.
 */
abstract class ExchangeConnector(
    val kind: ExchangeKind,
) : SourceConnector {
    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        app.exchanges.targets(kind).forEach { (account, uid) ->
            if (!app.exchanges.hasKey(kind, account)) {
                notes += "A ${kind.label} account needs its API key again"
                return@forEach
            }
            options.onProgress(kind.label)
            jobs +=
                try {
                    if (options.recoverHistory) app.exchanges.forgetHistory(account)
                    if (sync(app, account, uid, options)) {
                        notes += "Older ${kind.label} history is still being read; it continues with the next refresh"
                    }
                    JobState.COMPLETED
                } catch (failure: Exception) {
                    app.exchanges.recordError(account, failureMessage(failure))
                    JobState.FAILED
                }
        }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }

    /** Reads and applies one account's history; true when older history remains for a later refresh. */
    protected abstract fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): Boolean

    private fun failureMessage(failure: Exception): String =
        when {
            failure is ExchangeApiException && failure.rateLimited -> "${kind.label} asked to slow down; it is tried again later"
            failure is ExchangeApiException && failure.invalidKey -> "${kind.label} did not accept this API key"
            // Validation and the host's transport give short, fixed messages without keys or addresses.
            failure is IllegalArgumentException || failure is IllegalStateException || failure is ExchangeApiException ->
                failure.message ?: "Could not read this ${kind.label} account"
            else -> "Could not reach ${kind.label}"
        }

    /**
     * Reads one stream forward from where it stopped, with a day of overlap for records the exchange adds late, then
     * back towards [StreamWindows.limitMillis] while [hasBudget]. [read] reads and saves one window and returns its record count, or null
     * when the budget ran out. A refused old window ends the history there. Returns false when reading stopped early.
     */
    protected fun readStream(
        app: PortfolioApplication,
        account: AccountId,
        stream: StreamWindows,
        now: Long,
        hasBudget: () -> Boolean,
        read: (from: Long, to: Long) -> Int?,
    ): Boolean {
        var state = app.exchanges.streams(account)[stream.name] ?: ExchangeStreamState(stream.name, now, now, false, 0)
        var from = maxOf(state.fromMillis, state.toMillis - DAY_MILLIS)
        while (from < now) {
            val to = minOf(from + stream.windowMillis, now)
            read(from, to) ?: return false
            state = state.copy(toMillis = to)
            app.exchanges.saveStream(account, state)
            from = to
        }
        while (!state.complete) {
            // Stop before each step back, so a spent budget never marks history complete without reading it.
            if (!hasBudget()) return false
            val start = maxOf(state.fromMillis - stream.windowMillis, stream.limitMillis)
            val found =
                try {
                    if (start < state.fromMillis) read(start, state.fromMillis) ?: return false else 0
                } catch (refused: ExchangeApiException) {
                    // The exchange refuses windows older than it keeps: the history ends here.
                    if (refused.stopsRefresh) throw refused
                    state = state.copy(complete = true)
                    app.exchanges.saveStream(account, state)
                    break
                }
            val empty = if (found == 0) state.emptyWindows + 1 else 0
            state =
                state.copy(
                    fromMillis = start,
                    emptyWindows = empty,
                    complete = start <= stream.limitMillis || (stream.stopAfterEmpty != null && empty >= stream.stopAfterEmpty),
                )
            app.exchanges.saveStream(account, state)
        }
        return true
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}

/**
 * Trading symbols of exchanges whose trades are read per symbol. Candidates pair two coins the account has touched,
 * or a coin with one of the exchange's quote coins, among the symbols it lists; symbols the user added are always
 * read. Each candidate is saved as a cursor so the account screen can list it.
 */
object TradeSymbols {
    /** Cursor prefix of a symbol read for an account, valued `BASE/QUOTE`. */
    const val PAIR = "pair:"

    /** Cursor prefix of a symbol the user added. */
    const val USER_PAIR = "userpair:"

    /** Cursor holding the balances of the last refresh, to tell which coins changed. */
    const val BALANCES_CURSOR = "balances"

    /** The symbols to read, each with its base and quote coin, saved as [PAIR] cursors. */
    fun candidates(
        app: PortfolioApplication,
        kind: ExchangeKind,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
        listed: Set<String>,
        quotes: List<String>,
    ): Map<String, Pair<String, String>> {
        val cursors = app.exchanges.cursors(account)
        val coins =
            app.exchanges.rowSymbols(kind, account) + balances.keys +
                app.exchanges
                    .pairs(account)
                    .values
                    .flatMap { listOf(it.first, it.second) }
        val candidates = mutableMapOf<String, Pair<String, String>>()
        coins.forEach { base ->
            (coins + quotes).forEach { quote ->
                if (base != quote && base + quote in listed) candidates[base + quote] = base to quote
            }
        }
        cursors.filterKeys { it.startsWith(USER_PAIR) }.forEach { (key, value) ->
            value.split('/').takeIf { it.size == 2 }?.let { candidates[key.removePrefix(USER_PAIR)] = it[0] to it[1] }
        }
        candidates.forEach { (symbol, pair) -> app.exchanges.saveCursor(account, PAIR + symbol, "${pair.first}/${pair.second}") }
        return candidates
    }

    /** Coins whose balance differs from the last refresh's. */
    fun changedCoins(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
    ): Set<String> {
        val previous =
            app.exchanges
                .cursors(account)[BALANCES_CURSOR]
                .orEmpty()
                .split(';')
                .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        return (balances.keys + previous.keys).filter { previous[it] != balances[it]?.toString() }.toSet()
    }

    fun saveBalances(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
    ) = app.exchanges.saveCursor(
        account,
        BALANCES_CURSOR,
        balances.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" },
    )
}
