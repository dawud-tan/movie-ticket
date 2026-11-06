package com.protek.booking.service

import com.protek.booking.db.ActorContext
import com.protek.booking.db.Db
import com.protek.booking.db.RlsBoundRole
import com.protek.booking.domain.AuditLogEntry
import com.protek.booking.domain.Customer
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Tenant
import com.protek.booking.push.RecordingPushService
import com.protek.booking.repository.AuditLogRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID

/**
 * `hold → confirm` run over connections belonging to a role that
 * `FORCE ROW LEVEL SECURITY` actually binds.
 *
 * **Why this exists.** [com.protek.booking.payment.PaymentWebhookDifferentialTest]
 * closed one path this way after a policy gap took settlement down in production; this one
 * was next, on the grounds that it writes across more RLS tables in a single
 * transaction than anything else in the application — `resource_pool_session`,
 * `customer`, `reservation_group`, `reservation`, and `audit_log` through
 * the trigger — and that it is the path that takes money. Every one of those writes was, until
 * now, only ever executed by a SUPERUSER, which never evaluates a policy expression at all.
 *
 * The fixture keeps its ordinary privileges and only the call under test is switched, so what
 * is being measured is the booking path rather than the ability to seed rows for it.
 */
@QuarkusTest
class BookingFlowDifferentialTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
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
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var auditRepo: AuditLogRepository
    @Inject
    lateinit var watchRepo: ResourcePoolWatchRepository
    @Inject
    lateinit var actorContext: ActorContext

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String
    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var adminUser: String
    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var adminPassword: String

    private lateinit var rlsRole: RlsBoundRole
    private lateinit var push: RecordingPushService

    @BeforeEach
    fun createRoleSubjectToRls() {
        rlsRole = RlsBoundRole.create("rls_booking", jdbcUrl, adminUser, adminPassword, actorContext)
        // Every hold and confirm dispatches a fire-and-forget push; without a stub those reach the
        // real FCM client on an executor thread and log failures that have nothing to do with RLS.
        push = RecordingPushService()
        QuarkusMock.installMockForType(push, PushNotificationService::class.java)
    }

    @AfterEach
    fun dropRole() {
        rlsRole.close()
    }

    /** Swaps the `Db` bean for the bound role's, from this point in the test onward. */
    private fun switchToRoleSubjectToRls() {
        QuarkusMock.installMockForType(rlsRole.db(), Db::class.java)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aHoldAndItsConfirmationBothSucceedUnderARoleRowLevelSecurityBinds() {
        val staged = stage("R1")

        switchToRoleSubjectToRls()

        val held = bookingService.holdForCounterSale(
            staged.pool.resourcePoolId, staged.showing.resourcePoolSessionId,
            java.util.List.of(staged.seat.resourceUnitId))
        assertTrue(held.success, "hold failed under the role the application is meant to deploy "
            + "under: " + held.failureReason)

        assertTrue(bookingService.confirmGroup(held.group!!.reservationGroupId),
            "confirmGroup reads the group and every reservation in it before updating both — "
                + "a policy that hid either would report a false 'not all held' rather than fail")

        // Read back through the same unprivileged Db, so the assertion cannot pass on a privilege
        // the booking path itself did not have.
        val tenantId = staged.tenant.tenantId
        assertEquals("confirmed", db.inTenantTransaction(tenantId) { c ->
                reservationRepo.findById(c, tenantId, held.reservations[0].reservationId) }
            .orElseThrow().status)
        assertEquals("confirmed", db.inTenantTransaction(tenantId) { c ->
                groupRepo.findById(c, tenantId, held.group.reservationGroupId) }
            .orElseThrow().status)
    }

    @Test
    // The attribute key has to be the augmentor's own constant, not "email": @TestSecurity
    // bypasses SecurityIdentityAugmentor entirely, so nothing translates a
    // friendlier name. Set the wrong key and CurrentActor.email() is simply empty — the hold
    // still succeeds, with a customer row carrying a NULL email, which is not what this asserts.
    @TestSecurity(user = "first-timer@example.com", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "first-timer@example.com")])
    fun aSelfServiceHoldCreatesItsOwnCustomerRowUnderRls() {
        // The counter sale above resolves an existing walk-in placeholder; a customer's first
        // booking INSERTs into customer instead, which is a different policy check (WITH CHECK on
        // the new row rather than USING on an existing one) and the one a real customer hits.
        val staged = stage("R2")

        switchToRoleSubjectToRls()

        val held = bookingService.hold(
            staged.pool.resourcePoolId, staged.showing.resourcePoolSessionId,
            java.util.List.of(staged.seat.resourceUnitId), "First Timer", "+6281100000009",
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(held.success, "self-service hold failed under RLS: " + held.failureReason)

        val tenantId = staged.tenant.tenantId
        val created: Customer = db.inTenantTransaction(tenantId) { c ->
            customerRepo.findByEmail(c, tenantId, "first-timer@example.com") }.orElseThrow()
        assertEquals(created.customerId, db.inTenantTransaction(tenantId) { c ->
                groupRepo.findById(c, tenantId, held.group!!.reservationGroupId) }
            .orElseThrow().customerId,
            "the group must belong to the customer row the hold created, not to a placeholder")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun whenOneSeatOfABlockIsGoneTheSavepointCleanupAlsoRunsUnderRls() {
        // The hold's SAVEPOINT/ROLLBACK TO path, which only executes after a 23P01 and therefore never
        // ran as anything but a superuser before this. It does two writes *after* the failed
        // statement — cancelling the partial holds and marking the group failed — and both are
        // UPDATEs whose policy is evaluated separately from the INSERT that aborted.
        val staged = stage("R3")
        val second: ResourceUnit = fixture.unit(staged.tenant, staged.pool, "R3b", "R", 2)
        val taken = bookingService.holdForCounterSale(
            staged.pool.resourcePoolId, staged.showing.resourcePoolSessionId,
            java.util.List.of(second.resourceUnitId))
        assertTrue(taken.success, "setup hold failed: " + taken.failureReason)

        switchToRoleSubjectToRls()

        // Free seat first, taken seat second, so the collision happens with a partial hold already
        // created — which is what makes the cleanup run at all.
        val blocked = bookingService.holdForCounterSale(
            staged.pool.resourcePoolId, staged.showing.resourcePoolSessionId,
            java.util.List.of(staged.seat.resourceUnitId, second.resourceUnitId))

        assertFalse(blocked.success)
        assertEquals(second.resourceUnitId, blocked.failedResourceUnitId,
            "the response has to name the seat that blocked the block booking (the richer "
                + "response), which means reading it back out of a failed transaction")

        val tenantId = staged.tenant.tenantId
        // The committed record of the failure. Queried by the seat that blocked it because a failed
        // HoldResult deliberately carries no group — the failure is wanted committed as a coherent
        // record, and this is the only way to see whether the UPDATE that wrote it took effect.
        assertEquals("failed", db.inTenantTransaction(tenantId) { c -> db.queryOne(c,
                "SELECT status FROM reservation_group "
                    + "WHERE tenant_id = ? AND failed_resource_unit_id = ?",
                { rs -> rs.getString("status") }, tenantId, second.resourceUnitId) }
            .orElseThrow { AssertionError(
                "no failed reservation_group row: markFailed's UPDATE matched nothing under RLS") })

        // And the seat this attempt did grab is released rather than left held by a booking that
        // never completed — the cancel() half of the same cleanup.
        val partiallyHeld: SeatMapEntry = db.inTenantTransaction(tenantId) { c ->
                reservationRepo.findSeatMap(c, tenantId, staged.pool.resourcePoolId,
                    staged.showing.resourcePoolSessionId) }
            .stream().filter { e -> e.resourceUnitId!! == staged.seat.resourceUnitId }
            .findFirst().orElseThrow()
        assertEquals("available", partiallyHeld.seatStatus,
            "the partial hold must be cancelled, not left holding a seat for a failed group")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theHoldIsAuditedAndTheApplicationsOwnRoleCannotRewriteThatHistory() {
        val staged = stage("R4")

        switchToRoleSubjectToRls()

        val held = bookingService.holdForCounterSale(
            staged.pool.resourcePoolId, staged.showing.resourcePoolSessionId,
            java.util.List.of(staged.seat.resourceUnitId))
        assertTrue(held.success, "hold failed under RLS: " + held.failureReason)

        val tenantId = staged.tenant.tenantId
        val reservationId = held.reservations[0].reservationId
        val entries: List<AuditLogEntry> = db.inTenantTransaction(tenantId) { c ->
            auditRepo.findByEntity(c, tenantId, "reservation", reservationId) }
        assertFalse(entries.isEmpty(),
            "the audit trigger runs as the caller's role, so it needs INSERT on audit_log and a "
                + "policy that admits the row — if either is missing the hold itself fails")

        // The audit trail's whole point, stated as a privilege rather than as a promise: append and read only.
        // REVOKE UPDATE, DELETE, TRUNCATE in V1's role block is what makes the chain unalterable *through the
        // application's own credential*, and nothing tested that the revoke was still in force.
        rlsRole.openConnection().use { bound ->
            bound.createStatement().use { st ->
                val denied = assertThrows(SQLException::class.java) {
                    st.execute("UPDATE audit_log SET row_hash = 'tampered'") }
                assertEquals("42501", denied.sqlState,
                    "expected insufficient_privilege, got: " + denied.message)
            }
        }
        rlsRole.openConnection().use { bound ->
            bound.createStatement().use { st ->
                val denied = assertThrows(SQLException::class.java) {
                    st.execute("DELETE FROM audit_log") }
                assertEquals("42501", denied.sqlState,
                    "expected insufficient_privilege, got: " + denied.message)
            }
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theNotifyOnCommitLookupAlsoRunsUnderRls_whereAFailureWouldBeSwallowed() {
        // The one place where an RLS regression would be completely silent in production:
        // notifyWatchers is fire-and-forget and deliver()'s failures are logged and dropped, by
        // design, so that a push problem can never fail a committed booking. A policy that hid
        // resource_pool_watch would therefore turn every seat map back into a poll-only page with
        // nothing failing anywhere. Asserted against deliver(), the synchronous body, so it does
        // not race the executor.
        val staged = stage("R5")
        db.inTenantTransaction(staged.tenant.tenantId) { c -> watchRepo.register(
            c, staged.tenant.tenantId, staged.pool.resourcePoolId,
            staged.showing.resourcePoolSessionId, null, "token-under-rls") }

        switchToRoleSubjectToRls()

        notifier.deliver(staged.tenant.tenantId, staged.pool.resourcePoolId,
            staged.showing.resourcePoolSessionId, SeatAvailabilityNotifier.Reason.SEATS_HELD)

        assertEquals(java.util.List.of("token-under-rls"), push.allNotifiedTokens(),
            "the watcher lookup found nothing under the deploying role — every push would "
                + "silently stop while every test and every log stayed clean")
    }

    private data class Staged(val tenant: Tenant, val pool: ResourcePool, val seat: ResourceUnit,
                              val showing: ResourcePoolSession)

    /** Seeds one bookable seat with the fixture's ordinary privileges. Uses the tenant
     * `CurrentTenant` resolves to, since `BookingService` asks it rather than taking a
     * tenant argument. */
    private fun stage(seatLabel: String): Staged {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, seatLabel, seatLabel.substring(0, 1), 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        return Staged(tenant, pool, seat, showing)
    }
}
