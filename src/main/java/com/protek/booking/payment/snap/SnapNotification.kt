package com.protek.booking.payment.snap

import io.quarkus.runtime.annotations.RegisterForReflection
import java.util.Optional

/**
 * The fields this platform reads out of a BI-SNAP payment notification, plus the two headers that
 * authenticate it.
 *
 * A narrow projection with the complete `rawBody` alongside, for the same two reasons
 * `MidtransNotification` gives: Midtrans adds fields over time and binding all of them would
 * break deserialisation on one this code never reads, and `payment.raw_webhook_payload`
 * should hold Midtrans's own text rather than our re-rendering of it.
 *
 * `rawBody` is load-bearing here in a way it is not for the legacy API: the SNAP signature
 * is computed over the minified body, so verification is impossible without the original text.
 */
@RegisterForReflection
@JvmRecord
data class SnapNotification(
    val originalPartnerReferenceNo: String?,
    val originalReferenceNo: String?,
    val latestTransactionStatus: String?,
    /** `additionalInfo.refundHistory[]`, read only for `'04'`; null when absent or unreadable
     * ([GatewayRefund.history]). */
    val refundHistory: List<GatewayRefund>?,
    val signature: String?,
    val timestamp: String?,
    val rawBody: String?,
) {

    /**
     * Translates SNAP's numeric status into the vocabulary `PaymentService` already speaks,
     * so one transition implementation serves both APIs and they cannot drift apart.
     *
     * The notification status set is **not** the same as the query API's — notably it has
     * `08` Expiry and `09` Rejected, which the query set does not. Taken from
     * Midtrans's Payment Notification API page rather than assumed from the other endpoint.
     *
     * Empty means "recognised but not something this platform transitions on", which is
     * different from unrecognised and is handled differently by the caller.
     */
    fun asLegacyTransactionStatus(): Optional<String> = toLegacyTransactionStatus(latestTransactionStatus)

    /** True for '04' (refunded), which has no legacy status and takes its own path
     * (PaymentService.applyGatewayRefund), told apart from a status Midtrans has invented since. */
    fun isKnownButUnhandled(): Boolean = "04" == latestTransactionStatus

    companion object {
        /**
         * Shared with the status-query path, which reports the same states through a different call and
         * must not be allowed to disagree about what any of them mean.
         *
         * The two SNAP endpoints do not publish identical sets — the query adds `01` Initiated
         * and `02` Paying, the notification adds `08` Expiry and `09` Rejected — so
         * this handles the union. Accepting a code on the endpoint that does not send it costs nothing;
         * failing to handle one it does send loses a settlement.
         */
        @JvmStatic
        fun toLegacyTransactionStatus(snapStatus: String?): Optional<String> = when (snapStatus ?: "") {
            "00" -> Optional.of("settlement")
            // Initiated and Paying are both "the customer has not finished", which is what the
            // legacy vocabulary calls pending and what PaymentService treats as a no-op.
            "01", "02", "03" -> Optional.of("pending")
            "05" -> Optional.of("cancel")
            "06" -> Optional.of("failure")
            "08" -> Optional.of("expire")
            "09" -> Optional.of("deny")
            // 04 Refunded has no legacy equivalent: mapping it onto 'failure' would mark a refunded
            // booking as a failed payment, a different and wrong story for the audit trail.
            // PaymentService.applyGatewayRefund handles it before this mapping is consulted.
            else -> Optional.empty()
        }
    }
}
