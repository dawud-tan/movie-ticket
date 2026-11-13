package com.protek.booking.geo

import com.protek.booking.repository.SiteRepository.CityCount
import com.protek.booking.service.NearestCity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Ranking a tenant's cities by distance from a visitor.
 *
 * A plain JUnit test and not a `@QuarkusTest`: [NearestCity] injects nothing and
 * touches no database — the whole design is that the candidate list arrives already grouped by
 * SQL, which is what makes the ranking cheap enough to do in Java at all. Starting a Quarkus
 * runtime to test arithmetic would be several seconds a run for no coverage.
 */
class NearestCityTest {

    private val nearestCity = NearestCity()

    companion object {
        /* Real coordinates, because the numbers below are checked against real distances. */
        private val JAKARTA = city("Jakarta Pusat", -6.1957, 106.8367)
        private val REKAAN = city("Rekaan", -7.0866, 110.9144)
        private val MEDAN = city("Medan", 3.5952, 98.6722)

        private fun city(name: String, latitude: Double, longitude: Double): CityCount =
            CityCount(name, "Test", 1, BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude))
    }

    @Test
    fun aVisitorNearRekaanIsSentToRekaanRatherThanToTheCapital() {
        // Standing outside the Rekaan branch, roughly.
        val chosen = nearestCity.nearest(
            listOf(JAKARTA, REKAAN, MEDAN), -7.0870, 110.9150)

        assertEquals("Rekaan", chosen.orElseThrow().city)
    }

    @Test
    fun aVisitorInJakartaIsSentToJakartaEvenThoughTheListIsNotOrderedByDistance() {
        // The list arrives ordered by city name (SiteRepository.findCities' ORDER BY), so the
        // nearest is not the first and cannot be picked by taking the head.
        val chosen = nearestCity.nearest(
            listOf(JAKARTA, MEDAN, REKAAN), -6.2000, 106.8200)

        assertEquals("Jakarta Pusat", chosen.orElseThrow().city)
    }

    @Test
    fun theDistanceIsGeodesicAndInMetres() {
        val metres = nearestCity.metresBetween(-6.1957, 106.8367,
            REKAAN.centreLatitude, REKAAN.centreLongitude)

        // Jakarta to Rekaan is a little over 450 km. The tolerance is wide because the point
        // of this assertion is the UNIT and the order of magnitude — a formula that returned
        // degrees, radians or kilometres would pass no part of it, and those are the mistakes
        // that actually happen. WGS-84 vs a sphere is a few hundred metres over this distance,
        // which is well inside it.
        assertTrue(metres > 440_000 && metres < 470_000,
            "expected roughly 450 km in metres, got " + metres)
    }

    @Test
    fun distanceIsSymmetric() {
        val there = nearestCity.metresBetween(-6.1957, 106.8367,
            MEDAN.centreLatitude, MEDAN.centreLongitude)
        val back = nearestCity.metresBetween(3.5952, 98.6722,
            JAKARTA.centreLatitude, JAKARTA.centreLongitude)

        // Not a tautology worth skipping: Inverse and Direct solve different problems, and using
        // Direct here — which is what the feature was first described as needing — would take an
        // azimuth this code has no source for and produce an asymmetric, meaningless number.
        assertEquals(there, back, 1.0)
    }

    @Test
    fun aCityWithNoCoordinatesIsSkippedRatherThanTreatedAsTheOriginOfTheWorld() {
        val unlocated = CityCount("Kudus", "Jawa Tengah", 3, null, null)

        val chosen = nearestCity.nearest(listOf(unlocated, REKAAN), -7.087, 110.915)

        // The trap this pins down: treating a null coordinate as (0, 0) puts the city in the
        // Gulf of Guinea, which is nearer to nothing and would still win against a distant real
        // city for a visitor somewhere in between. The site table left these columns nullable and null, so
        // a tenant with some cities mapped and some not is the ordinary state, not an edge case.
        assertEquals("Rekaan", chosen.orElseThrow().city)
    }

    @Test
    fun aTenantWithNoMappedCitiesGetsNoAnswerRatherThanAnArbitraryOne() {
        val chosen = nearestCity.nearest(
            listOf(CityCount("Kudus", "Jawa Tengah", 3, null, null)), -7.087, 110.915)

        // Empty, so the caller falls back to the configured default — the same page a visitor
        // who declined to share their location gets, rather than an error state of its own.
        assertTrue(chosen.isEmpty)
    }

    @Test
    fun anEmptyCatalogueRanksNothing() {
        assertTrue(nearestCity.nearest(listOf(), -7.087, 110.915).isEmpty)
    }
}
