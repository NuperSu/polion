package io.polion.core.storage

import android.database.sqlite.SQLiteDatabase

class AndroidSqliteDatabase(
    path: String,
) : SqliteDatabase {
    private val database = SQLiteDatabase.openOrCreateDatabase(path, null)
    private val lock = Any()

    override fun execute(
        sql: String,
        arguments: List<String?>,
    ) = synchronized(lock) {
        if (arguments.isEmpty()) database.execSQL(sql) else database.execSQL(sql, arguments.toTypedArray())
    }

    override fun query(
        sql: String,
        arguments: List<String?>,
    ): List<List<String?>> =
        synchronized(lock) {
            require(arguments.none { it == null })
            database.rawQuery(sql, arguments.map { it!! }.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }

    override fun <T> transaction(block: () -> T): T =
        synchronized(lock) {
            check(!database.inTransaction()) { "Nested transactions are unsupported" }
            database.beginTransaction()
            try {
                val result = block()
                database.setTransactionSuccessful()
                result
            } finally {
                database.endTransaction()
            }
        }

    override fun close() = synchronized(lock) { database.close() }
}
