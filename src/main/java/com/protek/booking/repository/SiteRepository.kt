package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.Site
import com.protek.booking.seo.Slug
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

/** Branches — the level between a tenant and its auditoriums. */
@ApplicationScoped
class SiteRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, name: String?, slug: String?, city: String?, province: String?,
               address: String?, timezone: String?): Site =
        db.queryOne(c,
            "INSERT INTO site (tenant_id, name, slug, city, province, address, timezone) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, name, slug, city, province, address, timezone
        ).orElseThrow()

    fun findById(c: Connection, tenantId: UUID?, siteId: UUID?): Optional<Site> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM site WHERE tenant_id = ? AND site_id = ?",
            ::map, tenantId, siteId)

    /** The public URL's other form. Not restricted to `is_active`, unlike the
     * browse lists: a branch that has been switched off should render whatever the page can
     * still say rather than 404 a link that is out in the world, and every caller reaching
     * this is following such a link. */
    fun findBySlug(c: Connection, tenantId: UUID?, slug: String?): Optional<Site> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM site WHERE tenant_id = ? AND slug = ?",
            ::map, tenantId, slug)

    /**
     * The slugs already taken under `base` — `base` itself and anything of the
     * form `base-<n>` — so a new branch can be given the first free one.
     *
     * `LIKE base || '-%'` rather than a regex: the suffix is the only thing that
     * varies, the caller does the picking, and this shape uses the unique index that the slug
     * constraint created.
     */
    fun findSlugsUnder(c: Connection, tenantId: UUID?, base: String?): List<String> =
        db.query(c,
            "SELECT slug FROM site WHERE tenant_id = ? AND (slug = ? OR slug LIKE ? ESCAPE '\\')",
            { rs -> rs.getString("slug") },
            tenantId, base, Slug.likePrefix(base))

    fun findAllByTenant(c: Connection, tenantId: UUID?): List<Site> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM site WHERE tenant_id = ? AND is_active " +
                    "ORDER BY city, name",
            ::map, tenantId)

    fun findByCity(c: Connection, tenantId: UUID?, city: String?): List<Site> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM site WHERE tenant_id = ? AND city = ? AND is_active " +
                    "ORDER BY name",
            ::map, tenantId, city)

    /**
     * The branches with an upcoming showing of one film — the film page's list.
     *
     * An `EXISTS` rather than a join plus `DISTINCT`, the same shape and the
     * same reason as `FilmRepository.findNowPlaying`: the question is whether any showing
     * exists, not which, so a title playing in four of a branch's studios yields one row rather
     * than four. That case is not hypothetical — it is what a chain does with an opening
     * weekend.
     *
     * Ordered by city then name so a customer reading down the list finds their own city
     * grouped rather than scattered, which for a 38-branch chain is the difference between a
     * list and a wall.
     */
    fun findShowingFilm(c: Connection, tenantId: UUID?, filmId: UUID?): List<Site> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM site s WHERE s.tenant_id = ? AND s.is_active " +
                    "AND EXISTS (SELECT 1 FROM resource_pool_session sess " +
                    "            JOIN resource_pool p ON p.resource_pool_id = sess.resource_pool_id " +
                    "                 AND p.tenant_id = sess.tenant_id " +
                    "            WHERE p.site_id = s.site_id AND sess.tenant_id = s.tenant_id " +
                    "            AND sess.film_id = ? " +
                    "            AND sess.status IN ('scheduled','on_sale') AND sess.starts_at > now()) " +
                    "ORDER BY s.city, s.name",
            ::map, tenantId, filmId)

    /**
     * The cities this tenant operates in — the first level of the browse path.
     *
     * Grouped in SQL rather than by collecting every site and reducing in Java: for a
     * 38-branch chain that is the difference between shipping 38 rows to render 8 links and
     * shipping 8. The count travels with it because "Kota Lama (1)" is the useful label.
     */
    fun findCities(c: Connection, tenantId: UUID?): List<CityCount> =
        db.query(c,
            "SELECT city, province, count(*) AS branch_count, " +
                    "avg(latitude) AS centre_latitude, avg(longitude) AS centre_longitude " +
                    "FROM site " +
                    "WHERE tenant_id = ? AND is_active " +
                    "GROUP BY city, province ORDER BY city",
            { rs -> CityCount(rs.getString("city"), rs.getString("province"), rs.getInt("branch_count"),
                rs.getBigDecimal("centre_latitude"), rs.getBigDecimal("centre_longitude")) },
            tenantId)

    /**
     * One city in the browse list, with how many branches it holds and roughly where it is.
     *
     * A `@JvmRecord`, as the nested Java record was, so `city()` and `isLocated()` keep the
     * names `NearestCity`, `CityPreference` and the templates call.
     *
     * @param centreLatitude  the mean of this city's branch coordinates, or null when none of
     *        them has one. `avg()` ignores nulls rather than poisoning the group, so a
     *        city with three branches and one coordinate is placed at that branch — which is
     *        the best answer available and better than dropping the city off the ranking.
     */
    @JvmRecord
    data class CityCount(val city: String?, val province: String?, val branchCount: Int,
                         val centreLatitude: BigDecimal?, val centreLongitude: BigDecimal?) {

        /** Whether this city can take part in the nearest-city ranking at all. The site table left
         * latitude/longitude nullable and every row null, so "no coordinate" is the ordinary
         * state of a tenant who has not filled them in, not a fault. */
        fun isLocated(): Boolean = centreLatitude != null && centreLongitude != null
    }

    private fun map(rs: ResultSet): Site =
        Site(
            rs.getObject("site_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("city"),
            rs.getString("province"),
            rs.getString("address"),
            rs.getString("timezone"),
            // A cast, as the Java's (BigDecimal) was.
            rs.getObject("latitude") as BigDecimal?,
            rs.getObject("longitude") as BigDecimal?,
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "site_id, tenant_id, name, slug, city, province, address, timezone, latitude, longitude, " +
                    "is_active, created_at"
    }
}
