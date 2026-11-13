package com.protek.booking.payment

import com.protek.booking.payment.MidtransGateway.ChargeResult
import com.protek.booking.payment.MidtransGateway.TransactionStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A controllable [MidtransGateway] for tests, installed over the real one with
 * `QuarkusMock.installMockForType`.
 *
 * Extends [HttpMidtransGateway] rather than just implementing [MidtransGateway]
 * because `QuarkusMock.installMockForType` resolves the bean by its bean class and
 * requires the replacement to be assignable to it — the interface alone is not enough. Every
 * method the application calls is overridden, so no real HTTP is reachable from here.
 *
 * Hand-written rather than a mocking framework because the assertions here are about state
 * transitions across several calls — charge, then settle, then a duplicate settle — and a stub
 * that actually remembers what it charged reads as the scenario being tested. It also computes
 * real signatures with the same server key the application uses, so the signature verification
 * path is exercised for real instead of stubbed past: a test that bypassed verification would
 * prove nothing about the one control protecting this write path.
 *
 * The key is `stubServerKey`, not `serverKey`: the Java's private field shadowed the parent's
 * public `serverKey`, which Kotlin refuses, and the parent's stays null exactly as it did.
 */
class StubMidtransGateway(private val stubServerKey: String?) : HttpMidtransGateway() {

    private val statuses: MutableMap<String?, TransactionStatus> = HashMap()
    private var configured = true
    private var chargeFailure: RuntimeException? = null
    private var cancelFailure: RuntimeException? = null

    /** Every order id [cancel] was asked for, in order. */
    @JvmField
    final val cancelled: MutableList<String> = CopyOnWriteArrayList()

    /** The Midtrans reference each [cancel] carried, beside [cancelled]: BI-SNAP refuses a cancel
     * without it, so the callers must pass it through. */
    @JvmField
    final val cancelledReferences: MutableList<String?> = CopyOnWriteArrayList()

    /** The reason each [cancel] gave, beside [cancelled]: the caller's own words, so each path
     * (cash, a cancelled showing) can be told apart at the gateway. */
    @JvmField
    final val cancelledReasons: MutableList<String> = CopyOnWriteArrayList()

    override fun isConfigured(): Boolean = configured

    fun configured(value: Boolean): StubMidtransGateway {
        this.configured = value
        return this
    }

    /** Makes the next charge call blow up, for the "row is written before the call" property. */
    fun failCharges(failure: RuntimeException?): StubMidtransGateway {
        this.chargeFailure = failure
        return this
    }

    override fun chargeQris(orderId: String, grossAmount: BigDecimal, expiryMinutes: Int): ChargeResult {
        chargeFailure?.let { throw it }
        val transactionId = "midtrans-" + UUID.randomUUID()
        // Midtrans renders IDR amounts with two decimals in its own responses, and the signature
        // is computed over that exact text — so the stub reproduces the formatting rather than
        // the numeric value, which is the part real integrations get wrong.
        statuses[orderId] = status(orderId, transactionId, "pending", "accept", "201",
            grossAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
        return ChargeResult(transactionId, "https://stub.example/qr/" + orderId, "pending")
    }

    override fun fetchStatus(orderId: String, referenceNo: String?): Optional<TransactionStatus> =
        Optional.ofNullable(statuses[orderId])

    /** Makes every cancel call blow up — Midtrans unreachable at the moment cash was taken. */
    fun failCancels(failure: RuntimeException?): StubMidtransGateway {
        this.cancelFailure = failure
        return this
    }

    override fun cancel(orderId: String, referenceNo: String?, reason: String) {
        cancelled.add(orderId)
        cancelledReferences.add(referenceNo)
        cancelledReasons.add(reason)
        cancelFailure?.let { throw it }
        transition(orderId, "cancel", "accept", "200")
    }

    /** One [refund] call as the service made it. */
    data class RefundCall(val orderId: String, val referenceNo: String, val partnerRefundNo: String,
                          val amount: BigDecimal, val resend: Boolean)

    final val refunds: MutableList<RefundCall> = CopyOnWriteArrayList()

    /** Answers for the next refund calls, in order; once empty, [refundDefault]. A function, so a
     * script can answer from the call (its amount). */
    final val refundScript: java.util.concurrent.ConcurrentLinkedQueue<(RefundCall) -> MidtransGateway.RefundResult> =
        java.util.concurrent.ConcurrentLinkedQueue()

    /** No answer, by default, so a test that records a debt still finds it recorded: the automatic
     * refund runs after every cancel of a paid QRIS seat. [refundsSucceed] for one that wants it. */
    @Volatile
    var refundDefault: (RefundCall) -> MidtransGateway.RefundResult = { MidtransGateway.RefundResult.Transient("stub: no answer") }

    /** Every refund from now on is returned in full, under a fresh refundNo. */
    fun refundsSucceed(): StubMidtransGateway {
        refundDefault = { MidtransGateway.RefundResult.Refunded("RN-" + UUID.randomUUID(), it.amount) }
        return this
    }

    override fun refund(orderId: String, referenceNo: String, partnerRefundNo: String, amount: BigDecimal,
                        reason: String, resend: Boolean): MidtransGateway.RefundResult {
        val call = RefundCall(orderId, referenceNo, partnerRefundNo, amount, resend)
        refunds.add(call)
        return (refundScript.poll() ?: refundDefault).invoke(call)
    }

    /** Simulates the customer paying, as Midtrans would then report it. */
    fun settle(orderId: String?): TransactionStatus =
        transition(orderId, "settlement", "accept", "200")

    fun expire(orderId: String?): TransactionStatus =
        transition(orderId, "expire", "accept", "407")

    fun challenge(orderId: String?): TransactionStatus =
        transition(orderId, "capture", "challenge", "201")

    /** A manual fraud review coming back refused — the other way a [challenge] ends, and
     * the one that has to leave the payment terminal so it drops off the staff queue. */
    fun deny(orderId: String?): TransactionStatus =
        transition(orderId, "deny", "deny", "202")

    /** Midtrans forgetting an order entirely — the "our charge call never landed" case. */
    fun forget(orderId: String?) {
        statuses.remove(orderId)
    }

    private fun transition(orderId: String?, transactionStatus: String,
                           fraudStatus: String, statusCode: String): TransactionStatus {
        val existing = statuses[orderId]
            ?: throw IllegalStateException("no stub charge for order " + orderId)
        val updated = status(orderId, existing.transactionId, transactionStatus,
            fraudStatus, statusCode, existing.grossAmount)
        statuses[orderId] = updated
        return updated
    }

    private fun status(orderId: String?, transactionId: String?, transactionStatus: String,
                       fraudStatus: String, statusCode: String, grossAmount: String?): TransactionStatus =
        TransactionStatus(orderId, transactionId, transactionStatus, fraudStatus,
            statusCode, grossAmount, signature(orderId, statusCode, grossAmount))

    /** The real thing: SHA512(order_id + status_code + gross_amount + ServerKey). */
    fun signature(orderId: String?, statusCode: String?, grossAmount: String?): String {
        try {
            val digest = MessageDigest.getInstance("SHA-512")
            val hash = digest.digest((orderId + statusCode + grossAmount + stubServerKey)
                .toByteArray(StandardCharsets.UTF_8))
            return HexFormat.of().formatHex(hash)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException(e)
        }
    }
}
