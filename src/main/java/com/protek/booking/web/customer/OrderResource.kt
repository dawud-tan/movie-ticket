package com.protek.booking.web.customer

import com.protek.booking.security.Roles
import com.protek.booking.service.BookingService
import com.protek.booking.web.dto.ReservationGroupDetail
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * A customer's own orders: the list, and "confirm the hold
 * into a reservation" and "cancel" for one of them.
 *
 * <p><b>Published as {@code /orders}, not {@code /reservations}.</b> "Pesanan" —
 * an order — is what every Indonesian ticketing site calls this, and what a customer calls it
 * when they telephone about one. The internal vocabulary is unchanged and deliberately so: the
 * table is still {@code reservation_group}, the lifecycle is still written in reservations,
 * and renaming the schema to match a UI label would be the tail wagging the dog. The old path
 * 301s, for the reason {@code ShowtimeResource} gives about {@code /films}.
 *
 * <p><b>Why {@code {groupId}} sits on the methods rather than on the class.</b> It used to be
 * on the class — {@code @Path("/reservations/{groupId}")} — and that made the collection URL
 * unspellable from inside this resource: every method inherited the path parameter, so
 * "list my bookings" had nowhere to live. Moving it down is the tidier of the two fixes
 * of the two; the alternative was a second resource class whose only reason
 * to exist would be a URL this one had claimed.
 *
 * <p>Authenticated, unlike browsing: a verified email is
 * proportionate identity for buying a cinema seat, and the kesepakatan (consent) limb wants the
 * hold-to-confirmed transition to be a deliberate, attributable act — which it cannot be if
 * anyone holding the URL can perform it. {@code tenant_staff} is allowed alongside
 * {@code customer} because confirming and cancelling on a customer's behalf is a
 * tenant-facing use case (phone cancellations, no-shows) and the counter sale's settle step.
 *
 * <p>The role check alone is not sufficient here, and the service layer adds what it cannot
 * express: {@code BookingService.assertMayActOn} rejects a customer acting on another
 * customer's group, since RLS isolates tenants and not customers within a tenant. The list is
 * narrower still — it is scoped to the acting identity's own {@code customer} row and takes no
 * parameter that could widen it.
 */
@Path("/orders")
@RunOnVirtualThread
@RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
class OrderResource {

    @CheckedTemplate(basePath = "customer")
    object Templates {
        @JvmStatic
        external fun orders(orders: BookingService.Orders?): TemplateInstance

        @JvmStatic
        external fun reservation(
            detail: ReservationGroupDetail?,
            payment: com.protek.booking.domain.Payment?,
            paymentEnabled: Boolean,
            walletLinks: Map<UUID, String>?,
        ): TemplateInstance

        @JvmStatic
        external fun reservationFragment(
            detail: ReservationGroupDetail?,
            payment: com.protek.booking.domain.Payment?,
            paymentEnabled: Boolean,
            walletLinks: Map<UUID, String>?,
        ): TemplateInstance
    }

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var paymentService: com.protek.booking.payment.PaymentService
    @Inject
    lateinit var walletLinks: com.protek.booking.wallet.GoogleWalletLinks
    @Inject
    lateinit var currentTenant: com.protek.booking.service.CurrentTenant
    @Inject
    lateinit var orderPush: com.protek.booking.service.OrderPush
    @Inject
    lateinit var language: com.protek.booking.i18n.LanguagePreference

    /**
     * Order history — what a returning customer once had no way to see: the only handle on
     * a booking was the group id in the URL they were redirected to once, so a closed tab lost
     * it.
     *
     * <p>Takes no parameters at all, deliberately. Whose history to show is the acting identity's
     * to know and not the caller's to ask for; see {@code BookingService.listBookingHistory}.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    fun history(): TemplateInstance = Templates.orders(bookingService.listOrders())


    @GET
    @Path("/{groupId}")
    @Produces(MediaType.TEXT_HTML)
    fun detail(@PathParam("groupId") groupId: UUID): TemplateInstance {
        // getGroupDetail runs the ownership check, so it must come first: rendering a payment for
        // a group this caller may not act on would leak its amount before the 403.
        val detail = bookingService.getGroupDetail(groupId)
        return Templates.reservation(detail, latestPayment(groupId), paymentService.isEnabled(),
            walletLinksFor(detail))
    }

    /**
     * The same markup the page renders, for htmx to poll while a QRIS charge is outstanding — so a
     * customer who has scanned the QR watches their seats go confirmed rather than pressing a
     * Refresh link, which is what this replaced.
     *
     * <p><b>Polling rather than a push.</b> The platform already has Web Push, and it is the
     * wrong tool here: it needs notification permission, which a customer paying should not have to
     * grant and may refuse, and {@code resource_pool_watch} is keyed by pool and session rather
     * than by reservation group. Push exists for bystanders watching a seat map they are not
     * looking at; this customer is looking straight at the page. Polling costs one small request
     * every 3 s for at most the QR's lifetime, and needs no permission from anyone.
     *
     * <p>The stop condition is markup, not code: the fragment omits {@code hx-trigger} once the
     * payment is terminal, so htmx has nothing left to schedule. Nothing here has to remember to
     * cancel anything.
     *
     * <p>Runs the same ownership check as {@link #detail}, and for the same reason — it is the same
     * data, and a polled endpoint is no less worth protecting than a rendered one.
     */
    @GET
    @Path("/{groupId}/fragment")
    @Produces(MediaType.TEXT_HTML)
    fun fragment(@PathParam("groupId") groupId: UUID): TemplateInstance {
        val detail = bookingService.getGroupDetail(groupId)
        return Templates.reservationFragment(detail, latestPayment(groupId), paymentService.isEnabled(),
            walletLinksFor(detail))
    }

    /**
     * Creates the QRIS charge for a customer paying on their own device — the first
     * trigger point.
     *
     * <p>Separate from {@link #confirm} rather than replacing it: with Midtrans configured, the
     * customer pays and the <em>webhook</em> confirms, so confirmation is no longer something
     * the browser asks for. {@code confirm} remains as the pay-at-counter path,
     * which is still the only path when Midtrans is unconfigured.
     */
    @POST
    @Path("/{groupId}/pay")
    fun pay(@PathParam("groupId") groupId: UUID): Response {
        bookingService.getGroupDetail(groupId)
        try {
            paymentService.createQrisCharge(groupId, false)
        } catch (alreadyPaid: com.protek.booking.payment.PaymentService.OrderAlreadyPaidException) {
            // Paid already (a second tab, or at the counter): the order page shows the payment.
        }
        return Response.seeOther(URI.create("/orders/$groupId")).build()
    }

    @POST
    @Path("/{groupId}/confirm")
    fun confirm(@PathParam("groupId") groupId: UUID): Response {
        bookingService.confirmGroup(groupId)
        return Response.seeOther(URI.create("/orders/$groupId")).build()
    }

    /**
     * "Notify me about this order" (`order-push.js`), and its DELETE turning it off. Only the
     * order's own customer ([OrderPush.watch] answers 403 to anyone else, staff included). The
     * language is the one this request reads in, kept with the watch so the push needs no second.
     * The app's twin is `AppApiResource.watchOrder`.
     */
    @POST
    @Path("/{groupId}/push")
    @Consumes(MediaType.APPLICATION_JSON)
    fun watchOrder(@PathParam("groupId") groupId: UUID, request: PushResource.SubscribeRequest?): Response {
        val token = request?.token?.takeUnless { it.isBlank() }
            ?: return Response.status(Response.Status.BAD_REQUEST).build()
        orderPush.watch(groupId, token, language.current())
        return Response.noContent().build()
    }

    @DELETE
    @Path("/{groupId}/push")
    @Consumes(MediaType.APPLICATION_JSON)
    fun unwatchOrder(@PathParam("groupId") groupId: UUID, request: PushResource.SubscribeRequest?): Response {
        val token = request?.token ?: return Response.status(Response.Status.BAD_REQUEST).build()
        orderPush.unwatch(groupId, token)
        return Response.noContent().build()
    }

    private fun latestPayment(groupId: UUID): com.protek.booking.domain.Payment? {
        val payments = paymentService.paymentsFor(groupId)
        return if (payments.isEmpty()) null else payments[0]
    }

    /**
     * "Add to Google Wallet" links, per confirmed seat. Empty on any deployment
     * without an issuer id, which is every one of them until somebody creates the account —
     * the ticket card then renders exactly as it did before, which is the point of the pass
     * being additive.
     *
     * <p>The issuer name shown on the pass is the tenant's own, from {@code CurrentTenant}:
     * one Google issuer account is Protek's, and a customer should still see the cinema they
     * bought from. That arrangement leaves a contract question open.
     */
    private fun walletLinksFor(detail: ReservationGroupDetail): Map<UUID, String> {
        if (!walletLinks.isConfigured()) {
            return java.util.Map.of()
        }
        return walletLinks.saveUrlsFor(detail, currentTenant.get().displayName)
    }

    @POST
    @Path("/{groupId}/items/{reservationId}/cancel")
    fun cancelItem(
        @PathParam("groupId") groupId: UUID,
        @PathParam("reservationId") reservationId: UUID,
    ): Response {
        bookingService.cancelReservation(reservationId, "customer", "Cancelled by customer")
        return Response.seeOther(URI.create("/orders/$groupId")).build()
    }
}
