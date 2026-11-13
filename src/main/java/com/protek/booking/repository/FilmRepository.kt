package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.Film
import com.protek.booking.seo.Slug
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/** Films — the thing a showing is a showing *of*. */
@ApplicationScoped
class FilmRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, title: String?, slug: String?, durationMinutes: Int?,
               classification: String?, synopsis: String?, posterUrl: String?, trailerUrl: String?,
               releaseDate: LocalDate?): Film =
        db.queryOne(c,
            "INSERT INTO film (tenant_id, title, slug, duration_minutes, classification, synopsis, " +
                    "poster_url, trailer_url, release_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, title, slug, durationMinutes, classification, synopsis, posterUrl,
            trailerUrl, if (releaseDate == null) null else Date.valueOf(releaseDate)
        ).orElseThrow()

    fun findById(c: Connection, tenantId: UUID?, filmId: UUID?): Optional<Film> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM film WHERE tenant_id = ? AND film_id = ?",
            ::map, tenantId, filmId)

    /** The public URL's other form. Deliberately not filtered on `is_active` —
     * see `SiteRepository.findBySlug` for the reasoning, which is the same one. */
    fun findBySlug(c: Connection, tenantId: UUID?, slug: String?): Optional<Film> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM film WHERE tenant_id = ? AND slug = ?",
            ::map, tenantId, slug)

    /** The slugs already taken under `base`, so a new title can be given the first free
     * one. Same shape and same reasoning as `SiteRepository.findSlugsUnder` — and a
     * re-release is the case that actually reaches it, since two rows may legitimately share
     * a title. */
    fun findSlugsUnder(c: Connection, tenantId: UUID?, base: String?): List<String> =
        db.query(c,
            "SELECT slug FROM film WHERE tenant_id = ? AND (slug = ? OR slug LIKE ? ESCAPE '\\')",
            { rs -> rs.getString("slug") },
            tenantId, base, Slug.likePrefix(base))

    fun findAllByTenant(c: Connection, tenantId: UUID?): List<Film> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM film WHERE tenant_id = ? AND is_active ORDER BY title",
            ::map, tenantId)

    /**
     * "Now playing" — films with at least one upcoming, sellable showing anywhere in the
     * tenant.
     *
     * An `EXISTS` rather than a join plus DISTINCT: the question is whether any
     * showing exists, not which, so this stops at the first match per film and cannot
     * duplicate a title that plays in forty studios — which is precisely the chain case.
     */
    fun findNowPlaying(c: Connection, tenantId: UUID?): List<Film> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM film f WHERE f.tenant_id = ? AND f.is_active " +
                    "AND EXISTS (SELECT 1 FROM resource_pool_session s " +
                    "            WHERE s.film_id = f.film_id AND s.tenant_id = f.tenant_id " +
                    "            AND s.status IN ('scheduled','on_sale') AND s.starts_at > now()) " +
                    "ORDER BY f.title",
            ::map, tenantId)

    /**
     * "Now playing *at this branch*" — the cinema-first path's second step.
     *
     * Same `EXISTS` shape and same reason as [findNowPlaying]: one title
     * playing in four of a branch's studios is one row, not four. The extra join is to
     * `resource_pool`, because a showing knows its hall and a hall knows its branch —
     * `resource_pool_session` carries no `site_id` of its own and must not, or the
     * two could disagree about where a showing happens.
     */
    fun findNowPlayingAtSite(c: Connection, tenantId: UUID?, siteId: UUID?): List<Film> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM film f WHERE f.tenant_id = ? AND f.is_active " +
                    "AND EXISTS (SELECT 1 FROM resource_pool_session s " +
                    "            JOIN resource_pool p ON p.resource_pool_id = s.resource_pool_id " +
                    "                 AND p.tenant_id = s.tenant_id " +
                    "            WHERE s.film_id = f.film_id AND s.tenant_id = f.tenant_id " +
                    "            AND p.site_id = ? " +
                    "            AND s.status IN ('scheduled','on_sale') AND s.starts_at > now()) " +
                    "ORDER BY f.title",
            ::map, tenantId, siteId)

    private fun map(rs: ResultSet): Film {
        val releaseDate = rs.getDate("release_date")
        return Film(
            rs.getObject("film_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("title"),
            rs.getString("slug"),
            // A cast, as the Java's `(Integer)` was: pgjdbc returns an Integer for an int4
            // column, or null, and anything else should fail loudly rather than convert.
            rs.getObject("duration_minutes") as Int?,
            rs.getString("classification"),
            rs.getString("synopsis"),
            rs.getString("poster_url"),
            rs.getString("trailer_url"),
            releaseDate?.toLocalDate(),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val COLUMNS =
            "film_id, tenant_id, title, slug, duration_minutes, classification, synopsis, poster_url, " +
                    "trailer_url, release_date, is_active, created_at"
    }
}
