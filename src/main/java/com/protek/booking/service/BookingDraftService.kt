package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.BookingDraft
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.repository.BookingDraftRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.SiteRepository
import com.protek.booking.security.CurrentActor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID

/**
 * The seat-selection page reached from a showtime — `/booking?booking_ref=…`.
 *
 * **What this adds over the seat map that already existed.**
 * `/halls/{pool}/showings/{session}` names one showing and nothing else, so the only way
 * to see a different time is to go back two pages and pick again. The incumbent's page instead
 * carries the date and the time as *fields*, and a customer changes their mind on the
 * page they are already on. That needs a handle that outlives the showing, which is the draft.
 *
 * **Ownership is checked here, not by RLS.** `booking_ref` is a uuid in a query
 * string and looks like a capability; it is not one. RLS isolates tenants and not customers
 * within a tenant — the same gap `CurrentActor` exists for and `BookingService`
 * closes with `assertMayActOn` — so this class runs the identical check. Without it,
 * anyone holding the link would see which film a stranger was buying seats for.
 */
@ApplicationScoped
class BookingDraftService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var currentActor: CurrentActor
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var draftRepo: BookingDraftRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var poolRepo: ResourcePoolRepository
    @Inject
    lateinit var siteRepo: SiteRepository

    /**
     * How long a draft stays openable.
     *
     * Deliberately much longer than `booking.hold-duration-minutes`, and they are not
     * comparable quantities: a hold is a claim on a seat somebody else wants, so ten minutes is
     * a fairness limit; a draft claims nothing, so its only job is to stop the table growing
     * without bound. Long enough that a customer who put their phone down mid-choice comes back
     * to the page they left.
     */
    @ConfigProperty(name = "booking.draft-lifetime", defaultValue = "PT12H")
    lateinit var draftLifetime: Duration

    /**
     * Opens (or re-opens) this customer's booking for a showing.
     *
     * Returns empty for a caller with no `app_user` identity, which is the anonymous
     * visitor who has tapped a showtime before signing in. It is **not** an exception and the endpoint above it is
     * **not** `@RolesAllowed`: htmx does not swap a non-2xx response, so
     * a 401 here would make tapping a showtime do nothing at all — precisely the dead-button
     * failure that section records paying for. The caller answers 200 with the sign-in sheet
     * instead. The real boundary is unchanged and is where it always was: the hold endpoint,
     * and every read on this class.
     */
    fun openDraft(sessionId: UUID?): Optional<UUID> {
        val appUser = currentActor.appUserId()
        if (appUser.isEmpty) {
            return Optional.empty()
        }
        val tenantId = currentTenant.id()
        val expiresAt = Instant.now().plus(draftLifetime)
        return Optional.of(db.inTenantTransaction(tenantId) { c ->
            // Refuse a showing that does not exist or is not this tenant's before minting a ref
            // for it. The FK would refuse the second case too, as a 500 rather than a 404.
            val showing = sessionRepo.findById(c, tenantId, sessionId)
                    .orElseThrow { NotFoundException("Showing not found: " + sessionId) }
            draftRepo.upsert(c, tenantId, appUser.get(), showing.resourcePoolSessionId,
                    expiresAt).bookingRef!!
        })
    }

    /**
     * Everything the booking page renders, in one transaction.
     *
     * The draft, the showing, the studio, the branch, the film, the seat map, the price and
     * the whole date/time schedule come off one connection with one RLS `set_config` and
     * one commit. Read across several transactions they would be several snapshots, and this
     * page is the one where that shows: the time field would list a showing the seat map below
     * it was not rendering.
     */
    fun load(bookingRef: UUID?): BookingPage {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> readPage(c, tenantId, bookingRef) }
    }

    /**
     * Moves an open booking onto another showing — what the date and time fields submit.
     *
     * Re-reads and re-renders inside the same transaction rather than redirecting to
     * [load], so the page the customer gets back is the schedule the move was decided
     * against.
     *
     * The page is of the draft that now names that showing, which is this one unless the
     * account already had another open there (`BookingDraftRepository.moveToShowing`). Callers
     * follow its `bookingRef` rather than the one they sent.
     */
    fun moveToShowing(bookingRef: UUID?, sessionId: UUID?): BookingPage {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val draft = requireOwnDraft(c, tenantId, bookingRef)
            val target = sessionRepo.findById(c, tenantId, sessionId)
                    .orElseThrow { NotFoundException("Showing not found: " + sessionId) }
            val ref = if (target.resourcePoolSessionId!! == draft.resourcePoolSessionId) bookingRef
                    else draftRepo.moveToShowing(c, tenantId, bookingRef, draft.appUserId,
                            target.resourcePoolSessionId, Instant.now().plus(draftLifetime))
            readPage(c, tenantId, ref)
        }
    }

    /**
     * Moves an open booking onto a different day — what the date field submits.
     *
     * Lands on the **earliest** showtime that day. A date on its own does not identify a
     * showing, so something has to choose, and the earliest is the only choice that is stable:
     * "keep the same time of day" is undefined when the new date has no 19:00, and "keep the
     * same position in the list" moves under the customer as the schedule fills in.
     *
     * A date with no showings leaves the draft untouched and re-renders. That is reachable
     * without anybody misbehaving: the field was rendered from a schedule that a cancellation
     * (or the viability rule) may have emptied since.
     *
     * Like [moveToShowing], the page can be of another of the account's drafts.
     */
    fun moveToDate(bookingRef: UUID?, date: LocalDate?): BookingPage {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val page = readPage(c, tenantId, bookingRef)
            val earliest: Optional<ShowTime> = page.dates.stream()
                    .filter { day -> day.date == date }
                    .flatMap { day -> day.times.stream() }
                    .findFirst()
            if (earliest.isEmpty || earliest.get().sessionId == page.showing().resourcePoolSessionId) {
                return@inTenantTransaction page
            }
            readPage(c, tenantId, draftRepo.moveToShowing(c, tenantId, bookingRef, page.draft.appUserId,
                    earliest.get().sessionId, Instant.now().plus(draftLifetime)))
        }
    }

    private fun readPage(c: Connection, tenantId: UUID, bookingRef: UUID?): BookingPage {
        val draft = requireOwnDraft(c, tenantId, bookingRef)
        val showing = sessionRepo.findById(c, tenantId, draft.resourcePoolSessionId)
                .orElseThrow { NotFoundException("Showing not found: " + draft.resourcePoolSessionId) }
        val hall = poolRepo.findById(c, tenantId, showing.resourcePoolId)
                .orElseThrow { NotFoundException("Hall not found: " + showing.resourcePoolId) }
        val seatMap = catalogService.readSeatMapPage(c, tenantId, hall, showing)
        val site: Site? = if (hall.siteId == null) null
                else siteRepo.findById(c, tenantId, hall.siteId).orElse(null)

        // The date and time fields only have alternatives to offer when both the film and the
        // branch are known: "the same title, at this cinema, at another time" is the question
        // they answer. A label-only session (a first-class case) or a hall not yet
        // assigned to a branch has no such set, so the fields render as plain text instead of
        // as selects — see BookingPage.canChangeShowtime.
        val schedule: List<ResourcePoolSessionRepository.FilmShowing> =
                if (showing.filmId == null || site == null) java.util.List.of()
                else sessionRepo.findUpcomingByFilmAtSite(c, tenantId, showing.filmId, site.siteId)

        return BookingPage(draft, seatMap, site,
                groupByDate(schedule, zoneOf(hall, site), showing.resourcePoolSessionId))
    }

    /**
     * The ownership check RLS cannot make. Mirrors `BookingService.assertMayActOn`: a
     * customer may act on their own draft, and `tenant_staff` on any of their tenant's —
     * staff finishing a booking a customer started on the phone is the same third tenant-facing
     * use case that lets them confirm and cancel.
     */
    private fun requireOwnDraft(c: Connection, tenantId: UUID, bookingRef: UUID?): BookingDraft {
        val draft = draftRepo.findByRef(c, tenantId, bookingRef)
                .orElseThrow { NotFoundException("No such booking: " + bookingRef) }
        if (currentActor.isStaff()) {
            return draft
        }
        val appUser = currentActor.appUserId()
                .orElseThrow { ForbiddenException("This booking belongs to another account.") }
        if (appUser != draft.appUserId) {
            throw ForbiddenException("This booking belongs to another account.")
        }
        return draft
    }

    /** One day the film plays at this branch — the date field's options. */
    @JvmRecord
    data class ShowDate(val date: LocalDate, val label: String, val selected: Boolean, val times: List<ShowTime>) {

        /** The value the date field submits. ISO-8601 rather than [label], because the
         * label is for reading and would change meaning the moment the format did. */
        fun value(): String = date.toString()
    }

    /** One showtime on that day — the time field's options. */
    @JvmRecord
    data class ShowTime(val sessionId: UUID?, val resourcePoolId: UUID?, val label: String,
                        val hallName: String?, val selected: Boolean)

    /** Everything `/booking?booking_ref=…` renders. */
    @JvmRecord
    data class BookingPage(val draft: BookingDraft, val seatMap: CatalogService.SeatMapPage, val site: Site?,
                           val dates: List<ShowDate>) {

        fun hall(): ResourcePool = seatMap.hall

        fun showing(): ResourcePoolSession = seatMap.showing

        fun film(): Film? = seatMap.film

        /**
         * The film's title, or the showing's label for a session that is not a film.
         *
         * A label-only session is first-class — it is what the hospitality mapping
         * and every non-film event uses — so this page has to name something either way rather
         * than rendering a blank heading.
         */
        fun title(): String? {
            val film = film()
            return if (film == null) showing().label else film.title
        }

        /** The cinema's name, or the studio's when the hall has no branch yet (the site table keeps
         * `site_id` nullable, and an unassigned hall is still a bookable hall). */
        fun theatreName(): String? = if (site == null) hall().name else site.name

        /**
         * The studio number the page states — "Studio 1" is what a hall is called, and the
         * number is what a customer walks towards.
         *
         * Parsed off the name rather than stored: `resource_pool` has no number
         * column, the slug migration declined to give it a slug for the same reason ("nobody searches for
         * Studio 1"), and adding one would need every existing hall renumbered by hand. A name
         * with no digits in it — "IMAX", "Velvet" — falls back to the name itself, which
         * is the right answer for exactly those halls.
         */
        fun studioNumber(): String? {
            // Regex("\\D+") is java.util.regex's pattern, and "" carries no $ or \ for
            // replace to read as a group reference: the same call as Java's replaceAll.
            val digits = hall().name!!.replace(Regex("\\D+"), "")
            return if (digits.isEmpty()) hall().name else "Studio " + digits
        }

        /** Whether the date and time fields have anything to offer. False for a label-only
         * session or a hall with no branch — see `readPage`. */
        fun canChangeShowtime(): Boolean = !dates.isEmpty()

        /** The times on the currently-selected date, for the time field. Empty when the date
         * field is empty, in which case the page renders neither. */
        fun timesOnSelectedDate(): List<ShowTime> =
            dates.stream().filter(ShowDate::selected).findFirst()
                    .map(ShowDate::times).orElse(java.util.List.of())
    }

    private companion object {
        private val DAY_FMT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val TIME_FMT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /**
         * The branch's timezone, falling back to the hall's.
         *
         * That order, not the other way round: the site table moved the timezone up to `site` because
         * "an estate spanning Kalimantan and Java is not one timezone", and kept
         * `resource_pool.timezone` as a per-hall override with a default. A hall inherits
         * unless somebody deliberately set it, and a branch with no `site` row at all has
         * only the hall to go on.
         */
        private fun zoneOf(hall: ResourcePool, site: Site?): ZoneId {
            val zone = if (site != null && site.timezone != null) site.timezone else hall.timezone
            return if (zone == null) ZoneId.of("Asia/Jakarta") else ZoneId.of(zone)
        }

        /**
         * Turns a flat list of showtimes into the two fields the page renders.
         *
         * Grouped in Java rather than by a second `GROUP BY` query: the rows are already
         * here, ordered by `starts_at`, and a calendar day is a timezone question the
         * database would have to be told the answer to anyway. [LinkedHashMap] keeps the
         * earliest-first order the query established, so the date field reads forwards.
         */
        private fun groupByDate(schedule: List<ResourcePoolSessionRepository.FilmShowing>,
                                zone: ZoneId, selectedSessionId: UUID?): List<ShowDate> {
            val byDate = LinkedHashMap<LocalDate, MutableList<ShowTime>>()
            for (showing in schedule) {
                val day = showing.startsAt!!.atZone(zone).toLocalDate()
                byDate.computeIfAbsent(day) { ArrayList() }.add(ShowTime(
                        showing.resourcePoolSessionId,
                        showing.resourcePoolId,
                        TIME_FMT.format(showing.startsAt.atZone(zone)),
                        showing.hallName,
                        showing.resourcePoolSessionId!! == selectedSessionId))
            }
            val dates = ArrayList<ShowDate>(byDate.size)
            for ((key, value) in byDate) {
                val selected = value.stream().anyMatch(ShowTime::selected)
                dates.add(ShowDate(key, DAY_FMT.format(key), selected,
                        java.util.List.copyOf(value)))
            }
            return java.util.List.copyOf(dates)
        }
    }
}
