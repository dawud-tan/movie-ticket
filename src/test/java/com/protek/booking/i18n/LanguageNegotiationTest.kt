package com.protek.booking.i18n

import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Which language a request is answered in, end to end.
 *
 * The precedence under test is path, then cookie, then `Accept-Language`, then English
 * — and the two tests that matter are the ones where those disagree, because that is where a
 * reasonable-looking implementation gets it backwards. [LanguageTest] covers the same
 * rules without a server.
 *
 * Asserts on Indonesian copy from `messages/msg_id.properties` rather than on some
 * marker attribute, because a page that carries `lang="id"` and renders English is
 * exactly the failure this is for.
 */
@QuarkusTest
class LanguageNegotiationTest {

    companion object {
        /** The header's nav, in each language — the shortest proof that the bundle was consulted. */
        private const val CINEMAS_EN = ">Cinemas<"
        private const val CINEMAS_ID = ">Bioskop<"
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    /** "/" renders the two-menu page only for a tenant that has a branch; without one it falls
     * back to the flat hall list, which is a different template and not the one under test. */
    @BeforeEach
    fun aBranchExistsSoTheHomePageIsTheTwoMenuOne() {
        val tenant = currentTenant.get()
        fixture.site(tenant, "Language Demo " + UUID.randomUUID(), "Semarang")
    }

    // --- Nothing asked for --------------------------------------------------------------

    @Test
    fun aVisitorWhoAsksForNothingGetsEnglish() {
        given().get("/")
            .then().statusCode(200)
            .body(containsString("<html lang=\"en\">"))
            .body(containsString(CINEMAS_EN))
            // The switch names the language it would move you to, in that language.
            .body(containsString("Bahasa Indonesia"))
    }

    // --- Accept-Language ----------------------------------------------------------------

    @Test
    fun anIndonesianBrowserGetsIndonesian() {
        given().header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8").get("/")
            .then().statusCode(200)
            .body(containsString("<html lang=\"id\">"))
            .body(containsString(CINEMAS_ID))
            .body(containsString("Pesan tiket"))
            .body(not(containsString(CINEMAS_EN)))
    }

    @Test
    fun aLanguageThisSiteDoesNotPublishFallsBackRatherThanFailing() {
        given().header("Accept-Language", "ja").get("/")
            .then().statusCode(200).body(containsString(CINEMAS_EN))
    }

    // --- Where the inputs disagree -------------------------------------------------------

    @Test
    fun theCookieBeatsTheHeaderBecauseTheVisitorChoseItAndTheBrowserDidNot() {
        // The case this whole ordering exists for: Chrome on Android in this market very
        // commonly sends en-US whoever is holding the phone. A visitor who pressed the switch
        // has said something the header cannot say.
        given().header("Accept-Language", "en-US,en;q=0.9")
            .cookie(LanguagePreference.COOKIE, "id")
            .get("/")
            .then().statusCode(200).body(containsString(CINEMAS_ID))
    }

    @Test
    fun thePathBeatsTheCookieBecauseACrawlerHasNoCookie() {
        // If a cookie could override the URL, /id/ would serve English to the one visitor whose
        // opinion decides whether the Indonesian pages are indexed at all.
        given().cookie(LanguagePreference.COOKIE, "en").get("/id")
            .then().statusCode(200)
            .body(containsString("<html lang=\"id\">"))
            .body(containsString(CINEMAS_ID))
    }

    @Test
    fun anUnprefixedUrlStillFollowsTheCookieSoAPostLoginRedirectDoesNotChangeLanguage() {
        // /orders, the OIDC hops and every seeOther built in Java name a path with no prefix.
        given().cookie(LanguagePreference.COOKIE, "id").get("/")
            .then().statusCode(200).body(containsString(CINEMAS_ID))
    }

    @Test
    fun aCookieHoldingSomethingThatIsNotALanguageIsIgnored() {
        given().cookie(LanguagePreference.COOKIE, "klingon")
            .header("Accept-Language", "id")
            .get("/")
            .then().statusCode(200).body(containsString(CINEMAS_ID))
    }

    // --- The prefix routes to the same resource -------------------------------------------

    @Test
    fun theHomePageAnswersOnSlashIdWithAndWithoutTheTrailingSlash() {
        given().get("/id").then().statusCode(200).body(containsString(CINEMAS_ID))
        given().get("/id/").then().statusCode(200).body(containsString(CINEMAS_ID))
    }

    @Test
    fun aPrefixedPathReachesTheResourceItsUnprefixedFormReaches() {
        // movies.html is not translated yet; the point here is that the route matched at all
        // and that the header it renders inside is Indonesian.
        given().get("/id/movies").then().statusCode(200).body(containsString(CINEMAS_ID))
    }

    @Test
    fun aPathThatMerelyStartsWithTheLettersIsUntouched() {
        // Not 200: this tree has no /identity. Not 500 either — the filter must have left it
        // alone rather than rewritten it to /entity.
        given().get("/identity").then().statusCode(404)
    }

    @Test
    fun linksOnAPrefixedPageStayInsideThatLanguage() {
        val page = given().get("/id").then().statusCode(200).extract().body().asString()
        assertTrue(page.contains("href=\"/id/cinemas\""), "the nav's Bioskop link keeps the prefix")
        assertTrue(page.contains("href=\"/id/movies\""), "and so does Sedang tayang")
        // The authenticated paths deliberately do not — robots.txt disallows them by their
        // bare prefixes, and a second spelling would not be covered. See LanguagePreference.path.
        assertTrue(!page.contains("href=\"/id/orders\""), "authenticated links keep their bare form")
    }

    @Test
    fun anHtmlPageSaysWhatItVariesOnSoABrowserDoesNotReuseTheOtherLanguage() {
        given().get("/").then().statusCode(200).header("Vary", "Accept-Language, Cookie")
    }

    // --- The switch -----------------------------------------------------------------------

    @Test
    fun theSwitchRemembersTheChoiceAndMovesTheVisitorToTheUrlThatNamesIt() {
        given().redirects().follow(false)
            .formParam("lang", "id").formParam("next", "/movies")
            .post("/language")
            .then().statusCode(303)
            .header("Location", containsString("/id/movies"))
            .cookie(LanguagePreference.COOKIE, equalTo("id"))
    }

    @Test
    fun switchingBackTakesThePrefixOffRatherThanStackingASecondOne() {
        given().redirects().follow(false)
            .formParam("lang", "en").formParam("next", "/id/movies")
            .post("/language")
            .then().statusCode(303)
            .header("Location", containsString("/movies"))
            .header("Location", not(containsString("/id/")))
            .cookie(LanguagePreference.COOKIE, equalTo("en"))
    }

    @Test
    fun theQueryStringSurvivesTheSwitch() {
        given().redirects().follow(false)
            .formParam("lang", "id").formParam("next", "/cinemas?from=whatsapp")
            .post("/language")
            .then().statusCode(303)
            .header("Location", containsString("/id/cinemas?from=whatsapp"))
    }

    @Test
    fun theReturnPathCannotBeTurnedIntoAnOpenRedirect() {
        // //evil.example is the classic shape: a browser reads it as a protocol-relative
        // absolute URL, and it passes any check that only asks "does it start with a slash".
        given().redirects().follow(false)
            .formParam("lang", "id").formParam("next", "//evil.example")
            .post("/language")
            .then().statusCode(303)
            .header("Location", not(containsString("evil.example")))
    }

    @Test
    fun aLangFieldHoldingSomethingThatIsNotALanguageFallsBackRatherThanErroring() {
        given().redirects().follow(false)
            .formParam("lang", "klingon").formParam("next", "/movies")
            .post("/language")
            .then().statusCode(303)
            .cookie(LanguagePreference.COOKIE, equalTo("en"))
    }
}
