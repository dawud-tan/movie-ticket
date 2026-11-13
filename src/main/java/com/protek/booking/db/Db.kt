package com.protek.booking.db

import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The whole data-access layer: plain `java.sql` via Agroal, no ORM, no
 * query-builder. A repository class per entity calls [query]/[execute]
 * with a literal SQL string and a small mapping lambda.
 *
 * One thing beyond plain JDBC: every tenant-scoped table
 * carries `FORCE ROW LEVEL SECURITY`, so a query against one of them
 * only sees rows once `app.current_tenant_id` is set on the connection for the
 * current transaction. [inTenantTransaction] does that with `SET LOCAL`
 * semantics (via `set_config(..., is_local => true)`, since `SET LOCAL`
 * itself doesn't accept bind parameters) so the setting resets at commit/rollback
 * rather than leaking into whatever request reuses the pooled connection next.
 */
@ApplicationScoped
class Db {

    @Inject
    lateinit var dataSource: AgroalDataSource

    /** Who is acting, for `audit_log.actor_id` (every write is attributable to a
     * person or to the system). See [ActorContext] for why the interface lives in this package.
     *
     * `lateinit`, so the backing field is public: `UnprivilegedDb`, a Java test double in
     * this package, assigns it directly in its constructor. */
    @Inject
    lateinit var actorContext: ActorContext

    /** `@Throws` because Java lambdas implement this and run JDBC calls that throw a checked
     * `SQLException`; without the declaration javac rejects every one of them. */
    fun interface TxWork<T> {
        @Throws(SQLException::class)
        fun run(c: Connection): T
    }

    /** Runs `work` inside one transaction with the RLS tenant context set for its
     * duration. Commits on normal return, rolls back and rethrows otherwise. */
    fun <T> inTenantTransaction(tenantId: UUID?, work: TxWork<T>): T =
        inTransaction { c ->
            setTenantContext(c, tenantId)
            work.run(c)
        }

    /** Runs `work` inside one transaction with no tenant context set. Only valid
     * for statements against `tenant` itself, which carries no RLS policy —
     * every other tenant-scoped table will simply return zero rows without a context. */
    fun <T> inTransaction(work: TxWork<T>): T {
        try {
            dataSource.connection.use { c ->
                c.autoCommit = false
                try {
                    val result = work.run(c)
                    c.commit()
                    return result
                } catch (e: Exception) {
                    safeRollback(c)
                    throw wrap(e)
                }
            }
        } catch (e: SQLException) {
            throw DataAccessException("<get-connection>", e)
        }
    }

    private fun safeRollback(c: Connection) {
        try {
            c.rollback()
        } catch (ignored: SQLException) {
            // connection is already broken; the original exception is what matters
        }
    }

    /**
     * Publishes the three GUCs the schema reads: `app.current_tenant_id` for the RLS
     * policies, and `app.current_actor_id` plus
     * `app.current_actor_type` for the audit trigger.
     *
     * All three use `is_local => true`, so all three reset at commit or rollback
     * rather than leaking into whichever request reuses this pooled connection next. The actor
     * is set in the same statement batch as the tenant deliberately: an audit row attributed to
     * the previous request's actor would be worse than one attributed to nobody.
     *
     * The empty string, not SQL NULL, is what an absent actor sends — `set_config`
     * has no null-valued setting, and the trigger's `NULLIF(..., '')` turns it back
     * into a NULL `actor_id` on the way in.
     *
     * The *type* is never empty in practice, because "nobody is signed in" is itself
     * an answer — `'system'`. The trigger still coalesces, for connections that write
     * reservation rows without ever coming through here at all: a migration, or a psql session.
     *
     * `tenantId!!` because the Java took the parameter and threw its NPE here, on
     * `toString()`, inside the transaction; a non-null parameter would throw before it.
     */
    @Throws(SQLException::class)
    private fun setTenantContext(c: Connection, tenantId: UUID?) {
        c.prepareStatement(
            "SELECT set_config('app.current_tenant_id', ?, true), " +
                    "set_config('app.current_actor_id', ?, true), " +
                    "set_config('app.current_actor_type', ?, true)"
        ).use { ps ->
            ps.setString(1, tenantId!!.toString())
            ps.setString(2, actorContext.currentActorId().map(UUID::toString).orElse(""))
            ps.setString(3, actorContext.currentActorType())
            ps.execute()
        }
    }

    fun <T> query(c: Connection, sql: String, mapper: RowMapper<T>, vararg params: Any?): List<T> {
        try {
            c.prepareStatement(sql).use { ps ->
                bind(ps, *params)
                ps.executeQuery().use { rs ->
                    val results = ArrayList<T>()
                    while (rs.next()) {
                        results.add(mapper.map(rs))
                    }
                    return results
                }
            }
        } catch (e: SQLException) {
            throw DataAccessException(sql, e)
        }
    }

    fun <T : Any> queryOne(c: Connection, sql: String, mapper: RowMapper<T>, vararg params: Any?): Optional<T> {
        val rows = query(c, sql, mapper, *params)
        if (rows.isEmpty()) {
            return Optional.empty()
        }
        if (rows.size > 1) {
            throw IllegalStateException("Expected at most one row but got " + rows.size + " for: " + sql)
        }
        return Optional.of(rows[0])
    }

    fun execute(c: Connection, sql: String, vararg params: Any?): Int {
        try {
            c.prepareStatement(sql).use { ps ->
                bind(ps, *params)
                return ps.executeUpdate()
            }
        } catch (e: SQLException) {
            throw DataAccessException(sql, e)
        }
    }

    /**
     * One statement, many parameter sets, one round trip per batch — for the seat-map
     * generator, which inserts a whole auditorium at once.
     *
     * Exists because the per-row alternative is not merely slower, it is a different
     * order of magnitude: a 38-branch chain at ~3 studios each and 100-200 seats is on the
     * order of 15,000-25,000 `resource_unit` rows, and the admin UI previously wrote
     * them one HTTP POST at a time.
     *
     * Takes a [Connection] like everything else here, so the caller decides the
     * transaction boundary. That matters: a half-generated seat map is worse than none, and
     * the generator runs the whole plan inside one `inTenantTransaction`.
     */
    fun executeBatch(c: Connection, sql: String, rows: List<Array<Any?>>): Int {
        if (rows.isEmpty()) {
            return 0
        }
        try {
            c.prepareStatement(sql).use { ps ->
                for (params in rows) {
                    bind(ps, *params)
                    ps.addBatch()
                }
                var total = 0
                for (updated in ps.executeBatch()) {
                    // A driver may return SUCCESS_NO_INFO (-2) rather than a count. Postgres
                    // returns real counts, but treating a negative as one row keeps the total
                    // honest rather than negative if that ever changes.
                    total += if (updated < 0) 1 else updated
                }
                return total
            }
        } catch (e: SQLException) {
            throw DataAccessException(sql, e)
        }
    }

    @Throws(SQLException::class)
    private fun bind(ps: PreparedStatement, vararg params: Any?) {
        for (i in params.indices) {
            val p = params[i]
            if (p is Instant) {
                ps.setTimestamp(i + 1, Timestamp.from(p))
            } else {
                ps.setObject(i + 1, p)
            }
        }
    }

    private fun wrap(e: Exception): RuntimeException = when (e) {
        is DataAccessException -> e
        is SQLException -> DataAccessException("<transaction>", e)
        is RuntimeException -> e
        else -> RuntimeException(e)
    }
}
