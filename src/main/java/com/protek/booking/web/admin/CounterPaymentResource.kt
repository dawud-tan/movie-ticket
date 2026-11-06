package com.protek.booking.web.admin

import com.protek.booking.payment.PaymentService
import com.protek.booking.security.Roles
import com.protek.booking.service.BookingService
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * Counter settlement — QRIS's second trigger point, plus the cash path.
 *
 * <p>This is not a second payment integration: the QR-on-a-counter-screen
 * approach reuses what already exists, and an EDC terminal is a different rail that is
 * deliberately out of scope. So both endpoints here delegate to the same
 * {@link PaymentService} the customer-facing flow uses; the only difference is which actor is
 * recorded and, for QRIS, that {@code trigger_point} records {@code counter} rather than
 * {@code online} so a tenant can tell the two channels apart when reconciling takings.
 */
@Path("/admin/counter/reservations/{groupId}")
@RunOnVirtualThread
@RolesAllowed(Roles.TENANT_STAFF)
class CounterPaymentResource {

    @Inject
    lateinit var paymentService: PaymentService
    @Inject
    lateinit var bookingService: BookingService

    /** Cash: money is already in the drawer, so this records a paid payment attributed to the
     * staff member and confirms the seats in one transaction. No charge, no webhook, nothing to
     * reconcile.
     *
     * <p>Back to the order whatever the outcome. A second press records nothing
     * ({@link PaymentService#settleCash} says why that is a lock and not a check), and the page it
     * lands on already shows the one payment that exists — which is the answer staff need, more
     * than a banner saying the press was ignored. */
    @POST
    @Path("/cash")
    fun settleCash(@PathParam("groupId") groupId: UUID): Response {
        paymentService.settleCash(groupId)
        return Response.seeOther(URI.create("/reservations/$groupId")).build()
    }

    /** QRIS displayed on the counter screen for the customer to scan. The webhook confirms it,
     * exactly as it would for a customer paying on their own phone. */
    @POST
    @Path("/qris")
    fun chargeQris(@PathParam("groupId") groupId: UUID): Response {
        // Ownership/tenancy check via the same service path the customer flow uses, so a staff
        // member of one tenant cannot charge another tenant's group even with its id.
        bookingService.getGroupDetail(groupId)
        try {
            paymentService.createQrisCharge(groupId, true)
        } catch (alreadyPaid: PaymentService.OrderAlreadyPaidException) {
            // No QR for a paid order; the order page's payment card already shows what was taken,
            // as it does when "cash received" is refused.
        }
        return Response.seeOther(URI.create("/reservations/$groupId")).build()
    }
}
