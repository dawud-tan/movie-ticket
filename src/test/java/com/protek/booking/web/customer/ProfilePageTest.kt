package com.protek.booking.web.customer

import com.protek.booking.domain.Customer
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.CustomerProfileService
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * `/profile` — name, email and age.
 *
 * **The assertion that carries the most weight is the negative one.** Google does not send
 * a `birthdate` claim for the scopes this application requests, so on this deployment the
 * age is genuinely unknown — and the tempting wrong answer is next door in the same table:
 * `customer.date_of_birth_attested`, the age attestation's boolean recording that somebody ticked "I am
 * old enough". One boolean cannot tell 13+ from 21+, so a page that rendered an age from it
 * would be stating something nobody ever asserted.
 */
@QuarkusTest
class ProfilePageTest {

    companion object {
        /** A [CurrentActor] carrying the claims a real Google sign-in would. `ActingAs`
         * deliberately answers empty for all of them, so a test about what the claims produce has to
         * supply them. */
        private fun withClaims(customerId: UUID?, name: String?, email: String?, born: LocalDate?): ActingAs =
            object : ActingAs(true, false, customerId, null) {
                override fun fullName(): Optional<String> = Optional.ofNullable(name)

                override fun email(): Optional<String> = Optional.ofNullable(email)

                override fun birthDate(): Optional<LocalDate> = Optional.ofNullable(born)
            }
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var profileService: CustomerProfileService

    @Test
    @TestSecurity(user = "sri@example.test", roles = ["customer"])
    fun theProfileShowsTheNameAndEmailTheAccountSignedInWith() {
        val customer: Customer = fixture.customer(currentTenant.get())
        QuarkusMock.installMockForType(
            withClaims(customer.customerId, "Sri Wahyuni", "sri@example.test", null),
            CurrentActor::class.java)

        given().`when`().get("/profile").then().statusCode(200)
            .body(containsString("Sri Wahyuni"))
            .body(containsString("sri@example.test"))
    }

    @Test
    @TestSecurity(user = "sri@example.test", roles = ["customer"])
    fun anUnknownAgeIsSaidOutLoudRatherThanGuessedFromTheAttestation() {
        // fixture.customer() sets date_of_birth_attested = true, which is precisely the row that
        // makes the wrong answer available.
        val attested: Customer = fixture.customer(currentTenant.get())
        assertTrue(attested.dateOfBirthAttested)
        QuarkusMock.installMockForType(
            withClaims(attested.customerId, "Sri Wahyuni", "sri@example.test", null),
            CurrentActor::class.java)

        given().`when`().get("/profile").then().statusCode(200)
            .body(containsString("Not provided"))
            .body(containsString("Google does not share a date of birth"))
            // Shown as what it is — a confirmation — and never as a number.
            .body(containsString("That is a confirmation, not a date of birth"))
    }

    @Test
    @TestSecurity(user = "sri@example.test", roles = ["customer"])
    fun aProviderThatDoesSendABirthdateGetsAnAgeInCompletedYears() {
        val customer: Customer = fixture.customer(currentTenant.get())
        val born = LocalDate.now().minusYears(30).minusDays(1)
        QuarkusMock.installMockForType(
            withClaims(customer.customerId, "Sri Wahyuni", "sri@example.test", born),
            CurrentActor::class.java)

        val profile: CustomerProfileService.Profile = profileService.current()

        assertTrue(profile.hasAge())
        assertEquals(30, profile.age())
        given().`when`().get("/profile").then().statusCode(200).body(containsString("30"))
    }

    @Test
    @TestSecurity(user = "sri@example.test", roles = ["customer"])
    fun anAgeIsCompletedYearsAndNotDaysDividedBy365() {
        val customer: Customer = fixture.customer(currentTenant.get())
        // The day before a 30th birthday. Calendar arithmetic says 29; dividing elapsed days by
        // 365.25 rounds to 30 — and the few days either side of a birthday are exactly the
        // window an age gate is decided in.
        val born = LocalDate.now().minusYears(30).plusDays(1)
        QuarkusMock.installMockForType(
            withClaims(customer.customerId, "Sri Wahyuni", "sri@example.test", born),
            CurrentActor::class.java)

        assertEquals(29, profileService.current().age())
    }

    @Test
    @TestSecurity(user = "sri@example.test", roles = ["customer"])
    fun aBirthdateFromTheTokenIsWrittenOntoTheCustomerRowSoItSurvivesTheSession() {
        val customer: Customer = fixture.customer(currentTenant.get())
        val born = LocalDate.of(1991, 3, 14)
        QuarkusMock.installMockForType(
            withClaims(customer.customerId, "Sri Wahyuni", "sri@example.test", born),
            CurrentActor::class.java)
        profileService.current()

        // Now with no claim at all, as if the provider stopped sending it or an operator is
        // looking at the row: the stored value is what the page falls back on.
        QuarkusMock.installMockForType(
            withClaims(customer.customerId, "Sri Wahyuni", "sri@example.test", null),
            CurrentActor::class.java)

        assertEquals(born, profileService.current().dateOfBirth)
    }

    @Test
    @TestSecurity(user = "newcomer@example.test", roles = ["customer"])
    fun someoneWhoHasNeverBookedStillHasAProfile() {
        // A customer row is created at hold time, so a visitor who has signed in and not yet
        // booked has none. That is a real state and not an error — the page renders from the
        // token alone.
        QuarkusMock.installMockForType(
            withClaims(null, "Baru Sekali", "newcomer@example.test", null), CurrentActor::class.java)

        val profile: CustomerProfileService.Profile = profileService.current()

        assertFalse(profile.hasBooked)
        assertEquals("Baru Sekali", profile.name)
        given().`when`().get("/profile").then().statusCode(200).body(containsString("Baru Sekali"))
    }

    @Test
    fun theProfileIsNotPublic() {
        val status = given().redirects().follow(false).`when`().get("/profile")
            .then().extract().statusCode()

        assertTrue(status == 401 || (status >= 300 && status < 400),
            "anonymous GET /profile returned " + status + "; expected 401 or a redirect to the IdP")
    }
}
