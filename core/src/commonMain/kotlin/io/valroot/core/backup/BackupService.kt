package io.valroot.core.backup

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.SqliteDatabase

/** Encrypted backup export, restore into staging, and erasing all data. */
class BackupService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** Streams an encrypted backup of every durable table into [output]. */
    fun export(
        password: CharArray,
        output: ByteSink,
    ) {
        val encrypted = BackupEncryption.encrypt(output, password)
        PortableBackup.discover(db).export(db, encrypted)
        encrypted.close()
    }

    /** In-memory form of [export], for small data and tests. */
    fun export(password: CharArray): ByteArray = ByteArraySink().also { export(password, it) }.toByteArray()

    /** Decrypts [input] and restores it into the empty [staging] database; the live database is untouched. */
    fun restoreToStaging(
        staging: SqliteDatabase,
        input: ByteSource,
        password: CharArray,
    ) {
        PortfolioApplication(staging, now)
        PortableBackup.discover(db).restoreToStaging(staging, BackupEncryption.decrypt(input, password))
    }

    /** In-memory form of [restoreToStaging], for small data and tests. */
    fun restoreToStaging(
        staging: SqliteDatabase,
        archive: ByteArray,
        password: CharArray,
    ) = restoreToStaging(staging, ByteArraySource(archive), password)

    /** Erases application rows and purges deleted content from active SQLite files. */
    fun wipe() {
        db.query("PRAGMA secure_delete = ON")
        db.query("PRAGMA journal_size_limit = 0")
        db.transaction {
            val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it.single()!! }
            require(tables.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
            db.execute("PRAGMA defer_foreign_keys = ON")
            tables.filterNot { it.endsWith("_schema") || it == "android_metadata" }.forEach { db.execute("DELETE FROM $it") }
            check(db.query("PRAGMA foreign_key_check").isEmpty())
            invalidate()
        }
        // Rebuild free pages containing data deleted before secure deletion was enabled.
        db.execute("VACUUM")
        val checkpoint = db.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
        check(checkpoint[0] == "0") { "Database cleanup is busy; retry erasure" }
    }
}
