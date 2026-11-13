package com.protek.booking.web.admin

import com.protek.booking.domain.PublicHoliday
import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import com.protek.booking.security.Roles
import com.protek.booking.service.CatalogService
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Maintaining the tenant's {@code hari libur nasional} list.
 *
 * <p><b>Why a screen at all, rather than a seed or a feed.</b> "The day before a public
 * holiday" is one of a common four-band price list's bands and no date arithmetic produces it: Indonesia's
 * national holidays are set annually by SKB 3 Menteri, most are lunar, and the {@code cuti
 * bersama} days beside them are a policy decision published the same way. A third-party
 * calendar API is ruled out explicitly — this may run on an on-premise server that has to keep selling
 * tickets when its uplink is down, and a pricing input that can time out is one that will
 * eventually price a full house at the wrong band without saying so. So somebody types the
 * decree in once a year, and this is where.
 *
 * <p>Its own resource class rather than more methods on {@link HallAdminResource}: the list is
 * tenant-wide, not per hall, so every URL here would otherwise have to carry a {@code poolId}
 * it has no use for.
 */
@Path("/admin/holidays")
@RunOnVirtualThread
// Authorization rule 1, same as every other admin resource: the nav link is UX, this is the boundary.
@RolesAllowed(Roles.TENANT_STAFF)
class HolidayAdminResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun holidays(holidays: List<PublicHoliday>?, error: String?): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun list(): TemplateInstance = Templates.holidays(catalogService.listHolidays(), null)

    /**
     * Adds a day, or renames the day already listed on that date (the repository upserts).
     *
     * <p>Errors render back onto this page rather than becoming a 500: the two things that go
     * wrong here are a mistyped date and an empty name, and both are fixed by the person
     * looking at the form.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun add(@FormParam("holidayDate") holidayDate: String?, @FormParam("name") name: String?): Response {
        try {
            catalogService.addHoliday(parseDate(holidayDate), name)
        } catch (e: IllegalArgumentException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Templates.holidays(catalogService.listHolidays(), e.message))
                .type(MediaType.TEXT_HTML)
                .build()
        }
        return Response.seeOther(URI.create("/admin/holidays")).build()
    }

    @POST
    @Path("/{holidayId}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun remove(@PathParam("holidayId") holidayId: UUID): Response {
        catalogService.removeHoliday(holidayId)
        return Response.seeOther(URI.create("/admin/holidays")).build()
    }

    private companion object {
        /** {@code <input type="date">} posts ISO-8601, so this only has to catch a hand-crafted
         * POST — but it has to catch it as a message on the page rather than as an unhandled
         * parse exception, which is why it throws the same type {@code add} already handles. */
        fun parseDate(value: String?): LocalDate {
            if (value == null || value.isJavaBlank()) {
                throw IllegalArgumentException("A holiday needs a date.")
            }
            try {
                return LocalDate.parse(value.javaTrim())
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("'$value' is not a date (expected YYYY-MM-DD).", e)
            }
        }
    }
}
