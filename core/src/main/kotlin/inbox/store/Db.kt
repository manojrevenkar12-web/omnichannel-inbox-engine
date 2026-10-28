package inbox.store

import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/**
 * Plain JDBC with explicit transactions. Every unit of work that must be atomic (store a webhook
 * fact and update its conversation; record a message and queue its send) runs in one [tx].
 */
@ApplicationScoped
class Db(private val ds: AgroalDataSource) {
    fun <T> tx(block: (Connection) -> T): T =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                val r = block(c)
                c.commit()
                r
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }

    fun <T> read(block: (Connection) -> T): T = ds.connection.use { c -> block(c) }
}

fun Connection.prepare(sql: String, vararg args: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> ps.setNull(idx, Types.NULL)
            is Instant -> ps.setTimestamp(idx, Timestamp.from(a))
            is String -> ps.setString(idx, a)
            is Int -> ps.setInt(idx, a)
            is Long -> ps.setLong(idx, a)
            is Boolean -> ps.setBoolean(idx, a)
            is Double -> ps.setDouble(idx, a)
            is Enum<*> -> ps.setString(idx, a.name)
            else -> ps.setObject(idx, a)
        }
    }
    return ps
}

fun Connection.update(sql: String, vararg args: Any?): Int = prepare(sql, *args).use { it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *args).use { ps ->
        ps.executeQuery().use { rs ->
            val out = mutableListOf<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

fun <T> Connection.one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

fun ResultSet.instant(col: String): Instant? = getTimestamp(col)?.toInstant()

fun ResultSet.str(col: String): String? = getString(col)
