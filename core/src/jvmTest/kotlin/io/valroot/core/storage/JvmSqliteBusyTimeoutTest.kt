package io.valroot.core.storage

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmSqliteBusyTimeoutTest {
    @Test fun aSecondConnectionWaitsForAWriterInsteadOfFailing() {
        val file = Files.createTempFile("valroot-busy", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { first ->
                JvmSqliteDatabase(file.toString()).use { second ->
                    assertEquals("5000", second.query("PRAGMA busy_timeout").single().single())
                    first.execute("CREATE TABLE t (v TEXT)")
                    val writer =
                        thread {
                            first.transaction {
                                first.execute("INSERT INTO t VALUES ('a')")
                                Thread.sleep(500)
                            }
                        }
                    Thread.sleep(100)
                    // Without a busy timeout this write fails immediately with SQLITE_BUSY.
                    second.execute("INSERT INTO t VALUES ('b')")
                    writer.join()
                    assertEquals(2, second.query("SELECT v FROM t").size)
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
