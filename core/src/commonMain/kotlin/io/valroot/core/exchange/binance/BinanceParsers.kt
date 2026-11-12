package io.valroot.core.exchange.binance

import io.valroot.core.exchange.ExchangeContract
import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Binance responses as records; each keeps its raw JSON. */
@OptIn(ExperimentalTime::class)
object BinanceParsers {
    fun parseServerTime(payload: String): Long =
        requireNotNull((Binance.body(payload) as JsonObject).text("serverTime").toLongOrNull()) { "Unexpected time from Binance" }

    /** True when the key can only read: every trading, transfer and withdrawal permission is off. */
    fun parseReadOnly(payload: String): Boolean {
        val o = Binance.body(payload) as JsonObject
        val writes =
            listOf(
                "enableWithdrawals",
                "enableInternalTransfer",
                "enableSpotAndMarginTrading",
                "enableMargin",
                "enableFutures",
                "permitsUniversalTransfer",
                "enableVanillaOptions",
                "enablePortfolioMarginTrading",
                "enableFixApiTrade",
            )
        return o.text("enableReading") == "true" && writes.none { o.text(it) == "true" }
    }

    /** The account's user ID and its Spot balances. */
    fun parseAccount(payload: String): Pair<String, List<BinanceBalance>> {
        val o = Binance.body(payload) as JsonObject
        val uid = o.text("uid").also { require(it.isNotBlank() && it.all(Char::isDigit)) { "Unexpected account from Binance" } }
        val balances =
            o.array("balances").mapNotNull { element ->
                val b = element.jsonObject
                sum(b, "free", "locked")?.let { BinanceBalance("Spot", b.text("asset"), it) }
            }
        return uid to balances
    }

    /** Every wallet's coins, when Binance gives the detail; a wallet without detail is left out. */
    fun parseWalletBalances(payload: String): List<BinanceBalance> =
        (Binance.body(payload) as JsonArray).flatMap { element ->
            val wallet = element.jsonObject
            val name = wallet.text("walletName")
            wallet.array("assetBalances").mapNotNull { asset ->
                val a = asset.jsonObject
                sum(a, "free", "locked", "freeze", "withdrawing")?.let { BinanceBalance(name, a.text("asset"), it) }
            }
        }

    /** Wallet names Binance reports a non-zero balance for, valued in BTC, with or without coin detail. */
    fun parseWalletsWithBalance(payload: String): Set<String> =
        (Binance.body(payload) as JsonArray)
            .map { it.jsonObject }
            .filter { w -> decimal(w.text("balance"))?.let { it.coefficient.sign != 0 } == true }
            .map { it.text("walletName") }
            .toSet()

    /** One page of Simple Earn positions, flexible ([locked] false) or locked, and the total count. */
    fun parseEarnPositions(
        payload: String,
        locked: Boolean,
    ): Pair<List<BinanceBalance>, Int> {
        val o = Binance.body(payload) as JsonObject
        val rows =
            o.array("rows").mapNotNull { element ->
                val r = element.jsonObject
                decimal(r.text(if (locked) "amount" else "totalAmount"))?.let {
                    BinanceBalance(if (locked) "Earn locked" else "Earn flexible", r.text("asset"), it)
                }
            }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    /** Every listed spot symbol. */
    fun parseSymbols(payload: String): Set<String> =
        (Binance.body(payload) as JsonArray).mapNotNull { (it as? JsonObject)?.text("symbol")?.takeIf(String::isNotBlank) }.toSet()

    /** Each coin's token contracts on the networks Binance supports. */
    fun parseCoinContracts(payload: String): Map<String, List<ExchangeContract>> =
        (Binance.body(payload) as JsonArray)
            .associate { element ->
                val c = element.jsonObject
                c.text("coin") to
                    c.array("networkList").mapNotNull { n ->
                        val network = n.jsonObject
                        network
                            .text("contractAddress")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { ExchangeContract(network.text("network"), network.text("name"), it) }
                    }
            }.filterKeys { it.isNotBlank() }

    fun parseTrades(payload: String): List<BinanceTrade> = (Binance.body(payload) as JsonArray).map { trade(it.jsonObject) }

    fun parseDeposits(payload: String): List<BinanceDeposit> = (Binance.body(payload) as JsonArray).map { deposit(it.jsonObject) }

    fun parseWithdrawals(payload: String): List<BinanceWithdrawal> = (Binance.body(payload) as JsonArray).map { withdrawal(it.jsonObject) }

    /** Converts in the window, and whether Binance has more. */
    fun parseConverts(payload: String): Pair<List<BinanceConvert>, Boolean> {
        val o = Binance.body(payload) as JsonObject
        return o.array("list").map { convert(it.jsonObject) } to (o.text("moreData") == "true")
    }

    fun parseDust(payload: String): List<BinanceDust> =
        (Binance.body(payload) as JsonObject).array("userAssetDribblets").map { dust(it.jsonObject) }

    fun parseDividends(payload: String): Pair<List<BinanceDividend>, Int> {
        val o = Binance.body(payload) as JsonObject
        val rows = o.array("rows").map { dividend(it.jsonObject) }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    fun parseRewards(
        payload: String,
        locked: Boolean,
    ): Pair<List<BinanceReward>, Int> {
        val o = Binance.body(payload) as JsonObject
        val rows = o.array("rows").map { reward(it.jsonObject, locked) }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    fun parsePay(payload: String): List<BinancePay> = (Binance.body(payload) as JsonObject).array("data").map { pay(it.jsonObject) }

    fun parseFiatPayments(
        payload: String,
        buy: Boolean,
    ): List<BinanceFiatPayment> = (Binance.body(payload) as JsonObject).array("data").map { fiatPayment(it.jsonObject, buy) }

    fun parseP2p(payload: String): List<BinanceP2p> = (Binance.body(payload) as JsonObject).array("data").map { p2p(it.jsonObject) }

    /** A row saved as raw JSON, parsed again. */
    fun row(raw: String): JsonObject = Binance.json.parseToJsonElement(raw).jsonObject

    fun trade(o: JsonObject) =
        BinanceTrade(
            symbol = o.text("symbol"),
            id = requireNotNull(o.text("id").toLongOrNull()) { "Unexpected trade from Binance" },
            orderId = o.text("orderId"),
            qty = required(o, "qty"),
            quoteQty = required(o, "quoteQty"),
            commission = decimal(o.text("commission")) ?: ZERO,
            commissionAsset = o.text("commissionAsset"),
            isBuyer = o.text("isBuyer") == "true",
            atMillis = millis(o, "time"),
            raw = o.toString(),
        )

    fun deposit(o: JsonObject) =
        BinanceDeposit(
            id = o.text("id").ifBlank { o.text("txId") },
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            txId = o.text("txId"),
            status = o.text("status").toIntOrNull() ?: 0,
            internal = o.text("transferType") == "1",
            atMillis = millis(o, "insertTime"),
            raw = o.toString(),
        )

    fun withdrawal(o: JsonObject): BinanceWithdrawal {
        val txId = o.text("txId")
        return BinanceWithdrawal(
            id = o.text("id"),
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            fee = decimal(o.text("transactionFee")) ?: ZERO,
            txId = txId,
            status = o.text("status").toIntOrNull() ?: 0,
            internal = o.text("transferType") == "1" || txId.startsWith("Off-chain", ignoreCase = true),
            atMillis = utc(o.text("applyTime")),
            raw = o.toString(),
        )
    }

    fun convert(o: JsonObject) =
        BinanceConvert(
            id = o.text("orderId").ifBlank { o.text("quoteId") },
            fromAsset = o.text("fromAsset"),
            fromAmount = required(o, "fromAmount"),
            toAsset = o.text("toAsset"),
            toAmount = required(o, "toAmount"),
            success = o.text("orderStatus") == "SUCCESS",
            atMillis = millis(o, "createTime"),
            raw = o.toString(),
        )

    fun dust(o: JsonObject) =
        BinanceDust(
            id = o.text("transId"),
            atMillis = millis(o, "operateTime"),
            parts =
                o.array("userAssetDribbletDetails").map { element ->
                    val d = element.jsonObject
                    BinanceDust.Part(
                        d.text("fromAsset"),
                        required(d, "amount"),
                        required(d, "transferedAmount"),
                        decimal(d.text("serviceChargeAmount")) ?: ZERO,
                        d.text("targetAsset").ifBlank { "BNB" },
                    )
                },
            raw = o.toString(),
        )

    fun dividend(o: JsonObject): BinanceDividend {
        val amount = required(o, "amount")
        val outgoing = o.text("direction") == "-1" && amount.coefficient.sign > 0
        return BinanceDividend(
            id = o.text("id").ifBlank { o.text("tranId") },
            asset = o.text("asset"),
            amount = if (outgoing) ExactDecimal(-amount.coefficient, amount.scale) else amount,
            info = o.text("enInfo"),
            atMillis = millis(o, "divTime"),
            raw = o.toString(),
        )
    }

    /** Rewards have no ID; the asset, time, type, product and amount identify one. */
    fun reward(
        o: JsonObject,
        locked: Boolean,
    ): BinanceReward {
        val amount = required(o, if (locked) "amount" else "rewards")
        val asset = o.text("asset")
        val at = millis(o, "time")
        val product = if (locked) o.text("positionId") else o.text("projectId")
        return BinanceReward(
            id = listOf(if (locked) "locked" else "flexible", asset, "$at", o.text("type"), product, "$amount").joinToString("|"),
            asset = asset,
            amount = amount,
            atMillis = at,
            raw = o.toString(),
        )
    }

    fun pay(o: JsonObject) =
        BinancePay(
            id = o.text("transactionId"),
            currency = o.text("currency"),
            amount = required(o, "amount"),
            atMillis = millis(o, "transactionTime"),
            raw = o.toString(),
        )

    fun fiatPayment(
        o: JsonObject,
        buy: Boolean,
    ) = BinanceFiatPayment(
        id = o.text("orderNo"),
        buy = buy,
        crypto = o.text("cryptoCurrency"),
        cryptoAmount = required(o, "obtainAmount"),
        completed = o.text("status").equals("Completed", ignoreCase = true),
        atMillis = millis(o, "createTime"),
        raw = o.toString(),
    )

    fun p2p(o: JsonObject) =
        BinanceP2p(
            id = o.text("orderNumber"),
            buy = o.text("tradeType") == "BUY",
            asset = o.text("asset"),
            amount = required(o, "amount"),
            commission = decimal(o.text("commission")) ?: ZERO,
            completed = o.text("orderStatus") == "COMPLETED",
            atMillis = millis(o, "createTime"),
            raw = o.toString(),
        )
}

internal val ZERO = ExactDecimal.parse("0")

/** Binance's withdrawal times are `yyyy-MM-dd HH:mm:ss` in UTC. */
internal fun utc(text: String): Long =
    text.toLongOrNull()
        ?: requireNotNull(runCatching { Instant.parse(text.trim().replace(' ', 'T') + "Z").toEpochMilliseconds() }.getOrNull()) {
            "Unexpected time from Binance"
        }

internal fun sum(
    o: JsonObject,
    vararg names: String,
): ExactDecimal? {
    val parts = names.mapNotNull { decimal(o.text(it)) }
    if (parts.isEmpty()) return null
    return parts.reduce { a, b -> a + b }
}

internal fun JsonObject.text(name: String): String =
    when (val value = this[name]) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> ""
    }

internal fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

internal fun decimal(text: String): ExactDecimal? =
    text
        .takeIf {
            it.isNotBlank()
        }?.let { runCatching { ExactDecimal.parse(it) }.getOrNull() }

internal fun required(
    o: JsonObject,
    name: String,
): ExactDecimal = requireNotNull(decimal(o.text(name))) { "Unexpected $name from Binance" }

internal fun millis(
    o: JsonObject,
    name: String,
): Long = requireNotNull(o.text(name).toLongOrNull()) { "Unexpected $name from Binance" }
