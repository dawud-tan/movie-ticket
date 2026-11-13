package com.protek.booking.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID

/**
 * The Google Wallet save link, proven without an issuer account.
 *
 * **Why this can be a pure test at all.** The pass is a fat JWT: the class and object
 * definitions travel inside the link and Google creates them when the customer taps, so there is
 * no API call to stub and no issuer-side state to reconcile. Everything this feature does happens
 * before the link leaves the page, which means all of it is checkable here — the same reasoning
 * `SnapSignaturesTest` rests on for BI-SNAP.
 *
 * **And what it deliberately does not claim.** That Google accepts the payload is not
 * provable from this repository: it needs an issuer account, still the outstanding step,
 * which is a business-profile question rather than a code one. A green run here means the link is
 * well formed, signed by the key we think, and carries the right seat's token — not that a pass
 * has ever appeared on a phone.
 *
 * Kotlin since 2026-10-08, reading the claims with kotlinx.serialization where it read them with
 * Jackson; `signedJwt` is `internal`, which a Kotlin test in this module can reach.
 */
class WalletPassTest {

    private val keyPair = rsaKeyPair()

    private fun issuer() = WalletPass.Issuer("3388000000012345678", "Moonbeam Cineplex",
        "tickets@booking-platform.iam.gserviceaccount.com", keyPair.private)

    private fun event(sessionId: UUID): WalletPass.Event {
        // 19:00 in Makassar (WITA, UTC+8) — the case that separates "the branch's zone" from a
        // raw instant, and the one calendar pricing and the SEO markup both had to get right before this did.
        val start = ZonedDateTime.of(2099, 6, 7, 19, 0, 0, 0, MAKASSAR)
        return WalletPass.Event(sessionId, "Petualangan Sherina 2", "Studio 1",
            "Jl. Diponegoro 1, Rekaan", start, start.plusHours(2))
    }

    /** The claims segment's own text — the bytes that were signed. */
    private fun claimsText(jwt: String): String {
        val parts = jwt.split('.')
        assertEquals(3, parts.size, "a JWT is three dot-separated segments")
        return String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }

    private fun claimsOf(jwt: String): JsonObject =
        try {
            Json.parseToJsonElement(claimsText(jwt)).jsonObject
        } catch (e: Exception) {
            throw AssertionError("the claims segment is not decodable JSON: $e", e)
        }

    @Test
    fun theLinkIsAJwtSignedByTheServiceAccountKey_andGoogleCanCheckThat() {
        val jwt = WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(UUID.randomUUID(), "BPT-ABCDEF0123456789ABCDEF01", "A1", "A"))

        val parts = jwt.split('.')
        assertDoesNotThrow({ verify(parts[0] + "." + parts[1], parts[2]) },
            "the signature must verify against the public half of the signing key")

        val claims = claimsOf(jwt)
        assertEquals("google", claims.text("aud"))
        assertEquals("savetowallet", claims.text("typ"))
        assertEquals("tickets@booking-platform.iam.gserviceaccount.com", claims.text("iss"),
            "iss is the service account, which is how Google finds the key to check")
        assertTrue(claims["iat"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun aTamperedPayloadStopsVerifying_whichIsTheOnlyReasonToSignItAtAll() {
        val jwt = WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(UUID.randomUUID(), "BPT-AAAAAAAAAAAAAAAAAAAAAAAA", "B2", "B"))
        val parts = jwt.split('.')

        // Somebody editing the barcode value to a token they did not buy: same shape, same
        // length, different bytes.
        val forged = claimsText(jwt).replace("BPT-AAAAAAAAAAAAAAAAAAAAAAAA", "BPT-BBBBBBBBBBBBBBBBBBBBBBBB")
        val reencoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forged.toByteArray(StandardCharsets.UTF_8))

        assertFalse(verify(parts[0] + "." + reencoded, parts[2]),
            "a rewritten payload must not verify against the original signature")
    }

    @Test
    fun theBarcodeCarriesTheSeatsOwnAdmissionToken_notTheGroupIdAndNotTheBookingCode() {
        // The admission token's first trap, restated for Wallet: an admission credential that is also a URL path
        // segment couples two lifetimes that should be separate, and the booking code is
        // dictatable by design so it must never open a door. The admission schema mints a distinct token for
        // exactly this, and the pass has to carry that one.
        val reservationId = UUID.randomUUID()
        val token = "BPT-0123456789ABCDEF01234567"
        val ticket = claimsOf(WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(reservationId, token, "C3", "C")))
            .obj("payload").first("eventTicketObjects")

        assertEquals("CODE_128", ticket.obj("barcode").text("type"),
            "the same symbology the printed ticket draws, so one scan path serves both")
        assertEquals(token, ticket.obj("barcode").text("value"))
        assertEquals(token, ticket.obj("barcode").text("alternateText"),
            "printed under the bars, so an usher whose scanner will not read a phone screen " +
                "has something to type")
        assertEquals("ACTIVE", ticket.text("state"))
    }

    @Test
    fun oneClassPerShowing_andEverySeatOfItPointsAtThatClass() {
        val sessionId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val payload = claimsOf(WalletPass.signedJwt(issuer(), event(sessionId),
            WalletPass.Seat(reservationId, "BPT-000000000000000000000001", "D4", "D")))
            .obj("payload")

        val eventClass = payload.first("eventTicketClasses")
        val ticket = payload.first("eventTicketObjects")
        assertEquals("3388000000012345678.$sessionId", eventClass.text("id"),
            "the class is keyed on the showing: two screenings of one film in one evening are " +
                "two classes, or one of them prints the other's time")
        assertEquals(eventClass.text("id"), ticket.text("classId"))
        assertEquals("3388000000012345678.$reservationId", ticket.text("id"),
            "every id must be prefixed with the issuer id, which is what scopes it to the account")
        assertEquals("Moonbeam Cineplex", eventClass.text("issuerName"),
            "the customer sees the cinema they bought from, not the platform that issued it")
    }

    @Test
    fun theShowingTimeCarriesTheBranchesOffset_andNotUtc() {
        // The trap calendar pricing paid for and the SEO markup paid for again: every Indonesian zone is ahead
        // of UTC, a pass groups by local day, and an hour is a day for a late showing.
        val eventClass = claimsOf(WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(UUID.randomUUID(), "BPT-000000000000000000000002", "E5", "E")))
            .obj("payload").first("eventTicketClasses")

        assertEquals("2099-06-07T19:00:00+08:00", eventClass.obj("dateTime").text("start"))
        assertEquals("2099-06-07T21:00:00+08:00", eventClass.obj("dateTime").text("end"))
    }

    @Test
    fun theSeatIsOnThePassWhereAnUsherWouldLookForIt() {
        val seatInfo = claimsOf(WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(UUID.randomUUID(), "BPT-000000000000000000000003", "F6", "F")))
            .obj("payload").first("eventTicketObjects").obj("seatInfo")

        assertEquals("F6", seatInfo.obj("seat").obj("defaultValue").text("value"))
        assertEquals("F", seatInfo.obj("row").obj("defaultValue").text("value"))
    }

    @Test
    fun aHallWithNoAddressStillProducesAVenueGoogleWouldAccept() {
        // resource_pool.address is free text and nullable in practice; a venue with a name and no
        // address is rejected outright, which would lose the pass for a whole cinema over a field
        // nobody filled in.
        val noAddress = WalletPass.Event(UUID.randomUUID(), "Film", "Studio 2",
            null, ZonedDateTime.of(2099, 1, 1, 12, 0, 0, 0, MAKASSAR), null)
        val venue = claimsOf(WalletPass.signedJwt(issuer(), noAddress,
            WalletPass.Seat(UUID.randomUUID(), "BPT-000000000000000000000004", "G7", "G")))
            .obj("payload").first("eventTicketClasses").obj("venue")

        assertEquals("Studio 2", venue.obj("name").obj("defaultValue").text("value"))
        assertEquals("Studio 2", venue.obj("address").obj("defaultValue").text("value"))
    }

    @Test
    fun aControlCharacterInATitleIsEscapedAsJacksonEscapedIt() {
        // The claims were Jackson's ObjectNode until 2026-10-08. They are now a kotlinx tree
        // written by JacksonText, because kotlinx alone writes U+001F as \u001f and Jackson wrote
        // \u001F: the same string to any reader, but these are the bytes that get signed, and
        // MAX_SAVE_URL_LENGTH's headroom was measured on Jackson's. A title pasted out of a PDF is
        // where such a character comes from.
        val titled = WalletPass.Event(UUID.randomUUID(), "Film\u001Fwith\ta unit separator", "Studio 3",
            "Address", ZonedDateTime.of(2099, 1, 1, 12, 0, 0, 0, MAKASSAR), null)

        val claims = claimsText(WalletPass.signedJwt(issuer(), titled,
            WalletPass.Seat(UUID.randomUUID(), "BPT-000000000000000000000006", "H8", "H")))

        assertTrue(claims.contains("Film\\u001Fwith\\ta unit separator"), claims)
        assertFalse(claims.contains("\\u001f"), "kotlinx's lowercase escape must not reach the JWT")
    }

    @Test
    fun anOrdinaryTicketFitsWholeInAUrl_andTheHeadroomIsTheThingToWatch() {
        val url = WalletPass.saveUrl(issuer(), event(UUID.randomUUID()),
            WalletPass.Seat(UUID.randomUUID(), "BPT-9F3C2A1B8E7D6C5B4A392817", "K12", "K"))

        assertNotNull(url)
        assertTrue(url!!.startsWith(SAVE_URL_PREFIX), url)
        assertTrue(url.length <= WalletPass.MAX_SAVE_URL_LENGTH,
            "save URL was " + url.length + " of " + WalletPass.MAX_SAVE_URL_LENGTH)
        // Measured at 1772 when this was written — 28 characters of room. Asserting the FULL form
        // still fits is what makes a future field addition show up here rather than as passes
        // silently losing their end time in production.
        assertEquals(url.length, (SAVE_URL_PREFIX +
            WalletPass.signedJwt(issuer(), event(UUID.randomUUID()),
                WalletPass.Seat(UUID.randomUUID(), "BPT-9F3C2A1B8E7D6C5B4A392817", "K12", "K"))).length,
            "an ordinary ticket must still be the FULL pass, not a degraded one")
    }

    @Test
    fun aVerboseTicketKeepsItsPassBySheddingWhatATicketCanDoWithout() {
        // Measured at 1947 characters in full — over the ceiling. This is not a contrived case:
        // a long Indonesian title plus a full regency street address is what a real cinema's data
        // looks like, so "no pass" here would mean the feature not working where it matters. It gives up the end time first, then the barcode's printed text, then
        // the street address — in that order, and no further than it has to.
        val verbose = WalletPass.Event(UUID.randomUUID(),
            "Sri Asih: Kebangkitan Sang Pelindung Semesta (Dolby Atmos)",
            "Moonbeam Cineplex Kota Maju Studio 4 IMAX",
            "Jl. Jenderal Sudirman No. 123, Cempaka, Rekaan, Kabupaten Rekaan Raya, Jawa Tengah 58111",
            ZonedDateTime.of(2099, 6, 7, 21, 45, 0, 0, MAKASSAR),
            ZonedDateTime.of(2099, 6, 7, 23, 55, 0, 0, MAKASSAR))
        val seat = WalletPass.Seat(UUID.randomUUID(), "BPT-9F3C2A1B8E7D6C5B4A392817", "K12", "K")

        val url = WalletPass.saveUrl(issuer(), verbose, seat)

        assertNotNull(url, "a long title must cost decoration, not the whole pass")
        assertTrue(url!!.length <= WalletPass.MAX_SAVE_URL_LENGTH,
            "save URL was " + url.length + " of " + WalletPass.MAX_SAVE_URL_LENGTH)

        val payload = claimsOf(url.substring(SAVE_URL_PREFIX.length)).obj("payload")
        val eventClass = payload.first("eventTicketClasses")
        val ticket = payload.first("eventTicketObjects")
        // What must survive at every level: the barcode, the seat, the start time, the venue.
        assertEquals(seat.admissionToken, ticket.obj("barcode").text("value"),
            "the barcode is the ticket and is never what gets dropped")
        assertEquals("K12", ticket.obj("seatInfo").obj("seat").obj("defaultValue").text("value"))
        assertEquals("2099-06-07T21:45:00+08:00", eventClass.obj("dateTime").text("start"))
        assertTrue(eventClass.obj("venue").containsKey("address"),
            "Google rejects a venue with a name and no address, so the address is replaced " +
                "rather than removed")
        assertFalse(eventClass.obj("dateTime").containsKey("end"),
            "the end time is the first thing worth giving up")
    }

    @Test
    fun anImpossiblyLongOneIsRefusedRatherThanTruncated() {
        // A link a browser silently cuts short is worse than no link: the customer taps, gets an
        // error, and concludes the ticket is bad — standing at a door where the barcode on the
        // page would have worked. Nothing in the ladder can rescue a title this long, and it
        // stops rather than emitting one.
        val absurd = WalletPass.Event(UUID.randomUUID(), "X".repeat(2000),
            "Venue", "Address", ZonedDateTime.of(2099, 1, 1, 12, 0, 0, 0, MAKASSAR), null)

        assertNull(WalletPass.saveUrl(issuer(), absurd,
            WalletPass.Seat(UUID.randomUUID(), "BPT-000000000000000000000005", "L1", "L")))
    }

    private fun verify(signingInput: String, signatureSegment: String): Boolean =
        try {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(keyPair.public)
            signature.update(signingInput.toByteArray(StandardCharsets.UTF_8))
            signature.verify(Base64.getUrlDecoder().decode(signatureSegment))
        } catch (e: Exception) {
            throw AssertionError(e)
        }

    companion object {
        private val MAKASSAR: ZoneId = ZoneId.of("Asia/Makassar")
        private const val SAVE_URL_PREFIX = "https://pay.google.com/gp/v/save/"

        private fun rsaKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        // Jackson's get(...) chain, failing with the key's name rather than an NPE when it is absent.
        private fun JsonObject.obj(key: String): JsonObject =
            (this[key] ?: throw AssertionError("no '$key' in $this")).jsonObject

        private fun JsonObject.first(key: String): JsonObject =
            (this[key] ?: throw AssertionError("no '$key' in $this")).jsonArray[0].jsonObject

        private fun JsonObject.text(key: String): String =
            (this[key] ?: throw AssertionError("no '$key' in $this")).jsonPrimitive.content
    }
}
