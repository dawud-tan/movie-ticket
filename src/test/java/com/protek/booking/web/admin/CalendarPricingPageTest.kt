package com.protek.booking.web.admin

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.RatePlan
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The screens calendar pricing ships: setting a hall's bands, and maintaining the holiday list.
 *
 * Worth testing as markup rather than only through the service, because a pricing rule an
 * operator cannot enter is a pricing rule the tenant does not have — and because the templates
 * are where a wrong expression fails silently at render time rather than at compile time.
 */
@QuarkusTest
class CalendarPricingPageTest {

    companion object {
        private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var catalogService: CatalogService

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theRateFormAcceptsTheFourBandsAndTheHallPageShowsThemBack() {
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)

        given().contentType("application/x-www-form-urlencoded")
            .formParam("mode", "calendar")
            .formParam("monWed", "25000")
            .formParam("thuFri", "30000")
            .formParam("satSun", "35000")
            .formParam("holiday", "40000")
            .formParam("inclusions", "Termasuk voucher minuman Rp 10.000")
            .redirects().follow(false)
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/rate")
            .then().statusCode(303)

        val plan: RatePlan = catalogService.getRatePlan(hall.resourcePoolId).orElseThrow()
        assertTrue(plan.isCalendar())
        assertEquals(0, BigDecimal("40000").compareTo(plan.amountFor(RatePlan.Band.HOLIDAY)!!))

        // Rendered back, because four numbers an operator cannot see are four numbers they will
        // re-enter differently next time.
        given().`when`().get("/admin/halls/" + hall.resourcePoolId)
            .then().statusCode(200)
            .body(containsString("by calendar"))
            .body(containsString("Rp 25.000"))
            .body(containsString("Rp 40.000"))
            .body(containsString("Termasuk voucher minuman Rp 10.000"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBlankBandIsRefusedWithAMessageRatherThanStoredAsNothing() {
        // A rule missing a band prices three days of the week and fails on the fourth, at
        // whatever hour the first Thursday hold arrives. Refused at the form.
        val hall: ResourcePool = fixture.pool(currentTenant.get())

        given().contentType("application/x-www-form-urlencoded")
            .formParam("mode", "calendar")
            .formParam("monWed", "25000")
            .formParam("satSun", "35000")
            .redirects().follow(false)
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/rate")
            .then().statusCode(400)

        assertTrue(catalogService.getRatePlan(hall.resourcePoolId).isEmpty,
            "a refused form must not have written a partial rule")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBlankHolidayBandFallsBackToTheWeekendRate() {
        // Deliberate, not an oversight: a typical price list sets the two equal at most branches, so
        // a tenant who does not price holidays differently should not have to say so twice.
        val hall: ResourcePool = fixture.pool(currentTenant.get())

        given().contentType("application/x-www-form-urlencoded")
            .formParam("mode", "calendar")
            .formParam("monWed", "25000")
            .formParam("thuFri", "30000")
            .formParam("satSun", "35000")
            .redirects().follow(false)
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/rate")
            .then().statusCode(303)

        val plan: RatePlan = catalogService.getRatePlan(hall.resourcePoolId).orElseThrow()
        assertEquals(0, BigDecimal("35000").compareTo(plan.amountFor(RatePlan.Band.HOLIDAY)!!))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theHolidayListRendersAddsAndRemoves() {
        val date = LocalDate.of(2098, 4, 21)

        given().contentType("application/x-www-form-urlencoded")
            .formParam("holidayDate", date.toString())
            .formParam("name", "Hari Kartini (test)")
            .redirects().follow(false)
            .`when`().post("/admin/holidays")
            .then().statusCode(303)

        given().`when`().get("/admin/holidays")
            .then().statusCode(200)
            // The weekday is shown deliberately: the band also covers the day BEFORE this
            // date, so staff need to be able to see which day that is.
            .body(containsString("Mon 21 Apr 2098"))
            .body(containsString("Hari Kartini (test)"))

        val added = catalogService.listHolidays().stream()
            .filter { h -> h.holidayDate == date }.findFirst().orElseThrow()

        given().contentType("application/x-www-form-urlencoded")
            .redirects().follow(false)
            .`when`().post("/admin/holidays/" + added.publicHolidayId + "/delete")
            .then().statusCode(303)

        assertTrue(catalogService.listHolidays().stream().noneMatch { h -> h.holidayDate == date })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aMistypedHolidayDateIsAnsweredOnThePageRatherThanAsAServerError() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("holidayDate", "21-04-2098")
            .formParam("name", "Hari Kartini")
            .redirects().follow(false)
            .`when`().post("/admin/holidays")
            .then().statusCode(400)
            .body(containsString("is not a date"))
    }

    @Test
    fun theSeatMapNamesTheBandItIsChargingAndWhatTheTicketIncludes() {
        // A price that changes between Tuesday and Sunday with no explanation reads as a
        // mistake -- or as a price that moves while you are looking at it.
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Kudus", "Kudus")
        val hall: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 1")
        fixture.unit(tenant, hall, "S1", "S", 1)
        // Sunday 7 June 2099 at 19:00 WIB.
        val showing: ResourcePoolSession = fixture.sessionAt(tenant, hall,
            ZonedDateTime.of(LocalDate.of(2099, 6, 7), LocalTime.of(19, 0), WIB).toInstant(), "on_sale")
        fixture.calendarRateRule(tenant, hall, BigDecimal("25000"), BigDecimal("30000"),
            BigDecimal("35000"), BigDecimal("40000"),
            java.util.List.of("Termasuk voucher minuman Rp 10.000"))

        given().`when`().get("/halls/" + hall.resourcePoolId + "/showings/"
                + showing.resourcePoolSessionId)
            .then().statusCode(200)
            .body(containsString("Rp 35.000 per seat"))
            .body(containsString("Sat-Sun"))
            .body(containsString("Termasuk voucher minuman Rp 10.000"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBranchWithSeveralHallsOffersTheApplyToTheWholeBranchAction() {
        // A.7b obs. 5. Offered only where it means something: a hall standing on its own has no
        // siblings to fan out to, and a checkbox that does nothing is worse than no checkbox.
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Pati", "Pati")
        val studio1: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 1")
        fixture.poolAtSite(tenant, site, "Studio 2")

        given().`when`().get("/admin/halls/" + studio1.resourcePoolId)
            .then().statusCode(200)
            .body(containsString("Apply to all 2 halls at Demo Cinema Pati"))

        val lone: ResourcePool = fixture.pool(tenant)
        val body = given().`when`().get("/admin/halls/" + lone.resourcePoolId)
            .then().statusCode(200).extract().asString()
        assertFalse(body.contains("Apply to all"),
            "a hall with no branch has nothing to fan out to")
    }
}
