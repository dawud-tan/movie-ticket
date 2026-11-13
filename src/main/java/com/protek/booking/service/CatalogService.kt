package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Film
import com.protek.booking.domain.PublicHoliday
import com.protek.booking.domain.RateRule
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Site
import com.protek.booking.i18n.Language
import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import com.protek.booking.repository.FilmRepository
import com.protek.booking.repository.PublicHolidayRepository
import com.protek.booking.repository.RateRuleRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.repository.SiteRepository
import com.protek.booking.seo.Slug
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.jboss.logging.Logger
import java.sql.Connection
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

/**
 * Everything that isn't the hold/confirm/cancel lifecycle: browsing showtimes and seat
 * maps (customer-facing), and hall/seat/showing configuration (tenant-facing).
 *
 * Kotlin since 2026-10-08. Parameters a Java caller hands over
 * from a form or a query are nullable where the Java took them unchecked: a null `siteId` or
 * `ref` reaches the same repository miss and the same 404 it always did, rather than a Kotlin
 * null check's 500.
 */
@ApplicationScoped
class CatalogService {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var poolRepo: ResourcePoolRepository

    @Inject
    lateinit var unitRepo: ResourceUnitRepository

    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository

    @Inject
    lateinit var reservationRepo: ReservationRepository

    @Inject
    lateinit var rateRuleRepo: RateRuleRepository

    @Inject
    lateinit var siteRepo: SiteRepository

    @Inject
    lateinit var filmRepo: FilmRepository

    @Inject
    lateinit var bookingService: BookingService

    @Inject
    lateinit var holidayRepo: PublicHolidayRepository

    @Inject
    lateinit var calendarPricing: CalendarPricing

    // --- Browsing (customer-facing) -----------------------------------------------------
    //
    // These reads resolve the tenant with publicCatalogueId(), not id(). Browsing showtimes and
    // seat maps are public pages that must render for anybody, and "whose catalogue does this deployment
    // publish" is answered by configuration rather than by who is looking. id() answers the
    // different question of which tenant an identity may act on, and throws for a platform_admin
    // — correct for the writes below, but it turned the public home page into a 500 for any
    // signed-in platform admin (nobody else), which is what TenantResolutionSecurityTest now
    // pins down.
    //
    // Behaviour is unchanged for every other caller: an identity that has a tenant resolves the
    // same either way, and every /admin resource is @RolesAllowed(TENANT_STAFF), so no
    // platform_admin can reach the configuration methods further down. Writes keep using id()
    // and keep failing closed.

    fun listHalls(): List<ResourcePool> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> poolRepo.findAllByTenant(c, tenantId) }
    }

    fun getHallOrThrow(resourcePoolId: UUID?): ResourcePool {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> poolRepo.findById(c, tenantId, resourcePoolId) }
            .orElseThrow { NotFoundException("Hall not found: $resourcePoolId") }
    }

    // --- Branches ---------------------------------------------------------
    //
    // The browse path a chain needs is city -> branch -> showings. These reads resolve the
    // tenant the same way the rest of the browse path does (publicCatalogueId), because they
    // are the same public pages.

    /** The cities this tenant operates in — the first level of the browse path. */
    fun listCities(): List<SiteRepository.CityCount> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findCities(c, tenantId) }
    }

    fun listSites(): List<Site> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findAllByTenant(c, tenantId) }
    }

    fun listSitesInCity(city: String?): List<Site> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findByCity(c, tenantId, city) }
    }

    /**
     * A branch by whichever form of its public URL was used — slug or uuid.
     *
     * Both resolve, rather than the uuid form being retired with a redirect: every link
     * that predates the slug column is a uuid, including the ones in this repository's tests and in
     * whatever a member of staff has bookmarked. The slug form is the `<link
     * rel="canonical">` the page emits and the only one the sitemap lists, so a crawler
     * converges on it without anything having to break.
     */
    fun getSiteByRefOrThrow(ref: String?): Site {
        if (ref != null && Slug.looksLikeUuid(ref)) {
            return getSiteOrThrow(UUID.fromString(ref))
        }
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findBySlug(c, tenantId, ref) }
            .orElseThrow { NotFoundException("No such branch: $ref") }
    }

    /** A film by whichever form of its public URL was used. See [getSiteByRefOrThrow]. */
    fun getFilmByRefOrThrow(ref: String?): Film {
        if (ref != null && Slug.looksLikeUuid(ref)) {
            return getFilmOrThrow(UUID.fromString(ref))
        }
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> filmRepo.findBySlug(c, tenantId, ref) }
            .orElseThrow { NotFoundException("No such film: $ref") }
    }

    fun getSiteOrThrow(siteId: UUID?): Site {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findById(c, tenantId, siteId) }
            .orElseThrow { NotFoundException("Branch not found: $siteId") }
    }

    fun listHallsAtSite(siteId: UUID?): List<ResourcePool> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> poolRepo.findBySite(c, tenantId, siteId) }
    }

    /** Halls not yet assigned to a branch. Shown in admin so an unassigned hall is visible
     * as needing attention rather than silently missing from the branch browse. */
    fun listHallsWithoutSite(): List<ResourcePool> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> poolRepo.findWithoutSite(c, tenantId) }
    }

    /**
     * Whether this tenant has configured any branch at all.
     *
     * Drives the home page's shape. A single-site tenant that never creates a branch keeps
     * the original flat hall list; introducing a mandatory city step for a one-cinema operator
     * would be a worse page, and every single-cinema tenant and most tests are exactly that case.
     */
    fun hasSites(): Boolean = listCities().isNotEmpty()

    /**
     * Creates a branch.
     *
     * The timezone is validated here rather than taken as free text, which it was until
     * calendar pricing gave it something to do. It is now a *pricing* input — the branch's zone is
     * what decides whether a 23:30 showing is Sunday or Monday, and therefore which band it
     * sells at — so a typo'd zone is a mispriced house, silently, on the two evenings a week
     * the band changes. Rejected on the way in, where the operator is looking at the form and
     * can fix it, rather than logged months later by [CalendarPricing.zoneFor].
     */
    fun createSite(name: String?, city: String?, province: String?, address: String?, timezone: String?): Site {
        val tenantId = currentTenant.id()
        val zone = if (timezone == null || timezone.isJavaBlank()) "Asia/Jakarta" else timezone.javaTrim()
        try {
            ZoneId.of(zone)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("'" + zone + "' is not a timezone. Use an IANA name " +
                "such as Asia/Jakarta (WIB), Asia/Makassar (WITA) or Asia/Jayapura (WIT).", e)
        }
        return db.inTenantTransaction(tenantId) { c ->
            val slug = Slug.free(name, "cinema") { base -> siteRepo.findSlugsUnder(c, tenantId, base) }
            siteRepo.insert(c, tenantId, name, slug, city, province, address, zone)
        }
    }

    fun assignHallToSite(resourcePoolId: UUID?, siteId: UUID?) {
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c -> poolRepo.assignToSite(c, tenantId, resourcePoolId, siteId) }
    }

    fun listUpcomingShowings(resourcePoolId: UUID?): List<ResourcePoolSession> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> sessionRepo.findUpcomingByPool(c, tenantId, resourcePoolId) }
    }

    fun getShowingOrThrow(sessionId: UUID?): ResourcePoolSession {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> sessionRepo.findById(c, tenantId, sessionId) }
            .orElseThrow { NotFoundException("Showing not found: $sessionId") }
    }

    fun listSeats(resourcePoolId: UUID?): List<ResourceUnit> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> unitRepo.findByPool(c, tenantId, resourcePoolId) }
    }

    /** Live seat map. */
    fun getSeatMap(resourcePoolId: UUID?, sessionId: UUID?): List<SeatMapEntry> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c ->
            reservationRepo.findSeatMap(c, tenantId, resourcePoolId, sessionId)
        }
    }

    // --- One seat-map render, one transaction ---------------------------------------------
    //
    // The seat map is the only page this application polls, and it was reading itself four
    // separate times: the hall, the showing, the map and the price, each through one of the
    // methods above, each opening its own transaction. Measured at 3.5-4 transactions per poll
    // (pg_stat_database.xact_commit before/after) and confirmed as exactly four by
    // SeatMapRenderTest's pool counter, and every one of those pays a set_config for RLS and a
    // COMMIT on top of the query it came for.
    //
    // Two reasons to collapse them, and the second is the one that would survive even if the
    // first stopped mattering:
    //
    //  1. Cost. Four transactions is four connection acquisitions and ~12 round trips, where
    //     one transaction is ~6 under a flat rule and two more under a calendar one. Against
    //     a local socket that is small — measured, and ranked low for exactly that reason,
    //     and nothing here is claimed as a speed fix. It is
    //     not nothing either: it is the per-poll floor, paid by every open tab.
    //
    //  2. Consistency. Four transactions are four snapshots. A hold committing between the
    //     third and the fourth read gave a page whose seat map and whose price came from
    //     different states of the database, and a repricing landing between the first and the
    //     fourth gave a hall and a price that never coexisted. Nothing here is dangerous today
    //     -- the EXCLUDE constraint, not this render, is what decides whether a seat is free
    //     -- but a render that reads one consistent picture is the version you do not have
    //     to reason about again.

    /**
     * Everything one seat-map render needs, read inside a single transaction.
     *
     * @param price null when the hall has no rate rule, or when its rule cannot be parsed — the
     *        seat map renders without a price rather than 500ing, exactly as
     *        [getPriceDisplay] has always behaved.
     * @param film null for a `label`-only showing, which stays a first-class case.
     *        Read for its LSF rating: the age attestation asks the customer to attest against it, and a
     *        page that asked without naming the rating would be asking them to agree to nothing.
     */
    @JvmRecord
    data class SeatMapPage(
        val hall: ResourcePool,
        val showing: ResourcePoolSession,
        val seats: List<SeatMapEntry>,
        val price: CalendarPricing.SeatPrice?,
        val film: Film?,
    ) {

        /**
         * What the seat map shows, and ticks, for this showing's rating — null when there is no
         * film or it carries no rating. Derived here rather than stored so the page and the hold
         * cannot end up looking at two different gates.
         *
         * The language is a parameter rather than something this record resolves, and that is
         * the same invariant one level up: the sentence and the tag recorded beside its hash have
         * to come out of one call. Every caller is a bean that already knows the request's
         * language. A template cannot call this — it is handed the `Gate` instead.
         */
        fun ageGate(language: Language): AgeClassification.Gate? =
            if (film == null) null else AgeClassification.gateFor(film.classification, language)
    }

    // --- Cinema-first and film-first browse, one level deeper --------------
    //
    // The film page gave "which branches show this film"; browsing needs the other three corners
    // of the same grid, because a cinema's usual flow reaches a showtime from either direction:
    //   /cinemas/{branch}                    -> which films play here
    //   /cinemas/{branch} + film             -> when
    //   /movies/{film} + branch              -> when   (same query, asked the other way round)
    // All three resolve the tenant with publicCatalogueId() for the reason the block above
    // gives: these are public pages and must render for anybody.

    /** The films with an upcoming showing at one branch — the cinema page's list. */
    fun listFilmsAtSite(siteId: UUID?): List<Film> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> filmRepo.findNowPlayingAtSite(c, tenantId, siteId) }
    }

    /** The branches showing one film — the film page's list. */
    fun listCinemasShowingFilm(filmId: UUID?): List<Site> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> siteRepo.findShowingFilm(c, tenantId, filmId) }
    }

    /**
     * The showtimes of one film at one branch — the strip that opens under a title on the
     * cinema page, and under a cinema name on the film page.
     *
     * One endpoint and one query for both, because they are the same question: the pages
     * differ only in which of the two the visitor picked first. Two near-identical fragments
     * would be two places for the "on sale" rule to drift apart.
     *
     * `startsFrom`/`startsBefore` narrow it in SQL; see [ResourcePoolSessionRepository.findUpcomingByFilmAtSite].
     */
    fun listShowingsOfFilmAtSite(filmId: UUID?, siteId: UUID?, startsFrom: Instant? = null,
                                 startsBefore: Instant? = null): List<ResourcePoolSessionRepository.FilmShowing> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c ->
            sessionRepo.findUpcomingByFilmAtSite(c, tenantId, filmId, siteId, startsFrom, startsBefore)
        }
    }

    /** The customer seat map, whose URL names both the hall and the showing. */
    fun getSeatMapPage(resourcePoolId: UUID?, sessionId: UUID?): SeatMapPage {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c ->
            val hall = poolRepo.findById(c, tenantId, resourcePoolId)
                .orElseThrow { NotFoundException("Hall not found: $resourcePoolId") }
            val showing = sessionRepo.findById(c, tenantId, sessionId)
                .orElseThrow { NotFoundException("Showing not found: $sessionId") }
            readSeatMapPage(c, tenantId, hall, showing)
        }
    }

    /** The same page for a caller holding only the showing — the counter's seat picker, whose
     * URL has no hall in it because staff reach it from the showing. */
    fun getSeatMapPageForShowing(sessionId: UUID?): SeatMapPage {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c ->
            val showing = sessionRepo.findById(c, tenantId, sessionId)
                .orElseThrow { NotFoundException("Showing not found: $sessionId") }
            val hall = poolRepo.findById(c, tenantId, showing.resourcePoolId)
                .orElseThrow { NotFoundException("Hall not found: " + showing.resourcePoolId) }
            readSeatMapPage(c, tenantId, hall, showing)
        }
    }

    /* Public only because Kotlin has no package-private. It was package-private in Java so that
     * BookingDraftService could compose it into the booking page's single transaction
     * while nothing outside this package could open a transaction of its own around it, which
     * is the arrangement the one-transaction render exists to undo. The Connection parameter still says it: there is
     * no way to call this without already being inside somebody's transaction. And @JvmName,
     * which gives an internal member elsewhere a name Java can call, cannot be put on a
     * member that all-open has made open. */
    fun readSeatMapPage(c: Connection, tenantId: UUID, hall: ResourcePool,
                        showing: ResourcePoolSession): SeatMapPage {
        val seats = reservationRepo.findSeatMap(
            c, tenantId, hall.resourcePoolId, showing.resourcePoolSessionId)
        // One more query, and only for a showing that has a film (the age attestation's rating). Inside
        // the one transaction the seat-map render established, so it costs a round trip rather than a
        // set_config and a commit — which is the whole reason that lever was worth taking before
        // anything else wanted to read one more row here.
        val film = if (showing.filmId == null) null
        else filmRepo.findById(c, tenantId, showing.filmId).orElse(null)
        // Priced for this showing, not for the hall: under a calendar rule the same studio
        // is one band on Tuesday and another on Sunday. The hall is passed in rather than looked
        // up again — getPriceDisplay's own transaction had to re-read it, and that second read of
        // a row already in hand was the fifth query this render did not need.
        return SeatMapPage(hall, showing, seats,
            priceOrNull(c, tenantId, hall, showing.startsAt!!), film)
    }

    // --- Tenant-facing configuration -----------------------------------------------------

    /** "Configure a hall's seat map" — create the hall itself. */
    fun createHall(name: String?, address: String?, timezone: String?, blockClaimMode: String?): ResourcePool {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            poolRepo.insert(c, tenantId, name, address, timezone, blockClaimMode)
        }
    }

    /** Enter one seat row (`resource_unit` — every hall's layout differs). */
    fun addSeat(resourcePoolId: UUID?, label: String?, groupKey: String?, sequenceNo: Int?): ResourceUnit {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            unitRepo.insert(c, tenantId, resourcePoolId, label, groupKey, sequenceNo)
        }
    }

    // --- Bulk seat-map entry ---------------------------------------------
    //
    // The single highest-leverage item for a chain, and the one that is a delivery
    // blocker rather than a parity gap: addSeat() above is one seat per HTTP POST, and a
    // 38-branch chain is on the order of 15,000-25,000 seats. None of the browse or pricing
    // work matters if a tenant's inventory cannot physically be loaded.
    //
    // No schema change was needed for any of this — resource_unit already carries group_key
    // and sequence_no, which is exactly what a generator writes.

    /** What [generateSeats] would create, without creating it. */
    fun previewSeats(resourcePoolId: UUID?, spec: String?): SeatMapPreview =
        preview(resourcePoolId, SeatMapPlan.parse(spec))

    /** As [previewSeats], for the regular-grid quick form. */
    fun previewUniformSeats(resourcePoolId: UUID?, rowsExpr: String?, seatsPerRow: Int): SeatMapPreview =
        previewSeats(resourcePoolId, SeatMapPlan.uniform(rowsExpr, seatsPerRow))

    /** What [cloneSeatsFrom] would copy, without copying it. */
    fun previewClone(targetPoolId: UUID?, sourcePoolId: UUID?): SeatMapPreview =
        preview(targetPoolId, seatsOf(sourcePoolId))

    /**
     * Creates a whole seat map in one transaction.
     *
     * Additive, never destructive: it refuses rather than overwrites when a label already
     * exists in the hall. Replacing seats is not a safe default because `reservation`
     * rows reference `resource_unit`, so a "regenerate" that deleted seats would either
     * fail on the FK or, worse, orphan somebody's booked seat. Refusing means the operator can
     * also use this to add a row to an existing hall later, which is the same operation.
     */
    fun generateSeats(resourcePoolId: UUID?, spec: String?): Int =
        insertPlan(resourcePoolId, SeatMapPlan.parse(spec))

    /** As [generateSeats], for the regular-grid quick form. */
    fun generateUniformSeats(resourcePoolId: UUID?, rowsExpr: String?, seatsPerRow: Int): Int =
        generateSeats(resourcePoolId, SeatMapPlan.uniform(rowsExpr, seatsPerRow))

    /**
     * Copies another hall's layout into this one — multiplex studios repeat, and a chain's
     * studios repeat far more.
     */
    fun cloneSeatsFrom(targetPoolId: UUID?, sourcePoolId: UUID?): Int {
        if (targetPoolId == sourcePoolId) {
            throw IllegalArgumentException("Cannot clone a hall's seat map onto itself.")
        }
        return insertPlan(targetPoolId, seatsOf(sourcePoolId))
    }

    /**
     * What [importSeatsFromCsv] would create, without creating it.
     *
     * The hall's own name goes to the parser because a chain hands over one file for a whole
     * branch, and a studio column in that file is a filter rather than an extra column — see
     * [SeatMapCsv] for why importing all of it into whichever hall happened to be open is
     * the failure worth designing against.
     */
    fun previewCsvSeats(resourcePoolId: UUID?, csv: String?): SeatMapPreview {
        val parsed = readCsv(resourcePoolId, csv)
        return preview(resourcePoolId, parsed.seats).withNote(parsed.note())
    }

    /**
     * Creates a hall's seats from a spreadsheet export (deferred until a tenant turned up
     * with a file).
     *
     * It shares every rule with the generator by construction: the same collector decides
     * what a valid seat is, the same [insertPlan] refuses on a label that already exists
     * and writes in one transaction. Import is a way of *describing* a hall, not a second way of
     * creating one — so a relayout is as unavailable here as it is there, and for the same
     * reason: `reservation` references `resource_unit`, so replacing a seat map means
     * deciding what happens to the bookings that point into it.
     */
    fun importSeatsFromCsv(resourcePoolId: UUID?, csv: String?): Int =
        insertPlan(resourcePoolId, readCsv(resourcePoolId, csv).seats)

    /** The parsed file itself, for a caller that needs the seats and what the file left behind
     * separately — the relayout preview, which has its own summary to render. */
    fun readCsv(resourcePoolId: UUID?, csv: String?): SeatMapCsv.Import =
        SeatMapCsv.parse(csv, getHallOrThrow(resourcePoolId).name)

    /** Another hall's layout as a plan — the clone path's description, reusable by relayout. */
    fun seatsOfHall(sourcePoolId: UUID?): List<SeatMapPlan.Seat> = seatsOf(sourcePoolId)

    // --- Relayout ----------------------------------------------------------
    //
    // Generation and import add seats and refuse a label that already exists. That is right for
    // onboarding and wrong for a studio that has been re-seated, which is a different operation:
    // some seats keep their identity, some move, some stop being sellable. The three parse paths
    // above (spec, CSV, clone) all produce the same List<Seat>, so relayout takes any of them --
    // it is a different verb on the same description, not a fourth way of describing a hall.

    /**
     * What applying this plan to a hall that already has seats would change, and whether it can
     * be done at all.
     *
     * @param blocked labels the plan would retire but a live booking still points at. Non-empty
     *        means the relayout is refused — this is the answer to what was
     *        the only undecided part of relayout, and the answer is that we do not take a seat
     *        away from someone holding it. Staff cancel or move those bookings first, which is
     *        a decision with a customer on the other end of it rather than a database operation.
     */
    @JvmRecord
    data class RelayoutPreview(val diff: SeatMapDiff, val blocked: List<String>) {

        fun isBlocked(): Boolean = blocked.isNotEmpty()
    }

    /** What [applyRelayout] would do, without doing it. */
    fun previewRelayout(resourcePoolId: UUID?, planned: List<SeatMapPlan.Seat>): RelayoutPreview {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val diff = SeatMapDiff.of(unitRepo.findAllByPool(c, tenantId, resourcePoolId), planned)
            val claimed = reservationRepo.findClaimedUnitIds(c, tenantId, resourcePoolId)
            RelayoutPreview(diff, blockedBy(claimed, diff))
        }
    }

    /**
     * Re-seats a hall: moves the seats that stay, creates the new ones, brings back any the
     * layout wants again, and retires the rest. One transaction.
     *
     * **Retiring is `is_active = false` and never a delete.** `reservation`
     * references `resource_unit`, so a delete either fails on the foreign key or takes
     * away the row a printed ticket names. A retired seat leaves the seat map — every read
     * that decides what is sellable already filters `is_active`, which is why this needed
     * no migration and no new column — while every historical booking still resolves it.
     *
     * **A seat that keeps its label keeps its `resource_unit_id`**, so a relayout
     * that renumbers a row does not disturb a single booking in it. That is the whole reason
     * the diff matches by label rather than rebuilding the hall.
     *
     * **The claim check is inside the lock, and the lock is what makes it true.**
     * `findAllByPoolForUpdate` locks the hall's seats before the diff is computed, and
     * `BookingService` takes `FOR SHARE` on the seats it is about to book — so a
     * hold cannot appear between reading "nothing claims A5" and retiring A5. The preview runs
     * unlocked and can therefore be stale, which is exactly why the check is repeated here and
     * why this method, not the preview, is what decides.
     */
    fun applyRelayout(resourcePoolId: UUID?, planned: List<SeatMapPlan.Seat>): RelayoutPreview {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val diff = SeatMapDiff.of(
                unitRepo.findAllByPoolForUpdate(c, tenantId, resourcePoolId), planned)
            val blocked = blockedBy(reservationRepo.findClaimedUnitIds(c, tenantId, resourcePoolId), diff)
            if (blocked.isNotEmpty()) {
                throw IllegalArgumentException("These seats are booked and cannot be retired: " +
                    blocked.take(10).joinToString(", ") +
                    (if (blocked.size > 10) " (and " + (blocked.size - 10) + " more)" else "") +
                    ". Cancel or move those bookings first — the hall is unchanged.")
            }

            val updates = ArrayList<Array<Any?>>()
            for (moved in diff.moving) {
                updates.add(update(tenantId, moved, true))
            }
            for (back in diff.reinstating) {
                updates.add(update(tenantId, back, true))
            }
            for (marked in diff.remarking) {
                updates.add(update(tenantId, marked, true))
            }
            for (retired in diff.retiring) {
                // Keeps its row and its position; it just stops being part of the room.
                updates.add(arrayOf(retired.groupKey, retired.sequenceNo, false, null, null,
                    tenantId, retired.resourceUnitId))
            }
            unitRepo.updateAll(c, updates)
            unitRepo.insertAll(c, tenantId, resourcePoolId, diff.creating)
            RelayoutPreview(diff, listOf())
        }
    }

    /** Reads a hall's existing seats back as a plan, so clone and generate share one insert path. */
    private fun seatsOf(sourcePoolId: UUID?): List<SeatMapPlan.Seat> {
        val tenantId = currentTenant.id()
        val source = db.inTenantTransaction(tenantId) { c -> unitRepo.findByPool(c, tenantId, sourcePoolId) }
        if (source.isEmpty()) {
            throw IllegalArgumentException("That hall has no seats to copy.")
        }
        return source.map { unit -> SeatMapPlan.Seat(unit.label!!, unit.groupKey, unit.sequenceNo!!, unit.accessible) }
    }

    private fun preview(resourcePoolId: UUID?, seats: List<SeatMapPlan.Seat>): SeatMapPreview {
        val tenantId = currentTenant.id()
        val existing = db.inTenantTransaction(tenantId) { c -> unitRepo.findLabelsByPool(c, tenantId, resourcePoolId) }
        return summarise(seats, existing)
    }

    private fun insertPlan(resourcePoolId: UUID?, seats: List<SeatMapPlan.Seat>): Int {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            // Re-checked inside the writing transaction rather than trusting the preview's
            // read: the preview ran in its own transaction and an operator can sit on the
            // confirm screen indefinitely. Same reasoning as everywhere else in this codebase —
            // a check in one transaction says nothing about the state of the next.
            val existing = unitRepo.findLabelsByPool(c, tenantId, resourcePoolId)
            val check = summarise(seats, existing)
            if (check.hasConflicts()) {
                val conflicts = check.conflicts
                throw IllegalArgumentException("These seats already exist in this hall: " +
                    conflicts.take(10).joinToString(", ") +
                    (if (conflicts.size > 10) " (and " + (conflicts.size - 10) + " more)" else "") +
                    ". Nothing was created.")
            }
            unitRepo.insertAll(c, tenantId, resourcePoolId, seats)
        }
    }

    // --- Pricing ----------------------------------------------------
    //
    // Pricing lives in rate_rule.rule_definition jsonb rather than in rigid columns, which is
    // what let calendar pricing add day-of-week bands without a migration. RatePlan is the only thing that
    // reads or writes that shape; nothing here parses JSON itself.

    /**
     * Sets a hall's rate — flat or calendar-banded — by inserting a superseding rule.
     *
     * Insert rather than update, as it always was: `rate_rule` is append-only,
     * which is what makes "what was this hall charging in February" answerable and what
     * [RateRuleRepository.findActiveAt] rests on.
     *
     * @param applyToBranch write the same rule to every
     *        hall in this hall's branch. Cinemas price per branch, not per studio, and setting
     *        four bands separately on each of a multiplex's studios is where a chain's prices
     *        drift apart. The alternative considered and rejected was re-keying `rate_rule`
     *        to `site`: that is a migration, and it introduces a precedence
     *        question — which rule wins when a hall-level and a branch-level rule both match —
     *        that this has no way of asking. A bulk write leaves one rule per hall and one
     *        answer. A hall with no branch ignores this flag; there is nothing to fan out to.
     */
    fun setRate(resourcePoolId: UUID?, plan: RatePlan, applyToBranch: Boolean): Int {
        val tenantId = currentTenant.id()
        val json = plan.json()
        val effectiveFrom = Instant.now()
        return db.inTenantTransaction(tenantId) { c ->
            val targets = ArrayList<UUID?>()
            targets.add(resourcePoolId)
            if (applyToBranch) {
                val hall = poolRepo.findById(c, tenantId, resourcePoolId)
                    .orElseThrow { NotFoundException("Hall not found: $resourcePoolId") }
                if (hall.siteId != null) {
                    for (sibling in poolRepo.findBySite(c, tenantId, hall.siteId)) {
                        if (sibling.resourcePoolId != resourcePoolId) {
                            targets.add(sibling.resourcePoolId)
                        }
                    }
                }
            }
            // One transaction for the whole branch: a multiplex half-repriced is a multiplex
            // selling the same seat at two prices depending on which studio the customer picked.
            for (target in targets) {
                rateRuleRepo.insert(c, tenantId, target, json, effectiveFrom)
            }
            targets.size
        }
    }

    /** A read, so it resolves like the browsing methods above rather than like the writes around
     * it: the public seat map reaches it through [getPriceDisplay] to show a price. */
    fun getActiveRate(resourcePoolId: UUID?): Optional<RateRule> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> rateRuleRepo.findActive(c, tenantId, resourcePoolId) }
    }

    /** The hall's configured rate as the admin screen shows it — one amount, or four bands. */
    fun getRatePlan(resourcePoolId: UUID?): Optional<RatePlan> =
        getActiveRate(resourcePoolId).map { rule -> RatePlan.parse(rule.ruleDefinitionJson) }

    /**
     * What a seat of *this showing* costs, for display on the seat map and the counter
     * screen.
     *
     * Takes the showing rather than just the hall, because with calendar pricing a hall no
     * longer has "a price": the same studio is Rp 25,000 on Tuesday and Rp 35,000 on Sunday.
     * A page that showed one number per hall would be wrong on four days in seven.
     *
     * Empty rather than an exception when the stored JSON cannot be parsed, which preserves
     * how the flat version behaved: this is a public browse page, and a seat map that renders
     * without a price is a far better failure than one that 500s. The booking path stays loud —
     * [BookingService] lets the same error through, because taking money at a guessed
     * price is the failure worth refusing.
     */
    fun getPriceDisplay(resourcePoolId: UUID?, showingStartsAt: Instant): Optional<CalendarPricing.SeatPrice> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c ->
            poolRepo.findById(c, tenantId, resourcePoolId).map { h -> priceOrNull(c, tenantId, h, showingStartsAt) }
        }
    }

    /**
     * The price line for one showing at one hall, on a connection whose transaction the caller
     * owns — so a render that has already loaded the hall pays neither a second transaction nor
     * a second read of it ([readSeatMapPage]).
     *
     * Null rather than `Optional` because it is a nullable field of [SeatMapPage]
     * and of the templates below it; [getPriceDisplay] is the wrapping form.
     */
    private fun priceOrNull(c: Connection, tenantId: UUID, hall: ResourcePool,
                            showingStartsAt: Instant): CalendarPricing.SeatPrice? {
        val rule = rateRuleRepo.findActive(c, tenantId, hall.resourcePoolId)
        if (rule.isEmpty) {
            return null
        }
        return try {
            calendarPricing.priceFor(c, tenantId, rule.get(), hall, showingStartsAt)
        } catch (e: IllegalStateException) {
            LOG.warnf(e, "Hall %s has an unreadable rate_rule; showing the seat map without a price",
                hall.resourcePoolId)
            null
        }
    }

    // --- The public-holiday list --------------------------------------
    //
    // A pricing input that cannot be derived from a date and must not come from a third-party
    // API on a box that has to keep selling with its uplink down. Staff maintain it; the
    // decree that sets it is published once a year.

    /** This year onward — the list as the admin screen shows it. */
    fun listHolidays(): List<PublicHoliday> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            holidayRepo.findFrom(c, tenantId, LocalDate.now().withDayOfYear(1))
        }
    }

    fun addHoliday(date: LocalDate?, name: String?): PublicHoliday {
        if (date == null) {
            throw IllegalArgumentException("A holiday needs a date.")
        }
        if (name == null || name.isJavaBlank()) {
            throw IllegalArgumentException("A holiday needs a name — staff get asked at the " +
                "counter why a ticket cost the top band, and 'holiday' is not an answer.")
        }
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> holidayRepo.upsert(c, tenantId, date, name.javaTrim()) }
    }

    fun removeHoliday(publicHolidayId: UUID?): Int {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> holidayRepo.delete(c, tenantId, publicHolidayId) }
    }

    /** "Create and manage a showing" — a film, a start time, a hall. Created directly
     * `on_sale`: a solo-operator cinema has no separate on-sale-scheduling workflow to
     * model yet. */
    fun createShowing(resourcePoolId: UUID?, label: String?, startsAt: Instant?,
                      endsAt: Instant?, salesCutoffAt: Instant?): ResourcePoolSession {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            sessionRepo.insert(c, tenantId, resourcePoolId, label, startsAt, endsAt, salesCutoffAt, "on_sale")
        }
    }

    /**
     * A showing of a known film.
     *
     * `label` is still written, derived from the title when the operator did not type
     * one. The column is `NOT NULL` and stays that way: the schema stays
     * vertical-neutral, so a label-only session remains a first-class case and every existing
     * row keeps meaning what it meant. The film is the structured fact; the label is what a
     * non-film session has instead.
     */
    fun createShowingOfFilm(resourcePoolId: UUID?, filmId: UUID?, label: String?,
                            startsAt: Instant?, endsAt: Instant?, salesCutoffAt: Instant?): ResourcePoolSession {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val resolvedLabel = if (label == null || label.isJavaBlank()) {
                filmRepo.findById(c, tenantId, filmId)
                    .orElseThrow { NotFoundException("Film not found: $filmId") }
                    .title
            } else {
                label
            }
            sessionRepo.insert(c, tenantId, resourcePoolId, resolvedLabel, startsAt, endsAt,
                salesCutoffAt, "on_sale", filmId)
        }
    }

    // --- Films ------------------------------------------------------------

    /** Films with at least one upcoming sellable showing — the film-first browse path. */
    fun listNowPlaying(): List<Film> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> filmRepo.findNowPlaying(c, tenantId) }
    }

    fun listFilms(): List<Film> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> filmRepo.findAllByTenant(c, tenantId) }
    }

    fun getFilmOrThrow(filmId: UUID?): Film {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> filmRepo.findById(c, tenantId, filmId) }
            .orElseThrow { NotFoundException("Film not found: $filmId") }
    }

    /** Where and when a film plays, across every branch — the question the schema could not
     * answer before the film table. */
    fun listShowingsOfFilm(filmId: UUID?): List<ResourcePoolSessionRepository.FilmShowing> {
        val tenantId = currentTenant.publicCatalogueId()
        return db.inTenantTransaction(tenantId) { c -> sessionRepo.findUpcomingByFilm(c, tenantId, filmId) }
    }

    fun createFilm(title: String?, durationMinutes: Int?, classification: String?, synopsis: String?,
                   posterUrl: String?, trailerUrl: String?, releaseDate: LocalDate?): Film {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val slug = Slug.free(title, "film") { base -> filmRepo.findSlugsUnder(c, tenantId, base) }
            filmRepo.insert(c, tenantId, title, slug, durationMinutes,
                classification, synopsis, posterUrl, trailerUrl, releaseDate)
        }
    }

    fun updateShowingStatus(sessionId: UUID?, status: String?) {
        // A cancellation is not a status edit: its seats, refunds and watchers go with it.
        if ("cancelled" == status) {
            bookingService.cancelShowing(sessionId)
            return
        }
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c -> sessionRepo.updateStatus(c, tenantId, sessionId, status) }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(CatalogService::class.java)

        private fun update(tenantId: UUID, change: SeatMapDiff.Repositioning, active: Boolean): Array<Any?> =
            arrayOf(change.to.groupKey, change.to.sequenceNo, active, change.to.accessible, change.to.accessible,
                tenantId, change.seat.resourceUnitId)

        private fun blockedBy(claimed: Set<UUID>, diff: SeatMapDiff): List<String> =
            diff.retiring
                .filter { seat -> claimed.contains(seat.resourceUnitId) }
                .map { it.label!! }

        private fun summarise(seats: List<SeatMapPlan.Seat>, existing: Set<String>): SeatMapPreview {
            val conflicts = ArrayList<String>()
            val rows = LinkedHashSet<String?>()
            for (seat in seats) {
                rows.add(seat.groupKey)
                if (existing.contains(seat.label)) {
                    conflicts.add(seat.label)
                }
            }
            return SeatMapPreview(seats, conflicts, rows.size)
        }
    }
}
