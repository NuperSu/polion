package io.valroot.core.exchange

import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BybitTest {
    private fun d(text: String) = ExactDecimal.parse(text)

    @Test
    fun hmacMatchesRfc4231() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun signsTimestampKeyWindowAndQueryAsSent() {
        val request =
            BybitRequest(
                "/v5/account/transaction-log",
                listOf(
                    "accountType" to "UNIFIED",
                    "startTime" to "1",
                    "endTime" to "2",
                    "limit" to "50",
                ),
            )
        assertEquals("accountType=UNIFIED&startTime=1&endTime=2&limit=50", request.queryString)
        assertEquals(
            "5791cf8cde8e627264bca35298a7b02d85eb867d12137b7775a26952df7d5b35",
            Bybit.signature("test-secret", "XXXXXXXXXX", 1658384314791, request),
        )
        val headers = Bybit.headers("test-secret", "XXXXXXXXXX", 1658384314791, request).toMap()
        assertEquals(setOf("X-BAPI-API-KEY", "X-BAPI-TIMESTAMP", "X-BAPI-RECV-WINDOW", "X-BAPI-SIGN"), headers.keys)
        assertEquals("10000", headers["X-BAPI-RECV-WINDOW"])
        assertFalse(headers.values.any { it.contains("test-secret") })
    }

    @Test
    fun requestsKeepWindowsWithinLimitsAndPassCursorsUnchanged() {
        val day = Bybit.DAY_MILLIS
        assertFailsWith<IllegalArgumentException> { Bybit.transactionLog(0, 7 * day + 1) }
        assertFailsWith<IllegalArgumentException> { Bybit.deposits(0, 30 * day) }
        assertEquals("21963%3A1%2C14954%3A1", Bybit.transactionLog(0, day, "21963%3A1%2C14954%3A1").query.toMap()["cursor"])
        assertEquals("MTM3MTU3OTk=", Bybit.fundingHistory(0, day, "MTM3MTU3OTk=").query.toMap()["cursor"])
        // Seconds: the start rounds down and the end up, within 7 days.
        val funding = Bybit.fundingHistory(1_500, 7 * day + 1_500).query.toMap()
        assertEquals("1", funding["createTimeFrom"])
        assertEquals("${1 + 7 * 86_400}", funding["createTimeTo"])
        assertFailsWith<IllegalArgumentException> { Bybit.transactionLog(0, day, "a&b=c") }
    }

    @Test
    fun errorsCarryTheirKind() {
        val limited = assertFailsWith<BybitException> { Bybit.result("""{"retCode":10006,"retMsg":"Too many visits!","result":{}}""") }
        assertTrue(limited.rateLimited)
        val key = assertFailsWith<BybitException> { Bybit.result("""{"retCode":10003,"retMsg":"API key is invalid.","result":{}}""") }
        assertTrue(key.invalidKey)
        assertTrue(
            assertFailsWith<BybitException> { Bybit.result("""{"retCode":10002,"retMsg":"invalid request","result":{}}""") }.clockSkew,
        )
        assertEquals(
            1688639403000,
            Bybit.parseServerTime(
                """{"retCode":0,"retMsg":"OK","result":{"timeSecond":"1688639403","timeNano":"1688639403423213947"},"time":1688639403423}""",
            ),
        )
    }

    @Test
    fun keyInfoReportsReadOnlyIdentityAndExpiry() {
        val info =
            Bybit.parseKeyInfo(
                """{"retCode":0,"retMsg":"","result":{"id":"2208369","note":"t","apiKey":"X","readOnly":1,"secret":"",
                "permissions":{"Spot":["SpotTrade"],"Wallet":["AccountTransfer"]},"ips":["*"],"type":1,"deadlineDay":83,
                "expiredAt":"2027-02-03T00:00:00Z","createdAt":"2026-11-05T03:20:45Z","unified":0,"uta":1,"userID":1448939}}""",
            )
        assertEquals("1448939", info.uid)
        assertTrue(info.readOnly)
        assertTrue(info.unified)
        assertFalse(info.ipBound)
        assertEquals(1801612800000, info.expiresAtMillis)
        val bound =
            Bybit.parseKeyInfo(
                """{"retCode":0,"result":{"readOnly":0,"ips":["18.181.170.164"],"deadlineDay":-2,"expiredAt":"1970-01-01T00:00:00Z","uta":1,"userID":7}}""",
            )
        assertFalse(bound.readOnly)
        assertTrue(bound.ipBound)
        assertNull(bound.expiresAtMillis)
    }

    @Test
    fun parsesBalancesFromEveryAccountType() {
        val unified =
            Bybit.parseUnifiedBalance(
                """{"retCode":0,"result":{"list":[{"accountType":"UNIFIED","coin":[{"coin":"BTC","walletBalance":"0.00534882723991","equity":"0.1"},{"coin":"USDT","walletBalance":"12.5"}]}]}}""",
            )
        assertEquals(listOf(BybitBalance("UNIFIED", "BTC", d("0.00534882723991")), BybitBalance("UNIFIED", "USDT", d("12.5"))), unified)
        assertEquals(
            listOf(BybitBalance("FUND", "USDC", d("0"))),
            Bybit.parseFundingBalance(
                """{"retCode":0,"result":{"memberId":"X","accountType":"FUND","balance":[{"coin":"USDC","walletBalance":"0","transferBalance":"0","bonus":""}]}}""",
            ),
        )
        val overview =
            Bybit.parseOverview(
                """{"retCode":0,"result":{"totalEquity":"1","list":[
                {"accountType":"FundingAccount","coinDetail":[{"equity":"5","coin":"USDT"}]},
                {"accountType":"CryptoLoans","coinDetail":[{"equity":"-0.00100377","coin":"BTC"}]},
                {"accountType":"Earn","categories":[{"category":"Easy Earn","equity":"1","coinDetail":[{"equity":"0.3","coin":"BTC"},{"equity":"200","coin":"USDT"}]}]}]}}""",
            )
        assertEquals(
            listOf(
                BybitBalance("CryptoLoans", "BTC", d("-0.00100377")),
                BybitBalance("Earn", "BTC", d("0.3")),
                BybitBalance("Earn", "USDT", d("200")),
            ),
            overview,
        )
    }

    @Test
    fun parsesTransactionLogRows() {
        val page =
            Bybit.parseTransactionLog(
                """{"retCode":0,"retMsg":"OK","result":{"nextPageCursor":"21963%3A1%2C14954%3A1","list":[
                {"transSubType":"","id":"592324_XRPUSDT_161440249321","symbol":"XRPUSDT","side":"Buy","funding":"-0.003676","orderLinkId":"",
                "orderId":"1672128000-8-592324-1-2","fee":"0.00000000","change":"-0.003676","cashFlow":"0","transactionTime":"1672128000000",
                "type":"SETTLEMENT","feeRate":"0.0001","bonusChange":"","size":"100","qty":"100","cashBalance":"5086.55825002","currency":"USDT",
                "category":"linear","tradePrice":"0.3676","tradeId":"534c0003-4bf7-486f-aa02-78cee36825e4","extraFees":""}]}}""",
            )
        assertEquals("21963%3A1%2C14954%3A1", page.next)
        val entry = page.items.single()
        assertEquals("SETTLEMENT", entry.type)
        assertEquals(d("-0.003676"), entry.change)
        assertEquals(d("-0.003676"), entry.funding)
        assertEquals(d("5086.55825002"), entry.balanceAfter)
        assertEquals(1672128000000, entry.atMillis)
        assertEquals("592324_XRPUSDT_161440249321|SETTLEMENT|USDT|534c0003-4bf7-486f-aa02-78cee36825e4|1672128000000", entry.key)
        assertNull(Bybit.parseTransactionLog("""{"retCode":0,"result":{"nextPageCursor":"","list":[]}}""").next)
    }

    @Test
    fun parsesFundingHistoryWithSignedChanges() {
        val page =
            Bybit.parseFundingHistory(
                """{"retCode":0,"retMsg":"OK","result":{"nextPageCursor":"MTM3MTU3OTk=","list":[
                {"memberId":"290118","currency":"BTC","ioDirection":"I","txnAmt":"0.00003561","afterAmt":"7.5547230662687035","createTime":"1772669763",
                "showBusiType":"fundingAccountRecordEarn","showBusiTypeEn":"Earn","description":"x","descriptionEn":"Easy Earn | Flexible Interest Distribution","currcCursor":"NTg2MDQzMTI0OA=="},
                {"memberId":"290118","currency":"USDT","ioDirection":"O","txnAmt":"10","afterAmt":"0","createTime":"1772669764",
                "showBusiTypeEn":"Transfer","descriptionEn":"Transfer to Unified Trading Account","currcCursor":"NTg2MDQzMTI0OQ=="}]}}""",
            )
        val (interest, transfer) = page.items
        assertEquals(d("0.00003561"), interest.change)
        assertEquals(1772669763000, interest.atMillis)
        assertEquals("Earn", interest.business)
        assertEquals(d("-10"), transfer.change)
        assertEquals("NTg2MDQzMTI0OQ==", transfer.id)
    }

    @Test
    fun parsesDepositWithdrawalAndConvertRecords() {
        val deposit =
            Bybit
                .parseDeposits(
                    """{"retCode":0,"result":{"rows":[{"coin":"USDT","chain":"TRX","amount":"999.0496","txID":"04bf3f","status":3,"toAddress":"T",
                    "depositFee":"","successAt":"1742728163000","id":"160237231"}],"nextPageCursor":"eyJ9"}}""",
                ).items
                .single()
        assertTrue(deposit.credited)
        assertEquals(d("999.0496"), deposit.amount)
        val (done, cancelled) =
            Bybit
                .parseWithdrawals(
                    """{"retCode":0,"result":{"rows":[
                    {"coin":"USDC","chain":"ETH","amount":"41.43008","txID":"0x3d7b","status":"success","withdrawFee":"5","createTime":"1742738305000","withdrawId":"131629076","withdrawType":0},
                    {"coin":"USDT","chain":"SOL","amount":"951","txID":"","status":"CancelByUser","withdrawFee":"1","createTime":"1742729329000","withdrawId":"131603458","withdrawType":0}],
                    "nextPageCursor":""}}""",
                ).items
        assertTrue(done.done && done.onChain)
        assertEquals(d("5"), done.fee)
        assertTrue(cancelled.failed)
        val convert =
            Bybit
                .parseConverts(
                    """{"retCode":0,"result":{"list":[{"accountType":"eb_convert_funding","exchangeTxId":"10100108106409343501030232064","fromCoin":"ETH",
                    "fromAmount":"0.1","toCoin":"BTC","toAmount":"0.00534882723991","exchangeStatus":"success","createdAt":"1720071899995"}]}}""",
                ).single()
        assertEquals(d("0.00534882723991"), convert.toAmount)
        assertEquals(
            mapOf(
                "MNT" to listOf(ExchangeContract("ETH", "Ethereum", "0x3c3a81e81dc49a522a592e7622a7e711c06bf354")),
            ),
            Bybit.parseCoinContracts(
                """{"retCode":0,"result":{"rows":[{"name":"MNT","coin":"MNT","chains":[
                {"chainType":"Ethereum","chain":"ETH","contractAddress":"0x3c3a81e81dc49a522a592e7622a7e711c06bf354"},
                {"chainType":"Mantle Network","chain":"MANTLE","contractAddress":""}]}]}}""",
            ),
        )
        assertEquals(listOf("coin" to "MNT"), Bybit.coin("mnt").query)
    }
}
