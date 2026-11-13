package com.protek.booking.payment

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.TenantRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * Asks Midtrans about payments whose notification never arrived: a reconciliation
 * job for missed webhooks, the same scheduled-job pattern as the hold-expiry sweep.
 *
 * **Why this is not optional.** A webhook is a network call from someone else's
 * infrastructure to ours, and it can be lost: our instance was scaled to zero and cold-starting
 * past their timeout, a deploy was mid-rollout, their retries exhausted. When that happens the
 * customer has paid and the seats are still `held` — money taken for a booking that then
 * expires. Waiting for a customer to complain is not a reconciliation strategy; polling for the
 * cases we may have missed is, and it is cheap because the population is small and bounded.
 *
 * Same tenant-iteration shape as `ReservationLifecycleService` and `BillingService`, and
 * for the same reason: `payment` is under FORCE ROW LEVEL SECURITY, so a single
 * cross-tenant scan cannot see past one tenant's context.
 */
@ApplicationScoped
class PaymentReconciliationService {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var tenantRepo: TenantRepository

    @Inject
    lateinit var paymentRepo: PaymentRepository

    @Inject
    lateinit var midtrans: MidtransGateway

    @Inject
    lateinit var signatureVerifier: MidtransSignatureVerifier

    @Inject
    lateinit var paymentService: PaymentService

    @Inject
    lateinit var refundService: RefundService

    /**
     * Cap per tenant per run. Bounded on purpose: an unbounded scan against a backlog would
     * make one outbound HTTP call per pending payment inside a scheduled job, and a slow
     * Midtrans would hold the job open across its own next firing. A cap plus oldest-first
     * ordering means every run makes progress on the longest-outstanding payments.
     */
    @ConfigProperty(name = "midtrans.reconciliation.batch-size", defaultValue = "25")
    @JvmField
    final var batchSize: Int = 25

    /**
     * How far past its own `expires_at` a payment must be before a non-terminal answer from
     * Midtrans is read as "this can no longer settle". A margin rather than zero because the two
     * clocks are not the same one: `expires_at` is ours, the validity window is theirs, and
     * a QR paid in the last second of it must not be given up while their side is still settling.
     * An hour is far outside any such skew and far inside the days a stuck row would otherwise sit.
     */
    @ConfigProperty(name = "midtrans.reconciliation.give-up-grace", defaultValue = "PT1H")
    lateinit var giveUpGrace: Duration

    @Scheduled(cron = "{midtrans.reconciliation.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun reconcile() {
        if (!midtrans.isConfigured()) {
            return
        }
        val tenants = db.inTransaction(tenantRepo::findAll)
        var resolved = 0
        var checked = 0
        for (tenant in tenants) {
            val pending = db.inTenantTransaction(tenant.tenantId) { c ->
                paymentRepo.findPendingForReconciliation(c, tenant.tenantId, batchSize)
            }
            for (payment in pending) {
                checked++
                try {
                    if (reconcileOne(tenant, payment)) {
                        resolved++
                    }
                } catch (e: RuntimeException) {
                    // Per payment, not per batch: one unreachable transaction must not stop the
                    // rest — the whole point of this job is resolving the ones that CAN be
                    // resolved.
                    LOG.errorf(e, "Reconciliation failed for payment %s (order %s)",
                        payment.paymentId, payment.qrisPartnerReferenceNo)
                }
            }
        }
        if (checked > 0) {
            LOG.infof("Payment reconciliation: checked %d pending payment(s), resolved %d", checked, resolved)
        }
        // automatic refunds that started and have not finished (a timeout, no answer) go again.
        for (tenant in tenants) {
            try {
                refundService.retryStarted(tenant.tenantId, batchSize)
            } catch (e: RuntimeException) {
                LOG.errorf(e, "Refund retries failed for tenant %s", tenant.tenantId)
            }
        }
    }

    /** Public where the Java was package-private, because the Java tests call it and Kotlin has
     * no package-private. */
    fun reconcileOne(tenant: Tenant, payment: Payment): Boolean {
        val orderId = payment.qrisPartnerReferenceNo!!
        val status = midtrans.fetchStatus(orderId, payment.qrisReferenceNo)

        // Always stamp the attempt, resolved or not, so a payment being re-queried every run
        // without ever resolving is visible in the data rather than only in logs.
        db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.touchReconciled(c, tenant.tenantId, payment.paymentId)
        }

        if (status.isEmpty) {
            // A stored reference contradicts "no record": Midtrans only hands one out when it
            // answers the charge, so a QR was issued and may still be payable. Believing the
            // empty status here once killed every live QR within one five-minute sweep (the
            // BI-SNAP query answered "not found" for them), while the customer could still pay it.
            // So leave it pending and let hasLapsed end it, as it ends any lapsed payment. This
            // stays even with the query fixed: it is what stops a misread status killing a QR.
            if (payment.qrisReferenceNo != null) {
                if (hasLapsed(payment)) {
                    LOG.warnf("Midtrans reports no record of order %s (reference %s) and it lapsed " +
                        "past its validity — giving up", orderId, payment.qrisReferenceNo)
                    return giveUp(tenant, payment)
                }
                LOG.warnf("Midtrans reports no record of order %s, but the charge answered with " +
                    "reference %s, so a QR was issued; leaving it pending", orderId, payment.qrisReferenceNo)
                return false
            }
            // No reference: our charge call never actually reached them (see PaymentService: the
            // row is written before the call precisely so this case is discoverable), so no QR
            // was ever issued and there is nothing a customer could have paid.
            //
            // This is knowable on the *first* attempt and never changes: Midtrans cannot later
            // learn about an order it never received. Returning false here left the row pending
            // and re-queried it every five minutes forever, an unbounded cost that was seen in
            // practice. Give up on the evidence instead.
            LOG.warnf("Midtrans has no record of order %s; charge call never landed — giving up", orderId)
            return giveUp(tenant, payment)
        }
        val s = status.get()

        // The status response is signature-verified with the same code as a webhook. Skipping
        // verification here because "we asked them" would be a hole straight through the
        // signature check: this path performs the same hold → confirmed transition, so it needs
        // the same authentication.
        //
        // Gated on the gateway's own contract rather than on whether a signature happens to be
        // present: a null-check would silently skip verification the day the legacy gateway
        // returned null by accident, which is precisely the hole this guards. The flag defaults to
        // true, so only a gateway that has explicitly reasoned about it (see
        // MidtransGateway#fetchedStatusCarriesItsOwnSignature — BI-SNAP's responses are
        // authenticated by TLS on a request we ourselves signed) opts out.
        if (midtrans.fetchedStatusCarriesItsOwnSignature()) {
            val verified = signatureVerifier.verify(s.orderId, s.statusCode, s.grossAmount, s.signatureKey)
            if (!verified) {
                LOG.errorf("Reconciliation response for order %s failed signature verification; " +
                    "refusing to apply it", orderId)
                return false
            }
        }

        // Passes the tenant through rather than letting PaymentService re-derive it: this sweep
        // already knows whose pending payment it is, and payment is under FORCE ROW LEVEL
        // SECURITY, so the transition needs that context set to see the row at all.
        val applied = paymentService.applyVerifiedStatus(tenant.tenantId, orderId,
            s.transactionStatus, s.fraudStatus, s.transactionId, null, true)
        if (applied) {
            return true
        }

        // A captured payment under manual fraud review is the one non-terminal answer that must
        // never be given up on, and the check is deliberately here rather than inside hasLapsed.
        // Midtrans has the money; what is outstanding is a person's decision in their dashboard,
        // and that decision can land days after our QR validity has passed. Giving up would write
        // 'expired' on a captured charge, and the eventual 'accept' would then arrive at an
        // isTerminal() row and be ignored as a duplicate — customer charged, seats never confirmed.
        //
        // Read off Midtrans's answer, not off `payment`: the row was loaded before
        // applyVerifiedStatus stamped fraud_review_at on it, so the in-memory copy still says null
        // on the very first lapsed run — which is precisely the run that would have written it off.
        // The same rule as the ordering below, applied one step earlier: their answer decides.
        //
        // Yes, this keeps a row in the scanned population indefinitely, which is the unbounded cost
        // the give-up was added to end. That is the right trade here and only here: this is the one
        // population where a future run genuinely can get a different answer, and payment.fraud_review_at makes the row
        // visible at /admin/payments so it is waiting on a person rather than sitting unseen.
        if ("challenge" == s.fraudStatus) {
            LOG.warnf("Order %s is under manual fraud review at Midtrans; keeping it pending past " +
                "its validity rather than writing off a captured payment", orderId)
            return false
        }

        // Midtrans knows the order but reported nothing terminal — still awaiting payment. That is
        // the normal answer for a live charge, so it is only a give-up once our own validity
        // window has lapsed by the grace margin: past that the customer cannot pay this QR, so no
        // future run can get a different answer either.
        //
        // Ordered after applyVerifiedStatus on purpose. Their answer decides first, always; this
        // only fires when they had none to give. Reversing the two would let a lapsed-but-settled
        // payment be written off with the money already taken.
        if (hasLapsed(payment)) {
            LOG.warnf("Order %s is %s at Midtrans and lapsed past its validity — giving up",
                orderId, s.transactionStatus)
            return giveUp(tenant, payment)
        }
        return false
    }

    private fun hasLapsed(payment: Payment): Boolean {
        val expiresAt = payment.expiresAt ?: return false
        return Instant.now().isAfter(expiresAt.plus(giveUpGrace))
    }

    /**
     * Writes the give-up. Returns true so the run counts it as resolved: the row did leave the
     * pending population, which is what "resolved" means to the caller's tally — it does not
     * claim money moved. `'expired'` is the honest status for both give-up cases, because
     * both are exactly the situation where nothing was ever paid.
     */
    private fun giveUp(tenant: Tenant, payment: Payment): Boolean {
        db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.markGivenUp(c, tenant.tenantId, payment.paymentId)
        }
        return true
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(PaymentReconciliationService::class.java)
    }
}
