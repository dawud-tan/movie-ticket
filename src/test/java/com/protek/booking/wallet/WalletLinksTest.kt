package com.protek.booking.wallet

import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ResourcePool
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import com.protek.booking.web.dto.ReservationGroupDetail
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID

/**
 * A booking's Google Wallet links — the half of the feature that has decisions in
 * it, exercised against a real booking and a throwaway signing key.
 *
 * `WalletPassTest` covers what one pass contains. This covers which seats get one, that
 * each carries *its own* seat's token, and that the times on it are the branch's rather
 * than UTC's. Those are the mistakes that would be invisible in production: a pass that renders
 * beautifully and admits the wrong person, or one that puts a late Jayapura showing on the wrong
 * day of somebody's phone.
 *
 * The issuer is supplied directly rather than resolved from
 * `GOOGLE_APPLICATION_CREDENTIALS`, which is what the split in `GoogleWalletLinks`
 * exists for: whose Google account signs is an ops question, and none of the decisions here
 * depend on the answer.
 *
 * Kotlin since 2026-10-08, reading the passes with kotlinx.serialization where it read them with
 * Jackson.
 */
@QuarkusTest
class WalletLinksTest {

    @Inject
    lateinit var walletLinks: GoogleWalletLinks

    @Inject
    lateinit var bookingService: BookingService

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var fixture: TestFixture

    private val issuer = WalletPass.Issuer("3388000000012345678",
        "Demo Cinema", "tickets@example.iam.gserviceaccount.com",
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)

    private data class Booked(val detail: ReservationGroupDetail, val seats: List<Reservation>)

    /**
     * A settled counter sale of `seatCount` seats. The counter deliberately, because it is
     * the case that is structurally unable to have a pass sent to it — the booking still
     * has confirmed seats with tokens, so the links are buildable; what a walk-in has no way to
     * do is receive one. This proves the mapping without pretending the delivery problem away.
     */
    private fun sell(row: String, seatCount: Int, branchZone: ZoneId?, startsAt: Instant?): Booked {
        val tenant = currentTenant.get()
        val hall: ResourcePool = if (branchZone == null) {
            fixture.pool(tenant)
        } else {
            val site = fixture.siteInZone(tenant, "Branch " + row + " " + UUID.randomUUID(),
                "Jayapura", branchZone.id)
            fixture.poolAtSite(tenant, site, "Studio $row")
        }
        val showing = if (startsAt == null) fixture.session(tenant, hall, "on_sale")
        else fixture.sessionAt(tenant, hall, startsAt, "on_sale")
        fixture.rateRule(tenant, hall, SEAT_PRICE)
        val seatIds = (1..seatCount).map { i -> fixture.unit(tenant, hall, row + i, row, i).resourceUnitId }
        val held = bookingService.holdForCounterSale(
            hall.resourcePoolId, showing.resourcePoolSessionId, seatIds)
        assertTrue(held.success, "setup hold failed: " + held.failureReason)
        assertTrue(bookingService.confirmGroup(held.group!!.reservationGroupId), "setup confirm failed")
        val detail = bookingService.getGroupDetail(held.group.reservationGroupId)
        return Booked(detail, detail.admissionLines().map { it.reservation!! })
    }

    private fun payloadOf(saveUrl: String): JsonObject {
        val jwt = saveUrl.substring("https://pay.google.com/gp/v/save/".length)
        return try {
            val claims = String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), StandardCharsets.UTF_8)
            Json.parseToJsonElement(claims).jsonObject["payload"]!!.jsonObject
        } catch (e: Exception) {
            throw AssertionError("save URL did not carry a decodable JWT: $e", e)
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun everyConfirmedSeatGetsItsOwnPass_carryingItsOwnToken() {
        // The failure this exists to catch: three passes that all encode the first seat's token.
        // Nothing on screen would look wrong, and two of the three people would be turned away
        // at the door as duplicates.
        val booked = sell("W", 3, null, null)

        val links = walletLinks.saveUrlsFor(issuer, booked.detail)

        assertEquals(3, links.size, "one pass per confirmed seat")
        for (seat in booked.seats) {
            val url = links[seat.reservationId]
            assertNotNull(url, "seat " + seat.reservationId + " has no pass")
            val ticket = payloadOf(url!!)["eventTicketObjects"]!!.jsonArray[0].jsonObject
            assertEquals(seat.admissionToken,
                ticket["barcode"]!!.jsonObject["value"]!!.jsonPrimitive.content,
                "each pass must carry the token of the seat it is keyed to")
            assertEquals("3388000000012345678." + seat.reservationId, ticket["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anUnpaidHoldHasNoPass_becauseItHasNoTicketYet() {
        // admissionLines() is the single answer to "which seats have a ticket", and the pass has
        // to use it rather than re-deciding: a pass for a held seat promises entry the scan
        // endpoint refuses, and it would sit in the customer's wallet after the hold expired.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val showing = fixture.session(tenant, hall, "on_sale")
        fixture.rateRule(tenant, hall, SEAT_PRICE)
        val seatId = fixture.unit(tenant, hall, "X1", "X", 1).resourceUnitId
        val held = bookingService.holdForCounterSale(
            hall.resourcePoolId, showing.resourcePoolSessionId, listOf(seatId))
        assertTrue(held.success, held.failureReason)

        val detail = bookingService.getGroupDetail(held.group!!.reservationGroupId)
        assertEquals("held", detail.lines!![0].reservation!!.status, "precondition")

        assertTrue(walletLinks.saveUrlsFor(issuer, detail).isEmpty(),
            "an unpaid hold must not put a ticket in anybody's wallet")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theShowingTimeOnThePassIsTheBranchesLocalTime_notTheUtcOneTheRowHolds() {
        // Same trap calendar pricing and the SEO markup both paid for, arriving a third time: a pass groups by
        // local day, so a 00:30 Jayapura showing filed under UTC lands on the phone a day early —
        // which is the day the customer turns up.
        val jayapura = ZoneId.of("Asia/Jayapura")
        val startsAt = ZonedDateTime.of(2099, 10, 5, 0, 30, 0, 0, jayapura).toInstant()
        val booked = sell("Y", 1, jayapura, startsAt)

        assertEquals("2099-10-04", startsAt.atZone(ZoneId.of("UTC")).toLocalDate().toString(),
            "precondition: in UTC this showing is the day before, which is the whole point")

        val url = walletLinks.saveUrlsFor(issuer, booked.detail)[booked.seats[0].reservationId]
        val eventClass = payloadOf(url!!)["eventTicketClasses"]!!.jsonArray[0].jsonObject

        assertEquals("2099-10-05T00:30:00+09:00",
            eventClass["dateTime"]!!.jsonObject["start"]!!.jsonPrimitive.content,
            "WIT is UTC+9, and the pass has to say so")
        assertEquals("Demo Cinema", eventClass["issuerName"]!!.jsonPrimitive.content)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun withNoIssuerIdConfiguredThereAreNoLinksAtAll_whichIsEveryDeploymentToday() {
        // The public entry point, which is the one the page calls. Nobody has created the issuer
        // account yet (the outstanding, non-technical step), so this is the
        // state that actually ships — and it must cost nothing and break nothing.
        val booked = sell("Z", 1, null, null)

        assertFalse(walletLinks.isConfigured(),
            "the suite must run unconfigured, or the assertion below proves nothing")
        assertTrue(walletLinks.saveUrlsFor(booked.detail, "Demo Cinema").isEmpty())
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun whenLinksExistTheTicketCardOffersOneButtonPerSeat() {
        // The only test that proves the markup, and it needs the bean stubbed because no issuer
        // account exists to configure. Without it the whole feature could render nothing on every
        // deployment and every other test here would still pass: they all assert on a map.
        val booked = sell("T", 2, null, null)
        val first = booked.seats[0].reservationId!!
        val second = booked.seats[1].reservationId!!
        val stubbed = mapOf(
            first to "https://pay.google.com/gp/v/save/first.jwt.signature",
            second to "https://pay.google.com/gp/v/save/second.jwt.signature")
        QuarkusMock.installMockForType(object : GoogleWalletLinks() {
            override fun isConfigured(): Boolean = true

            override fun saveUrlsFor(detail: ReservationGroupDetail?, issuerName: String?): Map<UUID, String> =
                stubbed
        }, GoogleWalletLinks::class.java)

        given().get("/orders/" + booked.detail.group!!.reservationGroupId)
            .then().statusCode(200)
            .body(containsString("Add to Google Wallet"))
            .body(containsString("https://pay.google.com/gp/v/save/first.jwt.signature"))
            .body(containsString("https://pay.google.com/gp/v/save/second.jwt.signature"))
            // Still the ticket it always was: the pass is additive, and the barcode a walk-in
            // or an iPhone owner relies on is untouched by the button beside it.
            .body(containsString(booked.seats[0].admissionToken))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theTicketPageRendersWithoutAWalletButton_andTheBarcodeIsStillThere() {
        // What the ticket card is for: the pass is additive, so its absence changes nothing that
        // gets anybody through a door.
        val booked = sell("V", 1, null, null)

        given().get("/orders/" + booked.detail.group!!.reservationGroupId)
            .then().statusCode(200)
            .body(not(containsString("Add to Google Wallet")))
            .body(containsString(booked.seats[0].admissionToken))
    }

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }
}
