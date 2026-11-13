package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePoolSession
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Backs "create and manage a showing" (tenant-facing) and "browse showtimes" (customer-facing). */
@ApplicationScoped
class ResourcePoolSessionRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, label: String?,
               startsAt: Instant?, endsAt: Instant?, salesCutoffAt: Instant?, status: String?): ResourcePoolSession =
        insert(c, tenantId, resourcePoolId, label, startsAt, endsAt, salesCutoffAt, status, null)

    /** As above, for a showing of a known film. `filmId` may be null — a
     * label-only session is a first-class case, not a degraded one. */
    fun insert(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, label: String?,
               startsAt: Instant?, endsAt: Instant?, salesCutoffAt: Instant?, status: String?,
               filmId: UUID?): ResourcePoolSession =
        db.queryOne(c,
            "INSERT INTO resource_pool_session (tenant_id, resource_pool_id, label, starts_at, ends_at, " +
                    "sales_cutoff_at, status, film_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, resourcePoolId, label, startsAt, endsAt, salesCutoffAt, status, filmId
        ).orElseThrow()

    /**
     * Every upcoming showing of one film across the whole tenant, with the hall and branch
     * it plays in — the film-first browse path.
     *
     * Joined in SQL rather than resolved per session in Java: a title playing across a
     * 38-branch chain is the case this exists for, and the per-row alternative is one query
     * per showing to name its hall and another to name its branch.
     */
    fun findUpcomingByFilm(c: Connection, tenantId: UUID?, filmId: UUID?): List<FilmShowing> =
        db.query(c,
            "SELECT s.resource_pool_session_id, s.starts_at, s.ends_at, s.status, s.label, " +
                    "p.resource_pool_id, p.name AS hall_name, p.timezone AS hall_timezone, " +
                    "site.site_id, site.name AS site_name, site.slug AS site_slug, " +
                    "site.city AS site_city, site.province AS site_province, " +
                    "site.address AS site_address, site.timezone AS site_timezone " +
                    "FROM resource_pool_session s " +
                    "JOIN resource_pool p ON p.resource_pool_id = s.resource_pool_id " +
                    "LEFT JOIN site ON site.site_id = p.site_id " +
                    "WHERE s.tenant_id = ? AND s.film_id = ? " +
                    "AND s.status IN ('scheduled','on_sale') AND s.starts_at > now() " +
                    "ORDER BY site.city, site.name, s.starts_at",
            ::mapFilmShowing,
            tenantId, filmId)

    /**
     * One showing of a film, with where it plays. Every `site*` field is null for a hall
     * not yet assigned to a branch — still shown, never hidden.
     *
     * The branch's address and both timezones ride along because the SEO markup on this
     * page needs them per row: a `ScreeningEvent` needs
     * `location.address` and an ISO-8601 `startDate` carrying the branch's offset.
     * Selected here rather than looked up per showing because a title playing across a
     * 38-branch chain is the case this query exists for, and per-row lookups are what its
     * original comment already refused.
     *
     * A `@JvmRecord`, as the nested Java record was, so Java, Qute and the tests keep calling
     * `startsAt()`, `siteName()` and the rest.
     */
    @JvmRecord
    data class FilmShowing(val resourcePoolSessionId: UUID?, val startsAt: Instant?, val endsAt: Instant?,
                           val status: String?,
                           val resourcePoolId: UUID?, val hallName: String?, val hallTimezone: String?,
                           val siteId: UUID?, val siteName: String?, val siteSlug: String?, val siteCity: String?,
                           val siteProvince: String?, val siteAddress: String?, val siteTimezone: String?)

    /**
     * Every upcoming showing of one film at one branch, earliest first.
     *
     * Three pages read this and they are the reason it returns [FilmShowing] rather
     * than `ResourcePoolSession`: the showtime strip under a film on a branch page, the
     * same strip under a branch on a film page, and the booking page's date and time fields —
     * all of which name the studio, and one bare session does not know what it is called.
     *
     * `INNER JOIN site` here, where [findUpcomingByFilm] uses `LEFT` on
     * purpose: this query is asked *about* a branch, so a hall with no branch is not a
     * row it is missing, it is a row that is not being asked about.
     *
     * `startsFrom` and `startsBefore`, given together, narrow `starts_at` to that window, on
     * `idx_session_film`; either one null asks for every upcoming showing.
     */
    fun findUpcomingByFilmAtSite(c: Connection, tenantId: UUID?, filmId: UUID?, siteId: UUID?,
                                 startsFrom: Instant? = null, startsBefore: Instant? = null): List<FilmShowing> {
        val window = startsFrom != null && startsBefore != null
        return db.query(c,
            "SELECT s.resource_pool_session_id, s.starts_at, s.ends_at, s.status, s.label, " +
                    "p.resource_pool_id, p.name AS hall_name, p.timezone AS hall_timezone, " +
                    "site.site_id, site.name AS site_name, site.slug AS site_slug, " +
                    "site.city AS site_city, site.province AS site_province, " +
                    "site.address AS site_address, site.timezone AS site_timezone " +
                    "FROM resource_pool_session s " +
                    "JOIN resource_pool p ON p.resource_pool_id = s.resource_pool_id " +
                    "JOIN site ON site.site_id = p.site_id " +
                    "WHERE s.tenant_id = ? AND s.film_id = ? AND site.site_id = ? " +
                    "AND s.status IN ('scheduled','on_sale') AND s.starts_at > now() " +
                    (if (window) "AND s.starts_at >= ? AND s.starts_at < ? " else "") +
                    "ORDER BY s.starts_at, p.name",
            ::mapFilmShowing,
            *(if (window) arrayOf<Any?>(tenantId, filmId, siteId, startsFrom, startsBefore) else arrayOf<Any?>(tenantId, filmId, siteId)))
    }

    /**
     * Showings that have started, are still sellable, and have sold fewer than `minimumSeats`
     * seats — the showtime viability rule.
     *
     * `starts_at < now() - grace`: the clock runs from the advertised start, which is
     * what "15 minutes after display time" means at the branch. Not from `created_at`
     * (that would cancel next month's schedule the moment it was published) and not from
     * `sales_cutoff_at` (which is nullable and is a different policy).
     *
     * **The count is of live seats, not of paid ones.** `held` counts alongside
     * `confirmed` for the same reason the `EXCLUDE` constraint's predicate names
     * both: a hold is a claim on the seat, and cancelling a showing out from under somebody
     * who is on the payment page would be the rule eating its own customer. It also matches
     * what the seat map shows, so staff looking at a full-looking map never see it cancelled.
     *
     * `FOR UPDATE OF s` so the sweep and a concurrent hold cannot interleave between
     * the count and the cancel — `BookingService.holdInternal` takes the same row lock
     * through [findByIdForUpdate] before it counts anything, so one of the two waits.
     * `SKIP LOCKED` is deliberately absent: a showing whose row is busy is one the sweep
     * should look at on the next pass, not one it should silently pass over forever.
     */
    fun findStartedAndUndersold(c: Connection, tenantId: UUID?,
                                grace: Duration, minimumSeats: Int): List<ResourcePoolSession> =
        db.query(c,
            "SELECT " + prefixed("s") + " FROM resource_pool_session s " +
                    "WHERE s.tenant_id = ? AND s.status IN ('scheduled','on_sale') " +
                    "AND s.starts_at < now() - CAST(? AS interval) " +
                    "AND (SELECT count(*) FROM reservation r " +
                    "     WHERE r.tenant_id = s.tenant_id " +
                    "     AND r.resource_pool_session_id = s.resource_pool_session_id " +
                    "     AND r.status IN ('held','confirmed')) < ? " +
                    "ORDER BY s.starts_at " +
                    "FOR UPDATE OF s",
            ::map, tenantId, grace.toSeconds().toString() + " seconds", minimumSeats)

    /** How many seats of this showing are a live claim — `held` or `confirmed`,
     * the same two statuses the `EXCLUDE` constraint's predicate names. */
    fun countLiveSeats(c: Connection, tenantId: UUID?, sessionId: UUID?): Int =
        db.queryOne(c,
            "SELECT count(*) AS live FROM reservation " +
                    "WHERE tenant_id = ? AND resource_pool_session_id = ? " +
                    "AND status IN ('held','confirmed')",
            { rs -> rs.getInt("live") }, tenantId, sessionId).orElse(0)

    fun findById(c: Connection, tenantId: UUID?, sessionId: UUID?): Optional<ResourcePoolSession> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM resource_pool_session WHERE tenant_id = ? AND resource_pool_session_id = ?",
            ::map, tenantId, sessionId)

    /**
     * The session-status re-validation fix: locks the session row so a
     * concurrent cancellation blocks until this transaction resolves, rather than
     * racing a plain read against a not-yet-committed status change.
     */
    fun findByIdForUpdate(c: Connection, tenantId: UUID?, sessionId: UUID?): Optional<ResourcePoolSession> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM resource_pool_session WHERE tenant_id = ? AND resource_pool_session_id = ? FOR UPDATE",
            ::map, tenantId, sessionId)

    fun findUpcomingByPool(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): List<ResourcePoolSession> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_pool_session " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? " +
                    "AND status IN ('scheduled','on_sale') AND starts_at > now() " +
                    "ORDER BY starts_at",
            ::map, tenantId, resourcePoolId)

    fun updateStatus(c: Connection, tenantId: UUID?, sessionId: UUID?, status: String?): Int =
        db.execute(c,
            "UPDATE resource_pool_session SET status = ? WHERE tenant_id = ? AND resource_pool_session_id = ?",
            status, tenantId, sessionId)

    private fun mapFilmShowing(rs: ResultSet): FilmShowing =
        FilmShowing(
            rs.getObject("resource_pool_session_id", UUID::class.java),
            rs.getTimestamp("starts_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(),
            rs.getString("status"),
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getString("hall_name"),
            rs.getString("hall_timezone"),
            rs.getObject("site_id", UUID::class.java),
            rs.getString("site_name"),
            rs.getString("site_slug"),
            rs.getString("site_city"),
            rs.getString("site_province"),
            rs.getString("site_address"),
            rs.getString("site_timezone"))

    private fun map(rs: ResultSet): ResourcePoolSession {
        val cutoff = rs.getTimestamp("sales_cutoff_at")
        return ResourcePoolSession(
            rs.getObject("resource_pool_session_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getString("label"),
            rs.getTimestamp("starts_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(),
            cutoff?.toInstant(),
            rs.getString("status"),
            rs.getObject("film_id", UUID::class.java),
        )
    }

    private companion object {
        const val COLUMNS =
            "resource_pool_session_id, tenant_id, resource_pool_id, label, starts_at, ends_at, sales_cutoff_at, " +
                    "status, film_id"

        /** [COLUMNS] qualified with a table alias, so a query that joins can still select
         * exactly the set [map] reads. Kept in one place because the two lists silently
         * drifting apart is a `SQLException` at runtime and nothing at compile time.
         *
         * Kotlin's `split` takes the literal where Java's took a regex. `", "` has no
         * regex metacharacter and `COLUMNS` has no trailing separator, so both give the same
         * nine columns. */
        fun prefixed(alias: String): String =
            COLUMNS.split(", ").joinToString(", ") { column -> "$alias.$column" }
    }
}
