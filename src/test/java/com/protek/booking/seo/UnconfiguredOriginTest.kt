package com.protek.booking.seo

import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the SEO layer does on a deployment that has not been told its own public origin.
 *
 * This is the default state — `booking.public.https-base-url` is
 * `${PUBLIC_HTTPS_BASE_URL:}` and empty unless an operator sets it, which is how local
 * development and every other test in this suite run. It is a real state rather than a fault,
 * so the rule is the same one `SignInLink` follows: degrade, never guess, and above all
 * never fall back to the `Host` header — a canonical built from caller-supplied input is
 * a crawler being told, by us, that the real copy of this page lives on a stranger's hostname.
 */
@QuarkusTest
class UnconfiguredOriginTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    @Test
    fun aBranchPageStillRendersAndSimplyClaimsNoCanonical() {
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, "Unconfigured Origin " + UUID.randomUUID(), "Rekaan")

        val page = given().get("/cinemas/" + branch.slug)
            .then().statusCode(200).extract().body().asString()

        assertTrue(page.contains(branch.name!!), "the page itself is unaffected")
        assertFalse(page.contains("rel=\"canonical\""),
            "no origin means no canonical to claim — not a canonical pointing at localhost")
        assertFalse(page.contains("og:url"))
    }

    @Test
    fun theMarkupStillRendersWithoutTheUrlPropertiesItCannotFill() {
        // The JSON-LD is not suppressed wholesale: name, address and screen count are all
        // still true and still worth emitting. Only the absolute URLs are missing.
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, "Still Marked Up " + UUID.randomUUID(), "Solo")

        val page = given().get("/cinemas/" + branch.slug)
            .then().statusCode(200).extract().body().asString()

        assertTrue(page.contains("application/ld+json"))
        assertTrue(page.contains("MovieTheater"))
    }

    @Test
    fun theSitemapRefusesRatherThanPublishingUrlsNoCrawlerCouldUse() {
        // 503 and not 404: a <loc> must be absolute, so there is nothing valid to emit — but
        // the missing piece is one line of deployment configuration that is expected to
        // arrive. 404 tells a crawler to forget the sitemap; 503 tells it to come back.
        given().get("/sitemap-showtimes.xml")
            .then().statusCode(503)
            .body(containsString("PUBLIC_HTTPS_BASE_URL"))
    }
}
