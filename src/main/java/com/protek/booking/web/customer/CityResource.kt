package com.protek.booking.web.customer

import com.protek.booking.service.CatalogService
import com.protek.booking.service.NearestCity
import com.protek.booking.web.SafePath
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration

/**
 * Choosing a city — by hand from the header select, or from the browser's location.
 *
 * **Both are POSTs.** Writing a cookie on a GET would make the same URL render
 * differently depending on the order pages were visited, and would let a link somebody was
 * sent change what their next twelve pages show. It also keeps [CityPreference] a pure
 * read, which is what lets a crawler see the configured default rather than a per-crawl value.
 */
@Path("/city")
@RunOnVirtualThread
class CityResource {

    @Inject
    lateinit var catalogService: CatalogService

    @Inject
    lateinit var nearestCity: NearestCity

    /**
     * The header select. Redirects back where the visitor was, so choosing a city on the film
     * list leaves them on the film list.
     *
     * `next` is confined to a rooted, single-slash path before it is used. It arrives
     * in a form field, so it is caller-supplied, and a `Location` built straight from one
     * of those is an open redirect — the classic shape being `//evil.example`, which a
     * browser reads as a protocol-relative absolute URL and which passes any check that only
     * asks "does it start with a slash".
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun choose(@FormParam("city") city: String?,
               @FormParam("next") @DefaultValue("/") next: String?): Response =
        Response.seeOther(URI.create(SafePath.next(next)))
            .cookie(cookieFor(city))
            .build()

    /**
     * "Use my location" — the browser supplies coordinates, the server picks the nearest city.
     *
     * **The coordinates are never stored and never logged.** They are used to rank a list
     * that is already public and are then discarded; what persists is the name of a city, which
     * is what the visitor would have picked by hand. That is proportionate handling of
     * personal data, and it is why this needs no consent record: nothing about the person is kept.
     *
     * Answers JSON rather than a redirect because the caller is `city-select.js`,
     * which already has to handle the browser's own permission prompt asynchronously. It gets
     * back the city that was chosen so it can say so before reloading — a page that silently
     * reloads into a different city looks like a bug.
     */
    @POST
    @Path("/nearest")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    fun nearest(@FormParam("latitude") latitude: Double,
                @FormParam("longitude") longitude: Double): Response {
        val found = nearestCity.nearest(catalogService.listCities(), latitude, longitude)
        if (found.isEmpty) {
            // 200 with a null city, not 404: the request was fine and the answer is "nowhere
            // near anything I know about", which the script reports rather than retries.
            return Response.ok(Chosen(null)).build()
        }
        val city = found.get().city
        return Response.ok(Chosen(city)).cookie(cookieFor(city)).build()
    }

    /** What [nearest] answers. A class rather than a bare string so the response stays
     * an object and can gain a field without breaking the script that reads it. Serialised by
     * kotlinx since 2026-10-08, in Jackson's bytes (`RestJsonWriter`). */
    @Serializable
    data class Chosen(val city: String?)

    companion object {
        /** A year. The preference is worth remembering across a season's cinema-going, and it holds
         * nothing that expiring sooner would protect — see [CityPreference]'s note that this
         * is not a security input. */
        private val REMEMBER_FOR: Duration = Duration.ofDays(365)

        private fun cookieFor(city: String?): NewCookie =
            NewCookie.Builder(CityPreference.COOKIE)
                .value(CityPreference.encode(city ?: ""))
                .path("/")
                .maxAge(REMEMBER_FOR.toSeconds().toInt())
                // SameSite=Lax rather than Strict: a customer arriving from a WhatsApp link
                // should still land in the city they chose last time, and Strict withholds the
                // cookie on exactly that first cross-site navigation. Nothing here is worth
                // protecting from CSRF — the worst a forged POST achieves is showing somebody a
                // different city, which they can change back with the select.
                .sameSite(NewCookie.SameSite.LAX)
                // Not httpOnly: city-select.js has no reason to read it today, but marking a
                // display preference httpOnly claims a protection it does not have and would
                // block the obvious future enhancement of rendering without a round trip.
                .secure(false)
                .build()
    }
}
