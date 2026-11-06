package com.protek.booking.web.api

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.ScriptedChatModel
import com.protek.booking.support.TestFixture
import com.protek.booking.web.customer.ChatAssistantTest
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The app's `/api/app/chat` (mobile session M8) over real bearer tokens from [FakeGoogleOidc],
 * with the model replaced by [ScriptedChatModel] in [ChatAssistantTest]'s shape: the real AI
 * service, loop and tools run, and the suite stays offline. What is asserted is the trust
 * boundary: the proposal is the tool's record and not the model's text, the memory id is the
 * bearer's own account, and the daily cap is one count across the web and the app.
 * The off state is `ChatOffTest`'s.
 */
@QuarkusTest
@TestProfile(ChatAssistantTest.Configured::class)
@WithTestResource(FakeGoogleOidc::class)
class AppChatTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    @ConfigProperty(name = "quarkus.oidc.customer-app.auth-server-url")
    lateinit var issuer: String

    private lateinit var hall: ResourcePool
    private lateinit var session: ResourcePoolSession
    private lateinit var free: ResourceUnit

    @BeforeEach
    fun showingAndFakeModel() {
        val tenant = currentTenant.get()
        hall = fixture.pool(tenant)
        free = fixture.unit(tenant, hall, "Q1", "Q", 1)
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        session = fixture.session(tenant, hall, "on_sale")
        ScriptedChatModel.SCRIPT.clear()
    }

    private fun call(tool: String, json: String): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(tool).arguments(json).build()

    private fun email(who: String) = "chat-$who-${System.nanoTime()}@example.test"

    /** An ID token from the fake provider's `/token`, as the phone gets one. */
    private fun asApp(email: String): RequestSpecification {
        val form = "code=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
            "&audience=" + URLEncoder.encode(FakeGoogleOidc.CLIENT_ID, StandardCharsets.UTF_8)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$issuer/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString())
        return given().header("Authorization", "Bearer " + JsonPath.from(response.body()).getString("id_token"))
    }

    private fun body(message: String, thread: String) = """{"message":"$message","thread":"$thread"}"""

    @Test
    fun aProposalComesBackAsTheToolsRecord_poolSessionAndSeats_notAsTextToParse() {
        val buyer = email("buyer")
        val page = asApp(buyer).`when`().get("/api/app/chat").then().statusCode(200)
            .body("notice", containsString("Groq, Inc. (United States)"))
            .body("dailyCap", equalTo(2))
            .extract().jsonPath()
        val thread = page.getString("thread")

        val ids = "{\"hallId\":\"" + hall.resourcePoolId + "\",\"sessionId\":\"" + session.resourcePoolSessionId + "\""
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from(listOf(call("free_seats", "$ids}"))) }
        ScriptedChatModel.SCRIPT.add { _ ->
            AiMessage.from(listOf(call("propose_seats", ids + ",\"seats\":[\"Q1\"]}")))
        }
        // A model that writes a link of its own: the app shows it as text, and the button is the record's.
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("Kursi Q1 [buka](https://evil.example/)") }

        val reply = asApp(buyer).contentType("application/json").body(body("Satu kursi untuk pertunjukan ini", thread))
            .`when`().post("/api/app/chat").then().statusCode(200)
            .body("answer", equalTo("Kursi Q1 [buka](https://evil.example/)"))
            .body("notice", containsString("Groq, Inc. (United States)"))
            .extract().jsonPath()
        assertEquals(hall.resourcePoolId.toString(), reply.getString("proposal.poolId"))
        assertEquals(session.resourcePoolSessionId.toString(), reply.getString("proposal.sessionId"))
        assertEquals(listOf(free.resourceUnitId.toString()), reply.getList<String>("proposal.seatIds"))
        assertEquals(listOf("Q1"), reply.getList<String>("proposal.seatLabels"))
        assertTrue(ScriptedChatModel.SCRIPT.isEmpty(), "all three scripted turns ran")
    }

    @Test
    fun anotherAccountSendingTheSameThreadIdGetsAFreshConversation_notTheFirstAccountsMemory() {
        val thread = UUID.randomUUID().toString()
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("Baik.") }
        asApp(email("owner")).contentType("application/json").body(body("kode rahasia saya 4417", thread))
            .`when`().post("/api/app/chat").then().statusCode(200)

        ScriptedChatModel.SCRIPT.add { r ->
            val seen = r.messages().joinToString("\n")
            assertFalse(seen.contains("4417"), "the other account's turn reached this conversation: $seen")
            AiMessage.from("Halo!")
        }
        asApp(email("intruder")).contentType("application/json").body(body("apa kode rahasia saya?", thread))
            .`when`().post("/api/app/chat").then().statusCode(200).body("answer", equalTo("Halo!"))
        assertTrue(ScriptedChatModel.SCRIPT.isEmpty(), "both turns reached the model")
    }

    @Test
    fun withoutABearerTheAppGetsA401_andTheModelIsNeverCalled() {
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("never") }
        given().contentType("application/json").body(body("halo", UUID.randomUUID().toString()))
            .`when`().post("/api/app/chat").then().statusCode(401)
        given().`when`().get("/api/app/chat").then().statusCode(401)
        assertEquals(1, ScriptedChatModel.SCRIPT.size)
    }

    /** The cap is the app user's, whichever door: the same [CurrentActor] on `/chat` and on the
     * app's endpoint, as one person signed in on both. The resent copy is the second app POST. */
    @Test
    @TestSecurity(user = "both-doors@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "both-doors@demo-cinema.example")])
    fun theDailyCapIsOneCountAcrossTheWebAndTheApp_andAResentCopyCountsAsAMessage() {
        QuarkusMock.installMockForType(ActingAs.customer(null, UUID.randomUUID()), CurrentActor::class.java)
        val thread = UUID.randomUUID().toString()
        repeat(2) {
            ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("Halo!") }
            given().contentType("application/json").body(body("halo", thread))
                .`when`().post("/api/app/chat").then().statusCode(200).body("answer", equalTo("Halo!"))
        }
        given().contentType("application/json").body(body("halo", thread))
            .`when`().post("/api/app/chat").then().statusCode(429)
            .body("error", equalTo("cap_reached"))
            .body("message", containsString("today's 2 messages"))
        given().formParam("message", "halo").`when`().post("/chat").then().statusCode(200)
            .body(containsString("today&#39;s 2 messages"))
            .body(not(containsString("Halo!")))
        assertTrue(ScriptedChatModel.SCRIPT.isEmpty(), "the capped messages never reached the model")
    }
}
