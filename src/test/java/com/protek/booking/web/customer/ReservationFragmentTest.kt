package com.protek.booking.web.customer

import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.payment.MidtransGateway
import com.protek.booking.payment.MidtransSignatureVerifier
import com.protek.booking.payment.PaymentService
import com.protek.booking.payment.StubMidtransGateway
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The reservation page's self-updating behaviour: a customer who has scanned the QR should see
 * their seats confirmed without pressing anything.
 *
 * **What actually matters here is when polling STOPS.** That it starts is easy and visible;
 * that it ends is neither. The fragment carries `hx-trigger` only while a charge is
 * outstanding, so htmx runs out of work by itself once the payment is terminal — the same
 * markup-drives-behaviour trick the seat map's revert uses. Get it wrong and every settled booking
 * leaves a tab polling this endpoint for as long as it stays open, which nothing in the UI would
 * ever reveal.
 */
@QuarkusTest
class ReservationFragmentTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var paymentService: PaymentService

    private lateinit var stub: StubMidtransGateway

    @BeforeEach
    fun installStub() {
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        QuarkusMock.installMockForType(com.protek.booking.payment.TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }


    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun whileTheQrIsOutstanding_theFragmentAsksToBePolled() {
        val booked = bookOneSeat("R1")
        paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()

        given().`when`().get("/orders/" + booked.group.reservationGroupId + "/fragment")
            .then().statusCode(200)
            .body(containsString("hx-trigger"))
            .body(containsString("/fragment"))
            // The QR itself has to be in the polled markup, or a swap would blank the very
            // thing the customer is scanning.
            .body(containsString("QRIS payment code"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun onceSettled_theFragmentStopsAskingToBePolled() {
        val booked = bookOneSeat("R2")
        val payment: Payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        paymentService.applyNotification(notificationFor(stub.settle(payment.qrisPartnerReferenceNo)))

        val body = given().`when`()
            .get("/orders/" + booked.group.reservationGroupId + "/fragment")
            .then().statusCode(200)
            .body(containsString("Paid"))
            .extract().asString()

        // The whole stop mechanism. No JavaScript cancels a timer; the attribute simply is not
        // there any more, so htmx schedules nothing further.
        assertTrue(!body.contains("hx-trigger"),
            "a settled reservation must render without hx-trigger, or the tab keeps polling "
                    + "this endpoint until it is closed. Body was:\n" + body)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aQrPaidAfterItExpiredReadsAsARefundOwedAndNeverAsATicket() {
        val booked = bookOneSeat("R5")
        val payment: Payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        paymentService.applyNotification(notificationFor(stub.expire(payment.qrisPartnerReferenceNo)))
        paymentService.applyNotification(notificationFor(stub.settle(payment.qrisPartnerReferenceNo)))

        val body = given().`when`()
            .get("/orders/" + booked.group.reservationGroupId + "/fragment")
            .then().statusCode(200)
            .body(containsString("Refund owed"))
            .body(containsString("is owed back to you"))
            .extract().asString()
        assertTrue(!body.contains("<h2>Paid</h2>"), "the heading names the debt, not a sale. Body was:\n$body")
        assertTrue(!body.contains("ticket-card") && !body.contains("<svg"),
            "no confirmed seat, so no barcode. Body was:\n$body")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun beforeAnyChargeExists_thereIsNothingToPollFor() {
        // The customer has not pressed Pay yet, so there is no settlement to wait for and the
        // page should not be generating traffic on the chance that one appears.
        val booked = bookOneSeat("R3")

        given().`when`().get("/orders/" + booked.group.reservationGroupId + "/fragment")
            .then().statusCode(200)
            .body(not(containsString("hx-trigger")))
            .body(containsString("Cash received"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffAtTheCounterGetTheCountersButtonsInPlaceOfTheCustomers() {
        // Staff get Cash received / Show QRIS. The customer's "pay at
        // counter" would confirm with no payment row, and their QRIS would record trigger_point
        // 'online', so staff must not be left with those.
        val booked = bookOneSeat("R4")
        val group = booked.group.reservationGroupId

        given().`when`().get("/orders/$group/fragment")
            .then().statusCode(200)
            .body(containsString("action=\"/admin/counter/reservations/$group/cash\""))
            .body(containsString("action=\"/admin/counter/reservations/$group/qris\""))
            .body(not(containsString("action=\"/orders/$group/pay\"")))
            .body(not(containsString("action=\"/orders/$group/confirm\"")))
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aCustomerSeesTheirOwnWaysToPayAndNoneOfTheCounters() {
        val me = fixture.customer(currentTenant.get())
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        val seat: ResourceUnit = fixture.unit(tenant, pool, "R5", "R", 1)
        val showing: ResourcePoolSession = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        QuarkusMock.installMockForType(ActingAs.customer(me.customerId), CurrentActor::class.java)
        val result = bookingService.hold(pool.resourcePoolId, showing.resourcePoolSessionId,
            java.util.List.of(seat.resourceUnitId!!), "Test Customer", "+62-800-r5",
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        val group = result.group!!.reservationGroupId

        given().`when`().get("/orders/$group/fragment")
            .then().statusCode(200)
            .body(containsString("action=\"/orders/$group/pay\""))
            .body(containsString("action=\"/orders/$group/confirm\""))
            .body(not(containsString("/admin/counter/reservations/")))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun thePolledFragmentRunsTheSameOwnershipCheckAsThePage() {
        // A polled endpoint is no less worth protecting than a rendered one, and this one carries
        // the amount paid and every seat on the booking.
        given().`when`().get("/orders/" + java.util.UUID.randomUUID() + "/fragment")
            .then().statusCode(org.hamcrest.Matchers.not(200))
    }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservation: Reservation)

    private fun bookOneSeat(seatLabel: String): Booked {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        val seat: ResourceUnit = fixture.unit(tenant, pool, seatLabel, seatLabel.substring(0, 1), 1)
        val showing: ResourcePoolSession = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result: BookingService.HoldResult = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId!!))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations[0])
    }

    private fun notificationFor(
        s: MidtransGateway.TransactionStatus): com.protek.booking.payment.MidtransNotification =
        com.protek.booking.payment.MidtransNotification(s.orderId, s.transactionId,
            s.transactionStatus, s.fraudStatus, s.statusCode, s.grossAmount,
            s.signatureKey, "qris", "{}")
}
