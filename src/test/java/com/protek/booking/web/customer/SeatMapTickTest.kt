package com.protek.booking.web.customer

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * `?seats=` on the seat map: the booking assistant's proposal arrives ticked, and the hold is
 * still the customer's click. The list is caller input,
 * so everything that is not an available seat of this showing is dropped without an error.
 */
@QuarkusTest
class SeatMapTickTest {

    companion object {
        private fun checkbox(seat: ResourceUnit): String =
            "value=\"" + seat.resourceUnitId + "\""

        private fun ticked(html: String, seat: ResourceUnit): Boolean =
            html.contains(checkbox(seat) + " checked>")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService

    @Test
    fun aProposedSeatIsTickedAndATakenOrForeignOneIsNot() {
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)
        val free: ResourceUnit = fixture.unit(tenant, hall, "T1", "T", 1)
        val unasked: ResourceUnit = fixture.unit(tenant, hall, "T2", "T", 2)
        val taken: ResourceUnit = fixture.unit(tenant, hall, "T3", "T", 3)
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        val session: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")
        // Taken through the real hold, as a customer would: the shared tenant's seeded policy applies.
        QuarkusMock.installMockForType(ActingAs.customer(fixture.customer(tenant).customerId), CurrentActor::class.java)
        assertTrue(bookingService.hold(hall.resourcePoolId, session.resourcePoolSessionId,
            java.util.List.of(taken.resourceUnitId!!), "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
            AgeClassification.Attestation.NOT_TICKED).success, "setup hold failed")
        val otherHall: ResourcePool = fixture.pool(tenant)
        val foreign: ResourceUnit = fixture.unit(tenant, otherHall, "T1", "T", 1)

        val url = "/halls/" + hall.resourcePoolId + "/showings/" + session.resourcePoolSessionId
        val html = given().queryParam("seats", listOf(free.resourceUnitId.toString(),
                taken.resourceUnitId.toString(), foreign.resourceUnitId.toString(), "not-a-uuid",
                UUID.randomUUID().toString()).joinToString(","))
            .`when`().get(url).then().statusCode(200).extract().asString()

        assertTrue(ticked(html, free), "the proposed available seat renders ticked")
        assertFalse(ticked(html, unasked), "a seat nobody proposed stays unticked")
        assertFalse(html.contains(checkbox(taken)), "a taken seat is still the bare <span>, never a checkbox")
        assertFalse(html.contains(checkbox(foreign)), "another hall's seat id is ignored")

        val plain = given().`when`().get(url).then().statusCode(200).extract().asString()
        assertFalse(ticked(plain, free), "without ?seats= nothing is ticked")
        val poll = given().queryParam("seats", free.resourceUnitId.toString())
            .`when`().get(url + "/fragment").then().statusCode(200).extract().asString()
        assertFalse(ticked(poll, free), "the 3 s poll ticks nothing: hx-preserve keeps the page's ticks")
    }
}
