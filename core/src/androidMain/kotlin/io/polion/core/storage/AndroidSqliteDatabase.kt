package io.polion.core.storage

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream

class AndroidSqliteDatabase(
    path: String,
) : SqliteDatabase {
    private val database = SQLiteDatabase.openOrCreateDatabase(path, null)
    private val lock = Any()
    private var oversizedQueryId = 0L

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
            var columnCount = 0
            try {
                database.rawQuery(sql, arguments.map { it!! }.toTypedArray()).use { cursor ->
                    columnCount = cursor.columnCount
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            } catch (failure: SQLiteBlobTooBigException) {
                queryOversizedRows(sql, arguments, columnCount, failure)
            }
        }

    /** Materialize once to preserve ordering and expression values while reading cells in bounded windows. */
    private fun queryOversizedRows(
        sql: String,
        arguments: List<String?>,
        columnCount: Int,
        failure: SQLiteBlobTooBigException,
    ): List<List<String?>> {
        val statement = sql.trim().trimEnd(';')
        if (columnCount == 0 || !Regex("(?is)^(SELECT|WITH)(?:\\s|$)").containsMatchIn(statement)) throw failure
        val table = "polion_oversized_${oversizedQueryId++}"
        val columns = (0 until columnCount).map { "c$it" }
        val ownsTransaction = !database.inTransaction()
        if (ownsTransaction) database.beginTransaction()
        try {
            database.execSQL(
                "CREATE TEMP TABLE $table AS WITH input(${columns.joinToString(",")}) AS ($statement) SELECT * FROM input",
                arguments.toTypedArray(),
            )
            try {
                val count = database.compileStatement("SELECT COUNT(*) FROM $table").use { it.simpleQueryForLong() }
                val rows =
                    buildList {
                        for (row in 1..count) {
                            add(columns.map { column -> readOversizedCell(table, column, row) })
                        }
                    }
                if (ownsTransaction) database.setTransactionSuccessful()
                return rows
            } finally {
                database.execSQL("DROP TABLE $table")
            }
        } finally {
            if (ownsTransaction) database.endTransaction()
        }
    }

    private fun readOversizedCell(
        table: String,
        column: String,
        row: Long,
    ): String? {
        val length =
            database
                .rawQuery(
                    "SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid=?",
                    arrayOf(row.toString()),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    if (cursor.isNull(0)) return null
                    cursor.getLong(0)
                }
        val bytes = ByteArrayOutputStream()
        var offset = 1L
        while (offset <= length) {
            database
                .rawQuery(
                    "SELECT substr(CAST($column AS BLOB), ?, 32768) FROM $table WHERE rowid=?",
                    arrayOf(offset.toString(), row.toString()),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    bytes.write(cursor.getBlob(0))
                }
            offset += 32768
        }
        return bytes.toString(Charsets.UTF_8.name())
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
