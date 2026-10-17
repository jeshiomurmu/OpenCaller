package io.github.jeshiomurmu.opencaller.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.jeshiomurmu.opencaller.server.config.DatabaseConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Closeable
import java.sql.Connection

/**
 * The PostgreSQL pool, migrated by Flyway on start (db/migration), with Exposed on top. Queries run in [query] on
 * the injected I/O dispatcher so request coroutines never block on JDBC.
 */
class ServerDatabase private constructor(
    private val dataSource: HikariDataSource,
    private val io: CoroutineDispatcher,
) : Closeable {
    private val database = Database.connect(dataSource)

    /** Runs [block] in one READ COMMITTED transaction on the I/O dispatcher (row locks via `forUpdate()` where needed). */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(io) {
            transaction(database, Connection.TRANSACTION_READ_COMMITTED) { block() }
        }

    /**
     * Runs [block] only if this process gets the PostgreSQL session advisory lock [key] (`pg_try_advisory_lock`),
     * so one server instance runs a job at a time; returns `null` without running it if another holds the lock.
     * The lock lives on its own pooled connection and is released when [block] ends (or the connection dies).
     */
    suspend fun <T> withAdvisoryLock(
        key: Long,
        block: suspend () -> T,
    ): T? {
        val connection = withContext(io) { dataSource.connection.apply { autoCommit = true } }
        try {
            if (!withContext(io) { connection.advisory("SELECT pg_try_advisory_lock(?)", key) }) return null
            try {
                return block()
            } finally {
                withContext(NonCancellable + io) { connection.advisory("SELECT pg_advisory_unlock(?)", key) }
            }
        } finally {
            withContext(NonCancellable + io) { connection.close() }
        }
    }

    private fun Connection.advisory(
        sql: String,
        key: Long,
    ): Boolean =
        prepareStatement(sql).use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }

    override fun close() = dataSource.close()

    /** Creates the pool, applies pending migrations and connects Exposed. */
    companion object {
        fun connect(
            config: DatabaseConfig,
            io: CoroutineDispatcher,
        ): ServerDatabase {
            val dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.url
                        username = config.user
                        password = config.password
                        maximumPoolSize = config.maxPoolSize
                        poolName = "opencaller"
                        isAutoCommit = false
                    },
                )
            try {
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            } catch (e: org.flywaydb.core.api.FlywayException) {
                dataSource.close()
                throw e
            }
            return ServerDatabase(dataSource, io)
        }
    }
}
