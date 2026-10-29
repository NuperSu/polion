package io.polion.testing

import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.OwnerId
import io.polion.core.model.Rational
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.SqliteDatabase

fun verifyBitcoinAccountingWorkflow(openDatabase: () -> SqliteDatabase) {
    val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    val hashes = listOf("a", "b", "c").map { it.repeat(64) }.toMutableList()
    val heights = listOf(42, 43, 142)
    val ids = listOf("1", "2", "3").map { it.repeat(64) }

    fun transaction(index: Int): String {
        val input =
            if (index < 2) {
                "{\"is_coinbase\":true}"
            } else {
                """{"txid":"${ids[0]}","vout":0,"prevout":{"scriptpubkey_address":"$address","value":100}}"""
            }
        val output = if (index < 2) address else "external"
        val units = if (index < 2) 100 else 90
        val time = listOf(200, 100, 50)[index]
        return """
            {"txid":"${ids[index]}","vin":[$input],
             "vout":[{"scriptpubkey_address":"$output","value":$units}],
             "fee":${if (index < 2) 0 else 10},
             "status":{"confirmed":true,"block_time":$time,
             "block_height":${heights[index]},"block_hash":"${hashes[index]}"}}
            """.trimIndent()
    }
    var omitProviderUtxos = false
    val transport =
        EsploraTransport { path ->
            when {
                path == "/blocks/tip/hash" -> hashes.last()
                path == "/block/${hashes.last()}" -> """{"id":"${hashes.last()}","height":${heights.last()}}"""
                path.startsWith("/block-height/") -> hashes[heights.indexOf(path.substringAfterLast('/').toInt())]
                path.endsWith("/txids") -> "[\"${ids[hashes.indexOf(path.split('/')[2])]}\"]"
                path.endsWith("/mempool") -> "[]"
                path.endsWith("/chain") -> (2 downTo 0).joinToString(",", "[", "]", transform = ::transaction)
                path.endsWith("/utxo") -> if (omitProviderUtxos) "[]" else utxoResponse(address, (2 downTo 0).map(::transaction))
                else -> """{"chain_stats":{"tx_count":3},"mempool_stats":{"tx_count":0}}"""
            }
        }
    openDatabase().use { db ->
        val app = PortfolioApplication(db) { 1_000_000L }
        val owner = OwnerId("owner")
        val account = AccountId("account")
        app.saveOwner(Owner(owner, "Owner"))
        app.saveAccount(Account(account, owner, "Wallet", AccountKind.WALLET))
        app.saveTarget(
            TrackingTarget(
                TrackingTargetId("address"),
                account,
                TargetIdentity(BITCOIN_MAINNET, "address", address),
                TargetRole.LEAF,
                "Address",
            ),
        )
        app.setSourceConsent(true)
        app.refreshBitcoin(transport)
        val snapshot = app.snapshot()
        assertEquals(
            "100",
            snapshot.valuations
                .single()
                .amount.atomicUnits
                .toString(),
        )
        snapshot.activities.forEach { item ->
            app.saveTreatments(
                item.activity.id,
                item.activity.movements.map { movement ->
                    val quote =
                        QuoteValue(
                            AssetId("USD"),
                            Rational.of(
                                if (item.activity.id.value
                                        .endsWith(ids.first())
                                ) {
                                    "10"
                                } else {
                                    "100"
                                },
                            ),
                        )
                    if (movement.amount.atomicUnits.sign > 0) {
                        Treatment.Acquire(movement.id, quote)
                    } else {
                        Treatment.Dispose(
                            movement.id,
                            QuoteValue(
                                AssetId("USD"),
                                Rational.of(
                                    if (movement.role ==
                                        MovementRole.FEE
                                    ) {
                                        "0"
                                    } else {
                                        "90"
                                    },
                                ),
                            ),
                            movement.role == MovementRole.FEE,
                        )
                    }
                },
            )
        }
        assertTrue(app.accountingReport().orderingUncertain.isNotEmpty())
        app
            .accountingReport()
            .report.disposals
            .forEach { assertNull(it.basis) }
        app.verifyAccountingOrder(transport)
        val verified = app.accountingReport()
        assertTrue(verified.orderingUncertain.isEmpty())
        assertEquals(
            "9/1",
            verified.report.disposals
                .single { !it.fee }
                .basis
                .toString(),
        )
        assertEquals(
            "81/1",
            verified.report.disposals
                .single { !it.fee }
                .gain
                .toString(),
        )
        assertEquals(
            "1/1",
            verified.report.disposals
                .single { it.fee }
                .basis
                .toString(),
        )
        assertEquals(
            "100/1",
            verified.report.lots
                .single()
                .basis
                .toString(),
        )
        app.refreshBitcoin(transport)
        assertEquals(0, app.snapshot().sourceStatus.utxoMismatchCount)
        assertEquals(verified, app.accountingReport())
        omitProviderUtxos = true
        app.refreshBitcoin(transport)
        assertTrue(app.snapshot().sourceStatus.utxoMismatchCount > 0)
        assertEquals(
            "100",
            app
                .snapshot()
                .valuations
                .single()
                .amount.atomicUnits
                .toString(),
        )
        omitProviderUtxos = false
        app.refreshBitcoin(transport)
        assertEquals(0, app.snapshot().sourceStatus.utxoMismatchCount)
        val archive = app.exportBackup("fixture-password".toCharArray())
        openDatabase().use { staging ->
            app.restoreToStaging(staging, archive, "fixture-password".toCharArray())
            val restored = PortfolioApplication(staging) { 1_000_000L }
            assertEquals(verified, restored.accountingReport())
            assertEquals(app.exportCsv(), restored.exportCsv())
        }
        listOf("d", "e", "f").forEachIndexed { index, hash -> hashes[index] = hash.repeat(64) }
        app.refreshBitcoin(transport)
        assertTrue(app.accountingReport().orderingUncertain.isNotEmpty())
        app
            .accountingReport()
            .report.disposals
            .forEach { assertNull(it.basis) }
        app.verifyAccountingOrder(transport)
        assertEquals(verified, app.accountingReport())
    }
}

private fun assertEquals(
    expected: Any?,
    actual: Any?,
) {
    check(expected == actual) { "Fixture result mismatch: $expected versus $actual" }
}

private fun assertNull(value: Any?) {
    check(value == null)
}

private fun assertTrue(value: Boolean) {
    check(value)
}
