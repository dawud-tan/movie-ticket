package com.protek.booking.web

import io.quarkus.oidc.JavaScriptRequestChecker
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * Tells the web (code-flow) OIDC tenant that an htmx request is a JavaScript request, so that a
 * signed-out one is answered with a status instead of a redirect to Google.
 *
 * <p><b>Why.</b> Unauthenticated, the code flow answers every request with a 302 to
 * accounts.google.com. htmx follows it, the cross-origin request fails, and the page sees status
 * 0 — so `seatmap-selection.js` said "could not reach the server" to a customer whose session had
 * expired mid-selection, and its session-expired notice with the sign-in link never showed.
 * With this bean and
 * `quarkus.oidc.authentication.java-script-auto-redirect=false`, the challenge is **499**,
 * Quarkus's code for "a JavaScript request needs to sign in", which the script treats as a 401.
 *
 * <p>The property alone does nothing for htmx: Quarkus's own check looks for
 * `X-Requested-With: JavaScript` (or `XMLHttpRequest`), and htmx sends neither. A full-page
 * navigation carries no `HX-Request`, so it still gets the 302 to sign in. The bearer tenant
 * (`/api/app`) has no redirect to suppress and keeps its 401s.
 */
@ApplicationScoped
class HtmxRequestChecker : JavaScriptRequestChecker {
    override fun isJavaScriptRequest(context: RoutingContext): Boolean =
        context.request().getHeader("HX-Request") == "true"
}
