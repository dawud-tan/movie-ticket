package com.protek.booking.push

import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.repository.OrderWatchRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.OrderPush
import com.protek.booking.service.PushNotificationService
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Notify me about this order": `POST`/`DELETE /orders/{groupId}/push`, its app twin
 * `/api/app/orders/{groupId}/push`, and [OrderPush]'s sends. The refund half's wiring is in
 * `SnapNotificationEndpointTest`, beside refund checks, because that is where a refund runs.
 */
@QuarkusTest
class OrderPushTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var watches: OrderWatchRepository
    @Inject
    lateinit var orderPush: OrderPush

    private fun book(customer: Customer, seat: String): ReservationGroup {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, seat, seat.substring(0, 1), 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        actAs(customer)
        val held = bookingService.hold(pool.resourcePoolId, showing.resourcePoolSessionId, listOf(unit.resourceUnitId!!),
            "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(held.success, "setup hold failed: " + held.failureReason)
        return held.group!!
    }

    private fun actAs(customer: Customer) =
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

    private fun watchers(group: ReservationGroup) = db.inTenantTransaction(group.tenantId!!) { c ->
        watches.findByGroup(c, group.tenantId, group.reservationGroupId!!)
    }

    private fun watch(group: ReservationGroup, token: String, language: String) =
        db.inTenantTransaction(group.tenantId!!) { c ->
            watches.register(c, group.tenantId, group.reservationGroupId!!, token, language)
        }

    private fun post(path: String, token: String, language: String = "en") =
        given().contentType(ContentType.JSON).header("Accept-Language", language)
            .body("""{"token":"$token"}""").`when`().post(path)

    private fun delete(path: String, token: String) =
        given().contentType(ContentType.JSON).body("""{"token":"$token"}""").`when`().delete(path)

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun theBuyerTurnsItOnInTheirLanguage_againIsTheSameWatch_andTheSameControlTurnsItOff() {
        val me = fixture.customer(currentTenant.get())
        val order = book(me, "N1")
        val path = "/orders/${order.reservationGroupId}/push"

        post(path, "fid-web", "id").then().statusCode(204)
        post(path, "fid-web", "id").then().statusCode(204)
        assertEquals(listOf(OrderWatchRepository.Watcher("fid-web", "id")), watchers(order))

        // The app's twin lands on the same row: one service method behind both.
        post("/api/app/orders/${order.reservationGroupId}/push", "fid-web", "en").then().statusCode(204)
        assertEquals(listOf(OrderWatchRepository.Watcher("fid-web", "en")), watchers(order))

        delete(path, "fid-web").then().statusCode(204)
        assertEquals(emptyList<OrderWatchRepository.Watcher>(), watchers(order))
        delete("/api/app/orders/${order.reservationGroupId}/push", "fid-web").then().statusCode(204)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun anotherCustomerAndStaffAreRefused_andNoWatchIsWritten() {
        val tenant = currentTenant.get()
        val order = book(fixture.customer(tenant), "N2")
        val path = "/orders/${order.reservationGroupId}/push"

        actAs(fixture.customer(tenant))
        post(path, "fid-other").then().statusCode(403)
        post("/api/app/orders/${order.reservationGroupId}/push", "fid-other").then().statusCode(403)
        delete(path, "fid-other").then().statusCode(403)

        // Staff open any order's page, but the buyer's news is not theirs to receive.
        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)
        post(path, "fid-staff").then().statusCode(403)
        assertEquals(emptyList<OrderWatchRepository.Watcher>(), watchers(order))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aCancelledShowingIsPushedAfterCommit_toEachWatcherInTheirOwnLanguage() {
        val order = book(fixture.customer(currentTenant.get()), "N3")
        watch(order, "fid-en", "en")
        watch(order, "fid-id", "id")
        val showing = db.inTenantTransaction(order.tenantId!!) { c ->
            c.prepareStatement("SELECT resource_pool_session_id FROM reservation WHERE reservation_group_id = ?").use {
                it.setObject(1, order.reservationGroupId); it.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
            }
        }
        // What the send would see if it ran inside the cancel's transaction: the showing still on sale.
        val seen = CopyOnWriteArrayList<String>()
        val push = object : PushNotificationService() {
            override fun isConfigured() = true
            override fun sendToAll(fcmTokens: List<String>?, title: String?, body: String?,
                                   data: Map<String, String>?, link: String?): BatchResult {
                val status = db.inTenantTransaction(order.tenantId) { c ->
                    c.prepareStatement("SELECT status FROM resource_pool_session WHERE resource_pool_session_id = ?").use {
                        it.setObject(1, showing); it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                    }
                }
                if (data?.get("type") == "order") seen += "${fcmTokens} $status $title | $body | ${data["event"]} ${data["groupId"]}"
                return BatchResult(fcmTokens!!.size, listOf(), listOf())
            }
        }
        QuarkusMock.installMockForType(push, PushNotificationService::class.java)
        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)

        bookingService.cancelShowing(showing)

        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (seen.size < 2 && System.nanoTime() < until) Thread.sleep(20)
        val code = order.bookingCode
        assertEquals(setOf(
            "[fid-en] cancelled Showing cancelled | The cinema cancelled the showing of order $code. Open it to see what comes back to you. | SHOWING_CANCELLED ${order.reservationGroupId}",
            "[fid-id] cancelled Jadwal tayang dibatalkan | Bioskop membatalkan jadwal tayang pesanan $code. Buka pesanan untuk melihat apa yang dikembalikan kepada Anda. | SHOWING_CANCELLED ${order.reservationGroupId}",
        ), seen.toSet())
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aPushThatFailsLeavesTheCancelCommitted() {
        val order = book(fixture.customer(currentTenant.get()), "N4")
        watch(order, "fid-x", "en")
        val recorder = RecordingPushService().withTotalFailure(RuntimeException("FCM is down"))
        QuarkusMock.installMockForType(recorder, PushNotificationService::class.java)
        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)
        val showing = bookingService.getGroupDetail(order.reservationGroupId).showing!!.resourcePoolSessionId

        assertEquals(1, bookingService.cancelShowing(showing))

        recorder.awaitAttempts(1, Duration.ofSeconds(10))
        val detail = bookingService.getGroupDetail(order.reservationGroupId)
        assertEquals("cancelled", detail.showing!!.status)
        assertEquals("cancelled", detail.lines!!.single().reservation!!.status)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aDeadTokenIsPruned_theLiveOneKept_andTheRefundIsWordedWithItsAmount() {
        val tenant = currentTenant.get()
        val order = book(fixture.customer(tenant), "N5")
        watch(order, "fid-live", "en")
        watch(order, "fid-dead", "en")
        val recorder = RecordingPushService().withDeadTokens("fid-dead")
        QuarkusMock.installMockForType(recorder, PushNotificationService::class.java)

        orderPush.deliver(tenant.tenantId, order.reservationGroupId!!, OrderPush.Event.REFUND_SENT, BigDecimal("50000"))

        val sent = recorder.sends.single()
        assertEquals(setOf("fid-live", "fid-dead"), sent.tokens.toSet())
        assertEquals("Refund sent", sent.title)
        assertEquals("Rp 50.000 was returned to your payment app for order ${order.bookingCode}.", sent.body)
        assertEquals(sent.body, sent.data["body"], "the phone shows the same words, from data")
        assertEquals(listOf(OrderWatchRepository.Watcher("fid-live", "en")), watchers(order))
    }
}
