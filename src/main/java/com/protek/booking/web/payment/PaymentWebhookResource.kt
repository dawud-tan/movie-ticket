package com.protek.booking.web.payment

import com.protek.booking.payment.MidtransJson
import com.protek.booking.payment.MidtransNotification
import com.protek.booking.payment.PaymentService
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Midtrans's HTTP notification endpoint.
 *
 * **Unauthenticated by necessity, authenticated by signature.** Midtrans calls this with
 * no session and no bearer token, so there is no identity to check — and it moves reservations
 * from `held` to `confirmed`, which is exactly what someone would forge. This is
 * signature verification as a write-path authentication requirement, and that
 * is precisely the framing: the signature is not defence-in-depth on top of authentication, it
 * *is* the authentication for this write path.
 *
 * Deliberately **not** annotated `@RolesAllowed` or `@Authenticated`, and
 * deliberately not IP-allowlisted as the primary control: Midtrans's source addresses are not
 * contractually stable, and an allowlist that silently stops matching would drop real
 * settlements — worse than the problem it guards, given the signature already makes forgery
 * infeasible. An allowlist at the load balancer is a reasonable additional layer, not a
 * substitute.
 */
@Path("/payments/midtrans")
@RunOnVirtualThread
class PaymentWebhookResource {

    @Inject
    lateinit var paymentService: PaymentService

    /**
     * Takes the raw body as a String rather than a bound DTO, because the signature is computed
     * over Midtrans's own field values and the raw text is what gets stored in
     * `raw_webhook_payload`. Binding first and re-serialising to store would persist our
     * rendering of the notification rather than theirs — a weaker evidentiary record.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun notify(rawBody: String?): Response {
        val notification = try {
            parse(rawBody)
        } catch (e: IllegalArgumentException) {
            LOG.warnf("Unparseable Midtrans notification body")
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"malformed json\"}").build()
        }

        return try {
            val applied = paymentService.applyNotification(notification)
            // 200 for both "applied" and "already applied": Midtrans retries until it gets a
            // 2xx, so returning an error for a duplicate would guarantee an infinite retry loop
            // over a notification that has already been fully handled.
            Response.ok("{\"applied\":$applied}").build()
        } catch (e: PaymentService.InvalidSignatureException) {
            // 401, not 400: the body is well-formed, it just is not authenticated. No detail
            // about which component failed — a public endpoint explaining that is an oracle for
            // constructing a valid signature.
            LOG.warnf("Rejected Midtrans notification for order %s: invalid signature",
                notification.orderId)
            Response.status(Response.Status.UNAUTHORIZED)
                .entity("{\"error\":\"invalid signature\"}").build()
        } catch (e: NotFoundException) {
            // A verified signature for an order we have no record of. Not retryable, so 404
            // (which Midtrans stops retrying) rather than 500 — but logged at warn, because a
            // correctly-signed notification for an unknown order means our record is missing,
            // not that the caller is wrong.
            LOG.warnf("Verified notification for unknown order %s", notification.orderId)
            Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"unknown order\"}").build()
        }
    }

    /** Fields are read as Jackson's `asText()` read them until 2026-10-08 — the values the
     * signature is checked against, so their text matters (see [MidtransJson]). Throws
     * `IllegalArgumentException` on a body that is not JSON. */
    private fun parse(rawBody: String?): MidtransNotification {
        val node = MidtransJson.parseBody(rawBody ?: "{}")
        return MidtransNotification(
            MidtransJson.text(node, "order_id"),
            MidtransJson.text(node, "transaction_id"),
            MidtransJson.text(node, "transaction_status"),
            MidtransJson.text(node, "fraud_status"),
            MidtransJson.text(node, "status_code"),
            MidtransJson.text(node, "gross_amount"),
            MidtransJson.text(node, "signature_key"),
            MidtransJson.text(node, "payment_type"),
            rawBody)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(PaymentWebhookResource::class.java)
    }
}
