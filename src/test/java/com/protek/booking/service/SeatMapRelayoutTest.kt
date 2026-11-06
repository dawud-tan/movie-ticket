package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Re-seating a studio against a real database.
 *
 * Three properties carry the feature, and they are the three that would be expensive to get
 * wrong: a seat that survives a relayout keeps its identity and therefore its bookings; a seat
 * that does not survive is retired rather than deleted, so last season's ticket still names it;
 * and a seat somebody is holding or has paid for cannot be retired at all.
 */
@QuarkusTest
class SeatMapRelayoutTest {

    companion object {
        private val PRICE = BigDecimal("50000")

        private fun plan(spec: String): List<SeatMapPlan.Seat> = SeatMapPlan.parse(spec)
    }

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var unitRepo: ResourceUnitRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture

    private fun hall(): ResourcePool = fixture.pool(currentTenant.get())

    /** Every seat including the retired ones — what the admin screens deliberately do not show. */
    private fun allSeats(poolId: UUID?): List<ResourceUnit> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> unitRepo.findAllByPool(c, tenantId, poolId) }
    }

    @Test
    fun aSeatTheNewLayoutDropsLeavesTheHallWithoutLeavingTheDatabase() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-2"))

        assertEquals(listOf("A1", "A2"),
            catalogService.listSeats(hall.resourcePoolId).map { it.label },
            "the hall is what the new layout says")
        // The row survives, deactivated: a delete would either fail on reservation's foreign key
        // or take away the seat a printed ticket names.
        val all = allSeats(hall.resourcePoolId)
        assertEquals(4, all.size)
        assertEquals(listOf("A3", "A4"),
            all.filter { !it.isActive }.map { it.label })
    }

    @Test
    fun aSeatThatKeepsItsNameKeepsItsBookings() {
        val tenant: Tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")
        val a1 = catalogService.listSeats(hall.resourcePoolId)[0]

        val held = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(a1.resourceUnitId),
            "Customer", "+628120000001", AgeClassification.Attestation.NOT_TICKED)
        assertTrue(held.success)

        // A1 moves to the other end of its row and the rest of the hall is unchanged.
        catalogService.applyRelayout(hall.resourcePoolId, listOf(
            SeatMapPlan.Seat("A1", "A", 9),
            SeatMapPlan.Seat("A2", "A", 2),
            SeatMapPlan.Seat("A3", "A", 3),
            SeatMapPlan.Seat("A4", "A", 4)))

        val moved = catalogService.listSeats(hall.resourcePoolId).first { it.label == "A1" }
        assertEquals(a1.resourceUnitId, moved.resourceUnitId, "a moved seat is the same seat")
        assertEquals(9, moved.sequenceNo)
        assertEquals(a1.resourceUnitId, held.reservations[0].resourceUnitId,
            "and the booking still points at it")
    }

    @Test
    fun aSeatSomebodyIsHoldingCannotBeRetiredAndNothingElseChangesEither() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a3 = catalogService.listSeats(hall.resourcePoolId)[2]
        assertTrue(bookingService.hold(hall.resourcePoolId, showing.resourcePoolSessionId,
            listOf(a3.resourceUnitId), "Customer", "+628120000002",
            AgeClassification.Attestation.NOT_TICKED).success)

        val e = assertThrows(IllegalArgumentException::class.java) {
            catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-2"))
        }

        assertTrue(e.message!!.contains("A3"), "the message names the seat: " + e.message)
        // A4 has no booking of its own and would have been retired — the refusal is the whole
        // relayout, not the one seat, because half a layout is a room nobody can describe.
        assertEquals(listOf("A1", "A2", "A3", "A4"),
            catalogService.listSeats(hall.resourcePoolId).map { it.label })
    }

    @Test
    fun aSeatWhoseOnlyBookingIsFinishedIsRetirableAndTheOldTicketStillNamesIt() {
        // The case retirement exists for: the seat is gone from the room, the history is not.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-2")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a2 = catalogService.listSeats(hall.resourcePoolId)[1]
        val booked = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(a2.resourceUnitId), "Last Season",
            "+628120000006", AgeClassification.Attestation.NOT_TICKED)
        assertTrue(booked.success)
        val reservationId = booked.reservations[0].reservationId
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.cancel(c, tenant.tenantId, reservationId, "test: last season") }

        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1"))

        assertEquals(listOf("A1"),
            catalogService.listSeats(hall.resourcePoolId).map { it.label })
        val stillThere: ResourceUnit = db.inTenantTransaction(tenant.tenantId) { c ->
            unitRepo.findById(c, tenant.tenantId, a2.resourceUnitId).orElseThrow() }
        assertEquals("A2", stillThere.label, "the ticket's seat still resolves by id")
        assertFalse(stillThere.isActive)
    }

    @Test
    fun aRetiredSeatIsGoneFromTheCustomerSeatMap() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        val showing = fixture.session(tenant, hall, "on_sale")

        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-2"))

        val map: List<SeatMapEntry> = catalogService.getSeatMap(hall.resourcePoolId,
            showing.resourcePoolSessionId)
        assertEquals(listOf("A1", "A2"), map.map { it.label },
            "every read that decides what is sellable already filtered is_active — which is "
                + "why this needed no migration")
    }

    @Test
    fun aRetiredLabelComesBackAsTheSameSeatRatherThanASecondOneWithThatName() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        val a4 = catalogService.listSeats(hall.resourcePoolId)[3].resourceUnitId
        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-3"))

        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-4"))

        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(4, seats.size)
        assertEquals(a4, seats[3].resourceUnitId, "the seat came back, a copy of it did not")
        assertEquals(4, allSeats(hall.resourcePoolId).size, "no second row was ever inserted")
    }

    @Test
    fun holdingASeatThatWasJustRetiredIsRefused() {
        // The customer whose tab was open across the relayout: their DOM still has the seat id,
        // and the form post is the thing that must not be trusted.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a4 = catalogService.listSeats(hall.resourcePoolId)[3].resourceUnitId
        catalogService.applyRelayout(hall.resourcePoolId, plan("A: 1-3"))

        val result = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(a4), "Stale Tab", "+628120000003",
            AgeClassification.Attestation.NOT_TICKED)

        assertFalse(result.success)
        assertEquals(a4, result.failedResourceUnitId)
        assertTrue(result.failureReason!!.contains("no longer part of this hall"), result.failureReason)
    }

    @Test
    fun aSeatIdFromAnotherHallIsRefusedByTheSameCheck() {
        // Not what relayout was built for, but the same question — "is this a seat of this
        // hall?" — and it was previously unasked: the hold path took whatever ids it was sent.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val elsewhere = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-2")
        catalogService.generateSeats(elsewhere.resourcePoolId, "A: 1-2")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val somebodyElsesSeat = catalogService.listSeats(elsewhere.resourcePoolId)[0].resourceUnitId

        val result = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(somebodyElsesSeat), "Wrong Hall",
            "+628120000004", AgeClassification.Attestation.NOT_TICKED)

        assertFalse(result.success)
        // Stream.count() is a long, which Java widened the expected int to.
        assertEquals(0L, catalogService.getSeatMap(elsewhere.resourcePoolId,
            showing.resourcePoolSessionId).stream()
            .filter { seat -> "available" != seat.seatStatus }.count(),
            "and no seat was taken in the other hall")
    }

    @Test
    fun thePreviewSaysWhatWouldHappenAndWritesNothing() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        val preview: CatalogService.RelayoutPreview =
            catalogService.previewRelayout(hall.resourcePoolId, plan("A: 1-2\nB: 1-2"))

        assertEquals(2, preview.diff.unchanged.size)
        assertEquals(2, preview.diff.creating.size)
        assertEquals(2, preview.diff.retiring.size)
        assertEquals(4, preview.diff.seatCountAfterwards())
        assertFalse(preview.isBlocked())
        assertEquals(4, catalogService.listSeats(hall.resourcePoolId).size, "preview must not write")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun tickingReplaceOnTheAdminFormPreviewsTheRetirementsAndConfirmingAppliesThem() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        given().redirects().follow(false)
            .formParam("mode", "spec").formParam("spec", "A: 1-2").formParam("replace", "on")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/plan")
            .then().statusCode(200)
            .body(containsString("Are retired"))
            .body(containsString("A3"))
            .body(containsString("Apply this layout"))

        assertEquals(4, catalogService.listSeats(hall.resourcePoolId).size, "the preview must not write")

        given().redirects().follow(false)
            .formParam("spec", "A: 1-2").formParam("replace", "on")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/apply")
            .then().statusCode(303)

        assertEquals(listOf("A1", "A2"),
            catalogService.listSeats(hall.resourcePoolId).map { it.label })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBlockedRelayoutSaysSoOnThePageWithNoButtonToApplyIt() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a4 = catalogService.listSeats(hall.resourcePoolId)[3]
        assertTrue(bookingService.hold(hall.resourcePoolId, showing.resourcePoolSessionId,
            listOf(a4.resourceUnitId), "Customer", "+628120000007",
            AgeClassification.Attestation.NOT_TICKED).success)

        given().redirects().follow(false)
            .formParam("mode", "spec").formParam("spec", "A: 1-3").formParam("replace", "on")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/plan")
            .then().statusCode(200)
            // &#39; and not an apostrophe: this copy is a message bundle key now, and Qute
            // HTML-escapes the result of any expression that is not a RawString — which a
            // bundle method never is. The page reads identically in a browser; only the
            // bytes changed. Msg's javadoc has the rule, and this is the second test it has
            // caught (OrderHistoryTest was the first, while customer/ was being converted).
            .body(containsString("booked and can&#39;t be retired"))
            .body(containsString("A4"))
            // No way to proceed: the operator has a booking to deal with first, and a button
            // that only fails on the next screen would be a worse way to say so.
            .body(not(containsString("Apply this layout")))
    }

    @Test
    fun thePreviewNamesTheBookedSeatsThatWouldBlockTheRelayout() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a4 = catalogService.listSeats(hall.resourcePoolId)[3]
        assertTrue(bookingService.hold(hall.resourcePoolId, showing.resourcePoolSessionId,
            listOf(a4.resourceUnitId), "Customer", "+628120000005",
            AgeClassification.Attestation.NOT_TICKED).success)

        val preview = catalogService.previewRelayout(hall.resourcePoolId, plan("A: 1-3"))

        assertTrue(preview.isBlocked())
        assertEquals(listOf("A4"), preview.blocked)
    }
}
