package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_ASSET
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CsvImportTest {
    private val utc = { stamp: LocalStamp -> CsvImport.utcMillis(stamp) }

    @Test fun readsQuotedSemicolonAndTabFiles() {
        assertEquals(
            listOf(listOf("a", "b,c", "say \"hi\""), listOf("1", "", "x")),
            CsvImport.read("a,\"b,c\",\"say \"\"hi\"\"\"\r\n1,,x\r\n\r\n"),
        )
        assertEquals(listOf(listOf("date", "amount"), listOf("2026-01-01", "1.5")), CsvImport.read("date;amount\n2026-01-01;1.5"))
        assertEquals(listOf(listOf("a", "b")), CsvImport.read("a\tb"))
        assertEquals(listOf(listOf("multi\nline", "x")), CsvImport.read("\"multi\nline\",x"))
        assertFails { CsvImport.read("\"open,x") }
    }

    @Test fun columnsAreFoundByTemplateAndCommonNames() {
        val template = CsvImport.detect(CsvImport.read(CsvImport.TEMPLATE).first())
        assertEquals(CsvField.entries.associateWith { it.ordinal }, template.columns)
        val other = CsvImport.detect(listOf("Timestamp", "Side", "Coin", "Amount", "Unit Price", "TxID"))
        assertEquals(
            mapOf(
                CsvField.DATE to 0,
                CsvField.TYPE to 1,
                CsvField.ASSET to 2,
                CsvField.QUANTITY to 3,
                CsvField.PRICE to 4,
                CsvField.REFERENCE to 5,
            ),
            other.columns,
        )
        assertEquals(listOf(CsvField.ASSET), CsvImport.detect(listOf("date", "type", "amount")).missing)
    }

    @Test fun datesAndNumbersAreReadWithoutGuessing() {
        val noon = CsvImport.utcMillis(LocalStamp(2026, 1, 15, 12, 0, 0))
        assertEquals(1_768_478_400_000L, noon)
        assertEquals(noon, CsvImport.date("2026-01-15T12:00:00Z", utc))
        assertEquals(noon, CsvImport.date("2026-01-15 14:00+02:00", utc))
        assertEquals(noon + 250, CsvImport.date("2026-01-15T12:00:00.25Z", utc))
        assertEquals(noon - 3_600_000, CsvImport.date("2026-01-15 12:00", { utc(it) - 3_600_000 }))
        assertEquals(noon, CsvImport.date("1768478400", utc))
        assertEquals(noon, CsvImport.date("1768478400000", utc))
        assertEquals(CsvImport.utcMillis(LocalStamp(2024, 2, 29, 0, 0, 0)), CsvImport.date("2024-02-29", utc))
        listOf("01/02/2026", "2026-02-30", "2026-13-01", "yesterday").forEach { assertFails(it) { CsvImport.date(it, utc) } }
        assertEquals(ExactDecimal.parse("0.5"), CsvImport.number(".5"))
        listOf("1,5", "1 000", "-2", "1e3", "").forEach { assertFails(it) { CsvImport.number(it) } }
        assertEquals(TransactionType.TRANSFER to true, CsvImport.type("Transfer In"))
    }

    private val account = AccountId("exchange")

    private fun app(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.saveAccount(Account(account, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
        return app
    }

    private val options = CsvImportOptions(account, AssetId("USD"), CsvFeeDefault.CURRENCY, { CsvImport.utcMillis(it) })

    private val file =
        "Date,Type,Asset,Quantity,Price,Fee,Notes\r\n" +
            "2026-01-15T10:00:00Z,deposit,USD,5000,,,Opening cash\r\n" +
            "2026-01-15T10:30:00Z,buy,BTC,0.05,42000,1.5,\r\n" +
            "2026-01-16T08:00:00Z,buy,BTC,0.01,43000,,Same order twice\r\n" +
            "2026-01-16T08:00:00Z,buy,BTC,0.01,43000,,Same order twice\r\n" +
            "16/01/2026,buy,BTC,1,1,,\r\n" +
            "2026-01-17T08:00:00Z,buy,DOGE,10,0.1,,\r\n"

    @Test fun importsOnceUndoesAndRestores() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = app(db)
            val preview = app.previewCsvImport("trades.csv", file, options)
            assertEquals(
                listOf(CsvRowStatus.NEW, CsvRowStatus.NEW, CsvRowStatus.NEW, CsvRowStatus.NEW, CsvRowStatus.INVALID, CsvRowStatus.INVALID),
                preview.rows.map { it.status },
            )
            assertEquals(listOf(2, 3, 4, 5, 6, 7), preview.rows.map { it.line })
            assertEquals(setOf("DOGE"), preview.unresolvedAssets)
            assertNotEquals(preview.rows[2].draft!!.id, preview.rows[3].draft!!.id)
            assertTrue(preview.rows[5].message!!.contains("DOGE"))
            // Nothing is saved by a preview.
            assertTrue(app.transactionEntries(ActivityFilter()).isEmpty())

            val receipt = app.importCsv(preview)
            assertEquals(listOf(6, 4, 0, 2), listOf(receipt.rows, receipt.imported, receipt.alreadyImported, receipt.invalid))
            assertEquals(4, app.transactionEntries(ActivityFilter()).size)
            val btc = {
                app
                    .snapshot()
                    .valuations
                    .firstOrNull { it.asset == BITCOIN_ASSET }
                    ?.amount
                    ?.atomicUnits
                    ?.toString()
            }
            assertEquals("7000000", btc())
            // The same stale preview imported again saves nothing new.
            assertEquals(0, app.importCsv(preview).imported)
            assertEquals(4, app.transactionEntries(ActivityFilter()).size)

            val again = app.previewCsvImport("copy.csv", file, options)
            assertEquals(4, again.count(CsvRowStatus.ALREADY_IMPORTED))
            assertEquals(listOf(receipt.id), again.previousImports.map { it.id }.take(1))
            assertFails { app.importCsv(app.previewCsvImport("none.csv", file.lines().take(2).joinToString("\n"), options)) }

            assertEquals(4, app.reverseCsvImport(receipt.id))
            assertFails { app.reverseCsvImport(receipt.id) }
            assertTrue(app.transactionEntries(ActivityFilter()).all { it.activity.status == ActivityStatus.REVERSED })
            assertEquals(null, btc()?.takeIf { it != "0" })

            val restored = app.previewCsvImport("trades.csv", file, options)
            assertEquals(4, restored.count(CsvRowStatus.RESTORE))
            assertEquals(4, app.importCsv(restored).imported)
            assertEquals("7000000", btc())
            assertEquals(3, app.csvImportReceipts().size)
            assertEquals(6, app.csvImportRows(receipt.id).size)
        }
    }

    @Test fun referencesIdentifyRowsAndChoicesAreExplicit() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = app(db)
            val text =
                "date,type,asset,quantity,price,fee,fee_currency,reference\n" +
                    "2026-01-15,buy,XBT,1,40000,0.001,,A1\n2026-01-15,sell,BTC,1,1,,,A1\n"
            val preview =
                app.previewCsvImport(
                    "ref.csv",
                    text,
                    options.copy(assets = mapOf("XBT" to BITCOIN_ASSET), feeDefault = CsvFeeDefault.ASSET),
                )
            assertEquals(listOf(CsvRowStatus.INVALID, CsvRowStatus.INVALID), preview.rows.map { it.status })
            assertEquals("Another row has the same reference", preview.rows.first().message)
            val single =
                app.previewCsvImport(
                    "ref.csv",
                    text.lines().take(2).joinToString("\n"),
                    options.copy(
                        assets =
                            mapOf(
                                "XBT" to BITCOIN_ASSET,
                            ),
                        feeDefault = CsvFeeDefault.ASSET,
                    ),
                )
            assertEquals(
                BITCOIN_ASSET,
                single.rows
                    .single()
                    .draft!!
                    .feeAsset,
            )
            assertFails { app.previewCsvImport("x.csv", text, options.copy(account = AccountId("missing"))) }
            assertFails { app.previewCsvImport("x.csv", "date,type,quantity\n", options) }
        }
    }
}
