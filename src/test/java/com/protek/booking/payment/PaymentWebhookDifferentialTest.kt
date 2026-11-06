package com.protek.booking.payment

import com.protek.booking.db.ActorContext
import com.protek.booking.db.Db
import com.protek.booking.db.UnprivilegedDb
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The differential half of [PaymentFlowTest]: the same settlement flow, run over connections
 * belonging to a role that `FORCE ROW LEVEL SECURITY` actually binds.
 *
 * **Why a whole separate test class.** `PaymentFlowTest` passed for months against a
 * webhook that could not settle a single payment in production. Dev Services' Testcontainers role
 * is created SUPERUSER by the postgres image, and an RLS-exempt role never evaluates a policy
 * expression at all — so `applyVerifiedStatus` running with no tenant context looked
 * identical to running with the right one. It is not: under FORCE ROW LEVEL SECURITY "no context"
 * means "no rows", not "no filter", and every settlement failed with "No payment for order …".
 * The bug was found by pointing the suite at a plain database owner by hand. This
 * class is that manual step, automated, so the next regression fails here instead of in
 * production.
 *
 * The role is a throwaway `LOGIN` account holding `app_runtime` — V1's RLS
 * hardening role, `NOSUPERUSER NOBYPASSRLS` with the real runtime grant set — rather than an
 * ad-hoc set of grants, so what these tests exercise is the privilege shape the application is
 * meant to deploy under.
 */
@QuarkusTest
class PaymentWebhookDifferentialTest {

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
    lateinit var paymentService: PaymentService
    @Inject
    lateinit var reconciliationService: PaymentReconciliationService
    @Inject
    lateinit var paymentRepo: PaymentRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
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

    private lateinit var stub: StubMidtransGateway
    private lateinit var roleName: String
    private var unprivilegedDb: UnprivilegedDb? = null

    @BeforeEach
    fun installStubAndCreateRoleSubjectToRls() {
        stub = StubMidtransGateway("test-server-key")
        QuarkusMock.installMockForType(stub, MidtransGateway::class.java)
        // The configured server key is the "__unset__" sentinel in tests, which the real verifier
        // correctly treats as unconfigured and rejects everything with, so a concrete key has to be
        // installed for the signature path to be genuinely exercised rather than short-circuited.
        QuarkusMock.installMockForType(TestSignatureVerifier("test-server-key"),
            MidtransSignatureVerifier::class.java)

        roleName = "rls_webhook_" + Math.abs(UUID.randomUUID().hashCode())
        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
            admin.createStatement().use { st ->
                st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime'").use { rs ->
                    assertTrue(rs.next(),
                        "V1's app_runtime role is missing, so this test cannot run under the privilege " +
                            "shape it exists to check — the migration warns and continues when the " +
                            "migrating role lacks CREATEROLE, which is not the case under Dev Services")
                }
                st.execute("CREATE ROLE " + roleName + " LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS INHERIT")
                st.execute("GRANT app_runtime TO " + roleName)
            }
        }
    }

    @AfterEach
    fun dropRole() {
        if (unprivilegedDb != null) {
            unprivilegedDb!!.close()
            unprivilegedDb = null
        }
        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
            admin.createStatement().use { st ->
                st.execute("DROP OWNED BY " + roleName)
                st.execute("DROP ROLE " + roleName)
            }
        }
    }

    /**
     * Swaps the `Db` bean for one whose connections are the throwaway role's, from this point
     * in the test onward. Called after setup rather than before it so the fixture keeps its
     * ordinary privileges: what is under test is the webhook, not the ability to seed rows.
     */
    private fun switchToRoleSubjectToRls() {
        unprivilegedDb = UnprivilegedDb(jdbcUrl, roleName, "x", actorContext)
        QuarkusMock.installMockForType(unprivilegedDb!!, Db::class.java)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun verifiedSettlement_appliesEvenThoughTheWebhookCarriesNoIdentityToDeriveATenantFrom() {
        val booked = bookOneSeat("D1")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val settled = stub.settle(payment.qrisPartnerReferenceNo)

        switchToRoleSubjectToRls()

        // The assertion that fails the moment applyVerifiedStatus goes back to a context-free
        // transaction: findByOrderIdForUpdate then matches no row and this throws NotFoundException
        // rather than returning true.
        assertTrue(paymentService.applyNotification(notificationFor(settled)),
            "a settlement must apply when the connection's role is subject to RLS — this is the " +
                "only configuration that exists in production")

        // Read back through the same unprivileged Db, so the assertion cannot pass on a privilege
        // the webhook itself did not have.
        val after = db.inTenantTransaction(booked.tenant.tenantId) { c ->
            paymentRepo.findById(c, booked.tenant.tenantId, payment.paymentId)
        }.orElseThrow()
        assertEquals(Payment.STATUS_PAID, after.status)
        assertEquals("confirmed", db.inTenantTransaction(booked.tenant.tenantId) { c ->
            reservationRepo.findById(c, booked.tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status,
            "the seats are the point: a payment marked paid whose reservations stayed held is " +
                "the same customer-visible failure as no settlement at all")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun unknownOrderId_isStillReportedAsNotFound_ratherThanAsATenantScanThatFoundNothingOdd() {
        switchToRoleSubjectToRls()

        // Signed by hand rather than through the stub, which only knows orders it has charged —
        // the point here is a genuinely valid signature over an order id that exists nowhere, so
        // the tenant scan is what rejects it and not the verifier.
        val orderId = "ORDER-THAT-WAS-NEVER-ISSUED"
        val unknown = MidtransNotification(orderId, "midtrans-none",
            "settlement", "accept", "200", "50000.00",
            stub.signature(orderId, "200", "50000.00"), "qris", "{}")

        // The distinction the fix has to preserve: an order nobody owns must still be reported as
        // not found. Before it, an order whose tenant context simply was not set reported the same
        // thing — which is why the outage read as "Midtrans is sending us garbage".
        val e = assertThrows(NotFoundException::class.java) {
            paymentService.applyNotification(unknown)
        }
        assertTrue(e.message!!.contains(orderId), "actual message: " + e.message)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_appliesASettlementUnderTheSameRole_becauseItSharesTheTransitionCode() {
        val booked = bookOneSeat("D2")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        // The customer paid and the notification was lost. Reconciliation is the other caller of
        // applyVerifiedStatus, and it had the identical defect: it holds the tenant already, but
        // was throwing it away at the call.
        stub.settle(payment.qrisPartnerReferenceNo)
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        switchToRoleSubjectToRls()

        assertTrue(reconciliationService.reconcileOne(tenant, payment))
        assertEquals(Payment.STATUS_PAID, db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }.orElseThrow().status)
        assertEquals("confirmed", db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId,
                booked.reservation.reservationId)
        }.orElseThrow().status)
    }

    /**
     * The give-up transition, never-received branch. Before it existed this row stayed
     * `pending` and was re-queried every five minutes for as long as the database kept it,
     * one outbound call per run, none of which could ever succeed — Midtrans cannot later learn
     * about an order it never received.
     *
     * Here under the bound role because the give-up is a write across an RLS table, and a
     * write that silently matches no row is exactly the failure this class exists to catch: the
     * scan would have looked like it was giving up while the row stayed pending forever.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_givesUpOnAnOrderMidtransNeverReceived_insteadOfRequeryingItForever() {
        val booked = bookOneSeat("D3")
        // Our charge call never landed, so no QR was ever issued and nothing could have been paid,
        // and the row has no Midtrans reference (the next test is the row that has one).
        stub.failCharges(HttpMidtransGateway.MidtransException("simulated network failure"))
        assertThrows(HttpMidtransGateway.MidtransException::class.java) {
            paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
        }
        val payment = paymentService.paymentsFor(booked.group.reservationGroupId!!).single()
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        switchToRoleSubjectToRls()

        assertTrue(reconciliationService.reconcileOne(tenant, payment),
            "the row left the pending population, which is what this run resolved — it does " +
                "not claim money moved")
        assertEquals(Payment.STATUS_EXPIRED, reload(tenant, payment).status,
            "a payment Midtrans has no record of can never settle, so it must not stay pending")
    }

    /**
     * The guard on the branch above. The BI-SNAP query once answered "not found" for every live
     * QR, and the sweep wrote each one off within five minutes while the customer could still pay
     * it. A stored Midtrans reference proves the charge was answered, so "no record" is a misread:
     * the row stays pending until its validity lapses, and then it is given up as any lapsed one is.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_keepsAQrMidtransAnsweredFor_whenItsStatusQuerySaysNoRecord_untilItLapses() {
        val booked = bookOneSeat("D6")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        assertTrue(payment.qrisReferenceNo != null, "the charge answered with Midtrans's reference")
        stub.forget(payment.qrisPartnerReferenceNo)
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        switchToRoleSubjectToRls()

        assertFalse(reconciliationService.reconcileOne(tenant, payment),
            "a QR Midtrans issued may still be paid, whatever its status query answers")
        assertEquals(Payment.STATUS_PENDING, reload(tenant, payment).status)

        backdateExpiry(payment, "2 days")
        assertTrue(reconciliationService.reconcileOne(tenant, reload(tenant, payment)),
            "past its validity it ends as any lapsed payment does")
        assertEquals(Payment.STATUS_EXPIRED, reload(tenant, payment).status)
    }

    /**
     * The give-up transition, lapsed branch — and the ordering that makes it safe. Midtrans knows
     * the order and still reports `pending`, which is the normal answer for a live charge,
     * so the give-up turns on our own validity window having lapsed rather than on their answer.
     *
     * The seat is deliberately not asserted on: `payment` and `reservation` expire
     * on their own clocks, and conflating them is what made the original manual expiry wrong.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_givesUpOnAChargeLapsedPastItsValidity_whenMidtransStillReportsPending() {
        val booked = bookOneSeat("D4")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        // chargeQris leaves the stub reporting "pending", so applyVerifiedStatus applies nothing
        // and the lapsed check is the only thing that can resolve this row.
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()
        backdateExpiry(payment, "2 days")
        // Reloaded, not reused: hasLapsed reads expires_at off the Payment it is handed, and the
        // real scan always hands it a row loaded that instant by findPendingForReconciliation.
        // Passing the pre-backdate object would test a staleness production never has.
        val aged = reload(tenant, payment)

        switchToRoleSubjectToRls()

        assertTrue(reconciliationService.reconcileOne(tenant, aged))
        assertEquals(Payment.STATUS_EXPIRED, reload(tenant, payment).status,
            "past its validity the customer can no longer pay this QR, so no future run could " +
                "get a different answer")
    }

    /**
     * The guard on the branch above, and the one worth having: a payment Midtrans still reports as
     * pending, whose validity has *not* lapsed, is a live charge someone may be paying right
     * now. Giving up on it would write off money about to arrive.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun reconciliation_leavesALivePendingChargeAlone_becauseTheCustomerMayStillBePayingIt() {
        val booked = bookOneSeat("D5")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val tenant = db.inTransaction { c -> tenantRepo.findById(c, booked.tenant.tenantId) }.orElseThrow()

        switchToRoleSubjectToRls()

        assertFalse(reconciliationService.reconcileOne(tenant, payment),
            "nothing was resolved: Midtrans reports pending and the charge is still within its " +
                "validity window")
        assertEquals(Payment.STATUS_PENDING, reload(tenant, payment).status,
            "a live charge must survive the scan — this is the assertion that fails if the " +
                "lapsed check is ever loosened to fire on a non-terminal status alone")
    }

    /**
     * Ages a charge past its validity. Done with the owner connection rather than through the
     * application, because no application path backdates `expires_at` — the column is set
     * once at charge time, and the passage of two days is what the test is standing in for.
     */
    private fun backdateExpiry(payment: Payment, interval: String) {
        try {
            DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
                admin.prepareStatement(
                    "UPDATE payment SET expires_at = now() - interval '" + interval + "' " +
                        "WHERE payment_id = ?").use { ps ->
                    ps.setObject(1, payment.paymentId)
                    assertEquals(1, ps.executeUpdate(), "the charge to age must exist")
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException(e)
        }
    }

    private fun reload(tenant: Tenant, payment: Payment): Payment =
        db.inTenantTransaction(tenant.tenantId) { c ->
            paymentRepo.findById(c, tenant.tenantId, payment.paymentId)
        }.orElseThrow()

    /**
     * The differential itself, stated as plainly as it can be: one query, one row, two roles, two
     * answers. This is the whole reason 130 passing tests said nothing about the bug.
     */
    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theSameOrderLookupFindsTheRowAsASuperuserAndNoRowUnderRls_whichIsWhyTheSuiteWasBlind() {
        val booked = bookOneSeat("D3")
        val payment = paymentService.createQrisCharge(booked.group.reservationGroupId!!, false)
            .orElseThrow()
        val orderId = payment.qrisPartnerReferenceNo
        val sql = "SELECT count(*) FROM payment WHERE qris_partner_reference_no = ?"

        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { superuser ->
            assertEquals(1, count(superuser, sql, orderId),
                "the Testcontainers role is a SUPERUSER, so it never evaluates the policy and " +
                    "sees the row with no tenant context at all — this is what every other " +
                    "test in the suite has always been measuring")
        }

        DriverManager.getConnection(jdbcUrl, roleName, "x").use { bound ->
            bound.autoCommit = false

            assertEquals(0, count(bound, sql, orderId),
                "under FORCE ROW LEVEL SECURITY with no context set the same query returns no " +
                    "rows, which is what turned every settlement into 'No payment for order …'")
            bound.rollback()

            setTenant(bound, booked.tenant.tenantId)
            assertEquals(1, count(bound, sql, orderId),
                "with the owning tenant's context established, the row is visible again — " +
                    "PaymentService derives that tenant rather than being handed one")
            bound.commit()

            // Now the warm-pool state, which is the second half of the same trap and the reason
            // every policy wraps the GUC in NULLIF. The transaction that set it is over, so the
            // setting is back off — but not back to unset.
            assertEquals("", currentTenantSetting(bound),
                "current_setting(name, true) reads NULL only while the GUC has never been set on " +
                    "this connection, and '' ever after (measured on PostgreSQL 18.4). If " +
                    "this ever reads NULL again, the premise behind the NULLIF in every " +
                    "policy has changed and needs revisiting")
            assertEquals(0, count(bound, sql, orderId),
                "''::uuid raises 22P02, so without NULLIF this would fail loudly at the policy " +
                    "instead of returning zero rows — on a pooled connection, i.e. always")
            bound.rollback()
        }
    }

    private fun count(c: Connection, sql: String, param: String?): Int =
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, param)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun setTenant(c: Connection, tenantId: UUID) {
        c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
            ps.setString(1, tenantId.toString())
            ps.execute()
        }
    }

    private fun currentTenantSetting(c: Connection): String? =
        c.createStatement().use { st ->
            st.executeQuery("SELECT current_setting('app.current_tenant_id', true)").use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private data class Booked(val tenant: Tenant, val group: ReservationGroup, val reservation: Reservation)

    private fun bookOneSeat(seatLabel: String): Booked {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, seatLabel, seatLabel.substring(0, 1), 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, SEAT_PRICE)
        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertTrue(result.success, "setup hold failed: " + result.failureReason)
        return Booked(tenant, result.group!!, result.reservations[0])
    }

    private fun notificationFor(s: MidtransGateway.TransactionStatus): MidtransNotification =
        MidtransNotification(s.orderId, s.transactionId, s.transactionStatus,
            s.fraudStatus, s.statusCode, s.grossAmount, s.signatureKey, "qris",
            "{\"order_id\":\"" + s.orderId + "\"}")
}
