package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.i18n.Language
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The age advisory: what a self-declared date of birth says about a film's LSF rating.
 *
 * **The test that carries this whole feature is
 * [theWarningDoesNotBlockTheSale_butTheMissingTickStillDoes].** the age attestation is
 * the control and it refuses; this is a warning on top of it, and the two are easy to collapse
 * into one by accident. An implementation that quietly started refusing on the date of birth
 * would look like a stronger control and would in fact be a worse one — the date is
 * self-declared, nobody checked it, and a customer who mistyped their birth year would be
 * unable to buy a ticket they are entitled to.
 *
 * **The second most valuable is [theAgeIsTakenOnTheShowingsDateAndNotTodays].** A
 * customer who is 16 today and 17 by the showing meets the rating at the door, which is the only
 * place it is enforced. Warning them anyway would be wrong on a date they can check for
 * themselves, and a warning a customer has caught being wrong is one they will dismiss next time.
 */
@QuarkusTest
class AgeAdvisoryTest {

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var profileService: CustomerProfileService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var db: Db

    private data class Showing(val hall: ResourcePool, val session: ResourcePoolSession, val seats: List<ResourceUnit>) {
        fun seatIds(from: Int, to: Int): List<UUID?> =
            seats.subList(from, to).stream().map(ResourceUnit::resourceUnitId).toList()
    }

    /** A showing of a film with the given rating, starting `daysFromNow` from today. */
    private fun showingRated(classification: String, row: String, daysFromNow: Int): Showing {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        val seats: List<ResourceUnit> = java.util.List.of(
            fixture.unit(tenant, pool, row + 1, row, 1),
            fixture.unit(tenant, pool, row + 2, row, 2),
            fixture.unit(tenant, pool, row + 3, row, 3))
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        val film: Film = fixture.film(tenant, "Film " + row + " " + UUID.randomUUID(), classification)
        val session: ResourcePoolSession = fixture.sessionOfFilmAt(tenant, pool, film,
            Instant.now().plus(Duration.ofDays(daysFromNow.toLong())), "on_sale")
        return Showing(pool, session, seats)
    }

    private fun storeDateOfBirth(customerId: UUID?, dateOfBirth: LocalDate?) {
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c -> customerRepo.setDateOfBirth(c, tenantId, customerId, dateOfBirth) }
    }

    private fun customerByEmail(email: String): Customer {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> customerRepo.findByEmail(c, tenantId, email) }
            .orElseThrow()
    }

    // --- What the advisory says, with no database in the way -----------------------------

    @Test
    fun aCustomerBelowTheRatingIsWarned_andTheWarningNamesBothNumbers() {
        val showDate = LocalDate.of(2026, 9, 1)
        val advisory = AgeClassification.advisoryFor(
            AgeClassification.gateFor("17+", Language.ENGLISH), LocalDate.of(2011, 3, 14), showDate, Language.ENGLISH)

        assertNotNull(advisory, "a 15-year-old at a 17+ film is the case this exists for")
        assertEquals(15, advisory!!.ageAtShowtime)
        assertEquals(17, advisory.minimumAge)
        // Both numbers, because "you may be too young" without them reads as a guess about the
        // customer rather than arithmetic on what they told us.
        assertTrue(advisory.message!!.contains("15"), advisory.message)
        assertTrue(advisory.message.contains("17+"), advisory.message)
        // And it has to say the sale is still available, or it is indistinguishable from a
        // refusal to the person reading it.
        assertTrue(advisory.message.contains("still book"), advisory.message)
    }

    @Test
    fun aCustomerWhoMeetsTheRatingIsToldNothing() {
        assertNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
            LocalDate.of(2000, 1, 1), LocalDate.of(2026, 9, 1), Language.ENGLISH))
    }

    @Test
    fun exactlyTheMinimumAgeIsOldEnough_becauseTheRatingSaysOrOlder() {
        val showDate = LocalDate.of(2026, 9, 1)
        // Seventeen to the day. The sentence the age attestation shows reads "17 years of age or older", so the
        // boundary belongs on the permitted side of the comparison.
        assertNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
            showDate.minusYears(17), showDate, Language.ENGLISH))
        assertNotNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
            showDate.minusYears(17).plusDays(1), showDate, Language.ENGLISH))
    }

    @Test
    fun theAgeIsTakenOnTheShowingsDateAndNotTodays() {
        val today = LocalDate.now()
        val showDate = today.plusDays(30)
        // Sixteen today, seventeen ten days from now — so seventeen at the showing, and the door
        // will admit them. Warning this customer would be wrong on a date they can check.
        val turnsSeventeenSoon = today.minusYears(17).plusDays(10)

        assertNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
                turnsSeventeenSoon, showDate, Language.ENGLISH),
            "a customer who will have had their birthday by the showing meets the rating")
        assertNotNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
                turnsSeventeenSoon, today, Language.ENGLISH),
            "the same customer at a showing today does not, which is what makes the date matter")
    }

    @Test
    fun aBirthdayThatFallsAfterTheShowingStillWarns() {
        val today = LocalDate.now()
        assertNotNull(AgeClassification.advisoryFor(AgeClassification.gateFor("17+", Language.ENGLISH),
            today.minusYears(17).plusDays(60), today.plusDays(30), Language.ENGLISH))
    }

    @Test
    fun noDateOfBirthMeansNoAdvisory_whichIsTheStateEveryAccountStartsIn() {
        assertNull(AgeClassification.advisoryFor(AgeClassification.gateFor("21+", Language.ENGLISH), null,
            LocalDate.of(2026, 9, 1), Language.ENGLISH))
    }

    @Test
    fun anSuFilmWarnsNobody_becauseSemuaUmurGatesNobody() {
        assertNull(AgeClassification.advisoryFor(AgeClassification.gateFor("SU", Language.ENGLISH),
            LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 1), Language.ENGLISH))
    }

    @Test
    fun aFilmWithNoRatingRecordedWarnsNobodyEither() {
        assertNull(AgeClassification.gateFor(null, Language.ENGLISH))
        assertNull(AgeClassification.advisoryFor(null, LocalDate.of(2020, 1, 1),
            LocalDate.of(2026, 9, 1), Language.ENGLISH))
    }

    @Test
    fun anUnrecognisedRatingWarnsAboutNothing_thoughItStillGates() {
        val gate = AgeClassification.gateFor("18+", Language.ENGLISH)

        // gateFor still asks for a tick, because failing towards asking costs one tick and
        // failing the other way sells a restricted film with no attestation at all.
        assertTrue(gate!!.restricted(), "an unrecognised rating must still gate")
        // But a warning would have to invent the threshold it compares against, and a number
        // this application made up is worse than silence.
        assertNull(AgeClassification.advisoryFor(gate, LocalDate.of(2015, 1, 1),
            LocalDate.of(2026, 9, 1), Language.ENGLISH))
    }

    // --- What the seat map renders --------------------------------------------------------

    @Test
    @TestSecurity(user = "young@example.test", roles = ["customer"])
    fun theSeatMapWarnsAnUnderageCustomer_andStillAsksForTheTick() {
        val showing = showingRated("17+", "P", 1)
        val customer = fixture.customer(currentTenant.get())
        storeDateOfBirth(customer.customerId, LocalDate.now().minusYears(15))
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body(containsString("you will be 15"))
            .body(containsString("17+"))
            // The advisory is additive. Removing the tick would be the regression this
            // assertion exists to catch.
            .body(containsString("name=\"ageAttested\""))
            .body(containsString(AgeClassification.gateFor("17+", Language.ENGLISH)!!.statement))
    }

    @Test
    @TestSecurity(user = "unknown@example.test", roles = ["customer"])
    fun theSeatMapSaysNothingToACustomerWhoHasGivenNoDateOfBirth() {
        val showing = showingRated("17+", "Q", 1)
        val customer = fixture.customer(currentTenant.get())
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body(not(containsString("Your profile says")))
            // Which is the state of every deployment today, so the tick has to be there
            // regardless — it is the control, and this is the case where it is the only one.
            .body(containsString("name=\"ageAttested\""))
    }

    @Test
    @TestSecurity(user = "oldenough@example.test", roles = ["customer"])
    fun aCustomerOldEnoughSeesTheRatingAndNoWarning() {
        val showing = showingRated("17+", "R", 1)
        val customer = fixture.customer(currentTenant.get())
        storeDateOfBirth(customer.customerId, LocalDate.now().minusYears(30))
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body(containsString("17+"))
            .body(not(containsString("Your profile says")))
    }

    // --- The line between warning and refusing --------------------------------------------

    @Test
    @TestSecurity(user = "advisory-sale@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email",
            value = "advisory-sale@demo-cinema.example")])
    fun theWarningDoesNotBlockTheSale_butTheMissingTickStillDoes() {
        val showing = showingRated("17+", "S", 1)

        // The first hold creates the customer row this test then ages down.
        val first = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(0, 1),
            "Advisory Sale", "+62811900301", AgeClassification.Attestation.TICKED)
        assertTrue(first.success, "the attested hold should succeed: " + first.failureReason)

        val customer = customerByEmail("advisory-sale@demo-cinema.example")
        storeDateOfBirth(customer.customerId, LocalDate.now().minusYears(10))

        // Ten years old, on file, against a 17+ film — and it still sells, because the date is
        // self-declared and the age attestation's tick is what the sale turns on.
        val sold = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1, 2),
            "Advisory Sale", "+62811900301", AgeClassification.Attestation.TICKED)
        assertTrue(sold.success,
            "a stated age must warn and never refuse — nobody verified it: " + sold.failureReason)

        // And the control that does refuse is untouched by any of this.
        val refused = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(2, 3),
            "Advisory Sale", "+62811900301", AgeClassification.Attestation.NOT_TICKED)
        assertFalse(refused.success, "the missing tick still refuses")
        assertTrue(refused.failureReason!!.contains("17+"), refused.failureReason)

        val map: List<SeatMapEntry> = catalogService.getSeatMap(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId)
        // 1L: count() is a long, and a bare 1 would fall to assertEquals(Object, Object).
        assertEquals(1L, map.stream().filter { s -> "available" == s.seatStatus }.count(),
            "two of three seats sold and the refused one stayed on sale")
    }

    // --- The profile field that fills the column ------------------------------------------

    @Test
    @TestSecurity(user = "dob@example.test", roles = ["customer"])
    fun theProfileFormStoresADateOfBirth_andTheAgeAppears() {
        val customer = fixture.customer(currentTenant.get())
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

        given().redirects().follow(false)
            .formParam("dateOfBirth", LocalDate.now().minusYears(22).toString())
            .`when`().post("/profile")
            .then().statusCode(303)

        // Post/redirect/get: the value is on the row, not merely in the response that wrote it.
        assertEquals(22, profileService.current().age())
        given().`when`().get("/profile").then().statusCode(200).body(containsString("22"))
    }

    @Test
    @TestSecurity(user = "future@example.test", roles = ["customer"])
    fun aDateInTheFutureIsRefused_andNothingIsStored() {
        val customer = fixture.customer(currentTenant.get())
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)

        given().redirects().follow(false)
            .formParam("dateOfBirth", LocalDate.now().plusDays(1).toString())
            .`when`().post("/profile")
            .then().statusCode(200)
            .body(containsString("in the future"))

        assertFalse(profileService.current().hasAge(),
            "a refused date must not reach the column")
    }

    @Test
    @TestSecurity(user = "clear@example.test", roles = ["customer"])
    fun submittingTheFieldBlankClearsIt_becauseTellingUsIsOptional() {
        val customer = fixture.customer(currentTenant.get())
        storeDateOfBirth(customer.customerId, LocalDate.now().minusYears(40))
        QuarkusMock.installMockForType(ActingAs.customer(customer.customerId), CurrentActor::class.java)
        assertTrue(profileService.current().hasAge())

        given().redirects().follow(false).formParam("dateOfBirth", "")
            .`when`().post("/profile").then().statusCode(303)

        assertFalse(profileService.current().hasAge(),
            "a customer who withdraws the fact gets it removed, not remembered")
    }

    @Test
    @TestSecurity(user = "newcomer-dob@example.test", roles = ["customer"])
    fun someoneWhoHasNeverBookedCanStillRecordOne_soTheirFirstBookingIsTheOneItWarnsOn() {
        // customer rows are created by the hold, so without this the advisory would be silent on
        // exactly the booking it is most worth showing on.
        QuarkusMock.installMockForType(ActingAs.customer(null), CurrentActor::class.java)

        assertNull(profileService.updateDateOfBirth(LocalDate.now().minusYears(19)),
            "recording a date of birth before the first booking must not fail")
    }
}
