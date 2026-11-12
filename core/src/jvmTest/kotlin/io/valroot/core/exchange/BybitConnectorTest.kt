package io.valroot.core.exchange

import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
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

/** A Bybit account replayed by a fake API: every response is filtered by the request's window and paged. */
class BybitConnectorTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val start = now - 3 * Bybit.DAY_MILLIS
    private val account = AccountId("bybit")
    private val uid = "1001"

    private fun at(hours: Int) = start + hours * hour

    private inner class FakeBybit {
        var readOnly = 1
        var keyUid = uid
        val requests = mutableListOf<BybitRequest>()
        val unified = mutableMapOf("BTC" to "0.6", "USDT" to "900")
        val fund = mutableMapOf("USDT" to "50", "ZZZ" to "0")
        val earn = mutableMapOf("USDT" to "200")
        val log =
            mutableListOf(
                logRow("1", "TRANSFER_IN", "", "USDT", "1000", "0", "1000", "", at(1)),
                logRow("2", "TRADE", "spot", "USDT", "-500", "0", "500", "T1", at(2)),
                logRow("2", "TRADE", "spot", "BTC", "0.4995", "0.0005", "0.4995", "T1", at(2)),
                logRow("3", "AIRDROP", "", "BTC", "0.1005", "0", "0.6", "", at(3)),
            )
        val funding =
            mutableListOf(
                fundingRow("f0", "USDT", "I", "1300", "1300", at(0), "Deposit", "Deposit"),
                fundingRow("f1", "USDT", "O", "1000", "300", at(1), "Transfer", "Transfer to Unified Trading Account"),
                fundingRow("f2", "USDT", "O", "51", "249", at(4), "Withdrawal", "Withdrawal"),
                fundingRow("f3", "USDT", "O", "200", "49", at(5), "Earn", "Easy Earn | Flexible Savings Subscription"),
                fundingRow("f4", "USDT", "I", "1", "50", at(6), "Earn", "Easy Earn | Flexible Interest Distribution"),
            )
        val deposits =
            mutableListOf(
                """{"coin":"USDT","chain":"ETH","amount":"1300","txID":"0xabc","status":3,"successAt":"${at(0) - 60_000}","id":"d1"}""",
            )
        val withdrawals =
            mutableListOf(
                """{"coin":"USDT","chain":"ETH","amount":"50","txID":"0xdef","status":"success","withdrawFee":"1","createTime":"${at(
                    4,
                ) - 5_000}","withdrawId":"w1","withdrawType":0}""",
            )

        fun logRow(
            id: String,
            type: String,
            category: String,
            coin: String,
            change: String,
            fee: String,
            after: String,
            tradeId: String,
            time: Long,
        ) =
            """{"id":"$id","type":"$type","category":"$category","symbol":"","currency":"$coin","change":"$change","cashFlow":"","funding":"",
            |"fee":"$fee","cashBalance":"$after","tradeId":"$tradeId","orderId":"","transactionTime":"$time"}
            """.trimMargin()

        fun fundingRow(
            id: String,
            coin: String,
            direction: String,
            amount: String,
            after: String,
            time: Long,
            business: String,
            description: String,
        ) = """{"currency":"$coin","ioDirection":"$direction","txnAmt":"$amount","afterAmt":"$after","createTime":"${time / 1000}",
            |"showBusiTypeEn":"$business","descriptionEn":"$description","currcCursor":"$id"}
            """.trimMargin()

        private fun ok(result: String) = """{"retCode":0,"retMsg":"OK","result":$result}"""

        /** Rows inside the requested window, two per page. */
        private fun paged(
            rows: List<String>,
            time: (String) -> Long,
            request: BybitRequest,
            from: Long,
            to: Long,
            name: String,
        ): String {
            val inside = rows.filter { time(it) in from..to }
            val index = request.query.toMap()["cursor"]?.toInt() ?: 0
            val page = inside.drop(index).take(2)
            val next = if (index + 2 < inside.size) "${index + 2}" else ""
            return ok("""{"$name":[${page.joinToString(",")}],"nextPageCursor":"$next"}""")
        }

        private fun millis(
            row: String,
            field: String,
        ) = Regex("\"$field\":\"(\\d+)\"").find(row)!!.groupValues[1].toLong()

        fun get(request: BybitRequest): String {
            requests += request
            val q = request.query.toMap()
            return when (request.path) {
                "/v5/user/query-api" ->
                    ok(
                        """{"readOnly":$readOnly,"ips":[],"uta":1,"userID":$keyUid,"expiredAt":"2027-02-03T00:00:00Z"}""",
                    )
                "/v5/account/wallet-balance" ->
                    ok(
                        """{"list":[{"coin":[${unified.entries.joinToString(
                            ",",
                        ) { """{"coin":"${it.key}","walletBalance":"${it.value}"}""" }}]}]}""",
                    )
                "/v5/asset/transfer/query-account-coins-balance" ->
                    ok("""{"balance":[${fund.entries.joinToString(",") { """{"coin":"${it.key}","walletBalance":"${it.value}"}""" }}]}""")
                "/v5/asset/asset-overview" ->
                    ok(
                        """{"list":[{"accountType":"Earn","categories":[{"category":"Easy Earn","coinDetail":[${
                            earn.entries.joinToString(",") { """{"coin":"${it.key}","equity":"${it.value}"}""" }
                        }]}]}]}""",
                    )
                "/v5/account/transaction-log" ->
                    paged(
                        log,
                        { millis(it, "transactionTime") },
                        request,
                        q.getValue("startTime").toLong(),
                        q.getValue("endTime").toLong(),
                        "list",
                    )
                "/v5/asset/fundinghistory" ->
                    paged(
                        funding,
                        { millis(it, "createTime") * 1000 },
                        request,
                        q.getValue("createTimeFrom").toLong() * 1000,
                        q.getValue("createTimeTo").toLong() * 1000,
                        "list",
                    )
                "/v5/asset/deposit/query-record" ->
                    paged(
                        deposits,
                        { millis(it, "successAt") },
                        request,
                        q.getValue("startTime").toLong(),
                        q.getValue("endTime").toLong(),
                        "rows",
                    )
                "/v5/asset/withdraw/query-record" ->
                    paged(
                        withdrawals,
                        { millis(it, "createTime") },
                        request,
                        q.getValue("startTime").toLong(),
                        q.getValue("endTime").toLong(),
                        "rows",
                    )
                "/v5/asset/exchange/query-convert-history" -> ok("""{"list":[]}""")
                else -> error("Unexpected ${request.path}")
            }
        }
    }

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { now }
        app.snapshot()
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.saveAccountAndTarget(
            Account(account, OwnerId("owner"), "Bybit", AccountKind.ACCOUNT),
            TrackingTarget(TrackingTargetId("bybit"), account, TargetIdentity(BYBIT_NETWORK, "uid", uid), TargetRole.LEAF, "Bybit"),
        )
        app.setBybitKey(account, "KEYKEYKEYKEY", "SECRETSECRETSECRET")
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
            18 - decimal.substringAfter('.', "").length,
        )

    @Test
    fun firstRefreshRebuildsTheAccountFromBothLogs() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val bybit = FakeBybit()
            val outcome =
                BybitConnector {
                    acct,
                    request,
                    ->
                    assertEquals(account, acct).let { bybit.get(request) }
                }.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertNull(app.exchangeStatus(account).error)

            // Holdings equal Bybit's: wallets plus Earn.
            assertEquals(mapOf("coin:bitcoin" to units("0.6"), "coin:tether" to units("1150")), balances(app))

            val activities = app.snapshot(ActivityFilter(account = account, limit = 100)).activities.associateBy { it.activity.id.value }
            val trade = activities.getValue("bybit:$uid:trade:T1").activity
            assertEquals(ActivityKind.TRADE, trade.kind)
            assertEquals(
                setOf(
                    MovementRole.TRADE_LEG to "-${units("500")}",
                    MovementRole.TRADE_LEG to "${units("0.5")}",
                    MovementRole.FEE to "-${units("0.0005")}",
                ),
                trade.movements.map { it.role to it.amount.atomicUnits.toString() }.toSet(),
            )
            assertEquals(ActivityKind.REWARD, activities.getValue("bybit:$uid:log:3|AIRDROP|BTC||${at(3)}").activity.kind)
            assertEquals(ActivityKind.DEPOSIT, activities.getValue("bybit:$uid:funding:f0").activity.kind)
            val withdrawal = activities.getValue("bybit:$uid:funding:f2").activity
            assertEquals(listOf(MovementRole.TRANSFER, MovementRole.FEE), withdrawal.movements.map { it.role })
            assertEquals(ActivityKind.REWARD, activities.getValue("bybit:$uid:funding:f4").activity.kind)
            // Moves within Bybit leave no activity; the Unified wallet's balance before its first row is an opening.
            assertTrue("bybit:$uid:funding:f1" !in activities && "bybit:$uid:funding:f3" !in activities)
            assertTrue(activities.keys.none { it.contains("TRANSFER_IN") })
            assertEquals(
                "${units("400")}",
                activities
                    .getValue("bybit:$uid:opening:UNIFIED:USDT")
                    .activity.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertTrue(activities.keys.none { it.startsWith("bybit:$uid:balance:") })
            assertEquals(
                mapOf(
                    ActivityId("bybit:$uid:funding:f0") to ExchangeTransfer("ETH", "0xabc"),
                    ActivityId("bybit:$uid:funding:f2") to ExchangeTransfer("ETH", "0xdef"),
                ),
                app.exchangeTransfers(),
            )
            // The log is read back two years, the other streams until half a year (funding) or a year without records.
            assertTrue(app.exchangeStreams(account).values.all { it.complete })
            assertTrue(bybit.requests.size < BybitConnector.MAX_REQUESTS)
            assertTrue(bybit.requests.none { it.queryString.contains("SECRET") })
        }
    }

    @Test
    fun laterRefreshesReadOnlyNewRecordsAndKeepActivitiesStable() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val bybit = FakeBybit()
            val connector = BybitConnector { _, request -> bybit.get(request) }
            connector.refresh(app, SourceRefreshOptions())
            val before =
                app
                    .snapshot(ActivityFilter(account = account, limit = 100))
                    .activities
                    .map { it.activity }
                    .toSet()
            bybit.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(
                before,
                app
                    .snapshot(ActivityFilter(account = account, limit = 100))
                    .activities
                    .map { it.activity }
                    .toSet(),
            )
            // Key check, three balances, one window per stream (a day of overlap) and the convert page.
            assertEquals(9, bybit.requests.size)

            // A new airdrop: the balance follows it, and the coin Bybit holds beyond the records becomes an adjustment.
            bybit.log += bybit.logRow("4", "AIRDROP", "", "BTC", "0.4", "0", "1.0", "", now - hour)
            bybit.unified["BTC"] = "1.0"
            bybit.earn["BTC"] = "0.25"
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(units("1.25"), balances(app)["coin:bitcoin"])
            val activities = app.snapshot(ActivityFilter(account = account, limit = 100)).activities.map { it.activity.id.value }
            assertTrue("bybit:$uid:balance:BTC" in activities)
        }
    }

    @Test
    fun keysThatCanTradeOrBelongElsewhereAreRefused() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val bybit = FakeBybit().apply { readOnly = 0 }
            val connector = BybitConnector { _, request -> bybit.get(request) }
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals("This API key can trade or withdraw; Valroot accepts only read-only keys", app.exchangeStatus(account).error)
            bybit.readOnly = 1
            bybit.keyUid = "2002"
            connector.refresh(app, SourceRefreshOptions())
            assertEquals("This API key belongs to another Bybit account", app.exchangeStatus(account).error)
            assertEquals(2, bybit.requests.count { it.path == "/v5/user/query-api" })
            app.setBybitKey(account, null, null)
            val outcome = connector.refresh(app, SourceRefreshOptions())
            assertEquals(emptyList(), outcome.jobs)
            assertEquals(listOf("A Bybit account needs its API key again"), outcome.notes)
        }
    }

    @Test
    fun unmatchedSymbolsWaitForTheUsersChoice() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val bybit = FakeBybit()
            bybit.funding += bybit.fundingRow("f5", "ZZZ", "I", "7", "7", at(7), "Airdrop", "Airdrop")
            bybit.fund["ZZZ"] = "7"
            BybitConnector { _, request -> bybit.get(request) }.refresh(app, SourceRefreshOptions())
            assertEquals(setOf("ZZZ"), app.unresolvedExchangeCoins(BYBIT_EXCHANGE).keys)
            assertTrue(balances(app).keys.none { it.contains("zzz") })
            app.chooseExchangeCoin(BYBIT_EXCHANGE, "ZZZ", "zzz-coin")
            BybitConnector { _, request -> bybit.get(request) }.refresh(app, SourceRefreshOptions())
            assertEquals(units("7"), balances(app)["coin:zzz-coin"])
        }
    }
}
