package com.protek.booking.wallet

import com.protek.booking.isJavaBlank
import com.protek.booking.json.JacksonText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * The "Add to Google Wallet" link for one seat — one signed JWT, and nothing else.
 *
 * **Everything is in the JWT; there is no call to Google.** Google's save flow accepts
 * either a "skinny" JWT naming classes and objects that were created beforehand through the REST
 * API, or a "fat" one carrying their full definitions inline, which Google creates when the
 * customer taps. This is the fat form, and that choice is the whole architecture rather than a
 * detail: any Wallet work must be fire-and-forget after commit — a
 * booking that has already committed must not fail because Google was unreachable.
 * With the definitions inline there is no outbound request to fail, no issuer-side state to keep
 * in step with ours, and nothing to retry. The trap dissolves rather than being handled.
 *
 * **Pure, and separated from any bean deliberately** — the same reasoning
 * `SnapSignatures` records for the BI-SNAP signatures. This is the only part of the feature
 * that can be built and proven before an issuer account exists, and a wrong payload, a wrong key
 * and an unapproved issuer all present identically ("the link does not work"), so being able to
 * decode and verify the exact JWT in a test is what separates them.
 *
 * **What a test here can and cannot prove.** It can prove the JWT decodes, verifies against
 * the signing key, carries the seat's own admission token as its barcode, and fits in a URL. It
 * cannot prove Google accepts it: that needs an issuer account, which is ops with a business
 * profile behind it, not code.
 *
 * **The claims are built with kotlinx and written by [JacksonText]** (since 2026-10-08; Jackson's
 * `ObjectNode` before). That keeps the bytes Jackson wrote, which matters here more than anywhere
 * but the BI-SNAP signatures: [MAX_SAVE_URL_LENGTH]'s headroom was measured against them.
 */
object WalletPass {

    /** Where a save link points. The JWT is the whole path segment. */
    private const val SAVE_URL_PREFIX = "https://pay.google.com/gp/v/save/"

    /**
     * The ceiling this implementation holds itself to for a finished save URL.
     *
     * The JWT travels in a URL, so it inherits the practical limit on one — Google's own
     * guidance for this flow is to stay under ~1800 characters, and beyond it the failure is a
     * link that some browsers truncate rather than an error anybody sees. That is why the pass is
     * **per seat** rather than one link for a whole booking: eight seats' objects in one JWT
     * would cross it, and a family booking is exactly when it would be crossed.
     *
     * **Measured, because it is closer than it looks.** A signature is 342 characters of
     * the budget before anything is said, base64 adds a third to whatever JSON is left, and an
     * ordinary ticket — one Indonesian film title, one branch address — came out at **1772**.
     * That is 28 characters of headroom, which is one longer film title away from nothing. Hence
     * [Detail]: the pass sheds what a ticket can do without before it gives up, because a
     * shorter pass beats no pass. [saveUrl] refuses only when even the shortest form
     * crosses the line, and the caller then shows no button. A missing button is a customer who
     * still has the barcode on the page; a truncated one is a customer who taps, gets an error,
     * and concludes the ticket is bad while standing at a door where the page would have worked.
     */
    const val MAX_SAVE_URL_LENGTH = 1800

    /**
     * How much of the pass survives, in the order it is worth giving up.
     *
     * Every step is a real loss and none is a fudge, which is what makes the order the whole
     * decision: the end time is the thing nobody reads off a cinema ticket, the barcode's
     * printed text is a fallback the ticket page still carries, and a venue that names the hall
     * without the street still tells the customer where to stand. Google rejects an
     * `EventVenue` that has a name and no address, so the address is replaced rather than
     * dropped.
     */
    internal enum class Detail {
        /** Everything below is present. */
        FULL,

        /** No `dateTime.end`. */
        NO_END_TIME,

        /** ...and no `barcode.alternateText`. */
        NO_BARCODE_TEXT,

        /** ...and the venue address falls back to the venue name. */
        NO_VENUE_ADDRESS,
    }

    /** Google's field is a language-tagged string everywhere it takes text at all. */
    private const val LANGUAGE = "en-US"

    /**
     * Who is issuing, and with what key.
     *
     * @param issuerId the numeric Google Wallet issuer id. Every class and object id must be
     *        prefixed with it, which is what scopes them to this account.
     * @param displayName what the customer sees as the issuer on the pass — **the tenant's**
     *        name, not Protek's. This leaves a contract question open (one issuer
     *        account makes Protek the issuer of record for its tenants' tickets); showing the
     *        cinema's name is the display half of that, and it is the half a customer notices.
     * @param serviceAccountEmail the JWT's `iss`. Google matches it to the key.
     */
    @JvmRecord
    data class Issuer(val issuerId: String?, val displayName: String?, val serviceAccountEmail: String?,
                      val privateKey: PrivateKey?)

    /**
     * The showing — one `eventTicketClass`, shared by every seat of it.
     *
     * @param start the showing's start **in the branch's zone**. A pass groups by local day
     *        and prints a local time, so this must be the offset `CalendarPricing.zoneFor`
     *        resolves and never a raw UTC instant: for a Makassar branch that is an hour, and an
     *        hour is a day for a late showing. Same rule the SEO markup's `startDate`
     *        follows, and it calls the same method to get it.
     */
    @JvmRecord
    data class Event(val sessionId: UUID?, val name: String?, val venueName: String?, val venueAddress: String?,
                     val start: ZonedDateTime?, val end: ZonedDateTime?)

    /**
     * One seat — one `eventTicketObject`.
     *
     * @param admissionToken the per-seat token, and the barcode's value. **Not the group id
     *        and not the booking code**: an admission credential that is
     *        also a URL path segment couples two lifetimes that should be separate, and the
     *        booking code is dictatable by design so it must never open a door.
     */
    @JvmRecord
    data class Seat(val reservationId: UUID?, val admissionToken: String?, val label: String?, val rowLabel: String?)

    /**
     * The save URL for one seat, or null when the result would be too long to travel in one.
     *
     * Null rather than an exception: this is rendered beside a barcode that already works, so
     * a pass that cannot be built costs the customer a convenience and nothing more. Throwing
     * would take down the ticket card — the one thing on the page they actually need.
     */
    @JvmStatic
    fun saveUrl(issuer: Issuer, event: Event, seat: Seat): String? {
        // Re-signs once per step, which is an RSA operation the size of a page render and only
        // ever reached by a ticket that would otherwise have no pass at all.
        for (detail in Detail.entries) {
            val url = SAVE_URL_PREFIX + signedJwt(issuer, event, seat, detail)
            if (url.length <= MAX_SAVE_URL_LENGTH) {
                return url
            }
        }
        return null
    }

    /** The JWT alone, exposed so a test can decode and verify it without parsing a URL; with no
     * `detail`, the whole pass, for a test that wants to read what a ticket normally carries. */
    internal fun signedJwt(issuer: Issuer, event: Event, seat: Seat, detail: Detail = Detail.FULL): String {
        val header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encode(claims(issuer, event, seat, detail).toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$header.$claims"
        return signingInput + "." + encode(sign(signingInput, issuer.privateKey))
    }

    private fun claims(issuer: Issuer, event: Event, seat: Seat, detail: Detail): String =
        JacksonText.render(buildJsonObject {
            put("iss", issuer.serviceAccountEmail)
            put("aud", "google")
            put("typ", "savetowallet")
            put("iat", Instant.now().epochSecond)
            putJsonObject("payload") {
                putJsonArray("eventTicketClasses") { add(eventClass(issuer, event, detail)) }
                putJsonArray("eventTicketObjects") { add(ticketObject(issuer, event, seat, detail)) }
            }
        })

    private fun eventClass(issuer: Issuer, event: Event, detail: Detail): JsonObject = buildJsonObject {
        put("id", classId(issuer, event))
        put("issuerName", issuer.displayName)
        // A class created this way starts unreviewed; Google reviews it when the issuer asks for
        // publishing access. In demo mode — which is where a free issuer account lands, and which
        // is enough for a demo — passes issue to allowlisted accounts either way.
        put("reviewStatus", "UNDER_REVIEW")
        put("eventName", localized(event.name))
        putJsonObject("venue") {
            put("name", localized(event.venueName))
            // Google requires an address on the venue if the venue is present at all, so a hall
            // with none — or a pass that has run out of room for one — falls back to its own name
            // rather than emitting an object the API would reject.
            val keepAddress = detail < Detail.NO_VENUE_ADDRESS &&
                event.venueAddress != null && !event.venueAddress.isJavaBlank()
            put("address", localized(if (keepAddress) event.venueAddress else event.venueName))
        }
        putJsonObject("dateTime") {
            put("start", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(event.start))
            if (event.end != null && detail < Detail.NO_END_TIME) {
                put("end", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(event.end))
            }
        }
    }

    private fun ticketObject(issuer: Issuer, event: Event, seat: Seat, detail: Detail): JsonObject = buildJsonObject {
        put("id", issuer.issuerId + "." + seat.reservationId)
        put("classId", classId(issuer, event))
        put("state", "ACTIVE")

        putJsonObject("barcode") {
            // The same symbology the printed ticket draws (admission/Code128.kt), for the same
            // reason: whatever the usher's scanner reads, it hands the endpoint one string, and
            // that endpoint neither knows nor cares whether it came from paper, a screen or a
            // wallet.
            put("type", "CODE_128")
            put("value", seat.admissionToken)
            // Printed under the bars, so a scanner that will not read a phone screen — a real
            // failure at a real door — leaves the usher something to type. First to go when the
            // pass will not fit: the same string is on the ticket page, which the customer still
            // has.
            if (detail < Detail.NO_BARCODE_TEXT) {
                put("alternateText", seat.admissionToken)
            }
        }

        putJsonObject("seatInfo") {
            put("seat", localized(seat.label))
            if (seat.rowLabel != null && !seat.rowLabel.isJavaBlank()) {
                put("row", localized(seat.rowLabel))
            }
        }
    }

    /**
     * `<issuerId>.<sessionId>` — one class per showing.
     *
     * The session id rather than a name: two showings of one film in one evening are two
     * classes, and a name-derived id would collide them into one pass design carrying the wrong
     * time. Ids are immutable once Google has seen them, so this has to be right first.
     */
    private fun classId(issuer: Issuer, event: Event): String = issuer.issuerId + "." + event.sessionId

    private fun localized(value: String?): JsonObject = buildJsonObject {
        putJsonObject("defaultValue") {
            put("language", LANGUAGE)
            put("value", value ?: "")
        }
    }

    private fun sign(signingInput: String, privateKey: PrivateKey?): ByteArray {
        try {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(signingInput.toByteArray(StandardCharsets.UTF_8))
            return signature.sign()
        } catch (e: Exception) {
            throw IllegalStateException("Could not sign the Wallet JWT", e)
        }
    }

    /** Base64url, no padding — a JWT segment is not plain base64, and '=' in a URL path is the
     * kind of thing that works everywhere except the one browser a customer is using. */
    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
