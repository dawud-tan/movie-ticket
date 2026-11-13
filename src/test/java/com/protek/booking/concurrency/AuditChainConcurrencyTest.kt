package com.protek.booking.concurrency

import com.protek.booking.db.DataAccessException
import com.protek.booking.domain.Reservation
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.support.TestFixture
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.UUID

/**
 * The audit log's hash chain with two writers at once: the second waits, and the chain does not fork.
 *
 * `reservation_audit()` and `app_user_privilege_audit()` (V1, through `audit_append()`)
 * link each new `audit_log` row to the tenant's latest one. Until the tenant lock was added that read took
 * no lock, so two transactions of one tenant appending at once both linked to the same row, and the chain's
 * walk (each hash recomputed from the previous row's, in `audit_id` order) reported the second
 * as tampered with. This class pinned that fork from 2026-09-25. Both functions now take a
 * per-tenant advisory lock first, so the second append waits for the first to commit.
 *
 * **Two connections on one thread, and no sleep.** The first append is held open,
 * uncommitted. The second, on a connection with a short `lock_timeout`, must then fail with
 * `55P03`: it is waiting for the first. Re-run after the first commits, it links to it, and
 * the walk is clean. If the second append ever goes through while the first is open, the lock is
 * gone and the chain can fork again.
 *
 * The second test is the control: the same two appends, one after the other, walk clean. That
 * proves the walk here recomputes the trigger's hash correctly. `AuditLogRepositoryTest`
 * checks the same recipe, one entity at a time.
 */
@QuarkusTest
class AuditChainConcurrencyTest {

    companion object {
        private val PRICE = BigDecimal("50000.00")

        /** Opens a transaction with the tenant context set, as `Db.inTenantTransaction` does.
         * The actor GUCs are left unset, so the trigger records (and hashes) 'system'. */
        private fun begin(c: Connection, tenantId: UUID) {
            c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
                ps.setString(1, tenantId.toString())
                ps.execute()
            }
        }

        /** The chain's verification: recompute each row's hash from its own stored
         * content plus the previous row's stored hash, in audit_id order. Returns the rows whose
         * stored hash does not match, which a verifier would report as tampered with. */
        private fun walk(chain: List<Link>): List<Long> {
            val broken = ArrayList<Long>()
            var prior: String? = ""
            for (link in chain) {
                if (hash(prior, link.content) != link.rowHash) {
                    broken.add(link.auditId)
                }
                prior = link.rowHash
            }
            return broken
        }

        /** audit_append's `sha256_hex(prior || sha256_hex(audit_content(row)))`, in Kotlin. */
        private fun hash(prior: String?, content: String?): String = sha256Hex(prior + sha256Hex(content!!))

        private fun sha256Hex(input: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8)))

        private fun rollbackQuietly(c: Connection) {
            try {
                if (!c.isClosed && !c.autoCommit) {
                    c.rollback()
                }
            } catch (ignored: SQLException) {
                // Best effort after a failure; the assertion or exception already in flight is the
                // thing worth reporting.
            }
        }
    }

    @Inject
    lateinit var dataSource: AgroalDataSource
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture

    /** One row of the chain as a verifier sees it: its position, its stored hash, and the text
     * its content hash is taken over (`audit_content`). */
    private data class Link(val auditId: Long, val rowHash: String?, val content: String?)

    /** A fresh tenant, so its chain holds nothing but this test's rows, and two held reservations
     * on different seats, so confirming them touches two different rows and neither waits for
     * the other. */
    private data class Setup(val tenantId: UUID, val first: Reservation, val second: Reservation)

    @Test
    fun twoOverlappingAppends_theSecondWaitsForTheFirst_andTheWalkFindsNothing() {
        val s = twoHeldReservations()
        val before = chain(s.tenantId)

        dataSource.connection.use { c1 -> dataSource.connection.use { c2 ->
            try {
                begin(c1, s.tenantId)
                begin(c2, s.tenantId)
                c2.prepareStatement("SET LOCAL lock_timeout = '1s'").use { ps ->
                    ps.execute()
                }

                // Appends, and stays uncommitted: it now holds the tenant's audit lock.
                reservationRepo.confirm(c1, s.tenantId, s.first.reservationId)
                try {
                    reservationRepo.confirm(c2, s.tenantId, s.second.reservationId)
                    fail<Unit>("the second append went through while the first was uncommitted: the"
                            + " tenant's audit lock is gone, and the chain can fork again")
                } catch (e: DataAccessException) {
                    assertEquals("55P03", e.sqlState(), "the second append must be WAITING for the"
                            + " first (lock_timeout), not failing for another reason: " + e.message)
                }
                c2.rollback()
                c1.commit()
            } finally {
                rollbackQuietly(c1)
                rollbackQuietly(c2)
            }
        } }
        // What the waiting writer does once the first commits.
        dataSource.connection.use { c ->
            begin(c, s.tenantId)
            reservationRepo.confirm(c, s.tenantId, s.second.reservationId)
            c.commit()
        }

        val after = chain(s.tenantId)
        assertEquals(before.size + 2, after.size, "exactly the two confirmations were appended")
        val first = after[after.size - 2]
        val second = after[after.size - 1]
        assertEquals(hash(first.rowHash, second.content), second.rowHash,
                "the second append links to the first, not to their common predecessor")
        assertEquals(listOf<Long>(), walk(after), "the chain's walk finds nothing")
        assertEquals(listOf<Long>(), walkInSql(s.tenantId),
                "and the SQL walk (the one an operator runs against the live audit_log) agrees")
    }

    @Test
    fun theSameTwoAppendsOneAfterTheOther_linkInARow_andTheWalkFindsNothing() {
        val s = twoHeldReservations()
        for (r in listOf(s.first, s.second)) {
            dataSource.connection.use { c ->
                begin(c, s.tenantId)
                reservationRepo.confirm(c, s.tenantId, r.reservationId)
                c.commit()
            }
        }

        val after = chain(s.tenantId)
        assertEquals(listOf<Long>(), walk(after),
                "appended one after the other, every row must verify. A failure here means this"
                        + " class recomputes the trigger's hash wrongly, and the first test proves nothing")
        assertEquals(listOf<Long>(), walkInSql(s.tenantId), "and the SQL walk agrees")
    }

    // ------------------------------------------------------------------------------------------

    private fun twoHeldReservations(): Setup {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val a1 = fixture.unit(tenant, pool, "A1", "A", 1)
        val a2 = fixture.unit(tenant, pool, "A2", "A", 2)
        val showing = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 2)
        return Setup(tenant.tenantId,
                fixture.reservation(tenant, group, a1, showing, policy, PRICE),
                fixture.reservation(tenant, group, a2, showing, policy, PRICE))
    }

    /** The tenant's whole chain, in the order the verification walks it. Deliberately every entity type
     * rather than `AuditLogRepository.findByEntity`'s one entity: a fork is only visible
     * where two entities' rows interleave. */
    private fun chain(tenantId: UUID): List<Link> {
        dataSource.connection.use { c ->
            begin(c, tenantId)
            val links = ArrayList<Link>()
            c.prepareStatement(
                    "SELECT audit_id, row_hash, audit_content(a) AS content "
                            + "FROM audit_log a WHERE tenant_id = ? ORDER BY audit_id").use { ps ->
                ps.setObject(1, tenantId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        links.add(Link(rs.getLong("audit_id"), rs.getString("row_hash"),
                                rs.getString("content")))
                    }
                }
            }
            c.commit()
            return links
        }
    }

    /** The same walk as one read-only query, which is the shape an operator can run against the
     * live `audit_log` (for every tenant at once, partition it by tenant). Asserted
     * equal to [walk] in both tests, so that query is checked by the suite rather than
     * trusted. */
    private fun walkInSql(tenantId: UUID): List<Long> {
        dataSource.connection.use { c ->
            begin(c, tenantId)
            val broken = ArrayList<Long>()
            c.prepareStatement(
                    "SELECT audit_id FROM ("
                            + "  SELECT audit_id, row_hash, encode(digest("
                            + "      COALESCE(lag(row_hash) OVER (ORDER BY audit_id), '')"
                            + "      || encode(digest(audit_content(a), 'sha256'), 'hex'), 'sha256'), 'hex') AS expected"
                            + "  FROM audit_log a WHERE tenant_id = ?) walk "
                            + "WHERE row_hash <> expected ORDER BY audit_id").use { ps ->
                ps.setObject(1, tenantId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        broken.add(rs.getLong(1))
                    }
                }
            }
            c.commit()
            return broken
        }
    }
}
