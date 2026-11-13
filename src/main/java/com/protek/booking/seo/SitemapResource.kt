package com.protek.booking.seo

import com.protek.booking.i18n.Language
import com.protek.booking.service.CatalogService
import com.protek.booking.web.TemplateExtensions
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The public catalogue as a sitemap.
 *
 * <p><b>Why {@code /sitemap-showtimes.xml} and not {@code /sitemap.xml}.</b> This deployment
 * already serves a hand-maintained {@code /sitemap.xml} from {@code META-INF/resources},
 * belonging to the other application merged into this deployment. Two things would answer one
 * path, with the winner decided by whether the static handler or the JAX-RS router gets there
 * first — which is not a thing to leave to discover in production. A second sitemap is
 * explicitly allowed by the protocol and is listed alongside the first in {@code robots.txt}.
 *
 * <p><b>Why it is generated and not a file.</b> A cinema's catalogue turns over weekly. The
 * static sitemap next door lists articles that do not, which is why hand-maintaining that one
 * is reasonable and hand-maintaining this one would not be.
 *
 * <p><b>It lives in {@code com.protek.booking}, which is load-bearing.</b>
 * {@code CanonicalAuthorityFilter} decides what the booking app answers by the package of the
 * matched resource class, so being here is what stops this listing the cinema's catalogue to
 * a request that arrived on some other hostname pointed at the same socket.
 *
 * <p><b>Every listed page is listed once per language, each entry carrying the whole
 * {@code xhtml:link} set including itself.</b> That is the shape Google documents, and the
 * self-referential half is the same requirement {@code PublicUrls.alternates} exists to make
 * unmissable on the page itself — a set that omits the URL carrying it is discarded whole. It
 * doubles the entry count, which is the cost of the Indonesian URLs being in here at all:
 * before this they were in no sitemap, so the only way a crawler reached {@code /id/…} was by
 * following an on-page {@code hreflang}.
 *
 * <p><b>A page joins the set by being listed with {@link #localized}, never by being listed.</b>
 * {@code loc} exists beside it and emits one unprefixed URL with no alternates, for exactly the
 * reason {@code PageMeta} makes alternates a thing a page is <em>given</em>: an
 * {@code xhtml:link} is a claim that the other URL is this page in another language, and during
 * a page-by-page translation that claim is false for most pages. Everything listed below is
 * translated today, so everything below uses {@code localized}; the day an untranslated page is
 * added here, the compiler will not stop anyone, but the two method names will.
 *
 * <p>No {@code <lastmod>}, and that is deliberate. The honest last-modified date for a branch
 * page is when its showings last changed, which is not a column anything here holds;
 * {@code created_at} would be available and would be a different fact wearing the same name.
 * Google discounts a {@code lastmod} it decides is unreliable, so a wrong one is worse than
 * none. No {@code changefreq} or {@code priority} either — Google has said for years that it
 * ignores both.
 */
@Path("/sitemap-showtimes.xml")
@RunOnVirtualThread
class SitemapResource {

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var publicUrls: PublicUrls

    @GET
    @Produces(MediaType.APPLICATION_XML)
    fun sitemap(): Response {
        if (!publicUrls.isConfigured()) {
            // 503, not 404. A sitemap's <loc> must be an absolute URL, so without a configured
            // origin there is nothing valid to emit — but the missing piece is one line of
            // deployment configuration that is expected to arrive, and 404 tells a crawler to
            // forget the sitemap exists while 503 tells it to come back. Same reasoning as
            // SignInLink's fallback: unconfigured is a real state, not a fault.
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.TEXT_PLAIN)
                .entity("No sitemap: booking.public.https-base-url (PUBLIC_HTTPS_BASE_URL) is "
                        + "not set, so this deployment does not know its own public origin.\n")
                .build()
        }

        val xml = StringBuilder(4096)
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n")
            // Declared whether or not any entry uses it. The namespace is what makes the
            // xhtml:link elements below legal, and a sitemap that names an undeclared
            // prefix is not XML at all — a failure a crawler reports and nobody here sees.
            .append("        xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n")

        // The two entry points, then the browse path in the order a visitor walks it.
        localized(xml, "/")
        localized(xml, "/movies")
        for (city in catalogService.listCities()) {
            localized(xml, "/cities/" + TemplateExtensions.urlEncoded(city.city))
        }
        for (site in catalogService.listSites()) {
            localized(xml, "/cinemas/" + site.slug)
        }
        // Halls are uuid-keyed and stay that way — nobody searches for "Studio 1" — but they
        // are the page that links to a seat map, so leaving them out would leave the crawler
        // a step short of the thing that sells a ticket.
        for (hall in catalogService.listHalls()) {
            localized(xml, "/halls/" + hall.resourcePoolId)
        }
        // listNowPlaying, not listFilms: this should list what the site links to. A film with
        // no upcoming showing is not on /movies either, and submitting a page that says
        // "no upcoming showings" is asking to be judged on it.
        for (film in catalogService.listNowPlaying()) {
            localized(xml, "/movies/" + film.slug)
        }
        // Individual showings are deliberately absent. They are the most numerous URLs by an
        // order of magnitude for a chain, each one stops being useful within days, and each is
        // already reachable in one hop from a hall page that is listed. The ScreeningEvent
        // markup on /movies/{slug} points at them by URL, which is the discovery path that
        // actually matters.

        xml.append("</urlset>\n")
        return Response.ok(xml.toString(), MediaType.APPLICATION_XML).build()
    }

    /**
     * One {@code <url>} per language for a translated page, each with the full alternate set.
     *
     * <p>The alternates are built once and reused across the language entries on purpose: the
     * set is a property of the <em>page</em>, not of which spelling of it is being listed, and
     * rebuilding it per entry is how the entries end up disagreeing.
     */
    private fun localized(xml: StringBuilder, unprefixedPath: String) {
        val alternates = publicUrls.alternates(unprefixedPath)
        for (language in Language.all()) {
            publicUrls.absolute(language.urlFor(unprefixedPath)).ifPresent { url ->
                xml.append("    <url>\n        <loc>").append(xmlEscape(url)).append("</loc>\n")
                for (alternate in alternates) {
                    xml.append("        <xhtml:link rel=\"alternate\" hreflang=\"")
                        .append(xmlEscape(alternate.hreflang))
                        .append("\" href=\"").append(xmlEscape(alternate.href))
                        .append("\"/>\n")
                }
                xml.append("    </url>\n")
            }
        }
    }

    /**
     * One unprefixed {@code <url>} and no alternates — for a page whose template is not
     * translated. Unused today, and kept rather than deleted for the reason the class javadoc
     * gives: it is the half of the pair that makes "which pages claim to have translations" a
     * choice somebody makes rather than something that happens.
     */
    @Suppress("unused")
    private fun loc(xml: StringBuilder, path: String) {
        publicUrls.absolute(path).ifPresent { url ->
            xml.append("    <url><loc>").append(xmlEscape(url)).append("</loc></url>\n")
        }
    }

    private companion object {
        /**
         * XML-escapes a URL.
         *
         * <p>Belt and braces rather than dead code: every path here is built from a slug, a uuid
         * or {@code urlEncoded} output, all of which are already free of these characters. But the
         * origin is operator-supplied configuration, and the day one of these paths stops being
         * pre-encoded is the day an unescaped {@code &} silently makes the whole sitemap
         * unparseable — an error a crawler reports and nobody here would see.
         */
        fun xmlEscape(value: String?): String =
            value!!.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
    }
}
