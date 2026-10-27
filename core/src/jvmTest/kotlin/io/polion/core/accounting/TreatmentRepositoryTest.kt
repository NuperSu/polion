package io.polion.core.accounting

import io.polion.core.model.ActivityId
import io.polion.core.model.EventId
import io.polion.core.model.Rational
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TreatmentRepositoryTest {
    @Test fun survivesReopen() {
        val path = Files.createTempFile("accounting", ".sqlite")
        try {
            val decisions =
                listOf(
                    Treatment.Acquire(EventId("a"), Rational.of("20")),
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
}
