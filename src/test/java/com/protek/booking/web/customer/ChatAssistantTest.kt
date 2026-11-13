package com.protek.booking.web.customer

import com.protek.booking.agent.AssistantTools
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.isJavaBlank
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.ScriptedChatModel
import com.protek.booking.support.TestFixture
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.ChatMemoryProvider
import dev.langchain4j.model.TokenCountEstimator
import dev.langchain4j.model.chat.request.ChatRequest
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * `/chat` with the assistant configured and the model replaced by [ScriptedChatModel]:
 * the real AI service, loop and tools run, and no test ever calls the model host. What is asserted is the
 * part this application owns — the link is built from the proposal the tool recorded, the model's
 * text is escaped, the daily cap holds, and a taken seat cannot be proposed.
 */
@QuarkusTest
@TestProfile(ChatAssistantTest.Configured::class)
class ChatAssistantTest {

    class Configured : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("quarkus.langchain4j.openai.api-key" to "test-key-never-sent",
                "booking.assistant.daily-messages" to "2")
    }

    companion object {
        private fun call(tool: String, json: String): ToolExecutionRequest =
            ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(tool).arguments(json).build()

        private fun lastText(request: ChatRequest): String {
            val last: ChatMessage = request.messages()[request.messages().size - 1]
            return if (last is ToolExecutionResultMessage) last.text() else last.toString()
        }
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var tools: AssistantTools
    @Inject
    lateinit var memories: ChatMemoryProvider
    @Inject
    lateinit var tokens: TokenCountEstimator

    private lateinit var hall: ResourcePool
    private lateinit var session: ResourcePoolSession
    private lateinit var free: ResourceUnit
    private lateinit var taken: ResourceUnit

    @BeforeEach
    fun showingAndFakeModel() {
        val tenant: Tenant = currentTenant.get()
        hall = fixture.pool(tenant)
        free = fixture.unit(tenant, hall, "Q1", "Q", 1)
        fixture.unit(tenant, hall, "Q2", "Q", 2)
        taken = fixture.unit(tenant, hall, "Q3", "Q", 3)
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        session = fixture.session(tenant, hall, "on_sale")
        // Taken through the real hold, as a customer would: the shared tenant's seeded policy applies.
        QuarkusMock.installMockForType(ActingAs.customer(fixture.customer(tenant).customerId), CurrentActor::class.java)
        assertTrue(bookingService.hold(hall.resourcePoolId, session.resourcePoolSessionId,
            java.util.List.of(taken.resourceUnitId!!), "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
            AgeClassification.Attestation.NOT_TICKED).success, "setup hold failed")

        ScriptedChatModel.SCRIPT.clear()
        QuarkusMock.installMockForType(ActingAs.customer(null, UUID.randomUUID()), CurrentActor::class.java)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun anAnswerEndsInTheSeatMapWithTheProposedSeatTicked_andTheModelsTextIsEscaped() {
        val ids = "{\"hallId\":\"" + hall.resourcePoolId + "\",\"sessionId\":\"" + session.resourcePoolSessionId + "\""
        // A model that reads the free seats, proposes Q1, then answers with markup it must never
        // be able to inject. The tools and the loop between these turns are the real ones.
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from(listOf(call("free_seats", ids + "}"))) }
        ScriptedChatModel.SCRIPT.add { r ->
            val seats = lastText(r)
            assertTrue(seats.contains("Q1") && !seats.contains("Q3"), "free_seats lists only free seats: " + seats)
            AiMessage.from(listOf(call("propose_seats", ids + ",\"seats\":[\"Q1\"]}")))
        }
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("Kursi Q1 <script>alert(1)</script>") }

        given().`when`().get("/chat").then().statusCode(200)
            .body(containsString("Groq, Inc. (United States)"))   // the privacy notice names the host
            .body(containsString("name=\"thread\""))

        given().formParam("message", "Satu kursi untuk pertunjukan ini").formParam("thread", UUID.randomUUID().toString())
            .`when`().post("/chat").then().statusCode(200)
            .body(containsString("/halls/" + hall.resourcePoolId + "/showings/" + session.resourcePoolSessionId
                    + "?seats=" + free.resourceUnitId))
            .body(containsString("Q1 ticked"))
            .body(containsString("&lt;script&gt;"))
            .body(not(containsString("<script>alert")))
    }

    @Test
    @TestSecurity(user = "capped@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "capped@demo-cinema.example")])
    fun theDailyCapStopsTheThirdMessageWithoutCallingTheModel() {
        repeat(2) {
            ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from("Halo!") }
            given().formParam("message", "halo").`when`().post("/chat").then().statusCode(200)
                .body(containsString("Halo!"))
        }
        given().formParam("message", "halo").`when`().post("/chat").then().statusCode(200)
            .body(containsString("today&#39;s 2 messages"))
            .body(not(containsString("Halo!")))
        assertTrue(ScriptedChatModel.SCRIPT.isEmpty(), "the capped message never reached the model")
    }

    @Test
    @TestSecurity(user = "nofilm@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "nofilm@demo-cinema.example")])
    fun showingsWithoutAFilmIsARefusalNamingTheNextCall_notAToolError() {
        // What the deployed model sent for "film apa saja yang tayang" on 2026-10-23: no filmRef.
        ScriptedChatModel.SCRIPT.add { _ -> AiMessage.from(listOf(call("showings", "{\"siteRef\":\"any-cinema\"}"))) }
        ScriptedChatModel.SCRIPT.add { r ->
            val answer = lastText(r)
            assertTrue(answer.contains("filmRef wajib") && !answer.contains("NullPointerException"), answer)
            AiMessage.from("Film apa yang Anda cari?")
        }
        given().formParam("message", "Film apa saja yang tayang malam ini?").`when`().post("/chat").then().statusCode(200)
            .body(containsString("Film apa yang Anda cari?"))
        assertTrue(ScriptedChatModel.SCRIPT.isEmpty(), "both scripted turns ran")
    }

    @Test
    fun aLongConversationIsCutToTheTokenBudget_keepingTheSystemPrompt() {
        val memory = memories.get("budget:" + UUID.randomUUID())
        memory.add(SystemMessage.from("Kamu adalah asisten pemesanan tiket bioskop."))
        val seatTable = "Q1,Q2,Q3,".repeat(300)   // about a free_seats result, ~1,500 tokens
        repeat(20) { memory.add(UserMessage.from("kursi $it: $seatTable")) }
        val kept = memory.messages()
        assertTrue(kept.first() is SystemMessage, "the system prompt is never evicted")
        assertTrue(kept.size in 2..20, "oldest turns dropped, the newest kept: " + kept.size)
        assertTrue(tokens.estimateTokenCountInMessages(kept) <= 3500,
            "within token-window.max-tokens: " + tokens.estimateTokenCountInMessages(kept))
    }

    @Test
    fun anAnonymousVisitorCannotChat() {
        val status = given().redirects().follow(false).formParam("message", "halo")
            .`when`().post("/chat").then().extract().statusCode()
        assertTrue(status == 401 || status == 302 || status == 403, "anonymous POST /chat answered " + status)
    }

    @Test
    fun aTakenSeatCannotBeProposed_andAnUnknownCinemaIsARefusalTheModelCanReadNotAnException() {
        val refusal = tools.proposeSeats(hall.resourcePoolId.toString(), session.resourcePoolSessionId.toString(),
            listOf("Q3"))
        assertTrue(refusal.contains("seat_taken"), refusal)
        assertTrue(tools.movies("no-such-cinema").contains("not_found"))
        // The benchmark's movie fixture, column for column: it measures what this sends.
        val tenant = currentTenant.get()
        val site = fixture.site(tenant, "Chat Cinema " + System.nanoTime(), "Rekaan")
        fixture.sessionOfFilm(tenant, fixture.poolAtSite(tenant, site, "Studio 1"),
            fixture.film(tenant, "Chat Film " + System.nanoTime(), "SU"), "on_sale")
        val movies = tools.movies(site.slug)
        assertTrue(movies.contains("{filmId,slug,title,durationMinutes,classification,synopsis,releaseDate}:"), movies)
        // isJavaBlank, not isBlank: Java's isBlank.
        assertFalse(tools.cities().isJavaBlank())
    }

    @Test
    fun showingsWithoutADateListsDaysAndTimesWithNoIds_andADateBringsTheIdsBack() {
        val tenant = currentTenant.get()
        val site = fixture.site(tenant, "Chat Cinema " + System.nanoTime(), "Rekaan")
        val pool = fixture.poolAtSite(tenant, site, "Studio 1")
        val film = fixture.film(tenant, "Chat Film " + System.nanoTime(), "SU")
        val jakarta = ZoneId.of("Asia/Jakarta")
        val day = LocalDate.now(jakarta).plusDays(3)
        val morning = fixture.sessionOfFilmAt(tenant, pool, film, day.atTime(10, 30).atZone(jakarta).toInstant(), "on_sale")
        fixture.sessionOfFilmAt(tenant, pool, film, day.atTime(14, 0).atZone(jakarta).toInstant(), "on_sale")
        fixture.sessionOfFilmAt(tenant, pool, film, day.plusDays(1).atTime(10, 30).atZone(jakarta).toInstant(), "on_sale")

        val days = tools.showings(site.slug, film.slug, null)
        assertTrue(days.contains("$day,Studio 1,\"10:30 14:00\"") && days.contains("${day.plusDays(1)},Studio 1,\"10:30\""), days)
        assertFalse(days.contains(morning.resourcePoolSessionId.toString()) || days.contains(pool.resourcePoolId.toString()), days)
        assertTrue(tools.showings(site.slug, film.slug, day.toString()).contains(morning.resourcePoolSessionId.toString()))
    }
}
