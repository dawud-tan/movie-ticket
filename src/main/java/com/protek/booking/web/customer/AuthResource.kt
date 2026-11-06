@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.web.customer

import com.protek.booking.json.UuidSerializer
import com.protek.booking.security.CurrentActor
import io.quarkus.security.Authenticated
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.net.URI
import java.util.UUID

/**
 * The sign-in entry point. `@Authenticated` is what triggers it: with
 * `quarkus.oidc.application-type=web-app`, an unauthenticated request to a protected
 * path is redirected to Google's consent screen and back, so this endpoint's body only runs
 * once an identity exists — at which point the only thing left to do is send the browser
 * where it was going.
 *
 * There is deliberately no password form, no registration page, and no "forgot password"
 * flow anywhere in this codebase: Google SSO via OIDC, and a verified email address is
 * proportionate identity for buying a cinema seat. Not building those pages is also not
 * storing password hashes, not running a reset-token lifecycle, and not being the party that
 * leaks credentials.
 */
@Path("/")
@RunOnVirtualThread
class AuthResource {

    @Inject
    lateinit var currentActor: CurrentActor

    @GET
    @Path("/login")
    @Authenticated
    fun login(): Response = Response.seeOther(URI.create("/")).build()

    /**
     * What the server believes about the caller. Useful when diagnosing a role or tenant
     * mapping ("why am I getting 403"), which is otherwise invisible from the browser.
     *
     * Returns only this caller's own resolved identity, never anyone else's, and no token
     * material — so it is safe to leave reachable by any signed-in user rather than gating it
     * to admins, who are precisely the people least likely to need it.
     */
    @GET
    @Path("/me")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    fun me(): WhoAmI =
        WhoAmI(
            currentActor.email().orElse(null),
            when {
                currentActor.isStaff() -> "tenant_staff"
                currentActor.isPlatformAdmin() -> "platform_admin"
                currentActor.isCustomer() -> "customer"
                else -> null
            },
            currentActor.appUserId().orElse(null),
            currentActor.customerId().orElse(null))

    /** kotlinx.serialization since 2026-10-08, in Jackson's bytes (`RestJsonWriter`). */
    @Serializable
    data class WhoAmI(val email: String?, val role: String?, val appUserId: UUID?, val customerId: UUID?)
}
