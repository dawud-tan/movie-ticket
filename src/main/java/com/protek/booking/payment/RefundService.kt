package com.protek.booking.payment

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.payment.MidtransGateway.RefundResult
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.service.SeatAvailabilityNotifier
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Sends a recorded debt back through the gateway.
 * Every debt on a QRIS payment Midtrans can find (`qris_reference_no` set) — paid twice, seats
 * gone, paid after expiry, a cancelled showing, a paid seat staff cancelled — once its transaction
 * has committed. Never cash: the counter hands cash back.
 *
 * **After commit, off the request thread**, like the push: a refund that fails or hangs never undoes
 * or delays the cancel that recorded the debt. The debt is the record; this only ever lowers it, and
 * only on evidence that money moved: the refund call's 2007800 with its `refundAmount`, or a
 * `'04'`'s history ([PaymentService.applyGatewayRefund]). Both count a refund by Midtrans's
 * `refundNo` in [RefundLedger], under the payment's `FOR UPDATE`, so one refund is counted once.
 *
 * **Exactly once at Midtrans.** Each attempt's `partnerRefundNo` is the payment id and a sequence
 * number, claimed and committed *before* the call. A crash or timeout after the call leaves the
 * claim behind, and the next try resends the same number: Midtrans answers 4097801, or the
 * gateway finds it in the charge's history first ([MidtransGateway.refund]'s `resend`).
 *
 * **Retries** are [PaymentReconciliationService]'s 5-minute sweep, for a payment whose automatic
 * refund has started and not finished, up to [maxAttempts] sends of one number. A refusal is not
 * retried: it is flagged on `/admin/payments` in a sentence staff can act on, beside the debt it
 * leaves, and "Mark resolved" clears it as it clears a `'04'` flag. All of it is keys in
 * `raw_webhook_payload` (`_autoRefund`, `_refundsApplied`), the same no-migration pattern as `_gatewayRefunds`.
 *
 * shortcut: a debt whose after-commit attempt never started (the JVM died between the commit and
 * the executor) is not swept, because the sweep takes only payments already in `_autoRefund`, so
 * that debts staff had already handed back in cash before automatic refunds existed are not refunded a second time. It
 * stays on `/admin/payments` as every debt did before; revisit if one is ever seen.
 */
@ApplicationScoped
class RefundService {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var paymentRepo: PaymentRepository

    @Inject
    lateinit var midtrans: MidtransGateway

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @Inject
    lateinit var executor: ManagedExecutor

    @Inject
    lateinit var buyerMail: com.protek.booking.service.BuyerMail

    @Inject
    lateinit var orderPush: com.protek.booking.service.OrderPush

    /** Sends of one `partnerRefundNo` before it is given to staff: an hour of the 5-minute sweep. */
    @ConfigProperty(name = "booking.refund.max-attempts", defaultValue = "12")
    @JvmField
    final var maxAttempts: Int = 12

    /** Off the request thread; [paymentIds] are payments that may have just been owed a debt. */
    fun refundAfterCommit(tenantId: UUID, paymentIds: Collection<UUID>) {
        if (paymentIds.isEmpty()) return
        executor.execute {
            for (id in paymentIds) {
                try {
                    attempt(tenantId, id)
                } catch (e: RuntimeException) {
                    LOG.errorf(e, "Automatic refund of payment %s failed; the debt stays on /admin/payments", id)
                }
            }
        }
    }

    /** The sweep's half: payments whose automatic refund started and has not finished. */
    fun retryStarted(tenantId: UUID, limit: Int): Int {
        val ids = db.inTenantTransaction(tenantId) { c -> paymentRepo.findAutoRefundsOpen(c, tenantId, limit) }
        var refunded = 0
        for (id in ids) {
            try {
                if (attempt(tenantId, id) is RefundResult.Refunded) refunded++
            } catch (e: RuntimeException) {
                LOG.errorf(e, "Automatic refund retry of payment %s failed", id)
            }
        }
        return refunded
    }

    /**
     * One attempt for one payment, synchronously: claim, call, record. Null when there was nothing
     * to send (no debt, cash, a charge Midtrans cannot find, or a refusal already standing).
     */
    fun attempt(tenantId: UUID, paymentId: UUID): RefundResult? {
        val claim = db.inTenantTransaction(tenantId) { c -> claim(c, tenantId, paymentId) } ?: return null
        val result = midtrans.refund(claim.orderId, claim.referenceNo, claim.partnerRefundNo, claim.amount,
            claim.reason, claim.resend)
        val outcome = db.inTenantTransaction(tenantId) { c -> record(c, tenantId, paymentId, claim, result) }
        LOG.infof("Automatic refund %s of order %s (%s IDR, try %d): %s", claim.partnerRefundNo, claim.orderId,
            claim.amount, claim.tries, result)
        notifier.notifyOrder(tenantId, outcome.groupId)
        when (outcome.mail) {
            Mail.SENT -> {
                buyerMail.refundSent(tenantId, outcome.groupId, outcome.counted!!, Instant.now())
                orderPush.refundSent(tenantId, outcome.groupId, outcome.counted)
            }
            Mail.REFUSED -> {
                buyerMail.refundRefused(tenantId, outcome.groupId, claim.amount)
                orderPush.refundRefused(tenantId, outcome.groupId, claim.amount)
            }
            null -> {}
        }
        return result
    }

    private class Claim(val orderId: String, val referenceNo: String, val partnerRefundNo: String,
                        val amount: BigDecimal, val reason: String, val resend: Boolean, val tries: Int,
                        val state: RefundLedger.AutoRefund)

    private enum class Mail { SENT, REFUSED }

    private class Outcome(val groupId: UUID, val mail: Mail?, val counted: BigDecimal?)

    private fun claim(c: java.sql.Connection, tenantId: UUID, paymentId: UUID): Claim? {
        // The payment's lock first, before any write, as cancelShowing and the webhook take it.
        val p = paymentRepo.findByIdForUpdate(c, tenantId, paymentId).orElse(null) ?: return null
        val owed = p.refundDueAmount ?: BigDecimal.ZERO
        val referenceNo = p.qrisReferenceNo
        if (Payment.STATUS_PAID != p.status || Payment.CHANNEL_QRIS != p.paymentChannel ||
            referenceNo == null || owed.signum() <= 0) return null
        val state = RefundLedger.autoRefund(p.rawWebhookPayload)
        if (state.refused != null) return null
        // A number already sent keeps its number; its amount never grows past what is owed now (a
        // dashboard refund may have lowered the debt meanwhile), and Midtrans dedupes the number.
        val amount = state.amount?.toBigDecimalOrNull()?.min(owed) ?: owed
        val next = state.copy(tries = state.tries + 1, amount = amount.toPlainString())
        paymentRepo.updateRefundState(c, tenantId, paymentId, owed, "[]", RefundLedger.encode(next), null)
        return Claim(p.qrisPartnerReferenceNo!!, referenceNo, partnerRefundNo(paymentId, state.seq), amount,
            (p.refundReason ?: "refund owed").take(REASON_MAX), resend = state.tries > 0, tries = next.tries,
            state = next)
    }

    private fun record(c: java.sql.Connection, tenantId: UUID, paymentId: UUID, claim: Claim,
                       result: RefundResult): Outcome {
        val p = paymentRepo.findByIdForUpdate(c, tenantId, paymentId).orElseThrow()
        val owed = p.refundDueAmount ?: BigDecimal.ZERO
        val state = RefundLedger.autoRefund(p.rawWebhookPayload)
        return when (result) {
            is RefundResult.Refunded -> {
                // A second answer for a number already recorded (a resend that raced the first call):
                // the first answer counted it and told the buyer, so this one writes and tells nothing.
                if (state.seq != claim.state.seq) return Outcome(p.reservationGroupId!!, null, result.amount)
                val known = RefundLedger.applied(p.rawWebhookPayload).any { it.refundNo == result.refundNo }
                // The '04' may have beaten this answer here: then it already lowered the debt.
                val remaining = if (known) owed else (owed - result.amount).max(BigDecimal.ZERO)
                val entries = if (known) "[]" else RefundLedger.entries(listOf(result.refundNo to result.amount), Instant.now())
                val done = RefundLedger.AutoRefund(seq = state.seq + 1)
                paymentRepo.updateRefundState(c, tenantId, paymentId, remaining, entries, RefundLedger.encode(done), null)
                // Told even when the '04' counted it first: that path tells nobody, and on the server
                // (2026-11-06) a 3 ms lead cost the buyer both the mail and the push.
                Outcome(p.reservationGroupId!!, Mail.SENT, result.amount)
            }
            is RefundResult.Transient -> {
                if (state.tries < maxAttempts) {
                    Outcome(p.reservationGroupId!!, null, null)
                } else {
                    refuse(c, tenantId, p, state, "no-answer", "Midtrans did not answer $maxAttempts tries " +
                        "(${result.detail}): check the dashboard, then refund there or in cash")
                }
            }
            // The legacy rollback gateway cannot refund at all: the debt stays listed, as every debt
            // was before automatic refunds, with no flag and no mail for a refusal that is no news.
            is RefundResult.Refused -> if (result.code == null) {
                paymentRepo.updateRefundState(c, tenantId, paymentId, owed, "[]",
                    RefundLedger.encode(state.copy(refused = "unsupported")), null)
                Outcome(p.reservationGroupId!!, null, null)
            } else refuse(c, tenantId, p, state, result.code, staffSentence(result))
        }
    }

    private fun refuse(c: java.sql.Connection, tenantId: UUID, p: Payment, state: RefundLedger.AutoRefund,
                       code: String, sentence: String): Outcome {
        paymentRepo.updateRefundState(c, tenantId, p.paymentId!!, p.refundDueAmount ?: BigDecimal.ZERO, "[]",
            RefundLedger.encode(state.copy(refused = code)), sentence)
        LOG.warnf("Automatic refund of order %s stopped, flagged at /admin/payments: %s", p.qrisPartnerReferenceNo, sentence)
        return Outcome(p.reservationGroupId!!, Mail.REFUSED, null)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(RefundService::class.java)

        /** No published limit; the reason is a diagnostic, and a short one is enough. */
        private const val REASON_MAX = 100

        /** 32 hex digits, `r`, the sequence: within the 36 of X-EXTERNAL-ID, which carries it too
         * (34 characters passed the sandbox). */
        internal fun partnerRefundNo(paymentId: UUID, seq: Int): String =
            paymentId.toString().replace("-", "") + "r" + seq

        /** English, as every flag on that page is: a diagnostic composed here, not copy (House style). */
        internal fun staffSentence(refused: RefundResult.Refused): String = when (refused.code) {
            "4037814" -> "Midtrans balance already paid out: refund from the dashboard or in cash"
            "4057801" -> "Past the refund window for this payment app: refund in cash"
            else -> "Midtrans refused the automatic refund (${refused.code} ${refused.message}). ShopeePay " +
                "refunds only in full and only 06:00–23:55 WIB: refund from the dashboard or in cash"
        }
    }
}
