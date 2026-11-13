package com.protek.booking.service

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The per-showtime purchase cap: the anti-scalping ceiling on one self-service hold.
 *
 * Two properties carry the whole feature. The cap must actually refuse an oversized
 * selection *without touching a seat* — a refusal that left a `failed` group or a
 * partial hold behind would take inventory off sale to enforce a policy about buying it. And
 * the counter must stay exempt, which is the half most likely to be "tidied" into uniformity by
 * a later reader: every counter sale for a tenant attaches to the one walk-in placeholder
 * customer, so capping that channel — or aggregating the cap per customer — refuses the ninth
 * walk-in ticket of the evening on the grounds that the previous eight were also walk-ins.
 */
@QuarkusTest
class PurchaseCapTest {

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var purchaseCap: PurchaseCap
    @Inject
    lateinit var fixture: TestFixture

    /** A hall with more seats than the cap, so the cap is what refuses rather than the inventory. */
    private data class Hall(val pool: ResourcePool, val showing: ResourcePoolSession, val seats: List<ResourceUnit>) {
        fun seatIds(count: Int): List<UUID?> {
            val ids = ArrayList<UUID?>()
            for (i in 0 until count) {
                ids.add(seats[i].resourceUnitId)
            }
            return ids
        }
    }

    private fun hallOf(seatCount: Int): Hall {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        // Unique row letter per hall: labels are unique within a pool, and every test method here
        // provisions its own pool, so a fixed "A" would be fine — but the seat map asserts on
        // labels, and a distinct row keeps a failure message pointing at one test.
        val row = "P"
        val seats = ArrayList<ResourceUnit>()
        for (i in 1..seatCount) {
            seats.add(fixture.unit(tenant, pool, row + i, row, i))
        }
        val showing: ResourcePoolSession = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        return Hall(pool, showing, seats)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun askingForOneSeatMoreThanTheCapIsRefused_andSaysHowManyToUntick() {
        val cap = purchaseCap.maxSeatsPerHold()
        val hall = hallOf(cap + 1)

        val result = bookingService.hold(hall.pool.resourcePoolId,
            hall.showing.resourcePoolSessionId, hall.seatIds(cap + 1),
            "Scalper", "+62811000222",
            AgeClassification.Attestation.NOT_TICKED)

        assertFalse(result.success, "a selection one over the cap must be refused")
        assertTrue(result.reservations.isEmpty())
        // The customer is standing in front of a grid they have already ticked. "Too many seats"
        // makes them count; naming the limit and the overshoot makes them act.
        assertTrue(result.failureReason!!.contains("up to " + cap + " seats"),
            "the refusal must state the limit, not just that one was hit: " + result.failureReason)
        assertTrue(result.failureReason.contains("untick 1"),
            "the refusal must state how far over they are: " + result.failureReason)
        assertNull(result.failedResourceUnitId,
            "no specific seat blocked this — a seat id here would send the customer off to "
                + "pick a different seat, which fixes nothing")
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aRefusedOversizedHoldTakesNoSeatOffSale() {
        // The failure that would make this feature worse than not having it: refusing the request
        // after grabbing some of it. The check runs above the transaction precisely so there is
        // nothing to unwind.
        val cap = purchaseCap.maxSeatsPerHold()
        val hall = hallOf(cap + 3)

        val refused = bookingService.hold(hall.pool.resourcePoolId,
            hall.showing.resourcePoolSessionId, hall.seatIds(cap + 3),
            "Scalper", "+62811000333",
            AgeClassification.Attestation.NOT_TICKED)
        assertFalse(refused.success)

        val map: List<SeatMapEntry> = catalogService.getSeatMap(hall.pool.resourcePoolId,
            hall.showing.resourcePoolSessionId)
        assertEquals(cap + 3, map.size)
        assertTrue(map.stream().allMatch { s -> "available" == s.seatStatus },
            "every seat must still be available after a capped refusal: "
                + map.stream().filter { s -> "available" != s.seatStatus }.toList())
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun exactlyTheCapSucceeds() {
        // The boundary in the direction that costs a tenant money if it is wrong by one.
        val cap = purchaseCap.maxSeatsPerHold()
        val hall = hallOf(cap)

        val result = bookingService.hold(hall.pool.resourcePoolId,
            hall.showing.resourcePoolSessionId, hall.seatIds(cap),
            "Big Family", "+62811000444",
            AgeClassification.Attestation.NOT_TICKED)

        assertTrue(result.success, "the cap is inclusive: " + result.failureReason)
        assertEquals(cap, result.reservations.size)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun counterSalesAreNotCapped_becauseEveryWalkInSharesOneCustomerRow() {
        // The dominant channel. A party larger than the cap at the desk is an ordinary sale,
        // and there is no per-person accounting at the counter that could tell it from scalping:
        // the customer row is the tenant's single walk-in placeholder either way.
        val over = purchaseCap.maxSeatsPerHold() + 4
        val hall = hallOf(over)

        val result = bookingService.holdForCounterSale(
            hall.pool.resourcePoolId, hall.showing.resourcePoolSessionId,
            hall.seatIds(over))

        assertTrue(result.success,
            "the counter must be able to sell a party larger than the self-service cap: "
                + result.failureReason)
        assertEquals(over, result.reservations.size)
    }

    @Test
    fun theSeatMapStatesTheLimitBeforeTheCustomerHasTickedAnything() {
        // The hint and the enforcement read the same bean; this is what proves the template
        // expression resolves and the number reaching the page is that one.
        val hall = hallOf(2)

        val body = given().`when`()
            .get("/halls/" + hall.pool.resourcePoolId + "/showings/"
                + hall.showing.resourcePoolSessionId)
            .then().statusCode(200).extract().body().asString()

        assertTrue(body.contains("Up to " + purchaseCap.maxSeatsPerHold() + " seats per booking."),
            "the seat map must state the cap up front rather than only on refusal")
    }
}
