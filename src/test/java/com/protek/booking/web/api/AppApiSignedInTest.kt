package com.protek.booking.web.api

import com.protek.booking.db.Db
import com.protek.booking.domain.ConsentRecord
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.i18n.Language
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.payment.MidtransGateway
import com.protek.booking.payment.PaymentService
import com.protek.booking.payment.StubMidtransGateway
import com.protek.booking.repository.ConsentRecordRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.NativeItSupport
import com.protek.booking.support.TestFixture
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * `/api/app`'s signed-in half (mobile session M1) over real bearer tokens: the `customer-app`
 * OIDC tenant is on, pointed at [FakeGoogleOidc], and every token is minted by that provider the
 * way Credential Manager gets one from Google. `@TestSecurity` would skip exactly what is under
 * test here — the signature and audience checks, and `AppUserAugmentor` mapping the token to the
 * web's own customer row. Session M2's order, payment, ticket and profile follow the hold, with
 * Midtrans stubbed so a QRIS charge can be made.
 */
@QuarkusTest
@WithTestResource(FakeGoogleOidc::class)
class AppApiSignedInTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var consentRepo: ConsentRecordRepository
    @Inject
    lateinit var paymentService: PaymentService

    @BeforeEach
    fun stubMidtrans() {
        QuarkusMock.installMockForType(StubMidtransGateway("test-server-key"), MidtransGateway::class.java)
    }

    @ConfigProperty(name = "quarkus.oidc.customer-app.auth-server-url")
    lateinit var issuer: String

    private data class Hall(val film: Film, val pool: ResourcePool, val session: ResourcePoolSession,
                            val seats: List<ResourceUnit>) {
        val map get() = "/api/app/halls/${pool.resourcePoolId}/showings/${session.resourcePoolSessionId}"
        val hold get() = "$map/hold"
    }

    private fun hall(rating: String, seatCount: Int): Hall {
        val tenant: Tenant = currentTenant.get()
        val site = fixture.site(tenant, "App Signed-in Cinema " + System.nanoTime(), "Rekaan")
        val pool = fixture.poolAtSite(tenant, site, "Studio 1")
        val seats = (1..seatCount).map { fixture.unit(tenant, pool, "A$it", "A", it) }
        val film = fixture.film(tenant, "App Signed-in Film " + System.nanoTime(), rating)
        val session = fixture.sessionOfFilm(tenant, pool, film, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("45000"))
        return Hall(film, pool, session, seats)
    }

    private fun email(who: String) = "app-$who-${System.nanoTime()}@example.test"

    /** An ID token from the fake provider's `/token`, as the phone gets one. */
    private fun token(email: String, audience: String = FakeGoogleOidc.CLIENT_ID): String {
        val form = "code=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
            "&audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$issuer/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString())
        return JsonPath.from(response.body()).getString("id_token")
    }

    private fun asApp(email: String): RequestSpecification =
        given().header("Authorization", "Bearer " + token(email))

    private fun holdBody(seats: List<ResourceUnit>, ticked: Boolean = false): String =
        """{"seatIds":[${seats.joinToString { "\"" + it.resourceUnitId + "\"" }}],""" +
            """"customerName":"App Buyer","customerPhone":"+6280000000000","ageAttested":$ticked}"""

    private fun customerOf(groupId: UUID): UUID? {
        val tenantId = currentTenant.get().tenantId
        return db.inTenantTransaction(tenantId) { c -> groupRepo.findById(c, tenantId, groupId).orElseThrow().customerId }
    }

    private fun consentOf(groupId: UUID): List<ConsentRecord> {
        val tenantId = currentTenant.get().tenantId
        return db.inTenantTransaction(tenantId) { c -> consentRepo.findByCustomer(c, tenantId, customerOf(groupId)) }
    }

    @Test
    fun anAnonymousBrowseCallStillWorksWithTheBearerTenantOn() {
        val h = hall("SU", 1)

        given().`when`().get("/api/app/cities").then().statusCode(200).body("city", hasItem("Rekaan"))
        val raw = given().`when`().get(h.map).then().statusCode(200)
            .body("rows[0].seats[0].status", equalTo("available"))
            .body("mine.size()", equalTo(0))
            .body("ageAdvisory", nullValue())
            .extract().asString()
        assertFalse(raw.contains("reservationId"), raw)
        given().`when`().get("/api/app/me").then().statusCode(401)
    }

    @Test
    fun aTokenMintedForAnotherClientIsRefused_andTheRightOneIsTheSignedInReader() {
        val email = email("aud")
        val elsewhere = token(email, "some-other-sites-client")

        given().header("Authorization", "Bearer $elsewhere").`when`().get("/api/app/me").then().statusCode(401)
        // Not quietly treated as anonymous either: the app is told to sign in again.
        given().header("Authorization", "Bearer $elsewhere").`when`().get("/api/app/cities").then().statusCode(401)

        asApp(email).header("Accept-Language", "id").`when`().get("/api/app/me").then().statusCode(200)
            .body("email", equalTo(email))
            .body("name", equalTo(email))
            .body("language", equalTo("id"))
    }

    @Test
    fun aValidTokenBooksAsTheSameCustomerTheWebSessionWould() {
        val h = hall("SU", 2)
        val email = email("same")

        // The web first, through the code flow and its session cookie, as a browser books.
        val web = NativeItSupport.signIn(email)
        val redirect: String = given().cookies(web).redirects().follow(false)
            .formParam("seatIds", h.seats[0].resourceUnitId.toString())
            .formParam("customerName", "Web Buyer").formParam("customerPhone", "+6280000000001")
            .`when`().post("/halls/${h.pool.resourcePoolId}/showings/${h.session.resourcePoolSessionId}/hold")
            .then().statusCode(200).extract().header("HX-Redirect")
        val webGroup = UUID.fromString(redirect.removePrefix("/orders/"))

        val appGroup = UUID.fromString(asApp(email).contentType("application/json").body(holdBody(listOf(h.seats[1])))
            .`when`().post(h.hold).then().statusCode(200)
            .body("bookingCode", notNullValue())
            .extract().path<String>("groupId"))

        assertEquals(customerOf(webGroup), customerOf(appGroup),
            "one Google account is one customer, whichever surface it books from")
        asApp(email).`when`().get(h.map).then().statusCode(200)
            .body("mine.seatId", containsInAnyOrder(h.seats[0].resourceUnitId.toString(), h.seats[1].resourceUnitId.toString()))
            .body("mine.holdExpiresAt", hasItem(notNullValue()))
    }

    @Test
    fun oneTenantsCustomerNeverSeesAnotherTenantsHolds() {
        val h = hall("SU", 1)
        asApp(email("holder")).contentType("application/json").body(holdBody(h.seats))
            .`when`().post(h.hold).then().statusCode(200)

        // A stranger in the same tenant sees the seat taken, and nothing that names the booking.
        val raw = asApp(email("stranger")).`when`().get(h.map).then().statusCode(200)
            .body("rows[0].seats[0].status", equalTo("held"))
            .body("mine.size()", equalTo(0))
            .extract().asString()
        assertFalse(raw.contains("reservationId"), raw)

        // A customer of another tenant cannot reach the showing at all: RLS takes the tenant from
        // their row, so this tenant's hall does not exist for them.
        val outsider = email("outsider")
        fixture.appUser(fixture.tenant(), outsider, "customer")
        asApp(outsider).`when`().get(h.map).then().statusCode(404).body("error", notNullValue())
        asApp(outsider).contentType("application/json").body(holdBody(h.seats))
            .`when`().post(h.hold).then().statusCode(404)
    }

    @Test
    fun theAttestationIsTheServersSentenceInEitherLanguage_andTheHoldHashesTheOneRead() {
        val h = hall("17+", 1)
        val email = email("a3")

        val english: String = asApp(email).header("Accept-Language", "en").`when`().get(h.map)
            .then().statusCode(200).body("ageGate.restricted", equalTo(true))
            .extract().path("ageGate.statement")
        val indonesian: String = asApp(email).header("Accept-Language", "id").`when`().get(h.map)
            .then().statusCode(200).extract().path("ageGate.statement")
        assertNotEquals(english, indonesian)

        asApp(email).header("Accept-Language", "id").contentType("application/json").body(holdBody(h.seats, false))
            .`when`().post(h.hold).then().statusCode(409)
            .body("error", equalTo("hold_refused"))
            .body("message", notNullValue())
            .body("failedSeatId", nullValue())

        val group = UUID.fromString(asApp(email).header("Accept-Language", "id").contentType("application/json")
            .body(holdBody(h.seats, true))
            .`when`().post(h.hold).then().statusCode(200).extract().path<String>("groupId"))
        val consent = consentOf(group)
        assertEquals(1, consent.size, "the refused hold attested nothing")
        assertEquals("age_attestation:17+:id", consent[0].purpose)
        assertEquals(AgeClassification.hashOf(indonesian), consent[0].policyTextHash,
            "the hash is of the sentence the app showed, which is the server's own")
    }

    @Test
    fun aResentHoldAnswersWithTheFirstCopysOrder_notWithItsOwnSeatsTaken() {
        // Six seats of a 17+ film: a second copy counted as a new hold would also be over the
        // ten-a-day cap, and would write a second attestation for one tick.
        val h = hall("17+", 6)
        val email = email("resend")
        val bearer = token(email)
        val body = holdBody(h.seats, true)

        val first: JsonPath = given().header("Authorization", "Bearer $bearer").contentType("application/json")
            .body(body).`when`().post(h.hold).then().statusCode(200).extract().jsonPath()
        val again: JsonPath = given().header("Authorization", "Bearer $bearer").contentType("application/json")
            .body(body).`when`().post(h.hold).then().statusCode(200).extract().jsonPath()

        assertEquals(first.getString("groupId"), again.getString("groupId"))
        assertEquals(first.getString("bookingCode"), again.getString("bookingCode"))
        assertEquals(1, consentOf(UUID.fromString(first.getString("groupId"))).size)
        given().header("Authorization", "Bearer $bearer").`when`().get(h.map)
            .then().statusCode(200).body("mine.size()", equalTo(6))

        // Only an identical request is the same request: a different set from the same customer,
        // and the same set from anyone else, still go to the EXCLUDE constraint and lose.
        given().header("Authorization", "Bearer $bearer").contentType("application/json")
            .body(holdBody(listOf(h.seats[0]), true)).`when`().post(h.hold).then().statusCode(409)
            .body("failedSeatId", equalTo(h.seats[0].resourceUnitId.toString()))
        asApp(email("rival")).contentType("application/json").body(body)
            .`when`().post(h.hold).then().statusCode(409)
            .body("failedSeatId", equalTo(h.seats[0].resourceUnitId.toString()))
    }

    @Test
    fun theBookingDraftOpensOnce_movesBetweenShowtimes_andIsOnlyItsOwnersToRead() {
        val h = hall("SU", 1)
        val tenant = currentTenant.get()
        val later = fixture.sessionOfFilmAt(tenant, h.pool, h.film,
            h.session.startsAt!!.plus(Duration.ofHours(3)), "on_sale")
        val email = email("draft")
        val bearer = token(email)
        val open = "{\"sessionId\":\"${h.session.resourcePoolSessionId}\"}"

        val ref: String = given().header("Authorization", "Bearer $bearer").contentType("application/json").body(open)
            .`when`().post("/api/app/bookings").then().statusCode(200).extract().path("bookingRef")
        given().header("Authorization", "Bearer $bearer").contentType("application/json").body(open)
            .`when`().post("/api/app/bookings").then().statusCode(200).body("bookingRef", equalTo(ref))

        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/bookings/$ref")
            .then().statusCode(200)
            .body("title", equalTo(h.film.title))
            .body("seatMap.sessionId", equalTo(h.session.resourcePoolSessionId.toString()))
            .body("dates.times.flatten().size()", equalTo(2))

        val moved = "{\"sessionId\":\"${later.resourcePoolSessionId}\"}"
        given().header("Authorization", "Bearer $bearer").contentType("application/json").body(moved)
            .`when`().post("/api/app/bookings/$ref/showtime").then().statusCode(200)
            .body("bookingRef", equalTo(ref))
            .body("seatMap.sessionId", equalTo(later.resourcePoolSessionId.toString()))
        // A second booking on the first showtime, moved onto the later one the first booking is on:
        // the answer is that booking, and the app follows the ref it is given.
        val second: String = given().header("Authorization", "Bearer $bearer").contentType("application/json").body(open)
            .`when`().post("/api/app/bookings").then().statusCode(200).extract().path("bookingRef")
        given().header("Authorization", "Bearer $bearer").contentType("application/json").body(moved)
            .`when`().post("/api/app/bookings/$second/showtime").then().statusCode(200)
            .body("bookingRef", equalTo(ref))
        given().header("Authorization", "Bearer $bearer").contentType("application/json").body("{\"date\":\"tomorrow\"}")
            .`when`().post("/api/app/bookings/$ref/date").then().statusCode(400)

        asApp(email("nosy")).`when`().get("/api/app/bookings/$ref").then().statusCode(403)
        // A cross-site form carries the session cookie when the tenant is off, never JSON.
        given().header("Authorization", "Bearer $bearer").formParam("sessionId", h.session.resourcePoolSessionId.toString())
            .`when`().post("/api/app/bookings").then().statusCode(415)
    }

    // --- M2: the order, payment, ticket and profile --------------------------------------------

    private fun json(bearer: String): RequestSpecification =
        given().header("Authorization", "Bearer $bearer").contentType("application/json")

    private fun holdAs(bearer: String, h: Hall, seats: List<ResourceUnit> = h.seats): String =
        json(bearer).body(holdBody(seats)).`when`().post(h.hold).then().statusCode(200).extract().path("groupId")

    @Test
    fun anOrderIsItsOwnCustomersToRead_andToActOn() {
        val h = hall("SU", 1)
        val owner = token(email("owner"))
        val group = holdAs(owner, h)

        given().header("Authorization", "Bearer $owner").`when`().get("/api/app/orders").then().statusCode(200)
            .body("active.groupId", hasItem(group))
            .body("staff", equalTo(false))
        given().header("Authorization", "Bearer $owner").`when`().get("/api/app/orders/$group").then().statusCode(200)
            .body("lines[0].seatLabel", equalTo("A1"))
            .body("lines[0].status", equalTo("held"))
            .body("lines[0].holdExpiresAt", notNullValue())
            .body("lines[0].cancellable", equalTo(true))
            .body("lines[0].admissionToken", nullValue())
            .body("total", equalTo(45000.0f))
            .body("payment", nullValue())
            .body("canPay", equalTo(true)).body("canConfirm", equalTo(true)).body("poll", equalTo(false))

        // Another customer of the same tenant: RLS lets the row through, BookingService does not.
        val stranger = token(email("stranger"))
        given().header("Authorization", "Bearer $stranger").`when`().get("/api/app/orders").then().statusCode(200)
            .body("active.groupId", not(hasItem(group)))
        given().header("Authorization", "Bearer $stranger").`when`().get("/api/app/orders/$group").then().statusCode(403)
        val reservation: String = given().header("Authorization", "Bearer $owner").`when`()
            .get("/api/app/orders/$group").then().extract().path("lines[0].reservationId")
        for (action in listOf("pay", "confirm", "items/$reservation/cancel")) {
            json(stranger).body("{}").`when`().post("/api/app/orders/$group/$action").then().statusCode(403)
                .body("error", equalTo("forbidden"))
        }
        val outsider = email("outsider")
        fixture.appUser(fixture.tenant(), outsider, "customer")
        asApp(outsider).`when`().get("/api/app/orders/$group").then().statusCode(404)

        // Nothing the stranger sent changed the order.
        given().header("Authorization", "Bearer $owner").`when`().get("/api/app/orders/$group").then()
            .body("lines[0].status", equalTo("held")).body("payment", nullValue())
        // A cross-site form carries the session cookie when the tenant is off, never JSON.
        given().header("Authorization", "Bearer $owner").formParam("x", "y")
            .`when`().post("/api/app/orders/$group/confirm").then().statusCode(415)
    }

    @Test
    fun aStaffAccountsOrdersSayStaff_soTheAppShowsTheWebsStaffEmptyState() {
        val staff = email("staff")
        fixture.appUser(currentTenant.get(), staff, "tenant_staff")
        asApp(staff).`when`().get("/api/app/orders").then().statusCode(200)
            .body("staff", equalTo(true))
            .body("active.size()", equalTo(0))
            .body("past.size()", equalTo(0))
    }

    @Test
    fun aResentPayShowsTheNewestCharge_andOnceSettledAnswersWithThePaidOrder() {
        val h = hall("SU", 1)
        val bearer = token(email("pay"))
        val group = holdAs(bearer, h)

        val first: JsonPath = json(bearer).body("{}").`when`().post("/api/app/orders/$group/pay")
            .then().statusCode(200)
            .body("payment.status", equalTo("pending"))
            .body("payment.qrisContent", notNullValue())
            .body("poll", equalTo(true)).body("canPay", equalTo(false)).body("canConfirm", equalTo(false))
            .extract().jsonPath()
        // What the web's double submit does too: a second charge, and the page shows the newest.
        // The first copy's answer is the one that was lost, so its QR was never on a screen.
        val again: JsonPath = json(bearer).body("{}").`when`().post("/api/app/orders/$group/pay")
            .then().statusCode(200).body("poll", equalTo(true)).extract().jsonPath()
        assertNotEquals(first.getString("payment.paymentId"), again.getString("payment.paymentId"))
        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/orders/$group").then()
            .body("payment.paymentId", equalTo(again.getString("payment.paymentId")))

        paymentService.settleCash(UUID.fromString(group))
        json(bearer).body("{}").`when`().post("/api/app/orders/$group/pay").then().statusCode(200)
            .body("payment.status", equalTo("paid"))
            .body("poll", equalTo(false)).body("canPay", equalTo(false))
            .body("lines[0].status", equalTo("confirmed"))
            .body("lines[0].admissionToken", notNullValue())
    }

    @Test
    fun aResentConfirmAnswersWithTheConfirmedOrderAndItsTicket_withNoWalletLinkWhenUnset() {
        val h = hall("SU", 1)
        val bearer = token(email("confirm"))
        val group = holdAs(bearer, h)

        val first: JsonPath = json(bearer).body("{}").`when`().post("/api/app/orders/$group/confirm")
            .then().statusCode(200)
            .body("status", equalTo("confirmed"))
            .body("lines[0].status", equalTo("confirmed"))
            .body("lines[0].holdExpiresAt", nullValue())
            .body("lines[0].admissionToken", notNullValue())
            .body("lines[0].walletUrl", nullValue())
            .body("canPay", equalTo(false)).body("canConfirm", equalTo(false))
            .extract().jsonPath()
        val raw = json(bearer).body("{}").`when`().post("/api/app/orders/$group/confirm")
            .then().statusCode(200).body("status", equalTo("confirmed"))
            .body("lines[0].admissionToken", equalTo(first.getString("lines[0].admissionToken")))
            .extract().asString()
        assertFalse(raw.contains("pay.google.com"), raw)
    }

    @Test
    fun aResentCancelChangesNothing_andAPaidSeatIsRefusedWithTheWebsSentence() {
        val h = hall("SU", 2)
        val bearer = token(email("cancel"))
        val group = holdAs(bearer, h)
        val ids: List<String> = given().header("Authorization", "Bearer $bearer").`when`()
            .get("/api/app/orders/$group").then().extract().path("lines.reservationId")
        val cancel = "/api/app/orders/$group/items/${ids[0]}/cancel"

        json(bearer).body("{}").`when`().post(cancel).then().statusCode(200)
            .body("lines.find { it.reservationId == '${ids[0]}' }.status", equalTo("cancelled"))
            .body("lines.find { it.reservationId == '${ids[0]}' }.cancellable", equalTo(false))
            .body("total", equalTo(45000.0f))
        json(bearer).body("{}").`when`().post(cancel).then().statusCode(200)
            .body("lines.find { it.reservationId == '${ids[0]}' }.status", equalTo("cancelled"))
            .body("total", equalTo(45000.0f))

        paymentService.settleCash(UUID.fromString(group))
        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/orders/$group").then()
            .body("lines.cancellable", everyItem(equalTo(false)))
        json(bearer).header("Accept-Language", "id").body("{}")
            .`when`().post("/api/app/orders/$group/items/${ids[1]}/cancel").then().statusCode(403)
            .body("message", equalTo(LanguagePreference.messagesIn(Language.INDONESIAN).cancelPaidByStaffOnly()))
        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/orders/$group").then()
            .body("lines.find { it.reservationId == '${ids[1]}' }.status", equalTo("confirmed"))
    }

    @Test
    fun aResentDateOfBirthWritesTheSameValue_andARefusedOneCarriesTheBannersSentence() {
        val bearer = token(email("dob"))
        val id = LanguagePreference.messagesIn(Language.INDONESIAN)

        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/profile").then().statusCode(200)
            .body("dateOfBirth", nullValue()).body("age", nullValue()).body("hasBooked", equalTo(false))
        repeat(2) {
            json(bearer).body("{\"dateOfBirth\":\"1990-05-01\"}").`when`().post("/api/app/profile")
                .then().statusCode(204)
        }
        // The first write made this account's customer row; the re-read is a fresh identity.
        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/profile").then()
            .body("dateOfBirth", equalTo("1990-05-01"))
            .body("age", notNullValue())
            .body("hasBooked", equalTo(true))

        json(bearer).header("Accept-Language", "id").body("{\"dateOfBirth\":\"2999-01-01\"}")
            .`when`().post("/api/app/profile").then().statusCode(400)
            .body("message", equalTo(id.profileDateInFuture()))
        json(bearer).header("Accept-Language", "id").body("{\"dateOfBirth\":\"01/05/1990\"}")
            .`when`().post("/api/app/profile").then().statusCode(400)
            .body("message", equalTo(id.profileDateFormat()))
        // Blank clears it, as the web's empty date field does.
        json(bearer).body("{\"dateOfBirth\":\"\"}").`when`().post("/api/app/profile").then().statusCode(204)
        given().header("Authorization", "Bearer $bearer").`when`().get("/api/app/profile").then()
            .body("dateOfBirth", nullValue())
    }
}
