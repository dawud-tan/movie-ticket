package com.protek.booking.web.admin

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.security.Roles
import com.protek.booking.service.AdmissionService
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
import java.util.UUID

/**
 * The staff scan endpoint — the thing that turns a barcode into an admitted seat.
 *
 * **Scoped to one showing by its path**, because an usher works one door. See
 * [AdmissionService] for why that scoping is a correctness property and not merely
 * convenient navigation.
 *
 * **Why a text input and not a camera.** The input takes a scan from a USB or Bluetooth
 * barcode scanner, which types its payload and presses Enter exactly like a keyboard — the
 * hardware every cinema counter already has, needing no permission prompt, no HTTPS camera
 * grant and no app install. It equally takes a code typed by hand, which is the path that still
 * works when the customer's phone is flat, and the only path a walk-in with a printed ticket
 * ever needed. A camera-based scanner is a later addition on top of this, not a replacement for
 * it.
 *
 * **`@RolesAllowed(TENANT_STAFF)` is the boundary**, and it is the whole of it here:
 * unlike a reservation page there is no per-customer ownership question to add on top —
 * admitting is a staff action over any ticket for the showing they are manning, and RLS keeps
 * the showing itself inside one tenant.
 */
@Path("/admin/showings/{sessionId}/admission")
@RunOnVirtualThread
@RolesAllowed(Roles.TENANT_STAFF)
class AdmissionResource {

    /** Qute's Kotlin form of a checked template: an `object` of `@JvmStatic external` functions,
     * which compile to the static native methods the Java form declared. */
    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun admission(
            hall: ResourcePool, showing: ResourcePoolSession, result: AdmissionService.ScanResult?,
        ): TemplateInstance

        @JvmStatic
        external fun admissionPanel(
            showing: ResourcePoolSession, result: AdmissionService.ScanResult,
        ): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    @Inject
    lateinit var admissionService: AdmissionService

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun door(@PathParam("sessionId") sessionId: UUID): TemplateInstance {
        val showing = catalogService.getShowingOrThrow(sessionId)
        val hall = catalogService.getHallOrThrow(showing.resourcePoolId)
        return Templates.admission(hall, showing, null)
    }

    /**
     * One scan. Returns the panel fragment rather than a redirect, so the queue keeps moving:
     * htmx swaps the result in and the fresh input inside it takes focus, leaving the scanner
     * ready for the next ticket without a page load between customers.
     *
     * A POST that renders rather than redirecting would normally invite a double submit on
     * refresh. It does not matter here, and that is a property of the design rather than an
     * oversight — rescanning the same token is exactly what `admitted_at IS NULL` makes
     * safe, and the second attempt reports ALREADY_ADMITTED instead of admitting twice.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun scan(
        @PathParam("sessionId") sessionId: UUID,
        @FormParam("admissionToken") admissionToken: String?,
    ): TemplateInstance {
        val showing = catalogService.getShowingOrThrow(sessionId)
        return Templates.admissionPanel(showing, admissionService.scan(sessionId, admissionToken))
    }
}
