package com.protek.booking.web.customer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/** With no model key configured — the default, and every test's state but ChatAssistantTest's —
 * the assistant does not exist as far as a visitor can tell: no link, and the page and the app's
 * `/api/app/chat` are 404s. */
@QuarkusTest
class ChatOffTest {

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun anUnconfiguredAssistantHasNoLinkAndNoPage() {
        given().`when`().get("/movies").then().statusCode(200).body(not(containsString("href=\"/chat\"")))
        given().`when`().get("/chat").then().statusCode(404)
        // The app's door too (M8): its GET's 404 is what hides the app's entry point.
        given().`when`().get("/api/app/chat").then().statusCode(404)
        given().contentType("application/json").body("""{"message":"halo"}""")
            .`when`().post("/api/app/chat").then().statusCode(404)
    }
}
