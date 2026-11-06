package com.protek.booking.service

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.SiteRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The city → branch → hall browse path.
 *
 * What these pin down is the thing that made a chain unrepresentable before the site table: that a
 * hall now knows which branch it is in, and that "what is playing at Rekaan" is a query
 * rather than an impossibility.
 */
@QuarkusTest
class SiteBrowseTest {

    companion object {
        /** Every test here runs under the one configured tenant (CatalogService resolves
         * its tenant rather than accepting one), so branches accumulate across test methods.
         * Unique city names keep each test asserting about its own data. */
        private fun aCity(name: String): String = name + "-" + UUID.randomUUID()
    }

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun citiesAreGroupedWithABranchCountRatherThanListedPerBranch() {
        val tenant: Tenant = currentTenant.get()
        val rekaanCityName = aCity("Rekaan")
        val kudusCityName = aCity("Kudus")
        fixture.site(tenant, "Demo Cinema Rekaan", rekaanCityName)
        fixture.site(tenant, "Demo Cinema Rekaan 2", rekaanCityName)
        fixture.site(tenant, "Demo Cinema Kudus", kudusCityName)

        val cities: List<SiteRepository.CityCount> = catalogService.listCities()

        val rekaanCity = cities.first { city -> city.city == rekaanCityName }
        assertEquals(2, rekaanCity.branchCount, "two branches in one city must collapse to one entry")
        assertEquals("Jawa Tengah", rekaanCity.province)
        assertTrue(cities.any { city -> city.city == kudusCityName })
    }

    @Test
    fun aBranchListsOnlyItsOwnStudios() {
        val tenant = currentTenant.get()
        val rekaan: Site = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan")
        val kudus: Site = fixture.site(tenant, "Demo Cinema Kudus", "Kudus")
        fixture.poolAtSite(tenant, rekaan, "Studio 1")
        fixture.poolAtSite(tenant, rekaan, "Studio 2")
        fixture.poolAtSite(tenant, kudus, "Studio 1")

        val halls: List<ResourcePool> = catalogService.listHallsAtSite(rekaan.siteId)

        // Three studios named "Studio 1" exist across the tenant; the branch is what makes
        // them distinguishable, which is the entire point of branches.
        assertEquals(2, halls.size)
        assertEquals(listOf("Studio 1", "Studio 2"), halls.map { it.name })
        assertTrue(halls.all { hall -> rekaan.siteId == hall.siteId })
    }

    @Test
    fun aCityListsOnlyItsOwnBranches() {
        val tenant = currentTenant.get()
        val rekaanCityName = aCity("Rekaan")
        fixture.site(tenant, "Demo Cinema Rekaan", rekaanCityName)
        fixture.site(tenant, "Demo Cinema Kudus", aCity("Kudus"))

        val sites: List<Site> = catalogService.listSitesInCity(rekaanCityName)

        assertEquals(1, sites.size)
        assertEquals("Demo Cinema Rekaan", sites[0].name)
    }

    @Test
    fun aHallCreatedWithoutABranchIsStillAHallAndStaysReachable() {
        // The additive guarantee. Every resource_pool row predating the site table has no site_id, and
        // a hall that silently vanished from the catalogue would be a far worse failure than
        // an ungrouped one.
        val tenant = currentTenant.get()
        val orphan = fixture.pool(tenant)

        assertNull(orphan.siteId)
        assertTrue(catalogService.listHallsWithoutSite()
            .any { hall -> hall.resourcePoolId == orphan.resourcePoolId },
            "an unassigned hall must be listed as unassigned, not dropped")
        assertNotNull(catalogService.getHallOrThrow(orphan.resourcePoolId),
            "an unassigned hall must still be reachable by its own link")
    }

    @Test
    fun anExistingHallCanBeMovedIntoABranchWithoutBeingRecreated() {
        val tenant = currentTenant.get()
        val orphan = fixture.pool(tenant)
        val branch = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan")

        catalogService.assignHallToSite(orphan.resourcePoolId, branch.siteId)

        assertEquals(branch.siteId, catalogService.getHallOrThrow(orphan.resourcePoolId).siteId)
        assertTrue(catalogService.listHallsAtSite(branch.siteId)
            .any { hall -> hall.resourcePoolId == orphan.resourcePoolId })
    }

    @Test
    fun aBranchCarriesItsOwnTimezoneWhileTheHallKeepsItsOverride() {
        // A chain spanning Kalimantan and Java is not one timezone, but
        // resource_pool.timezone was deliberately kept rather than dropped.
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, "Demo Cinema Kotabaru", "Kotabaru")
        val hall = fixture.poolAtSite(tenant, branch, "Studio 1")

        assertEquals("Asia/Jakarta", catalogService.getSiteOrThrow(branch.siteId).timezone)
        assertNotNull(hall.timezone, "the per-hall timezone override must survive the site table")
    }
}
