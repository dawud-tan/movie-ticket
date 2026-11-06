package com.protek.booking.web.payment

import com.protek.booking.payment.MidtransJson
import com.protek.booking.payment.PaymentService
import com.protek.booking.payment.snap.GatewayRefund
import com.protek.booking.payment.snap.SnapNotification
import com.protek.booking.payment.snap.SnapNotificationVerifier
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Midtrans's BI-SNAP notification endpoint.
 *
 * **The path is not ours to choose.** The merchant registers a bare domain and Midtrans
 * appends a relative path per payment type, so QRIS notifications arrive at exactly
 * `/v1.0/qr/qr-mpm-notify`. The same string is inside the signature, which is why it is
 * declared once on [SnapNotificationVerifier.NOTIFY_PATH] and shared rather than written
 * twice — a mismatch between the mounted path and the signed path fails verification in a way that
 * looks like an attack.
 *
 * Measured 2026-08-15: Midtrans really does deliver here on **port 8443**, despite listing
 * "unusual destination port" as a delivery blocker, and it does so from addresses that are
 * **not** the ones its documentation publishes. Both are why this endpoint is authenticated by
 * signature alone and not by source address.
 *
 * Coexists with `PaymentWebhookResource` on purpose: the legacy endpoint must stay mounted
 * through the cutover drain, because a payment created on the legacy API keeps notifying in the
 * legacy format.
 */
@Path(SnapNotificationVerifier.NOTIFY_PATH)
@RunOnVirtualThread
class SnapNotificationResource {

    @Inject
    lateinit var paymentService: PaymentService

    /**
     * Takes the body as a raw `String` — required, not merely preferable. The SNAP signature
     * is computed over the minified original text, so binding to a DTO first and re-serialising
     * would produce a different document and make verification impossible. The legacy endpoint
     * takes a raw String too, to store Midtrans's own text as evidence; here the two reasons coincide.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun notify(rawBody: String?,
               @HeaderParam("X-SIGNATURE") signature: String?,
               @HeaderParam("X-TIMESTAMP") timestamp: String?): Response {
        val notification = try {
            parse(rawBody, signature, timestamp)
        } catch (e: IllegalArgumentException) {
            LOG.warnf("Unparseable BI-SNAP notification body")
            return snap(BAD_REQUEST, "Invalid Field Format")
        }

        return try {
            val applied = paymentService.applySnapNotification(notification)
            // Success for both "applied" and "already applied". Midtrans retries a failed delivery
            // — measured at roughly 100 seconds — so answering anything else for a duplicate would
            // guarantee an unending redelivery loop over a notification already fully handled.
            LOG.debugf("BI-SNAP notification for order %s applied=%s",
                notification.originalPartnerReferenceNo, applied)
            snap(OK, "Request has been processed successfully")
        } catch (e: PaymentService.InvalidSignatureException) {
            LOG.warnf("Rejected BI-SNAP notification for order %s: invalid signature",
                notification.originalPartnerReferenceNo)
            snap(UNAUTHORIZED, "Unauthorized")
        } catch (e: NotFoundException) {
            // A correctly-signed notification for an order we have no record of. Not retryable, and
            // logged at warn rather than debug because it means OUR record is missing, not that the
            // caller is wrong.
            LOG.warnf("Verified BI-SNAP notification for unknown order %s",
                notification.originalPartnerReferenceNo)
            snap(NOT_FOUND, "Transaction Not Found")
        }
    }

    /** HTTP status is taken from the response code's own first three digits, so the two can never
     * disagree — the code embeds the status by construction. */
    private fun snap(responseCode: String, message: String): Response {
        val httpStatus = responseCode.substring(0, 3).toInt()
        return Response.status(httpStatus)
            .entity("{\"responseCode\":\"$responseCode\",\"responseMessage\":\"$message\"}")
            .build()
    }

    /** Read as Jackson's `asText()` read it (see [MidtransJson]); throws
     * `IllegalArgumentException` on a body that is not JSON. */
    private fun parse(rawBody: String?, signature: String?, timestamp: String?): SnapNotification {
        val node = MidtransJson.parseBody(rawBody ?: "{}")
        return SnapNotification(
            MidtransJson.text(node, "originalPartnerReferenceNo"),
            MidtransJson.text(node, "originalReferenceNo"),
            MidtransJson.text(node, "latestTransactionStatus"),
            GatewayRefund.history(node),
            signature,
            timestamp,
            rawBody)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SnapNotificationResource::class.java)

        /**
         * SNAP response codes are seven digits: HTTP status, then a two-digit service code, then a
         * two-digit case code. Notification is service **52** — not the charge path's 47, which is
         * the mistake available to anyone copying from the generate call.
         *
         * Response code 2005200 is quoted from Midtrans's Payment Notification API page, and it is the one
         * that matters: it is what stops redelivery. The failure codes below are **constructed**
         * from the documented structure rather than quoted, because that page lists only the success
         * case. If Midtrans ever publishes the full set, reconcile these against it.
         */
        private const val OK = "2005200"
        private const val UNAUTHORIZED = "4015200"
        private const val NOT_FOUND = "4045201"
        private const val BAD_REQUEST = "4005200"
    }
}
