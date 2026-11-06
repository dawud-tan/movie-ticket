package com.protek.booking.web.customer

import com.protek.booking.service.DefaultTenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * `base.html`'s footer: the tenant's complaint phone on every page, so a buyer has it before
 * paying (PP 71/2019 Pasal 37(1)). `AppApiTest` checks the app's copy of the same number.
 */
@QuarkusTest
class ComplaintPhoneFooterTest {

    @Inject
    lateinit var defaultTenant: DefaultTenant

    private fun phone(): String = defaultTenant.get().complaintPhone.also { assertNotNull(it) }!!

    @Test
    fun aVisitorSeesTheComplaintPhoneOnTheHomePageAsADialableLink() {
        val phone = phone()
        given().`when`().get("/").then().statusCode(200)
            .body(containsString("Customer complaints:"))
            .body(containsString(">" + phone + "</a>"))
            .body(containsString("href=\"tel:" + phone.filter { it == '+' || it.isDigit() } + "\""))
    }

    // pse_registration_no was recordable and shown nowhere. Recorded through the
    // platform endpoint, as after provisioning, so the footer must not be reading a stale cache.
    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun aRecordedPseNumberAppearsInTheFooterAndNoneIsShownBeforeIt() {
        val tenantId = defaultTenant.id()
        given().`when`().get("/").then().statusCode(200).body(not(containsString("PSE registration no.")))
        try {
            given().contentType("application/json").body("{\"registrationNo\":\"PSE-TEST-0042\"}")
                .`when`().post("/platform/tenants/$tenantId/pse-registration").then().statusCode(204)
            given().`when`().get("/").then().statusCode(200)
                .body(containsString("PSE registration no. PSE-TEST-0042"))
            given().`when`().get("/id").then().statusCode(200)
                .body(containsString("Tanda Daftar PSE PSE-TEST-0042"))
        } finally {
            given().contentType("application/json").body("{\"registrationNo\":null}")
                .`when`().post("/platform/tenants/$tenantId/pse-registration").then().statusCode(204)
        }
    }

    @Test
    fun theIndonesianSiteLabelsTheSameNumberInIndonesian() {
        given().`when`().get("/id").then().statusCode(200)
            .body(containsString("Pengaduan konsumen:"))
            .body(containsString(phone()))
    }

    // The footer reads the catalogue's tenant, not CurrentTenant.get(), which throws for a platform
    // admin: that read would turn every page 500 for them.
    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun aSignedInPlatformAdminStillGetsThePageWithTheFooter() {
        given().`when`().get("/movies").then().statusCode(200).body(containsString(phone()))
    }
}
