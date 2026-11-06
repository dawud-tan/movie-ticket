package com.protek.booking.payment

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
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Payment end to end: charge on hold, settle by verified webhook, reconcile a missed one,
 * and settle cash at the counter.
 *
 * The properties these tests exist for are the ones that cost real money when wrong:
 * a forged notification confirming seats, a retried notification confirming twice, a settled
 * payment whose seats never got confirmed, and a QR that outlives its own hold.
 */
@QuarkusTest
class PaymentFlowTest {

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
    lateinit var reconciliationService: PaymentReconciliationService
    @Inject
    lateinit var paymentRepo: PaymentRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var tenantRepo: com.protek.booking.repository.TenantRepository

    @ConfigProperty(name = "midtrans.server-key")
    lateinit var serverKey: String

    private lateinit var stub: StubMidtransGateway

    @BeforeEach
    fun installStub() {
        // The configured server key is the "__unset__" sentinel in tests, which the verifier
        // treats as unconfigured — so a concrete key is installed here, making the signature
        // path genuinely active rather than short-circuited.
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }


    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservation: Reservation)

    private fun bookOneSeat(seatLabel: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, seatLabel, seatLabel.substring(0, 1), 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations[0])
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun charge_isCreatedForTheExactTicketTotal_andExpiresBeforeItsHold() {
        val booked = bookOneSeat("P1")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, true)
            .orElseThrow { AssertionError("charge should have been created") }

        assertEquals(0, SEAT_PRICE.compareTo(payment.amount),
            "QRIS is amount-at-transaction-time with no surcharge permitted, so " +
                "the charge must equal the ticket total exactly")
        assertEquals(Payment.STATUS_PENDING, payment.status)
        assertEquals(Payment.CHANNEL_QRIS, payment.paymentChannel)
        assertEquals(Payment.TRIGGER_COUNTER, payment.triggerPoint,
            "a counter-triggered charge must be distinguishable from an online one so a " +
                "tenant can reconcile takings by channel")
        assertNull(payment.qrisTerminalId,
            "the trigger point has its own column, payment.trigger_point, so qris_terminal_id must be " +
                "left for BI-SNAP's real terminalId rather than re-used for the channel")
        assertNotNull(payment.qrisContent, "there must be something to show the customer")
        assertNotNull(payment.qrisReferenceNo, "Midtrans's transaction id must be recorded")

        // The QR must expire strictly before the hold, or a payment landing in the overlap
        // settles a seat the expiry sweep may already have released.
        assertTrue(payment.expiresAt!!.isBefore(booked.reservation.holdExpiresAt),
            "QR expiry " + payment.expiresAt + " must precede hold expiry " +
                booked.reservation.holdExpiresAt)

    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun verifiedSettlement_confirmsEverySeatInTheGroup_attributedToTheEngine() {
        val booked = bookOneSeat("P2")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val orderId = payment.qrisPartnerReferenceNo

        val settled = stub.settle(orderId)
        assertTrue(paymentService.applyNotification(notificationFor(settled)))

        val after = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow()
        assertEquals(Payment.STATUS_PAID, after.status)
        assertTrue(after.webhookSignatureVerified)
        assertEquals("agen_elektronik", after.settledByActor,
            "an automated confirmation with no human review is PP 71/2019's agen_elektronik " +
                " — recording it distinctly is what lets the audit trail answer " +
                "'did a person decide this?'")

        val reservation = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow()
        assertEquals("confirmed", reservation.status,
            "a paid payment with unconfirmed seats is the worst possible split: the customer " +
                "has been charged and holds nothing")
        assertEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            groupRepo.findById(c, booked.tenant.tenantId,
                booked.group.reservationGroupId)
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun forgedNotification_isRejected_andChangesNothing() {
        val booked = bookOneSeat("P3")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val settled = stub.settle(payment.qrisPartnerReferenceNo)

        // Everything a real settlement has, except a signature anyone could produce. This is the
        // entire attack the endpoint exists to stop: the transition it performs is hold ->
        // confirmed, i.e. free seats.
        val forged = MidtransNotification(settled.orderId,
            settled.transactionId, "settlement", "accept", settled.statusCode,
            settled.grossAmount, "0".repeat(128), "qris", "{}")

        assertThrows(PaymentService.InvalidSignatureException::class.java) {
            paymentService.applyNotification(forged)
        }

        val after = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow()
        assertEquals(Payment.STATUS_PENDING, after.status, "a forged notification must not settle")
        assertEquals("held", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status,
            "a forged notification must not confirm seats")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun duplicateSettlement_isIdempotent_becauseMidtransRetries() {
        val booked = bookOneSeat("P4")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val notification = notificationFor(stub.settle(payment.qrisPartnerReferenceNo))

        assertTrue(paymentService.applyNotification(notification), "first delivery applies")
        // Midtrans retries until it gets a 2xx, so the same settlement arrives repeatedly. The
        // second call must report "nothing to do" rather than re-settling — and must still be a
        // success from the caller's point of view, or the retries never stop.
        assertFalse(paymentService.applyNotification(notification), "replay must be a no-op")
        assertFalse(paymentService.applyNotification(notification), "and stay a no-op")

        val payments = paymentService.paymentsFor(booked.group.reservationGroupId)
        assertEquals(1, payments.size, "a replay must not create a second payment row")
        assertEquals(Payment.STATUS_PAID, payments[0].status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun expiredCharge_doesNotReleaseTheSeats() {
        val booked = bookOneSeat("P5")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        assertTrue(paymentService.applyNotification(notificationFor(stub.expire(payment.qrisPartnerReferenceNo))))

        val after = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow()
        assertEquals(Payment.STATUS_EXPIRED, after.status)

        // Deliberate: the hold has its own expiry and its own sweep, and a customer whose
        // first QR expired may still pay within the remaining hold window via a fresh charge.
        // Releasing here would take the seat from someone still entitled to it.
        assertEquals("held", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun fraudChallenge_isNotTreatedAsSettlement() {
        val booked = bookOneSeat("P6")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        // 'capture' with fraud_status 'challenge' means Midtrans wants a manual decision. Reading
        // capture as paid would confirm seats against a payment that may yet be reversed.
        assertFalse(paymentService.applyNotification(
            notificationFor(stub.challenge(payment.qrisPartnerReferenceNo))))

        assertEquals(Payment.STATUS_PENDING, db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }
            .orElseThrow().status)
        assertEquals("held", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_resolvesASettlementWhoseWebhookNeverArrived() {
        val booked = bookOneSeat("P7")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()

        // The customer paid; the notification was lost (cold start, mid-deploy, exhausted
        // retries). Without reconciliation the seats simply expire and the money is gone.
        stub.settle(payment.qrisPartnerReferenceNo)

        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()
        assertTrue(reconciliationService.reconcileOne(tenant, payment))

        val after = db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }.orElseThrow()
        assertEquals(Payment.STATUS_PAID, after.status)
        assertNotNull(after.lastReconciledAt,
            "the attempt is stamped whether or not it resolved, so a repeatedly unresolvable " +
                "payment is visible in the data rather than only in logs")
        assertEquals("confirmed", db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_handlesMidtransHavingNoRecordOfTheOrder() {
        val booked = bookOneSeat("P8")
        // Our charge call never actually landed. The payment row exists precisely so this is
        // discoverable (the row is written before the call), and there is nothing to settle.
        // It fails rather than being forgotten, so the row carries no Midtrans reference: a row
        // with one is a QR that was issued, which reconciliation must not give up on.
        stub.failCharges(HttpMidtransGateway.MidtransException("simulated network failure"))
        assertThrows(HttpMidtransGateway.MidtransException::class.java) {
            paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
        }
        val payment = paymentService.paymentsFor(booked.group.reservationGroupId!!).single()
        assertNull(payment.qrisReferenceNo)

        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()
        // Retargeted 2026-08-20: this used to assert the row stayed pending, which was the whole
        // defect — nothing performed pending -> expired, so the scan re-queried a dead row every
        // five minutes forever. Midtrans cannot later learn about an order it never received, so
        // the answer is known on this first attempt and the row is given up now.
        assertTrue(reconciliationService.reconcileOne(tenant, payment))
        assertEquals(Payment.STATUS_EXPIRED, db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }
            .orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cashSettlement_confirmsImmediately_andStaysOutOfReconciliation() {
        val booked = bookOneSeat("P9")
        val payment = paymentService.settleCash(booked.group.reservationGroupId!!).payment!!

        assertEquals(Payment.STATUS_PAID, payment.status)
        assertEquals(Payment.CHANNEL_CASH, payment.paymentChannel)
        assertEquals("tenant_staff", payment.settledByActor)
        assertEquals(0, SEAT_PRICE.compareTo(payment.amount))

        assertEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)

        // Cash must never appear in the reconciliation scan: there is no Midtrans transaction to
        // ask about, and it would otherwise look like a QRIS charge that never settled.
        val pending = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findPendingForReconciliation(c, booked.tenant.tenantId, 100)
        }
        assertTrue(pending.stream().noneMatch { p -> p.paymentId == payment.paymentId })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun everyPaymentPathRecordsItsTriggerPoint_andNoneOfThemWritesATerminalId() {
        // The regression this pins: until payment.trigger_point existed the channel was stored in qris_terminal_id, so a
        // reconciliation keyed on that column agreed with a Midtrans report keyed on the real
        // terminalId only by coincidence. All three paths are checked together because the bug
        // was that they all wrote the same overloaded column.
        val online = paymentService.createQrisCharge(
            bookOneSeat("PC").group.reservationGroupId!!, false).orElseThrow()
        val counter = paymentService.createQrisCharge(
            bookOneSeat("PD").group.reservationGroupId!!, true).orElseThrow()
        val cash = paymentService.settleCash(bookOneSeat("PE").group.reservationGroupId!!).payment!!

        assertEquals(Payment.TRIGGER_ONLINE, online.triggerPoint)
        assertEquals(Payment.TRIGGER_COUNTER, counter.triggerPoint)
        assertEquals(Payment.TRIGGER_COUNTER, cash.triggerPoint,
            "cash is only ever taken at the counter, so it has no other trigger point to be")

        // A tenant reconciling takings needs channel and trigger point to stay independent:
        // qris/counter is a real combination (staff raise a QR for a walk-in to scan), so the
        // two columns must not collapse into one another.
        assertEquals(Payment.CHANNEL_QRIS, counter.paymentChannel)
        assertEquals(Payment.CHANNEL_CASH, cash.paymentChannel)

        for (p in java.util.List.of(online, counter, cash)) {
            assertNull(p.qrisTerminalId,
                "nothing sends BI-SNAP a terminalId yet, so the column must stay null rather " +
                    "than be re-used — leaving it free for the real field")
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun withMidtransUnconfigured_bookingFallsBackToPayAtCounter_ratherThanFailing() {
        stub.configured(false)
        val booked = bookOneSeat("PA")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
        assertTrue(payment.isEmpty,
            "a missing payment integration must not cost a tenant a sale — the booking stays " +
                "on the pay-at-counter path")
        assertFalse(paymentService.isEnabled())
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun paymentRowIsWrittenBeforeTheChargeCall_soAFailedCallIsStillDiscoverable() {
        val booked = bookOneSeat("PB")
        stub.failCharges(HttpMidtransGateway.MidtransException("simulated network failure"))

        assertThrows(HttpMidtransGateway.MidtransException::class.java) {
            paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
        }

        // The ordering that matters: charge-then-insert can take a customer's money with no local
        // record at all, which is unrecoverable. Insert-then-charge leaves a pending row that
        // reconciliation will find.
        val payments = paymentService.paymentsFor(booked.group.reservationGroupId!!)
        assertEquals(1, payments.size,
            "the payment row must survive a failed charge call so reconciliation can find it")
        assertEquals(Payment.STATUS_PENDING, payments[0].status)
        assertNull(payments[0].qrisContent, "no QR was ever issued")
    }

    private fun notificationFor(s: MidtransGateway.TransactionStatus): MidtransNotification =
        MidtransNotification(s.orderId, s.transactionId, s.transactionStatus,
            s.fraudStatus, s.statusCode, s.grossAmount, s.signatureKey, "qris",
            "{\"order_id\":\"" + s.orderId + "\",\"transaction_status\":\"" +
                s.transactionStatus + "\"}")
}
