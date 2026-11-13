package com.protek.booking.service

import com.protek.booking.i18n.LanguagePreference
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The age advisory for whoever is looking at a seat map, on whichever page is showing them one.
 *
 * **A bean rather than a private method on one resource, because there are two pages.** The
 * seat map and the booking page render the same `seatmapFragment.html` from the same
 * `SeatMapPage` — deliberately, so that the seat map's revert-is-markup rule holds across both — and
 * an advisory computed in only one of them would warn a customer who arrived one way and stay
 * silent for the customer who arrived the other. That is the bug this class exists to make
 * unavailable, and it is the bug that was actually hit: adding the banner to the fragment broke
 * the booking page outright, because the second caller supplied no such key.
 */
@ApplicationScoped
class AgeAdvisoryService {

    @Inject
    lateinit var profileService: CustomerProfileService
    /** The advisory and the attestation sit on consecutive lines of the page, so they resolve
     * from one language rather than two. */
    @Inject
    lateinit var language: LanguagePreference
    @Inject
    lateinit var calendarPricing: CalendarPricing

    /**
     * The advisory for this viewer and this showing, or null when there is nothing to warn about
     * — which is almost always.
     *
     * **Ordered so the common case costs nothing.** An unrestricted rating returns before
     * anything is read, so the SU and no-film showings that make up most of the catalogue pay for
     * this feature with one null check on a page re-rendered every three seconds by every open
     * tab. Only a restricted film reaches the profile lookup, and only a signed-in
     * customer who has actually given us a date of birth reaches the arithmetic.
     *
     * **The hall's own zone rather than its branch's, which is the one place this is
     * deliberately less precise than pricing.** `CalendarPricing.zoneFor(Connection, …)`
     * prefers the site's zone and pays a query to read it; the two disagree only when a hall's
     * timezone column is stale for its branch, and the consequence here is a showing whose local
     * date differs by a day — which changes the advisory only for a customer whose birthday is
     * that exact day. A warning is not worth a query on this endpoint to settle that; a price
     * was, which is why pricing does it the other way.
     */
    fun forSeatMap(page: CatalogService.SeatMapPage?): AgeClassification.Advisory? {
        if (page == null) {
            return null
        }
        val gate = page.ageGate(language.current())
        if (gate == null || !gate.restricted()) {
            return null
        }
        val dateOfBirth = profileService.knownDateOfBirth().orElse(null)
        if (dateOfBirth == null) {
            return null
        }
        val zone = calendarPricing.zoneFor(null, page.hall.timezone,
            "hall " + page.hall.resourcePoolId)
        return AgeClassification.advisoryFor(gate, dateOfBirth,
            page.showing.startsAt!!.atZone(zone).toLocalDate(), language.current())
    }
}
