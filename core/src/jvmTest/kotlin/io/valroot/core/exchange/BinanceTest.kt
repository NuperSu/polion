package io.valroot.core.exchange

import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinanceTest {
    private fun d(text: String) = ExactDecimal.parse(text)

    @Test
    fun signsTheQueryAsBinanceDocumentsIt() {
        // Binance's own example secret, query and signature.
        assertEquals(
            "c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71",
            Binance.signature(
                "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j",
                "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559",
            ),
        )
        val request = Binance.trades("BTCUSDT", 0)
        assertEquals(
            "symbol=BTCUSDT&fromId=0&limit=1000&timestamp=1700000000000&recvWindow=10000",
            Binance.signedQuery(request, 1_700_000_000_000),
        )
        val url = Binance.url(request, "secret0123456789", 1_700_000_000_000)
        assertTrue(
            url.startsWith(
                "https://api.binance.com/api/v3/myTrades?symbol=BTCUSDT&fromId=0&limit=1000&timestamp=1700000000000&recvWindow=10000&signature=",
            ),
        )
        assertFalse(url.contains("secret0123456789"))
        assertEquals("https://api.binance.com/api/v3/ticker/price", Binance.url(Binance.tickers(), null, 1))
    }

    @Test
    fun requestsKeepWindowsWithinLimits() {
        assertFailsWith<IllegalArgumentException> { Binance.deposits(0, 90 * Binance.DAY_MILLIS) }
        assertFailsWith<IllegalArgumentException> { Binance.converts(0, 31 * Binance.DAY_MILLIS) }
        assertFailsWith<IllegalArgumentException> { Binance.p2p(true, 0, 31 * Binance.DAY_MILLIS, 1) }
        assertFailsWith<IllegalArgumentException> { BinanceRequest("/v5/order/create") }
        assertFailsWith<IllegalArgumentException> { BinanceRequest("/api/v3/myTrades", listOf("symbol" to "A&B")) }
    }

    @Test
    fun errorsCarryTheirKind() {
        assertTrue(assertFailsWith<BinanceException> { Binance.body("""{"code":-1021,"msg":"Timestamp outside recvWindow."}""") }.clockSkew)
        assertTrue(
            assertFailsWith<BinanceException> {
                Binance.body("""{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""")
            }.invalidKey,
        )
        assertTrue(assertFailsWith<BinanceException> { Binance.body("""{"code":-1003,"msg":"Too many requests."}""") }.rateLimited)
        assertFailsWith<BinanceException> { Binance.body("""{"code":"345239","message":"Query time range too large"}""") }
        Binance.body("""{"code":"000000","message":"success","data":[]}""")
        assertEquals(1_499_827_319_559, Binance.parseServerTime("""{"serverTime":1499827319559}"""))
    }

    @Test
    fun readOnlyMeansEveryWritePermissionIsOff() {
        val readOnly =
            """{"ipRestrict":false,"createTime":1623840271000,"enableReading":true,"enableWithdrawals":false,"enableInternalTransfer":false,
            "enableMargin":false,"enableFutures":false,"permitsUniversalTransfer":false,"enableVanillaOptions":false,"enableFixApiTrade":false,
            "enableFixReadOnly":true,"enableSpotAndMarginTrading":false,"enablePortfolioMarginTrading":false}"""
        assertTrue(Binance.parseReadOnly(readOnly))
        assertFalse(Binance.parseReadOnly(readOnly.replace("\"enableInternalTransfer\":false", "\"enableInternalTransfer\":true")))
        assertFalse(Binance.parseReadOnly(readOnly.replace("\"enableSpotAndMarginTrading\":false", "\"enableSpotAndMarginTrading\":true")))
        assertFalse(Binance.parseReadOnly(readOnly.replace("\"enableReading\":true", "\"enableReading\":false")))
    }

    @Test
    fun parsesAccountWalletsAndEarnPositions() {
        val (uid, spot) =
            Binance.parseAccount(
                """{"makerCommission":15,"balances":[{"asset":"BTC","free":"0.5","locked":"0.25"},{"asset":"LTC","free":"4763368.68006011","locked":"0"}],"uid":354937868}""",
            )
        assertEquals("354937868", uid)
        assertEquals(listOf(BinanceBalance("Spot", "BTC", d("0.75")), BinanceBalance("Spot", "LTC", d("4763368.68006011"))), spot)
        val wallets =
            """[{"activate":true,"balance":"0.1","walletName":"Spot","assetBalances":[{"asset":"USDT","free":"10","locked":"1","freeze":"0","withdrawing":"0.5"}]},
                {"activate":true,"balance":"0","walletName":"Funding","assetBalances":[]},{"activate":true,"balance":"0.2","walletName":"Earn"}]"""
        assertEquals(listOf(BinanceBalance("Spot", "USDT", d("11.5"))), Binance.parseWalletBalances(wallets))
        assertEquals(setOf("Spot", "Earn"), Binance.parseWalletsWithBalance(wallets))
        assertEquals(
            listOf(BinanceBalance("Earn flexible", "USDT", d("75.46000000"))) to 1,
            Binance.parseEarnPositions(
                """{"rows":[{"totalAmount":"75.46000000","asset":"USDT","productId":"USDT001"}],"total":1}""",
                locked = false,
            ),
        )
        assertEquals(
            listOf(BinanceBalance("Earn locked", "AXS", d("122.09202928"))) to 1,
            Binance.parseEarnPositions(
                """{"rows":[{"positionId":"123123","asset":"AXS","amount":"122.09202928"}],"total":1}""",
                locked = true,
            ),
        )
    }

    @Test
    fun parsesSymbolsAndContracts() {
        assertEquals(
            setOf("BTCUSDT", "ETHBTC"),
            Binance.parseSymbols("""[{"symbol":"BTCUSDT","price":"1"},{"symbol":"ETHBTC","price":"0.05"}]"""),
        )
        assertEquals(
            mapOf("PEPE" to listOf(ExchangeContract("ETH", "Ethereum (ERC20)", "0x6982508145454ce325ddbe47a25d4ec3d2311933"))),
            Binance.parseCoinContracts(
                """[{"coin":"PEPE","name":"Pepe","networkList":[{"network":"ETH","name":"Ethereum (ERC20)","contractAddress":"0x6982508145454ce325ddbe47a25d4ec3d2311933"},
                {"network":"BSC","name":"BNB Smart Chain (BEP20)","contractAddress":""}]}]""",
            ),
        )
    }

    @Test
    fun parsesTradesDepositsAndWithdrawals() {
        val trade =
            Binance
                .parseTrades(
                    """[{"symbol":"BNBBTC","id":28457,"orderId":100234,"orderListId":-1,"price":"4.00000100","qty":"12.00000000","quoteQty":"48.000012",
                    "commission":"10.10000000","commissionAsset":"BNB","time":1499865549590,"isBuyer":true,"isMaker":false,"isBestMatch":true}]""",
                ).single()
        assertEquals(28457, trade.id)
        assertTrue(trade.isBuyer)
        assertEquals(d("48.000012"), trade.quoteQty)
        val deposit =
            Binance
                .parseDeposits(
                    """[{"id":"769800519366885376","amount":"0.001","coin":"BNB","network":"BNB","status":1,"address":"bnb1","txId":"98A3EA560C6B3336D348B6C83F0F95ECE4F1F5919E94BD006E5BF3BF264FACFC",
                    "insertTime":1661493146000,"transferType":0,"confirmTimes":"1/1"}]""",
                ).single()
        assertTrue(deposit.credited && !deposit.internal)
        val (onChain, offChain) =
            Binance.parseWithdrawals(
                """[{"id":"b6ae22b3aa844210a7041aee7589627c","amount":"8.91000000","transactionFee":"0.004","coin":"USDT","status":6,"address":"0x94df",
                "txId":"0xb5ef8c13b968a406cc62a93a8bd80f9e9a906ef1b3fcf20a2e48573c17659268","applyTime":"2019-10-12 11:12:02","network":"ETH","transferType":0},
                {"id":"x","amount":"1","transactionFee":"0","coin":"USDT","status":6,"txId":"Off-chain transfer 123","applyTime":"2024-06-04 00:00:00","transferType":1}]""",
            )
        assertTrue(onChain.done && !onChain.internal)
        assertEquals(1_570_878_722_000, onChain.atMillis)
        assertEquals(d("0.004"), onChain.fee)
        assertTrue(offChain.internal)
    }

    @Test
    fun parsesConvertsDustDistributionsAndRewards() {
        val (converts, more) =
            Binance.parseConverts(
                """{"list":[{"quoteId":"f3b9","orderId":940708407462087195,"orderStatus":"SUCCESS","fromAsset":"USDT","fromAmount":"20","toAsset":"BNB",
                "toAmount":"0.06154036","createTime":1624248872184}],"startTime":1623824139000,"endTime":1626416139000,"limit":100,"moreData":false}""",
            )
        assertEquals("940708407462087195", converts.single().id)
        assertFalse(more)
        val dust =
            Binance
                .parseDust(
                    """{"total":1,"userAssetDribblets":[{"operateTime":1615985535000,"totalTransferedAmount":"0.00132256","totalServiceChargeAmount":"0.00002699",
                    "transId":45178372831,"userAssetDribbletDetails":[{"transId":4359321,"serviceChargeAmount":"0.000009","amount":"0.0009","operateTime":1615985535000,
                    "transferedAmount":"0.000441","fromAsset":"USDT"}]}]}""",
                ).single()
        assertEquals("45178372831", dust.id)
        assertEquals("BNB", dust.parts.single().targetAsset)
        val (dividends, total) =
            Binance.parseDividends(
                """{"rows":[{"id":1637366104,"amount":"10.00000000","asset":"BHFT","divTime":1563189166000,"enInfo":"BHFT distribution","tranId":2968885920},
                {"id":1637366105,"amount":"1.00000000","asset":"BNB","divTime":1563189166000,"enInfo":"Fee","tranId":2968885921,"direction":-1}],"total":2}""",
            )
        assertEquals(2, total)
        assertEquals(d("-1.00000000"), dividends[1].amount)
        val (rewards, _) =
            Binance.parseRewards(
                """{"rows":[{"asset":"BUSD","rewards":"0.00006408","projectId":"USDT001","type":"BONUS","time":1577257222000}],"total":1}""",
                locked = false,
            )
        assertEquals("flexible|BUSD|1577257222000|BONUS|USDT001|0.00006408", rewards.single().id)
    }

    @Test
    fun parsesPayCardBuysAndP2p() {
        assertEquals(
            d("-23.72469206"),
            Binance
                .parsePay(
                    """{"code":"000000","message":"success","data":[{"orderType":"C2C","transactionId":"M_P_71505104267788288","transactionTime":1610090460133,
                    "amount":"-23.72469206","currency":"BNB"}],"success":true}""",
                ).single()
                .amount,
        )
        val buy =
            Binance
                .parseFiatPayments(
                    """{"code":"000000","message":"success","data":[{"orderNo":"353fca44","sourceAmount":"20.00","fiatCurrency":"EUR","obtainAmount":"4.462",
                    "cryptoCurrency":"LUNA","totalFee":"0.2","price":"4.437472","status":"Completed","createTime":1624529919000}],"total":1,"success":true}""",
                    buy = true,
                ).single()
        assertTrue(buy.completed && buy.buy)
        val p2p =
            Binance
                .parseP2p(
                    """{"code":"000000","message":"success","data":[{"orderNumber":"20219644646554779648","tradeType":"SELL","asset":"BUSD","fiat":"CNY",
                    "amount":"5000.00000000","totalPrice":"33400.00000000","orderStatus":"COMPLETED","createTime":1619361369000,"commission":"0"}],"total":1,"success":true}""",
                ).single()
        assertFalse(p2p.buy)
        assertTrue(p2p.completed)
    }
}
