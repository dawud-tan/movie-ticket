package com.protek.booking.payment

import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * `/admin/payments/sales.csv`: the sales report a cinema files PBJT from, which nothing produced
 * before. Gross and refund owed side by side.
 */
@QuarkusTest
class SalesReportTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var paymentService: PaymentService

    @BeforeEach
    fun installStub() {
        QuarkusMock.installMockForType(StubMidtransGateway("test-server-key"), MidtransGateway::class.java)
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Jakarta"))

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aCashSaleTodayIsOneLineWithItsBranchCityAndGross() {
        val tenant = currentTenant.get()
        // A comma and a leading '=' in the branch name: the CSV must quote one and defuse the other.
        val name = "=Sales, Branch " + System.nanoTime()
        val site = fixture.site(tenant, name, "Harborview")
        val hall = fixture.poolAtSite(tenant, site, "Studio 1")
        val seats = listOf(fixture.unit(tenant, hall, "R1", "R", 1).resourceUnitId,
            fixture.unit(tenant, hall, "R2", "R", 2).resourceUnitId)
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        val showing = fixture.session(tenant, hall, "on_sale")
        val hold = bookingService.holdForCounterSale(hall.resourcePoolId, showing.resourcePoolSessionId, seats)
        assertTrue(hold.success, "setup hold failed: " + hold.failureReason)
        assertEquals(PaymentService.CashOutcome.SETTLED, paymentService.settleCash(hold.group!!.reservationGroupId!!).outcome)

        val csv = given().queryParam("from", today().minusDays(1).toString()).queryParam("to", today().plusDays(1).toString())
            .`when`().get("/admin/payments/sales.csv").then().statusCode(200)
            .header("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;"))
            .extract().asString()

        val lines = csv.split("\r\n")
        assertEquals("date,cinema,city,channel,payments,gross_idr,refund_owed_idr", lines[0])
        assertTrue(lines.contains(today().toString() + ",\"'" + name + "\",Harborview,cash,1,100000.00,0.00"),
            "no line for the sale in:\n" + csv)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aBackwardOrOverlongOrMalformedRangeIsRefused() {
        val t = today()
        given().queryParam("from", t.toString()).queryParam("to", t.minusDays(1).toString())
            .`when`().get("/admin/payments/sales.csv").then().statusCode(400)
        given().queryParam("from", t.minusDays(400).toString()).queryParam("to", t.toString())
            .`when`().get("/admin/payments/sales.csv").then().statusCode(400)
        given().queryParam("from", "1 Nov").queryParam("to", t.toString())
            .`when`().get("/admin/payments/sales.csv").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "someone@example.com", roles = ["customer"])
    fun aCustomerCannotDownloadIt() {
        given().queryParam("from", today().toString()).queryParam("to", today().toString())
            .`when`().get("/admin/payments/sales.csv").then().statusCode(403)
    }
}
