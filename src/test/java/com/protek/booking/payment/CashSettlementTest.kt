package com.protek.booking.payment

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * "Cash received" records the money once per order, however many times it is pressed and from
 * however many counters — the defect found on 2026-10-02 that [PaymentService.settleCash] now
 * closes.
 *
 * The race test is the one that matters. A single-threaded "settle twice" passes against any
 * read-then-decide implementation, which is exactly the implementation that fails at a busy desk:
 * two presses in flight both read "not paid". So it fires eight at once through the counter's
 * form and counts the payments left behind.
 */
@QuarkusTest
class CashSettlementTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
        private const val SIMULTANEOUS_PRESSES = 8
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
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository

    private lateinit var stub: StubMidtransGateway

    @BeforeEach
    fun installStub() {
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val seats: List<Reservation>) {
        fun groupId(): UUID = group.reservationGroupId!!
    }

    private fun hold(vararg labels: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seatIds = ArrayList<UUID?>()
        var seq = 1
        for (label in labels) {
            seatIds.add(fixture.unit(tenant, pool, label, label.substring(0, 1), seq++).resourceUnitId)
        }
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, seatIds)
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations)
    }

    private fun paid(booked: Booked): List<Payment> =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findByReservationGroup(c, booked.tenant.tenantId, booked.groupId())
        }
            .stream()
            .filter { p -> Payment.STATUS_PAID == p.status }
            .toList()

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theSecondPressOfCashReceivedRecordsNothingAndPointsAtTheFirst() {
        val booked = hold("K1", "K2")

        val first = paymentService.settleCash(booked.groupId())
        val second = paymentService.settleCash(booked.groupId())

        assertEquals(PaymentService.CashOutcome.SETTLED, first.outcome)
        assertEquals(0, BigDecimal("100000").compareTo(first.payment!!.amount))
        assertEquals(PaymentService.CashOutcome.ALREADY_PAID, second.outcome)
        assertEquals(first.payment.paymentId, second.payment!!.paymentId,
            "the refusal carries the payment of record, so the caller can show what was taken")
        assertEquals(1, paid(booked).size, "one order, one cash payment")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aSecondPaidCashRowInsertedWithoutTheLockIsRefusedByTheDatabase() {
        // idx_payment_one_cash_per_order is the belt to the lock's braces: a path that skips settleCash entirely still
        // cannot record the money twice.
        val booked = hold("J1")
        assertEquals(PaymentService.CashOutcome.SETTLED, paymentService.settleCash(booked.groupId()).outcome)
        val tenantId = booked.tenant.tenantId

        val refused = assertThrows(DataAccessException::class.java) {
            db.inTenantTransaction(tenantId) { c -> paymentRepo.insertCashPaid(c, tenantId,
                booked.groupId(), SEAT_PRICE, "IDR", "M-TEST", "CASH-" + UUID.randomUUID(), "tenant_staff") }
        }

        assertTrue(PaymentRepository.isSecondCashPayment(refused), refused.message)
        assertEquals(1, paid(booked).size)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun eightSimultaneousPressesRecordExactlyOnePayment() {
        val booked = hold("L1", "L2", "L3")
        val form = "/admin/counter/reservations/" + booked.groupId() + "/cash"

        val barrier = CyclicBarrier(SIMULTANEOUS_PRESSES)
        val executor = Executors.newFixedThreadPool(SIMULTANEOUS_PRESSES)
        val presses = ArrayList<Future<Int>>()
        try {
            for (i in 0 until SIMULTANEOUS_PRESSES) {
                presses.add(executor.submit(Callable {
                    barrier.await(30, TimeUnit.SECONDS)
                    given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
                        .`when`().post(form).statusCode()
                }))
            }
            for (press in presses) {
                assertEquals(303, press.get(60, TimeUnit.SECONDS),
                    "the browser goes back to the order whatever happened")
            }
        } finally {
            executor.shutdownNow()
        }

        val payments = paid(booked)
        assertEquals(1, payments.size, SIMULTANEOUS_PRESSES.toString() + " simultaneous presses left " +
            payments.size + " paid payments for one order")
        assertEquals(0, BigDecimal("150000").compareTo(payments[0].amount))
        assertEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            groupRepo.findById(c, booked.tenant.tenantId, booked.groupId())
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anOrderAlreadyPaidByQrisIsNotPaidAgainInCash() {
        // The customer scanned the QR on the counter screen, then offered cash as well: the drawer
        // must not gain a second payment the record does not need.
        val booked = hold("M1")
        val charge = paymentService.createQrisCharge(booked.groupId(), true).orElseThrow()
        val settled = stub.settle(charge.qrisPartnerReferenceNo)
        assertTrue(paymentService.applyNotification(MidtransNotification(settled.orderId,
            settled.transactionId, settled.transactionStatus, settled.fraudStatus,
            settled.statusCode, settled.grossAmount, settled.signatureKey, "qris", "{}")))

        val cash = paymentService.settleCash(booked.groupId())

        assertEquals(PaymentService.CashOutcome.ALREADY_PAID, cash.outcome)
        assertEquals(charge.paymentId, cash.payment!!.paymentId)
        assertEquals(Payment.CHANNEL_QRIS, cash.payment.paymentChannel)
        assertEquals(1, paid(booked).size)
    }

    private fun reread(booked: Booked, p: Payment): Payment =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, p.paymentId)
        }.orElseThrow()

    private fun notify(status: MidtransGateway.TransactionStatus): Boolean =
        paymentService.applyNotification(MidtransNotification(status.orderId,
            status.transactionId, status.transactionStatus, status.fraudStatus,
            status.statusCode, status.grossAmount, status.signatureKey, "qris", "{}"))

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cashTakenWhileTheQrIsOnScreenWithdrawsTheQrAndCancelsItAtMidtrans() {
        val booked = hold("N1")
        val qr = paymentService.createQrisCharge(booked.groupId(), true).orElseThrow()

        assertEquals(PaymentService.CashOutcome.SETTLED, paymentService.settleCash(booked.groupId()).outcome)

        assertEquals(Payment.STATUS_FAILED, reread(booked, qr).status,
            "the QR is off the screen: the fragment shows a QR only while its payment is pending")
        assertEquals(java.util.List.of(qr.qrisPartnerReferenceNo), stub.cancelled,
            "only Midtrans can stop the customer's banking app paying it")
        assertNotNull(qr.qrisReferenceNo)
        assertEquals(java.util.List.of(qr.qrisReferenceNo), stub.cancelledReferences,
            "with Midtrans's own reference, without which BI-SNAP refuses the cancel")
        assertEquals(java.util.List.of("the order was paid in cash"), stub.cancelledReasons,
            "telling Midtrans the actual cause, as the log line does")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aQrPaidAfterCashWasTakenIsRecordedAndOwedBackNotLost() {
        // The cancel never reached Midtrans, and the customer scanned the screenshot afterwards.
        stub.failCancels(HttpMidtransGateway.MidtransException("Midtrans unreachable"))
        val booked = hold("N2")
        val qr = paymentService.createQrisCharge(booked.groupId(), true).orElseThrow()
        assertEquals(PaymentService.CashOutcome.SETTLED, paymentService.settleCash(booked.groupId()).outcome,
            "a failed cancel does not undo the cash")

        assertTrue(notify(stub.settle(qr.qrisPartnerReferenceNo)))

        val late = reread(booked, qr)
        assertEquals(Payment.STATUS_PAID, late.status, "real money Midtrans holds is recorded")
        assertEquals(0, qr.amount!!.compareTo(late.refundDueAmount), "and owed back in full")
        assertEquals(2, paid(booked).size)
        assertFalse(notify(stub.settle(qr.qrisPartnerReferenceNo)), "a retried notification changes nothing")
        assertEquals(0, qr.amount.compareTo(reread(booked, qr).refundDueAmount))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aQrPaidAfterItExpiredOnAnOrderNobodyElsePaidIsOwedBackAndConfirmsNoSeat() {
        // This used to be a log line and nothing else.
        val booked = hold("N4")
        val qr = paymentService.createQrisCharge(booked.groupId(), true).orElseThrow()
        assertTrue(notify(stub.expire(qr.qrisPartnerReferenceNo)))

        assertTrue(notify(stub.settle(qr.qrisPartnerReferenceNo)), "the late money is an applied change")

        val late = reread(booked, qr)
        assertEquals(Payment.STATUS_PAID, late.status, "real money Midtrans holds is recorded")
        assertEquals(0, qr.amount!!.compareTo(late.refundDueAmount), "and owed back in full")
        assertEquals(PaymentService.REFUND_PAID_AFTER_EXPIRY, late.refundReason)
        val seats = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findByGroup(c, booked.tenant.tenantId, booked.groupId())
        }
        assertTrue(seats.none { it.status == "confirmed" },
            "the webhook confirms no seat: the hold may be someone else's by now")
        assertFalse(notify(stub.settle(qr.qrisPartnerReferenceNo)), "a retried notification changes nothing")
        assertEquals(0, qr.amount.compareTo(reread(booked, qr).refundDueAmount))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anOrderAlreadyPaidGetsNoNewQr() {
        val booked = hold("N3")
        val cash = paymentService.settleCash(booked.groupId()).payment!!

        val refused = assertThrows(
            PaymentService.OrderAlreadyPaidException::class.java
        ) { paymentService.createQrisCharge(booked.groupId(), true) }

        assertEquals(cash.paymentId, refused.payment().paymentId)
        assertTrue(db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findByReservationGroup(c, booked.tenant.tenantId, booked.groupId())
        }
            .stream().noneMatch { p -> Payment.STATUS_PENDING == p.status })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anOrderWithNoSeatLeftIsNotSettledForNothing() {
        // Before 2026-10-02 this recorded a Rp 0 cash payment and marked an empty order confirmed.
        val booked = hold("N1")
        assertTrue(bookingService.cancelReservation(booked.seats[0].reservationId,
            "tenant_staff", "customer walked away"))

        val cash = paymentService.settleCash(booked.groupId())

        assertEquals(PaymentService.CashOutcome.NOTHING_TO_PAY, cash.outcome)
        assertNull(cash.payment)
        assertTrue(db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findByReservationGroup(c, booked.tenant.tenantId, booked.groupId())
        }.isEmpty())
        assertNotEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            groupRepo.findById(c, booked.tenant.tenantId, booked.groupId())
        }.orElseThrow().status)
    }
}
