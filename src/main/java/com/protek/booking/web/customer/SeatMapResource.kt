package com.protek.booking.web.customer

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.security.Roles
import com.protek.booking.service.AgeAdvisoryService
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
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * Customer-facing live seat map, hold. The hold endpoint is the one
 * genuinely HTMX-driven interaction in this app: a failed attempt is answered synchronously,
 * in the same response, as an HTML fragment swapped into the seat-map DOM — no
 * client-side JSON parsing, no separate state to reconcile.
 */
@Path("/halls/{poolId}/showings/{sessionId}")
@RunOnVirtualThread
class SeatMapResource {

    @CheckedTemplate(basePath = "customer")
    object Templates {
        @JvmStatic
        external fun seatmap(
            hall: ResourcePool?, showing: ResourcePoolSession?,
            rows: List<SeatRow>?, errorMessage: String?,
            price: CalendarPricing.SeatPrice?,
            ageGate: AgeClassification.Gate?,
            ageAdvisory: AgeClassification.Advisory?,
            ticked: Set<UUID>,
        ): TemplateInstance

        @JvmStatic
        external fun seatmapFragment(
            hall: ResourcePool?, showing: ResourcePoolSession?,
            rows: List<SeatRow>?, errorMessage: String?,
            price: CalendarPricing.SeatPrice?,
            ageGate: AgeClassification.Gate?,
            ageAdvisory: AgeClassification.Advisory?,
            ticked: Set<UUID>,
        ): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    /** The age attestation's gate carries the exact sentence hashed into consent_record, so the language it was
     * shown in is decided here, once, and travels on the Gate. AgeClassification says why it
     * takes a Language rather than a bundle. */
    @Inject
    lateinit var language: LanguagePreference
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var ageAdvisories: AgeAdvisoryService

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun seatmap(
        @PathParam("poolId") poolId: UUID, @PathParam("sessionId") sessionId: UUID,
        @QueryParam("seats") seats: String?,
    ): TemplateInstance {
        val page = catalogService.getSeatMapPage(poolId, sessionId)
        return Templates.seatmap(page.hall, page.showing, SeatMapSupport.groupByRow(page.seats),
            null, page.price, page.ageGate(language.current()), ageAdvisories.forSeatMap(page),
            ticked(seats, page.seats))
    }

    /**
     * `?seats=` (comma-separated seat ids): the booking assistant's proposal, ticked at render so
     * the customer reviews it and the hold is still their own click.
     * It is caller input, so only ids that are an available seat of
     * this showing are kept; anything else is dropped, never an error. Only the full page takes
     * it: the 3 s poll keeps the ticks through `hx-preserve`, and a seat taken meanwhile drops out
     * through the markup revert, as a hand-ticked one does.
     */
    private fun ticked(seats: String?, all: List<SeatMapEntry>): Set<UUID> {
        if (seats.isNullOrBlank()) return emptySet()
        val available = all.filter { it.seatStatus == "available" }.mapNotNull { it.resourceUnitId }.toSet()
        return seats.split(',').mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            .filter { it in available }.toSet()
    }

    @GET
    @Path("/fragment")
    @Produces(MediaType.TEXT_HTML)
    fun fragment(@PathParam("poolId") poolId: UUID, @PathParam("sessionId") sessionId: UUID): TemplateInstance =
        renderFragment(poolId, sessionId, null)

    /**
     * Authenticated, unlike the two GETs above: browsing and viewing a seat map
     * are public, and holding a seat is where "proportionate identity for buying a
     * cinema seat" starts applying. Anonymous holds would also make {@code audit_log}'s
     * attributability limb (UU ITE Pasal 6) unsatisfiable for the row that matters most.
     */
    @POST
    @Path("/hold")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun hold(
        @PathParam("poolId") poolId: UUID, @PathParam("sessionId") sessionId: UUID,
        @FormParam("seatIds") seatIds: List<UUID>?,
        @FormParam("customerName") customerName: String?,
        @FormParam("customerPhone") customerPhone: String?,
        @FormParam("ageAttested") @DefaultValue("false") ageAttested: Boolean,
    ): Response {
        // The form only carries this box for a restricted film, so an absent
        // parameter is the ordinary case rather than a missing field — hence a default rather
        // than a null check. The server decides whether the rating needed one; a POST that
        // simply omits it against a 17+ film is refused, which is the case a browser cannot
        // produce and a direct caller can.
        val result = bookingService.hold(poolId, sessionId, seatIds,
            customerName, customerPhone,
            if (ageAttested) AgeClassification.Attestation.TICKED
            else AgeClassification.Attestation.NOT_TICKED)
        if (result.success) {
            // htmx intercepts HX-Redirect and does a full client-side navigation, which is
            // exactly what we want after a successful hold: off the seat map, onto the
            // reservation's own hold/confirm/cancel screen.
            return Response.ok()
                .header("HX-Redirect", "/orders/" + result.group!!.reservationGroupId)
                .build()
        }
        return Response.ok(renderFragment(poolId, sessionId, result.failureReason)).build()
    }

    /**
     * The polled render. One {@code getSeatMapPage} call rather than the four
     * reads this used to make, because this is the one endpoint in the application that every
     * open tab requests on a timer: whatever it does, it does 0.33 times a second per viewer.
     * Four transactions here were four connection acquisitions, four RLS {@code set_config}s
     * and four commits for one fragment — see the comment on that method.
     */
    private fun renderFragment(poolId: UUID, sessionId: UUID, errorMessage: String?): TemplateInstance {
        val page = catalogService.getSeatMapPage(poolId, sessionId)
        return Templates.seatmapFragment(page.hall, page.showing, SeatMapSupport.groupByRow(page.seats),
            errorMessage, page.price, page.ageGate(language.current()), ageAdvisories.forSeatMap(page), emptySet())
    }
}
