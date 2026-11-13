package io.valroot.core.exchange.binance

import io.valroot.core.exchange.ExchangeApiException
import io.valroot.core.exchange.HmacQuery
import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.ExperimentalTime

/**
 * A GET request to Binance: a path and its query, sent as written. Signed requests get `timestamp`, `recvWindow` and
 * `signature` appended by [HmacQuery].
 */
data class BinanceRequest(
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val signed: Boolean = true,
) {
    init {
        require((path.startsWith("/api/v3/") || path.startsWith("/sapi/v1/")) && path.all { it.isLetterOrDigit() || it in "/-_" })
        require(
            query.all { (name, value) ->
                name.all(Char::isLetterOrDigit) &&
                    value.isNotEmpty() &&
                    value.all { it.code in 33..126 && it !in "&#?=" }
            },
        )
    }

    val queryString: String get() = HmacQuery.query(query)
}

/** One balance: a coin in one Binance wallet (Spot, Funding, Earn, …). */
data class BinanceBalance(
    val wallet: String,
    val coin: String,
    val amount: ExactDecimal,
)

/** One fill of a spot order. */
data class BinanceTrade(
    val symbol: String,
    val id: Long,
    val orderId: String,
    val qty: ExactDecimal,
    val quoteQty: ExactDecimal,
    val commission: ExactDecimal,
    val commissionAsset: String,
    val isBuyer: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

data class BinanceDeposit(
    val id: String,
    val coin: String,
    val network: String,
    val amount: ExactDecimal,
    val txId: String,
    val status: Int,
    /** 1 for a transfer from another Binance user, 0 on chain. */
    val internal: Boolean,
    val atMillis: Long,
    val raw: String = "",
) {
    /** Success (1), or credited but not yet withdrawable (6). */
    val credited: Boolean get() = status == 1 || status == 6
}

data class BinanceWithdrawal(
    val id: String,
    val coin: String,
    val network: String,
    val amount: ExactDecimal,
    val fee: ExactDecimal,
    val txId: String,
    val status: Int,
    val internal: Boolean,
    val atMillis: Long,
    val raw: String = "",
) {
    /** Completed (6). Rejected (3) and other states move nothing. */
    val done: Boolean get() = status == 6
}

data class BinanceConvert(
    val id: String,
    val fromAsset: String,
    val fromAmount: ExactDecimal,
    val toAsset: String,
    val toAmount: ExactDecimal,
    val success: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** One dust conversion: small balances of several coins turned into BNB. */
data class BinanceDust(
    val id: String,
    val atMillis: Long,
    val parts: List<Part>,
    val raw: String = "",
) {
    data class Part(
        val fromAsset: String,
        val amount: ExactDecimal,
        val received: ExactDecimal,
        val fee: ExactDecimal,
        val targetAsset: String,
    )
}

/** A distribution: airdrops, staking and savings payouts, and the like; [amount] is signed. */
data class BinanceDividend(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val info: String,
    val atMillis: Long,
    val raw: String = "",
)

/** A Simple Earn reward, flexible or locked. */
data class BinanceReward(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val atMillis: Long,
    val raw: String = "",
)

/** A Binance Pay transaction; [amount] is positive when received. */
data class BinancePay(
    val id: String,
    val currency: String,
    val amount: ExactDecimal,
    val atMillis: Long,
    val raw: String = "",
)

/** Crypto bought or sold with a card or bank transfer, outside the account's balances. */
data class BinanceFiatPayment(
    val id: String,
    val buy: Boolean,
    val crypto: String,
    val cryptoAmount: ExactDecimal,
    val completed: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** A P2P order: crypto bought from or sold to another user for fiat paid outside Binance. */
data class BinanceP2p(
    val id: String,
    val buy: Boolean,
    val asset: String,
    val amount: ExactDecimal,
    val commission: ExactDecimal,
    val completed: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

class BinanceException(
    code: Int,
    message: String,
) : ExchangeApiException(code, message) {
    override val rateLimited: Boolean get() = code == -1003 || code == 429 || code == 418
    override val invalidKey: Boolean get() = code in setOf(-2014, -2015, -1022, 401)
    override val clockSkew: Boolean get() = code == -1021
}

/**
 * Binance's spot and wallet REST API, read-only. Every path is relative to [BASE_URL]; the host signs each
 * [BinanceRequest] with [signature] and never shows the secret to this code.
 *
 * Binance keeps no balance log: history is assembled from separate records (trades per symbol, converts, dust,
 * deposits, withdrawals, distributions, Simple Earn rewards, Pay, card buys and P2P), each with its own window.
 */
@OptIn(ExperimentalTime::class)
object Binance {
    const val BASE_URL = "https://api.binance.com"
    const val RECV_WINDOW = 10_000L
    const val DAY_MILLIS = 86_400_000L

    /** Binance opened on 2017-07-14. */
    const val LAUNCH_MILLIS = 1_500_001_200_000L

    /** Deposits, withdrawals and Pay accept less than 90 days per request. */
    const val RECORD_WINDOW_MILLIS = 89 * DAY_MILLIS
    const val DIVIDEND_WINDOW_MILLIS = 180 * DAY_MILLIS

    /** Converts, Simple Earn rewards and P2P accept 30 days per request. */
    const val MONTH_WINDOW_MILLIS = 30 * DAY_MILLIS

    /** Pay keeps 18 months, P2P 6 months. */
    const val PAY_RETENTION_MILLIS = 540 * DAY_MILLIS
    const val P2P_RETENTION_MILLIS = 180 * DAY_MILLIS

    const val TRADE_PAGE = 1000
    const val RECORD_PAGE = 1000
    const val EARN_PAGE = 100

    internal val json = Json { ignoreUnknownKeys = true }

    fun restrictions() = BinanceRequest("/sapi/v1/account/apiRestrictions")

    fun account() = BinanceRequest("/api/v3/account", listOf("omitZeroBalances" to "true"))

    fun walletBalance() = BinanceRequest("/sapi/v1/asset/wallet/balance", listOf("needBalanceDetail" to "true"))

    fun flexiblePositions(page: Int) =
        BinanceRequest("/sapi/v1/simple-earn/flexible/position", listOf("current" to "$page", "size" to "$EARN_PAGE"))

    fun lockedPositions(page: Int) =
        BinanceRequest(
            "/sapi/v1/simple-earn/locked/position",
            listOf(
                "current" to "$page",
                "size" to "$EARN_PAGE",
            ),
        )

    /** Every listed spot symbol with its price; public. */
    fun tickers() = BinanceRequest("/api/v3/ticker/price", signed = false)

    fun time() = BinanceRequest("/api/v3/time", signed = false)

    fun coins() = BinanceRequest("/sapi/v1/capital/config/getall")

    fun trades(
        symbol: String,
        fromId: Long,
    ) = BinanceRequest("/api/v3/myTrades", listOf("symbol" to symbol, "fromId" to "$fromId", "limit" to "$TRADE_PAGE"))

    fun deposits(
        fromMillis: Long,
        toMillis: Long,
        offset: Int = 0,
    ) = windowed(
        "/sapi/v1/capital/deposit/hisrec",
        fromMillis,
        toMillis,
        RECORD_WINDOW_MILLIS,
        "offset" to "$offset",
        "limit" to "$RECORD_PAGE",
    )

    fun withdrawals(
        fromMillis: Long,
        toMillis: Long,
        offset: Int = 0,
    ) = windowed(
        "/sapi/v1/capital/withdraw/history",
        fromMillis,
        toMillis,
        RECORD_WINDOW_MILLIS,
        "offset" to "$offset",
        "limit" to "$RECORD_PAGE",
    )

    fun converts(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/convert/tradeFlow", fromMillis, toMillis, MONTH_WINDOW_MILLIS, "limit" to "1000")

    fun dust() = BinanceRequest("/sapi/v1/asset/dribblet")

    fun dividends(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/asset/assetDividend", fromMillis, toMillis, DIVIDEND_WINDOW_MILLIS, "limit" to "500")

    fun flexibleRewards(
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = windowed(
        "/sapi/v1/simple-earn/flexible/history/rewardsRecord",
        fromMillis,
        toMillis,
        MONTH_WINDOW_MILLIS,
        "type" to "ALL",
        "current" to "$page",
        "size" to "$EARN_PAGE",
    )

    fun lockedRewards(
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = windowed(
        "/sapi/v1/simple-earn/locked/history/rewardsRecord",
        fromMillis,
        toMillis,
        MONTH_WINDOW_MILLIS,
        "current" to "$page",
        "size" to "$EARN_PAGE",
    )

    fun pay(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/pay/transactions", fromMillis, toMillis, RECORD_WINDOW_MILLIS, "limit" to "100")

    fun fiatPayments(
        buy: Boolean,
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = BinanceRequest(
        "/sapi/v1/fiat/payments",
        listOf(
            "transactionType" to if (buy) "0" else "1",
            "beginTime" to "$fromMillis",
            "endTime" to "$toMillis",
            "page" to "$page",
            "rows" to "500",
        ),
    ).also { require(toMillis > fromMillis && toMillis - fromMillis <= RECORD_WINDOW_MILLIS) }

    fun p2p(
        buy: Boolean,
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = BinanceRequest(
        "/sapi/v1/c2c/orderMatch/listUserOrderHistory",
        listOf(
            "tradeType" to if (buy) "BUY" else "SELL",
            "startTimestamp" to "$fromMillis",
            "endTimestamp" to "$toMillis",
            "page" to "$page",
            "rows" to "100",
        ),
    ).also { require(toMillis > fromMillis && toMillis - fromMillis <= MONTH_WINDOW_MILLIS) }

    private fun windowed(
        path: String,
        fromMillis: Long,
        toMillis: Long,
        window: Long,
        vararg extra: Pair<String, String>,
    ): BinanceRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= window)
        return BinanceRequest(path, listOf("startTime" to "$fromMillis", "endTime" to "$toMillis") + extra)
    }

    /** The full URL of [request]; signed requests carry their signature, and the key goes in `X-MBX-APIKEY`. */
    fun url(
        request: BinanceRequest,
        secret: String?,
        timestampMillis: Long,
    ): String =
        HmacQuery.url(
            BASE_URL,
            request.path,
            request.query,
            if (request.signed) requireNotNull(secret) else null,
            timestampMillis,
            RECV_WINDOW,
        )

    /** A response as JSON; an error object (`code` below zero, or a non-success string code) throws [BinanceException]. */
    fun body(payload: String): JsonElement {
        val root = json.parseToJsonElement(payload)
        if (root is JsonObject) {
            // Spot errors have a negative number; Pay, card and P2P answer a string, "000000" on success.
            val primitive = root["code"] as? JsonPrimitive
            val code = primitive?.content
            val number = code?.toIntOrNull()
            val failed =
                when {
                    primitive == null -> false
                    primitive.isString -> code != "000000"
                    else -> number == null || number < 0
                }
            if (failed) {
                throw BinanceException(
                    number ?: -1,
                    root.text("msg").ifBlank { root.text("message") }.ifBlank { "Binance error $code" },
                )
            }
            if (root["success"] is JsonPrimitive && (root["success"] as JsonPrimitive).content == "false") {
                throw BinanceException(-1, root.text("message").ifBlank { "Binance refused the request" })
            }
        }
        return root
    }
}
