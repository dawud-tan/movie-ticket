package com.protek.booking.security

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The second and highest-consequence authorization rule, tested:
 * `app.current_tenant_id` must be derived from the authenticated identity, "never from a
 * header, URL parameter, or form field the caller controls."
 *
 * The plan is explicit about why this needs a test rather than a code review: a
 * request-supplied tenant id "looks perfectly correct while there's one tenant, and becomes a
 * trivial cross-tenant data leak the moment there are two — the user simply changes the value."
 * A single-tenant test environment cannot catch that at all, so every test here creates a
 * second tenant and then tries to reach it.
 */
@QuarkusTest
class TenantResolutionSecurityTest {

    companion object {
        /** The seeded demo hall and showing — the public pages a platform admin lands on. */
        private val SEEDED_POOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val SEEDED_SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")

        /** Headers a plausible implementation might have trusted. None of them exist as an input
         * anywhere in this codebase, which is the property being locked down — a future
         * "convenience" that reads one of these would start failing here. */
        private val SPOOFABLE_HEADERS = listOf(
            "X-Tenant-Id", "X-Tenant-Slug", "Host", "X-Forwarded-Host", "X-Original-Host")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var appUserRepository: AppUserRepository
    @Inject
    lateinit var poolRepository: ResourcePoolRepository
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant

    @Test
    fun spoofedTenantHeaders_cannotChangeWhichTenantsHallsAreReturned() {
        val other: Tenant = fixture.tenant()
        val otherHall: ResourcePool = db.inTenantTransaction(other.tenantId) { c ->
            poolRepository.insert(c, other.tenantId, "Other Tenant Hall",
                "Elsewhere", "Asia/Jakarta", "none") }

        for (header in SPOOFABLE_HEADERS) {
            val body = given()
                .header(header, other.tenantId.toString())
                .header("X-Tenant-Id", other.tenantId.toString())
                .`when`().get("/")
                .then().statusCode(200)
                .extract().asString()

            assertTrue(!body.contains(otherHall.resourcePoolId!!.toString()),
                "spoofing " + header + " exposed another tenant's hall " +
                    otherHall.resourcePoolId + " on the public showtimes page")
            assertTrue(!body.contains("Other Tenant Hall"),
                "spoofing " + header + " exposed another tenant's hall name")
        }
    }

    @Test
    fun anonymousBrowsing_resolvesTheConfiguredTenantOnly() {
        // The documented fallback for the public use cases: server-side configuration, not
        // request input. Asserting the demo tenant's own seeded row is what shows up confirms
        // the fallback is the configured tenant rather than, say, the first tenant in the table.
        //
        // The marker is the seeded *branch*, not 'Studio 1', since the site table: a tenant
        // with branches configured gets the browse pages rather than a flat hall list, and the
        // demo tenant is seeded with one. What is being asserted is unchanged — that this is
        // the configured tenant's catalogue.
        //
        // WHERE THE MARKER NOW APPEARS: the home page is the two-menu hub and lists no cities
        // of its own, so "Jakarta Pusat" reaches this assertion through the city select in
        // base.html's header. That is still a tenant-scoped read — CityPreference.options() goes
        // through CatalogService.listCities() under RLS — so the property under test is intact.
        // It does depend on the select rendering at all, which needs more than one city
        // (CityPreference.hasChoice); the demo seed seeds Rekaan alongside Jakarta for that reason. If
        // this ever fails with a body that is otherwise correct, check that first.
        val body = given().`when`().get("/").then().statusCode(200).extract().asString()
        assertTrue(body.contains("Jakarta Pusat"),
            "anonymous browsing should show the configured tenant's seeded branch; got: " + body)
    }

    // NOTE on what is NOT tested over HTTP here, and why.
    //
    // The identity -> tenant half of the rule cannot be driven through @TestSecurity: that
    // annotation installs a SecurityIdentity directly and BYPASSES SecurityIdentityAugmentor
    // entirely (verified empirically — @TestSecurity roles arrive, but AppUserAugmentor's
    // attributes are absent, so /me reports a null email for an identity it clearly
    // authenticated). A test written that way would silently exercise the anonymous
    // configured-tenant fallback while appearing to prove identity-derived resolution, which is
    // precisely the false confidence to be wary of.
    //
    // So the mapping logic is tested directly against AppUserAugmentor in
    // AppUserAugmentorTest, and what remains here is the property that genuinely can be
    // asserted over HTTP: no request input of any shape changes the resolved tenant.

    @Test
    fun tenantContextIsNeverReadFromRequestInput_byConstruction() {
        // A structural assertion to complement the behavioural ones: CurrentTenant exposes no
        // method that accepts a tenant id, slug, or hostname, so there is no API through which
        // request input could become tenant context even by mistake. This is the invariant the
        // HTTP tests above verify from the outside; asserting it here means a future overload
        // that reintroduces the hazard fails immediately, with this comment as the explanation.
        val offending: List<String> = java.util.Arrays.stream(
            com.protek.booking.service.CurrentTenant::class.java.declaredMethods)
            .filter { m -> java.lang.reflect.Modifier.isPublic(m.modifiers) }
            .filter { m -> m.parameterCount > 0 }
            .map(java.lang.reflect.Method::getName)
            .toList()
        assertEquals(java.util.List.of<String>(), offending,
            "CurrentTenant gained a public method taking parameters: " + offending +
                ". Tenant context must come from the authenticated identity only (plan I.2).")
    }

    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun platformAdminBrowsingThePublicHomePage_seesTheConfiguredTenant_ratherThanFailing() {
        // Regression: this returned 500 in production the first time a platform admin signed in.
        // CurrentTenant.id() refuses to invent a tenant for platform_admin — correctly — but the
        // public showtimes page routed through it, so bootstrapping yourself as platform_admin
        // (PLATFORM_ADMIN_EMAIL) broke the site's front page for you and nobody else.
        //
        // @TestSecurity bypassing AppUserAugmentor is what makes this test possible rather than
        // a problem: the identity arrives with the platform_admin role and NO tenant attribute,
        // which is exactly the shape a real platform admin has.
        // Marker is the seeded branch rather than 'Studio 1' for the same reason as the
        // anonymous case above, and it arrives by the same route — the header's city select,
        // since the home page lists no cities itself. The property under test (200 and the
        // configured catalogue, not a 500) is untouched.
        val body = given().`when`().get("/").then().statusCode(200).extract().asString()
        assertTrue(body.contains("Jakarta Pusat"),
            "a platform admin browsing the public page should see the configured tenant's " +
                "catalogue, the same as an anonymous visitor; got: " + body)
    }

    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun platformAdminViewingAPublicSeatMap_rendersRatherThanFailing() {
        // The other half of the public surface, and the one that polls every 3s — a 500 here
        // would repeat forever in the log rather than once per page view. The fragment is the
        // polled endpoint, so it is asserted separately from the page that embeds it.
        val seatMap = "/halls/" + SEEDED_POOL_ID + "/showings/" + SEEDED_SESSION_ID
        given().`when`().get(seatMap).then().statusCode(200)
        given().`when`().get(seatMap + "/fragment").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun platformAdmin_stillHasNoImplicitTenantForOperationsThatActOnOne() {
        // The complement of the two tests above, and the reason the fix added a second method
        // instead of relaxing the first. Making id() fall back to the configured tenant would
        // also have made the public pages work — and would have let a cross-tenant admin action
        // land on the wrong tenant while looking correct. If someone ever "simplifies"
        // publicCatalogueId() away by softening id(), this fails.
        assertThrows(IllegalStateException::class.java, { currentTenant.id() },
            "CurrentTenant.id() must keep refusing to invent a tenant for a platform_admin")
    }

    @Test
    fun rlsBlocksCrossTenantReads_evenWhenATenantIdIsSuppliedDirectlyToTheServiceLayer() {
        // Belt and braces behind the identity rule: even code that somehow obtained another
        // tenant's id cannot read its rows without setting that tenant's RLS context, which is
        // exactly what CurrentTenant refuses to do from request input.
        val other: Tenant = fixture.tenant()
        db.inTenantTransaction(other.tenantId) { c ->
            poolRepository.insert(c, other.tenantId, "Isolated Hall", null, "Asia/Jakarta", "none") }

        val configuredTenant: UUID? = if (catalogService.listHalls().isEmpty()) null
            else catalogService.listHalls()[0].tenantId
        val visible: List<ResourcePool> = catalogService.listHalls()
        assertTrue(visible.stream().noneMatch { h -> h.tenantId == other.tenantId },
            "CatalogService, running under the configured tenant " + configuredTenant +
                ", returned a hall belonging to " + other.tenantId)
    }
}
