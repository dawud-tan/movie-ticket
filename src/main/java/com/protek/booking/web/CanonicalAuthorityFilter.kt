package com.protek.booking.web

import com.protek.booking.javaTrim
import io.quarkus.websockets.next.HttpUpgradeCheck
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import jakarta.annotation.PostConstruct
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import org.jboss.resteasy.reactive.server.SimpleResourceInfo
import java.util.Locale
import java.util.Optional
import java.util.stream.Collectors

/**
 * Serves the booking application only under its own canonical authority; anything else gets a 404.
 *
 * <p><b>Why the resource's package and not the request path.</b> {@code ShowtimeResource} and
 * {@code AuthResource} are both {@code @Path("/")} — the booking app owns the root — so there is
 * no path prefix that means "this is the cinema". Matching on the resource class that was
 * selected is the only discriminator that actually separates this application from anything
 * else sharing the deployment.
 *
 * <p><b>Why post-matching.</b> {@code @PreMatching} runs before a resource has been chosen, so
 * {@code SimpleResourceInfo} would have nothing to report and the filter could not tell the two
 * applications apart. As a post-matching filter it never runs for a request that matched nothing —
 * which is fine, since that is already a 404.
 *
 * <p><b>The sockets too.</b> A websockets-next upgrade (the live seat map and order,
 * {@code AppLiveSocket.kt}) never reaches a REST filter, so the same rule also runs as an
 * {@link HttpUpgradeCheck}, before the 101. Found 2026-10-24: an upgrade naming a bare IP was
 * answered. The package test there is on the endpoint id, which defaults to the endpoint's class
 * name ({@code WebSocket.FCQN_NAME}).
 *
 * <p><b>Where the authority comes from.</b> {@code HttpServerRequest.authority()} is the one API
 * that answers this across protocol versions: Vert.x reads HTTP/1.1's {@code Host} header and
 * HTTP/2 and HTTP/3's {@code :authority} pseudo-header into the same value, so nothing here has to
 * branch on the wire protocol. That matters here, because this deployment serves h2 ({@code
 * quarkus.http.http2=true}) alongside HTTP/1.1.
 *
 * <p><b>What this is and is not.</b> It is routing hygiene: it stops the cinema answering on an
 * unintended hostname or on a bare IP. It is <b>not</b> an access control, because the authority
 * is supplied by the caller — anyone can send the canonical value to the same socket. Nothing
 * security-relevant may ever be derived from it, and in particular it must never become an input
 * to tenancy: the {@code Host} header must never decide whose data is returned, and
 * {@code CurrentTenant} deliberately exposes no API that would accept one. This class only decides
 * <em>whether</em> to answer, never <em>what</em> to answer with.
 *
 * <p><b>Disabled unless configured</b>, which is what keeps dev mode and the whole test suite
 * working against {@code localhost}. Set it with {@code BOOKING_CANONICAL_AUTHORITY} (README);
 * it is a runtime property, so it is changed with a restart, not a rebuild.
 *
 * <p><b>The cinema's security headers ride on the same package test</b> ({@link #addSecurityHeaders}),
 * configured or not, because another application sharing the deployment may run inline scripts and
 * third-party CDNs that the cinema's Content-Security-Policy would refuse.
 */
@ApplicationScoped
class CanonicalAuthorityFilter : HttpUpgradeCheck {

    @ConfigProperty(name = "booking.canonical-authority")
    lateinit var configured: Optional<List<String>>

    private lateinit var allowed: Set<String>

    @PostConstruct
    fun normalise() {
        // Lower-cased once here rather than per request: hostnames are case-insensitive, and a
        // config value typed with a capital would otherwise 404 the entire site.
        allowed = configured.orElseGet { java.util.List.of() }.stream()
            .map { value -> value.javaTrim().lowercase(Locale.ROOT) }
            .filter { value -> !value.isEmpty() }
            .collect(Collectors.toUnmodifiableSet())
        if (!allowed.isEmpty()) {
            LOG.infof("Booking app is served only under authority %s; other authorities get 404", allowed)
        }
    }

    // Runs on the endpoint's virtual thread (measured 2026-10-23); a filter cannot carry
    // @RunOnVirtualThread itself, Quarkus fails the build.
    @ServerRequestFilter
    fun rejectForeignAuthority(resource: SimpleResourceInfo, routing: RoutingContext): Optional<Response> {
        if (allowed.isEmpty()) {
            return Optional.empty()
        }
        val target: Class<*>? = resource.resourceClass
        if (target == null || !target.name.startsWith(GUARDED_PACKAGE)) {
            // Every other application in this deployment is none of this filter's business.
            return Optional.empty()
        }
        return if (isCanonical(routing.request())) Optional.empty()
        else Optional.of(Response.status(Response.Status.NOT_FOUND).build())
    }

    // Enforced. Renaming CSP_HEADER to Content-Security-Policy-Report-Only is the way back if a page
    // breaks: the browser then reports each violation in its console instead. No I/O, so it may run
    // anywhere.
    @ServerResponseFilter
    fun addSecurityHeaders(resource: SimpleResourceInfo?, response: ContainerResponseContext) {
        val target: Class<*>? = resource?.resourceClass
        if (target == null || !target.name.startsWith(GUARDED_PACKAGE)) {
            return
        }
        response.headers.putSingle(CSP_HEADER, POLICY)
        response.headers.putSingle("X-Content-Type-Options", "nosniff")
        // An order's URL is its group id, so no other site is told it: the origin only. Browsers
        // default to this now; the header is for the ones that still send the whole URL.
        response.headers.putSingle("Referrer-Policy", "strict-origin-when-cross-origin")
        // "Use my location" (city-select.js) is the one device API these pages call.
        response.headers.putSingle("Permissions-Policy", "geolocation=(self), camera=(), microphone=(), payment=(), usb=()")
    }

    override fun appliesTo(endpointId: String): Boolean = endpointId.startsWith(GUARDED_PACKAGE)

    // Uni because HttpUpgradeCheck is a Quarkus SPI, not a resource, as AppUserAugmentor is
    // (one of the few exceptions to the blocking style). Both answers are already complete, and nothing here does I/O.
    override fun perform(context: HttpUpgradeCheck.HttpUpgradeContext): Uni<HttpUpgradeCheck.CheckResult> =
        if (allowed.isEmpty() || isCanonical(context.httpRequest())) HttpUpgradeCheck.CheckResult.permitUpgrade()
        else HttpUpgradeCheck.CheckResult.rejectUpgrade(Response.Status.NOT_FOUND.statusCode)

    private fun isCanonical(request: HttpServerRequest): Boolean {
        val authority = authorityOf(request)
        if (allowed.contains(authority)) {
            return true
        }
        // DEBUG, not WARN: on a public address this fires for every stray scan, and a log level
        // that invites someone to silence the category would take the real diagnosis with it.
        LOG.debugf("404 for %s %s: authority '%s' is not %s", request.method(), request.path(), authority, allowed)
        return false
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(CanonicalAuthorityFilter::class.java)

        /** Trailing dot so a hypothetical {@code com.protek.bookingsomethingelse} cannot match. */
        const val GUARDED_PACKAGE = "com.protek.booking."

        const val CSP_HEADER = "Content-Security-Policy"

        /**
         * Every script and stylesheet is a file on this origin (CspReadyTemplatesTest), except the
         * Firebase SDK from gstatic (seatmap-push.js and the service worker), which then calls the
         * Installations and FCM registration APIs. Images take any https host: posters are URLs
         * staff type, and the QRIS code is an image Midtrans hosts. Forms post here only, but a
         * signed-out POST to a protected page is redirected to Google's sign-in, and form-action
         * governs that redirect too.
         */
        const val POLICY = "default-src 'self'; script-src 'self' https://www.gstatic.com; " +
            "connect-src 'self' https://firebaseinstallations.googleapis.com https://fcmregistrations.googleapis.com; " +
            "img-src 'self' https:; style-src 'self'; worker-src 'self'; frame-ancestors 'none'; " +
            "form-action 'self' https://accounts.google.com; base-uri 'self'; object-src 'none'"

        /**
         * The request's authority as {@code host[:port]}, lower-cased.
         *
         * <p>The port is omitted when the caller omitted it, rather than being defaulted from the
         * scheme — so {@code www.cinema.example} and {@code www.cinema.example:8443} are distinct
         * configured values. Being explicit is the safer direction: inferring 443 would silently widen
         * a rule someone wrote narrowly.
         *
         * <p>Empty for a request with no authority at all (HTTP/1.0 without {@code Host}), which then
         * matches nothing and is refused — correct, since such a request cannot be for a named site.
         */
        fun authorityOf(request: HttpServerRequest): String {
            val authority = request.authority()
            if (authority == null || authority.host() == null) {
                return ""
            }
            val host = authority.host().lowercase(Locale.ROOT)
            return if (authority.port() < 0) host else host + ":" + authority.port()
        }
    }
}
