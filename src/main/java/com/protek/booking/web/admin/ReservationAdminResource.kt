package com.protek.booking.web.admin

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.isJavaBlank
import com.protek.booking.security.Roles
import com.protek.booking.service.BookingService
import com.protek.booking.service.CatalogService
import com.protek.booking.web.SeatMapSupport
import com.protek.booking.web.dto.SeatRow
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/** Tenant-facing "manually release or cancel a reservation" — phone cancellations
 * and no-shows are a real, everyday operational need. */
@Path("/admin")
@RunOnVirtualThread
@RolesAllowed(Roles.TENANT_STAFF)
class ReservationAdminResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun showingReservations(
            hall: ResourcePool?, showing: ResourcePoolSession?, rows: List<SeatRow>?,
        ): TemplateInstance

        @JvmStatic
        external fun bookingLookup(code: String?, message: String?): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var bookingService: BookingService

    @GET
    @Path("/showings/{sessionId}/reservations")
    @Produces(MediaType.TEXT_HTML)
    fun showingReservations(@PathParam("sessionId") sessionId: UUID): TemplateInstance {
        val showing = catalogService.getShowingOrThrow(sessionId)
        val hall = catalogService.getHallOrThrow(showing.resourcePoolId)
        val rows = SeatMapSupport.groupByRow(catalogService.getSeatMap(showing.resourcePoolId, sessionId))
        return Templates.showingReservations(hall, showing, rows)
    }

    /**
     * "I am calling about K7M2QPRT" — the counter's way in to a booking.
     *
     * <p>This is what makes the booking code worth minting. A code nobody can look anything up
     * with is decoration; this screen is the other half of it, and it is the only way staff can
     * reach a specific booking without the customer reading a UUID down the phone.
     *
     * <p><b>A hit redirects to the customer-facing page rather than rendering a copy of it.</b>
     * That page already shows the seats, the payment, the barcodes and the cancel buttons, and
     * staff may view it — {@code ReservationResource} allows {@code tenant_staff} for exactly
     * this kind of acting-on-behalf-of. A second staff-only rendering of the same booking would
     * be a screen to keep in step with the first for no capability the first does not have.
     *
     * <p><b>A miss renders, and says only that.</b> "No booking with that code" is the whole
     * message: not "not in this cinema" or a count of near misses, since a code is a value
     * somebody could try their neighbour's on. RLS makes another tenant's code read as a miss
     * here anyway, which is the same answer for the same reason.
     */
    @GET
    @Path("/bookings")
    @Produces(MediaType.TEXT_HTML)
    fun findBooking(@QueryParam("code") code: String?): Response {
        if (code == null || code.isJavaBlank()) {
            return Response.ok(Templates.bookingLookup("", null)).build()
        }
        return bookingService.findGroupByBookingCode(code)
            .map { group ->
                Response.seeOther(
                    URI.create("/reservations/" + group.reservationGroupId)).build()
            }
            .orElseGet {
                Response.ok(Templates.bookingLookup(code,
                    "No booking with that code.")).build()
            }
    }

    @POST
    @Path("/reservations/{reservationId}/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun cancel(
        @PathParam("reservationId") reservationId: UUID,
        @FormParam("sessionId") sessionId: UUID?,
    ): Response {
        bookingService.cancelReservation(reservationId, "tenant_staff", "Released by tenant staff")
        return Response.seeOther(URI.create("/admin/showings/$sessionId/reservations")).build()
    }
}
