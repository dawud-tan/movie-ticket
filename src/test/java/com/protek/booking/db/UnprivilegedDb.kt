package com.protek.booking.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections

/**
 * A [Db] whose connections belong to a role that row-level security is actually applied to,
 * for tests that need to observe policy behaviour rather than merely assume it.
 *
 * **Why this exists.** Dev Services' Testcontainers role is created SUPERUSER by the postgres
 * image, and an RLS-exempt role never evaluates a policy expression at all — so the entire suite
 * can be green while every policy in the schema is wrong. Two real bugs reached deployment that
 * way. Installing this over the `Db` bean with
 * `QuarkusMock.installMockForType` makes the production code under test run its own queries,
 * unchanged, against a role that `FORCE ROW LEVEL SECURITY` binds.
 *
 * Lives in `com.protek.booking.db` rather than beside its test because `Db`
 * resolves [ActorContext] by injection and the field was package-private when `Db` was Java
 * (it is a `lateinit var` now, pattern 14); a hand-built
 * instance has to be handed the real one or `setTenantContext` NPEs on the actor half of
 * the GUC pair it publishes. Overriding [inTransaction] alone is enough to redirect
 * `inTenantTransaction` too, and deliberately so: that leaves the real
 * `set_config(..., is_local => true)` call as the thing under test rather than a
 * reimplementation of it that could agree with a broken original.
 *
 * Connections are per-thread and **reused** across transactions, which mirrors Agroal and is
 * load-bearing, not incidental: `current_setting(name, true)` reads back NULL only while the
 * GUC has never been set on that connection, and `''` ever after. A fresh connection per
 * transaction would test the state a warm pool is never in.
 */
class UnprivilegedDb(
    private val url: String,
    private val user: String,
    private val password: String,
    actorContext: ActorContext,
) : Db(), AutoCloseable {

    private val perThread = ThreadLocal<Connection>()
    private val inTransaction: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    private val opened: MutableList<Connection> = Collections.synchronizedList(ArrayList())

    init {
        this.actorContext = actorContext
    }

    override fun <T> inTransaction(work: TxWork<T>): T {
        if (inTransaction.get()) {
            // Agroal would hand out a second connection here; one reused connection cannot, and
            // would silently commit the outer transaction early. Nothing nests today — fail loudly
            // rather than let a future caller get a quietly wrong answer.
            throw IllegalStateException(
                "nested inTransaction on a single reused connection is not supported by this test double")
        }
        val c: Connection
        try {
            c = connection()
        } catch (e: SQLException) {
            throw DataAccessException("<get-connection>", e)
        }
        inTransaction.set(true)
        try {
            val result = work.run(c)
            c.commit()
            return result
        } catch (e: Exception) {
            try {
                c.rollback()
            } catch (ignored: SQLException) {
                // the connection is already broken; the original failure is what matters
            }
            throw wrapFailure(e)
        } finally {
            inTransaction.set(false)
        }
    }

    /** Mirrors `Db.wrap`, which is private: a DataAccessException or any other runtime
     * exception must reach the caller unchanged, since tests assert on the ones the service
     * throws by hand (NotFoundException in particular). */
    private fun wrapFailure(e: Exception): RuntimeException = when (e) {
        is RuntimeException -> e
        is SQLException -> DataAccessException("<transaction>", e)
        else -> RuntimeException(e)
    }

    /** One connection per thread, because the post-commit notify path runs on a ManagedExecutor
     * thread and would otherwise share a transaction with the caller that triggered it. */
    @Throws(SQLException::class)
    private fun connection(): Connection {
        var c = perThread.get()
        if (c == null || c.isClosed) {
            c = DriverManager.getConnection(url, user, password)
            c.autoCommit = false
            perThread.set(c)
            opened.add(c)
        }
        return c
    }

    override fun close() {
        synchronized(opened) {
            for (c in opened) {
                try {
                    c.close()
                } catch (ignored: SQLException) {
                    // best effort; the throwaway role is dropped either way
                }
            }
            opened.clear()
        }
        perThread.remove()
    }
}
