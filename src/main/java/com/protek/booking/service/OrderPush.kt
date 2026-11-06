package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.i18n.Language
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.repository.OrderWatchRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.seo.PublicUrls
import com.protek.booking.web.TemplateExtensions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/**
 * The buyer's own push: "Notify me about this order" on the order page, the web's
 * `POST /orders/{groupId}/push` and the app's `POST /api/app/orders/{groupId}/push`, then one push
 * for each of the three events the buyer's email covers (`BuyerMail`), sent from the same points.
 *
 * **Only the order's own customer** may subscribe, which is narrower than the page: staff open any
 * order (`BookingService.assertMayActOn`), but a push to staff's device about a buyer's refund
 * would tell the wrong person. RLS keeps the tenant; the check here keeps the customer.
 *
 * **After commit, fire-and-forget**, like `SeatAvailabilityNotifier.notifyWatchers` and under the
 * same kill switch: a failure here is a log line, never an undone cancel or refund. Copy comes from
 * the bundle in the language stored with each watch, so each device gets one language.
 */
@ApplicationScoped
class OrderPush {

    enum class Event { SHOWING_CANCELLED, REFUND_SENT, REFUND_REFUSED }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var watches: OrderWatchRepository
    @Inject
    lateinit var groups: ReservationGroupRepository
    @Inject
    lateinit var pushService: PushNotificationService
    @Inject
    lateinit var executor: ManagedExecutor
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var currentActor: CurrentActor
    @Inject
    lateinit var publicUrls: PublicUrls

    @ConfigProperty(name = "booking.push.notify-on-commit", defaultValue = "true")
    @JvmField
    final var notifyEnabled: Boolean = true

    /** The opt-in. An upsert: a second tap, or the same tap resent, is the same watch. */
    fun watch(groupId: UUID, token: String, language: Language) {
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c ->
            assertOwnOrder(c, tenantId, groupId)
            watches.register(c, tenantId, groupId, token, language.tag())
        }
    }

    /** The same control turned off; zero when there was nothing to remove. */
    fun unwatch(groupId: UUID, token: String): Int {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            assertOwnOrder(c, tenantId, groupId)
            watches.delete(c, tenantId, groupId, token)
        }
    }

    private fun assertOwnOrder(c: java.sql.Connection, tenantId: UUID, groupId: UUID) {
        val group = groups.findById(c, tenantId, groupId)
            .orElseThrow { NotFoundException("Reservation group not found: $groupId") }
        // customer_id is set only on a customer's app_user (V1's check), so staff never match.
        if (currentActor.customerId().orElse(null) != group.customerId) {
            throw ForbiddenException("Only this order's customer can be notified about it.")
        }
    }

    /** `BookingService.cancelShowing`, after commit, for each order it cancelled seats of. */
    fun showingCancelled(tenantId: UUID, groupIds: Collection<UUID>) {
        if (!notifyEnabled || groupIds.isEmpty()) return
        val ids = groupIds.toList()
        try {
            executor.execute { for (g in ids) quietly(g) { deliver(tenantId, g, Event.SHOWING_CANCELLED, null) } }
        } catch (e: RuntimeException) {
            LOG.warnf(e, "Order push not dispatched for %d order(s)", ids.size)
        }
    }

    /** `RefundService.attempt`, already off the request thread, beside `BuyerMail.refundSent`. */
    fun refundSent(tenantId: UUID, groupId: UUID, amount: BigDecimal) {
        if (notifyEnabled) quietly(groupId) { deliver(tenantId, groupId, Event.REFUND_SENT, amount) }
    }

    fun refundRefused(tenantId: UUID, groupId: UUID, amount: BigDecimal) {
        if (notifyEnabled) quietly(groupId) { deliver(tenantId, groupId, Event.REFUND_REFUSED, amount) }
    }

    private fun quietly(groupId: UUID, send: () -> Unit) {
        try {
            send()
        } catch (e: Exception) {
            LOG.warnf(e, "Order push failed for order %s", groupId)
        }
    }

    /** Synchronous body, public so tests assert the outcome without racing the executor. */
    fun deliver(tenantId: UUID, groupId: UUID, event: Event, amount: BigDecimal?) {
        val (code, watchers) = db.inTenantTransaction(tenantId) { c ->
            groups.findById(c, tenantId, groupId).map { it.bookingCode }.orElse(null) to
                watches.findByGroup(c, tenantId, groupId)
        }
        if (watchers.isEmpty()) return
        // Unset in dev and the tests (PublicUrls): the notification then opens nothing on a tap.
        val link = publicUrls.absolute("/orders/$groupId").orElse(null)
        val money = amount?.let { TemplateExtensions.fmtMoney(it) }
        val dead = ArrayList<String>()
        for ((tag, tokens) in watchers.groupBy({ it.language }, { it.token })) {
            val msg = LanguagePreference.messagesIn(Language.ofTag(tag).orElse(Language.DEFAULT))
            val (title, body) = when (event) {
                Event.SHOWING_CANCELLED -> msg.orderShowingCancelled() to msg.orderPushCancelledBody(code)
                Event.REFUND_SENT -> msg.orderPushRefundSentTitle() to msg.orderPushRefundSentBody(money, code)
                Event.REFUND_REFUSED -> msg.orderPushRefundRefusedTitle() to msg.orderPushRefundRefusedBody(money, code)
            }
            // The phone gets data only (PushService words nothing itself here: title and body are
            // already in the subscriber's language) and opens the order from groupId.
            val data = mapOf("type" to "order", "event" to event.name, "groupId" to groupId.toString(),
                "title" to title, "body" to body)
            dead += pushService.sendToAll(tokens, title, body, data, link).deadTokens
        }
        if (dead.isNotEmpty()) {
            val removed = db.inTenantTransaction(tenantId) { c -> dead.sumOf { watches.deleteByToken(c, tenantId, it) } }
            LOG.infof("Pruned %d order watch row(s) for %d dead subscription(s)", removed, dead.size)
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(OrderPush::class.java)
    }
}
