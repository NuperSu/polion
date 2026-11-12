package io.valroot.testing

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.BitcoinGraphRepository
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.SqliteDatabase

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
        app.accounts.saveOwner(Owner(owner, "Owner"))
        app.accounts.saveAccount(Account(account, owner, "Wallet", AccountKind.WALLET))
        app.accounts.saveTarget(
            TrackingTarget(
                TrackingTargetId("address"),
                account,
                TargetIdentity(BITCOIN_MAINNET, "address", address),
                TargetRole.LEAF,
                "Address",
            ),
        )
        app.sources.setConsent(true)
        app.bitcoin.refresh(transport)
        val snapshot = app.snapshot()
        assertEquals(
            "100",
            snapshot.valuations
                .single()
                .amount.atomicUnits
                .toString(),
        )
        snapshot.activities.forEach { item ->
            app.accounting.saveTreatments(
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
        assertTrue(
            app.accounting
                .report()
                .orderingUncertain
                .isNotEmpty(),
        )
        app
            .accounting
            .report()
            .report.disposals
            .forEach { assertNull(it.basis) }
        app.bitcoin.verifyAccountingOrder(transport)
        val verified = app.accounting.report()
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
        app.bitcoin.refresh(transport)
        assertEquals(0, app.snapshot().sourceStatus.utxoMismatchCount)
        assertEquals(verified, app.accounting.report())
        // The advisory UTXO comparison runs during a full scan; an unchanged refresh keeps the previous result.
        omitProviderUtxos = true
        app.bitcoin.refresh(transport, recoverHistory = true)
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
        app.bitcoin.refresh(transport, recoverHistory = true)
        assertEquals(0, app.snapshot().sourceStatus.utxoMismatchCount)
        val archive = app.backup.export("fixture-password".toCharArray())
        openDatabase().use { staging ->
            app.backup.restoreToStaging(staging, archive, "fixture-password".toCharArray())
            val restored = PortfolioApplication(staging) { 1_000_000L }
            assertEquals(verified, restored.accounting.report())
            assertEquals(app.csv.export(), restored.csv.export())
            val savedGraph = BitcoinGraphRepository(db).latest()!!
            val restoredGraph = BitcoinGraphRepository(staging).latest()!!
            assertEquals(savedGraph.anchor, restoredGraph.anchor)
            assertEquals(savedGraph.graph.transactions, restoredGraph.graph.transactions)
            assertEquals(savedGraph.graph.ownedOutputs, restoredGraph.graph.ownedOutputs)
            assertEquals(savedGraph.rawChecksums, restoredGraph.rawChecksums)
            assertEquals(savedGraph.comparison, restoredGraph.comparison)
        }
        hashes[2] = "f".repeat(64)
        app.bitcoin.refresh(transport)
        assertTrue(
            app.accounting
                .report()
                .orderingUncertain
                .isNotEmpty(),
        )
        app
            .accounting
            .report()
            .report.disposals
            .forEach { assertNull(it.basis) }
        app.bitcoin.verifyAccountingOrder(transport)
        assertEquals(verified, app.accounting.report())
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
