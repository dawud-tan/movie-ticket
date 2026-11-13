@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.web.api

import com.protek.booking.i18n.Language
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.i18n.StatusLabels
import com.protek.booking.json.UuidSerializer
import com.protek.booking.security.Roles
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.SeatAvailabilityNotifier
import io.quarkus.websockets.next.CloseReason
import io.quarkus.websockets.next.OnClose
import io.quarkus.websockets.next.OnOpen
import io.quarkus.websockets.next.UserData
import io.quarkus.websockets.next.WebSocket
import io.quarkus.websockets.next.WebSocketConnection
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.Semaphore

/*
 * `/api/app/live`: the app's seat map and order page take their updates from these two sockets
 * while on screen, in place of the web's 3 s polls.
 * The Qute pages keep polling.
 *
 * One socket per screen, with the subscription in the path, so the client sends nothing and there
 * is no message to parse. A frame is JSON. Every frame is sent after the change committed, by
 * [SeatAvailabilityNotifier], which keys each subscriber by the tenant resolved here and never by
 * anything the client sent. A refused subscription is closed with 1008 before anything is sent.
 */

/** One seat's status, with its label in the language of the socket's `Accept-Language`. */
@Serializable
data class LiveSeat(val seatId: UUID, val status: String, val statusLabel: String)

/** The first frame carries every seat of the showing; each later one only the seats that changed. */
@Serializable
data class LiveSeats(val seats: List<LiveSeat>)

/** The order's status, its newest payment's, and each seat's by reservation id. The app reads the
 * order again when any of them differs from its screen. */
@Serializable
data class LiveOrder(val status: String?, val paymentStatus: String?, val seats: Map<String, String>)

/** Of the DB pool's 10 connections (`quarkus.datasource.jdbc.max-size`), what anonymous opens may hold. */
private const val OPENING_READS = 4

private val LOG: Logger = Logger.getLogger("com.protek.booking.web.api.AppLiveSocket")
private val LEAVE = UserData.TypedKey<Runnable>("leave")
private val REFUSED = CloseReason(1008, "refused")
private val BUSY = CloseReason(1013, "try again later")

// Fire-and-forget on purpose, the one exception to the tree's blocking style: SeatAvailabilityNotifier
// calls this from its executor while holding the showing's monitor, so sendTextAndAwait would let
// one phone with a full TCP window stall every frame and every open on that showing.
private fun send(connection: WebSocketConnection, text: String) {
    connection.sendText(text).subscribe().with({}, { e -> LOG.debugf(e, "Frame not sent on %s", connection.id()) })
}

/** Registers [leave] for the close callback, and runs it now if the socket already closed while
 * the subscription was being made. `remove` makes it run once either way. */
private fun leaveOnClose(connection: WebSocketConnection, leave: Runnable) {
    connection.userData().put(LEAVE, leave)
    if (connection.isClosed) {
        connection.userData().remove(LEAVE)?.run()
    }
}

private fun uuidOrNull(value: String?): UUID? =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * Mirrors the seat map's `/fragment` poll (`GET /halls/{poolId}/showings/{sessionId}` and the
 * app's `GET /api/app/halls/{poolId}/showings/{sessionId}`). Anonymous, as browsing is: the
 * tenant is `publicCatalogueId()`, and a showing that tenant does not have is refused.
 *
 * **At most [OPENING_READS] opens read at once**, and the rest are closed with 1013 rather than
 * queued. An open reads the showing under that showing's monitor, so anonymous opens on one
 * showing run one at a time. Queued, a flood of them would hold every worker thread, and every
 * JDBC endpoint with them. A refused app falls back to its poll and tries again in 30 s.
 */
@WebSocket(path = "/api/app/live/showings/{poolId}/{sessionId}")
@RunOnVirtualThread
class ShowingLiveSocket {

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var statusLabels: StatusLabels

    /** Public so a test can take every permit. */
    val openings = Semaphore(OPENING_READS)

    @OnOpen
    fun open(connection: WebSocketConnection) {
        val poolId = uuidOrNull(connection.pathParam("poolId"))
        val sessionId = uuidOrNull(connection.pathParam("sessionId"))
        // The handshake's language, kept: a frame is sent long after the request that set it.
        val msg = LanguagePreference.messagesIn(
            Language.fromAcceptLanguage(connection.handshakeRequest().header(HttpHeaders.ACCEPT_LANGUAGE)))
        val subscriber: (Map<UUID, String>) -> Unit = { seats ->
            send(connection, Json.encodeToString(LiveSeats(seats.map { (id, status) ->
                LiveSeat(id, status, statusLabels.of(status, msg)) })))
        }
        val tenantId = currentTenant.publicCatalogueId()
        if (poolId == null || sessionId == null) {
            connection.closeAndAwait(REFUSED)
            return
        }
        if (!openings.tryAcquire()) {
            connection.closeAndAwait(BUSY)
            return
        }
        val subscribed = try {
            notifier.subscribeToShowing(tenantId, poolId, sessionId, subscriber)
        } finally {
            openings.release()
        }
        if (!subscribed) {
            connection.closeAndAwait(REFUSED)
            return
        }
        leaveOnClose(connection) { notifier.unsubscribeFromShowing(tenantId, poolId, sessionId, subscriber) }
    }

    @OnClose
    fun close(connection: WebSocketConnection) {
        connection.userData().remove(LEAVE)?.run()
    }
}

/**
 * Mirrors the order page's `/fragment` poll (`GET /orders/{groupId}` and the app's
 * `GET /api/app/orders/{groupId}`), with the same roles. The bearer is checked at the upgrade, and
 * the order is checked as the page checks it: `BookingService.getGroupDetail` refuses another
 * customer's order, and RLS hides another tenant's. Only then is the socket registered, under the
 * tenant of the verified identity.
 */
@WebSocket(path = "/api/app/live/orders/{groupId}")
@RunOnVirtualThread
@RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
class OrderLiveSocket {

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var bookingService: BookingService

    @OnOpen
    fun open(connection: WebSocketConnection) {
        val groupId = uuidOrNull(connection.pathParam("groupId")) ?: return connection.closeAndAwait(REFUSED)
        val detail = try {
            bookingService.getGroupDetail(groupId)
        } catch (e: WebApplicationException) {
            // 403 for another customer's order, 404 for one RLS hides: the page's own refusals.
            connection.closeAndAwait(REFUSED)
            return
        }
        val tenantId = currentTenant.id()
        // Its showing, so every seat event there (a hold expiring, a cancel, a confirm) reaches it.
        val sessionId = detail.showing?.resourcePoolSessionId
        val subscriber: (SeatAvailabilityNotifier.OrderStatus) -> Unit = { status ->
            send(connection, Json.encodeToString(LiveOrder(status.status, status.paymentStatus,
                status.seats.mapKeys { it.key.toString() })))
        }
        notifier.subscribeToOrder(tenantId, groupId, sessionId, subscriber)
        leaveOnClose(connection) { notifier.unsubscribeFromOrder(tenantId, groupId, sessionId, subscriber) }
    }

    @OnClose
    fun close(connection: WebSocketConnection) {
        connection.userData().remove(LEAVE)?.run()
    }
}
