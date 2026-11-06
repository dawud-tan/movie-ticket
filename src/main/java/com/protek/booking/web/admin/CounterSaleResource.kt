package com.protek.booking.web.admin

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.security.Roles
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CalendarPricing
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * Selling at the counter on behalf of a walk-in.
 *
 * <p>Not an edge case — on-the-spot counter sales are the dominant
 * channel at Indonesian cinemas, so a system handling only online self-service would cover
 * the minority of a tenant's actual transactions. That is why this shipped next to
 * multi-tenancy rather than being deferred as a convenience feature.
 *
 * <p><b>Why this is a thin resource over the same service:</b> a counter sale differs from a
 * self-service booking in exactly two facts — who the customer row is (the per-tenant walk-in
 * placeholder) and what {@code created_by_actor} records ({@code tenant_staff}). Everything
 * that makes a booking correct — the {@code EXCLUDE} constraint, the {@code FOR UPDATE}
 * session re-validation, the savepoint-per-seat block handling — is
 * {@code BookingService.holdForCounterSale} calling the identical code path. A parallel
 * counter-sale booking engine would be a second place for the double-booking guarantee to be
 * subtly wrong.
 *
 * <p>Settlement is deliberately split in two here: cash is marked paid
 * immediately, and the QRIS path is the Midtrans integration triggered from the staff
 * side — one integration, two trigger points, not a second payment mechanism.
 */
@Path("/admin/counter")
@RunOnVirtualThread
@RolesAllowed(Roles.TENANT_STAFF)
class CounterSaleResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun counterSale(
            hall: ResourcePool?, showing: ResourcePoolSession?,
            rows: List<SeatRow>?, price: CalendarPricing.SeatPrice?,
            errorMessage: String?,
            ageGate: AgeClassification.Gate?,
        ): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    /** The counter never asks for an attestation (every walk-in shares one customer row),
     * but it does show the rating, so it still needs a gate — and a gate is always made for a
     * language. */
    @Inject
    lateinit var language: LanguagePreference
    @Inject
    lateinit var bookingService: BookingService

    /** The seat picker staff work from at the counter. Same computed seat map as the customer
     * view — staff and customer must never be looking at two different
     * pictures of availability. */
    @GET
    @Path("/showings/{sessionId}")
    @Produces(MediaType.TEXT_HTML)
    fun seatPicker(@PathParam("sessionId") sessionId: UUID): TemplateInstance = render(sessionId, null)

    @POST
    @Path("/showings/{sessionId}/hold")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun hold(
        @PathParam("sessionId") sessionId: UUID,
        @FormParam("seatIds") seatIds: List<UUID>?,
    ): Response {
        val showing = catalogService.getShowingOrThrow(sessionId)
        val result = bookingService.holdForCounterSale(showing.resourcePoolId, sessionId, seatIds)
        if (result.success) {
            // Straight to the reservation's own page, where staff settle it — cash now, or
            // QRIS through Midtrans.
            return Response.seeOther(
                URI.create("/reservations/" + result.group!!.reservationGroupId)).build()
        }
        // Re-render with the specific seat that blocked it, the "richer response" for
        // composite bookings: staff can immediately offer a different specific block rather
        // than an undifferentiated failure.
        return Response.ok(render(sessionId, result.failureReason)).build()
    }

    /**
     * The same four reads the customer seat map makes, in the same one transaction
     * — including the band this showing sells at, so the number staff quote at
     * the desk is the number the hold will charge. A counter that quotes the wrong band takes
     * cash for it.
     */
    private fun render(sessionId: UUID, errorMessage: String?): TemplateInstance {
        val page = catalogService.getSeatMapPageForShowing(sessionId)
        // The rating is shown, never ticked: the counter does not attest on a
        // walk-in's behalf — see BookingService.holdForCounterSale — so what this screen owes
        // staff is the fact they need in order to ask for ID.
        return Templates.counterSale(page.hall, page.showing, SeatMapSupport.groupByRow(page.seats),
            page.price, errorMessage, page.ageGate(language.current()))
    }
}
