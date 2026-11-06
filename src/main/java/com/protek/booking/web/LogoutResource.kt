package com.protek.booking.web

import io.quarkus.oidc.OidcSession
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import java.net.URI

/**
 * Signs the user out of <em>this application</em> by dropping the OIDC session cookie.
 *
 * <p><b>Why this is hand-written instead of {@code quarkus.oidc.logout.path}.</b> That property
 * enables RP-Initiated Logout, which asks the provider to end the session at its end. Quarkus
 * refuses to start when the provider does not advertise an {@code end_session_endpoint}, and Google
 * does not advertise one — the app died at boot with "The application supports RP-Initiated Logout
 * but the OpenID Provider does not advertise the end_session_endpoint", before printing so much as
 * a banner. Found by starting with {@code OIDC_ENABLED=true} rather than by reading the config, and
 * worth remembering: it fails at startup, not at the moment someone clicks sign out, so enabling
 * OIDC and never testing logout would still have taken the whole service down.
 *
 * <p>Local logout is also the behaviour we actually want. RP-Initiated Logout against Google would
 * sign the customer out of their Google account — Gmail included — because they finished buying a
 * cinema ticket. Clearing our own session and leaving their Google session alone is the correct
 * scope for a tenant booking site.
 *
 * <p>{@code GET} rather than {@code POST} because {@code base.html} renders sign-out as a plain
 * link. That is CSRF-forgeable in the sense that a third-party page could force a logout, which
 * costs the user a re-login and nothing else; a POST form is the stricter choice if this ever
 * carries more weight than session state.
 */
@Path("/logout")
@RunOnVirtualThread
class LogoutResource {

    @Inject
    lateinit var oidcSession: OidcSession
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    fun logout(): Response {
        val home = Response.seeOther(URI.create("/")).build()
        // Anonymous covers two cases that both must not blow up: a stray /logout hit with no
        // session, and the whole OIDC-disabled deployment, where there is no session to clear and
        // logout() has nothing to act on. (The Java's identity == null is the uninitialised
        // lateinit here.)
        if (!this::identity.isInitialized || identity.isAnonymous) {
            return home
        }
        // OidcSession has no blocking logout; awaiting its Uni is safe on the virtual thread.
        oidcSession.logout().await().indefinitely()
        return home
    }
}
