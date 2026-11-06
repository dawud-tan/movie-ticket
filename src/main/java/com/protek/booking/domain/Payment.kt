package com.protek.booking.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A QRIS charge (or a cash settlement) against one [ReservationGroup].
 *
 * References `reservation_group_id` always, never `reservation_id`, which avoids a
 * polymorphic FK across verticals. It also
 * matches how payment actually works: a customer pays once for a block of seats, not once
 * per seat, so the group is the thing with a price.
 */
@JvmRecord
data class Payment(
    val paymentId: UUID?,
    val tenantId: UUID?,
    val reservationGroupId: UUID?,
    val amount: BigDecimal?,
    val currency: String?,
    val qrisMerchantId: String?,
    /** BI-SNAP's `terminalId`. Null until the charge request sends one — it does not
     * today — and null on every legacy-API row. Once held the ONLINE/COUNTER channel, before
     * payment.trigger_point split the two; that meaning is now [triggerPoint]. */
    val qrisTerminalId: String?,
    /** Our order id, sent to Midtrans and echoed back on every notification. UNIQUE, which
     * is what lets the webhook handler resolve exactly one row per notification. */
    val qrisPartnerReferenceNo: String?,
    /** The QR payload string to render for scanning, or the URL Midtrans hosts it at. */
    val qrisContent: String?,
    /** Midtrans's own `transaction_id`, known only after the charge call returns. */
    val qrisReferenceNo: String?,
    val status: String?,
    val expiresAt: Instant?,
    val paidAt: Instant?,
    val webhookReceivedAt: Instant?,
    val webhookSignatureVerified: Boolean,
    val rawWebhookPayload: String?,
    val paymentChannel: String?,
    /** Which trigger point created the charge (one integration, two trigger
     * points). Its own column now — it lived in `qris_terminal_id` while the
     * legacy aggregator flow had no real terminal to record there. */
    val triggerPoint: String?,
    val settledByActor: String?,
    val lastReconciledAt: Instant?,
    /** When Midtrans first reported `capture` with `fraud_status=challenge` for
     * this order — money captured, a human decision outstanding. Null on every other
     * payment, and on every BI-SNAP one: QRIS under SNAP has no fraud-review state at all.
     * First write wins, so this is when the review started rather than when we last asked. */
    val fraudReviewAt: Instant?,
    /** How much of [amount] is owed back after a cancellation, accumulated one
     * cancelled seat at a time. Deliberately not expressed as
     * `status = 'refunded'`: this records a debt, and nothing in this codebase moves
     * money back yet. Zero, never null. */
    val refundDueAmount: BigDecimal?,
    val refundDueAt: Instant?,
    val refundReason: String?,
    val createdAt: Instant?,
) {

    /** True once this payment has reached a state nothing further will move it out of, so the
     * webhook handler and the reconciliation job can both stop early rather than re-applying
     * a transition. */
    fun isTerminal(): Boolean =
        STATUS_PAID == status || STATUS_EXPIRED == status ||
                STATUS_FAILED == status || STATUS_REFUNDED == status

    /**
     * Whether this payment owes money back that has not been returned.
     *
     * Independent of [isTerminal] on purpose: a paid payment with an outstanding
     * refund is still terminal as far as the webhook handler and reconciliation scan are
     * concerned — the charge is settled and must not be re-applied — while remaining an open
     * obligation for a human to act on. Conflating the two is what a `'refund_due'`
     * status would have done.
     */
    fun hasOutstandingRefund(): Boolean = refundDueAmount != null && refundDueAmount.signum() > 0

    /** What the gateway has given back of this payment: every refund counted in
     * `raw_webhook_payload._refundsApplied`, ours or one staff made on the dashboard. Null when none. */
    fun returnedAmount(): BigDecimal? = com.protek.booking.payment.RefundLedger.applied(rawWebhookPayload)
        .takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { sum, r -> sum + r.amount }

    /** When the latest of those was counted here. */
    fun returnedAt(): Instant? = com.protek.booking.payment.RefundLedger.applied(rawWebhookPayload)
        .mapNotNull { it.at }.maxOrNull()

    fun hasReturned(): Boolean = returnedAmount() != null

    /**
     * Whether Midtrans has captured this payment and is waiting on a manual fraud decision.
     *
     * Narrowed to `pending` deliberately: [fraudReviewAt] is never cleared, so
     * a payment that was challenged and then accepted still carries the stamp as history. What
     * makes a row an *open* obligation is that the decision has not yet moved it anywhere.
     *
     * This is the one non-terminal state the reconciliation scan must not give up on. Everything
     * else it writes off has stopped being payable; this one has already been paid, and the answer
     * genuinely changes when a person acts on it.
     */
    fun isUnderFraudReview(): Boolean = fraudReviewAt != null && STATUS_PENDING == status

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PAID = "paid"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_FAILED = "failed"
        const val STATUS_REFUNDED = "refunded"

        const val CHANNEL_QRIS = "qris"
        const val CHANNEL_CASH = "cash"

        /** Lowercase to match [CHANNEL_QRIS] and `settled_by_actor`. The uppercase
         * ONLINE/COUNTER these replace were shaped to look like something Midtrans might recognise
         * back when they lived in `qris_terminal_id`; they never were. */
        const val TRIGGER_ONLINE = "online"
        const val TRIGGER_COUNTER = "counter"
    }
}
