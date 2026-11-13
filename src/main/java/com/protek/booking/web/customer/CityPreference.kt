package com.protek.booking.web.customer

import com.protek.booking.isJavaBlank
import com.protek.booking.repository.SiteRepository
import com.protek.booking.service.CatalogService
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Optional

/**
 * Which city the visitor is browsing — the select at the top of every public page.
 *
 * <p><b>Why a cookie rather than a path segment.</b> The site already publishes
 * {@code /cities/{city}}, and that stays: it is the crawlable, linkable form and the sitemap
 * lists it. But the city also has to survive a visitor walking off it — onto {@code /movies},
 * onto a film page, onto the booking page — and threading it through every one of those URLs
 * would put a browsing preference into the canonical of pages that are not about a city. The
 * cookie carries the preference; the URLs keep meaning what they mean.
 *
 * <p><b>Read-only here, deliberately.</b> Nothing on a GET writes the cookie, so a crawler
 * fetching {@code /cinemas} gets the configured default every time rather than a per-crawl
 * value that would make the same URL render differently on each visit. {@link CityResource} is
 * the only writer and it is a POST.
 *
 * <p><b>Not a security input.</b> This decides which of the tenant's own cities is listed
 * first, and nothing else. It is caller-supplied, so the tenancy rule applies exactly as it does to
 * the {@code Host} header: it must never reach tenancy. It cannot — every read below goes
 * through {@code CatalogService}, which resolves the tenant itself.
 */
// `open`, and every public function with it: a @RequestScoped bean is reached through a client
// proxy that subclasses it, and all-open covers @ApplicationScoped and @Path but not this scope.
// ArC would strip the `final`s at build time anyway (transform-unproxyable-classes, on by
// default); saying it here keeps the bean proxyable without depending on that rewrite.
@Named("city")
@RequestScoped
open class CityPreference {

    @Inject
    lateinit var catalogService: CatalogService

    /** Same {@link Instance} guard as {@code CurrentActor}: a template can be rendered from
     * background work, which has no request and therefore no cookie. Not an error. */
    @Inject
    lateinit var request: Instance<CurrentVertxRequest>

    /**
     * The city a visitor with no cookie and no location gets.
     *
     * <p>Matched loosely on purpose — see {@link #selected()}. "Jakarta" is the default because
     * it is where the largest share of any Indonesian chain's audience is, and a first-time
     * visitor is better served by a populated page than by an empty one.
     */
    @ConfigProperty(name = "booking.default-city", defaultValue = "Jakarta")
    lateinit var defaultCity: String

    private var cached: List<SiteRepository.CityCount>? = null

    /**
     * The cities this tenant operates in.
     *
     * <p>Memoised per request because the header renders on every page and several of those
     * pages ask again for their own body. One read either way; without this it would be two or
     * three, each its own transaction and its own RLS {@code set_config} — the exact cost that
     * was spent removing from the seat map.
     */
    open fun options(): List<SiteRepository.CityCount> {
        val current = cached ?: catalogService.listCities()
        cached = current
        return current
    }

    /** Whether to render the select at all. A single-branch tenant has one city and nothing to
     * choose between, and the branch rule holds: an operator with one cinema should not be walked
     * through a city step to reach it. */
    open fun hasChoice(): Boolean = options().size > 1

    /** Whether "use my location" could produce an answer. False until somebody fills in the site
     * latitude/longitude, which are nullable and null on a fresh install — offering a button
     * that can only ever say "no cities have coordinates" is worse than not offering it. */
    open fun isLocatable(): Boolean = options().stream().anyMatch(SiteRepository.CityCount::isLocated)

    /**
     * The city being browsed, resolved in the order a visitor would expect:
     *
     * <ol>
     *   <li>what they last chose, if it is still one of this tenant's cities;</li>
     *   <li>the configured default, matched exactly;</li>
     *   <li>the configured default matched as a prefix — this is what makes a default of
     *       "Jakarta" land on "Jakarta Pusat" or "Jakarta Selatan", which is how Indonesian
     *       cities are actually named in a branch list and why an exact match alone would
     *       silently never fire;</li>
     *   <li>the first city alphabetically, so the page is never empty.</li>
     * </ol>
     *
     * <p>The stale-cookie case in step 1 is the one worth naming: a tenant who closes their
     * last branch in a city leaves every returning visitor holding a cookie for a city that no
     * longer exists. Validating against {@link #options()} turns that into "you are now looking
     * at somewhere else" rather than an empty page with a city name at the top of it.
     */
    open fun selected(): String? {
        val cities = options()
        if (cities.isEmpty()) {
            return ""
        }
        val chosen = cookieValue()
            .filter { city -> cities.stream().anyMatch { option -> option.city!! == city } }
        if (chosen.isPresent) {
            return chosen.get()
        }
        // `city!!` wherever the Java called a method on it unguarded, so a null city still throws
        // the NPE it threw rather than being skipped.
        return cities.stream().filter { option -> option.city!!.equals(defaultCity, ignoreCase = true) }
            .findFirst()
            .or {
                cities.stream()
                    .filter { option ->
                        option.city!!.lowercase(Locale.ROOT)
                            .startsWith(defaultCity.lowercase(Locale.ROOT))
                    }
                    .findFirst()
            }
            .map { it.city }
            .orElseGet { cities[0].city }
    }

    /**
     * The cookie's value, URL-decoded.
     *
     * <p>Encoded on the way in by {@link CityResource} because real city names contain spaces
     * ("Jakarta Pusat", "Banjar Baru") and a space is not a legal cookie-octet: sent raw it is
     * either dropped or quoted, and which of those happens is the client's choice. The same
     * reasoning as {@code TemplateExtensions.urlEncoded}, for a different transport.
     */
    private fun cookieValue(): Optional<String> {
        val routing = routing() ?: return Optional.empty()
        val cookie = routing.request().getCookie(COOKIE)
        val value = cookie?.value
        if (value == null || value.isJavaBlank()) {
            return Optional.empty()
        }
        return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8))
    }

    /**
     * Where the visitor is, so the city form can send them back to it.
     *
     * <p>Path and query only — never the authority. {@link CityResource} confines this value
     * again on the way in, because by then it has been round-tripped through a form field and
     * is caller-supplied whatever this method produced.
     */
    open fun currentPath(): String {
        val routing = routing() ?: return "/"
        val uri: String? = routing.request().uri()
        return if (uri == null || uri.isJavaBlank() || uri[0] != '/') "/" else uri
    }

    private fun routing(): RoutingContext? =
        try {
            if (!request.isResolvable) {
                null
            } else {
                request.get()?.current
            }
        } catch (e: ContextNotActiveException) {
            null
        }

    companion object {
        /** The cookie the select writes. Prefixed like the rest of this application's own state so
         * it is distinguishable from any other application's cookies on the same origin. */
        const val COOKIE = "booking_city"

        /** The value {@link CityResource} writes back, so the two ends cannot disagree about the
         * encoding. */
        @JvmStatic
        internal fun encode(city: String): String = URLEncoder.encode(city, StandardCharsets.UTF_8)
    }
}
