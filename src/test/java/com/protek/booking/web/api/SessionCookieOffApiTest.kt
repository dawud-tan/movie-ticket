package com.protek.booking.web.api

import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.NativeItSupport
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * The browser's session cookie authenticates nothing on `/api/app`, even with its bearer tenant
 * off — which is what keeps a cross-site POST off it, not `@Consumes`.
 * A POST with no `Content-Type` skips `@Consumes` in 4.0.0.Beta1, and `fetch` with a `Blob` body
 * sends exactly that without a preflight; it still answers 401 here because `tenant-paths` hands
 * the path to its own tenant whether it is enabled or not, and a disabled tenant signs nobody
 * in. Removing the `tenant-paths` line, or adding a tenant resolver that falls back to the default
 * tenant, would let the cookie through and fail this class (found in a security review).
 *
 * The cookie comes from the real code flow through [FakeGoogleOidc], not `@TestSecurity`, which
 * would skip it. `/orders` answering 200 with it is the control: without it a 401 below would
 * pass for a cookie that never worked anywhere. `java.net.http` sends no `Content-Type` unless
 * told to, as REST Assured would.
 */
@QuarkusTest
@WithTestResource(SessionCookieOffApiTest.WebSessionOnly::class)
class SessionCookieOffApiTest {

    /** [FakeGoogleOidc] with the `customer-app` tenant turned back off. */
    class WebSessionOnly : QuarkusTestResourceLifecycleManager {
        private val fake = FakeGoogleOidc()
        override fun start(): Map<String, String> =
            fake.start() + ("quarkus.oidc.customer-app.tenant-enabled" to "false")
        override fun stop() = fake.stop()
    }

    @TestHTTPResource("/")
    lateinit var base: URL

    private lateinit var cookie: String

    @BeforeEach
    fun signInOnTheWeb() {
        cookie = NativeItSupport.signIn("staff@demo-cinema.example").entries.joinToString("; ") { it.key + "=" + it.value }
    }

    private fun send(method: String, path: String, body: String?, type: String?): Int {
        val request = HttpRequest.newBuilder(URI.create(base.toString().removeSuffix("/") + path))
            .header("Cookie", cookie)
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (type != null) request.header("Content-Type", type)
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun theCookieThatOpensOrdersOnTheWeb_isAnonymousOnTheApi() {
        assertEquals(200, send("GET", "/orders", null, null), "the control: the cookie signs in on the web")
        assertEquals(401, send("GET", "/api/app/me", null, null))
    }

    @Test
    fun anUntypedPostCarryingTheCookie_isRefusedBeforeTheApisMethod() {
        val thread = UUID.randomUUID()
        val chat = """{"message":"halo","thread":"$thread"}"""
        assertEquals(401, send("POST", "/api/app/chat", null, null))
        assertEquals(401, send("POST", "/api/app/chat", chat, null))
        assertEquals(401, send("POST", "/api/app/chat", chat, "application/json"))
    }
}
