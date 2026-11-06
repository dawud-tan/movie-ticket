package com.protek.booking.i18n

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import java.net.URI

/**
 * Makes {@code /id/movies} route to the same resource method as {@code /movies}.
 *
 * <p><b>One rewrite rather than a second copy of every {@code @Path}.</b> The alternative was a
 * parallel resource class per language, or a language path parameter threaded through every
 * method signature in the tree. This strips the prefix before matching and lets the forty-odd
 * existing resource methods stay exactly as they are; what language it was is still readable
 * afterwards because the rewrite touches the JAX-RS view of the request and not Vert.x's, so
 * {@link LanguagePreference} reads the prefix off {@code HttpServerRequest.path()} as it always
 * was.
 *
 * <p><b>Pre-matching, which is the whole point</b> — after matching there is nothing left to
 * decide. Note the contrast with {@code CanonicalAuthorityFilter}, which is post-matching for
 * the opposite reason: it needs to know which resource class was selected. The two do not
 * interfere, because this one only changes the path and that one only reads the matched class's
 * package — and {@code com.protek.booking.i18n} is inside the package it guards, so
 * {@link LanguageResource} is served under the canonical authority like every other page here.
 *
 * <p><b>Nothing here validates or refuses.</b> A path that carries no known prefix is left
 * untouched and matches whatever it always matched; {@code /identity} is not {@code /id}, which
 * is why {@link Language#split} matches whole segments rather than calling
 * {@code startsWith("/id")}.
 */
@ApplicationScoped
class LanguagePathFilter {

    // On the Vert.x event loop, measured 2026-10-23: before matching there is no endpoint to take
    // a thread from, and @RunOnVirtualThread on a filter fails the build. Keep it pure string work.
    @ServerRequestFilter(preMatching = true)
    fun stripLanguagePrefix(request: ContainerRequestContext) {
        val uri = request.uriInfo.requestUri
        val split = Language.split(uri.rawPath)
        if (!split.wasPrefixed()) {
            return
        }
        // Rebuilt from the raw components rather than through UriBuilder: UriBuilder.replacePath
        // percent-encodes what it is given, and the path it would be given here is already
        // encoded — /id/cities/Jakarta%20Pusat would come back out as %2520.
        val rewritten = StringBuilder()
        rewritten.append(uri.scheme).append("://").append(uri.rawAuthority)
                .append(split.path)
        if (uri.rawQuery != null) {
            rewritten.append('?').append(uri.rawQuery)
        }
        request.setRequestUri(URI.create(rewritten.toString()))
    }

    /**
     * Says out loud that an HTML page's language depends on who asked.
     *
     * <p>No shared cache sits in front of this deployment today — the server is directly reachable,
     * and nothing but the service worker sets {@code Cache-Control} at all. This is for the
     * browser's own heuristic caching, which is what would otherwise re-serve the page in the
     * language the visitor just switched away from. {@code Cookie} is named as well as
     * {@code Accept-Language} because the cookie is the stronger of the two inputs
     * ({@link LanguagePreference}).
     *
     * <p>HTML only. A JSON or XML response here carries no interface copy, and {@code Vary} on
     * the sitemap would be a claim that it varies, which it does not.
     */
    @ServerResponseFilter
    fun varyOnWhateverChoseTheLanguage(response: ContainerResponseContext) {
        val type: MediaType? = response.mediaType
        if (type != null && type.isCompatible(MediaType.TEXT_HTML_TYPE)) {
            response.headers.putSingle(HttpHeaders.VARY, "Accept-Language, Cookie")
        }
    }
}
