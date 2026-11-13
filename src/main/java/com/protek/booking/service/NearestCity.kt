package com.protek.booking.service

import com.protek.booking.repository.SiteRepository
import jakarta.enterprise.context.ApplicationScoped
import net.sf.geographiclib.Geodesic
import net.sf.geographiclib.GeodesicData
import net.sf.geographiclib.GeodesicMask
import java.math.BigDecimal
import java.util.Optional

/**
 * Ranks the cities a tenant operates in by how far they are from a visitor.
 *
 * **Where the work is split, and why this is the fast arrangement rather than the lazy
 * one.** Three places could compute this and only one of them is free:
 *
 *  - **In PostgreSQL, with a distance function.** `earth_distance` lives in the
 *    `cube`+`earthdistance` contrib extensions and PostGIS in its own; both
 *    need `CREATE EXTENSION`, which a migrating role that owns the database but is not
 *    a superuser cannot run. The slug migration hit exactly this
 *    constraint over `unaccent` and worked around it rather than requiring the
 *    extension, and the contrib package is a manual step a rebuilt server has to
 *    remember. A feature that silently fails to install is worse than
 *    one that never needed installing. `earth_distance` also models a sphere, so it
 *    would be less accurate than what is already vendored here.
 *  - **In Java, over every branch.** One distance per `site` row: 100-200 rows for
 *    a regional chain, to rank 8 links.
 *  - **In Java, over the cities SQL already grouped.** `SiteRepository.findCities`
 *    has grouped in SQL since branches existed — its own comment says that for a 38-branch chain that is
 *    "the difference between shipping 38 rows to render 8 links and shipping 8". The home
 *    page runs that query anyway. So ranking its output costs **no extra query, no extra
 *    round trip, and one `Inverse` call per city** — bounded by how many cities a
 *    cinema chain operates in, which is tens.
 *
 * The third is what this does. The expensive part was never the arithmetic; it was the number
 * of rows crossing the socket, and the branch grouping had already paid that down.
 *
 * **`Inverse`, not `Direct`.** These are the two halves of the geodesic
 * problem and they answer different questions: `Direct` takes a point, an azimuth and a
 * distance and returns where you end up; `Inverse` takes two points and returns the
 * distance between them ([GeodesicData.s12], metres). Two points is what we have.
 *
 * **[GeodesicMask.DISTANCE] rather than the default.** The default computes the
 * azimuths at both ends and the arc length as well, none of which anything here reads. On the
 * WGS-84 ellipsoid that is real work, not a field copy.
 */
// GeographicLib keeps its upstream Java names (WGS84, Inverse) in Kotlin too, so this call
// reads as it did against the Java.
@ApplicationScoped
class NearestCity {

    /**
     * The nearest city to a visitor, or empty when nothing can be ranked.
     *
     * Empty is a real answer with two causes, and both are ordinary: the tenant has no
     * branches at all, or no branch has coordinates. The site table added latitude/longitude as optional
     * and left every row null, so a tenant who never filled them in is the common case, not a
     * misconfiguration. The caller falls back to the configured default city, which is what a
     * visitor who declined location sharing gets too — so the two paths converge instead of
     * one of them being an error state.
     */
    fun nearest(cities: List<SiteRepository.CityCount>,
                latitude: Double, longitude: Double): Optional<SiteRepository.CityCount> {
        var best: SiteRepository.CityCount? = null
        var bestMetres = Double.MAX_VALUE
        for (city in cities) {
            if (!city.isLocated()) {
                continue
            }
            val metres = metresBetween(latitude, longitude,
                city.centreLatitude, city.centreLongitude)
            if (metres < bestMetres) {
                bestMetres = metres
                best = city
            }
        }
        return Optional.ofNullable(best)
    }

    /**
     * Geodesic distance in metres on the WGS-84 ellipsoid — the same datum a browser's
     * `navigator.geolocation` reports in, so the two ends agree about what the numbers
     * mean.
     *
     * `doubleValue()` on the `numeric(9,6)` columns: six decimal places is
     * ~0.1 m at the equator and a `double` carries fifteen significant digits, so
     * nothing is lost. `BigDecimal` bought exactness in storage, which is where it was
     * wanted; trigonometry is not that place.
     */
    // Nullable as the Java accepted them; a null still fails with an NPE, as
    // doubleValue() did.
    fun metresBetween(latitude: Double, longitude: Double,
                      otherLatitude: BigDecimal?, otherLongitude: BigDecimal?): Double {
        val line: GeodesicData = Geodesic.WGS84.Inverse(latitude, longitude,
            otherLatitude!!.toDouble(), otherLongitude!!.toDouble(),
            GeodesicMask.DISTANCE)
        return line.s12
    }
}
