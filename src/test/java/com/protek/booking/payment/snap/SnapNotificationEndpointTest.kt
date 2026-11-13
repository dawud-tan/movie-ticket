package com.protek.booking.payment.snap

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.payment.MidtransGateway
import com.protek.booking.payment.PaymentService
import com.protek.booking.payment.StubMidtransGateway
import com.protek.booking.repository.PaymentRepository
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
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * The BI-SNAP notification endpoint's HTTP contract.
 *
 * The status codes are the point, and they are not cosmetic: Midtrans retries a failed
 * delivery — measured at roughly 100 seconds on the real sandbox — so the `responseCode`
 * decides whether a situation resolves or becomes an unending redelivery loop.
 *
 * Credentials are installed per test with a key pair this test owns, standing in for Midtrans's
 * published public key. Without that, an unconfigured verifier correctly rejects everything, and
 * "did the request reach the handler" and "was the signature bad" become indistinguishable from
 * outside — the same trap `PaymentWebhookEndpointTest` documents for the legacy endpoint.
 */
@QuarkusTest
class SnapNotificationEndpointTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
        private val MIDTRANS_KEYS = generateKeys()

        /**
         * The four-part notification string, spelled out here rather than obtained from
         * [SnapSignatures].
         *
         * Deliberate, and established by mutation: an earlier version of this helper called the
         * production builder, which made every test in this class blind to the shape of that string —
         * changing the formula changed both the signing and the verifying side together, and they went
         * on agreeing with each other while agreeing with nothing Midtrans would send. Constructing it
         * independently is what makes these tests able to fail when the production formula drifts.
         */
        private fun independentStringToSign(body: String, timestamp: String): String {
            try {
                val minified = com.fasterxml.jackson.databind.ObjectMapper().readTree(body).toString()
                val hash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(minified.toByteArray(StandardCharsets.UTF_8)))
                return "POST:" + SnapNotificationVerifier.NOTIFY_PATH + ":" + hash + ":" + timestamp
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun body(orderId: String?, status: String, amount: String = "50000.00"): String {
            return "{\"originalPartnerReferenceNo\":\"" + orderId + "\"," +
                "\"originalReferenceNo\":\"midtrans-ref-1\"," +
                "\"latestTransactionStatus\":\"" + status + "\"," +
                "\"amount\":{\"value\":\"" + amount + "\",\"currency\":\"IDR\"}}"
        }

        /** A `'04'` as the sandbox showed it: `amount.value` stays the original charge, and
         * `additionalInfo.refundHistory[]` lists every refund so far ([refunds]: refundNo to amount). */
        private fun refundBody(orderId: String?, vararg refunds: Pair<String, String>,
                               charged: String = "50000.00", reason: String = "test"): String =
            body(orderId, "04", charged).dropLast(1) + ",\"additionalInfo\":{\"refundHistory\":[" +
                refunds.joinToString(",") { (no, amount) ->
                    "{\"reason\":\"$reason\",\"refundNo\":\"$no\",\"partnerReferenceNo\":\"p-$no\"," +
                        "\"refundStatus\":\"00\",\"refundAmount\":{\"value\":\"$amount\",\"currency\":\"IDR\"}}"
                } + "]}}"

        private fun rsaSign(stringToSign: String): String {
            try {
                val signer = Signature.getInstance("SHA256withRSA")
                signer.initSign(MIDTRANS_KEYS.private)
                signer.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
                return Base64.getEncoder().encodeToString(signer.sign())
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun generateKeys(): KeyPair {
            try {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                return generator.generateKeyPair()
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }
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
    lateinit var refundService: com.protek.booking.payment.RefundService
    @Inject
    lateinit var mailbox: io.quarkus.mailer.MockMailbox
    @Inject
    lateinit var customerRepo: com.protek.booking.repository.CustomerRepository
    @Inject
    lateinit var orderWatches: com.protek.booking.repository.OrderWatchRepository

    private lateinit var stub: StubMidtransGateway

    /** Stands in for the real credentials: `configured` is private and set only by the real
     * load path, so the two accessors this endpoint actually uses are overridden instead. */
    class TestCredentials(private val publicKey: PublicKey) : SnapCredentials() {
        override fun isConfigured(): Boolean = true

        override fun midtransPublicKey(): PublicKey = publicKey
    }

    @BeforeEach
    fun installCredentialsAndStub() {
        QuarkusMock.installMockForType(TestCredentials(MIDTRANS_KEYS.public), SnapCredentials::class.java)
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        // No legacy signature verifier is installed: this endpoint never touches it. The SNAP
        // path authenticates with Midtrans's public key instead, which is the whole point.
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aGenuinelySignedSettlement_confirmsTheSeats_andAnswers2005200() {
        val booked = bookOneSeat("N1")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val orderId = payment.qrisPartnerReferenceNo

        postSigned(body(orderId, "00"))
            .then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))

        // The seats are the point. A payment marked paid whose reservations stayed held is the
        // same customer-visible failure as no settlement at all.
        assertEquals(Payment.STATUS_PAID, db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow().status)
        assertEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aForgedSignature_isRejectedWith4015200_andConfirmsNothing() {
        val booked = bookOneSeat("N2")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val body = body(payment.qrisPartnerReferenceNo, "00")

        // Everything a real settlement has, except a signature only Midtrans's key could produce.
        // This is the entire attack the endpoint exists to stop: the transition it performs is
        // hold -> confirmed, i.e. free seats.
        given().contentType("application/json")
            .header("X-SIGNATURE", Base64.getEncoder().encodeToString(ByteArray(256)))
            .header("X-TIMESTAMP", "2026-08-15T16:30:00+07:00")
            .body(body)
            .`when`().post(SnapNotificationVerifier.NOTIFY_PATH)
            .then().statusCode(401)
            .body("responseCode", org.hamcrest.Matchers.equalTo("4015200"))

        assertEquals("held", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status,
            "a forged notification must not confirm seats")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aSignatureUsingTheFivePartTransactionalFormula_isRejected() {
        // The trap this whole endpoint is most likely to be built wrong around: the transactional
        // signature inserts an access-token field, making five parts where a notification has four.
        // A verifier copied from the charge path rejects every genuine notification and fails
        // exactly as a forgery does, so nothing in the logs would say "wrong formula".
        val booked = bookOneSeat("N3")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val body = body(payment.qrisPartnerReferenceNo, "00")
        val timestamp = "2026-08-15T16:30:00+07:00"
        // Also built literally rather than via SnapSignatures, for the same reason as
        // independentStringToSign: a test that asks production code what "wrong" looks like
        // cannot notice production code changing its mind about what "right" is.
        // A Regex, because Java's replaceFirst takes one and Kotlin's String overload is literal.
        val wrongShape = rsaSign(independentStringToSign(body, timestamp)
            .replaceFirst(Regex("^POST:([^:]*):"), "POST:\$1:some-token:"))

        given().contentType("application/json")
            .header("X-SIGNATURE", wrongShape)
            .header("X-TIMESTAMP", timestamp)
            .body(body)
            .`when`().post(SnapNotificationVerifier.NOTIFY_PATH)
            .then().statusCode(401)
    }

    @Test
    fun aVerifiedNotificationForAnUnknownOrder_answers4045201() {
        // Not retryable — our record is missing, not the caller's fault — so it must not be a
        // status that makes Midtrans keep redelivering forever.
        postSigned(body("BP-does-not-exist", "00"))
            .then().statusCode(404)
            .body("responseCode", org.hamcrest.Matchers.equalTo("4045201"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aRedeliveredSettlement_stillAnswers2005200_soTheRetriesStop() {
        val booked = bookOneSeat("N4")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val body = body(payment.qrisPartnerReferenceNo, "00")

        postSigned(body).then().statusCode(200)
        // Midtrans retries until it gets a success, and it redelivers a notification it has already
        // sent. Answering anything else the second time turns a fully-handled settlement into an
        // unending loop against this endpoint.
        postSigned(body).then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))
        postSigned(body).then().statusCode(200)

        assertEquals(1, paymentService.paymentsFor(booked.group.reservationGroupId).size,
            "a redelivery must not create a second payment row")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun expiry_marksThePaymentExpired_butLeavesTheSeatsHeld() {
        val booked = bookOneSeat("N5")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()

        // "08" is Expiry in the notification set, which is NOT the same set the query API uses.
        postSigned(body(payment.qrisPartnerReferenceNo, "08")).then().statusCode(200)

        assertEquals(Payment.STATUS_EXPIRED, db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow().status)
        // Deliberate, and inherited from the legacy path: the hold has its own expiry and sweep,
        // and a customer whose QR expired may still pay within the remaining window via a fresh
        // charge. Releasing here takes the seat from someone still entitled to it.
        assertEquals("held", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    // BI-SNAP '04'. Clear the debt, flag the
    // rest, move no seat. Status stays paid: the seats are still sold.

    private fun paidSeat(label: String): Pair<Booked, Payment> {
        val booked = bookOneSeat(label)
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()
        postSigned(body(payment.qrisPartnerReferenceNo, "00")).then().statusCode(200)
        return booked to payment
    }

    private fun reread(booked: Booked, payment: Payment): Payment =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow()

    private fun seatStatus(booked: Booked): String? =
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findByGroup(c, booked.tenant.tenantId, booked.group.reservationGroupId)
        }.single().status

    private fun flagged(booked: Booked): List<Payment> = paymentService.gatewayRefundsFlagged()
        .filter { it.reservationGroupId == booked.group.reservationGroupId }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aGatewayRefundOfWhatWasOwed_clearsTheDebt_onceOnly_andMovesNoSeat() {
        val (booked, payment) = paidSeat("N6")
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.recordRefundDue(c, booked.tenant.tenantId, payment.paymentId, SEAT_PRICE, "test debt")
        }

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "30000.00")).then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))
        assertEquals(0, BigDecimal("20000").compareTo(reread(booked, payment).refundDueAmount), "reduced by what came back")
        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "30000.00")).then().statusCode(200)
        assertEquals(0, BigDecimal("20000").compareTo(reread(booked, payment).refundDueAmount),
            "a redelivered '04' is not a second refund")

        // The history is cumulative: the second notification lists the first refund again.
        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "30000.00", "R2" to "20000.00"))
            .then().statusCode(200)
        val cleared = reread(booked, payment)
        assertEquals(0, BigDecimal.ZERO.compareTo(cleared.refundDueAmount))
        assertNull(cleared.refundDueAt, "V1's CHECK: no debt, no stamp")
        assertEquals(Payment.STATUS_PAID, cleared.status)
        assertEquals("confirmed", seatStatus(booked), "no seat moves")
        assertTrue(flagged(booked).isEmpty(), "a refund of a debt is not something staff must judge")
        assertTrue(cleared.rawWebhookPayload!!.contains("\"latestTransactionStatus\": \"00\""),
            "the settlement's own payload is kept: ${cleared.rawWebhookPayload}")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aGatewayRefundOfAPaymentThatOwedNothing_leavesTheSeatSold_andIsListedForStaff() {
        val (booked, payment) = paidSeat("N7")

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00")).then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))

        assertEquals(Payment.STATUS_PAID, reread(booked, payment).status)
        assertEquals("confirmed", seatStatus(booked))
        assertEquals("refunded at the gateway, seats still sold", flagged(booked).single().refundReason)
        given().`when`().get("/admin/payments").then().statusCode(200)
            .body(containsString("Gateway refunds to check"))
            .body(containsString(payment.qrisPartnerReferenceNo))
    }

    // The queue used to keep every flagged row forever.
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aResolvedGatewayRefundLeavesTheQueue_andALaterRefundNoticeListsItAgain() {
        val (booked, payment) = paidSeat("N10")
        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00")).then().statusCode(200)
        assertEquals(1, flagged(booked).size)
        val staffId = java.util.UUID.randomUUID()
        QuarkusMock.installMockForType(
            com.protek.booking.support.ActingAs.staff(staffId), com.protek.booking.security.CurrentActor::class.java)

        given().redirects().follow(false).contentType("application/x-www-form-urlencoded").`when`()
            .post("/admin/payments/" + payment.paymentId + "/gateway-refund-resolved")
            .then().statusCode(303)
        assertTrue(flagged(booked).isEmpty(), "a resolved row is still listed")
        val stamped = reread(booked, payment).rawWebhookPayload!!
        assertTrue(stamped.contains("\"_gatewayRefundResolvedBy\": \"$staffId\""), stamped)
        assertTrue(stamped.contains("_gatewayRefundFlag"), "the flag stays as the record of what was judged")
        assertEquals("confirmed", seatStatus(booked), "resolving moves no seat")

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00", "R2" to "1000.00")).then().statusCode(200)
        assertEquals(1, flagged(booked).size, "a second refund is a second thing to judge")
    }

    @Test
    @TestSecurity(user = "someone@example.com", roles = ["customer"])
    fun aCustomerCannotResolveAGatewayRefund() {
        given().redirects().follow(false).contentType("application/x-www-form-urlencoded").`when`()
            .post("/admin/payments/" + java.util.UUID.randomUUID() + "/gateway-refund-resolved")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aGatewayRefundWithAnUnreadableAmount_clearsNothing_andIsFlagged() {
        val (booked, payment) = paidSeat("N8")
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.recordRefundDue(c, booked.tenant.tenantId, payment.paymentId, SEAT_PRICE, "test debt")
        }

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "lima puluh ribu")).then().statusCode(200)

        assertEquals(0, SEAT_PRICE.compareTo(reread(booked, payment).refundDueAmount))
        assertTrue(flagged(booked).single().refundReason!!.contains("unreadable"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aGatewayRefundOfAnUnpaidCharge_changesItsStatusNot_andIsFlagged() {
        val booked = bookOneSeat("N9")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00")).then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))

        assertEquals(Payment.STATUS_PENDING, reread(booked, payment).status)
        assertEquals("held", seatStatus(booked))
        assertEquals(1, flagged(booked).size)
    }

    // '04' used to book amount.value, the whole order, as the refund.
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun oneSeatOfTwoRefundedOnTheDashboard_clearsThatSeatsDebt_notTheOrders() {
        val booked = bookSeats("P1", "P2")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()
        postSigned(body(payment.qrisPartnerReferenceNo, "00", "100000.00")).then().statusCode(200)
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.recordRefundDue(c, booked.tenant.tenantId, payment.paymentId, SEAT_PRICE, "one seat cancelled")
        }

        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00", charged = "100000.00"))
            .then().statusCode(200)
        assertEquals(0, BigDecimal.ZERO.compareTo(reread(booked, payment).refundDueAmount))
        assertTrue(flagged(booked).isEmpty(), "the seat's own refund is not an over-refund")

        // Same refund, a body that differs (Midtrans's reason text): not counted again, not flagged.
        postSigned(refundBody(payment.qrisPartnerReferenceNo, "R1" to "50000.00", charged = "100000.00",
            reason = "edited")).then().statusCode(200)
        val after = reread(booked, payment)
        assertEquals(0, BigDecimal.ZERO.compareTo(after.refundDueAmount))
        assertTrue(flagged(booked).isEmpty())
        assertEquals(listOf("R1"), com.protek.booking.payment.RefundLedger.applied(after.rawWebhookPayload).map { it.refundNo })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aRefundNoticeWithNoHistory_clearsNothing_evenWithAnAmount_andIsFlagged() {
        val (booked, payment) = paidSeat("N11")
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.recordRefundDue(c, booked.tenant.tenantId, payment.paymentId, SEAT_PRICE, "test debt")
        }

        postSigned(body(payment.qrisPartnerReferenceNo, "04")).then().statusCode(200)
            .body("responseCode", org.hamcrest.Matchers.equalTo("2005200"))

        assertEquals(0, SEAT_PRICE.compareTo(reread(booked, payment).refundDueAmount), "amount.value is never the refund")
        assertTrue(flagged(booked).single().refundReason!!.contains("history missing"))
    }

    // the automatic refund. The stub answers 2007800 with a fresh refundNo unless scripted.

    private fun paidSeats(vararg labels: String): Pair<Booked, Payment> {
        val booked = bookSeats(*labels)
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false).orElseThrow()
        postSigned(body(payment.qrisPartnerReferenceNo, "00", "%d.00".format(50000 * labels.size))).then().statusCode(200)
        return booked to payment
    }

    /** The sweep is tenant-wide, and every test here shares the tenant: count this order's calls only. */
    private fun callsFor(payment: Payment) = stub.refunds.filter { it.orderId == payment.qrisPartnerReferenceNo }

    /** Hands the order to a signed-in customer with an address of its own: a counter sale belongs
     * to the walk-in placeholder, which is mailed nothing. */
    private fun ownedByABuyer(booked: Booked): String {
        val email = "buyer-" + java.util.UUID.randomUUID() + "@example.com"
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            val customer = customerRepo.insert(c, booked.tenant.tenantId, "Buyer",
                "+62813" + (Math.random() * 1_000_0000).toLong(), email, true)
            c.prepareStatement("UPDATE reservation_group SET customer_id = ? WHERE reservation_group_id = ?").use {
                it.setObject(1, customer.customerId); it.setObject(2, booked.group.reservationGroupId); it.executeUpdate()
            }
        }
        return email
    }

    private fun awaitMails(to: String, count: Int): List<io.quarkus.mailer.Mail> {
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (mailbox.getMailsSentTo(to).orEmpty().size < count && System.nanoTime() < until) Thread.sleep(50)
        return mailbox.getMailsSentTo(to).orEmpty().also { assertEquals(count, it.size, "mails to $to: $it") }
    }

    private fun owed(booked: Booked, payment: Payment): BigDecimal = reread(booked, payment).refundDueAmount!!

    private fun awaitOwed(booked: Booked, payment: Payment, expected: BigDecimal) {
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (owed(booked, payment).compareTo(expected) != 0 && System.nanoTime() < until) Thread.sleep(50)
        assertEquals(0, expected.compareTo(owed(booked, payment)), "owed back")
    }

    private fun recordDebt(booked: Booked, payment: Payment, amount: BigDecimal = SEAT_PRICE) {
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.recordRefundDue(c, booked.tenant.tenantId, payment.paymentId, amount, "test debt")
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffCancellingOnePaidSeatOfTwo_refundsThatSeat_andMidtransesLater04CountsNothingMore() {
        stub.refundsSucceed()
        val (booked, payment) = paidSeats("Q1", "Q2")

        bookingService.cancelReservation(booked.reservation.reservationId, "tenant_staff", "Released by staff")
        awaitOwed(booked, payment, BigDecimal.ZERO)
        val call = callsFor(payment).single()
        assertEquals(0, SEAT_PRICE.compareTo(call.amount), "the seat's price, never the order's")
        assertEquals("midtrans-ref-1", call.referenceNo)
        assertTrue(!call.resend)
        val counted = com.protek.booking.payment.RefundLedger.applied(reread(booked, payment).rawWebhookPayload)

        // Midtrans's own '04' for the same refund: its history's refundNo is the answer's referenceNo.
        postSigned(refundBody(payment.qrisPartnerReferenceNo, counted.single().refundNo to "50000.00",
            charged = "100000.00")).then().statusCode(200)
        assertEquals(0, BigDecimal.ZERO.compareTo(owed(booked, payment)))
        assertEquals(1, com.protek.booking.payment.RefundLedger.applied(reread(booked, payment).rawWebhookPayload).size)
        assertTrue(flagged(booked).isEmpty(), "our own refund is not one for staff to judge")

        // Step 4: what the buyer sees on the web.
        given().`when`().get("/orders/" + booked.group.reservationGroupId + "/fragment").then().statusCode(200)
            .body(containsString("Rp 50.000 returned to your payment app on"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun a04ThatArrivesBeforeTheRefundsOwnAnswer_isCountedOnce_andTheBuyerIsStillTold() {
        val (booked, payment) = paidSeat("Q3")
        val buyer = ownedByABuyer(booked)
        recordDebt(booked, payment)
        // Midtrans notified the server within a second of each sandbox refund: here, before we hear back.
        stub.refundScript += { call ->
            postSigned(refundBody(payment.qrisPartnerReferenceNo, "RN-RACE" to "50000.00")).then().statusCode(200)
            MidtransGateway.RefundResult.Refunded("RN-RACE", call.amount)
        }

        refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)

        assertEquals(0, BigDecimal.ZERO.compareTo(owed(booked, payment)))
        assertEquals(listOf("RN-RACE"),
            com.protek.booking.payment.RefundLedger.applied(reread(booked, payment).rawWebhookPayload).map { it.refundNo })
        // The '04' tells nobody, so this answer must: the server lost the mail and the push this way on
        // 2026-11-06, when Midtrans's '04' landed 3 ms before the refund's own answer was recorded.
        assertTrue(awaitMails(buyer, 1).single().subject.contains("Refund sent"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aResendThatRacesTheFirstCall_isCountedOnce_andTellsTheBuyerOnce() {
        val (booked, payment) = paidSeat("Q6")
        val buyer = ownedByABuyer(booked)
        recordDebt(booked, payment)
        // The sweep resends the same number while the first call is still out; both come back Refunded.
        stub.refundScript += { call ->
            refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)
            MidtransGateway.RefundResult.Refunded("RN-TWICE", call.amount)
        }
        stub.refundScript += { call -> MidtransGateway.RefundResult.Refunded("RN-TWICE", call.amount) }

        refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)

        val (first, second) = callsFor(payment)
        assertEquals(first.partnerRefundNo, second.partnerRefundNo, "the race this pins: one number, sent twice")
        assertEquals(0, BigDecimal.ZERO.compareTo(owed(booked, payment)))
        assertEquals(listOf("RN-TWICE"),
            com.protek.booking.payment.RefundLedger.applied(reread(booked, payment).rawWebhookPayload).map { it.refundNo })
        awaitMails(buyer, 1)
        Thread.sleep(500)
        assertEquals(1, mailbox.getMailsSentTo(buyer).orEmpty().size, "the second answer tells nobody")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aRefundWithNoAnswer_isResentByTheSweepUnderTheSameNumber_andCountsOnlyWhenItLands() {
        val (booked, payment) = paidSeat("Q4")
        recordDebt(booked, payment)
        stub.refundScript += { MidtransGateway.RefundResult.Transient("timeout") }

        refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)
        assertEquals(0, SEAT_PRICE.compareTo(owed(booked, payment)), "no evidence, no reduction")

        stub.refundsSucceed()
        refundService.retryStarted(booked.tenant.tenantId, 25)
        assertEquals(0, BigDecimal.ZERO.compareTo(owed(booked, payment)))
        val (first, second) = callsFor(payment)
        assertEquals(first.partnerRefundNo, second.partnerRefundNo, "a crash or timeout resends the same refund")
        assertTrue(second.resend, "and the gateway looks in the history before sending it again")

        refundService.retryStarted(booked.tenant.tenantId, 25)
        assertEquals(2, callsFor(payment).size, "a finished refund is not swept")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aRefusedRefund_isNotRetried_andWaitsForStaffWithASentenceTheyCanActOn() {
        val (booked, payment) = paidSeat("Q5")
        val buyer = ownedByABuyer(booked)
        recordDebt(booked, payment)
        stub.refundScript += { MidtransGateway.RefundResult.Refused("4037814", "Insufficient Funds") }

        refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)
        refundService.retryStarted(booked.tenant.tenantId, 25)

        assertEquals(1, callsFor(payment).size, "a refusal is not retried")
        assertEquals(0, SEAT_PRICE.compareTo(owed(booked, payment)), "still owed")
        assertEquals("Midtrans balance already paid out: refund from the dashboard or in cash",
            flagged(booked).single().refundReason)
        assertTrue(refundService.attempt(booked.tenant.tenantId, payment.paymentId) == null,
            "nor does a later cancel restart it")
        assertTrue(awaitMails(buyer, 1).single().text.contains("Our staff will return it to you"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aRefundMidtransNeverAnswers_stopsAtTheCeiling_andGoesToStaff() {
        val (booked, payment) = paidSeat("Q6")
        recordDebt(booked, payment)
        repeat(refundServiceMaxAttempts()) {
            stub.refundScript += { MidtransGateway.RefundResult.Transient("timeout") }
            refundService.attempt(booked.tenant.tenantId, payment.paymentId!!)
        }

        assertTrue(flagged(booked).single().refundReason!!.startsWith("Midtrans did not answer"))
        refundService.retryStarted(booked.tenant.tenantId, 25)
        assertEquals(refundServiceMaxAttempts(), callsFor(payment).size)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBuyerWhoOptedIn_isPushedTheCancelAndTheRefund_whereTheMailGoes() {
        stub.refundsSucceed()
        val (booked, payment) = paidSeats("Q5")
        db.inTenantTransaction(booked.tenant.tenantId) { c ->
            orderWatches.register(c, booked.tenant.tenantId, booked.group.reservationGroupId!!, "fid-buyer", "id")
        }
        val push = com.protek.booking.push.RecordingPushService()
        QuarkusMock.installMockForType(push, com.protek.booking.service.PushNotificationService::class.java)

        bookingService.cancelShowing(booked.reservation.resourcePoolSessionId)

        awaitOwed(booked, payment, BigDecimal.ZERO)
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        fun orderSends() = push.sends.filter { it.data["type"] == "order" }
        while (orderSends().size < 2 && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(setOf("SHOWING_CANCELLED Jadwal tayang dibatalkan", "REFUND_SENT Dana dikembalikan"),
            orderSends().map { it.data["event"] + " " + it.title }.toSet())
        assertTrue(orderSends().all { it.tokens == listOf("fid-buyer") })
    }

    private fun refundServiceMaxAttempts(): Int =
        org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue("booking.refund.max-attempts", Int::class.java).orElse(12)

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun cancellingAShowing_refundsEveryPaidOrderInFull_butNeverCash() {
        stub.refundsSucceed()
        val (booked, payment) = paidSeats("Q7", "Q8")
        val buyer = ownedByABuyer(booked)
        val cash = bookSeats("Q9")
        assertTrue(paymentService.settleCash(cash.group.reservationGroupId!!).settled())
        val sentBefore = mailbox.totalMessagesSent

        bookingService.cancelShowing(booked.reservation.resourcePoolSessionId)
        bookingService.cancelShowing(cash.reservation.resourcePoolSessionId)

        awaitOwed(booked, payment, BigDecimal.ZERO)
        assertEquals(0, BigDecimal("100000").compareTo(callsFor(payment).single().amount))
        Thread.sleep(500)
        assertTrue(stub.refunds.none { it.orderId.startsWith("CASH-") }, "the counter hands cash back; nothing goes to Midtrans")

        // Step 5: the buyer hears twice, in one message each, Indonesian then English.
        val (cancelled, sent) = awaitMails(buyer, 2).sortedBy { if (it.subject.startsWith("Pertunjukan")) 0 else 1 }
        assertTrue(cancelled.text.indexOf("Pertunjukan berikut dibatalkan") < cancelled.text.indexOf("The cinema cancelled"), cancelled.text)
        assertTrue(cancelled.text.contains("Rp 100.000 is being returned automatically"), cancelled.text)
        assertTrue(cancelled.text.contains("Q7, Q8") || cancelled.text.contains("Q8, Q7"), cancelled.text)
        assertTrue(sent.text.contains("Rp 100.000 was returned to your payment app on"), sent.text)
        assertEquals(sentBefore + 2, mailbox.totalMessagesSent, "the walk-in counter sale gets none")
    }

    @Test
    fun aMalformedBody_answers4005200_ratherThanFailingOpaquely() {
        given().contentType("application/json")
            .header("X-SIGNATURE", "whatever")
            .header("X-TIMESTAMP", "2026-08-15T16:30:00+07:00")
            .body("{not json")
            .`when`().post(SnapNotificationVerifier.NOTIFY_PATH)
            .then().statusCode(400)
            .body("responseCode", org.hamcrest.Matchers.equalTo("4005200"))
    }

    @Test
    fun theHttpStatusAlwaysMatchesTheResponseCodesOwnPrefix() {
        // The seven-digit code embeds its HTTP status, so the two disagreeing would be a defect
        // visible to Midtrans's retry logic rather than to us.
        postSigned(body("BP-does-not-exist", "00")).then()
            .statusCode(404)
            .body("responseCode", org.hamcrest.Matchers.startsWith("404"))
    }

    // ------------------------------------------------------------------ helpers

    private fun postSigned(body: String): io.restassured.response.Response {
        val timestamp = "2026-08-15T16:30:00+07:00"
        return given().contentType("application/json")
            .header("X-SIGNATURE", rsaSign(independentStringToSign(body, timestamp)))
            .header("X-TIMESTAMP", timestamp)
            .body(body)
            .`when`().post(SnapNotificationVerifier.NOTIFY_PATH)
    }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservation: Reservation)

    private fun bookOneSeat(seatLabel: String): Booked = bookSeats(seatLabel)

    private fun bookSeats(vararg seatLabels: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seats = seatLabels.mapIndexed { i, l -> fixture.unit(tenant, pool, l, l.substring(0, 1), i + 1) }
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, seats.map { it.resourceUnitId })
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations[0])
    }
}
