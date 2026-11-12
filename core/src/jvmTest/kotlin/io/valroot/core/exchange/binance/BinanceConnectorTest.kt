package io.valroot.core.exchange.binance

import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.exchange.ExchangeAssets
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeTransfer
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.sync.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Binance account replayed by a fake API that filters every record by the request's window. */
class BinanceConnectorTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val start = now - 3 * Binance.DAY_MILLIS
    private val account = AccountId("binance")
    private val uid = "777"

    private fun at(hours: Int) = start + hours * hour

    private inner class FakeBinance {
        var readOnly = true
        val requests = mutableListOf<BinanceRequest>()
        val spot = mutableMapOf("BTC" to "0.4994", "USDT" to "100", "BNB" to "0.01")
        val earn = mutableMapOf("USDT" to "50")
        val trades =
            mutableMapOf(
                "BTCUSDT" to
                    mutableListOf(
                        trade("BTCUSDT", 1, "0.6", "600", "0.0006", "BTC", true, at(1)),
                        trade("BTCUSDT", 2, "0.1", "120", "0.12", "USDT", false, at(2)),
                    ),
            )
        val deposits =
            mutableListOf(
                """{"id":"d1","amount":"1000","coin":"USDT","network":"ETH","status":1,"txId":"0xaaa","insertTime":${at(
                    0,
                )},"transferType":0}""",
            )
        val withdrawals =
            mutableListOf(
                withdrawal("w1", "USDT", "368.88", "1", "0xbbb", at(3)),
                withdrawal("w2", "ETH", "1", "0.001", "0xccc", at(5)),
            )
        val dividends = mutableListOf("""{"id":91,"amount":"0.01","asset":"BNB","divTime":${at(4)},"enInfo":"BNB airdrop","tranId":5}""")

        fun trade(
            symbol: String,
            id: Long,
            qty: String,
            quote: String,
            commission: String,
            commissionAsset: String,
            buyer: Boolean,
            time: Long,
        ) = """{"symbol":"$symbol","id":$id,"orderId":${id + 100},"price":"1","qty":"$qty","quoteQty":"$quote","commission":"$commission",
            |"commissionAsset":"$commissionAsset","time":$time,"isBuyer":$buyer,"isMaker":false}
            """.trimMargin()

        fun withdrawal(
            id: String,
            coin: String,
            amount: String,
            fee: String,
            txId: String,
            time: Long,
        ): String {
            val stamp =
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(java.time.Instant.ofEpochMilli(time))
            return """{"id":"$id","amount":"$amount","transactionFee":"$fee","coin":"$coin","status":6,""" +
                """"txId":"$txId","applyTime":"$stamp",""" +
                """"network":"ETH","transferType":0}"""
        }

        private fun inside(
            time: Long,
            q: Map<String, String>,
            from: String = "startTime",
            to: String = "endTime",
        ) = time in q.getValue(from).toLong()..q.getValue(to).toLong()

        private fun time(
            row: String,
            field: String,
        ) = Regex("\"$field\":(\\d+)").find(row)!!.groupValues[1].toLong()

        fun get(request: BinanceRequest): String {
            requests += request
            val q = request.query.toMap()
            return when (request.path) {
                "/sapi/v1/account/apiRestrictions" ->
                    """{"enableReading":true,"enableWithdrawals":false,"enableInternalTransfer":false,"enableSpotAndMarginTrading":${!readOnly}}"""
                "/api/v3/account" -> """{"balances":[${spot.entries.joinToString(
                    ",",
                ) { """{"asset":"${it.key}","free":"${it.value}","locked":"0"}""" }}],"uid":$uid}"""
                "/sapi/v1/asset/wallet/balance" ->
                    """[{"activate":true,"balance":"1","walletName":"Spot"},{"activate":true,"balance":"0.001","walletName":"Earn"}]"""
                "/sapi/v1/simple-earn/flexible/position" ->
                    """{"rows":[${earn.entries.joinToString(
                        ",",
                    ) { """{"asset":"${it.key}","totalAmount":"${it.value}"}""" }}],"total":${earn.size}}"""
                "/sapi/v1/simple-earn/locked/position" -> """{"rows":[],"total":0}"""
                "/api/v3/ticker/price" ->
                    """[{"symbol":"BTCUSDT","price":"1"},{"symbol":"ETHUSDT","price":"1"},{"symbol":"BNBUSDT","price":"1"},{"symbol":"ETHBTC","price":"1"},
                    {"symbol":"BNBBTC","price":"1"},{"symbol":"XYZUSDT","price":"1"}]"""
                "/api/v3/myTrades" -> {
                    val from = q.getValue("fromId").toLong()
                    "[" +
                        trades[
                            q.getValue(
                                "symbol",
                            ),
                        ].orEmpty().filter { Regex("\"id\":(\\d+)").find(it)!!.groupValues[1].toLong() >= from }.joinToString(",") +
                        "]"
                }
                "/sapi/v1/capital/deposit/hisrec" -> "[" + deposits.filter { inside(time(it, "insertTime"), q) }.joinToString(",") + "]"
                "/sapi/v1/capital/withdraw/history" ->
                    "[" + withdrawals.filter { inside(BinanceParsers.withdrawal(BinanceParsers.row(it)).atMillis, q) }.joinToString(",") +
                        "]"
                "/sapi/v1/asset/assetDividend" ->
                    dividends.filter { inside(time(it, "divTime"), q) }.let { """{"rows":[${it.joinToString(",")}],"total":${it.size}}""" }
                "/sapi/v1/convert/tradeFlow" -> """{"list":[],"moreData":false}"""
                "/sapi/v1/asset/dribblet" -> """{"total":0,"userAssetDribblets":[]}"""
                "/sapi/v1/simple-earn/flexible/history/rewardsRecord",
                "/sapi/v1/simple-earn/locked/history/rewardsRecord",
                -> """{"rows":[],"total":0}"""
                "/sapi/v1/pay/transactions", "/sapi/v1/fiat/payments", "/sapi/v1/c2c/orderMatch/listUserOrderHistory" ->
                    """{"code":"000000","message":"success","data":[],"success":true}"""
                "/sapi/v1/capital/config/getall" -> "[]"
                else -> error("Unexpected ${request.path}")
            }
        }
    }

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { now }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccountAndTarget(
            Account(account, OwnerId("owner"), "Binance", AccountKind.ACCOUNT),
            TrackingTarget(
                TrackingTargetId("binance"),
                account,
                TargetIdentity(ExchangeKind.BINANCE.network, "uid", uid),
                TargetRole.LEAF,
                "Binance",
            ),
        )
        app.exchanges.setKey(ExchangeKind.BINANCE, account, "KEYKEYKEYKEY", "SECRETSECRETSECRET")
        return app
    }

    private fun balances(app: PortfolioApplication) =
        app
            .snapshot()
            .balances.quantities
            .filterKeys { it.account == account }
            .filterValues { it.sign != 0 }
            .mapKeys { it.key.asset.value }

    private fun units(decimal: String) =
        ExactInteger.parse(decimal.replace(".", "").trimStart('0').ifEmpty { "0" }).timesPowerOfTen(
            ExchangeAssets.DECIMALS - decimal.substringAfter('.', "").length,
        )

    private fun activities(app: PortfolioApplication) =
        app.snapshot(ActivityFilter(account = account, limit = 500)).activities.associateBy {
            it.activity.id.value
        }

    @Test
    fun recordsTradesTransfersAndRewardsAndFillWhatTheyMiss() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val binance = FakeBinance()
            val outcome =
                BinanceConnector {
                    acct,
                    request,
                    ->
                    assertEquals(account, acct).let { binance.get(request) }
                }.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertNull(app.exchanges.status(account).error)
            assertEquals(emptyList(), outcome.notes)

            // Every wallet and Earn: Spot plus 50 USDT in Earn.
            assertEquals(
                mapOf("coin:bitcoin" to units("0.4994"), "coin:tether" to units("150"), "coin:binancecoin" to units("0.01")),
                balances(app),
            )
            val all = activities(app)
            val buy = all.getValue("binance:$uid:trade:BTCUSDT:1").activity
            assertEquals(ActivityKind.TRADE, buy.kind)
            assertEquals(
                setOf(
                    MovementRole.TRADE_LEG to "${units("0.6")}",
                    MovementRole.TRADE_LEG to "-${units("600")}",
                    MovementRole.FEE to "-${units("0.0006")}",
                ),
                buy.movements.map { it.role to it.amount.atomicUnits.toString() }.toSet(),
            )
            assertEquals(ActivityKind.REWARD, all.getValue("binance:$uid:dividend:91").activity.kind)
            assertEquals(
                listOf(MovementRole.TRANSFER, MovementRole.FEE),
                all
                    .getValue("binance:$uid:withdrawal:w1")
                    .activity.movements
                    .map { it.role },
            )
            // ETH left without an inflow on record: the missing amount arrives just before.
            val gap = all.getValue("binance:$uid:gap:ETH:${at(5) - 1}").activity
            assertEquals(
                "${units("1.001")}",
                gap.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertTrue(all.keys.none { it.contains(":balance:") })
            assertEquals(
                mapOf(
                    ActivityId("binance:$uid:deposit:d1") to ExchangeTransfer("ETH", "0xaaa"),
                    ActivityId("binance:$uid:withdrawal:w1") to ExchangeTransfer("ETH", "0xbbb"),
                    ActivityId("binance:$uid:withdrawal:w2") to ExchangeTransfer("ETH", "0xccc"),
                ),
                app.exchanges.transfers(),
            )
            // Symbols pairing the account's coins, as Binance lists them.
            assertEquals(setOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "ETHBTC", "BNBBTC"), app.exchanges.pairs(account).keys)
            assertTrue(
                app.exchanges
                    .streams(account)
                    .values
                    .all { it.complete },
            )
        }
    }

    @Test
    fun laterRefreshesReadNewWindowsAndOnlySymbolsWhoseCoinsChanged() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val binance = FakeBinance()
            val connector = BinanceConnector { _, request -> binance.get(request) }
            connector.refresh(app, SourceRefreshOptions())
            val before = activities(app).values.map { it.activity }.toSet()
            binance.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(before, activities(app).values.map { it.activity }.toSet())
            assertTrue(binance.requests.none { it.path == "/api/v3/myTrades" })

            // A new trade changes BTC and USDT, so their symbols are read again from the last trade ID.
            binance.trades.getValue("BTCUSDT") += binance.trade("BTCUSDT", 3, "0.0006", "0.6", "0", "BNB", true, now - hour)
            binance.spot["BTC"] = "0.5"
            binance.spot["USDT"] = "99.4"
            binance.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(
                setOf("BTCUSDT" to "3", "ETHBTC" to "0", "BNBBTC" to "0", "ETHUSDT" to "0", "BNBUSDT" to "0"),
                binance.requests
                    .filter { it.path == "/api/v3/myTrades" }
                    .map {
                        it.query.toMap().let { q ->
                            q.getValue("symbol") to
                                q.getValue("fromId")
                        }
                    }.toSet(),
            )
            assertEquals(units("0.5"), balances(app)["coin:bitcoin"])
            assertTrue("binance:$uid:trade:BTCUSDT:3" in activities(app))
        }
    }

    @Test
    fun keysThatCanTradeAreRefusedAndAddedPairsAreRead() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val binance = FakeBinance().apply { readOnly = false }
            val connector = BinanceConnector { _, request -> binance.get(request) }
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals(
                "This API key can trade, transfer or withdraw; Valroot accepts only keys that can read",
                app.exchanges.status(account).error,
            )
            binance.readOnly = true
            app.exchanges.addPair(account, "xyz", "usdt")
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(binance.requests.any { it.path == "/api/v3/myTrades" && it.query.toMap()["symbol"] == "XYZUSDT" })
        }
    }
}
