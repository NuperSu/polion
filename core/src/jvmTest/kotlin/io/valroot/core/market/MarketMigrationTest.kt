package io.valroot.core.market

import io.valroot.core.model.AssetId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketMigrationTest {
    @Test
    fun migratesVersionOneAndRejectsIncompleteSchemaAtomically() {
        val path = Files.createTempFile("valroot-market-migrate", ".db")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                val repository = MarketRepository(db)
                repository.registerAsset(
                    AssetDefinition(AssetId("usd"), AssetIdentity(AssetKind.FIAT, null, "USD")),
                    AssetMetadata("USD", "Dollar", 2, 0),
                )
                db.execute("DROP TABLE market_override_withdrawals")
                db.execute("UPDATE market_schema SET version=1")
                val migrated = MarketRepository(db)
                assertEquals("USD", migrated.metadataAt(AssetId("usd"), 0)?.symbol)
                assertEquals("2", db.query("SELECT version FROM market_schema").single().single())
                db.execute("DROP TABLE market_override_withdrawals")
                db.execute("DROP TABLE market_prices")
                db.execute("UPDATE market_schema SET version=1")
                assertFailsWith<IllegalStateException> { MarketRepository(db) }
                assertEquals("1", db.query("SELECT version FROM market_schema").single().single())
                assertEquals(emptyList(), db.query("SELECT name FROM sqlite_master WHERE name='market_override_withdrawals'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
