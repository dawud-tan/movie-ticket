package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePoolWatch
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.seo.PublicUrls
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The notify-on-commit step: after a reservation's status changes, look up who is watching
 * that seat map and push to them.
 *
 * **Why this is a single seam rather than a hook per call site.** Six code paths change
 * `reservation.status` — hold, confirm, cancel, webhook settlement, cash settlement, and
 * the expiry sweep — and every one of them wants the same three things: find the watchers of
 * one pool/session, batch a push to them, prune the subscriptions FCM says are gone. Duplicating
 * that six times is six places for the pruning half to be forgotten.
 *
 * **Why it lives here and not in the audit trigger.** The audit
 * trigger stays logging-only, with notify-on-commit living in the request handlers that already
 * change reservation.status. A database trigger cannot make an outbound HTTPS call to FCM
 * without something like a background worker or an extension, and a trigger that could would be
 * firing network I/O inside the transaction that is holding row locks.
 *
 * **After commit, off the request thread.** Callers invoke this once their transaction has
 * returned — so the rows the watchers are about to be told about are already visible to anyone
 * who reads them — and the work is handed to a [ManagedExecutor] so the
 * send does not block the response the customer is actually waiting on.
 * A customer holding a seat should not wait on an FCM round trip to other people's browsers.
 *
 * **And to the app's open sockets** (`/api/app/live`): the same call also hands the
 * seats that changed to a showing's sockets, and the order's state to the sockets of each order
 * on that showing, held here in memory. A payment status that moves no seat (an expired QR) is
 * [notifyOrder]'s. One JVM on one box, so there is no other node to fan out to. A subscriber is
 * keyed by the tenant the socket's own request resolved, and an event reaches only the key built
 * from the `tenantId` its caller passes, so one tenant's change never reaches another tenant's
 * socket even for a colliding id. The push kill switch below is FCM's and does not stop these:
 * a socket has no third party to storm.
 */
@ApplicationScoped
class SeatAvailabilityNotifier {

    /** Reason codes sent in the push payload, so the client can decide what to do with each
     * rather than parsing prose meant for humans. */
    enum class Reason(internal val title: String, internal val body: String) {
        SEATS_HELD("Seats just taken", "Someone selected seats for this showing."),
        SEATS_CONFIRMED("Seats confirmed", "Seats for this showing were just confirmed."),
        SEATS_RELEASED("Seats available again", "Seats for this showing were just released."),
        HOLDS_EXPIRED("Seats available again", "Held seats for this showing were released."),
        /* The showtime viability rule. Deliberately not folded into HOLDS_EXPIRED even though
         * both end with seats becoming free: "the seats are back" and "this screening is not
         * happening" are different news, and a watcher told the first would refresh the map and
         * keep waiting for a showing that no longer exists. */
        SHOWING_CANCELLED("Showing cancelled",
                "This screening was cancelled because too few seats were sold."),
        /* Staff's "Cancel showing": it sent the viability sweep's reason, which told a
         * watcher too few seats were sold when the cinema had simply cancelled. Its own name rather
         * than a new body on the old one, because the app words a notification from the name
         * (the Android app, which this repository does not carry) and must tell the two apart too; the web reads only title and body. */
        SHOWING_CANCELLED_BY_CINEMA("Showing cancelled", "This screening was cancelled by the cinema."),
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var watchRepository: ResourcePoolWatchRepository
    @Inject
    lateinit var pushService: PushNotificationService
    @Inject
    lateinit var executor: ManagedExecutor
    @Inject
    lateinit var sessionRepository: ResourcePoolSessionRepository
    @Inject
    lateinit var reservationRepository: ReservationRepository
    @Inject
    lateinit var groupRepository: ReservationGroupRepository
    @Inject
    lateinit var paymentRepository: PaymentRepository
    @Inject
    lateinit var publicUrls: PublicUrls

    /** One showing's sockets and the seats they were last told, which a change is diffed against.
     * Its monitor orders read-diff-send, so a later read can never be sent before an earlier one
     * and leave a phone showing a seat in the state it left. */
    private class LiveShowing {
        val subscribers: MutableSet<(Map<UUID, String>) -> Unit> = ConcurrentHashMap.newKeySet()
        var seats: Map<UUID, String> = emptyMap()
    }

    private data class ShowingKey(val tenantId: UUID, val poolId: UUID, val sessionId: UUID)
    private data class OrderKey(val tenantId: UUID, val groupId: UUID, val sessionId: UUID?)

    /** What an order socket is told: the group's status, its newest payment's and each seat's,
     * which between them are everything the order page's buttons and its 3 s poll turn on. */
    data class OrderStatus(val status: String?, val paymentStatus: String?, val seats: Map<UUID, String>)

    private val showings = ConcurrentHashMap<ShowingKey, LiveShowing>()
    private val orders = ConcurrentHashMap<OrderKey, MutableSet<(OrderStatus) -> Unit>>()

    /**
     * Turns the whole feature off without touching code — useful on a deployment with no
     * Firebase credentials, and as the kill switch if a push storm ever needs stopping without a
     * redeploy. Independent of whether credentials exist: an operator may want push off even
     * where it would work.
     */
    @ConfigProperty(name = "booking.push.notify-on-commit", defaultValue = "true")
    @JvmField
    final var notifyEnabled: Boolean = true

    /**
     * Registers a watch: [deliver] then pushes to [token] when this pool/session changes,
     * for 20 minutes. The seat map's `POST /push/subscribe` and the app's
     * `POST /api/app/push/subscribe` both land here. An upsert on (pool, session, token), so
     * registering again refreshes the same row's expiry and returns it: a reload or a resent
     * request adds no second push per seat.
     */
    fun watch(tenantId: UUID, poolId: UUID, sessionId: UUID?, token: String): ResourcePoolWatch =
        db.inTenantTransaction(tenantId) { c -> watchRepository.register(c, tenantId, poolId, sessionId, null, token) }

    /** The client's own unregister (the primary cleanup path); how many rows went, which is
     * zero for a watch that was never there or has already expired. */
    fun unwatch(tenantId: UUID, poolId: UUID, sessionId: UUID?, token: String): Int =
        db.inTenantTransaction(tenantId) { c -> watchRepository.deleteWatch(c, tenantId, poolId, sessionId, token) }

    /**
     * Fire-and-forget notification for one pool/session.
     *
     * Deliberately swallows every failure. A push to bystanders is strictly less important
     * than the booking that triggered it, which has already committed by the time this runs —
     * letting an FCM outage surface as an error here would turn someone else's infrastructure
     * problem into a failed response for a customer whose seat is already safely held.
     */
    fun notifyWatchers(tenantId: UUID?, resourcePoolId: UUID?, sessionId: UUID?, reason: Reason?) {
        if (tenantId != null && resourcePoolId != null && sessionId != null) {
            val key = ShowingKey(tenantId, resourcePoolId, sessionId)
            showings[key]?.let { live -> fireAndForget("seat delta for showing $sessionId") { sendSeats(key, live, null) } }
            for ((order, subscribers) in orders) {
                if (order.tenantId == tenantId && order.sessionId == sessionId) {
                    fireAndForget("status of order ${order.groupId}") { sendOrder(order, subscribers) }
                }
            }
        }
        if (!notifyEnabled || resourcePoolId == null) {
            return
        }
        executor.execute {
            try {
                deliver(tenantId, resourcePoolId, sessionId, reason)
            } catch (e: Exception) {
                LOG.warnf(e, "Availability push failed for pool %s session %s", resourcePoolId, sessionId)
            }
        }
    }

    /** Synchronous body, package-visible so tests can assert the outcome without racing the
     * executor — the asynchrony is a production concern, not something to make tests flaky.
     *
     * Public rather than package-visible, because Kotlin has none;
     * `NotifyOnCommitTest` and `BookingFlowDifferentialTest` call it. `@Throws`
     * because the Java declared `throws Exception` and those callers are written against it. */
    @Throws(Exception::class)
    fun deliver(tenantId: UUID?, resourcePoolId: UUID?, sessionId: UUID?, reason: Reason?) {
        val tokens = db.inTenantTransaction(tenantId) { c ->
            watchRepository.findTokensToNotify(c, tenantId, resourcePoolId, sessionId)
        }
        if (tokens.isEmpty()) {
            return
        }

        // The data payload is what makes this a live-updating map rather than just a toast: the
        // service worker matches poolId/sessionId against the page the customer has open and
        // refreshes that seat grid in place.
        val data: MutableMap<String, String> = java.util.HashMap()
        data["type"] = "seat-availability"
        data["reason"] = reason!!.name
        data["poolId"] = resourcePoolId!!.toString()
        if (sessionId != null) {
            data["sessionId"] = sessionId.toString()
        }

        // What a tap opens: the seat map, as sw.html's own click handler did until the SDK's copy
        // became the only one (2026-11-06). Nothing while PUBLIC_HTTPS_BASE_URL is unset, as for the order push.
        val link = sessionId?.let { publicUrls.absolute("/halls/$resourcePoolId/showings/$it").orElse(null) }
        val result = pushService.sendToAll(tokens, reason.title, reason.body, data, link)

        // The first failure path: an unregistered or invalid token means that subscription is
        // gone, and the row is what keeps it in the send set — so delete it rather than retry it
        // on every future change. Transient failures are deliberately left alone.
        if (result.deadTokens.isNotEmpty()) {
            val removed = db.inTenantTransaction(tenantId) { c ->
                var n = 0
                for (dead in result.deadTokens) {
                    n += watchRepository.deleteByToken(c, tenantId, dead)
                }
                n
            }
            LOG.infof("Pruned %d watch row(s) for %d dead subscription(s)", removed, result.deadTokens.size)
        }
        LOG.debugf("Availability push for pool %s session %s: %d sent, %d dead, %d failed",
                resourcePoolId, sessionId, result.sent, result.deadTokens.size,
                result.failedTokens.size)
    }

    /**
     * Registers an app socket on one showing and hands it every seat's status at once, then only
     * the seats that change. The first frame closes the gap between the app's read of the map and
     * this subscription. False, with nothing registered, when [tenantId] has no such showing.
     */
    fun subscribeToShowing(tenantId: UUID, poolId: UUID, sessionId: UUID,
                           subscriber: (Map<UUID, String>) -> Unit): Boolean {
        val key = ShowingKey(tenantId, poolId, sessionId)
        val exists = db.inTenantTransaction(tenantId) { c ->
            sessionRepository.findById(c, tenantId, sessionId).filter { it.resourcePoolId == poolId }.isPresent
        }
        if (!exists) {
            return false
        }
        // compute, not get-then-put, so a last subscriber leaving cannot drop the entry this one joins.
        val live = showings.compute(key) { _, v -> (v ?: LiveShowing()).also { it.subscribers += subscriber } }!!
        try {
            sendSeats(key, live, subscriber)
        } catch (e: Exception) {
            // The socket never learns it subscribed, so its close callback has nothing to undo.
            unsubscribeFromShowing(tenantId, poolId, sessionId, subscriber)
            throw e
        }
        return true
    }

    fun unsubscribeFromShowing(tenantId: UUID, poolId: UUID, sessionId: UUID,
                               subscriber: (Map<UUID, String>) -> Unit) {
        showings.computeIfPresent(ShowingKey(tenantId, poolId, sessionId)) { _, v ->
            v.subscribers -= subscriber
            if (v.subscribers.isEmpty()) null else v
        }
    }

    /** Registers an app socket on one order, on [sessionId]'s showing, and tells it the order's
     * state at once. The caller has already shown the order is this identity's
     * (`BookingService.getGroupDetail`); the tenant is that identity's, never the socket's input. */
    fun subscribeToOrder(tenantId: UUID, groupId: UUID, sessionId: UUID?, subscriber: (OrderStatus) -> Unit) {
        val key = OrderKey(tenantId, groupId, sessionId)
        orders.compute(key) { _, v -> (v ?: ConcurrentHashMap.newKeySet()).also { it += subscriber } }
        try {
            sendOrder(key, setOf(subscriber))
        } catch (e: Exception) {
            unsubscribeFromOrder(tenantId, groupId, sessionId, subscriber)
            throw e
        }
    }

    fun unsubscribeFromOrder(tenantId: UUID, groupId: UUID, sessionId: UUID?, subscriber: (OrderStatus) -> Unit) {
        orders.computeIfPresent(OrderKey(tenantId, groupId, sessionId)) { _, v ->
            v -= subscriber
            if (v.isEmpty()) null else v
        }
    }

    /** After commit, fire-and-forget, like [notifyWatchers]: a payment's status changed, which an
     * expired or failed QR does without moving a seat, so [notifyWatchers] never hears of it. */
    fun notifyOrder(tenantId: UUID, groupId: UUID) {
        for ((order, subscribers) in orders) {
            if (order.tenantId == tenantId && order.groupId == groupId) {
                fireAndForget("status of order $groupId") { sendOrder(order, subscribers) }
            }
        }
    }

    /** Off the committing thread, and nothing it throws reaches the caller, whose booking or
     * payment has already committed. */
    private fun fireAndForget(what: String, work: () -> Unit) {
        try {
            executor.execute {
                try {
                    work()
                } catch (e: Exception) {
                    LOG.warnf(e, "Live update failed: %s", what)
                }
            }
        } catch (e: Exception) {
            LOG.warnf(e, "Live update not dispatched: %s", what)
        }
    }

    /** Reads the showing's seats once and sends each subscriber what it has not been told:
     * everything to [joining], the change since the last read to the rest. */
    private fun sendSeats(key: ShowingKey, live: LiveShowing, joining: ((Map<UUID, String>) -> Unit)?) =
        synchronized(live) {
            if (live.subscribers.isEmpty()) {
                return@synchronized
            }
            // shortcut: a seat retired by a relayout drops out of this read and no frame says so;
            // the socket's next open shows it. Upgrade if relayouts during sales become common.
            val now = db.inTenantTransaction(key.tenantId) { c ->
                reservationRepository.findSeatMap(c, key.tenantId, key.poolId, key.sessionId)
            }.associate { it.resourceUnitId!! to it.seatStatus!! }
            val changed = now.filter { (seat, status) -> live.seats[seat] != status }
            live.seats = now
            for (subscriber in live.subscribers) {
                val part = if (subscriber === joining) now else changed
                if (part.isNotEmpty()) offer { subscriber(part) }
            }
        }

    private fun sendOrder(key: OrderKey, subscribers: Collection<(OrderStatus) -> Unit>) {
        val status = db.inTenantTransaction(key.tenantId) { c ->
            OrderStatus(groupRepository.findById(c, key.tenantId, key.groupId).map { it.status }.orElse(null),
                paymentRepository.findByReservationGroup(c, key.tenantId, key.groupId).firstOrNull()?.status,
                reservationRepository.findByGroup(c, key.tenantId, key.groupId).associate { it.reservationId!! to it.status!! })
        }
        for (subscriber in subscribers) offer { subscriber(status) }
    }

    /** One subscriber's failure is its own: the rest are still sent to. A closed socket leaves
     * through its own close callback, not from here. */
    private fun offer(send: () -> Unit) {
        try {
            send()
        } catch (e: Exception) {
            LOG.debugf(e, "Live subscriber refused a frame")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SeatAvailabilityNotifier::class.java)
    }
}
