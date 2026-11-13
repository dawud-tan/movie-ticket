package com.protek.booking.concurrency

import com.protek.booking.db.Db
import com.protek.booking.domain.Reservation
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The anti-passback guarantee, measured the same way the double-booking guarantee is: N
 * simultaneous attempts on one ticket, released together by a [CyclicBarrier], expecting
 * exactly one to get in.
 *
 * **Why this needs a race test at all.** The realistic version is not an attack, it is a
 * party of four at two doors with one screenshotted barcode between them, or an usher whose
 * scanner fired twice. The tempting implementation — read `admitted_at`, decide, write — is
 * wrong in exactly the way a single-threaded test cannot see: both readers see NULL and both let
 * someone in. `ReservationRepository#admit` instead puts `admitted_at IS NULL` in the
 * UPDATE's own WHERE clause, so the database arbitrates. This test is what would fail if anyone
 * ever put a read-then-decide back in front of it.
 *
 * Deliberately at the repository rather than through `AdmissionService`: the conditional
 * UPDATE *is* the mechanism, and driving it directly keeps the measurement on it rather
 * than on whether a CDI request context propagates to a thread pool.
 */
@QuarkusTest
class AdmissionRescanConcurrencyTest {

    companion object {
        private const val CONCURRENT_SCANS = 8
        private val PRICE = BigDecimal("50000.00")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun concurrentScansOfOneTicket_admitExactlyOnce() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val held = fixture.reservation(tenant, group, unit, session, policy, PRICE)

        // Confirmed is the only status that admits anyone, so the race has to start from there —
        // otherwise every attempt would be refused by the status predicate and the test would
        // report "exactly one admission" by way of zero.
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, held.reservationId) }
        val token = held.admissionToken

        val barrier = CyclicBarrier(CONCURRENT_SCANS)
        val executor = Executors.newFixedThreadPool(CONCURRENT_SCANS)
        try {
            val futures = ArrayList<Future<Boolean>>()
            for (i in 0 until CONCURRENT_SCANS) {
                futures.add(executor.submit(Callable {
                    barrier.await(30, TimeUnit.SECONDS)
                    val admitted: Optional<Reservation> = db.inTenantTransaction(tenant.tenantId) { c ->
                        reservationRepo.admit(c, tenant.tenantId, token, null) }
                    admitted.isPresent
                }))
            }

            var admitted = 0
            for (f in futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    admitted++
                }
            }
            assertEquals(1, admitted,
                "one ticket, one entry — " + admitted + " of " + CONCURRENT_SCANS
                    + " simultaneous scans were admitted")
        } finally {
            executor.shutdownNow()
        }

        val after = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, held.reservationId) }.orElseThrow()
        assertTrue(after.isAdmitted(), "the winner's entry must actually be recorded")
        assertEquals("confirmed", after.status, "and the seat must still be sold")
    }
}
