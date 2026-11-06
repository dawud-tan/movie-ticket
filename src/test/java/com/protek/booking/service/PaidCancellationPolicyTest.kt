package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.payment.MidtransGateway
import com.protek.booking.payment.MidtransSignatureVerifier
import com.protek.booking.payment.PaymentService
import com.protek.booking.payment.StubMidtransGateway
import com.protek.booking.payment.TestSignatureVerifier
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The paid-cancellation policy, a business decision of 2026-08-17: a booking that has actually
 * been paid for may be cancelled by staff, not by the customer.
 *
 * This is the cheaper of the two honest resolutions and matches Indonesian cinema
 * norms. It is **provisional pending confirmation with a cinema's own staff**, so what these
 * tests pin down is deliberately narrow: who may cancel a *settled* booking. They say
 * nothing about refund mechanics, which the refund obligation already covers, and nothing about unpaid holds,
 * which stay a customer action.
 */
@QuarkusTest
class PaidCancellationPolicyTest {

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
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository

    @BeforeEach
    fun installStub() {
        QuarkusMock.installMockForType(StubMidtransGateway("test-server-key"), MidtransGateway::class.java)
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservations: List<Reservation>)

    private fun book(rowLabel: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, rowLabel + "1", rowLabel, 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations)
    }

    /** The customer the booking actually belongs to, so the ownership check passes and the
     * paid-cancellation rule is what the test is measuring. */
    private fun owningCustomer(booked: Booked): UUID? =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            groupRepo.findById(c, booked.tenant.tenantId, booked.group.reservationGroupId) }
            .orElseThrow().customerId

    private fun statusOf(booked: Booked, reservationId: UUID?): String? =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId, reservationId) }
            .orElseThrow().status

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theOwningCustomerCannotCancelABookingTheyHavePaidFor() {
        val booked = book("P")
        paymentService.settleCash(booked.group.reservationGroupId!!)
        val reservationId = booked.reservations[0].reservationId

        QuarkusMock.installMockForType(ActingAs.customer(owningCustomer(booked)), CurrentActor::class.java)

        val e = assertThrows(ForbiddenException::class.java) {
            bookingService.cancelReservation(reservationId, "customer", "changed my mind") }

        // The refusal must read as a policy, not as "this isn't yours" — the customer does own
        // it, and telling them otherwise would send them to the wrong remedy.
        assertTrue(e.message!!.contains("cinema staff"), e.message)
        assertEquals("confirmed", statusOf(booked, reservationId),
            "the seat must still be sold — a refused cancellation must change nothing")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffCanCancelThatSameBooking() {
        val booked = book("Q")
        paymentService.settleCash(booked.group.reservationGroupId!!)
        val reservationId = booked.reservations[0].reservationId

        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)

        assertTrue(bookingService.cancelReservation(reservationId, "tenant_staff", "customer phoned"))
        assertEquals("cancelled", statusOf(booked, reservationId))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theOwningCustomerCanStillCancelAnUnpaidHold() {
        // The rule is narrow on purpose. Releasing a hold nobody has paid for is the common
        // case and the one that matters for seat turnover.
        val booked = book("N")
        val reservationId = booked.reservations[0].reservationId

        QuarkusMock.installMockForType(ActingAs.customer(owningCustomer(booked)), CurrentActor::class.java)

        assertTrue(bookingService.cancelReservation(reservationId, "customer", "changed my mind"))
        assertEquals("cancelled", statusOf(booked, reservationId))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aPendingChargeIsNotASettlementSoTheCustomerMayStillCancel() {
        // A charge that was created but never paid took no money. It expires by itself, and
        // treating it as settled would strand the customer with a seat they cannot release.
        val booked = book("M")
        val pending: Payment = paymentService.createQrisCharge(
            booked.group.reservationGroupId!!, false).orElseThrow()
        assertEquals(Payment.STATUS_PENDING, pending.status)
        val reservationId = booked.reservations[0].reservationId

        QuarkusMock.installMockForType(ActingAs.customer(owningCustomer(booked)), CurrentActor::class.java)

        assertTrue(bookingService.cancelReservation(reservationId, "customer", "abandoned"))
        assertEquals("cancelled", statusOf(booked, reservationId))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aStaffCancellationOfAPaidBookingStillRaisesTheRefundObligation() {
        // The rule decides who may start the path; it must not have quietly bypassed the
        // bookkeeping on the way.
        val booked = book("L")
        val paid: Payment = paymentService.settleCash(booked.group.reservationGroupId!!).payment!!

        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)
        bookingService.cancelReservation(
            booked.reservations[0].reservationId, "tenant_staff", "no-show")

        val after = paymentService.paymentsFor(booked.group.reservationGroupId).stream()
            .filter { p -> p.paymentId!! == paid.paymentId }.findFirst().orElseThrow()
        assertTrue(after.hasOutstandingRefund(), "a staff cancellation of a paid seat still owes money back")
        assertEquals(0, SEAT_PRICE.compareTo(after.refundDueAmount!!))
    }
}
