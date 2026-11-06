package com.protek.booking.payment

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.payment.snap.GatewayRefund
import com.protek.booking.payment.snap.SnapNotification
import com.protek.booking.payment.snap.SnapNotificationVerifier
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.SeatAvailabilityNotifier
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * QRIS Dynamic MPM payment, covering both trigger points — the
 * customer's own device and the counter — plus the manual cash path.
 *
 * **The hold is what this justifies.** Payment is the hold
 * mechanism's real-world justification, and that is literal: before it a hold existed to be
 * confirmed by a mocked action, so its expiry was a policy choice. With a real payment, the
 * hold is the window in which a customer can pay for a specific seat without anyone else
 * taking it, and the QR's expiry must sit inside the hold's — a QR outliving its hold could be
 * paid for a seat already released to someone else, which is money taken for nothing.
 */
@ApplicationScoped
class PaymentService {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var currentActor: CurrentActor

    @Inject
    lateinit var paymentRepo: PaymentRepository

    @Inject
    lateinit var reservationRepo: ReservationRepository

    @Inject
    lateinit var groupRepo: ReservationGroupRepository

    @Inject
    lateinit var tenantRepo: TenantRepository

    @Inject
    lateinit var midtrans: MidtransGateway

    @Inject
    lateinit var signatureVerifier: MidtransSignatureVerifier

    @Inject
    lateinit var snapVerifier: SnapNotificationVerifier

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @Inject
    lateinit var refundService: RefundService

    @ConfigProperty(name = "midtrans.merchant-id")
    @JvmField
    final var merchantId: String? = null

    @ConfigProperty(name = "booking.hold-duration-minutes", defaultValue = "10")
    @JvmField
    final var holdDurationMinutes: Int = 10

    /**
     * Minutes shaved off the hold duration when setting the QR's expiry, so the QR always
     * expires strictly before the hold it belongs to. Without this margin the two expire
     * together and a payment landing in the overlap settles a seat the expiry sweep may already
     * have released.
     */
    @ConfigProperty(name = "midtrans.expiry-safety-margin-minutes", defaultValue = "1")
    @JvmField
    final var expirySafetyMarginMinutes: Int = 1

    fun isEnabled(): Boolean = midtrans.isConfigured()

    /**
     * Creates a QRIS charge for a held group. Called after the hold succeeds, not before: the
     * seats must be exclusively claimed before a customer is asked for money, or a paid charge
     * could find its seats already gone.
     *
     * @return empty when Midtrans is not configured — the caller then falls back to the
     *         pay-at-counter flow rather than failing a booking that is otherwise fine. A
     *         missing payment integration should not cost a tenant a sale.
     * @throws OrderAlreadyPaidException when the order already has a paid payment, cash or QRIS.
     *         Checked under the same seat lock [settleCash] takes, so a QR and a cash
     *         press cannot cross: whichever locks second sees the other.
     */
    fun createQrisCharge(reservationGroupId: UUID, fromCounter: Boolean): Optional<Payment> {
        if (!midtrans.isConfigured()) {
            LOG.debugf("Midtrans not configured; group %s stays on the pay-at-counter path",
                reservationGroupId)
            return Optional.empty()
        }
        val tenantId = currentTenant.id()

        // Our own order id, random rather than derived from the group id. Two reasons: a group
        // whose first charge expired needs a second, distinct order id (Midtrans rejects a
        // reused one), and an unguessable reference means the public webhook lookup — which is
        // deliberately not tenant-scoped — cannot be steered by guessing ids.
        val orderId = "BP-" + UUID.randomUUID()
        val qrExpiryMinutes = maxOf(1, holdDurationMinutes - expirySafetyMarginMinutes)
        val expiresAt = Instant.now().plus(Duration.ofMinutes(qrExpiryMinutes.toLong()))

        val pending = db.inTenantTransaction(tenantId) { c ->
            groupRepo.findById(c, tenantId, reservationGroupId)
                .orElseThrow { NotFoundException("Reservation group not found: $reservationGroupId") }
            // Summed from the reservation rows rather than a stored group total: each row froze
            // its own price_amount at hold time, and those are what the customer agreed to.
            val seats = reservationRepo.findByGroupForUpdate(c, tenantId, reservationGroupId)
            paidPayment(c, tenantId, reservationGroupId)?.let { throw OrderAlreadyPaidException(it) }
            val total = liveTotal(seats)
            if (total.signum() <= 0) {
                throw IllegalStateException("Reservation group " + reservationGroupId +
                    " has no payable amount; refusing to create a zero-value charge")
            }
            paymentRepo.insertPending(
                c, tenantId, reservationGroupId, total, "IDR", merchantId,
                if (fromCounter) Payment.TRIGGER_COUNTER else Payment.TRIGGER_ONLINE,
                orderId, expiresAt)
        }
        val total = pending.amount!!

        // The row is committed BEFORE calling Midtrans, deliberately. If the charge call
        // succeeds but this process dies before writing the result, a pending row with a known
        // order id already exists and reconciliation will find and resolve it. The reverse
        // order — charge first, insert after — can take a customer's money with no local record
        // of the charge at all, which is unrecoverable rather than merely untidy.
        //
        // Under BI-SNAP this stopped being a story about crashes and became routine. Observed
        // 2026-08-15 in production: Midtrans's first notification for an order arrived and
        // was fully handled 30 ms BEFORE this method logged the charge it was notifying about. The
        // notification found its payment row only because the row was already there. Insert-after
        // would not need a dying process to lose it — just a network that answers faster than our
        // own HTTP call returns, which is the normal case rather than the exceptional one.
        val charge = midtrans.chargeQris(orderId, total, qrExpiryMinutes)
        db.inTenantTransaction(tenantId) { c ->
            paymentRepo.attachChargeResult(c, tenantId, pending.paymentId, charge.qrContent, charge.transactionId)
        }

        LOG.infof("Created QRIS charge %s (Midtrans %s) for group %s, %s IDR, expiring in %d min",
            orderId, charge.transactionId, reservationGroupId, total, qrExpiryMinutes)
        return db.inTenantTransaction(tenantId) { c -> paymentRepo.findById(c, tenantId, pending.paymentId) }
    }

    private fun paidPayment(c: Connection, tenantId: UUID, reservationGroupId: UUID): Payment? =
        paymentRepo.findByReservationGroup(c, tenantId, reservationGroupId)
            .firstOrNull { Payment.STATUS_PAID == it.status }

    /** [createQrisCharge] refused: the order is already paid, by `payment`. */
    class OrderAlreadyPaidException(@Transient private val payment: Payment) : RuntimeException(
        "Reservation group " + payment.reservationGroupId + " is already paid (" +
            payment.paymentChannel + ", payment " + payment.paymentId + ")",
    ) {
        fun payment(): Payment = payment
    }

    /**
     * Applies a verified Midtrans notification.
     *
     * The tenant is **derived**, never supplied: a webhook arrives with no identity, and
     * Midtrans echoes back our order id, not our tenancy model. [resolveTenantForOrder]
     * finds the owning tenant from that id, which is safe for the reason
     * `PaymentRepository#findByOrderIdForUpdate` spells out — `qris_partner_reference_no` is
     * UNIQUE and generated by us as a random value, so a caller can present an id that either
     * exists or does not, never steer the lookup at another tenant's row.
     *
     * This used to run with no tenant context at all, on the assumption that "no context" meant
     * "no tenant filter". Under `FORCE ROW LEVEL SECURITY` it means "no rows": the policy is
     * `tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid`, which
     * is NULL — matching nothing — while the GUC has never been set. Every settlement therefore
     * failed as "No payment for order …", and the suite missed it because Dev Services'
     * Testcontainers role is a SUPERUSER and an RLS-exempt role never evaluates a policy at all.
     * Found by running the suite against a plain database owner, which is what
     * this application actually deploys as. `Db#inTransaction` states the same rule in its own
     * contract: it is only valid for `tenant` itself.
     *
     * The `FOR UPDATE` lock is what makes repeated delivery idempotent: Midtrans retries
     * until it gets a 200, so the same settlement arrives several times, and two concurrent
     * deliveries would otherwise both read `pending` and both confirm the group.
     *
     * @return true when this call was the one that applied a state change; false when the
     *         payment was already terminal (a replay), which is still a successful outcome to
     *         report back to Midtrans so it stops retrying.
     */
    fun applyNotification(notification: MidtransNotification): Boolean {
        if (!signatureVerifier.verify(notification.orderId, notification.statusCode,
                notification.grossAmount, notification.signatureKey)) {
            // Deliberately no detail about which part failed: a public endpoint that explains
            // why a signature was rejected is an oracle for constructing a valid one.
            throw InvalidSignatureException("Notification signature verification failed")
        }
        val tenantId = resolveTenantForOrder(notification.orderId)
            ?: throw NotFoundException("No payment for order " + notification.orderId)
        return applyVerifiedStatus(tenantId, notification.orderId, notification.transactionStatus,
            notification.fraudStatus, notification.transactionId, notification.rawBody, true)
    }

    /**
     * Applies a BI-SNAP notification.
     *
     * Verification happens **here** rather than in the resource, mirroring
     * [applyNotification], so that no method on this service applies an unauthenticated
     * status change. The mechanism differs — SHA256withRSA over four parts against Midtrans's
     * public key, instead of a symmetric digest of body fields — but the rule is identical: for
     * this write path the signature *is* the authentication.
     *
     * Everything after verification is shared with the legacy path unchanged: the same tenant
     * scan, the same `FOR UPDATE` lock making repeated delivery idempotent, the same
     * notify-after-commit. A `payment` row does not record which API created it and does not
     * need to — the switch between APIs happens on a zero-pending window instead.
     */
    fun applySnapNotification(notification: SnapNotification): Boolean {
        if (!snapVerifier.verify(notification)) {
            // No detail about which component failed, for the same reason the legacy path gives
            // none: a public endpoint that explains itself is an oracle for forging a signature.
            throw InvalidSignatureException("BI-SNAP notification signature verification failed")
        }
        val orderId = notification.originalPartnerReferenceNo
        val transactionStatus = notification.asLegacyTransactionStatus()
        if (notification.isKnownButUnhandled()) {
            val tenantId = resolveTenantForOrder(orderId)
                ?: throw NotFoundException("No payment for order $orderId")
            return applyGatewayRefund(tenantId, orderId, notification.refundHistory, notification.rawBody)
        }
        if (transactionStatus.isEmpty) {
            LOG.warnf("Unrecognised BI-SNAP latestTransactionStatus '%s' for order %s; payment " +
                "left untouched", notification.latestTransactionStatus, orderId)
            return false
        }
        val tenantId = resolveTenantForOrder(orderId)
            ?: throw NotFoundException("No payment for order $orderId")
        // fraudStatus is null: QRIS under SNAP has no fraud-review state, so the 'challenge' branch
        // in applyVerifiedStatus simply never fires for this path.
        return applyVerifiedStatus(tenantId, orderId, transactionStatus.get(), null,
            notification.originalReferenceNo, notification.rawBody, true)
    }

    /**
     * Finds which tenant owns an order id by trying each tenant's RLS context in turn.
     *
     * A scan rather than a lookup because there is deliberately no order-id → tenant mapping
     * outside `payment` itself, and `payment` is exactly what RLS is hiding. One short
     * read per tenant, and only on the webhook path: reconciliation already holds the tenant whose
     * pending payments it is sweeping and passes it straight through.
     *
     * `findAll` rather than only active tenants: a settlement for an order placed before
     * a tenant was suspended still has to be applied, or a customer has paid for seats that never
     * get confirmed.
     *
     * `orderId` stays nullable, as it was in Java: a validly signed notification lacking the field
     * finds no tenant and is refused as "No payment for order null", not as an NPE.
     */
    private fun resolveTenantForOrder(orderId: String?): UUID? {
        for (tenant in db.inTransaction(tenantRepo::findAll)) {
            val owns = db.inTenantTransaction(tenant.tenantId) { c -> paymentRepo.existsByOrderId(c, orderId) }
            if (owns) {
                return tenant.tenantId
            }
        }
        return null
    }

    /**
     * The shared transition logic behind both the webhook and reconciliation, so the two cannot
     * disagree about what `settlement` or `expire` means. The
     * mapping: settled payment moves hold → confirmed, timeout moves hold → expired.
     *
     * Takes the tenant explicitly rather than deriving it again: `payment` is under FORCE
     * ROW LEVEL SECURITY, so every read below needs the context set (see
     * [applyNotification]). The webhook derives it; reconciliation already knows it.
     *
     * `internal` where the Java was package-private: its one other caller is
     * [PaymentReconciliationService], and no Java code calls it.
     */
    internal fun applyVerifiedStatus(
        tenantId: UUID, orderId: String?, transactionStatus: String?,
        fraudStatus: String?, midtransTransactionId: String?, rawBody: String?,
        signatureVerified: Boolean,
    ): Boolean {
        var settledSeatMap: Payment? = null
        var order: UUID? = null
        var owing: UUID? = null
        val applied = db.inTenantTransaction(tenantId) { c ->
            val payment = paymentRepo.findByOrderIdForUpdate(c, orderId).orElse(null)
                ?: throw NotFoundException("No payment for order $orderId")
            order = payment.reservationGroupId
            val settles = ("settlement" == transactionStatus || "capture" == transactionStatus) &&
                "challenge" != fraudStatus
            if (settles && (Payment.STATUS_EXPIRED == payment.status ||
                    Payment.STATUS_FAILED == payment.status)) {
                // The QR was written off — typically withdrawn because cash was taken while it
                // was on screen — but the customer's bank paid it anyway (the cancel lost the race,
                // or never reached Midtrans). Real money: record it, and if the order is already
                // paid, owe it back rather than leave a silent second paid row.
                return@inTenantTransaction applyLateSettlement(c, payment, midtransTransactionId,
                    signatureVerified, rawBody).also { if (it) owing = payment.paymentId }
            }
            if (payment.isTerminal()) {
                LOG.debugf("Order %s already %s; ignoring duplicate notification",
                    orderId, payment.status)
                return@inTenantTransaction false
            }

            when (transactionStatus ?: "") {
                "settlement", "capture" -> {
                    // 'capture' with fraud_status 'challenge' is NOT a settlement: Midtrans is
                    // asking for a manual decision, and treating it as paid would confirm seats
                    // against a payment that may yet be reversed.
                    if ("challenge" == fraudStatus) {
                        // Stamped, not just logged. Midtrans has the money and is waiting on
                        // a person in their dashboard, so this row is an open obligation — and
                        // until it was a column it was invisible to both the staff who must act on
                        // it and to the reconciliation scan, which read this `return false` as
                        // "still awaiting payment" and wrote the row off an hour later.
                        paymentRepo.markUnderFraudReview(c, orderId)
                        LOG.warnf("Order %s captured but fraud_status=challenge; leaving pending " +
                            "for manual review rather than confirming seats — listed at " +
                            "/admin/payments until the review resolves", orderId)
                        return@inTenantTransaction false
                    }
                    val updated = paymentRepo.markPaid(c, orderId, midtransTransactionId,
                        ACTOR_ENGINE, signatureVerified, rawBody)
                    if (updated == 0) {
                        return@inTenantTransaction false
                    }
                    if (confirmGroupSeats(c, payment) == 0 && !paidElsewhere(c, payment)) {
                        // Paid for seats nobody holds any more — the showing was cancelled with
                        // the QR on screen, or the seats were released: all of it is owed back.
                        paymentRepo.recordRefundDue(c, payment.tenantId, payment.paymentId,
                            payment.amount, REFUND_SEATS_GONE)
                        owing = payment.paymentId
                        LOG.warnf("Order %s settled with no live seat; %s %s owed back", orderId,
                            payment.amount, payment.currency)
                    }
                    if (owedBackIfPaidTwice(c, payment)) owing = payment.paymentId
                    // Recorded for the post-commit notification below rather than pushed from
                    // here: this is still inside the transaction, and a watcher told "confirmed"
                    // would refetch and could read pre-commit availability (notify on commit, never before).
                    settledSeatMap = payment
                    LOG.infof("Order %s settled; confirmed group %s", orderId, payment.reservationGroupId)
                    true
                }
                "expire", "cancel", "deny", "failure" -> {
                    val status = if ("expire" == transactionStatus) Payment.STATUS_EXPIRED else Payment.STATUS_FAILED
                    val updated = paymentRepo.markTerminalStatus(c, orderId, status,
                        signatureVerified, rawBody)
                    if (updated == 0) {
                        return@inTenantTransaction false
                    }
                    // The seats are deliberately NOT released here. The hold has its own
                    // expiry and its own sweep, and a customer whose first QR expired
                    // may still pay within the remaining hold window via a fresh charge.
                    // Releasing on payment failure would take the seat away from someone who
                    // is still entitled to it.
                    LOG.infof("Order %s ended as %s; hold left to its own expiry", orderId, status)
                    true
                }
                "pending" -> {
                    LOG.debugf("Order %s still pending", orderId)
                    false
                }
                else -> {
                    LOG.warnf("Unrecognised Midtrans transaction_status '%s' for order %s; " +
                        "leaving payment pending", transactionStatus, orderId)
                    false
                }
            }
        }

        // Settlement frees nothing, but it does change what the seat map shows (held -> confirmed),
        // and a watcher looking at that showing should see it without reloading.
        settledSeatMap?.let { notifySettledGroup(it) }
        // Every applied change: settlement also reaches the order's sockets through the seat map's
        // notification, but an expired or failed QR moves no seat, and it is what turns the app's
        // order page back to "pay".
        if (applied) order?.let { notifier.notifyOrder(tenantId, it) }
        // Paid twice, seats gone, paid after expiry: a debt the money just created goes back by itself.
        owing?.let { refundService.refundAfterCommit(tenantId, listOf(it)) }
        return applied
    }

    /**
     * BI-SNAP `'04'`: Midtrans says money went back to the buyer.
     * Clear the debt, flag the rest, move no seat.
     *
     * **What came back is read from `additionalInfo.refundHistory[]`, never from `amount.value`**.
     * The sandbox showed `amount.value` stays the original charge after a partial
     * refund, so one seat of two refunded on the dashboard used to clear the whole debt and be
     * flagged as an over-refund. The history is cumulative, so only its refunds not yet in
     * [RefundLedger] count; that same list holds the refunds our own refund call already counted from
     * its answer, so a `'04'` for one of those changes nothing.
     *
     * The status stays `paid`, though V1's CHECK allows `'refunded'`: the seats are still sold, and
     * a `'refunded'` row beside confirmed seats would tell `paidElsewhere`, the order page and the
     * reconciliation scan that the order is unpaid while its tickets still admit. Whatever is not a
     * debt this refund settles (nothing was owed, more came back than was owed, or the history is
     * unreadable) is flagged for staff on `/admin/payments` instead, in the stored payload, because
     * deciding what happens to the seats is a person's call. No migration: V1 is the only one.
     */
    private fun applyGatewayRefund(tenantId: UUID, orderId: String?, history: List<GatewayRefund>?,
                                   rawBody: String?): Boolean {
        var order: UUID? = null
        val applied = db.inTenantTransaction(tenantId) { c ->
            val payment = paymentRepo.findByOrderIdForUpdate(c, orderId).orElse(null)
                ?: throw NotFoundException("No payment for order $orderId")
            order = payment.reservationGroupId
            val known = RefundLedger.applied(payment.rawWebhookPayload).map { it.refundNo }.toSet()
            val fresh = history?.filter { it.refundNo !in known }?.distinctBy { it.refundNo }
            val refunded = fresh?.fold(BigDecimal.ZERO) { sum, r -> sum + r.amount }
            val paid = Payment.STATUS_PAID == payment.status
            val owed = payment.refundDueAmount ?: BigDecimal.ZERO
            val flag = when {
                !paid -> "refunded at the gateway while ${payment.status}"
                fresh == null -> "refunded at the gateway, refund history missing or unreadable; nothing cleared"
                // A redelivery with a changed body, or our own refund already counted from its answer.
                fresh.isEmpty() -> null
                owed.signum() == 0 -> "refunded at the gateway, seats still sold"
                refunded!! > owed -> "refunded at the gateway beyond the $owed owed, seats still sold"
                else -> null
            }
            val counted = if (paid) fresh.orEmpty() else emptyList()
            // Only readable refunds on a paid row clear anything; the CHECK keeps it within amount.
            val remaining = if (counted.isEmpty()) owed else (owed - refunded!!).max(BigDecimal.ZERO)
            if (paymentRepo.recordGatewayRefund(c, payment.tenantId, payment.paymentId, remaining, rawBody, flag,
                    RefundLedger.entries(counted.map { it.refundNo to it.amount }, Instant.now())) == 0) {
                LOG.debugf("Order %s: this '04' was already applied", orderId)
                return@inTenantTransaction false
            }
            LOG.warnf("Order %s refunded at the gateway (%s new of %s in its history); owed back now %s%s",
                orderId, refunded ?: "unreadable", history?.size ?: "none", remaining,
                flag?.let { "; flagged at /admin/payments: $it" }.orEmpty())
            true
        }
        // The order page's "Refund owed" card changes, and that is all: no seat moved.
        if (applied) order?.let { notifier.notifyOrder(tenantId, it) }
        return applied
    }

    private fun applyLateSettlement(
        c: Connection, payment: Payment, midtransTransactionId: String?,
        signatureVerified: Boolean, rawBody: String?,
    ): Boolean {
        val orderId = payment.qrisPartnerReferenceNo
        val otherPaid = paidElsewhere(c, payment)
        if (paymentRepo.markPaidLate(c, orderId, midtransTransactionId, ACTOR_ENGINE,
                signatureVerified, rawBody) == 0) {
            return false
        }
        // Owed back in full either way, and the seats are deliberately NOT confirmed when nothing
        // else paid: the hold may have lapsed and the seat be someone else's by now, and the EXCLUDE
        // constraint, not this webhook, decides who sits there. The buyer gets the money back.
        // A QR we withdrew ('failed') whose seats are all gone is BookingService.cancelShowing's
        // case: name that, not an expiry. A cash-withdrawn QR is the paid-twice case above it.
        val reason = when {
            otherPaid -> REFUND_PAID_TWICE
            Payment.STATUS_FAILED == payment.status &&
                live(reservationRepo.findByGroup(c, payment.tenantId, payment.reservationGroupId)).isEmpty() ->
                REFUND_SEATS_GONE
            else -> REFUND_PAID_AFTER_EXPIRY
        }
        paymentRepo.recordRefundDue(c, payment.tenantId, payment.paymentId, payment.amount, reason)
        LOG.warnf("Order %s was paid after it was %s%s; %s %s owed back, listed at /admin/payments",
            orderId, payment.status, if (otherPaid) " and the order already paid" else "",
            payment.amount, payment.currency)
        return true
    }

    private fun paidElsewhere(c: Connection, payment: Payment): Boolean =
        paymentRepo.findByReservationGroup(c, payment.tenantId, payment.reservationGroupId)
            .any { Payment.STATUS_PAID == it.status && it.paymentId != payment.paymentId }

    /** A pending QR settling for an order that is already paid — a second QR, or one raised
     * before cash and paid before [settleCash] withdrew it. The money is recorded as paid
     * (it is), and owed back in full. */
    private fun owedBackIfPaidTwice(c: Connection, payment: Payment): Boolean {
        val otherPaid = paidElsewhere(c, payment)
        if (otherPaid) {
            paymentRepo.recordRefundDue(c, payment.tenantId, payment.paymentId, payment.amount,
                REFUND_PAID_TWICE)
            LOG.warnf("Order %s settled for a group that was already paid; %s %s owed back",
                payment.qrisPartnerReferenceNo, payment.amount, payment.currency)
        }
        return otherPaid
    }

    /**
     * Looks up the session for a settled group and notifies its watchers.
     *
     * The session is read from the group's reservations rather than carried on `payment`,
     * which references `reservation_group_id` only (never `reservation_id`, to
     * avoid a polymorphic FK) and so has no session of its own.
     */
    private fun notifySettledGroup(payment: Payment) {
        try {
            val reservations = db.inTenantTransaction(payment.tenantId) { c ->
                reservationRepo.findByGroup(c, payment.tenantId, payment.reservationGroupId)
            }
            val sessionId: UUID? = reservations.firstOrNull()?.resourcePoolSessionId
            val poolId: UUID? = db.inTenantTransaction(payment.tenantId) { c ->
                groupRepo.findById(c, payment.tenantId, payment.reservationGroupId)
            }.map { it.resourcePoolId }.orElse(null)
            notifier.notifyWatchers(payment.tenantId, poolId, sessionId,
                SeatAvailabilityNotifier.Reason.SEATS_CONFIRMED)
        } catch (e: RuntimeException) {
            // A settled payment must never be undone by a notification problem — the money is in
            // and the seats are confirmed regardless of who hears about it.
            LOG.warnf(e, "Could not notify watchers for settled group %s", payment.reservationGroupId)
        }
    }

    /**
     * Moves every seat in the group from `held` to `confirmed`.
     *
     * Uses the payment row's own `tenant_id` — derived, not supplied — and runs on the
     * same connection as the payment update so settlement and confirmation commit together. A
     * paid payment with unconfirmed seats is the worst possible split: the customer has been
     * charged and holds nothing.
     *
     * Setting the RLS context here is required, not optional: this connection had none (the
     * webhook path deliberately runs without it), and every reservation table is under FORCE
     * ROW LEVEL SECURITY, so without it the updates would silently match zero rows.
     */
    /** Returns how many seats of the group are confirmed afterwards. */
    private fun confirmGroupSeats(c: Connection, payment: Payment): Int {
        c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
            ps.setString(1, payment.tenantId.toString())
            ps.execute()
        }
        // Locked before the first confirm, because that confirm takes the tenant's audit lock
        // and a row lock waited on after it could deadlock against a cancel holding that row.
        val reservations = reservationRepo.findByGroupForUpdate(c, payment.tenantId, payment.reservationGroupId)
        var confirmed = 0
        for (reservation in reservations) {
            if ("held" == reservation.status) {
                confirmed += reservationRepo.confirm(c, payment.tenantId, reservation.reservationId)
            } else if ("confirmed" == reservation.status) {
                confirmed++
            }
        }
        groupRepo.markConfirmed(c, payment.tenantId, payment.reservationGroupId)
        return confirmed
    }

    /** What one press of "cash received" did. Mirrors `AdmissionService.Outcome`'s reason
     * for being more than a boolean: each refusal means something different at the desk. */
    enum class CashOutcome {
        /** Recorded now, by this call: one paid cash payment, seats confirmed. */
        SETTLED,

        /** The order already has a paid payment — cash or QRIS — and nothing was recorded. The
         * second press of a double click, a retried request, a second staff member, or a customer
         * who paid by QRIS before the cash was taken. */
        ALREADY_PAID,

        /** No seat of the order is held or sold any more — the hold expired, every seat was
         * released — and nothing was recorded. Before 2026-10-02 this recorded a Rp 0 "payment"
         * and marked the order confirmed with no seats in it. */
        NOTHING_TO_PAY,
    }

    /** @property payment the payment of record: the one just made for `SETTLED`, the one that
     *        was already there for `ALREADY_PAID`, null for `NOTHING_TO_PAY`. */
    @JvmRecord
    data class CashSettlement(val outcome: CashOutcome, val payment: Payment?) {
        fun settled(): Boolean = outcome == CashOutcome.SETTLED
    }

    /**
     * The cash path: staff took notes at the counter, so there is no charge to await and
     * nothing to reconcile. Records a paid payment attributed to the staff member and confirms
     * the seats in the same transaction — **at most once per order**.
     *
     * **Why a lock and not a check.** Until 2026-10-02 nothing stopped a second settlement:
     * `payment` is unique only on its gateway reference and this path mints a
     * fresh `CASH-<uuid>` every time, so a double click recorded the money twice and the
     * evening's takings disagreed with the drawer. Reading "is it paid?" first would not have fixed
     * it — two presses in flight both read "no". So every settlement first locks the order's seats
     * (`findByGroupForUpdate`); the second waits for the first to commit, and under READ
     * COMMITTED the statement after the lock sees the payment the first one wrote. That is the
     * "lock taken by everyone, first" the seat rule allows, not the availability pre-check it forbids,
     * and it is the seats rather than the group row for the lock-order reason that method gives.
     *
     * **The unique index behind it.** `idx_payment_one_cash_per_order` allows one paid *cash* payment per order —
     * the belt to these braces, for a future path that inserts without the lock. With the lock it
     * should never fire; if it does, the order was paid in cash by someone else, so the answer is
     * `ALREADY_PAID` rather than a 500. Cash only: a QRIS payment that lands after cash is
     * real money received twice, which must be recorded (and refunded), not refused.
     */
    fun settleCash(reservationGroupId: UUID): CashSettlement {
        val tenantId = currentTenant.id()
        val withdrawn = mutableListOf<Payment>()
        val result = try {
            settleCashInTransaction(tenantId, reservationGroupId, withdrawn)
        } catch (e: DataAccessException) {
            if (!PaymentRepository.isSecondCashPayment(e)) {
                throw e
            }
            // The transaction rolled back; read the payment that won in a fresh one.
            val ofRecord = db.inTenantTransaction(tenantId) { c ->
                paymentRepo.findByReservationGroup(c, tenantId, reservationGroupId)
            }.firstOrNull { Payment.STATUS_PAID == it.status } ?: throw e
            CashSettlement(CashOutcome.ALREADY_PAID, ofRecord)
        }
        val staff = currentActor.email().orElse("staff")
        if (!result.settled()) {
            LOG.infof("Cash settlement refused for group %s by %s: %s", reservationGroupId, staff,
                result.outcome)
            return result
        }
        // SETTLED always carries the payment it just recorded.
        val paid = checkNotNull(result.payment)
        LOG.infof("Cash settlement recorded for group %s (%s IDR) by %s",
            reservationGroupId, paid.amount, staff)
        // Same seat-map change as a QRIS settlement, so the same notification — a counter sale is
        // not a second booking engine and should not be a second notification path either.
        notifySettledGroup(paid)
        cancelAtGateway(withdrawn, "the order was paid in cash")
        return result
    }

    /** After commit, like the push: a failed cancel must not undo the cash or the cancelled
     * showing. If the QR is paid anyway, the webhook records it as owed back
     * ([applyLateSettlement]). [why] finishes "the QR was withdrawn because …" in the log, and
     * is sent to the gateway as the cancel's reason.
     * Takes the payments, not their order ids, because BI-SNAP's cancel also needs Midtrans's
     * own reference for the charge. */
    internal fun cancelAtGateway(withdrawn: List<Payment>, why: String) {
        for (payment in withdrawn) {
            val orderId = payment.qrisPartnerReferenceNo!!
            try {
                midtrans.cancel(orderId, payment.qrisReferenceNo, why)
                LOG.infof("Cancelled QRIS charge %s at the gateway: %s", orderId, why)
            } catch (e: RuntimeException) {
                LOG.errorf(e, "Could not cancel QRIS charge %s at the gateway (%s); " +
                    "if the customer still pays it, it is listed as a refund at /admin/payments",
                    orderId, why)
            }
        }
    }

    private fun settleCashInTransaction(
        tenantId: UUID, reservationGroupId: UUID, withdrawn: MutableList<Payment>,
    ): CashSettlement = db.inTenantTransaction(tenantId) { c ->
        val group = groupRepo.findById(c, tenantId, reservationGroupId)
            .orElseThrow { NotFoundException("Reservation group not found: $reservationGroupId") }
        // The lock first. Everything below reads after it, so it sees a settlement that
        // committed while this one waited.
        val seats = reservationRepo.findByGroupForUpdate(c, tenantId, reservationGroupId)
        val alreadyPaid = paymentRepo.findByReservationGroup(c, tenantId, reservationGroupId)
            .firstOrNull { Payment.STATUS_PAID == it.status }
        if (alreadyPaid != null) {
            return@inTenantTransaction CashSettlement(CashOutcome.ALREADY_PAID, alreadyPaid)
        }
        val live = live(seats)
        if (live.isEmpty()) {
            return@inTenantTransaction CashSettlement(CashOutcome.NOTHING_TO_PAY, null)
        }
        val total = liveTotal(seats)
        val payment = paymentRepo.insertCashPaid(c, tenantId, reservationGroupId, total,
            "IDR", merchantId, "CASH-" + UUID.randomUUID(), ACTOR_STAFF)
        for (reservation in live) {
            if ("held" == reservation.status) {
                reservationRepo.confirm(c, tenantId, reservation.reservationId)
            }
        }
        groupRepo.markConfirmed(c, tenantId, group.reservationGroupId)
        // A QR still on screen for this order would let it be paid twice: withdraw it here,
        // under the lock, and cancel it at the gateway once this commits.
        for (p in paymentRepo.findByReservationGroup(c, tenantId, reservationGroupId)) {
            if (Payment.STATUS_PENDING == p.status && Payment.CHANNEL_QRIS == p.paymentChannel &&
                paymentRepo.markWithdrawn(c, tenantId, p.paymentId) > 0) {
                withdrawn.add(p)
            }
        }
        CashSettlement(CashOutcome.SETTLED, payment)
    }

    fun paymentsFor(reservationGroupId: UUID): List<Payment> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            paymentRepo.findByReservationGroup(c, tenantId, reservationGroupId)
        }
    }

    /**
     * Captures Midtrans is holding for a manual fraud decision — one of the two queues
     * behind `/admin/payments`.
     *
     * Read here rather than in the resource so it runs inside a tenant transaction:
     * `payment` is under FORCE ROW LEVEL SECURITY, and a read with no context returns no
     * rows rather than every row — which for a queue is indistinguishable from an empty queue,
     * and an empty queue is exactly what staff will believe.
     */
    fun paymentsUnderFraudReview(): List<Payment> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            paymentRepo.findUnderFraudReview(c, tenantId, ATTENTION_LIMIT)
        }
    }

    /** BI-SNAP `'04'` refunds that settled no debt of ours ([applyGatewayRefund]): the third queue. */
    fun gatewayRefundsFlagged(): List<Payment> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            paymentRepo.findGatewayRefundsFlagged(c, tenantId, ATTENTION_LIMIT)
        }
    }

    /** `/admin/payments`' "Mark resolved": false when the payment is not a flagged one of this tenant. */
    fun resolveGatewayRefund(paymentId: UUID): Boolean {
        val tenantId = currentTenant.id()
        // The app_user id, as admission's admitted_by_app_user_id: a stable key, where an email can change.
        val by = currentActor.appUserId().map { it.toString() }.orElse(null)
        return db.inTenantTransaction(tenantId) { c -> paymentRepo.resolveGatewayRefundFlag(c, tenantId, paymentId, by) } > 0
    }

    fun salesByDay(from: LocalDate, to: LocalDate): List<PaymentRepository.SalesLine> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> paymentRepo.salesByDay(c, tenantId, from, to) }
    }

    /** Settled payments still owing money back — the other queue on the same page, and the
     * first caller `findRefundsDue` has ever had. */
    fun refundsDue(): List<Payment> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> paymentRepo.findRefundsDue(c, tenantId, ATTENTION_LIMIT) }
    }

    /** Thrown for an unverifiable notification. Mapped to 401 by the resource — not 400 — since
     * the request is well-formed and the problem is that it is not authenticated. */
    class InvalidSignatureException(message: String) : RuntimeException(message)

    companion object {
        private val LOG: Logger = Logger.getLogger(PaymentService::class.java)

        /** PP 71/2019's term for an automated confirmation with no human review — which is
         * exactly what a webhook settlement is. Recording it distinctly from
         * `tenant_staff` is what lets the audit trail answer "did a person decide this?" */
        private const val ACTOR_ENGINE = "agen_elektronik"
        private const val ACTOR_STAFF = "tenant_staff"

        /** `refund_reason` when a QRIS payment lands for an order already paid. */
        internal const val REFUND_PAID_TWICE = "QRIS paid after the order was already paid"

        /** `refund_reason` when a written-off QRIS is paid anyway and nothing else paid the order:
         * the seats were never confirmed by it, so the whole amount goes back. */
        internal const val REFUND_SEATS_GONE = "QRIS paid after its seats were cancelled or released"

        internal const val REFUND_PAID_AFTER_EXPIRY = "QRIS paid after it had expired; seats not confirmed"

        /**
         * Cap on each queue `/admin/payments` renders. Bounded for the same reason the
         * reconciliation batch is: an unbounded SELECT behind a page is an outage waiting for a tenant
         * having a bad week. If a queue ever reaches this, the number is itself the finding — these
         * lists are supposed to be near-empty — so say so rather than reaching for pagination.
         */
        private const val ATTENTION_LIMIT = 100

        /** The seats an order still has to pay for — held or already confirmed, never cancelled,
         * expired or completed. One rule for the QRIS amount and the cash amount alike. */
        private fun live(reservations: List<Reservation>): List<Reservation> =
            reservations.filter { "held" == it.status || "confirmed" == it.status }

        private fun liveTotal(reservations: List<Reservation>): BigDecimal =
            live(reservations).map { it.priceAmount }.fold(BigDecimal.ZERO, BigDecimal::add)
    }
}
