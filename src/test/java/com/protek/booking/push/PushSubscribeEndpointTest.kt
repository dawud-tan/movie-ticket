package com.protek.booking.push

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The HTTP contract of the subscribe/unsubscribe pair — the client's only way into the seat-freed push.
 *
 * **Why this test exists at all,** when [WatchLifecycleTest] already covers the
 * repository underneath it: the whole feature was broken end to end while every one of those tests
 * passed. A Kotlin dependency brought in
 * `quarkus-rest-kotlin-serialization`, and that extension takes over JSON for the entire
 * application. kotlinx.serialization cannot serialize a Java record it has no generated serializer
 * for, so a real POST here failed with 500 "Serializer for class 'SubscribeRequest' is not found"
 * — a defect living entirely in the HTTP layer, invisible to any test that calls the repository or
 * the service directly. `JacksonJsonProvider` was the fix until 2026-10-08; since then the
 * request and response types are Kotlin `@Serializable` classes, `RestJsonWriter` writes
 * them, and `MalformedJsonInterceptor` keeps a bad body a 400.
 * This is what would have caught the need for either, and what will catch either one going wrong:
 * [malformedJsonIsRejectedAsBadRequest_notAsAServerError] is the case the interceptor exists for.
 *
 * So the assertions deliberately go through the wire rather than the beans, and check the
 * database afterwards: a 200 that wrote no row would be the same silent failure in a nicer costume.
 *
 * `watchCount` is a `Long`, so its assertions compare against `1L`/`0L`: Java widened the `int`
 * to `assertEquals(long, long)`, and a bare Kotlin `1` would fall to the `Object` overload and
 * compare an `Integer` with a `Long`.
 */
@QuarkusTest
class PushSubscribeEndpointTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var tenantRepo: TenantRepository
    @ConfigProperty(name = "booking.tenant.slug")
    lateinit var configuredSlug: String

    private fun watchCount(tenant: Tenant, token: String): Long =
        db.inTenantTransaction(tenant.tenantId) { c ->
            db.queryOne(c,
                "SELECT count(*) FROM resource_pool_watch WHERE tenant_id = ? AND fcm_registration_token = ?",
                { rs -> rs.getLong(1) }, tenant.tenantId, token).orElse(0L)
        }

    private data class Scene(val tenant: Tenant, val pool: ResourcePool, val session: ResourcePoolSession)

    /* The endpoint resolves the tenant from config (booking.tenant.slug) rather than from the
     * request, so the fixture has to hang its pool off that same tenant for the write to be
     * visible — a fresh fixture.tenant() would be a different tenant entirely and RLS would
     * correctly hide the row. */
    private fun sceneOnConfiguredTenant(): Scene {
        val tenant = db.inTransaction { c ->
            tenantRepo.findBySlug(c, configuredSlug).orElseThrow {
                IllegalStateException("configured tenant '" + configuredSlug + "' is seeded by the demo seed")
            }
        }
        val pool = fixture.pool(tenant)
        return Scene(tenant, pool, fixture.session(tenant, pool, "on_sale"))
    }

    private fun body(s: Scene, token: String): String =
        """{"poolId":"%s","sessionId":"%s","token":"%s"}"""
            .format(s.pool.resourcePoolId, s.session.resourcePoolSessionId, token)

    @Test
    fun subscribeWritesAWatch_andUnsubscribeRemovesIt() {
        val s = sceneOnConfiguredTenant()
        val token = "endpoint-test-token"

        given().contentType("application/json").body(body(s, token))
            .`when`().post("/push/subscribe")
            .then().statusCode(200)
            // The id proves a row came back rather than an empty 200, which is what a
            // half-working serialization path would produce.
            .body("resourcePoolWatchId", notNullValue())
        assertEquals(1L, watchCount(s.tenant, token))

        given().contentType("application/json").body(body(s, token))
            .`when`().delete("/push/subscribe")
            .then().statusCode(204)
        assertEquals(0L, watchCount(s.tenant, token))
    }

    @Test
    fun subscribingTwiceRefreshesRatherThanDuplicating() {
        // The client re-subscribes on every page load, so this is the ordinary path, not an edge
        // case: if it inserted, a customer who reloads twice gets three pushes per seat.
        val s = sceneOnConfiguredTenant()
        val token = "endpoint-test-idempotent"

        for (i in 0 until 3) {
            given().contentType("application/json").body(body(s, token))
                .`when`().post("/push/subscribe").then().statusCode(200)
        }
        assertEquals(1L, watchCount(s.tenant, token))
    }

    @Test
    fun unsubscribingSomethingThatWasNeverThereIsStillNoContent() {
        // pagehide fires on tabs that never subscribed (push denied, or unsupported browser), and
        // that beacon must not produce an error the client cannot act on anyway.
        val s = sceneOnConfiguredTenant()
        given().contentType("application/json").body(body(s, "never-registered"))
            .`when`().delete("/push/subscribe")
            .then().statusCode(204)
    }

    @Test
    fun malformedJsonIsRejectedAsBadRequest_notAsAServerError() {
        given().contentType("application/json").body("{\"poolId\":\"not-a-uuid\"}")
            .`when`().post("/push/subscribe")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin@platform.example", roles = ["platform_admin"])
    fun platformAdminSubscribingAndUnsubscribing_doesNotFailWithA500() {
        // Regression: both endpoints called currentTenant.id(),
        // which throws by design for platform_admin (CurrentTenant.kt:133) because that
        // identity has no home tenant. A platform admin browsing any seat map therefore 500'd on
        // page close, when the browser fires DELETE /push/subscribe. Both now resolve through
        // publicCatalogueId() instead — the same "reader like anyone else" resolution the public
        // seat map itself already uses (TenantResolutionSecurityTest).
        val s = sceneOnConfiguredTenant()
        val token = "platform-admin-endpoint-test-token"

        given().contentType("application/json").body(body(s, token))
            .`when`().post("/push/subscribe")
            .then().statusCode(200)
            .body("resourcePoolWatchId", notNullValue())
        assertEquals(1L, watchCount(s.tenant, token))

        given().contentType("application/json").body(body(s, token))
            .`when`().delete("/push/subscribe")
            .then().statusCode(204)
        assertEquals(0L, watchCount(s.tenant, token))
    }

    // --- /api/app/push/subscribe: the phone's mirror (Mobile M6) ----------------------------

    @Test
    fun theAppsMirrorWritesTheSameWatch_andItsDeleteRemovesIt() {
        val s = sceneOnConfiguredTenant()
        val token = "app-endpoint-test-token"

        given().contentType("application/json").body(body(s, token))
            .`when`().post("/api/app/push/subscribe")
            .then().statusCode(200)
            .body("resourcePoolWatchId", notNullValue())
        assertEquals(1L, watchCount(s.tenant, token))

        given().contentType("application/json").body(body(s, token))
            .`when`().delete("/api/app/push/subscribe")
            .then().statusCode(204)
        assertEquals(0L, watchCount(s.tenant, token))
    }

    @Test
    fun aResentAppSubscribeAnswersTheSameWatchRatherThanASecondOne() {
        // OkHttp may send a POST again after a dropped connection (AppApiResource's KDoc): the
        // second copy must be the first one's row, or a seat change pushes twice to one phone.
        val s = sceneOnConfiguredTenant()
        val token = "app-endpoint-test-resent"

        val ids = (0 until 2).map {
            given().contentType("application/json").body(body(s, token))
                .`when`().post("/api/app/push/subscribe")
                .then().statusCode(200)
                .extract().path<String>("resourcePoolWatchId")
        }
        assertEquals(ids[0], ids[1])
        assertEquals(1L, watchCount(s.tenant, token))
    }

    @Test
    fun theAppsMirrorRefusesAMissingTokenWithAJsonRefusal() {
        val s = sceneOnConfiguredTenant()
        given().contentType("application/json").body("""{"poolId":"%s"}""".format(s.pool.resourcePoolId))
            .`when`().post("/api/app/push/subscribe")
            .then().statusCode(400)
            .body("error", equalTo("bad_request"))
    }

    @Test
    fun deletingAnAppWatchThatIsAlreadyGoneIsStillNoContent() {
        // The watch expires after 20 minutes, so the app's "stop" can arrive after the row has.
        val s = sceneOnConfiguredTenant()
        given().contentType("application/json").body(body(s, "app-never-registered"))
            .`when`().delete("/api/app/push/subscribe")
            .then().statusCode(204)
    }

    @Test
    fun aTestSendFromAnAnonymousCaller_isRefused_evenWithALiveWatchOnThePool() {
        // The watch is what makes this the real case: without one the endpoint 404s before it
        // sends, so a refusal that came from there would prove nothing about the role check.
        val s = sceneOnConfiguredTenant()
        given().contentType("application/json").body(body(s, "test-send-anonymous"))
            .`when`().post("/push/subscribe").then().statusCode(200)
        // 401 with OIDC disabled, 302 to the provider when enabled; never the send's 200.
        val status = given().redirects().follow(false).contentType("application/json")
            .body("""{"poolId":"%s","message":"Your booking was cancelled"}""".format(s.pool.resourcePoolId))
            .`when`().post("/push/test-send").then().extract().statusCode()
        org.junit.jupiter.api.Assertions.assertTrue(status == 401 || status == 302,
            "anonymous POST /push/test-send returned $status; expected 401 or a redirect to the IdP")
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"])
    fun aTestSendFromACustomer_is403() {
        // Any Google account becomes a customer on first sign-in, so this is the caller that matters.
        given().contentType("application/json").body("""{"poolId":"%s"}""".format(java.util.UUID.randomUUID()))
            .`when`().post("/push/test-send")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"])
    fun aTestSendFromTenantStaff_getsPastTheRoleCheck() {
        // A pool with no watches answers 404 before anything is sent, so this needs no FCM.
        given().contentType("application/json").body("""{"poolId":"%s"}""".format(java.util.UUID.randomUUID()))
            .`when`().post("/push/test-send")
            .then().statusCode(404)
            .body("error", equalTo("no live watches registered for this pool"))
    }
}
