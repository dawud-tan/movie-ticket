package com.protek.booking.web.customer

import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * {@code /reservations} → {@code /orders}.
 *
 * <p>A class of its own because {@code OrderResource} now claims {@code @Path("/orders")}, and a
 * JAX-RS resource has exactly one class-level path. The same reason a second resource class was
 * once considered for {@code {groupId}} and rejected — except here it is the redirect, not the
 * live page, that has nowhere else to live, so the trade goes the other way.
 *
 * <p><b>301, not 302.</b> This path is in bookmarks, and it is where every hold from the start
 * redirected the customer after a successful booking. A permanent redirect moves a client onto
 * the new address once; a temporary one asks every client to keep checking forever.
 *
 * <p><b>Only the GETs.</b> The three POSTs on {@code OrderResource} — pay, confirm, cancel — are
 * submitted by forms this application renders, and it renders the new paths. Redirecting a POST
 * is a request whose body most clients silently drop, which on the pay endpoint would look
 * exactly like a payment that did nothing.
 *
 * <p><b>No role check, deliberately.</b> These methods read nothing and answer with a
 * {@code Location} built from the path they were given, so an anonymous caller learns only what
 * they already typed. Putting {@code @RolesAllowed} here would send a signed-out customer
 * following an old bookmark to the sign-in page and then, after signing in, to the URL they were
 * redirected from — the one that no longer exists.
 */
@Path("/reservations")
@RunOnVirtualThread
class MovedPathsResource {

    @GET
    fun list(): Response = movedTo("/orders")

    @GET
    @Path("/{groupId}")
    fun detail(@PathParam("groupId") groupId: UUID): Response = movedTo("/orders/$groupId")

    private companion object {
        fun movedTo(path: String): Response =
            Response.status(Response.Status.MOVED_PERMANENTLY).location(URI.create(path)).build()
    }
}
