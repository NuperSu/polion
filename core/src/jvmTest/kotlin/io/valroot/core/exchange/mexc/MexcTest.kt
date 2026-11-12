package io.valroot.core.exchange.mexc

import io.valroot.core.exchange.ExchangeContract
import io.valroot.core.exchange.HmacQuery
import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MexcTest {
    private fun d(text: String) = ExactDecimal.parse(text)

    @Test
    fun signsLikeBinanceAndKeepsWindowsWithinLimits() {
        val request = Mexc.trades("MXUSDT", 1, 2)
        val url = Mexc.url(request, "secret0123456789", 1_700_000_000_000)
        val query = "symbol=MXUSDT&startTime=1&endTime=2&limit=100&timestamp=1700000000000&recvWindow=10000"
        assertEquals("https://api.mexc.com/api/v3/myTrades?$query&signature=${HmacQuery.signature("secret0123456789", query)}", url)
        assertFalse(url.contains("secret0123456789"))
        assertEquals("https://api.mexc.com/api/v3/ticker/price", Mexc.url(Mexc.tickers(), null, 1))
        assertFailsWith<IllegalArgumentException> { Mexc.deposits(0, Mexc.WEEK_MILLIS + 1) }
        assertFailsWith<IllegalArgumentException> { MexcRequest("/api/v3/order", listOf("symbol" to "A&B")) }
    }

    @Test
    fun errorsCarryTheirKind() {
        assertTrue(
            assertFailsWith<MexcException> {
                Mexc.body("""{"code":700003,"msg":"Timestamp for this request is outside of the recvWindow."}""")
            }.clockSkew,
        )
        assertTrue(assertFailsWith<MexcException> { Mexc.body("""{"code":10072,"msg":"Api key info invalid"}""") }.invalidKey)
        val malformed = assertFailsWith<MexcException> { Mexc.body("""{"code":700004,"msg":"Mandatory parameter 'limit' was not sent"}""") }
        assertFalse(malformed.invalidKey)
        Mexc.body("""{"data":["BTCUSDT"],"code":0,"msg":"success"}""")
    }

    @Test
    fun parsesIdentityBalancesAndCoins() {
        assertEquals("12345678", Mexc.parseUid("""{"uid":"12345678"}"""))
        assertEquals(
            mapOf("MX" to d("0.0084217282955947436887649"), "USDT" to d("1.5")),
            Mexc.parseBalances(
                """{"canTrade":true,"balances":[{"asset":"MX","free":"0.0084217282955947436887649","locked":"0"},{"asset":"USDT","free":"1","locked":"0.5"},
                {"asset":"ZERO","free":"0","locked":"0"}],"permissions":["SPOT"]}""",
            ),
        )
        assertEquals(
            mapOf("USDT" to listOf(ExchangeContract("TRX", "TRC20", "TN3W4H6rK2ce4vX9YnFQHwKENnHjoxbm9"))),
            Mexc.parseCoinContracts(
                """[{"coin":"USDT","Name":"USDT","networkList":[{"coin":"USDT","network":"TRC20","netWork":"TRX","contract":"TN3W4H6rK2ce4vX9YnFQHwKENnHjoxbm9"}]}]""",
            ),
        )
    }

    @Test
    fun parsesRecords() {
        val deposit =
            Mexc
                .parseDeposits(
                    """[{"amount":"50000","coin":"EOS","network":"EOS","status":5,"address":"0x20b7","txId":"01391d1c:0","insertTime":1659513342000}]""",
                ).single()
        assertTrue(deposit.credited)
        assertEquals("01391d1c", deposit.txId)
        val withdrawal =
            Mexc
                .parseWithdrawals(
                    """[{"id":"bb17a2d4","txId":null,"coin":"EOS","network":"EOS","amount":"10","transferType":0,"status":7,"transactionFee":"0.1",
                    "applyTime":1665300874000,"transHash":"0x0ced593b"}]""",
                ).single()
        assertTrue(withdrawal.done && !withdrawal.internal)
        assertEquals("0x0ced593b", withdrawal.txId)
        val (internal, pages) =
            Mexc.parseInternalTransfers(
                """{"page":1,"totalRecords":1,"totalPageNum":1,"data":[{"tranId":"119","asset":"BTC","amount":"0.1","toAccountType":"UID",
                "toAccount":"87658765","fromAccount":"a@b.c","status":"SUCCESS","timestamp":1544433325000}]}""",
            )
        assertEquals(1, pages)
        assertEquals("87658765", internal.single().toAccount)
        val (dust, _) =
            Mexc.parseDust(
                """{"data":[{"totalConvert":"0.00885018","totalFee":"0.000177","convertTime":1665360563000,"convertDetails":[{"id":"3e52","convert":"0.00885018",
                "fee":"0.000177","amount":"0.007130464601986065","time":1665360563000,"asset":"ETHF"}]}],"totalRecords":1,"page":1,"totalPageNum":1}""",
            )
        assertEquals(
            d("0.00885018"),
            dust
                .single()
                .parts
                .single()
                .received,
        )
        assertEquals("1665360563000:3e52", dust.single().id)
    }
}
