package com.protek.booking.payment

import java.math.BigDecimal
import java.util.Optional

/**
 * The Midtrans Core API surface this platform uses — two calls, deliberately.
 *
 * An interface rather than a concrete client because the tests need to drive settlement,
 * expiry, and failure deterministically, and because it names exactly what this integration
 * depends on: if a future change needs a third Midtrans call, that shows up here rather than
 * spreading HTTP details through the service layer.
 *
 * **Core API, not Snap**: this platform already has its own Qute/HTMX
 * checkout flow, and redirecting to Midtrans's hosted page would replace working UI with a
 * dependency. **Midtrans, not a direct acquirer integration**: it is an aggregator, which
 * is the point — it removes the need to integrate each tenant's bank's QRIS acquirer
 * separately, and several major Indonesian e-wallets do not expose a standalone dynamic-QRIS
 * API to ordinary merchants at all.
 */
interface MidtransGateway {

    /**
     * Creates a dynamic QRIS charge for the exact ticket total.
     *
     * @param orderId our `qris_partner_reference_no` — the only identifier Midtrans
     *                echoes back on notifications, so it is what the webhook handler resolves on
     * @param grossAmount the exact total. QRIS is amount-at-transaction-time with no
     *                    surcharge permitted (QRIS's no-surcharge rule), so this
     *                    is the ticket total and nothing added to it
     * @param expiryMinutes how long the QR stays scannable — kept at or below the hold
     *                      duration by the caller, since a QR outliving its hold could be paid
     *                      for a seat that has already been released
     */
    fun chargeQris(orderId: String, grossAmount: BigDecimal, expiryMinutes: Int): ChargeResult

    /**
     * Asks Midtrans what actually happened to a charge — the reconciliation path for
     * notifications that never arrived. Empty when Midtrans has no record of the
     * order at all, which is distinct from "it exists and is still pending".
     *
     * @param referenceNo Midtrans's own id for the charge (`payment.qris_reference_no`), null when
     *                    the charge never answered. BI-SNAP needs it to find a live QR; the legacy
     *                    API keys on the order id alone and ignores it
     */
    fun fetchStatus(orderId: String, referenceNo: String?): Optional<TransactionStatus>

    /**
     * Withdraws a charge that is still pending, so the customer's banking app can no longer pay
     * it. Used when staff take cash while the order's QR is still on screen: only
     * the gateway can stop that QR being paid. Throws on failure; the caller logs and leaves the
     * row to reconciliation, and a settlement that still lands becomes a refund obligation.
     * [referenceNo] is as for [fetchStatus]: BI-SNAP refuses a cancel without it. [reason] is
     * why the QR is withdrawn, the same English the caller logs, so the log line and the
     * gateway's dashboard say the same thing; a diagnostic, so it goes through no bundle.
     */
    fun cancel(orderId: String, referenceNo: String?, reason: String)

    /**
     * Sends [amount] of a paid charge back to the buyer's payment app: a debt this
     * platform recorded, returned automatically. Never throws; every outcome is a [RefundResult].
     *
     * [partnerRefundNo] is ours, derived from the payment and the attempt, so a resend after a
     * crash or a timeout is the same refund to Midtrans, not a second one. [resend] says this number
     * may already have reached Midtrans, and asks the gateway to look for it before sending again.
     * [amount] is always sent: without it BI-SNAP refunds the charge's whole remainder (seen in
     * the sandbox), which is more than the debt whenever other seats of the order are still sold.
     */
    fun refund(orderId: String, referenceNo: String, partnerRefundNo: String, amount: BigDecimal,
               reason: String, resend: Boolean): RefundResult

    /** What one refund attempt came to. Only [Refunded] is evidence that money moved. */
    sealed interface RefundResult {
        /** Midtrans returned [amount]; [refundNo] is its id for the refund, the `refundNo` a `'04'`'s
         * history will carry. */
        data class Refunded(val refundNo: String, val amount: BigDecimal) : RefundResult

        /** No answer, a timeout, or a code Midtrans calls retryable: try the same number again. */
        data class Transient(val detail: String) : RefundResult

        /** Midtrans said no to this attempt. Not retried: staff act on [code]. Null [code] is a
         * gateway that cannot refund at all. */
        data class Refused(val code: String?, val message: String?) : RefundResult
    }

    /** Whether the integration is configured at all. False means no credentials are set, and
     * the caller should fall back to the pay-at-counter flow rather than fail a booking. */
    fun isConfigured(): Boolean

    /**
     * Whether a [TransactionStatus] from [fetchStatus] carries its own signature that
     * the caller must verify before acting on it.
     *
     * **Defaults to true, so a gateway that has not thought about this fails closed.**
     *
     * The reconciliation sweep performs the same `hold → confirmed` transition as the
     * webhook, so it needs the same confidence in what it is acting on. For the legacy API that
     * confidence comes from a `signature_key` field in the response, verified with the very
     * same code as a webhook — hence true.
     *
     * BI-SNAP returns false, and that is a considered position rather than a shortcut. The two
     * cases have **different threat models**, and the legacy API only made them look identical
     * because it happened to offer the same field:
     *  - A **webhook** is inbound and unsolicited. Anyone on the internet can POST to it, so
     *    the signature is the *only* thing separating a real settlement from a forgery. It
     *    is load-bearing.
     *  - A **status query** is outbound. We choose the host, TLS authenticates that host, and
     *    the request is authenticated with our own access token and signature. An attacker able
     *    to forge that response has already broken TLS — at which point they could equally forge
     *    the charge response, and no field-level signature would help.
     *
     * So for SNAP this is not a hole where the legacy API had a control; it is the recognition that
     * the legacy control was defence in depth on a path that TLS already authenticated.
     */
    fun fetchedStatusCarriesItsOwnSignature(): Boolean = true

    /** Every field nullable: each is read out of a Midtrans response, which may omit any of them.
     * `@JvmRecord` so Java callers keep the record accessors (`charge.qrContent()`). */
    @JvmRecord
    data class ChargeResult(val transactionId: String?, val qrContent: String?, val transactionStatus: String?)

    /**
     * A status snapshot. `statusCode`, `grossAmount` and `signatureKey` are
     * carried because they are the three inputs (with the server key) to Midtrans's signature,
     * so a reconciliation response can be verified with the same code as a webhook — an
     * unverified reconciliation result would be a hole straight through the signature check.
     */
    @JvmRecord
    data class TransactionStatus(
        val orderId: String?, val transactionId: String?, val transactionStatus: String?,
        val fraudStatus: String?, val statusCode: String?, val grossAmount: String?,
        val signatureKey: String?,
    )
}
