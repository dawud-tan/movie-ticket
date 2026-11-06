package com.protek.booking.push

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The watcher lookup and the register/expire lifecycle around it.
 *
 * The lookup query is transcribed from the plan, and its three unusual details — the COALESCE
 * on both sides, DISTINCT, and the expiry filter — each exist for a reason that only shows up in a
 * specific scenario. These tests are those scenarios, because a query that "looks right" here is
 * exactly the kind that silently notifies the wrong people.
 */
@QuarkusTest
class WatchLifecycleTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var watchRepo: ResourcePoolWatchRepository

    private data class Scene(val tenant: Tenant, val pool: ResourcePool, val session: ResourcePoolSession)

    private fun scene(): Scene {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        return Scene(tenant, pool, fixture.session(tenant, pool, "on_sale"))
    }

    private fun notifyTokens(s: Scene, sessionId: UUID?): List<String> =
        db.inTenantTransaction(s.tenant.tenantId) { c ->
            watchRepo.findTokensToNotify(c, s.tenant.tenantId, s.pool.resourcePoolId, sessionId)
        }

    private fun register(s: Scene, sessionId: UUID?, token: String) {
        db.inTenantTransaction(s.tenant.tenantId) { c ->
            watchRepo.register(c, s.tenant.tenantId, s.pool.resourcePoolId, sessionId, null, token)
        }
    }

    @Test
    fun aWatchOnOneSessionIsNotNotifiedAboutAnother() {
        val s = scene()
        val other = fixture.session(s.tenant, s.pool, "on_sale")
        register(s, s.session.resourcePoolSessionId, "token-tonight")
        register(s, other.resourcePoolSessionId, "token-tomorrow")

        assertEquals(listOf("token-tonight"), notifyTokens(s, s.session.resourcePoolSessionId),
            "a customer watching tonight's showing must not be woken by changes to tomorrow's")
        assertEquals(listOf("token-tomorrow"), notifyTokens(s, other.resourcePoolSessionId))
    }

    @Test
    fun aHallWideWatchAndASessionWatchAreDifferentSubscriptions() {
        // The COALESCE sentinel makes a NULL session match only a NULL session argument. That is
        // the plan's own query, and it is deliberate rather than a quirk: a hall-wide watch (what
        // the /push/test harness registers) is not a wildcard over every showing.
        val s = scene()
        register(s, null, "token-hall")
        register(s, s.session.resourcePoolSessionId, "token-session")

        assertEquals(listOf("token-hall"), notifyTokens(s, null),
            "COALESCE on both sides is what lets a NULL-session watch match at all — plain "
                    + "'=' would compare NULL to NULL and match nothing")
        assertEquals(listOf("token-session"), notifyTokens(s, s.session.resourcePoolSessionId))
    }

    @Test
    fun oneDeviceHoldingTwoWatchesIsNotifiedOnce() {
        // A device can legitimately hold a hall watch and a session watch. Without DISTINCT the
        // same token would be handed to FCM twice for one change, so the customer's phone buzzes
        // twice for one seat.
        val s = scene()
        register(s, null, "token-same-device")
        register(s, s.session.resourcePoolSessionId, "token-same-device")

        assertEquals(1, notifyTokens(s, s.session.resourcePoolSessionId).size)
    }

    @Test
    fun anExpiredWatchIsNotNotified_andIsPrunedBySweep() {
        val s = scene()
        register(s, s.session.resourcePoolSessionId, "token-live")
        register(s, s.session.resourcePoolSessionId, "token-stale")
        // Force one row past its window, the state a crashed or discarded tab leaves behind.
        db.inTenantTransaction(s.tenant.tenantId) { c ->
            db.execute(c,
                "UPDATE resource_pool_watch SET expires_at = now() - interval '1 minute' " +
                        "WHERE tenant_id = ? AND fcm_registration_token = ?",
                s.tenant.tenantId, "token-stale")
        }

        assertEquals(listOf("token-live"), notifyTokens(s, s.session.resourcePoolSessionId),
            "the expiry filter stops an abandoned tab receiving pushes even before it is pruned")

        val pruned = db.inTenantTransaction(s.tenant.tenantId) { c ->
            watchRepo.deleteExpired(c, s.tenant.tenantId)
        }
        assertEquals(1, pruned)
        assertEquals(listOf("token-live"), notifyTokens(s, s.session.resourcePoolSessionId))
    }

    @Test
    fun reRegisteringTheSameTokenRefreshesTheWindowRatherThanDuplicating() {
        val s = scene()
        register(s, s.session.resourcePoolSessionId, "token-a")
        db.inTenantTransaction(s.tenant.tenantId) { c ->
            db.execute(c,
                "UPDATE resource_pool_watch SET expires_at = now() - interval '1 minute' " +
                        "WHERE tenant_id = ? AND fcm_registration_token = ?",
                s.tenant.tenantId, "token-a")
        }
        assertTrue(notifyTokens(s, s.session.resourcePoolSessionId).isEmpty())

        // The dedupe index means this upserts rather than inserting a second row, which is also
        // what makes it safe for the client to re-subscribe on every page load.
        register(s, s.session.resourcePoolSessionId, "token-a")
        assertEquals(listOf("token-a"), notifyTokens(s, s.session.resourcePoolSessionId),
            "re-registering must revive the watch by refreshing expires_at")
    }

    @Test
    fun clientUnregisterRemovesExactlyThatWatch() {
        val s = scene()
        register(s, null, "token-x")
        register(s, s.session.resourcePoolSessionId, "token-x")

        val removed = db.inTenantTransaction(s.tenant.tenantId) { c ->
            watchRepo.deleteWatch(
                c, s.tenant.tenantId, s.pool.resourcePoolId, s.session.resourcePoolSessionId, "token-x")
        }
        assertEquals(1, removed)
        assertTrue(notifyTokens(s, s.session.resourcePoolSessionId).isEmpty())
        assertEquals(listOf("token-x"), notifyTokens(s, null),
            "closing one showing's page must not cancel the same device's other watches")
    }

    @Test
    fun deleteByTokenRemovesEveryWatchForADeadSubscription() {
        // The prune path for a token FCM reports as gone: the subscription is dead everywhere, not
        // just for the seat map that happened to trigger the send.
        val s = scene()
        val other = fixture.session(s.tenant, s.pool, "on_sale")
        register(s, s.session.resourcePoolSessionId, "token-dead")
        register(s, other.resourcePoolSessionId, "token-dead")
        register(s, s.session.resourcePoolSessionId, "token-alive")

        val removed = db.inTenantTransaction(s.tenant.tenantId) { c ->
            watchRepo.deleteByToken(c, s.tenant.tenantId, "token-dead")
        }
        assertEquals(2, removed)
        assertEquals(listOf("token-alive"), notifyTokens(s, s.session.resourcePoolSessionId))
        assertTrue(notifyTokens(s, other.resourcePoolSessionId).isEmpty())
    }

    @Test
    fun watchesAreTenantIsolated() {
        val a = scene()
        val b = scene()
        register(a, a.session.resourcePoolSessionId, "token-tenant-a")
        register(b, b.session.resourcePoolSessionId, "token-tenant-b")

        assertEquals(listOf("token-tenant-a"), notifyTokens(a, a.session.resourcePoolSessionId))
        assertEquals(listOf("token-tenant-b"), notifyTokens(b, b.session.resourcePoolSessionId))
    }
}
