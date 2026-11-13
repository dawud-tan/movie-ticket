package com.protek.booking.payment.snap

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Authenticates an inbound BI-SNAP notification — the SNAP counterpart of
 * `MidtransSignatureVerifier`, and the same load-bearing role.
 *
 * The endpoint it guards is necessarily public: Midtrans calls it with no session and no bearer
 * token, from addresses this platform does not control and which — measured 2026-08-15 — are not
 * even the ones their documentation lists. It performs the `held → confirmed` transition,
 * which is precisely what someone would forge. So the signature is not defence in depth on top of
 * authentication; it **is** the authentication.
 *
 * **Different mechanism from the legacy verifier, not a variant of it.** Legacy is a
 * symmetric digest of four body fields against a shared server key. This is
 * **SHA256withRSA against Midtrans's public key** over a four-part string — and the danger is
 * that the transactional signature this codebase also computes has *five* parts. Using that
 * formula here rejects every genuine notification while failing exactly as a forgery would, so
 * [SnapSignatures.notificationStringToSign] exists separately and is pinned by test.
 */
@ApplicationScoped
class SnapNotificationVerifier {

    @Inject
    lateinit var credentials: SnapCredentials

    fun isConfigured(): Boolean = credentials.isConfigured()

    /**
     * @return false for every failure an attacker controls — a missing header, a malformed
     *         signature, an unparseable body. A public endpoint that distinguishes them in its
     *         response is an oracle for constructing a valid one.
     */
    fun verify(notification: SnapNotification?): Boolean {
        if (!credentials.isConfigured() || notification == null) {
            return false
        }
        return SnapSignatures.verifyNotification("POST", NOTIFY_PATH, notification.rawBody,
            notification.timestamp, notification.signature, credentials.midtransPublicKey())
    }

    companion object {
        /** The path Midtrans appends for QRIS, and therefore the one inside the signature. Not a path
         * of our choosing: the merchant configures a domain and Midtrans appends this. A
         * `const`, so `SnapNotificationResource`'s `@Path` can still name it. */
        const val NOTIFY_PATH = "/v1.0/qr/qr-mpm-notify"
    }
}
