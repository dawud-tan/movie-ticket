package com.protek.booking.web.api

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.payment.PaymentService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.SeatAvailabilityNotifier
import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.TestFixture
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.websockets.next.BasicWebSocketConnector
import io.quarkus.websockets.next.CloseReason
import io.quarkus.websockets.next.WebSocketClientConnection
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `/api/app/live` (mobile M7) over real sockets: who may subscribe to which showing and order, and
 * that a frame is sent after the commit without the commit ever depending on it. Bearer tokens
 * come from [FakeGoogleOidc], as in `AppApiSignedInTest`, so the upgrade's check is the real one.
 */
@QuarkusTest
@WithTestResource(FakeGoogleOidc::class)
class AppLiveSocketTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var notifier: SeatAvailabilityNotifier
    @Inject
    lateinit var paymentService: PaymentService
    @Inject
    lateinit var showingSocket: ShowingLiveSocket

    @TestHTTPResource("/")
    lateinit var base: URI

    @ConfigProperty(name = "quarkus.oidc.customer-app.auth-server-url")
    lateinit var issuer: String

    private data class Hall(val tenant: Tenant, val pool: ResourcePool, val session: ResourcePoolSession,
                            val seats: List<ResourceUnit>) {
        val live get() = "/api/app/live/showings/${pool.resourcePoolId}/${session.resourcePoolSessionId}"
        val hold get() = "/api/app/halls/${pool.resourcePoolId}/showings/${session.resourcePoolSessionId}/hold"
    }

    private fun hall(tenant: Tenant, seatCount: Int): Hall {
        val site = fixture.site(tenant, "Live Cinema " + System.nanoTime(), "Rekaan")
        val pool = fixture.poolAtSite(tenant, site, "Studio 1")
        val seats = (1..seatCount).map { fixture.unit(tenant, pool, "A$it", "A", it) }
        val film = fixture.film(tenant, "Live Film " + System.nanoTime(), "SU")
        val session = fixture.sessionOfFilm(tenant, pool, film, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("45000"))
        return Hall(tenant, pool, session, seats)
    }

    private fun email(who: String) = "live-$who-${System.nanoTime()}@example.test"

    /** An ID token from the fake provider's `/token`, as the phone gets one. */
    private fun token(email: String): String {
        val form = "code=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
            "&audience=" + URLEncoder.encode(FakeGoogleOidc.CLIENT_ID, StandardCharsets.UTF_8)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$issuer/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString())
        return JsonPath.from(response.body()).getString("id_token")
    }

    private fun holdAs(bearer: String, h: Hall, vararg seats: ResourceUnit): String =
        given().header("Authorization", "Bearer $bearer").contentType("application/json")
            .body("""{"seatIds":[${seats.joinToString { "\"" + it.resourceUnitId + "\"" }}],"customerName":"Live Buyer",""" +
                """"customerPhone":"+6280000000000","ageAttested":false}""")
            .`when`().post(h.hold).then().statusCode(200).extract().path("groupId")

    /** One socket as the app holds it: its frames in arrival order, and how it closed. */
    private class Socket(val connection: WebSocketClientConnection, val frames: LinkedBlockingQueue<String>,
                         val closed: CompletableFuture<CloseReason>) {
        fun next(): JsonPath = JsonPath.from(frames.poll(10, TimeUnit.SECONDS) ?: fail("no frame within 10 s"))
        fun closeCode(): Int = closed.get(10, TimeUnit.SECONDS).code
    }

    private fun open(path: String, bearer: String? = null, language: String = "en"): Socket {
        val frames = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<CloseReason>()
        val connector = BasicWebSocketConnector.create().baseUri(base).path(path)
            .addHeader("Accept-Language", language)
            .onTextMessage { _, text -> frames.add(text) }
            .onClose { _, reason -> closed.complete(reason) }
        bearer?.let { connector.addHeader("Authorization", "Bearer $it") }
        return Socket(connector.connectAndAwait(), frames, closed)
    }

    @Test
    fun aSeatDeltaReachesTheShowingsOwnTenant_andNeverAnotherTenantsSocket() {
        val h = hall(currentTenant.get(), 2)
        val watching = open(h.live, language = "id")
        val first = watching.next()
        assertEquals(2, first.getList<Any>("seats").size, "the first frame is every seat")
        assertEquals(listOf("available", "available"), first.getList<String>("seats.status"))
        assertEquals("Tersedia", first.getString("seats[0].statusLabel"), "labels follow the socket's language")

        // Another tenant's customer: the tenant is their row's, so this tenant's showing does not
        // exist for them, whatever the path names. Refused before any frame.
        val outsider = email("outsider")
        val otherTenant = fixture.tenant()
        fixture.appUser(otherTenant, outsider, "customer")
        val outsiderToken = token(outsider)
        val refused = open(h.live, bearer = outsiderToken)
        assertEquals(1008, refused.closeCode())
        assertTrue(refused.frames.isEmpty(), "a refused socket is told nothing")

        // Their own tenant's showing is theirs to watch, and hears nothing of this one's sales.
        val theirs = hall(otherTenant, 1)
        val elsewhere = open(theirs.live, bearer = outsiderToken)
        assertEquals(theirs.seats[0].resourceUnitId.toString(), elsewhere.next().getString("seats[0].seatId"))

        holdAs(token(email("buyer")), h, h.seats[0])
        val delta = watching.next()
        assertEquals(listOf(h.seats[0].resourceUnitId.toString()), delta.getList<String>("seats.seatId"),
            "only the seat that changed")
        assertEquals("held", delta.getString("seats[0].status"))
        assertEquals("Ditahan", delta.getString("seats[0].statusLabel"))
        assertNull(elsewhere.frames.poll(1, TimeUnit.SECONDS), "another tenant's socket hears nothing")
    }

    @Test
    fun aSubscriberThatThrowsOrDisconnects_leavesTheBookingCommitted_andTheOthersServed() {
        val h = hall(currentTenant.get(), 2)
        val tenantId = h.tenant.tenantId
        val poolId = h.pool.resourcePoolId!!
        val sessionId = h.session.resourcePoolSessionId!!
        val calls = AtomicInteger()
        val throwing: (Map<UUID, String>) -> Unit = { calls.incrementAndGet(); throw IllegalStateException("socket gone") }
        assertTrue(notifier.subscribeToShowing(tenantId, poolId, sessionId, throwing))
        val gone = open(h.live)
        gone.next()
        val served = open(h.live)
        served.next()
        // Gone just before the change: the fan-out finds it unsubscribed or finds its send failing,
        // depending on which the server reaches first, and neither may matter to the booking.
        gone.connection.closeAndAwait()

        val buyer = token(email("buyer"))
        val group = holdAs(buyer, h, h.seats[1])

        assertEquals("held", served.next().getString("seats[0].status"), "the healthy socket is still served")
        assertEquals(2, calls.get(), "the throwing subscriber was in both fan-outs")
        given().header("Authorization", "Bearer $buyer").`when`().get("/api/app/orders/$group").then()
            .statusCode(200).extract().path<String>("lines[0].status").let {
                assertEquals("held", it, "the booking committed regardless")
            }
        notifier.unsubscribeFromShowing(tenantId, poolId, sessionId, throwing)
    }

    @Test
    fun anOrderIsItsOwnCustomersToWatch_andEverySeatOrPaymentChangeSendsItsState() {
        val h = hall(currentTenant.get(), 2)
        val owner = token(email("owner"))
        val group = holdAs(owner, h, h.seats[0], h.seats[1])
        val live = "/api/app/live/orders/$group"

        // No bearer: refused at the upgrade, as the order page refuses an anonymous reader.
        assertThrows(Exception::class.java) { open(live) }
        // Another customer of the same tenant: RLS lets the row through, BookingService does not.
        val stranger = open(live, bearer = token(email("stranger")))
        assertEquals(1008, stranger.closeCode())
        assertTrue(stranger.frames.isEmpty(), "a refused socket is told nothing")
        // Another tenant's customer: RLS hides the order.
        val outsider = email("outsider")
        fixture.appUser(fixture.tenant(), outsider, "customer")
        assertEquals(1008, open(live, bearer = token(outsider)).closeCode())

        val watching = open(live, bearer = owner)
        val first = watching.next()
        assertEquals("pending", first.getString("status"))
        assertNull(first.getString("paymentStatus"))
        assertEquals(listOf("held", "held"), first.getMap<String, String>("seats").values.toList())

        // A seat change that leaves the group pending still reaches it: what a lapsed hold does.
        val (cancelled, kept) = first.getMap<String, String>("seats").keys.toList()
        given().header("Authorization", "Bearer $owner").contentType("application/json").body("{}")
            .`when`().post("/api/app/orders/$group/items/$cancelled/cancel").then().statusCode(200)
        val afterCancel = watching.next()
        assertEquals("pending", afterCancel.getString("status"))
        assertEquals(mapOf(cancelled to "cancelled", kept to "held"), afterCancel.getMap<String, String>("seats"))

        paymentService.settleCash(UUID.fromString(group))
        val settled = watching.next()
        assertEquals("confirmed", settled.getString("status"))
        assertEquals("paid", settled.getString("paymentStatus"))
        assertEquals("confirmed", settled.getMap<String, String>("seats")[kept])
    }

    @Test
    fun opensBeyondTheCapAreToldToComeBackLater_ratherThanQueuedOnTheWorkers() {
        val h = hall(currentTenant.get(), 1)
        val taken = showingSocket.openings.drainPermits()
        try {
            val busy = open(h.live)
            assertEquals(1013, busy.closeCode())
            assertTrue(busy.frames.isEmpty())
        } finally {
            showingSocket.openings.release(taken)
        }
        assertEquals("available", open(h.live).next().getString("seats[0].status"), "a permit back, an open again")
    }
}
