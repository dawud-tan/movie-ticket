package com.protek.booking.web.admin

import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The two rendered halves of the admission schema: the ticket a customer shows, and the door an usher scans it at.
 *
 * Rendering is worth its own test rather than being taken on trust, because here the artifact
 * *is* the markup. A correct token in the database that never reaches the page is a
 * customer standing at a door with nothing to present, and neither the service tests nor the
 * repository tests can see that.
 */
@QuarkusTest
class AdmissionPageTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService

    private data class Booked(val tenant: Tenant, val hall: ResourcePool, val showing: ResourcePoolSession,
                              val group: ReservationGroup, val reservation: Reservation)

    private fun sell(seatLabel: String, confirm: Boolean): Booked {
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)
        val seat: ResourceUnit = fixture.unit(tenant, hall, seatLabel, seatLabel.substring(0, 1), 1)
        val showing: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")
        fixture.rateRule(tenant, hall, SEAT_PRICE)
        val result: BookingService.HoldResult = bookingService.holdForCounterSale(
            hall.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId!!))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        if (confirm) {
            assertTrue(bookingService.confirmGroup(result.group!!.reservationGroupId), "setup confirm failed")
        }
        return Booked(tenant, hall, showing, result.group!!, result.reservations[0])
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aConfirmedBookingRendersABarcodeAndTheCodeBeneathIt() {
        val booked = sell("T1", true)

        val body = given().`when`().get("/reservations/" + booked.group.reservationGroupId)
            .then().statusCode(200)
            .body(containsString("Your ticket"))
            .body(containsString("class=\"barcode\""))
            .extract().asString()

        assertTrue(body.contains(booked.reservation.admissionToken!!),
            "the printed code is the fallback for a flat phone or a cracked screen, so it has "
                    + "to be on the page as text and not only inside the symbol")
        // The white ground travels with the symbol rather than depending on the page's theme —
        // this sheet is dark, and a quiet zone only works if it is actually light.
        assertTrue(body.contains("fill=\"#ffffff\""),
            "the barcode must carry its own quiet zone. Body was:\n" + body)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anUnpaidHoldShowsNoTicketAtAll() {
        // Showing a barcode for an unpaid seat would promise entry the scan endpoint then refuses
        // at the door, in front of the customer — a worse failure than showing nothing.
        val booked = sell("T2", false)

        val body = given().`when`().get("/reservations/" + booked.group.reservationGroupId)
            .then().statusCode(200)
            .body(not(containsString("Your ticket")))
            .extract().asString()

        assertTrue(!body.contains(booked.reservation.admissionToken!!),
            "the token exists from insert, but an unpaid hold must not display it. Body was:\n" + body)
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun theDoorPageOffersAFocusedInputThatAScannerCanTypeInto() {
        val booked = sell("T3", true)

        given().`when`().get("/admin/showings/" + booked.showing.resourcePoolSessionId + "/admission")
            .then().statusCode(200)
            .body(containsString("admissionToken"))
            // autofocus is what makes a barcode scanner work at all without a click: the
            // hardware types into whatever currently has focus.
            .body(containsString("autofocus"))
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun scanningReturnsThePanelWithTheOutcomeAndAFreshInput() {
        val booked = sell("T4", true)

        val body = given().contentType("application/x-www-form-urlencoded")
            .formParam("admissionToken", booked.reservation.admissionToken)
            .`when`().post("/admin/showings/" + booked.showing.resourcePoolSessionId + "/admission")
            .then().statusCode(200)
            .body(containsString("Admitted"))
            .body(containsString("Seat T4"))
            .extract().asString()

        // The queue-moving property: the response carries the whole panel, so the input comes back
        // empty and focused and the next ticket can be scanned without a click or a page load.
        assertTrue(body.contains("autofocus"),
            "the swapped panel must bring its own focused input back. Body was:\n" + body)
        assertTrue(body.contains("id=\"admission-panel\""),
            "hx-swap=outerHTML targets #admission-panel, so the response has to re-declare it")
    }

    @Test
    @TestSecurity(user = "usher@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "usher@demo-cinema.example")])
    fun aRefusedScanSaysWhichRefusalItWas() {
        val booked = sell("T5", false)

        given().contentType("application/x-www-form-urlencoded")
            .formParam("admissionToken", booked.reservation.admissionToken)
            .`when`().post("/admin/showings/" + booked.showing.resourcePoolSessionId + "/admission")
            .then().statusCode(200)
            .body(containsString("Not valid for entry"))
            // The remedy, not just the refusal — an unpaid hold goes to the counter, which is
            // a different instruction from every other way a ticket can fail.
            .body(containsString("counter"))
    }
}
