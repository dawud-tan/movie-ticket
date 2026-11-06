package com.protek.booking.web.admin

import com.protek.booking.domain.AppUser
import com.protek.booking.security.CurrentActor
import com.protek.booking.security.Roles
import com.protek.booking.service.StaffAdminService
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
 * A tenant managing its own staff — {@code /admin/staff}.
 *
 * <p><b>The only endpoint in the tree requiring {@link Roles#STAFF_MANAGER}.</b> Everything
 * else under {@code /admin} stays on {@link Roles#TENANT_STAFF}: a counter clerk sells
 * tickets and scans admissions exactly as before, and only the people their cinema nominated
 * can hand that access to somebody new. See {@code Roles.STAFF_MANAGER} for why that is a
 * capability derived from a column rather than a fourth role.
 *
 * <p>Its own resource class rather than more methods on {@link HallAdminResource}, on the same
 * grounds {@code HolidayAdminResource} gives: the staff list is tenant-wide, so every URL here
 * would otherwise carry a {@code poolId} it has no use for — and mixing the one
 * manager-gated screen into a class annotated for all staff is precisely the sort of drift
 * that ends with a privilege check on the wrong method.
 */
@Path("/admin/staff")
@RunOnVirtualThread
// Authorization rule 1, as everywhere: the nav link in base.html is UX, THIS is the boundary.
@RolesAllowed(Roles.STAFF_MANAGER)
class StaffAdminResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun staff(staff: List<AppUser>?, selfAppUserId: UUID?, error: String?): TemplateInstance
    }

    @Inject
    lateinit var staffAdmin: StaffAdminService
    @Inject
    lateinit var currentActor: CurrentActor

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun list(): TemplateInstance = render(null)

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun add(@FormParam("email") email: String?): Response {
        try {
            staffAdmin.invite(email)
        } catch (e: StaffAdminService.StaffAdminException) {
            return badRequest(e)
        }
        return backToList()
    }

    /**
     * Promote or demote. One endpoint taking the target state rather than two, because the
     * button that sends it is rendered from the row's current state — two endpoints would let
     * a stale page demote someone who was already demoted and report success.
     */
    @POST
    @Path("/{appUserId}/manager")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun setManager(
        @PathParam("appUserId") appUserId: UUID,
        @FormParam("canManage") canManage: Boolean,
    ): Response {
        try {
            staffAdmin.setManager(appUserId, canManage)
        } catch (e: StaffAdminService.StaffAdminException) {
            return badRequest(e)
        }
        return backToList()
    }

    @POST
    @Path("/{appUserId}/active")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun setActive(
        @PathParam("appUserId") appUserId: UUID,
        @FormParam("active") active: Boolean,
    ): Response {
        try {
            staffAdmin.setActive(appUserId, active)
        } catch (e: StaffAdminService.StaffAdminException) {
            return badRequest(e)
        }
        return backToList()
    }

    /** Errors render the page back with the message, rather than becoming a 500: everything
     * {@code StaffAdminService} throws is fixable by the person looking at the form. */
    private fun badRequest(e: StaffAdminService.StaffAdminException): Response =
        Response.status(Response.Status.BAD_REQUEST)
            .entity(render(e.message))
            .type(MediaType.TEXT_HTML)
            .build()

    private fun render(error: String?): TemplateInstance {
        // The acting identity is passed to the template so it can mark "you" in the list —
        // a manager about to deactivate a row should be able to see it is their own.
        return Templates.staff(staffAdmin.listStaff(), currentActor.appUserId().orElse(null), error)
    }

    private fun backToList(): Response = Response.seeOther(URI.create("/admin/staff")).build()
}
