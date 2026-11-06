package com.protek.booking.service

import com.protek.booking.domain.RateRule
import com.protek.booking.domain.ResourcePool
import com.protek.booking.isJavaBlank
import com.protek.booking.repository.PublicHolidayRepository
import com.protek.booking.repository.RateRuleRepository
import com.protek.booking.repository.SiteRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.sql.Connection
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Turns a [RatePlan] plus a showing into the price of one seat.
 *
 * Everything hard about calendar pricing is in the two lines this class exists to get
 * right: *which local date* a showing falls on, and *whether that date is a holiday
 * or the day before one*. The bands themselves are a lookup.
 */
@ApplicationScoped
class CalendarPricing {

    @Inject
    lateinit var siteRepo: SiteRepository

    @Inject
    lateinit var holidayRepo: PublicHolidayRepository

    @Inject
    lateinit var rateRuleRepo: RateRuleRepository

    /**
     * The price of one seat under `rule` for a showing starting at `showingStartsAt`.
     *
     * @param showingStartsAt the showing's own start, **not** the time of the booking.
     *        A Monday matinee bought on Saturday is a Monday ticket, and pricing it from
     *        `now()` would charge the weekend band for it.
     */
    fun priceFor(c: Connection, tenantId: UUID, rule: RateRule,
                 hall: ResourcePool, showingStartsAt: Instant): SeatPrice {
        val plan = RatePlan.parse(rule.ruleDefinitionJson)
        if (!plan.isCalendar()) {
            // The flat path stays exactly as cheap as it was before calendar pricing: no site lookup, no
            // holiday lookup, no timezone resolution. Every flat-rate tenant is on this path
            // and none of them should pay two extra queries per hold for a feature they have
            // not configured.
            return SeatPrice(plan.amountFor(RatePlan.Band.MON_WED), null, null, plan.inclusions())
        }

        val zone = zoneFor(c, tenantId, hall)
        val localDate = showingStartsAt.atZone(zone).toLocalDate()
        val match = holidayRepo.findHolidayOrEve(c, tenantId, localDate)
        val band = RatePlan.bandFor(localDate, match.isPresent)
        return SeatPrice(plan.amountFor(band), band,
            match.map(PublicHolidayRepository.HolidayMatch::reason).orElse(null),
            plan.inclusions())
    }

    /**
     * What a ticket bought at `at` promised to include (A.7b obs. 4's drink voucher).
     *
     * Read from the rule that was in force *then*, not the one in force now: a
     * booking's amount is fixed on its `reservation` row, and an inclusions line
     * read from today's rule would leave a ticket printed with this month's voucher against
     * last month's price. Sound because `rate_rule` is append-only.
     *
     * Empty rather than an error when no rule is found. A hall whose rule was deleted
     * still has bookings, and a reservation page that 500s because a ticket cannot state its
     * drink voucher would be a poor trade.
     */
    fun inclusionsAt(c: Connection, tenantId: UUID, resourcePoolId: UUID, at: Instant): List<String> =
        rateRuleRepo.findActiveAt(c, tenantId, resourcePoolId, at)
            .map { rule -> RatePlan.parse(rule.ruleDefinitionJson).inclusions() }
            .orElse(listOf())

    /**
     * The timezone a showing's day-of-week is decided in.
     *
     * **The branch wins when there is one.** A hall is physically inside its branch, so
     * a hall in a different timezone from the branch containing it is not a configuration
     * anybody has — whereas a hall row carrying the `'Asia/Jakarta'` default while its
     * branch is correctly set to `'Asia/Makassar'` is the normal state of every hall
     * created before the site table and of every hall created since by an operator who left the field
     * alone. `resource_pool.timezone` is `NOT NULL DEFAULT 'Asia/Jakarta'` (V1),
     * so "the hall overrides" and "the hall was never touched" are the same bytes: reading
     * the hall first would mean the default silently outranking the only value an operator
     * actually chose, and mispricing every showing at a Makassar or Jayapura branch by one
     * band on the two evenings a week it matters.
     *
     * So `resource_pool.timezone` stays the answer for a hall with *no*
     * branch — which is what it has always been, and what keeps every pre-V9 tenant priced
     * exactly as before.
     *
     * An unparseable zone falls back rather than throwing, and logs. The admin form takes
     * the zone as free text, so a typo is reachable — [CatalogService.createSite]
     * validates on the way in, but rows predate that check and an operator can edit the
     * database. Refusing to price would take the hall off sale over a typo in a field that
     * only shifts a band boundary; pricing at the fallback and saying so in the log does not.
     */
    fun zoneFor(c: Connection, tenantId: UUID, hall: ResourcePool): ZoneId {
        if (hall.siteId != null) {
            val site = siteRepo.findById(c, tenantId, hall.siteId)
            if (site.isPresent) {
                return zoneFor(site.get().timezone, hall.timezone, "site " + site.get().siteId)
            }
        }
        return zoneFor(null, hall.timezone, "hall " + hall.resourcePoolId)
    }

    /**
     * The same precedence, for a caller that already has both zone strings in hand.
     *
     * Exists so that nothing re-derives the rule above. The SEO markup needs
     * a showing's UTC offset to write an ISO-8601 `startDate`, and it renders every
     * showing of one film across the whole tenant — for a chain that is one branch lookup per
     * showing through the [zoneFor] form taking a connection, against a
     * join that has already selected both columns. Copying the two-line precedence into that
     * query's row mapper was the alternative, and a second copy of this decision is exactly
     * what the entry recording it asks not to happen.
     *
     * @param siteTimezone the branch's zone, or null when the hall has no branch.
     * @param hallTimezone `resource_pool.timezone`, which is NOT NULL so it is only
     *        ever the fallback — see the precedence argument above.
     */
    fun zoneFor(siteTimezone: String?, hallTimezone: String?, source: String): ZoneId {
        if (siteTimezone != null && !siteTimezone.isJavaBlank()) {
            return parseZone(siteTimezone, source)
        }
        return parseZone(hallTimezone, source)
    }

    private fun parseZone(timezone: String?, source: String): ZoneId {
        if (timezone == null || timezone.isJavaBlank()) {
            return FALLBACK_ZONE
        }
        return try {
            ZoneId.of(timezone)
        } catch (e: DateTimeException) {
            LOG.warnf("Unknown timezone '%s' on %s — pricing this showing's day-of-week band in %s " +
                "instead. Fix the timezone: a band boundary is an hour wide.",
                timezone, source, FALLBACK_ZONE)
            FALLBACK_ZONE
        }
    }

    /**
     * One seat's price, and enough about how it was reached to explain it.
     *
     * @param amount what a seat costs — the only field the booking itself needs.
     * @param band which day band produced it, or `null` under a flat rule. Display only.
     * @param holidayReason the holiday that forced the top band ("Idul Fitri", "the day before
     *        Idul Fitri"), or `null`. This is what staff get asked at the counter.
     * @param inclusions what the ticket includes at this price (A.7b obs. 4's drink voucher).
     */
    @JvmRecord
    data class SeatPrice(
        val amount: BigDecimal?,
        val band: RatePlan.Band?,
        val holidayReason: String?,
        val inclusions: List<String>,
    ) {

        /** How the seat map labels the price: "Rp 35.000 per seat (Public holiday — Idul Fitri)". */
        fun bandLabel(): String? {
            if (band == null) {
                return null
            }
            return if (holidayReason == null) band.label else band.label + " — " + holidayReason
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(CalendarPricing::class.java)

        /**
         * Where a hall with no branch and no usable zone of its own lands. Indonesia's most
         * populous zone and the value V1 defaults both timezone columns to, so this is the
         * status quo rather than a new opinion.
         */
        private val FALLBACK_ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
    }
}
