package com.protek.booking.security

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

/**
 * The first authorization rule made into a test, because it is exactly the one that needs it:
 * "Every admin resource needs `@RolesAllowed` enforcement independently, and that needs
 * a test proving a `customer`-role identity gets a 403 hitting an admin URL directly —
 * not merely that the nav link isn't rendered."
 *
 * So these assertions deliberately hit URLs directly with `RestAssured` rather than
 * inspecting rendered HTML for the presence or absence of an anchor. A test that asserted the
 * nav link is hidden would pass identically whether or not the endpoint is protected, which
 * makes it worse than no test: it would provide false confidence about the exact property
 * is easy to confuse.
 *
 * `@TestSecurity` supplies the identity directly, so what is under test is this
 * application's authorization wiring rather than Google's login page. That also means these
 * tests run with `quarkus.oidc.tenant-enabled=false` (the default), which is the point:
 * the authorization boundary must not depend on the identity provider being reachable.
 */
@QuarkusTest
class AdminAuthorizationTest {

    companion object {
        // Every mutating admin path, not just the landing page: the class-level @RolesAllowed is
        // what protects them all, and enumerating them is what would catch a future method that
        // gets its own narrower annotation by mistake.
        private val TENANT_STAFF_PATHS = arrayOf(
            "/admin",
            "/admin/halls/00000000-0000-0000-0000-000000000002",
            "/admin/showings/00000000-0000-0000-0000-000000000003/reservations",
            "/admin/showings/00000000-0000-0000-0000-000000000003/admission",
            "/admin/counter/showings/00000000-0000-0000-0000-000000000003",
            // Both of these are their own resource class rather than a method on
            // HallAdminResource, so neither inherits that class's annotation — which is exactly
            // the case this enumeration exists to catch.
            "/admin/holidays",
            "/admin/payments",
        )
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"])
    fun customerRole_gettingAdminUrlsDirectly_is403_notMerelyAnUnrenderedLink() {
        for (path in TENANT_STAFF_PATHS) {
            given().`when`().get(path)
                .then().statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"])
    fun customerRole_postingToAdminUrlsDirectly_is403() {
        // A GET-only check would miss the case that actually matters: a customer who cannot
        // see the form but can still submit to its action URL.
        given().contentType("application/x-www-form-urlencoded")
            .formParam("name", "Injected Hall")
            .`when`().post("/admin/halls")
            .then().statusCode(403)

        given().contentType("application/x-www-form-urlencoded")
            .formParam("status", "cancelled")
            .`when`().post("/admin/showings/00000000-0000-0000-0000-000000000003/status")
            .then().statusCode(403)

        given().contentType("application/x-www-form-urlencoded")
            .formParam("seatIds", "00000000-0000-0000-0000-000000000009")
            .`when`().post("/admin/counter/showings/00000000-0000-0000-0000-000000000003/hold")
            .then().statusCode(403)

        // The door. Worth its own line rather than trusting the GET above: this is the one
        // admin POST whose whole job is to change a row a customer has an interest in, and the
        // credential it takes arrives in the form body — so "cannot see the page" must not be
        // mistaken for "cannot submit to it".
        given().contentType("application/x-www-form-urlencoded")
            .formParam("admissionToken", "BPT-000000000000000000000000")
            .`when`().post("/admin/showings/00000000-0000-0000-0000-000000000003/admission")
            .then().statusCode(403)
    }

    @Test
    fun anonymous_gettingAdminUrls_isRejected() {
        for (path in TENANT_STAFF_PATHS) {
            // 401 with OIDC disabled (no mechanism can authenticate), or 302 to the provider
            // when it is enabled. Either is a rejection; what must never happen is 200.
            val status = given().redirects().follow(false).`when`().get(path)
                .then().extract().statusCode()
            org.junit.jupiter.api.Assertions.assertTrue(status == 401 || status == 302,
                "anonymous GET " + path + " returned " + status + "; expected 401 or a redirect to the IdP")
        }
    }

    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun platformAdmin_isNotAutomaticallyTenantStaff() {
        // A platform admin is not a superset of tenant_staff here, on purpose: the role model scopes
        // tenant_staff to one tenant and leaves platform_admin global, so a platform admin has
        // no tenant whose halls these would be. Letting the global role through would mean
        // CurrentTenant silently substituting the configured default tenant — a cross-tenant
        // action that looks correct.
        given().`when`().get("/admin").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"])
    fun tenantStaff_cannotReachPlatformEndpoints() {
        given().`when`().get("/platform/tenants").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"])
    fun customerRole_cannotReachPlatformEndpoints() {
        given().`when`().get("/platform/tenants").then().statusCode(403)
    }

    @Test
    fun publicBrowsing_staysPublic() {
        // The complement of the checks above, and worth asserting explicitly: the public use cases
        // 1-2 are public, so a regression that "fixed" authorization by protecting everything
        // would be caught here rather than by a customer unable to see showtimes.
        given().`when`().get("/").then().statusCode(200)
    }
}
