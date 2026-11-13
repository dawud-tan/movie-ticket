package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ConsentRecord
import com.protek.booking.domain.Customer
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.i18n.Language
import com.protek.booking.repository.ConsentRecordRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The age attestation: the LSF age classification, enforced at hold time.
 *
 * What this feature is worth is narrow and worth stating: it converts two columns that
 * promised a control nobody implemented — `customer.date_of_birth_attested` since V1 and
 * `film.classification` since the film table — into one that runs. It is not identity verification,
 * and the tests below do not pretend otherwise; enforcement at the door stays a staff function.
 *
 * **The test that matters most is the one about the second film.** A single boolean cannot
 * express three thresholds, so an implementation that recorded "attested" and then trusted it
 * would let somebody who confirmed they were 13 walk into a 21+ film. That is a worse state than
 * the dead column, because it would look like a working control.
 *
 * The `booking.email` attribute rather than `email` is the harness fact
 * `DailyPurchaseCapTest` records: `@TestSecurity` bypasses the augmentor, and
 * `CurrentActor.email()` reads the augmentor's real key — set the wrong one and every hold
 * mints a fresh customer, which would make the second-film test pass while proving nothing.
 */
@QuarkusTest
class AgeAttestationTest {

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var consentRepo: ConsentRecordRepository
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var db: Db

    /** A showing of a film with the given rating, in a hall of its own with three seats. */
    private data class Showing(val hall: ResourcePool, val session: ResourcePoolSession, val seats: List<ResourceUnit>) {
        fun seatIds(count: Int): List<UUID?> =
            seats.subList(0, count).stream().map(ResourceUnit::resourceUnitId).toList()
    }

    /** A showing of a film carrying `classification` — which may be null, for the film
     * whose rating nobody has recorded. */
    private fun showingOfFilmRated(classification: String?, row: String): Showing {
        val tenant: Tenant = currentTenant.get()
        val hall = hall(row)
        val film: Film = fixture.film(tenant, "Film " + row + " " + UUID.randomUUID(), classification)
        return Showing(hall.pool, fixture.sessionOfFilm(tenant, hall.pool, film, "on_sale"),
            hall.seats)
    }

    /** A showing with no film at all — the label-only case the schema keeps first-class. */
    private fun showingWithNoFilm(row: String): Showing {
        val tenant: Tenant = currentTenant.get()
        val hall = hall(row)
        return Showing(hall.pool, fixture.session(tenant, hall.pool, "on_sale"), hall.seats)
    }

    private data class Hall(val pool: ResourcePool, val seats: List<ResourceUnit>)

    private fun hall(row: String): Hall {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = fixture.pool(tenant)
        val seats: List<ResourceUnit> = java.util.List.of(
            fixture.unit(tenant, pool, row + 1, row, 1),
            fixture.unit(tenant, pool, row + 2, row, 2),
            fixture.unit(tenant, pool, row + 3, row, 3))
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        return Hall(pool, seats)
    }

    private fun consentFor(customerId: UUID?): List<ConsentRecord> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> consentRepo.findByCustomer(c, tenantId, customerId) }
    }

    private fun customer(customerId: UUID?): Customer {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> customerRepo.findById(c, tenantId, customerId) }
            .orElseThrow()
    }

    @Test
    @TestSecurity(user = "a3refuse@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3refuse@demo-cinema.example")])
    fun aRestrictedFilmIsRefusedWithoutTheTick_andTakesNoSeatOffSale() {
        val showing = showingOfFilmRated("17+", "A")

        val refused = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(2),
            "Too Young", "+62811900001", AgeClassification.Attestation.NOT_TICKED)

        assertFalse(refused.success, "a 17+ film must not sell without the attestation")
        assertTrue(refused.failureReason!!.contains("17+"),
            "the refusal has to name the rating, or it explains nothing: " + refused.failureReason)
        assertNull(refused.failedResourceUnitId,
            "no particular seat blocked this — pointing at one would send the customer off to "
                + "pick a different seat, which fixes nothing")

        // The failure that would make the control worse than not having it: refusing after
        // grabbing part of the request. The check runs before the insert loop, so there is
        // nothing to unwind.
        val map: List<SeatMapEntry> = catalogService.getSeatMap(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId)
        assertTrue(map.stream().allMatch { s -> "available" == s.seatStatus },
            "every seat must still be available after a refused hold: "
                + map.stream().filter { s -> "available" != s.seatStatus }.toList())
    }

    @Test
    @TestSecurity(user = "a3tick@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3tick@demo-cinema.example")])
    fun tickingItSells_andFinallyWritesTheColumnV1HasBeenPromisingSinceTheBeginning() {
        val showing = showingOfFilmRated("17+", "B")

        val held = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1),
            "Old Enough", "+62811900002", AgeClassification.Attestation.TICKED)

        assertTrue(held.success, "the attested hold should succeed: " + held.failureReason)
        assertTrue(customer(held.group!!.customerId).dateOfBirthAttested,
            "customer.date_of_birth_attested existed since V1 with nothing writing it; the age attestation is "
                + "the writer")
    }

    @Test
    @TestSecurity(user = "a3consent@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3consent@demo-cinema.example")])
    fun theConsentRowNamesTheRatingAndHashesTheExactSentenceTheCustomerWasShown() {
        val showing = showingOfFilmRated("21+", "C")

        val held = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1),
            "Old Enough", "+62811900003", AgeClassification.Attestation.TICKED)
        assertTrue(held.success, held.failureReason)

        val consent = consentFor(held.group!!.customerId)
        assertEquals(1, consent.size, "one attestation, one row")
        assertEquals("age_attestation:21+:en", consent[0].purpose,
            "the row has to say what was asserted AND in which language; a hash alone is "
                + "unreadable to the one person who would ever consult it, and since "
                + "2026-09-17 two sentences sit behind one rating")
        assertEquals(AgeClassification.LEGAL_BASIS, consent[0].legalBasis)
        assertNotNull(consent[0].consentGivenAt)
        // The hash is of the sentence the seat map renders, which is why the template asks the
        // server for it rather than writing its own copy: two sources and the hash stops
        // describing what anybody actually read.
        assertEquals(AgeClassification.hashOf(AgeClassification.gateFor("21+", Language.ENGLISH)!!.statement),
            consent[0].policyTextHash)
    }

    @Test
    @TestSecurity(user = "a3ladder@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3ladder@demo-cinema.example")])
    fun attestingForA13PlusFilmDoesNotGetYouIntoA21PlusOne() {
        // The reason date_of_birth_attested is a record and never a permission. One boolean,
        // three thresholds: anything that read the flag to skip the second prompt would let a
        // 13-year-old's attestation clear a 21+ film, and would look like a working control
        // while doing it.
        val thirteen = showingOfFilmRated("13+", "D")
        val first = bookingService.hold(thirteen.hall.resourcePoolId,
            thirteen.session.resourcePoolSessionId, thirteen.seatIds(1),
            "Same Person", "+62811900004", AgeClassification.Attestation.TICKED)
        assertTrue(first.success, first.failureReason)
        assertTrue(customer(first.group!!.customerId).dateOfBirthAttested,
            "precondition: the flag is now set for this customer")

        val twentyOne = showingOfFilmRated("21+", "E")
        val refused = bookingService.hold(twentyOne.hall.resourcePoolId,
            twentyOne.session.resourcePoolSessionId, twentyOne.seatIds(1),
            "Same Person", "+62811900004", AgeClassification.Attestation.NOT_TICKED)
        assertFalse(refused.success,
            "an attestation against 13+ says nothing about 21+ — it must ask again")
        assertTrue(refused.failureReason!!.contains("21+"), refused.failureReason)

        // A refused hold has no group to read a customer id from, so the same seat is taken again
        // WITH the tick: that it succeeds against the same customer row is what proves the
        // refusal above was about the rating rather than about the test having two customers.
        val attested = bookingService.hold(twentyOne.hall.resourcePoolId,
            twentyOne.session.resourcePoolSessionId, twentyOne.seatIds(1),
            "Same Person", "+62811900004", AgeClassification.Attestation.TICKED)
        assertTrue(attested.success, attested.failureReason)
        assertEquals(first.group.customerId, attested.group!!.customerId,
            "all three holds must belong to ONE customer, or this test proves nothing")

        val purposes = consentFor(first.group.customerId).stream()
            .map(ConsentRecord::purpose).sorted().toList()
        assertEquals(java.util.List.of("age_attestation:13+:en", "age_attestation:21+:en"), purposes,
            "each attestation is its own row: what was asserted, in which language, and when")
    }

    @Test
    @TestSecurity(user = "a3su@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3su@demo-cinema.example")])
    fun anSuFilmAsksNothing_becauseSemuaUmurIsTheRatingThatGatesNobody() {
        val showing = showingOfFilmRated("SU", "F")

        val held = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1),
            "Family", "+62811900005", AgeClassification.Attestation.NOT_TICKED)

        assertTrue(held.success, "SU must not be gated: " + held.failureReason)
        assertFalse(customer(held.group!!.customerId).dateOfBirthAttested,
            "nothing was attested to, so nothing may be recorded as attested")
        assertTrue(consentFor(held.group.customerId).isEmpty())
    }

    @Test
    @TestSecurity(user = "a3none@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3none@demo-cinema.example")])
    fun aShowingWithNoFilmSellsExactlyAsItDidBeforeA3() {
        // The schema is vertical-neutral: a label-only session is a first-class case,
        // and it is what most fixtures are. A gate that refused one would have broken
        // the majority of the demo to enforce a rating nothing had recorded.
        val showing = showingWithNoFilm("G")

        val held = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1),
            "No Film Here", "+62811900006", AgeClassification.Attestation.NOT_TICKED)

        assertTrue(held.success, held.failureReason)
        assertTrue(consentFor(held.group!!.customerId).isEmpty())
    }

    @Test
    @TestSecurity(user = "a3unrated@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "a3unrated@demo-cinema.example")])
    fun aFilmWithNoRatingRecordedSellsToo_whichIsWhyTheColumnStaysNullable() {
        // the film table left film.classification nullable precisely so it would not claim this control
        // before it existed. Now that it exists, an unrated film still sells: the column records
        // a rating where the operator knows one, and its absence is not a control failure.
        val showing = showingOfFilmRated(null, "H")

        val held = bookingService.hold(showing.hall.resourcePoolId,
            showing.session.resourcePoolSessionId, showing.seatIds(1),
            "Unrated", "+62811900007", AgeClassification.Attestation.NOT_TICKED)

        assertTrue(held.success, held.failureReason)
        assertTrue(consentFor(held.group!!.customerId).isEmpty())
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theCounterIsNotGated_becauseNobodyMayAttestOnAWalkInsBehalf() {
        // Every walk-in in the building shares one customer row, so a tick at the desk would
        // write "attested" onto a row that is not a person and carry that claim into every later
        // counter sale. The control the counter has is the better one: the customer is standing
        // in front of staff, which is where the age attestation puts enforcement anyway.
        val showing = showingOfFilmRated("21+", "J")

        val held = bookingService.holdForCounterSale(
            showing.hall.resourcePoolId, showing.session.resourcePoolSessionId,
            showing.seatIds(1))

        assertTrue(held.success, "a counter sale of a 21+ film must still go through: "
            + held.failureReason)
        val walkIn = customer(held.group!!.customerId)
        assertTrue(walkIn.walkInPlaceholder, "precondition: the counter attaches to the placeholder")
        assertFalse(walkIn.dateOfBirthAttested,
            "the placeholder must never be marked as having attested to anything")
        assertTrue(consentFor(held.group.customerId).isEmpty(),
            "no consent row may be written in the name of a row that is not a person")
    }

    @Test
    fun theSeatMapShowsTheRatingAndAsksForTheTick_inTheCustomersOwnWords() {
        // Public and anonymous, like the rest of the browse path: the rating is something a
        // parent reads BEFORE spending a minute ticking seats, not after signing in.
        val showing = showingOfFilmRated("17+", "K")

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body(containsString("Age classification"))
            .body(containsString("17+"))
            .body(containsString("name=\"ageAttested\""))
            // The exact sentence, from the same method BookingService hashes. A copy of the
            // wording in the template would leave consent_record.policy_text_hash describing
            // something nobody read.
            .body(containsString(AgeClassification.gateFor("17+", Language.ENGLISH)!!.statement))
            // Said out loud rather than implied: the tick is not admission.
            .body(containsString("Staff check at the door"))
    }

    @Test
    fun anSuSeatMapNamesTheRatingAndAsksNothing() {
        val showing = showingOfFilmRated("SU", "L")

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            // Shown even though it gates nothing: "SU" and "no rating recorded" are different
            // facts, and a badge that appeared only for restricted films would conflate them.
            .body(containsString("SU"))
            .body(not(containsString("name=\"ageAttested\"")))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theCounterPickerShowsTheRatingWithNothingToTick() {
        val showing = showingOfFilmRated("21+", "M")

        given().`when`().get("/admin/counter/showings/" + showing.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body(containsString("21+"))
            .body(containsString("check ID before selling"))
            .body(not(containsString("name=\"ageAttested\"")))
    }

    @Test
    fun anUnrecognisedRatingGatesRatherThanWavingThrough() {
        // Unreachable through the CHECK constraint, which permits only the four LSF values —
        // asserted here because the failure DIRECTION is what matters: a default that treated an
        // unknown rating as unrestricted would sell a restricted film with no attestation and no
        // sign of it, while asking costs one tick.
        val gate = AgeClassification.gateFor("18+", Language.ENGLISH)!!

        assertTrue(gate.restricted())
        assertEquals("18+", gate.classification)
        assertTrue(gate.statement!!.contains("18+"),
            "with no age to name, the sentence has to fall back to the rating itself: "
                + gate.statement)
    }

    @Test
    fun theSentenceNamesTheAge_becauseTheHashIsWorthlessIfNobodyCanSayWhatWasHashed() {
        assertEquals("I confirm that I am 17 years of age or older.",
            AgeClassification.gateFor("17+", Language.ENGLISH)!!.statement)
        assertNull(AgeClassification.gateFor("SU", Language.ENGLISH)!!.statement)
        assertNull(AgeClassification.gateFor(null, Language.ENGLISH))
        assertNull(AgeClassification.gateFor("  ", Language.ENGLISH))
        // Same sentence, same hash, every time — the property the consent row rests on.
        assertEquals(AgeClassification.hashOf("I confirm that I am 17 years of age or older."),
            AgeClassification.hashOf(AgeClassification.gateFor("17+", Language.ENGLISH)!!.statement))
        assertNotEquals(AgeClassification.hashOf(AgeClassification.gateFor("17+", Language.ENGLISH)!!.statement),
            AgeClassification.hashOf(AgeClassification.gateFor("21+", Language.ENGLISH)!!.statement))
    }

    @Test
    fun anIndonesianAttestationHashesTheIndonesianSentence_andSaysSoInThePurpose() {
        // The decision this pins (2026-09-17, AgeClassification's javadoc has it): one
        // classification no longer has one hash, and that is correct rather than a regression.
        // The column records what the person actually read; hashing an English sentence a
        // customer never saw would record an agreement that did not happen.
        val english = AgeClassification.gateFor("17+", Language.ENGLISH)!!
        val indonesian = AgeClassification.gateFor("17+", Language.INDONESIAN)!!

        assertEquals("I confirm that I am 17 years of age or older.", english.statement)
        assertEquals("Saya menyatakan bahwa usia saya 17 tahun atau lebih.", indonesian.statement)
        assertNotEquals(AgeClassification.hashOf(english.statement),
            AgeClassification.hashOf(indonesian.statement),
            "two sentences, two hashes — which is the whole reason the purpose carries a tag")

        // And the tag is what keeps a hash resolvable back to a sentence. Without it a row
        // reading age_attestation:17+ and some hex is evidence of nothing: it cannot be matched
        // against either sentence without guessing which language was on screen.
        assertEquals("age_attestation:17+:en", english.consentPurpose())
        assertEquals("age_attestation:17+:id", indonesian.consentPurpose())
    }

    @Test
    fun theGateCannotBeMadeWithASentenceInOneLanguageAndATagForAnother() {
        // gateFor takes a Language and not a Msg, and this is what that buys. A bundle
        // parameter would let a caller hand in the Indonesian messages while the tag said "en",
        // writing a consent row that lies about what was read — and nothing downstream could
        // ever detect it, because the hash would match a sentence nobody was shown.
        for (language in Language.all()) {
            val gate = AgeClassification.gateFor("21+", language)!!
            assertEquals(language.tag(), gate.languageTag)
            assertEquals(gate.statement,
                AgeClassification.gateFor("21+", Language.ofTag(gate.languageTag).orElseThrow())!!
                    .statement,
                "the tag on the row has to reproduce the sentence beside it")
        }
    }

    @Test
    fun aGateMadeWithNoLanguageFallsBackToEnglishRatherThanFailing() {
        // Reachable off a request thread — LanguagePreference.current() answers DEFAULT there —
        // and the failure direction is what matters: a null here must not become a null
        // statement, which restricted() would read as "this film gates nothing".
        val gate = AgeClassification.gateFor("21+", null)!!

        assertTrue(gate.restricted())
        assertEquals(Language.DEFAULT.tag(), gate.languageTag)
    }
}
