package com.protek.booking.admission

import com.protek.booking.db.ActorContext
import com.protek.booking.db.Db
import com.protek.booking.db.RlsBoundRole
import com.protek.booking.domain.AppUser
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.security.Roles
import com.protek.booking.service.AdmissionService
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The admission artifact end to end: a barcode token, the state that says it has been used,
 * and the door that flips it.
 *
 * **Run under a role RLS actually binds**, like `PaymentWebhookDifferentialTest` and
 * for the same reason it exists at all. An admission is a write across an RLS table, and one that
 * silently matched no row would be indistinguishable from a ticket that had already been used —
 * the door would refuse every genuine customer while every assertion about "already admitted"
 * still passed. Dev Services' role is SUPERUSER and never evaluates a policy, so a suite run only
 * against it can be green while the door is broken for everyone.
 *
 * Fixtures are built *before* the switch, deliberately: what is under test is the scan,
 * not the ability to seed a cinema.
 */
@QuarkusTest
class AdmissionScanTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var admissionService: AdmissionService
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var actorContext: ActorContext

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String
    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var adminUser: String
    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var adminPassword: String

    private var rlsRole: RlsBoundRole? = null

    @AfterEach
    fun dropRole() {
        if (rlsRole != null) {
            rlsRole!!.close()
            rlsRole = null
        }
    }

    private fun switchToRoleSubjectToRls() {
        rlsRole = RlsBoundRole.create("rls_admission", jdbcUrl, adminUser, adminPassword, actorContext)
        QuarkusMock.installMockForType(rlsRole!!.db(), Db::class.java)
    }

    private data class Booked(val tenant: Tenant, val hall: ResourcePool, val showing: ResourcePoolSession,
                              val group: ReservationGroup, val reservations: List<Reservation>) {

        fun first(): Reservation {
            return reservations[0]
        }
    }

    /** A sold booking: seats held at the counter, then confirmed. Confirmed is the only status
     * that admits anyone, so it is the starting point for everything except the refusal cases. */
    private fun sell(rowLabel: String, seatCount: Int, confirm: Boolean): Booked {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val showing = fixture.session(tenant, hall, "on_sale")
        fixture.rateRule(tenant, hall, SEAT_PRICE)
        val seatIds: MutableList<UUID?> = java.util.ArrayList()
        for (i in 1..seatCount) {
            seatIds.add(fixture.unit(tenant, hall, rowLabel + i, rowLabel, i).resourceUnitId)
        }
        val held = bookingService.holdForCounterSale(
            hall.resourcePoolId, showing.resourcePoolSessionId, seatIds)
        assertTrue(held.success, "setup hold failed: " + held.failureReason)
        if (confirm) {
            assertTrue(bookingService.confirmGroup(held.group!!.reservationGroupId), "setup confirm failed")
        }
        val reservations = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findByGroup(c, tenant.tenantId, held.group!!.reservationGroupId)
        }
        return Booked(tenant, hall, showing, held.group!!, reservations)
    }

    private fun reload(booked: Booked, reservationId: UUID?): Reservation {
        return db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId, reservationId)
        }.orElseThrow()
    }

    // --- the happy path, and the thing it must not break -----------------------------------

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aConfirmedTicketIsAdmittedAndTheSeatStaysSold() {
        val booked = sell("A", 1, true)
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            booked.showing.resourcePoolSessionId!!, booked.first().admissionToken)

        assertEquals(AdmissionService.Outcome.ADMITTED, result.outcome, result.message)
        assertEquals("A1", result.seat!!.label, "the usher is told which seat to point at")

        val after = reload(booked, booked.first().reservationId)
        assertNotNull(after.admittedAt, "admitted_at is the whole record of the entry")
        // The point of the orthogonal column, asserted rather than assumed: admission must not
        // move status. See V1's reservation.admitted_at — an 'admitted' status falls outside the EXCLUDE constraint's
        // predicate, and the seat would become bookable while its holder is sitting in it.
        assertEquals("confirmed", after.status,
            "admission must not change status — see V1's reservation.admitted_at on why that would release the seat")
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun anAdmittedSeatIsStillOccupiedAndStillCannotBeDoubleBooked() {
        // The regression guard for the design decision the admission schema turns on. If admission ever becomes a
        // status value, this test is what fails — and it fails on the two things a customer would
        // actually notice: the seat map offering their seat, and someone else being sold it.
        val booked = sell("B", 1, true)
        val seatId = booked.first().resourceUnitId
        switchToRoleSubjectToRls()

        admissionService.scan(booked.showing.resourcePoolSessionId!!, booked.first().admissionToken)

        val seatMap: List<SeatMapEntry> = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findSeatMap(c, booked.tenant.tenantId,
                booked.hall.resourcePoolId, booked.showing.resourcePoolSessionId)
        }
        val entry = seatMap.stream()
            .filter { e -> e.resourceUnitId!! == seatId }.findFirst().orElseThrow()
        assertEquals("confirmed", entry.seatStatus,
            "an admitted seat must still read as sold on the live seat map")

        val second = bookingService.holdForCounterSale(
            booked.hall.resourcePoolId, booked.showing.resourcePoolSessionId, java.util.List.of(seatId))
        assertFalse(second.success,
            "the EXCLUDE constraint must still refuse this seat after its holder has walked in")
        assertEquals(seatId, second.failedResourceUnitId)
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun eachSeatInAPartyGetsItsOwnTicket() {
        // Per reservation, not per group: a party of three does not necessarily arrive together,
        // and one token for the group would admit all three on the first person's phone with
        // nothing left to check for the other two.
        val booked = sell("C", 3, true)
        switchToRoleSubjectToRls()

        // 3L: count() is a long, which Java's assertEquals(long, long) widened 3 to.
        assertEquals(3L, booked.reservations.stream()
            .map(Reservation::admissionToken).distinct().count(), "three seats, three tokens")

        val second = booked.reservations[1]
        admissionService.scan(booked.showing.resourcePoolSessionId!!, second.admissionToken)

        assertNotNull(reload(booked, second.reservationId).admittedAt)
        assertNull(reload(booked, booked.first().reservationId).admittedAt,
            "admitting one seat must not admit the rest of the party")
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun theTokenIsNeitherTheGroupIdNorTheReservationId() {
        // The first trap. An admission credential that is also a URL path segment
        // couples two lifetimes that must stay separate, and PaymentService.kt:119 already
        // records the same reasoning for Midtrans order ids.
        val booked = sell("D", 1, true)
        switchToRoleSubjectToRls()

        val token = booked.first().admissionToken!!
        assertFalse(token.contains(booked.group.reservationGroupId!!.toString()))
        assertFalse(token.contains(booked.first().reservationId!!.toString()))
        assertTrue(token.startsWith("BPT-"), "a scan log must be unambiguous about what it read: " + token)
    }

    // --- the refusals ----------------------------------------------------------------------

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aSecondScanOfTheSameTicketIsRefusedAndKeepsTheOriginalEntryTime() {
        val booked = sell("E", 1, true)
        switchToRoleSubjectToRls()
        val token = booked.first().admissionToken

        admissionService.scan(booked.showing.resourcePoolSessionId!!, token)
        val firstEntry: java.time.Instant? = reload(booked, booked.first().reservationId).admittedAt

        val again =
            admissionService.scan(booked.showing.resourcePoolSessionId, token)

        assertEquals(AdmissionService.Outcome.ALREADY_ADMITTED, again.outcome, again.message)
        assertEquals(firstEntry, reload(booked, booked.first().reservationId).admittedAt,
            "the second scan must not overwrite when they actually came in — that timestamp is " +
                "what settles a dispute at the door")
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aTicketForAnotherShowingIsRefusedAndNamesTheShowingItIsFor() {
        // Not a security failure — it is a real ticket — but admitting it is a full house and an
        // argument. The usher needs the other showing's name to redirect the customer.
        val tonight = sell("F", 1, true)
        val tenant = tonight.tenant
        val otherShowing = fixture.session(tenant, tonight.hall, "on_sale")
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            otherShowing.resourcePoolSessionId!!, tonight.first().admissionToken)

        assertEquals(AdmissionService.Outcome.WRONG_SHOWING, result.outcome, result.message)
        assertEquals(tonight.showing.resourcePoolSessionId, result.showing!!.resourcePoolSessionId)
        assertNull(reload(tonight, tonight.first().reservationId).admittedAt,
            "a refused scan must leave the real ticket unused, so it still works at its own door")
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun anUnpaidHoldIsRefusedAndSentToTheCounter() {
        // The token exists from insert — the admission schema mints it unconditionally so no confirm path can
        // forget to. Holding one is not the same as being admissible, and this is the test that
        // says so.
        val booked = sell("G", 1, false)
        assertEquals("held", booked.first().status)
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            booked.showing.resourcePoolSessionId!!, booked.first().admissionToken)

        assertEquals(AdmissionService.Outcome.NOT_ADMISSIBLE, result.outcome, result.message)
        assertTrue(result.message.contains("counter"),
            "an unpaid hold goes to the counter to pay, which is a different remedy from every " +
                "other refusal: " + result.message)
        assertNull(reload(booked, booked.first().reservationId).admittedAt)
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aCancelledBookingIsRefused() {
        val booked = sell("H", 1, true)
        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)
        bookingService.cancelReservation(booked.first().reservationId, "tenant_staff", "no-show")
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            booked.showing.resourcePoolSessionId!!, booked.first().admissionToken)

        assertEquals(AdmissionService.Outcome.NOT_ADMISSIBLE, result.outcome, result.message)
        assertTrue(result.message.contains("cancelled"), result.message)
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun anUnknownCodeIsRefusedWithoutClaimingItBelongsToSomeoneElse() {
        val booked = sell("I", 1, true)
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            booked.showing.resourcePoolSessionId!!, "BPT-000000000000000000000000")

        assertEquals(AdmissionService.Outcome.UNKNOWN_TOKEN, result.outcome, result.message)
        assertNull(result.reservation)

        // Empty input is the everyday version of this: a scanner that fired on nothing, or an
        // usher who pressed Enter on an empty field. It must not throw a page at them.
        assertEquals(AdmissionService.Outcome.UNKNOWN_TOKEN,
            admissionService.scan(booked.showing.resourcePoolSessionId, "   ").outcome)
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aCodeTypedInLowercaseStillFindsItsTicket() {
        // Manual entry is the fallback that works when the customer's phone is flat, so it has to
        // work when a human types it — and a human will not hold shift for 24 hex characters.
        val booked = sell("J", 1, true)
        switchToRoleSubjectToRls()

        val result = admissionService.scan(
            booked.showing.resourcePoolSessionId!!,
            "  " + booked.first().admissionToken!!.lowercase(java.util.Locale.ROOT) + "  ")

        assertEquals(AdmissionService.Outcome.ADMITTED, result.outcome, result.message)
    }

    // --- who scanned it --------------------------------------------------------------------

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun theScanningStaffMemberIsRecordedOnTheRow() {
        // On the row rather than read back out of audit_log, because that trigger attributes
        // actor_type to the reservation's *creator* — so a self-service booking admitted by an
        // usher audits as though the customer did it (a known defect of that trigger). The
        // column is the answer that is not affected by it.
        val booked = sell("K", 1, true)
        val tenant = booked.tenant
        val usher: AppUser = db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.insert(
                c, "usher-" + UUID.randomUUID() + "@demo-cinema.example",
                Roles.TENANT_STAFF, tenant.tenantId, null)
        }
        QuarkusMock.installMockForType(ActingAs.staff(usher.appUserId), CurrentActor::class.java)
        switchToRoleSubjectToRls()

        admissionService.scan(booked.showing.resourcePoolSessionId!!, booked.first().admissionToken)

        assertEquals(usher.appUserId, reload(booked, booked.first().reservationId).admittedByAppUserId)
    }
}
