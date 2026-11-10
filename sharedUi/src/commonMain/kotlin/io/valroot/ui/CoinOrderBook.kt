package io.valroot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.valroot.core.market.OrderBookSnapshot
import io.valroot.core.model.ExactDecimal
import io.valroot.ui.model.ModelOrderRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Clock

internal const val ORDER_BOOK_POLL_MILLIS = 10_000L

/** A book older than this is shown as out of date. */
internal const val ORDER_BOOK_MAX_AGE_MILLIS = 30_000L

/**
 * The Kraken book for one coin while its Order book tab is open. [book] is the last loaded book, kept through failures.
 * [noMarket] explains that Kraken has no market for the coin. [issue] is the message of the last failed request.
 */
internal class CoinOrderBookState(
    val book: OrderBookSnapshot?,
    val noMarket: String?,
    val issue: String?,
    val outOfDate: Boolean,
    val retry: () -> Unit,
)

/**
 * Requests the order book right away and then every [ORDER_BOOK_POLL_MILLIS] while [active]; leaving the tab or changing the coin
 * cancels the polling. Requests go through [PlatformActions.fetchOrderBook], so each result arrives on the main thread.
 */
@Composable
internal fun rememberCoinOrderBook(
    host: PlatformActions,
    coinId: String?,
    active: Boolean,
): CoinOrderBookState {
    var book by remember(coinId) { mutableStateOf<OrderBookSnapshot?>(null) }
    var noMarket by remember(coinId) { mutableStateOf<String?>(null) }
    var issue by remember(coinId) { mutableStateOf<String?>(null) }
    var requests by remember(coinId) { mutableStateOf(0) }
    LaunchedEffect(host, coinId, active, requests) {
        if (coinId == null || !active) return@LaunchedEffect
        while (true) {
            when (val result = awaitOrderBook(host, coinId)) {
                is OrderBookResult.Loaded -> {
                    book = result.book
                    noMarket = null
                    issue = null
                }
                is OrderBookResult.NoMarket -> {
                    book = null
                    noMarket = result.message
                    issue = null
                }
                is OrderBookResult.Failed -> issue = result.message
            }
            delay(ORDER_BOOK_POLL_MILLIS)
        }
    }
    val loaded = book
    val outOfDate =
        issue != null || (loaded != null && Clock.System.now().toEpochMilliseconds() - loaded.observedAtMillis > ORDER_BOOK_MAX_AGE_MILLIS)
    return CoinOrderBookState(loaded, noMarket, issue, outOfDate) { requests++ }
}

private suspend fun awaitOrderBook(
    host: PlatformActions,
    coinId: String,
): OrderBookResult =
    suspendCancellableCoroutine { continuation ->
        host.fetchOrderBook(coinId) { result ->
            // A request that outlives its tab is dropped instead of resuming a cancelled coroutine.
            if (continuation.isActive) continuation.resume(result)
        }
    }

/** Line above the book: the venue, the pair and the local time the app received it. */
internal fun orderBookSource(book: OrderBookSnapshot): String = "${book.venue} · ${book.pair} · updated ${clockTime(book.observedAtMillis)}"

/** Bid and ask levels side by side, matched by index; the shorter side is padded with empty cells. */
internal fun orderBookRows(book: OrderBookSnapshot?): List<ModelOrderRow> {
    if (book == null) return emptyList()
    return List(maxOf(book.bids.size, book.asks.size)) { index ->
        val bid = book.bids.getOrNull(index)
        val ask = book.asks.getOrNull(index)
        ModelOrderRow(
            amountBid = bid?.volume?.let(::exactText).orEmpty(),
            bid = bid?.price?.let(::exactText).orEmpty(),
            ask = ask?.price?.let(::exactText).orEmpty(),
            amountAsk = ask?.volume?.let(::exactText).orEmpty(),
        )
    }
}

/** The exact value without trailing zeros in its fraction, keeping one fraction digit: "90000.10" shows as "90000.1", "2.000" as "2.0". */
internal fun exactText(value: ExactDecimal): String {
    val text = value.toString()
    if (!text.contains('.')) return text
    val trimmed = text.trimEnd('0')
    return if (trimmed.endsWith('.')) trimmed + "0" else trimmed
}
