package io.polion.core.accounting

import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.EventId
import io.polion.core.model.Rational
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TreatmentRepositoryTest {
    @Test fun survivesReopen() {
        val path = Files.createTempFile("accounting", ".sqlite")
        try {
            val decisions =
                listOf(
                    Treatment.Acquire(EventId("a"), QuoteValue(AssetId("usd"), Rational.of("20"))),
                    Treatment.Dispose(EventId("b"), null, true),
                    Treatment.InternalTransfer(EventId("out"), EventId("in")),
                )
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                TreatmentRepository(db).save(ActivityId("activity"), decisions)
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                assertEquals(decisions, TreatmentRepository(db).load(ActivityId("activity")))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun legacyAmountsNeedExplicitQuoteAndCanBeReclassified() {
        JvmSqliteDatabase(":memory:").use { db ->
            db.execute(
                "CREATE TABLE accounting_treatments (activity_id TEXT NOT NULL, position INTEGER NOT NULL, kind TEXT NOT NULL, movement TEXT NOT NULL, other_movement TEXT, numerator TEXT, denominator TEXT, flag INTEGER NOT NULL, PRIMARY KEY(activity_id, position))",
            )
            db.execute("INSERT INTO accounting_treatments VALUES ('a',0,'ACQUIRE','m',NULL,'2','1',0)")
            val repository = TreatmentRepository(db)
            assertFailsWith<IllegalArgumentException> { repository.load(ActivityId("a")) }
            val corrected = listOf(Treatment.Acquire(EventId("m"), QuoteValue(AssetId("usd"), Rational.of("2"))))
            repository.save(ActivityId("a"), corrected)
            assertEquals(corrected, repository.load(ActivityId("a")))
        }
    }
}
