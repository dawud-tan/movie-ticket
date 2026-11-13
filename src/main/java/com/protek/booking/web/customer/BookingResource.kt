package com.protek.booking.web.customer

import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.security.Roles
import com.protek.booking.service.AgeAdvisoryService
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingDraftService
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
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Optional
import java.util.UUID

/**
 * The seat-selection page a showtime leads to — {@code /booking?booking_ref=…}.
 *
 * <p><b>Why this exists alongside {@code SeatMapResource}.</b> That class renders the same seat
 * map at {@code /halls/{pool}/showings/{session}}, and it stays: it is the URL a ticket link,
 * every existing test and the staff screens point at, and it is the addressable form of one
 * showing. This one adds the thing a URL naming a showing cannot have — a handle that survives
 * the customer changing their mind about the showing. The seat map itself, the hold endpoint and
 * the poll are unchanged and shared; only the frame around them is new.
 *
 * <p><b>{@link #open} is deliberately not {@code @RolesAllowed} and every other method here
 * is.</b> That asymmetry is the whole of steps 2e/3e. An anonymous visitor tapping a showtime
 * has to be shown a sign-in sheet, and a sheet cannot be delivered by a 401: htmx does not swap
 * a non-2xx response, so the container-level rejection would leave the button
 * doing nothing at all — the dead-button failure that section records paying for once already.
 * So {@link #open} answers 200 with the sheet and creates nothing. Everything that touches a
 * draft is behind the role check, and the hold endpoint that actually takes a seat is behind its
 * own, unchanged.
 */
@Path("/booking")
@RunOnVirtualThread
class BookingResource {

    @CheckedTemplate(basePath = "customer")
    object Templates {
        /**
         * {@code ageGate} is a parameter rather than {@code page.seatMap.ageGate} in the markup,
         * and the reason is the one {@code AgeClassification} gives: a gate carries the exact
         * sentence hashed into {@code consent_record}, so it is always made <em>for a
         * language</em> — and a template has no way to supply one. The seat map has always
         * passed it in; this page used to reach for it in the markup, and stopped being able to
         * the moment the accessor took an argument. Exactly the shape
         * {@code AgeAdvisoryService}'s javadoc records for the advisory: two pages render one
         * fragment, and the second caller is the one that breaks.
         */
        @JvmStatic
        external fun booking(
            page: BookingDraftService.BookingPage?, rows: List<SeatRow>?, errorMessage: String?,
            ageGate: AgeClassification.Gate?, ageAdvisory: AgeClassification.Advisory?,
        ): TemplateInstance

        @JvmStatic
        external fun authSheet(): TemplateInstance
    }

    @Inject
    lateinit var bookingDrafts: BookingDraftService
    @Inject
    lateinit var ageAdvisories: AgeAdvisoryService
    @Inject
    lateinit var language: LanguagePreference

    /**
     * Step 2c/3c: a showtime was pressed.
     *
     * <p>Answers three ways, and which one is not a matter of the caller's preference:
     * <ul>
     *   <li><b>anonymous</b> → 200 carrying the sign-in sheet;</li>
     *   <li><b>signed in, htmx</b> → 200 with {@code HX-Redirect}, which htmx turns into a real
     *       client-side navigation;</li>
     *   <li><b>signed in, no JavaScript</b> → 303, because the plain form post that the same
     *       markup produces without htmx has no way to act on a header.</li>
     * </ul>
     * The third is not theoretical politeness: the showtime strip is rendered as real forms
     * precisely so the flow works without scripting, and answering only {@code HX-Redirect}
     * would have left that path silently landing on a blank page.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun open(
        @FormParam("sessionId") sessionId: UUID?,
        @HeaderParam("HX-Request") htmxRequest: String?,
    ): Response {
        val bookingRef = bookingDrafts.openDraft(sessionId)
        if (bookingRef.isEmpty) {
            return Response.ok(Templates.authSheet()).build()
        }
        val target = "/booking?booking_ref=" + bookingRef.get()
        if (htmxRequest != null) {
            return Response.ok().header("HX-Redirect", target).build()
        }
        return Response.seeOther(URI.create(target)).build()
    }

    /**
     * The page itself. {@code booking_ref} in the query string rather than the path, matching
     * the flow this was built to: it is a booking's identity, not a resource path segment, and
     * it is what the two field forms post back to.
     */
    @GET
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Produces(MediaType.TEXT_HTML)
    fun page(@QueryParam("booking_ref") bookingRef: UUID?): TemplateInstance =
        render(bookingDrafts.load(bookingRef))

    /** The date field. See {@code BookingDraftService.moveToDate} for why it lands on the
     * earliest showtime of the chosen day. */
    @POST
    @Path("/{bookingRef}/date")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun chooseDate(
        @PathParam("bookingRef") bookingRef: UUID,
        @FormParam("date") date: String?,
    ): Response {
        // A malformed date leaves the booking exactly as it was rather than 400ing: the field is
        // a select rendered from the server's own list, so a value that will not parse did not
        // come from the page, and the honest response to that is the page again.
        // The returned page's ref, not ours: the move can land on another of this account's drafts.
        return backToBooking(parseDate(date).map { bookingDrafts.moveToDate(bookingRef, it).draft.bookingRef!! }
            .orElse(bookingRef))
    }

    /** The time field. */
    @POST
    @Path("/{bookingRef}/showtime")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun chooseShowtime(
        @PathParam("bookingRef") bookingRef: UUID,
        @FormParam("showtime") sessionId: UUID?,
    ): Response {
        return backToBooking(bookingDrafts.moveToShowing(bookingRef, sessionId).draft.bookingRef!!)
    }

    /**
     * Dismissing the sign-in sheet — an endpoint answering with nothing, which is the point.
     *
     * <p>An empty 200 swapped into {@code #auth-sheet} removes the sheet. The alternatives were
     * an {@code onclick} (inline script, which a content-security policy worth having forbids)
     * or an {@code hx-on:click} that edits the DOM by hand. Neither is better than one route
     * that returns nothing, and this one keeps the sheet's behaviour in markup — the same
     * principle as the seat map's revert.
     */
    @GET
    @Path("/sheet/dismiss")
    @Produces(MediaType.TEXT_HTML)
    fun dismissSheet(): String = ""

    private fun backToBooking(bookingRef: UUID): Response =
        Response.seeOther(URI.create("/booking?booking_ref=$bookingRef")).build()

    /** The seat rows are grouped by the same helper the seat map uses, so both pages render
     * an identical grid from an identical shape — the seat map's revert-is-markup rule depends on the
     * two staying byte-identical, and one shared template plus one shared grouper is what
     * makes that true rather than hoped for. */
    private fun render(page: BookingDraftService.BookingPage): TemplateInstance =
        Templates.booking(
            page, SeatMapSupport.groupByRow(page.seatMap.seats), null,
            page.seatMap.ageGate(language.current()),
            ageAdvisories.forSeatMap(page.seatMap),
        )

    private companion object {
        fun parseDate(date: String?): Optional<LocalDate> =
            try {
                Optional.ofNullable(date).map { LocalDate.parse(it) }
            } catch (e: DateTimeParseException) {
                Optional.empty()
            }
    }
}
