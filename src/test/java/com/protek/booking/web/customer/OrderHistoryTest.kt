package com.protek.booking.web.customer

import com.protek.booking.domain.Customer
import com.protek.booking.domain.ReservationGroup
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
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * `GET /orders` — order history.
 *
 * **What was missing.** A customer's only handle on a booking was the `groupId` in
 * the URL they were redirected to once, so closing the tab lost the booking: there was no
 * collection URL to come back to, and no way to spell one, since `{groupId}` used to sit on
 * the resource class itself.
 *
 * **The test that matters most here is the negative one.** RLS isolates tenants and not
 * customers within a tenant, so "my bookings" is enforced entirely by this application — one
 * missing predicate turns this page into a list of every booking the cinema holds.
 */
@QuarkusTest
class OrderHistoryTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService

    /**
     * Books a seat as `customer`, through the same path a real self-service booking takes.
     *
     * `hold` rather than `holdForCounterSale` deliberately: every counter sale in a
     * tenant attaches to the one walk-in placeholder row, so a history test built on it could not
     * tell "my bookings" apart from "this cinema's bookings" — the exact distinction under test.
     */
    private fun bookOneSeatAs(customer: Customer, seatLabel: String): ReservationGroup {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        val seat: ResourceUnit = fixture.unit(tenant, pool, seatLabel, seatLabel.substring(0, 1), 1)
        val showing: ResourcePoolSession = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)
        val result: BookingService.HoldResult = bookingService.hold(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId!!),
            "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return result.group!!
    }

    private fun actAs(customer: Customer) {
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aReturningCustomerSeesEveryBookingTheyHaveMade() {
        val me = fixture.customer(currentTenant.get())
        val first = bookOneSeatAs(me, "H1")
        val second = bookOneSeatAs(me, "H2")
        actAs(me)

        given().`when`().get("/orders")
            .then().statusCode(200)
            // The code, because that is what the customer is asked for on the phone, and the
            // link, because a list nobody can act from is not a booking history.
            .body(containsString(first.bookingCode))
            .body(containsString(second.bookingCode))
            .body(containsString("/orders/" + first.reservationGroupId))
            // The hall and the showing come from two LEFT JOINs in the history query, and a
            // join that matched nothing would fail silently — the row would still render,
            // through the branch meant for a failed booking with no showing at all.
            .body(containsString("Test Hall"))
            .body(containsString("Test Showing"))
            .body(containsString("1 seat"))
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun anotherCustomersBookingIsNotInMyHistory() {
        // Both customers belong to the SAME tenant, which is the whole point: RLS would isolate
        // two cinemas from each other by itself and prove nothing about this page.
        val me = fixture.customer(currentTenant.get())
        val someoneElse = fixture.customer(currentTenant.get())
        val mine = bookOneSeatAs(me, "J1")
        val theirs = bookOneSeatAs(someoneElse, "J2")
        actAs(me)

        given().`when`().get("/orders")
            .then().statusCode(200)
            .body(containsString(mine.bookingCode))
            .body(not(containsString(theirs.bookingCode)))
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aCustomerWhoHasNotBookedIsSentToWhatIsOnRatherThanShownAnEmptyTable() {
        actAs(fixture.customer(currentTenant.get()))

        // &#39; and not an apostrophe, because this copy is a message bundle key now and Qute
        // HTML-escapes the result of any expression that is not a RawString — which a bundle
        // method never is. The page reads identically in a browser; only the bytes changed.
        // Msg's javadoc has the rule, and it is the one thing that bit while customer/ was
        // being converted.
        given().`when`().get("/orders")
            .then().statusCode(200)
            .body(containsString("No orders yet"))
            .body(containsString("Browse what&#39;s on"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffHaveNoBookingsOfTheirOwnAndAreSentToTheLookupInstead() {
        // Staff reach this URL through the same nav a customer does, and they have no customer
        // row by construction — they sell against the tenant's walk-in placeholder. Telling them
        // "no bookings yet" would read as a fault in the page rather than a fact about the account.
        given().`when`().get("/orders")
            .then().statusCode(200)
            .body(containsString("/admin/bookings"))
            .body(not(containsString("No bookings yet")))
    }

    @Test
    fun thePageIsNotPublic() {
        // Browsing is anonymous, a booking is not. Order history is the strongest case of
        // that rule — it is one URL that names everything a person has bought.
        //
        // 401 with OIDC disabled, 302 to the provider when it is enabled; either is a rejection,
        // and 200 is the only answer that must never appear. Same shape as
        // AdminAuthorizationTest.anonymous_gettingAdminUrls_isRejected.
        val status = given().redirects().follow(false).`when`().get("/orders")
            .then().extract().statusCode()
        assertTrue(status == 401 || status == 302,
            "anonymous GET /reservations returned " + status + "; expected 401 or a redirect to the IdP")
    }
}
