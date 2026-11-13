package com.protek.booking.concurrency

import com.protek.booking.support.ScratchPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The differential half of [SeatBookingConcurrencyTest]: the identical concurrent
 * race, run against a scratch schema built from the real V1 migration with exactly one
 * change — the `reservation_no_overlap` EXCLUDE constraint stripped out. If
 * double-booking does NOT occur here, `SeatBookingConcurrencyTest` passing would be
 * luck, not evidence — this is what turns it into evidence.
 */
class SeatBookingConcurrencyDifferentialTest {

    companion object {
        private const val CONCURRENT_ATTEMPTS = 8
    }

    @Test
    fun withoutExcludeConstraint_concurrentHoldsForIdenticalSeatAllSucceed_doubleBookingOccurs() {
        ScratchPostgres.start().use { scratch ->
            // Java's String.replaceAll: the pattern is a regex, and Regex.replace is the same matcher call.
            scratch.applySchema("db/migration/V1__init_schema.sql") { sql -> sql.replace(Regex(
                    "(?s),\\s*CONSTRAINT reservation_no_overlap.*?WHERE \\(status IN \\('held','confirmed'\\)\\)"), "") }

            val tenantId = UUID.randomUUID()
            val poolId = UUID.randomUUID()
            val unitId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val periodStart = Instant.now().plusSeconds(3600)
            val periodEnd = periodStart.plusSeconds(7200)

            scratch.connect().use { setup ->
                setup.autoCommit = true
                execute(setup, "INSERT INTO tenant (tenant_id, slug, display_name, vertical, complaint_phone) VALUES (?,?,?,?,?)",
                        tenantId, "scratch-" + tenantId, "Scratch Tenant", "live_entertainment", "+62-0")
                execute(setup, "INSERT INTO resource_pool (resource_pool_id, tenant_id, name) VALUES (?,?,?)",
                        poolId, tenantId, "Scratch Hall")
                execute(setup, "INSERT INTO resource_unit (resource_unit_id, tenant_id, resource_pool_id, label) VALUES (?,?,?,?)",
                        unitId, tenantId, poolId, "A1")
                execute(setup, "INSERT INTO customer (customer_id, tenant_id, full_name) VALUES (?,?,?)",
                        customerId, tenantId, "Scratch Customer")
            }

            val barrier = CyclicBarrier(CONCURRENT_ATTEMPTS)
            val executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)
            val futures = ArrayList<Future<Boolean>>()
            try {
                for (i in 0 until CONCURRENT_ATTEMPTS) {
                    futures.add(executor.submit(Callable { attemptHold(
                            scratch, tenantId, poolId, unitId, customerId, periodStart, periodEnd, barrier) }))
                }
                var successes = 0L
                for (f in futures) {
                    if (f.get(30, TimeUnit.SECONDS)) {
                        successes++
                    }
                }

                val heldCount: Long = scratch.connect().use { check ->
                    check.prepareStatement(
                            "SELECT count(*) FROM reservation WHERE resource_unit_id = ? AND status = 'held'").use { q ->
                        q.setObject(1, unitId)
                        q.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                }

                // Long counts, so the expected value is a Long: Java widened it to assertEquals(long, long).
                assertEquals(CONCURRENT_ATTEMPTS.toLong(), successes,
                        "without the EXCLUDE constraint, every concurrent insert for the same seat/period succeeds")
                assertEquals(CONCURRENT_ATTEMPTS.toLong(), heldCount,
                        "double-booking: " + heldCount + " 'held' rows now coexist for the same seat/period — "
                                + "exactly the anomaly the EXCLUDE constraint normally makes structurally impossible")
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun attemptHold(scratch: ScratchPostgres, tenantId: UUID, poolId: UUID, unitId: UUID, customerId: UUID,
                            periodStart: Instant, periodEnd: Instant, barrier: CyclicBarrier): Boolean {
        try {
            scratch.connect().use { c ->
                c.autoCommit = false
                execute(c, "SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString())

                val groupId: UUID = c.prepareStatement(
                        "INSERT INTO reservation_group (tenant_id, resource_pool_id, customer_id, requested_unit_count) " +
                                "VALUES (?, ?, ?, 1) RETURNING reservation_group_id").use { gs ->
                    gs.setObject(1, tenantId)
                    gs.setObject(2, poolId)
                    gs.setObject(3, customerId)
                    gs.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }

                barrier.await(30, TimeUnit.SECONDS)

                c.prepareStatement(
                        "INSERT INTO reservation (tenant_id, reservation_group_id, resource_unit_id, period, price_amount, hold_expires_at) " +
                                "VALUES (?, ?, ?, tstzrange(?, ?), ?, ?)").use { ins ->
                    ins.setObject(1, tenantId)
                    ins.setObject(2, groupId)
                    ins.setObject(3, unitId)
                    ins.setTimestamp(4, Timestamp.from(periodStart))
                    ins.setTimestamp(5, Timestamp.from(periodEnd))
                    ins.setBigDecimal(6, BigDecimal("50000.00"))
                    ins.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(600)))
                    ins.execute()
                }
                c.commit()
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun execute(c: Connection, sql: String, vararg params: Any?) {
        c.prepareStatement(sql).use { ps ->
            for (i in params.indices) {
                ps.setObject(i + 1, params[i])
            }
            ps.execute()
        }
    }
}
