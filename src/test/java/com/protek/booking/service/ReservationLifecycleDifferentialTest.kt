package com.protek.booking.service

import com.protek.booking.db.ActorContext
import com.protek.booking.db.Db
import com.protek.booking.db.RlsBoundRole
import com.protek.booking.domain.Customer
import com.protek.booking.domain.PolicyDocument
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.push.RecordingPushService
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The unattended half of the reservation lifecycle — `held → expired`, `confirmed → completed`, and the
 * expired-watch prune — run over connections belonging to a role that
 * `FORCE ROW LEVEL SECURITY` actually binds.
 *
 * **Why the sweeps specifically.** Nobody is waiting on a response. They were
 * next after the booking path, and they are the worse of the two to get
 * wrong: a policy gap in `hold` produces an error a customer sees immediately, while a
 * policy gap here produces a sweep that quietly updates zero rows. Seats stay held forever,
 * `reservation_no_overlap` never sheds settled history, and the only symptom is an absence.
 *
 * Runs [ReservationLifecycleService.sweep] itself rather than the repository calls
 * underneath it, because the sweep's own first statement is the shape that caused a production
 * outage: a transaction with *no* tenant context, which under FORCE RLS means "no
 * rows" rather than "no filter". That statement is load-bearing — every tenant the sweep will
 * ever touch comes out of it.
 */
@QuarkusTest
@TestProfile(SchedulerOffTestProfile::class)
class ReservationLifecycleDifferentialTest {

    companion object {
        private val PRICE = BigDecimal("50000.00")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var lifecycle: ReservationLifecycleService
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var policyRepo: PolicyDocumentRepository
    @Inject
    lateinit var watchRepo: ResourcePoolWatchRepository
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var actorContext: ActorContext

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String
    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var adminUser: String
    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var adminPassword: String

    private lateinit var rlsRole: RlsBoundRole

    @BeforeEach
    fun createRoleSubjectToRls() {
        rlsRole = RlsBoundRole.create("rls_sweep", jdbcUrl, adminUser, adminPassword, actorContext)
        // An expiring hold notifies its seat map; without a stub that reaches the real FCM client.
        QuarkusMock.installMockForType(RecordingPushService(), PushNotificationService::class.java)
    }

    @AfterEach
    fun dropRole() {
        rlsRole.close()
    }

    private fun switchToRoleSubjectToRls() {
        QuarkusMock.installMockForType(rlsRole.db(), Db::class.java)
    }

    @Test
    fun theSweepExpiresAStaleHoldUnderARoleRowLevelSecurityBinds() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "S1", "S", 1)
        val stale = heldReservation(tenant, pool, seat, Instant.now().minus(Duration.ofMinutes(1)))

        switchToRoleSubjectToRls()
        lifecycle.sweep()

        assertEquals("expired", statusOf(tenant, stale),
            "the hold-expiry sweep updated nothing under the deploying role — every abandoned "
                + "hold would keep its seat unsellable, with no error anywhere to say so")
    }

    @Test
    fun theSweepCompletesAPastConfirmedReservationUnderTheSameRole() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "S2", "S", 1)
        val start = Instant.now().minus(Duration.ofDays(2))
        val past = heldReservation(tenant, pool, seat, Instant.now().plus(Duration.ofMinutes(10)),
            start, start.plus(Duration.ofHours(2)))
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, past.reservationId) }

        switchToRoleSubjectToRls()
        lifecycle.sweep()

        assertEquals("completed", statusOf(tenant, past))
    }

    @Test
    fun theSweepPrunesAnExpiredWatchUnderTheSameRole() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val showing = fixture.session(tenant, pool, "on_sale")
        db.inTenantTransaction(tenant.tenantId) { c -> watchRepo.register(c, tenant.tenantId,
            pool.resourcePoolId, showing.resourcePoolSessionId, null, "token-abandoned") }
        // register() always writes now() + 20 minutes, so the row has to be aged by hand to be the
        // abandoned tab this prune exists for.
        db.inTenantTransaction(tenant.tenantId) { c -> db.execute(c,
            "UPDATE resource_pool_watch SET expires_at = now() - interval '1 minute' "
                + "WHERE tenant_id = ? AND fcm_registration_token = ?",
            tenant.tenantId, "token-abandoned") }

        switchToRoleSubjectToRls()
        lifecycle.sweep()

        assertTrue(db.inTenantTransaction(tenant.tenantId) { c ->
            watchRepo.findByPool(c, tenant.tenantId, pool.resourcePoolId) }.isEmpty(),
            "DELETE under RLS needs the policy to admit the row it is deleting, not merely to "
                + "show it — an abandoned watch that survives the prune accumulates forever")
    }

    /**
     * The differential itself, on the statement the whole sweep hangs off.
     *
     * `sweep()` opens a transaction with no tenant context to list every tenant. That is
     * the exact shape that broke settlement, and it is correct *only* because
     * `tenant` carries no RLS policy. If one were ever added — a plausible-looking tightening
     * — the sweep would find zero tenants, do nothing at all, and report success.
     */
    @Test
    fun theSweepsContextFreeTenantScanIsTheOneReadThatMayHaveNoTenantContext() {
        val tenant = fixture.tenant()

        switchToRoleSubjectToRls()

        val visible: List<Tenant> = db.inTransaction(tenantRepo::findAll)
        assertTrue(visible.any { t -> t.tenantId == tenant.tenantId },
            "with no tenant context set, the bound role must still see the tenant list — this "
                + "read is what every per-tenant sweep iterates, so zero rows here is a "
                + "silent no-op sweep rather than a failure")

        // And the contrast that makes the claim mean something: any tenant-scoped table, same
        // connection, same absent context, returns nothing rather than everything.
        rlsRole.openConnection().use { bound ->
            assertEquals(0, countReservations(bound),
                "a tenant-scoped table read with no context must return no rows — if this ever "
                    + "counts rows, FORCE ROW LEVEL SECURITY is not in effect for this role "
                    + "and tenant isolation is gone")
        }
        rlsRole.openOwnerConnection().use { owner ->
            assertTrue(countReservations(owner) > 0,
                "the same query as the migrating superuser sees rows with no context at all — "
                    + "which is what every test outside this class has always measured")
        }
    }

    private fun countReservations(c: Connection): Int {
        c.prepareStatement("SELECT count(*) FROM reservation").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun statusOf(tenant: Tenant, reservation: Reservation): String? =
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, reservation.reservationId) }
            .orElseThrow().status

    private fun heldReservation(tenant: Tenant, pool: ResourcePool, seat: ResourceUnit,
                                holdExpiresAt: Instant): Reservation {
        val start = Instant.now().plus(Duration.ofDays(1))
        return heldReservation(tenant, pool, seat, holdExpiresAt, start, start.plus(Duration.ofHours(2)))
    }

    /** Seeded with the fixture's ordinary privileges: what is under test is the sweep, not the
     * ability to create rows for it. */
    private fun heldReservation(tenant: Tenant, pool: ResourcePool, seat: ResourceUnit,
                                holdExpiresAt: Instant, periodStart: Instant, periodEnd: Instant): Reservation {
        val session: ResourcePoolSession = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Showing",
                periodStart, periodEnd, periodStart.minusSeconds(900), "on_sale") }
        val customer: Customer = fixture.customer(tenant)
        val policy: PolicyDocument = db.inTenantTransaction(tenant.tenantId) { c ->
            policyRepo.findLatest(c, tenant.tenantId, "cancellation_policy") }
            .orElseGet { fixture.policy(tenant) }
        val group: ReservationGroup = fixture.group(tenant, pool, customer, 1)
        return db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.insertHold(
            c, tenant.tenantId, group.reservationGroupId, seat.resourceUnitId,
            session.resourcePoolSessionId, policy.policyDocumentId, periodStart, periodEnd,
            PRICE, "IDR", holdExpiresAt, "customer", UUID.randomUUID()) }
    }
}
