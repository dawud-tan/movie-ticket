package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.AuditLogEntry
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.AdmissionService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `audit_log.actor_type` records who *acted*, not who created the row.
 *
 * **The defect these tests pin down.** Until the fix the trigger wrote
 * `NEW.created_by_actor` into every audit row, for UPDATEs as well as INSERTs. On an
 * INSERT that is right — the creator is the actor. On every later transition it was a lie with
 * a specific shape: a staff redemption, a scheduled sweep and a payment webhook were all logged
 * as the customer who booked the seat. The evidentiary value of this log rests on
 * attributability (UU ITE Pasal 6), and a tamper-evident chain of confidently wrong statements
 * is worse than no chain — it is what a disputed booking would be settled from.
 *
 * Each test below is one of the four cases the migration lists, written as the question staff
 * would actually ask of the log: who cancelled this, who let this person in, who completed this.
 */
@QuarkusTest
class AuditAttributionTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var auditRepo: AuditLogRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var admissionService: AdmissionService

    private fun actingAsCustomer() {
        // A customer row is not needed for attribution — actor TYPE is a fact about the login,
        // not about a customer row — so a random id keeps the setup to the point.
        QuarkusMock.installMockForType(ActingAs.customer(UUID.randomUUID()), CurrentActor::class.java)
    }

    private fun actingAsStaff() {
        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)
    }

    /**
     * A held seat created the way a customer creates one: `created_by_actor = 'customer'`
     * on the row, whatever identity happens to be acting when a later transition runs.
     *
     * `policy_document_id` is null rather than resolved from the fixture: the column is
     * nullable, nothing here reads it, and `policy_document` is UNIQUE per
     * `(tenant, doc_type, version)` so a helper that created one could only be called once
     * per tenant.
     */
    private fun heldByCustomer(tenant: Tenant, group: ReservationGroup, unit: ResourceUnit,
                               session: ResourcePoolSession): Reservation =
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                session.resourcePoolSessionId, null, session.startsAt, session.endsAt,
                PRICE, "IDR", Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID()) }

    private fun auditFor(tenant: Tenant, reservation: Reservation): List<AuditLogEntry> =
        db.inTenantTransaction(tenant.tenantId) { c -> auditRepo.findByEntity(
                c, tenant.tenantId, "reservation", reservation.reservationId) }

    private fun lastEntryFor(tenant: Tenant, reservation: Reservation): AuditLogEntry {
        val entries = auditFor(tenant, reservation)
        assertFalse(entries.isEmpty(), "the trigger writes a row for every write; none found")
        return entries[entries.size - 1]
    }

    @Test
    fun aSelfServiceBookingIsStillAuditedAsTheCustomerWhoMadeIt() {
        // The case that was always right, kept because the fix must not have turned everything into
        // 'system' on the way to fixing the others.
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        actingAsCustomer()
        val held = heldByCustomer(tenant, group, unit, session)

        val insert = lastEntryFor(tenant, held)
        assertEquals("INSERT", insert.action)
        assertEquals("customer", insert.actorType)
    }

    @Test
    fun aStaffCancellationOfACustomersBookingIsAuditedAsStaff() {
        // THE DEFECT, in its most everyday form: a phone cancellation. Before the fix this row said
        // 'customer', because the trigger read the row's creator — so the log claimed the
        // customer had cancelled a booking they had rung up to ask someone else to cancel.
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "B2", "B", 2)
        val session = fixture.session(tenant, pool, "on_sale")
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        actingAsCustomer()
        val held = heldByCustomer(tenant, group, unit, session)

        actingAsStaff()
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.cancel(
                c, tenant.tenantId, held.reservationId, "Phoned in") }

        val cancellation = lastEntryFor(tenant, held)
        assertEquals("UPDATE", cancellation.action)
        assertEquals("tenant_staff", cancellation.actorType,
                "actor_type must name whoever performed this transition, not whoever created the row")

        // Both facts survive, and they are different questions: the row still records that this
        // was a self-service booking, and the log now records that staff ended it.
        val after = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, held.reservationId) }.orElseThrow()
        assertEquals("customer", after.createdByActor,
                "created_by_actor is a different fact and must not have moved")
    }

    @Test
    fun theCompletionSweepIsAuditedAsTheSystemAndNotAsTheCustomer() {
        // confirmed -> completed, the transition order history was actually blocked on. Nobody is signed in
        // when the scheduled sweep runs, and 'system' is the honest record of that; attributing a
        // machine's decision to a customer is the entry that would mislead a later reader most,
        // because completion is the terminus a retention justification hangs on.
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "C3", "C", 3)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        // A showing that has already finished, built with explicit past instants: the EXCLUDE
        // constraint is indifferent to a range in the past, and the sweep only looks at the end.
        val end = Instant.now().minus(Duration.ofHours(2))
        val start = end.minus(Duration.ofHours(2))
        val past = db.inTenantTransaction(tenant.tenantId) { c -> sessionRepo.insert(
                c, tenant.tenantId, pool.resourcePoolId, "Finished showing",
                start, end, start.minusSeconds(900), "on_sale") }

        actingAsCustomer()
        val held = heldByCustomer(tenant, group, unit, past)
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, held.reservationId) }

        // Nobody is acting now: this is the sweep, running on a timer with no human behind it.
        QuarkusMock.installMockForType(ActingAs.nobody(), CurrentActor::class.java)
        val completed = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.completePastShowings(c, tenant.tenantId) }
        assertEquals(1, completed)

        val completion = lastEntryFor(tenant, held)
        assertEquals("UPDATE", completion.action)
        assertEquals("system", completion.actorType,
                "a sweep with no human behind it must not be recorded as the customer who booked")
    }

    @Test
    fun anUsherAdmittingASelfServiceBookingIsAuditedAsStaff() {
        // The admission schema sidestepped this one with admitted_by_app_user_id, which answers "who let this
        // person in" on the row itself. The audit row was still wrong, and it is the row a
        // dispute about a second scan would be read from.
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "D4", "D", 4)
        val showing = fixture.session(tenant, pool, "on_sale")
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        actingAsCustomer()
        val held = heldByCustomer(tenant, group, unit, showing)
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, held.reservationId) }

        actingAsStaff()
        val scanned = admissionService.scan(
                showing.resourcePoolSessionId!!, held.admissionToken)
        assertTrue(scanned.isAdmitted(), "setup scan was refused: " + scanned.message)

        val admission = lastEntryFor(tenant, held)
        assertEquals("tenant_staff", admission.actorType)
    }

    @Test
    fun admissionStillDoesNotCompleteTheReservation() {
        // The question was whether a scan should also move confirmed -> completed. It must not, and the
        // reason is the one the admission schema records for 'admitted': 'completed' is outside the EXCLUDE
        // constraint's predicate, so writing it at the door would free the seat at the moment its
        // holder sat down in it. The sweep can write it only because it waits for the period to
        // end, after which nothing can overlap the range anyway.
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "E5", "E", 5)
        val showing = fixture.session(tenant, pool, "on_sale")
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        actingAsCustomer()
        val held = heldByCustomer(tenant, group, unit, showing)
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, held.reservationId) }

        actingAsStaff()
        assertTrue(admissionService.scan(showing.resourcePoolSessionId!!, held.admissionToken)
                .isAdmitted())

        val after = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, held.reservationId) }.orElseThrow()
        assertEquals("confirmed", after.status,
                "admission is a second dimension (admitted_at), never a status transition")
        assertTrue(after.isAdmitted(), "the scan must still have recorded the entry")
    }

    companion object {
        private val PRICE = BigDecimal("50000.00")
    }
}
