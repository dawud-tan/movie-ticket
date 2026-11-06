package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.push.RecordingPushService
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * Notify-on-commit: every path that changes `reservation.status` must tell the
 * watchers of that seat map.
 *
 * Asserts against [SeatAvailabilityNotifier.deliver] — the synchronous body — rather than
 * the fire-and-forget entry point, so the assertions do not race the executor. The asynchrony is a
 * production requirement (the send must not block the response the customer is waiting on),
 * not something worth making the tests flaky over; that the entry point dispatches is a one-line
 * fact, while what gets delivered and what gets pruned is the behaviour with teeth. The setup's
 * own hold goes through the executor too, so `bookOneSeat` waits for it before returning.
 */
@QuarkusTest
@TestProfile(SchedulerOffTestProfile::class)
class NotifyOnCommitTest {

    companion object {
        private val PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var notifier: SeatAvailabilityNotifier
    @Inject
    lateinit var watchRepo: ResourcePoolWatchRepository

    private lateinit var push: RecordingPushService

    @BeforeEach
    fun installStub() {
        push = RecordingPushService()
        QuarkusMock.installMockForType(push, PushNotificationService::class.java)
    }

    private data class Booked(val tenant: Tenant, val pool: ResourcePool, val showing: ResourcePoolSession,
                              val seat: ResourceUnit, val result: BookingService.HoldResult)

    private fun bookOneSeat(label: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, label, label.substring(0, 1), 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, PRICE)
        // The hold fires its own SEATS_HELD on the executor after commit, as production requires.
        // Left alone, that send lands whenever the executor gets to it: after the test has
        // registered its watchers, it reached them as a second send and failed
        // deliverSendsToTheWatchersOfThatSeatMapOnly with [token-watching, token-watching] (once
        // in eight runs, on the Java and the Kotlin alike). With no watcher the send is not
        // observable at all, so one is registered just to wait on, then removed with what it saw.
        watch(tenant, pool, showing, "token-setup")
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, listOf(seat.resourceUnitId))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        push.awaitAttempts(1, Duration.ofSeconds(5))
        db.inTenantTransaction(tenant.tenantId) { c -> watchRepo.deleteByToken(c, tenant.tenantId, "token-setup") }
        push.clear()
        return Booked(tenant, pool, showing, seat, result)
    }

    private fun watch(tenant: Tenant, pool: ResourcePool, showing: ResourcePoolSession, token: String) {
        db.inTenantTransaction(tenant.tenantId) { c -> watchRepo.register(
            c, tenant.tenantId, pool.resourcePoolId, showing.resourcePoolSessionId, null, token) }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun deliverSendsToTheWatchersOfThatSeatMapOnly() {
        val booked = bookOneSeat("N1")
        watch(booked.tenant, booked.pool, booked.showing, "token-watching")

        val otherPool = fixture.pool(booked.tenant)
        val otherShowing = fixture.session(booked.tenant, otherPool, "on_sale")
        watch(booked.tenant, otherPool, otherShowing, "token-elsewhere")

        notifier.deliver(booked.tenant.tenantId, booked.pool.resourcePoolId,
            booked.showing.resourcePoolSessionId, SeatAvailabilityNotifier.Reason.SEATS_RELEASED)

        assertEquals(listOf("token-watching"), push.allNotifiedTokens(),
            "a watcher of a different seat map must not be notified")
        assertEquals("SEATS_RELEASED", push.sends[0].data["reason"])
        assertEquals(booked.pool.resourcePoolId.toString(), push.sends[0].data["poolId"],
            "the payload carries the seat map so the service worker can refresh the right page "
                + "in place rather than only showing a banner")
        assertEquals(booked.showing.resourcePoolSessionId.toString(),
            push.sends[0].data["sessionId"])
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun noWatchersMeansNoSendAtAll() {
        val booked = bookOneSeat("N2")
        notifier.deliver(booked.tenant.tenantId, booked.pool.resourcePoolId,
            booked.showing.resourcePoolSessionId, SeatAvailabilityNotifier.Reason.SEATS_HELD)
        assertTrue(push.sends.isEmpty(), "an empty watcher set must not produce an FCM call")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun deadTokensArePruned_butTransientFailuresAreNot() {
        val booked = bookOneSeat("N3")
        watch(booked.tenant, booked.pool, booked.showing, "token-gone")
        watch(booked.tenant, booked.pool, booked.showing, "token-flaky")
        watch(booked.tenant, booked.pool, booked.showing, "token-ok")
        push.withDeadTokens("token-gone").withFailingTokens("token-flaky")

        notifier.deliver(booked.tenant.tenantId, booked.pool.resourcePoolId,
            booked.showing.resourcePoolSessionId, SeatAvailabilityNotifier.Reason.SEATS_RELEASED)

        val remaining: List<String> = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            watchRepo.findTokensToNotify(c, booked.tenant.tenantId,
                booked.pool.resourcePoolId, booked.showing.resourcePoolSessionId) }
        assertFalse(remaining.contains("token-gone"),
            "An unregistered token is the signal to DELETE the watch, not to retry it")
        assertTrue(remaining.contains("token-flaky"),
            "a transient FCM failure must NOT unsubscribe a live customer — that is why dead "
                + "and failed tokens are reported separately")
        assertTrue(remaining.contains("token-ok"))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aPushFailureCannotBreakACommittedBooking() {
        // The booking has already committed by the time the notification runs. Letting FCM being
        // unreachable surface as an error would turn someone else's outage into a failed response
        // for a customer whose seat is already safely held.
        val booked = bookOneSeat("N4")
        watch(booked.tenant, booked.pool, booked.showing, "token-any")
        push.withTotalFailure(RuntimeException("FCM unreachable"))

        assertDoesNotThrow { notifier.notifyWatchers(booked.tenant.tenantId,
            booked.pool.resourcePoolId, booked.showing.resourcePoolSessionId,
            SeatAvailabilityNotifier.Reason.SEATS_RELEASED) }

        assertTrue(bookingService.cancelReservation(
            booked.result.reservations[0].reservationId, "customer", "test"),
            "the booking lifecycle must keep working while push is broken")

        // Both sends this test causes are fire-and-forget — the notifyWatchers call above and
        // the release the cancellation triggers — so both can still be in flight here, and the next
        // test's @BeforeEach installs a fresh recorder that a late one would land in. That is the
        // leak RecordingPushService#awaitAttempts documents.
        //
        // A fixed count of two, not a "current + 1" baseline: reading the counter after dispatching
        // an asynchronous send races the send itself, so the baseline came back 0 and the wait was
        // satisfied by the very attempt it was supposed to be waiting past. This is the only test
        // that uses the async entry point, so it is the only one that has to wait at all.
        push.awaitAttempts(2, Duration.ofSeconds(5))
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun expirySweepReportsTheSeatMapsItChanged() {
        // The one case with no live request to piggyback on. The sweep can only notify if the
        // UPDATE tells it which pools/sessions it touched — reservation carries no
        // resource_pool_id, so this is the CTE join to resource_unit doing its job.
        val booked = bookOneSeat("N5")
        // The query expires every stale hold in the tenant, and the tenant is shared with every
        // other class: without this drain, holds other tests left behind came back alongside this
        // one (expected 1, was 4). The background sweep used to clear them, but only when its
        // 30-second timer happened to fire first, and it could equally expire this test's hold
        // before the call below; SchedulerOffTestProfile turns it off, so this drain is the
        // only sweep the class sees.
        db.inTenantTransaction(booked.tenant.tenantId) { c -> expireAndReport(c, booked.tenant.tenantId) }
        db.inTenantTransaction(booked.tenant.tenantId) { c -> db.execute(c,
            "UPDATE reservation SET hold_expires_at = now() - interval '1 minute' " +
                "WHERE tenant_id = ? AND reservation_id = ?",
            booked.tenant.tenantId, booked.result.reservations[0].reservationId) }

        val affected: List<ReservationRepository.AffectedSeatMap> =
            db.inTenantTransaction(booked.tenant.tenantId) { c ->
                expireAndReport(c, booked.tenant.tenantId) }

        assertEquals(1, affected.size, "the sweep must report exactly the seat map it expired")
        assertEquals(booked.pool.resourcePoolId, affected[0].resourcePoolId,
            "resource_pool_id has to come from the join through resource_unit")
        assertEquals(booked.showing.resourcePoolSessionId, affected[0].resourcePoolSessionId)
    }

    @Inject
    lateinit var reservationRepo: ReservationRepository

    private fun expireAndReport(c: Connection, tenantId: UUID?): List<ReservationRepository.AffectedSeatMap> =
        reservationRepo.expireStaleHoldsReturningSeatMaps(c, tenantId)
}
