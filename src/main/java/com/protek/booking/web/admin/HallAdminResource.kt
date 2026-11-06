package com.protek.booking.web.admin

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Site
import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import com.protek.booking.security.Roles
import com.protek.booking.service.CatalogService
import com.protek.booking.service.RatePlan
import com.protek.booking.service.SeatMapPlan
import com.protek.booking.service.SeatMapPreview
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.EnumMap
import java.util.UUID

/**
 * Tenant-facing "configure a hall's seat map" and "create and manage a showing".
 * Plain server-rendered forms with standard redirects — HTMX is reserved for the one
 * interaction that needs it (the customer hold flow); everywhere
 * else a full page reload after a POST is simpler and just as correct.
 */
@Path("/admin")
@RunOnVirtualThread
// Authorization rule 1: hiding the nav link is UX, not a security boundary — every admin resource
// carries its own enforcement, so hitting this URL directly is rejected by the container
// regardless of what the templates rendered. Class-level, so a method added later inherits
// it rather than shipping unprotected by omission.
//
// tenant_staff only, deliberately not platform_admin: a platform admin has no home tenant
// (tenant_staff is scoped to one tenant and platform_admin is global), so this
// tenant-scoped UI has no tenant to show them. Platform-level work lives under /platform.
@RolesAllowed(Roles.TENANT_STAFF)
class HallAdminResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun halls(halls: List<ResourcePool>?, sites: List<Site>?, films: List<Film>?): TemplateInstance

        @JvmStatic
        external fun hallDetail(
            hall: ResourcePool?, seats: List<ResourceUnit>?,
            ratePlan: RatePlan?, showings: List<ResourcePoolSession>?,
            otherHalls: List<ResourcePool>?, sites: List<Site>?, currentSite: Site?,
            films: List<Film>?, branchHalls: List<ResourcePool>?,
        ): TemplateInstance

        @JvmStatic
        external fun seatPlanPreview(
            hall: ResourcePool?, preview: SeatMapPreview?, error: String?,
            spec: String?, sourcePoolId: UUID?, sourceName: String?,
            csv: String?,
        ): TemplateInstance

        @JvmStatic
        external fun seatPlanRelayout(
            hall: ResourcePool?, relayout: CatalogService.RelayoutPreview?,
            error: String?, spec: String?, sourcePoolId: UUID?,
            sourceName: String?, csv: String?, note: String?,
        ): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun halls(): TemplateInstance =
        Templates.halls(catalogService.listHalls(), catalogService.listSites(), catalogService.listFilms())

    @POST
    @Path("/halls")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun createHall(
        @FormParam("name") name: String?, @FormParam("address") address: String?,
        @FormParam("siteId") siteId: UUID?,
    ): Response {
        val hall = catalogService.createHall(name, address, "Asia/Jakarta", "contiguous_n")
        if (siteId != null) {
            catalogService.assignHallToSite(hall.resourcePoolId, siteId)
        }
        return Response.seeOther(URI.create("/admin/halls/" + hall.resourcePoolId)).build()
    }

    // --- Branches ---------------------------------------------------------

    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun createSite(
        @FormParam("name") name: String?, @FormParam("city") city: String?,
        @FormParam("province") province: String?, @FormParam("address") address: String?,
        @FormParam("timezone") timezone: String?,
    ): Response {
        // A branch's own timezone, defaulted rather than required: an operator adding their
        // second branch should not have to answer a timezone question to do it, and every
        // branch of the chain this was scoped for is in WIB.
        catalogService.createSite(name, city, province, address,
            if (timezone == null || timezone.isJavaBlank()) "Asia/Jakarta" else timezone)
        return Response.seeOther(URI.create("/admin")).build()
    }

    /** Move a hall into a branch, or out of one. Halls created before branches existed have no
     * branch, and this is how they get one without being recreated. */
    @POST
    @Path("/halls/{poolId}/site")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun assignHallToSite(@PathParam("poolId") poolId: UUID, @FormParam("siteId") siteId: UUID?): Response {
        catalogService.assignHallToSite(poolId, siteId)
        return Response.seeOther(URI.create("/admin/halls/$poolId")).build()
    }

    @GET
    @Path("/halls/{poolId}")
    @Produces(MediaType.TEXT_HTML)
    fun hallDetail(@PathParam("poolId") poolId: UUID): TemplateInstance {
        val hall = catalogService.getHallOrThrow(poolId)
        val seats = catalogService.listSeats(poolId)
        // The configured rule, not a price: under a calendar rule this hall has four
        // amounts and no single "current rate" to show.
        val ratePlan = catalogService.getRatePlan(poolId).orElse(null)
        val showings = catalogService.listUpcomingShowings(poolId)
        // `resourcePoolId!!` where the Java called equals on it, so a null would still throw.
        val otherHalls = catalogService.listHalls().stream()
            .filter { other -> other.resourcePoolId!! != poolId }
            .toList()
        val currentSite = if (hall.siteId == null) null else catalogService.getSiteOrThrow(hall.siteId)
        // What "apply to every hall in this branch" would actually write to, so the checkbox
        // can say the number rather than leave staff to guess how far it reaches.
        val branchHalls: List<ResourcePool> = if (hall.siteId == null) {
            java.util.List.of()
        } else {
            catalogService.listHallsAtSite(hall.siteId)
        }
        return Templates.hallDetail(hall, seats, ratePlan, showings, otherHalls,
            catalogService.listSites(), currentSite, catalogService.listFilms(), branchHalls)
    }

    @POST
    @Path("/halls/{poolId}/seats")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun addSeat(
        @PathParam("poolId") poolId: UUID, @FormParam("label") label: String?,
        @FormParam("groupKey") groupKey: String?, @FormParam("sequenceNo") sequenceNo: Int?,
    ): Response {
        catalogService.addSeat(poolId, label, groupKey, sequenceNo)
        return Response.seeOther(URI.create("/admin/halls/$poolId")).build()
    }

    // --- Bulk seat-map entry ---------------------------------------------
    //
    // Two steps on purpose. resource_unit rows are referenced by reservation, so there is no
    // cheap undo for a mistyped range: /plan shows what would happen, /apply is the only thing
    // that writes. The same two-step shape covers the clone case, so an operator confirms a
    // copied layout the same way they confirm a generated one.

    /**
     * Step one: parse whatever the operator described and show it back. Writes nothing.
     *
     * <p>A parse failure renders as an error on this same page rather than a 500 — the message
     * names the offending line, and the whole point is that the operator can fix it.
     */
    @POST
    @Path("/halls/{poolId}/seats/plan")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun planSeats(
        @PathParam("poolId") poolId: UUID,
        @FormParam("mode") mode: String?,
        @FormParam("rows") rows: String?,
        @FormParam("seatsPerRow") seatsPerRow: Int?,
        @FormParam("spec") spec: String?,
        @FormParam("sourcePoolId") sourcePoolId: UUID?,
        @FormParam("replace") replace: String?,
    ): TemplateInstance {
        val hall = catalogService.getHallOrThrow(poolId)
        val relayout = replace != null
        var sourceName: String? = null
        var resolved: String? = null
        try {
            if ("clone" == mode) {
                if (sourcePoolId == null) {
                    throw IllegalArgumentException("Choose a hall to copy the layout from.")
                }
                sourceName = catalogService.getHallOrThrow(sourcePoolId).name
                if (relayout) {
                    return Templates.seatPlanRelayout(hall,
                        catalogService.previewRelayout(poolId, catalogService.seatsOfHall(sourcePoolId)),
                        null, null, sourcePoolId, sourceName, null, null)
                }
                return Templates.seatPlanPreview(hall, catalogService.previewClone(poolId, sourcePoolId),
                    null, null, sourcePoolId, sourceName, null)
            }
            // The quick grid form is expanded to a spec here, so preview and confirm carry one
            // representation rather than two that could drift apart.
            resolved = if ("uniform" == mode) {
                SeatMapPlan.uniform(rows, seatsPerRow ?: 0)
            } else {
                spec
            }
            if (relayout) {
                return Templates.seatPlanRelayout(hall,
                    catalogService.previewRelayout(poolId, SeatMapPlan.parse(resolved)),
                    null, resolved, null, null, null, null)
            }
            return Templates.seatPlanPreview(hall, catalogService.previewSeats(poolId, resolved),
                null, resolved, null, null, null)
        } catch (e: IllegalArgumentException) {
            return if (relayout) {
                Templates.seatPlanRelayout(hall, null, e.message, resolved, sourcePoolId, sourceName, null, null)
            } else {
                Templates.seatPlanPreview(hall, null, e.message, spec, sourcePoolId, null, null)
            }
        }
    }

    /**
     * Step one for a file: read the tenant's own seat list and show what it would create.
     *
     * <p>Its own endpoint rather than a fourth {@code mode} on {@code /plan}, because a file
     * needs {@code multipart/form-data} and the three forms already posting there do not — one
     * shared endpoint would have made every one of them carry an encoding they have no use for.
     * Everything after the parse is the same path: same preview, same confirm screen, same
     * refuse-on-collision insert.
     */
    @POST
    @Path("/halls/{poolId}/seats/csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    fun planSeatsFromCsv(
        @PathParam("poolId") poolId: UUID,
        @RestForm("file") file: FileUpload?,
        @RestForm("replace") replace: String?,
    ): TemplateInstance {
        val hall = catalogService.getHallOrThrow(poolId)
        val relayout = replace != null
        var csv: String? = null
        try {
            csv = readCsv(file)
            if (relayout) {
                val parsed = catalogService.readCsv(poolId, csv)
                return Templates.seatPlanRelayout(hall, catalogService.previewRelayout(poolId, parsed.seats),
                    null, null, null, null, csv, parsed.note())
            }
            return Templates.seatPlanPreview(hall, catalogService.previewCsvSeats(poolId, csv),
                null, null, null, null, csv)
        } catch (e: IllegalArgumentException) {
            return if (relayout) {
                Templates.seatPlanRelayout(hall, null, e.message, null, null, null, csv, null)
            } else {
                Templates.seatPlanPreview(hall, null, e.message, null, null, null, csv)
            }
        }
    }

    /** Step two: commit the previewed plan. The service re-checks for label collisions inside
     * the writing transaction, so a stale confirm screen cannot half-create a hall. */
    @POST
    @Path("/halls/{poolId}/seats/apply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun applySeats(
        @PathParam("poolId") poolId: UUID,
        @FormParam("spec") spec: String?,
        @FormParam("sourcePoolId") sourcePoolId: UUID?,
        @FormParam("csv") csv: String?,
        @FormParam("replace") replace: String?,
    ): Response {
        val relayout = replace != null
        try {
            if (relayout) {
                // Same three descriptions, different verb: the seats that stay keep their ids,
                // the ones this layout does not name are retired. The service re-checks inside
                // the writing transaction that none of them is booked.
                catalogService.applyRelayout(poolId, plannedSeats(poolId, spec, csv, sourcePoolId))
            } else if (sourcePoolId != null) {
                catalogService.cloneSeatsFrom(poolId, sourcePoolId)
            } else if (csv != null && !csv.isJavaBlank()) {
                // The file itself is carried back from the preview rather than uploaded twice:
                // one file chooser, two steps. Re-parsed here, so what is written is what this
                // request contains and not what an earlier one remembered.
                catalogService.importSeatsFromCsv(poolId, csv)
            } else {
                catalogService.generateSeats(poolId, spec)
            }
        } catch (e: IllegalArgumentException) {
            val hall = catalogService.getHallOrThrow(poolId)
            return Response.status(Response.Status.CONFLICT)
                .entity(if (relayout) {
                    Templates.seatPlanRelayout(hall, null, e.message, spec, sourcePoolId, null, csv, null)
                } else {
                    Templates.seatPlanPreview(hall, null, e.message, spec, sourcePoolId, null, csv)
                })
                .type(MediaType.TEXT_HTML)
                .build()
        }
        return Response.seeOther(URI.create("/admin/halls/$poolId")).build()
    }

    /** The three ways a hall can be described — a copied hall, an uploaded file, a typed spec —
     * resolved to the one list of seats every path downstream works in. */
    private fun plannedSeats(poolId: UUID, spec: String?, csv: String?, sourcePoolId: UUID?): List<SeatMapPlan.Seat> {
        if (sourcePoolId != null) {
            return catalogService.seatsOfHall(sourcePoolId)
        }
        if (csv != null && !csv.isJavaBlank()) {
            return catalogService.readCsv(poolId, csv).seats
        }
        return SeatMapPlan.parse(spec)
    }

    /**
     * Sets this hall's price — one flat amount, or calendar pricing's day-of-week bands.
     *
     * <p>One endpoint for both shapes rather than two, because they are one decision an
     * operator makes on one screen: a tenant switching from flat to banded should not have to
     * find a different form, and a tenant on flat pricing should not see four inputs they do
     * not use. The {@code mode} field says which, exactly as the seat-plan forms already do.
     *
     * <p>A bad number is answered on the page rather than as a 500 — this is a form an
     * operator fills in, and "Thu-Fri needs an amount" is actionable where a stack trace is
     * not.
     */
    @POST
    @Path("/halls/{poolId}/rate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun setRate(
        @PathParam("poolId") poolId: UUID,
        @FormParam("mode") mode: String?,
        @FormParam("amount") amount: BigDecimal?,
        @FormParam("monWed") monWed: BigDecimal?,
        @FormParam("thuFri") thuFri: BigDecimal?,
        @FormParam("satSun") satSun: BigDecimal?,
        @FormParam("holiday") holiday: BigDecimal?,
        @FormParam("inclusions") inclusions: String?,
        @FormParam("applyToBranch") applyToBranch: String?,
    ): Response {
        val lines = inclusionLines(inclusions)
        val plan: RatePlan
        if ("calendar" == mode) {
            val bands: MutableMap<RatePlan.Band, BigDecimal> = EnumMap(RatePlan.Band::class.java)
            putBand(bands, RatePlan.Band.MON_WED, monWed)
            putBand(bands, RatePlan.Band.THU_FRI, thuFri)
            putBand(bands, RatePlan.Band.SAT_SUN, satSun)
            // The holiday band is what the whole feature is for, but leaving it blank is a
            // reasonable thing for a tenant who does not price holidays differently to do --
            // so it defaults to the weekend rate rather than being refused. The measured chain's
            // own list sets the two equal at most branches.
            putBand(bands, RatePlan.Band.HOLIDAY, holiday ?: satSun)
            plan = RatePlan.byCalendar(bands, lines)
        } else {
            if (amount == null) {
                throw BadRequestException("A flat rate needs an amount.")
            }
            plan = RatePlan.flat(amount, lines)
        }
        catalogService.setRate(poolId, plan, applyToBranch != null)
        return Response.seeOther(URI.create("/admin/halls/$poolId")).build()
    }

    @POST
    @Path("/halls/{poolId}/showings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun createShowing(
        @PathParam("poolId") poolId: UUID, @FormParam("label") label: String?,
        @FormParam("startsAt") startsAt: String?, @FormParam("endsAt") endsAt: String?,
        @FormParam("filmId") filmId: UUID?,
    ): Response {
        val starts = toInstant(startsAt)
        val ends = toInstant(endsAt)
        // Both paths stay open. Picking a film is what makes a showing findable by title;
        // typing a label is what a non-film event still does, and the
        // schema stays vertical-neutral on purpose.
        //
        // One of the two is required, because `label` is NOT NULL and a session with neither
        // a film nor a name is not a showing anyone could recognise. Rejected here with a
        // message rather than left to surface as a 23502 from the insert.
        if (filmId == null && (label == null || label.isJavaBlank())) {
            throw BadRequestException("A showing needs either a film or a label.")
        }
        if (filmId != null) {
            catalogService.createShowingOfFilm(poolId, filmId, label, starts, ends, starts.minusSeconds(900))
        } else {
            catalogService.createShowing(poolId, label, starts, ends, starts.minusSeconds(900))
        }
        return Response.seeOther(URI.create("/admin/halls/$poolId")).build()
    }

    // --- Films ------------------------------------------------------------

    @POST
    @Path("/films")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun createFilm(
        @FormParam("title") title: String?,
        @FormParam("durationMinutes") durationMinutes: Int?,
        @FormParam("classification") classification: String?,
        @FormParam("synopsis") synopsis: String?,
        @FormParam("posterUrl") posterUrl: String?,
        @FormParam("trailerUrl") trailerUrl: String?,
        @FormParam("releaseDate") releaseDate: String?,
    ): Response {
        catalogService.createFilm(title, durationMinutes,
            if (classification == null || classification.isJavaBlank()) null else classification,
            synopsis, posterUrl, trailerUrl,
            if (releaseDate == null || releaseDate.isJavaBlank()) null else LocalDate.parse(releaseDate))
        return Response.seeOther(URI.create("/admin")).build()
    }

    @POST
    @Path("/showings/{sessionId}/status")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun updateShowingStatus(@PathParam("sessionId") sessionId: UUID, @FormParam("status") status: String?): Response {
        val showing = catalogService.getShowingOrThrow(sessionId)
        catalogService.updateShowingStatus(sessionId, status)
        return Response.seeOther(URI.create("/admin/halls/" + showing.resourcePoolId)).build()
    }

    /** {@code datetime-local} form input has no timezone; interpreted in the hall's own
     * timezone (Asia/Jakarta for every tenant so far, all Indonesia-domiciled). */
    private fun toInstant(datetimeLocal: String?): Instant {
        // `!!` where the Java handed a null straight to LocalDateTime.parse, which threw an NPE.
        return LocalDateTime.parse(datetimeLocal!!).atZone(JAKARTA).toInstant()
    }

    private companion object {
        private val JAKARTA: ZoneId = ZoneId.of("Asia/Jakarta")

        /** A seat file for one studio is a few kilobytes; this is what stops "the whole chain in one
         * sheet" being parsed into memory before the 2000-seat limit can refuse it. */
        private const val MAX_CSV_BYTES: Long = 1_000_000

        /**
         * A spreadsheet export is not reliably UTF-8. "Save as CSV" on a Windows Excel writes the
         * system code page, so a single accented character — in a column we do not even import —
         * would otherwise abort the whole file with a {@link MalformedInputException}. UTF-8 first
         * because that is what "CSV UTF-8" and every modern export produce; ISO-8859-1 as the
         * fallback because it decodes any byte sequence at all, and seat labels are ASCII in
         * practice, so they survive either way.
         */
        fun readCsv(file: FileUpload?): String {
            if (file == null || file.size() == 0L) {
                throw IllegalArgumentException("Choose a CSV file to import.")
            }
            if (file.size() > MAX_CSV_BYTES) {
                throw IllegalArgumentException("That file is " + (file.size() / 1024) + " KB. A seat list for " +
                    "one studio is a few KB — this looks like a whole branch or chain, and a hall is " +
                    "imported one studio at a time.")
            }
            try {
                return Files.readString(file.uploadedFile(), StandardCharsets.UTF_8)
            } catch (notUtf8: MalformedInputException) {
                try {
                    return Files.readString(file.uploadedFile(), StandardCharsets.ISO_8859_1)
                } catch (e: IOException) {
                    throw IllegalArgumentException("That file could not be read: " + e.message)
                }
            } catch (e: IOException) {
                throw IllegalArgumentException("That file could not be read: " + e.message)
            }
        }

        fun putBand(bands: MutableMap<RatePlan.Band, BigDecimal>, band: RatePlan.Band, amount: BigDecimal?) {
            if (amount == null) {
                throw BadRequestException(band.label + " needs an amount. Calendar pricing " +
                    "prices every day of the week, so a blank band is a day that cannot be sold.")
            }
            bands[band] = amount
        }

        /** The inclusions textarea: one promise per line ("Termasuk voucher minuman Rp 10.000"). */
        fun inclusionLines(inclusions: String?): List<String> {
            if (inclusions == null || inclusions.isJavaBlank()) {
                return java.util.List.of()
            }
            // Kotlin's lines() splits on the same \n, \r and \r\n as Java's, and keeps a trailing
            // empty line Java's drops; the isEmpty filter removes it either way. javaTrim() is
            // Java's trim(), and stream().toList() keeps the result unmodifiable.
            return inclusions.lines().stream().map { it.javaTrim() }.filter { line -> line.isNotEmpty() }.toList()
        }
    }
}
