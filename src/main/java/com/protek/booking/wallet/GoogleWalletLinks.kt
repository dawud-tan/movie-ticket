package com.protek.booking.wallet

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import com.protek.booking.web.dto.ReservationGroupDetail
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional
import java.util.UUID

/**
 * Turns a booking's confirmed seats into "Add to Google Wallet" links.
 *
 * **Additive, never a replacement.** The barcode on the ticket card is the ticket; this
 * adds a second place to carry the same value. That ordering is not
 * reversible — Google Wallet is a display surface, not a ticketing system, and it gives nothing
 * at all on the admission side. iOS gets no pass, a walk-in has no device to send one to, and
 * both still have the printed card. Every method here therefore fails by returning nothing.
 *
 * **Unconfigured is the normal state and must stay cheap.** This application boots with
 * zero required environment variables — a guarantee `PushNotificationService` records
 * paying for — so the issuer id is optional, the credentials are loaded lazily on first use, and
 * a deployment with neither renders a ticket page with no button on it and logs nothing at
 * request time.
 *
 * **Indonesia is supported for this and not for what people mean.** Passes live in the
 * Google Wallet app, which is available here; the contactless-payment half is what the "not
 * available in Indonesia" headlines are about, and nothing here wants it. The one
 * genuinely unverified thing is whether an Indonesian-registered business can be granted
 * publishing access, which is a business-profile question rather than a code one — until then an
 * issuer account works in demo mode, issuing to allowlisted test accounts, which is all a
 * demo needs.
 */
@ApplicationScoped
class GoogleWalletLinks {

    /**
     * The Google Wallet issuer id, from the Google Pay &amp; Wallet Console.
     *
     * `Optional<String>` for the reason `SignInLink` records: the property is
     * declared in `application.properties` as `${WALLET_ISSUER_ID:}`, so when the env
     * var is unset the property *is* defined and empty, and a plain `String`
     * injection point fails the whole deployment rather than defaulting.
     */
    @ConfigProperty(name = "booking.wallet.issuer-id")
    lateinit var issuerId: Optional<String>

    /** Resolved once. `null` means "looked and there was nothing usable" — see
     * [credentials] for why that is remembered rather than retried per request. */
    @Volatile
    private var cached: ServiceAccountCredentials? = null

    @Volatile
    private var resolved = false

    /** `isJavaBlank`, not Kotlin's `isBlank`, which also counts U+00A0. */
    fun isConfigured(): Boolean = issuerId.isPresent && !issuerId.get().isJavaBlank()

    /**
     * A save link per confirmed seat, keyed by `reservation_id`.
     *
     * Empty — never a partial map with broken entries — whenever anything is missing: no
     * issuer id, no service-account credentials, no showing on the booking. The template asks
     * per seat, so a seat whose link would have been too long for a URL simply has none while
     * its neighbours keep theirs.
     *
     * @param issuerName the tenant's display name. Passed in rather than resolved here because
     *        this class has no business deciding whose tenant a booking belongs to — tenancy stays
     *        that in `CurrentTenant`, which has no API that would take one from a caller.
     */
    fun saveUrlsFor(detail: ReservationGroupDetail?, issuerName: String?): Map<UUID, String> {
        if (!isConfigured() || detail == null || detail.showing == null) {
            return java.util.Map.of()
        }
        val credentials = credentials() ?: return java.util.Map.of()
        return saveUrlsFor(WalletPass.Issuer(issuerId.get().javaTrim(), issuerName,
                credentials.clientEmail, credentials.privateKey), detail)
    }

    /**
     * The same links for a caller that has already answered "who is issuing, and with what key".
     *
     * Split out because those are two independent questions and only one of them needs a
     * Google account: this half — which seats get a pass, what goes on it, whose zone its times
     * are in — is the half with decisions in it, and a test can exercise every one of them
     * against a throwaway key pair. The same reason `SnapSignatures` is separate from the
     * gateway that uses it.
     *
     * Public where the Java was package-private: Kotlin has none, and `@JvmName` cannot be put on
     * an all-open member.
     */
    fun saveUrlsFor(issuer: WalletPass.Issuer, detail: ReservationGroupDetail?): Map<UUID, String> {
        if (detail?.showing == null) {
            return java.util.Map.of()
        }
        val showing = detail.showing
        val hall = detail.hall!!
        val zone = detail.zone ?: ZoneId.systemDefault()
        val event = WalletPass.Event(
                showing.resourcePoolSessionId,
                showing.label,
                hall.name,
                hall.address,
                ZonedDateTime.ofInstant(showing.startsAt, zone),
                if (showing.endsAt == null) null
                else ZonedDateTime.ofInstant(showing.endsAt, zone))

        val links = LinkedHashMap<UUID, String>()
        for (line in detail.admissionLines()) {
            val reservation = line.reservation!!
            val url = WalletPass.saveUrl(issuer, event, WalletPass.Seat(
                    reservation.reservationId, reservation.admissionToken,
                    if (line.seat == null) "" else line.seat.label,
                    line.seat?.groupKey))
            if (url != null) {
                links[reservation.reservationId!!] = url
            }
        }
        return links
    }

    /**
     * The service account that signs, from `GOOGLE_APPLICATION_CREDENTIALS` — the same
     * variable Web Push already needs, so a box with push working has this for free.
     *
     * **Resolved once, including the failure.** Application Default Credentials throw when
     * unset, and a deployment without them is the ordinary case rather than an incident: retrying
     * per page view would turn a permanent absence into a stack trace on every ticket render.
     *
     * A non-service-account credential (a developer's own `gcloud` login) has no private
     * key to sign with. That is a configuration mistake worth naming once in the log rather than
     * a supported mode, because the symptom — no button, no error — is otherwise indistinguishable
     * from not having set the issuer id.
     */
    private fun credentials(): ServiceAccountCredentials? {
        if (resolved) {
            return cached
        }
        synchronized(this) {
            if (resolved) {
                return cached
            }
            try {
                val credentials = GoogleCredentials.getApplicationDefault()
                if (credentials is ServiceAccountCredentials) {
                    cached = credentials
                } else {
                    LOG.warnf("booking.wallet.issuer-id is set but GOOGLE_APPLICATION_CREDENTIALS "
                            + "resolved to %s, which has no private key to sign a pass with. "
                            + "Wallet links are off; point it at a service-account key file.",
                            credentials.javaClass.simpleName)
                }
            } catch (e: Exception) {
                LOG.warnf("booking.wallet.issuer-id is set but no Google application credentials "
                        + "are available (%s). Wallet links are off; the printed ticket is "
                        + "unaffected.", e.message)
            }
            resolved = true
            return cached
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(GoogleWalletLinks::class.java)
    }
}
