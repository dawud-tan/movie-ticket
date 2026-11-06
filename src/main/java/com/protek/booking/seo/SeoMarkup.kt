package com.protek.booking.seo

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.Site
import com.protek.booking.isJavaBlank
import com.protek.booking.repository.ResourcePoolSessionRepository.FilmShowing
import com.protek.booking.service.CalendarPricing
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The schema.org markup the public pages carry.
 *
 * <p><b>What this is worth, stated so nobody re-derives it hopefully.</b> Google turned event
 * <em>rich snippets</em> off on 2024-03-04; the enhanced blue-link card people picture when
 * they say "rich card" for a screening is gone and is not coming back. {@code Event} is still
 * a supported type feeding the interactive events experience, and {@code ScreeningEvent}
 * inherits that as a subtype — Google's documentation says "event types on schema.org" and
 * never enumerates them, which is why the type below is written as
 * {@code ["Event", "ScreeningEvent"]} rather than the subtype alone. {@code MovieTheater} is
 * the one that is still a live rich result in its own right, and it is also the one that
 * matters for the query this was built for: a local "bioskop" plus the town's name.
 *
 * <p><b>And what it is not.</b> The showtimes carousel in Search and Maps is not built from
 * markup at all — it is fed by commercial showtime feeds (Boxoffice Co., International
 * Showtimes). No amount of JSON-LD puts a chain's branches into it. That is a data-feed
 * partnership; it must not be promised to a cinema on the strength of this file.
 *
 * <p>Every method returns a string that is already safe to drop into a script element — see
 * {@link JsonLd} for why the escaping cannot be left to the template — and returns empty
 * rather than throwing when it has nothing to say.
 */
@ApplicationScoped
class SeoMarkup {

    @Inject
    lateinit var jsonLd: JsonLd
    @Inject
    lateinit var calendarPricing: CalendarPricing
    @Inject
    lateinit var publicUrls: PublicUrls

    /**
     * A branch as a {@code MovieTheater}.
     *
     * <p>{@code address} is the property Google requires of a place, and the site table is what made it
     * expressible: {@code city} is {@code NOT NULL} so {@code addressLocality} always has a
     * value, with {@code province} and {@code address} filling in when they are set. Before
     * the site table a "cinema" was a {@code resource_pool} row with a free-text address and no city at
     * all, which is why this was not worth writing then.
     */
    fun movieTheater(site: Site?, halls: List<ResourcePool>?): String {
        if (site == null) {
            return ""
        }
        val builder = JsonLd.document("MovieTheater")
            .put("name", site.name)
            .put("address", address(site))
            .put("geo", geo(site))
            // A cinema's screen count is the one MovieTheater-specific property we can
            // answer honestly, and it is the number a chain is compared on.
            .put("screenCount", if (halls == null || halls.isEmpty()) null else halls.size)
        publicUrls.absolute("/cinemas/" + site.slug).ifPresent { url -> builder.put("url", url) }
        return jsonLd.render(builder.build())
    }

    /**
     * A film as a {@code Movie}, together with a {@code ScreeningEvent} for each upcoming
     * showing of it.
     *
     * <p>One script element holding an array, rather than one element per screening: a title
     * playing across a 38-branch chain is dozens of nodes, and a page carrying dozens of
     * script elements is harder to read and no better understood.
     *
     * <p><b>The screenings are why {@code ScreeningEvent} is worth preferring over plain
     * {@code Event} at all</b> — {@code workPresented} is the property that says an event is a
     * showing <em>of</em> something, and the film table is what supplies the something. On a branch where
     * a showing's title was free text on {@code resource_pool_session.label}, there was
     * nothing to point it at.
     */
    fun movieWithScreenings(film: Film?, showings: List<FilmShowing>?): String {
        if (film == null) {
            return ""
        }
        val nodes = ArrayList<Map<String, Any?>>()
        nodes.add(movieNode(film))
        if (showings != null) {
            for (showing in showings) {
                nodes.add(screeningEvent(film, showing))
            }
        }
        return jsonLd.renderGraph(nodes)
    }

    private fun movieNode(film: Film): Map<String, Any?> {
        val builder = JsonLd.document("Movie")
            .put("name", film.title)
            .put("description", film.synopsis)
            .put("image", film.posterUrl)
            .put("contentRating", film.classification)
            .put("datePublished", film.releaseDate?.toString())
            .put("duration", isoDuration(film.durationMinutes))
            .put("trailer", if (film.trailerUrl == null || film.trailerUrl.isJavaBlank()) null
                else JsonLd.document("VideoObject")
                    .put("name", film.title + " trailer")
                    .put("url", film.trailerUrl)
                    .buildNested())
        publicUrls.absolute("/movies/" + film.slug).ifPresent { url -> builder.put("url", url) }
        return builder.buildNested()
    }

    /**
     * One screening.
     *
     * <p><b>Two types, deliberately.</b> {@code ScreeningEvent} is the accurate one and
     * {@code Event} is the one Google's documentation names; declaring both costs nothing and
     * removes the question of whether the subtype is recognised.
     */
    private fun screeningEvent(film: Film, showing: FilmShowing): Map<String, Any?> {
        val builder = JsonLd.document(java.util.List.of("Event", "ScreeningEvent"))
            // The film's name, not the hall's: this node is the screening, and a search
            // result reading "Studio 1" would tell a customer nothing.
            .put("name", film.title)
            .put("startDate", isoStart(showing))
            .put("endDate", isoEnd(showing))
            .put("eventStatus", "https://schema.org/EventScheduled")
            .put("eventAttendanceMode", "https://schema.org/OfflineEventAttendanceMode")
            .put("workPresented", movieNode(film))
            .put("location", location(showing))
        // The URL of the page that actually sells the ticket — the seat map. Deliberately not
        // the film page this markup is embedded in: an events result whose link lands the
        // customer back on a list of showtimes has wasted the click.
        publicUrls.absolute("/halls/" + showing.resourcePoolId
                + "/showings/" + showing.resourcePoolSessionId)
            .ifPresent { url ->
                builder.put("url", url)
                builder.put("offers", JsonLd.document("Offer")
                    .put("url", url)
                    // No price. A calendar rule gives one showing four amounts
                    // depending on the day it falls on, and a single number here would
                    // be wrong twice a week. availability is likewise left out rather
                    // than asserted from a seat count this page has not read.
                    .put("priceCurrency", "IDR")
                    .buildNested())
            }
        return builder.buildNested()
    }

    /**
     * The branch as an event location.
     *
     * <p>Falls back to the hall's own name when the hall has no branch, which is every hall
     * created before the site table. A screening with no location at all would be rejected outright, and
     * "Studio 1, no address" is a worse node than one carrying whatever the branch knows —
     * but it is still better than dropping the screening.
     */
    private fun location(showing: FilmShowing): Map<String, Any?> {
        if (showing.siteName == null) {
            return JsonLd.document("MovieTheater").put("name", showing.hallName).buildNested()
        }
        val builder = JsonLd.document("MovieTheater")
            .put("name", showing.siteName)
            .put("address", JsonLd.document("PostalAddress")
                .put("streetAddress", showing.siteAddress)
                .put("addressLocality", showing.siteCity)
                .put("addressRegion", showing.siteProvince)
                .put("addressCountry", "ID")
                .buildNested())
        publicUrls.absolute("/cinemas/" + showing.siteSlug).ifPresent { url -> builder.put("url", url) }
        return builder.buildNested()
    }

    private fun address(site: Site): Map<String, Any?> =
        JsonLd.document("PostalAddress")
            .put("streetAddress", site.address)
            .put("addressLocality", site.city)
            .put("addressRegion", site.province)
            // Hard-coded rather than a column: there is no country on `site`, every
            // deployment of this is Indonesian, and an addressCountry guessed from a
            // timezone would be wrong the first time someone sold a ticket in Dili.
            .put("addressCountry", "ID")
            .buildNested()

    private fun geo(site: Site): Map<String, Any?>? {
        if (site.latitude == null || site.longitude == null) {
            return null
        }
        return JsonLd.document("GeoCoordinates")
            .put("latitude", site.latitude)
            .put("longitude", site.longitude)
            .buildNested()
    }

    /**
     * The showing's start as ISO-8601 <em>with the branch's offset</em>.
     *
     * <p>Not UTC and not the JVM's zone. "2026-08-23T19:00+08:00" is a Makassar evening
     * screening; the same instant written as "11:00Z" is correct and useless, because the
     * events experience groups by local day. The zone comes from
     * {@link CalendarPricing#zoneFor(String, String, String)} — calendar pricing's single answer
     * to this question, branch first and the hall only when there is no branch — rather than
     * being re-derived here, so the two can never name different days.
     */
    private fun isoStart(showing: FilmShowing): String = iso(showing.startsAt, zoneOf(showing))

    private fun isoEnd(showing: FilmShowing): String? =
        if (showing.endsAt == null) null else iso(showing.endsAt, zoneOf(showing))

    private fun zoneOf(showing: FilmShowing): ZoneId =
        calendarPricing.zoneFor(showing.siteTimezone, showing.hallTimezone,
            "showing " + showing.resourcePoolSessionId)

    private companion object {
        /**
         * ISO-8601 with the offset, which is the format schema.org requires for a date-time and
         * the reason a display formatter could not be reused: {@code TemplateExtensions.fmtDateTime}
         * renders "Sat 23 Aug 2026, 19:00 WIB", and "WIB" means nothing to a parser.
         */
        val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** Truncated to the second, because {@code starts_at} is a timestamptz with microsecond
         * precision and "19:00:00.312186+08:00" is a showtime with six digits of noise on it. */
        fun iso(instant: Instant?, zone: ZoneId): String =
            ISO_OFFSET.format(instant!!.truncatedTo(ChronoUnit.SECONDS).atZone(zone))

        /** Minutes as an ISO-8601 duration ("PT118M"), which is what schema.org's Duration is. */
        fun isoDuration(minutes: Int?): String? =
            if (minutes == null || minutes <= 0) null else Duration.ofMinutes(minutes.toLong()).toString()
    }
}
