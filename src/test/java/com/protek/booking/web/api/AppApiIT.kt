package com.protek.booking.web.api

import com.protek.booking.support.FakeGoogleOidc
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusIntegrationTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * `/api/app` against the native binary. `AppApiTest` covers the behaviour on the JVM.
 * This exists because the ways a native image fails here (a kotlinx serializer, a Kotlin data
 * class, an exception mapper that is not registered) only show up in the image.
 *
 * No seeding and no sign-in: the demo showing (`…0003`, hall `…0002`) is on sale
 * for a day after migration, and every call here is anonymous. [FakeGoogleOidc] is applied
 * only because the binary's prod profile expects an OIDC provider to boot against, as in the
 * other ITs.
 */
@QuarkusIntegrationTest
@WithTestResource(FakeGoogleOidc::class)
class AppApiIT {

    @Test
    fun theBrowsePathAndItsErrorsAnswerJsonFromTheNativeBinary() {
        given().`when`().get("/api/app/cities")
            .then().statusCode(200).body("city", hasItem("Jakarta Pusat"))

        given().`when`().get("/api/app/movies")
            .then().statusCode(200).body("slug", hasItem("demo-film"))

        given().`when`().get("/api/app/movies/demo-film")
            .then().statusCode(200)
            .body("movie.title", equalTo("Demo Film"))
            .body("cinemas.slug", hasItem("demo-cinema-menteng"))

        given().`when`().get("/api/app/cinemas/demo-cinema-menteng/movies/demo-film/showings")
            .then().statusCode(200)
            .body("[0].sessionId", equalTo("00000000-0000-0000-0000-000000000003"))
            .body("[0].zone", notNullValue())

        val map: JsonPath = given().header("Accept-Language", "id").`when`()
            .get("/api/app/halls/00000000-0000-0000-0000-000000000002/showings/00000000-0000-0000-0000-000000000003")
            .then().statusCode(200)
            .body("rows[0].seats[0].statusLabel", notNullValue())
            .body("price.amount", notNullValue())
            .body("ageGate.statement", notNullValue())
            .extract().jsonPath()
        assertFalse(map.prettify().contains("reservationId"), map.prettify())

        given().contentType("application/json").body("{\"latitude\":-7.0870,\"longitude\":110.9150}")
            .`when`().post("/api/app/cities/nearest")
            .then().statusCode(200).body("city", equalTo("Rekaan"))

        given().contentType("application/json").body("{\"latitude\":-7.0}")
            .`when`().post("/api/app/cities/nearest")
            .then().statusCode(400).body("error", equalTo("bad_request"))

        given().`when`().get("/api/app/movies/no-such-film")
            .then().statusCode(404).contentType("application/json").body("error", notNullValue())
    }
}
