package com.protek.booking.payment

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.SQLException

/**
 * `fraud_status: challenge` — a capture Midtrans has taken the money for and is holding for
 * a human decision.
 *
 * **Why this needed its own class rather than the one assertion [PaymentFlowTest]
 * already had.** That test proved the branch does not settle seats, which was the whole story
 * until the reconciliation give-up shipped. After it, the branch's `return false` became
 * indistinguishable from "still awaiting payment": `reconcileOne` would take the row
 * `pending → expired` once the QR validity had lapsed by the grace margin — roughly an hour
 * after a charge — and the review's eventual `accept` would then arrive at an
 * `isTerminal()` row and be discarded as a duplicate. Customer charged, seats never
 * confirmed, no refund obligation recorded, and one warning in a log nobody reads.
 *
 * So the properties here are the ones that cost real money: a captured payment is not written
 * off, either outcome of the review still lands afterwards, and the row is visible to staff while
 * it waits.
 */
@QuarkusTest
class FraudReviewTest {

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
    lateinit var tenantRepo: TenantRepository

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String
    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var adminUser: String
    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var adminPassword: String

    private lateinit var stub: StubMidtransGateway

    @BeforeEach
    fun installStub() {
        // The configured server key is the "__unset__" sentinel in tests, which the real verifier
        // treats as unconfigured and rejects everything with — so a concrete key is installed and
        // the signature path is genuinely exercised rather than short-circuited.
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aCaptureHeldForReview_isStampedOnTheRow_notOnlyInTheLog() {
        val booked = bookOneSeat("F1")
        val payment = charge(booked)

        assertFalse(paymentService.applyNotification(notificationFor(
            stub.challenge(payment.qrisPartnerReferenceNo))))

        val after = reload(booked.tenant, payment)
        assertEquals(Payment.STATUS_PENDING, after.status,
            "a challenged capture must not be read as a settlement")
        assertNotNull(after.fraudReviewAt,
            "the hold has to be a fact in the data — a log line reaches neither the staff who " +
                "must act on it nor the reconciliation scan that must not write it off")
        assertTrue(after.isUnderFraudReview())
        assertEquals("held", reloadSeat(booked).status,
            "seats stay held: confirming them against a payment that may yet be reversed is " +
                "the outcome the whole branch exists to avoid")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theStampRecordsWhenTheReviewStarted_notWhenWeLastAskedAboutIt() {
        val booked = bookOneSeat("F2")
        val payment = charge(booked)
        paymentService.applyNotification(notificationFor(stub.challenge(payment.qrisPartnerReferenceNo)))
        val first = reload(booked.tenant, payment).fraudReviewAt

        // Midtrans retries a notification until it gets a 200, and the reconciliation scan re-reads
        // the same capture+challenge on every run. If either re-stamped, every open review would
        // read as "raised just now" and the age — the only thing that distinguishes a review worth
        // chasing from one raised minutes ago — would be permanently wrong.
        paymentService.applyNotification(notificationFor(stub.challenge(payment.qrisPartnerReferenceNo)))

        assertEquals(first, reload(booked.tenant, payment).fraudReviewAt,
            "first write wins, or 'waiting since' means 'last polled at'")
    }

    /**
     * The regression guard, and the reason this class exists. Before payment.fraud_review_at this row came back
     * `expired` — a captured payment written off because the scan could not tell a fraud
     * hold from an unpaid QR.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aCaptureUnderReview_isNotWrittenOffWhenItsQrValidityLapses_becauseTheMoneyIsAlreadyTaken() {
        val booked = bookOneSeat("F3")
        val payment = charge(booked)
        stub.challenge(payment.qrisPartnerReferenceNo)
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        // A manual review takes as long as a person takes; our QR validity is ~9 minutes and the
        // give-up grace an hour. Every real review outlives both.
        backdateExpiry(payment, "2 days")
        // Reloaded rather than reused, as the lapsed-branch test does: hasLapsed reads expires_at
        // off the Payment it is handed, and the real scan always hands it a freshly loaded row.
        val aged = reload(tenant, payment)

        assertFalse(reconciliationService.reconcileOne(tenant, aged),
            "nothing was resolved — the decision is still outstanding at Midtrans")
        assertEquals(Payment.STATUS_PENDING, reload(tenant, payment).status,
            "a captured payment must survive the scan: writing 'expired' here strands money " +
                "that has already left the customer's account")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anAcceptedReview_stillSettlesTheBooking_longAfterTheQrWouldHaveLapsed() {
        val booked = bookOneSeat("F4")
        val payment = charge(booked)
        stub.challenge(payment.qrisPartnerReferenceNo)
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()
        backdateExpiry(payment, "2 days")
        assertFalse(reconciliationService.reconcileOne(tenant, reload(tenant, payment)))

        // The review comes back accepted, days later. This is the assertion the give-up broke: the
        // outcome has to be applicable, which it is not once the row has been marked terminal.
        stub.settle(payment.qrisPartnerReferenceNo)
        assertTrue(reconciliationService.reconcileOne(tenant, reload(tenant, payment)))

        assertEquals(Payment.STATUS_PAID, reload(tenant, payment).status)
        assertEquals("confirmed", reloadSeat(booked).status,
            "the seats are the point — a payment marked paid whose reservations stayed held is " +
                "the same customer-visible failure as no settlement at all")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aDeniedReview_failsThePayment_andTheRowLeavesTheQueueWithNothingClearingTheStamp() {
        val booked = bookOneSeat("F5")
        val payment = charge(booked)
        paymentService.applyNotification(notificationFor(stub.challenge(payment.qrisPartnerReferenceNo)))
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        assertTrue(paymentService.applyNotification(notificationFor(
            stub.deny(payment.qrisPartnerReferenceNo))))

        val after = reload(tenant, payment)
        assertEquals(Payment.STATUS_FAILED, after.status)
        assertNotNull(after.fraudReviewAt,
            "the stamp is history and stays put — a disputed booking is reviewed months later")
        assertFalse(after.isUnderFraudReview(),
            "but it is no longer open, so the queue empties itself: no clearing path exists to " +
                "forget to call")
        assertTrue(db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findUnderFraudReview(c, tenant.tenantId, 100)
        }.stream()
            .noneMatch { p -> p.paymentId == payment.paymentId },
            "a resolved review must drop off the staff queue")
    }

    /**
     * The surfacing half: the open question was whether a challenged
     * payment should reach staff at all rather than sitting in a log line. Asserted as markup,
     * because a queue nobody can see is the state this change exists to end.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun bothOutstandingObligations_reachStaffOnOnePage() {
        val held = bookOneSeat("F6")
        val challenged = charge(held)
        paymentService.applyNotification(notificationFor(stub.challenge(challenged.qrisPartnerReferenceNo)))

        // The other half of the same page: a paid booking cancelled afterwards owes money back,
        // and findRefundsDue had no caller anywhere before this screen.
        val refunded = bookOneSeat("F7")
        val cash = paymentService.settleCash(refunded.group.reservationGroupId!!).payment!!
        assertTrue(bookingService.cancelReservation(refunded.reservation.reservationId,
            "tenant_staff", "customer changed plans"))

        given().`when`().get("/admin/payments")
            .then().statusCode(200)
            .body(containsString("Held for fraud review"))
            .body(containsString(challenged.qrisPartnerReferenceNo))
            .body(containsString("Refunds owed"))
            .body(containsString(cash.qrisPartnerReferenceNo))
            .body(containsString("customer changed plans"))
            // Linked to the booking, because "order BP-…" alone tells staff nothing about who
            // is waiting or for which seats.
            .body(containsString("/orders/" + challenged.reservationGroupId))
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

    private fun charge(booked: Booked): Payment =
        paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()

    private fun reload(tenant: Tenant, payment: Payment): Payment =
        db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }.orElseThrow()

    private fun reloadSeat(booked: Booked): Reservation =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow()

    /** Ages the charge past its own validity window. Direct JDBC because there is no application
     * path that moves expires_at backwards, and there should not be one. */
    private fun backdateExpiry(payment: Payment, interval: String) {
        try {
            DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
                admin.prepareStatement(
                    "UPDATE payment SET expires_at = now() - interval '" + interval + "' " +
                        "WHERE payment_id = ?").use { ps ->
                    ps.setObject(1, payment.paymentId)
                    assertEquals(1, ps.executeUpdate(), "the charge to age must exist")
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException(e)
        }
    }

    private fun notificationFor(s: MidtransGateway.TransactionStatus): MidtransNotification =
        MidtransNotification(s.orderId, s.transactionId, s.transactionStatus,
            s.fraudStatus, s.statusCode, s.grossAmount, s.signatureKey, "qris",
            "{\"order_id\":\"" + s.orderId + "\",\"transaction_status\":\"" +
                s.transactionStatus + "\"}")
}
