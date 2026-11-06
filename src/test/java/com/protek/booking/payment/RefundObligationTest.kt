package com.protek.booking.payment

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The refund obligation: cancelling a booking that was actually paid for must record what is owed back.
 *
 * Before the refund obligation this was a silent hole rather than a visible one. `cancelReservation`
 * updated `reservation` and notified watchers and never looked at `payment`, so a
 * settled booking could be cancelled leaving a `paid` payment attached to a
 * `cancelled` reservation and nothing, anywhere, recording that the customer was owed
 * money. Every test passed, because no test asked.
 *
 * The property under test is deliberately "a debt is recorded", not "a refund happened" —
 * there is still no refund call to Midtrans, and `status = 'refunded'`
 * would claim money moved that has not.
 */
@QuarkusTest
class RefundObligationTest {

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
    lateinit var paymentService: PaymentService
    @Inject
    lateinit var paymentRepo: PaymentRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var catalogService: com.protek.booking.service.CatalogService

    @BeforeEach
    fun installStub() {
        // Same reasoning as PaymentFlowTest: the configured key is the "__unset__" sentinel in
        // tests, so a concrete one is installed to keep the charge path genuinely active.
        QuarkusMock.installMockForType(StubMidtransGateway("test-server-key"),
            MidtransGateway::class.java)
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservations: List<Reservation>)

    private fun book(rowLabel: String, seatCount: Int): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val unitIds = ArrayList<UUID?>()
        for (i in 1..seatCount) {
            unitIds.add(fixture.unit(tenant, pool, rowLabel + i, rowLabel, i).resourceUnitId)
        }
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, unitIds)
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffCancellingAShowing_cancelsItsSeats_owesEveryPaidOne_andTellsTheBuyer() {
        // This used to set the status and nothing else.
        val stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seats = (1..3).map { fixture.unit(tenant, pool, "SC$it", "SC", it).resourceUnitId!! }
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val paidOrder = bookingService.holdForCounterSale(pool.resourcePoolId, showing.resourcePoolSessionId,
            seats.subList(0, 2)).group!!.reservationGroupId!!
        val cash = paymentService.settleCash(paidOrder).payment!!
        val unpaidOrder = bookingService.holdForCounterSale(pool.resourcePoolId, showing.resourcePoolSessionId,
            seats.subList(2, 3)).group!!.reservationGroupId!!
        val qr = paymentService.createQrisCharge(unpaidOrder, true).orElseThrow()

        catalogService.updateShowingStatus(showing.resourcePoolSessionId, "cancelled")

        val statuses = db.inTenantTransaction(tenant.tenantId) { c ->
            (reservationRepo.findByGroup(c, tenant.tenantId, paidOrder) +
                reservationRepo.findByGroup(c, tenant.tenantId, unpaidOrder)).map { it.status }
        }
        assertEquals(listOf("cancelled", "cancelled", "cancelled"), statuses)
        val owed = reload(tenant, cash)
        assertEquals(0, BigDecimal("100000").compareTo(owed.refundDueAmount), "both paid seats owed back")
        assertEquals(BookingService.SHOWING_CANCELLED, owed.refundReason)
        assertEquals(Payment.STATUS_PAID, owed.status)
        assertFalse(reload(tenant, qr).hasOutstandingRefund(), "an unpaid charge owes nothing")
        // The open QR is withdrawn and cancelled at the gateway after commit.
        assertEquals(Payment.STATUS_FAILED, reload(tenant, qr).status, "the open QR is withdrawn")
        assertEquals(listOf(qr.qrisPartnerReferenceNo), stub.cancelled, "and cancelled at Midtrans")
        assertEquals(listOf(qr.qrisReferenceNo), stub.cancelledReferences, "with Midtrans's reference")
        assertEquals(listOf("its showing was cancelled"), stub.cancelledReasons,
            "naming the cancelled showing, not the cash payment this reason used to claim")

        assertFalse(bookingService.holdForCounterSale(pool.resourcePoolId, showing.resourcePoolSessionId,
            seats.subList(0, 1)).success, "a cancelled showing takes no hold")

        // The buyer pays it anyway, before Midtrans processed the cancel: no seat to confirm, so it
        // is all owed, and named as seats gone rather than as an expiry.
        val late = stub.settle(qr.qrisPartnerReferenceNo)
        assertTrue(paymentService.applyNotification(MidtransNotification(late.orderId, late.transactionId,
            late.transactionStatus, late.fraudStatus, late.statusCode, late.grossAmount, late.signatureKey,
            "qris", "{}")))
        val paidLate = reload(tenant, qr)
        assertEquals(0, SEAT_PRICE.compareTo(paidLate.refundDueAmount))
        assertEquals(PaymentService.REFUND_SEATS_GONE, paidLate.refundReason)

        io.restassured.RestAssured.given().`when`().get("/orders/$paidOrder/fragment")
            .then().statusCode(200)
            .body(org.hamcrest.Matchers.containsString("Showing cancelled"))
            .body(org.hamcrest.Matchers.containsString("is owed back to you"))
            .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hx-trigger")))
        io.restassured.RestAssured.given().`when`().get("/orders/$unpaidOrder/fragment")
            .then().statusCode(200)
            .body(org.hamcrest.Matchers.containsString("Showing cancelled"))
            .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pay now with QRIS")))
    }

    private fun reload(tenant: Tenant, payment: Payment): Payment =
        db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }.orElseThrow()

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cancellingAPaidSeat_recordsWhatIsOwedBack_withoutClaimingItWasRefunded() {
        val booked = book("R", 1)
        val payment = paymentService.settleCash(booked.group.reservationGroupId!!).payment!!
        assertEquals(Payment.STATUS_PAID, payment.status)
        assertFalse(payment.hasOutstandingRefund(), "nothing owed before the cancellation")

        assertTrue(bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "customer changed plans"))

        val after = reload(booked.tenant, payment)
        assertTrue(after.hasOutstandingRefund(), "a paid, cancelled booking must owe money back")
        assertEquals(0, SEAT_PRICE.compareTo(after.refundDueAmount))
        assertNotNull(after.refundDueAt, "the debt must be stamped, or it is invisible again")
        assertEquals("customer changed plans", after.refundReason)

        // The money has NOT moved. Claiming otherwise is the failure mode the refund obligation exists to avoid.
        assertEquals(Payment.STATUS_PAID, after.status,
            "status must stay 'paid' — nothing in this codebase refunds anything yet")
        assertTrue(after.isTerminal(),
            "an outstanding refund must not make a settled charge re-settleable")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cancellingOneSeatOfAPaidGroup_owesThatSeat_notTheWholeBooking() {
        val booked = book("S", 3)
        val payment = paymentService.settleCash(booked.group.reservationGroupId!!).payment!!
        assertEquals(0, SEAT_PRICE.multiply(BigDecimal("3")).compareTo(payment.amount))

        bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "seat 1 released")

        assertEquals(0, SEAT_PRICE.compareTo(reload(booked.tenant, payment).refundDueAmount),
            "one cancelled seat owes one seat's price, not the group total")

        // Accumulates: seats are cancelled one at a time, so the debt has to add up rather than
        // overwrite. Getting this wrong would under-refund a customer who cancels twice.
        bookingService.cancelReservation(
            booked.reservations[1].reservationId, "tenant_staff", "seat 2 released")

        val after = reload(booked.tenant, payment)
        assertEquals(0, SEAT_PRICE.multiply(BigDecimal("2")).compareTo(after.refundDueAmount))
        assertEquals("seat 1 released", after.refundReason,
            "the reason records why the debt first arose, not the most recent cancellation")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cancellingAnUnsettledCharge_owesNothing() {
        val booked = book("T", 1)
        val payment = paymentService.createQrisCharge(
            booked.group.reservationGroupId!!, false).orElseThrow()
        assertEquals(Payment.STATUS_PENDING, payment.status)

        bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "abandoned")

        val after = reload(booked.tenant, payment)
        assertFalse(after.hasOutstandingRefund(),
            "a charge that was never settled took no money, so it owes none back")
        assertEquals(0, BigDecimal.ZERO.compareTo(after.refundDueAmount))
        assertNull(after.refundDueAt)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun outstandingRefunds_areFindable_soAHumanCanActOnThem() {
        val booked = book("U", 1)
        val payment = paymentService.settleCash(booked.group.reservationGroupId!!).payment!!
        bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "no-show")

        // Recording the debt is only half the fix; a debt nobody can list is still invisible.
        val due = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findRefundsDue(c, booked.tenant.tenantId, 100)
        }
        assertTrue(due.stream().anyMatch { p -> p.paymentId == payment.paymentId },
            "a cancelled, paid booking must show up in the outstanding-refunds query")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theCancellationItselfStillHappens() {
        val booked = book("V", 1)
        paymentService.settleCash(booked.group.reservationGroupId!!)

        bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "released")

        // The refund bookkeeping must not have swallowed the cancellation — the seat going back
        // on sale is the part customers see, and the seat-freed push sends it to watchers.
        assertEquals("cancelled", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservations[0].reservationId)
        }.orElseThrow().status)
    }
}
