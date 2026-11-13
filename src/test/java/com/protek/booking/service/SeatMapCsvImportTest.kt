package com.protek.booking.service

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * CSV seat-map import against a real database and through the real upload form
 * (deferred until a tenant arrived with a file).
 *
 * `SeatMapCsvTest` covers the file formats; this covers what the file becomes. The
 * two that matter are at the end: an imported hall books through the same untouched
 * `EXCLUDE` path a generated one does, and the upload is two steps with the file
 * carried between them, so confirming does not mean choosing the file again.
 */
@QuarkusTest
class SeatMapCsvImportTest {

    companion object {
        /** Matches `TestFixture.pool`, because a studio column in the file is matched against
         * the hall's own name — that is what makes a branch-wide sheet importable one hall at a time. */
        private const val HALL_NAME = "Test Hall"
    }

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    private fun hall(): ResourcePool = fixture.pool(currentTenant.get())

    // The two files below are unindented raw strings rather than trimIndent()ed ones, which
    // would drop the Java text blocks' final newline.
    @Test
    fun aStudiosOwnFileBecomesItsSeatsUnderTheNamesItAlreadyUses() {
        val hall = hall()

        val created = catalogService.importSeatsFromCsv(hall.resourcePoolId, """Studio,Row,Seat No,Class
Test Hall,A,1,Regular
Test Hall,A,2,Regular
Test Hall,B,01,VIP
Studio 2,A,1,Regular
""")

        assertEquals(3, created, "the fourth line is another studio's and must stay there")
        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(listOf("A1", "A2", "B01"), seats.map { it.label })
        // "B01" is what is painted on the seat; 1 is what the map orders by.
        assertEquals(1, seats[2].sequenceNo)
        assertEquals("B", seats[2].groupKey)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aWheelchairSpaceFromTheFileIsMarkedOnEverySeatMapAndAnyoneMayHoldIt() {
        // Stored in resource_unit.attributes, marked only.
        val tenant = currentTenant.get()
        val hall = hall()
        catalogService.importSeatsFromCsv(hall.resourcePoolId, "row,seat,kursi roda\nW,1,ya\nW,2,\n")
        assertEquals(listOf(true, false), catalogService.listSeats(hall.resourcePoolId).map { it.accessible })

        // A relayout from the generator says nothing about the mark, so it stays.
        catalogService.applyRelayout(hall.resourcePoolId, SeatMapPlan.parse("W: 1-2"))
        assertEquals(listOf(true, false), catalogService.listSeats(hall.resourcePoolId).map { it.accessible })

        val showing = fixture.session(tenant, hall, "on_sale")
        fixture.rateRule(tenant, hall, java.math.BigDecimal("50000"))
        val seatPath = "/halls/" + hall.resourcePoolId + "/showings/" + showing.resourcePoolSessionId
        given().`when`().get(seatPath).then().statusCode(200)
            .body(containsString("data-accessible=\"true\" title=\"W1 (Wheelchair space)\""))
            .body(containsString("data-accessible=\"false\" title=\"W2\""))
        given().`when`().get("/api/app$seatPath").then().statusCode(200)
            .body("rows[0].seats.accessible", org.hamcrest.Matchers.contains(true, false))

        val accessibleSeat = catalogService.listSeats(hall.resourcePoolId)[0].resourceUnitId
        assertTrue(bookingService.holdForCounterSale(hall.resourcePoolId, showing.resourcePoolSessionId,
            listOf(accessibleSeat)).success, "a mark, not a rule: no eligibility check")
    }

    @Test
    fun thePreviewSaysWhatTheFileLeftBehindBeforeAnythingIsWritten() {
        val hall = hall()

        val preview = catalogService.previewCsvSeats(hall.resourcePoolId, """Studio,Row,Seat,Class
Test Hall,A,1,Regular
Studio 2,A,1,Regular
""")

        assertEquals(1, preview.seatCount())
        assertTrue(preview.note!!.contains("Studio 2"), preview.note)
        assertTrue(preview.note.contains("Class"), preview.note)
        assertEquals(0, catalogService.listSeats(hall.resourcePoolId).size, "preview must not write")
    }

    @Test
    fun aFileThatWouldDuplicateAnExistingSeatIsRefusedAndCreatesNothing() {
        // Import is additive on exactly the same terms as the generator: reservation rows point
        // at resource_unit, so replacing a seat map is not a thing this can do quietly.
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        val e = assertThrows(IllegalArgumentException::class.java) {
            catalogService.importSeatsFromCsv(hall.resourcePoolId, "row,seat\nA,3\nB,1\nB,2")
        }

        assertTrue(e.message!!.contains("A3"), "message should name the clash: " + e.message)
        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(4, seats.size)
        assertTrue(seats.stream().noneMatch { it.groupKey == "B" },
            "row B had no conflict of its own and must still not exist")
    }

    @Test
    fun anImportedHallStillBooksThroughTheUnchangedExclusionConstraint() {
        // The load-bearing one. Import is a new way to write resource_unit rows; the
        // reservation spine must not be able to tell an imported seat from a typed one.
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)
        catalogService.importSeatsFromCsv(hall.resourcePoolId, "A,1,2,,7,8")
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        val showing: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")

        val seats: List<ResourceUnit> = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(listOf(1, 2, 7, 8), seats.map { it.sequenceNo },
            "the gap the file describes has to survive into the database")
        val acrossTheAisle = seats[2]

        val first = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(acrossTheAisle.resourceUnitId),
            "First Customer", "+628120000001",
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(first.success, "an imported seat must be bookable")

        val second = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(acrossTheAisle.resourceUnitId),
            "Second Customer", "+628120000002",
            AgeClassification.Attestation.NOT_TICKED)
        assertFalse(second.success, "the EXCLUDE constraint must still reject the double claim")
        assertEquals(acrossTheAisle.resourceUnitId, second.failedResourceUnitId)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun uploadingAFilePreviewsItAndConfirmingCreatesTheSeatsWithoutASecondUpload() {
        val hall = hall()
        val csv = "row;seat\nA;1\nA;2\nB;1\n"

        val preview = given()
            .multiPart("file", "denah-kursi.csv", csv.toByteArray(StandardCharsets.UTF_8), "text/csv")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/csv")
            .then().statusCode(200)
            .body(containsString("Import 3 seats"))
            .extract().asString()

        assertEquals(0, catalogService.listSeats(hall.resourcePoolId).size, "the preview must not write")
        // The file travels back inside the confirm form, so the operator picks it once.
        assertTrue(preview.contains("name=\"csv\""), "the preview must carry the file into the confirm step")

        given().redirects().follow(false)
            .formParam("csv", csv)
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/apply")
            .then().statusCode(303)

        assertEquals(listOf("A1", "A2", "B1"),
            catalogService.listSeats(hall.resourcePoolId).map { it.label })
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anUploadedFileCanAlsoBeARelayoutRatherThanAnAddition() {
        // The relayout checkbox rides the same multipart form as the file, so it is bound as a
        // part rather than a form parameter — worth its own test, because a silently unbound
        // "replace" would quietly do the additive thing to a hall the operator meant to re-seat.
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        given().multiPart("file", "denah-baru.csv", "row,seat\nA,1\nA,2\n".toByteArray(StandardCharsets.UTF_8),
            "text/csv")
            .multiPart("replace", "on")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/csv")
            .then().statusCode(200)
            .body(containsString("Are retired"))
            .body(containsString("A3"))

        assertEquals(4, catalogService.listSeats(hall.resourcePoolId).size, "the preview must not write")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aFileThatCannotBeReadIsAnsweredOnThePageRatherThanAsAStackTrace() {
        val hall = hall()

        given().multiPart("file", "kosong.csv", "row,seat\n".toByteArray(StandardCharsets.UTF_8), "text/csv")
            .`when`().post("/admin/halls/" + hall.resourcePoolId + "/seats/csv")
            .then().statusCode(200)
            .body(containsString("no seats"))
    }
}
