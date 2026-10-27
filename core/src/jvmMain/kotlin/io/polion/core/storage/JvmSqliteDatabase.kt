package io.polion.core.storage

import java.sql.DriverManager

class JvmSqliteDatabase(
    path: String,
) : SqliteDatabase {
    private val connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val lock = Any()

    override fun execute(
        sql: String,
        arguments: List<String?>,
    ) = synchronized(lock) {
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.execute()
        }
        Unit
    }

    override fun query(
        sql: String,
        arguments: List<String?>,
    ): List<List<String?>> =
        synchronized(lock) {
            connection.prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { i, value -> statement.setString(i + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                    }
                }
            }
        }

    override fun <T> transaction(block: () -> T): T =
        synchronized(lock) {
            check(connection.autoCommit) { "Nested transactions are unsupported" }
            connection.autoCommit = false
            try {
                val result = block()
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    /** Create a transactionally consistent snapshot in a new file. */
    fun snapshot(destination: String) =
        synchronized(lock) {
            check(connection.autoCommit) { "Snapshot requires no active transaction" }
            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, destination)
                statement.execute()
            }
        }

    override fun close() = synchronized(lock) { connection.close() }
}
