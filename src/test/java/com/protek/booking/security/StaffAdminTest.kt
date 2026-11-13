package com.protek.booking.security

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.StaffAdminService
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Staff management: the authorization boundary, the tenant scoping that RLS does not
 * provide for `app_user`, the lockout guard, and the audit record.
 *
 * **Why some tests use a fixture tenant and others the configured one.** Anything going
 * through [StaffAdminService] reads `CurrentTenant`, which resolves from a
 * `UUID` identity attribute the augmentor sets — and `@TestSecurity` can only
 * supply `String` attributes, so those tests necessarily act on the configured default
 * tenant. Everything that can be driven at the repository level uses a fresh fixture tenant
 * instead, which is both isolated and able to assert cross-tenant behaviour.
 */
@QuarkusTest
class StaffAdminTest {

    @Inject
    lateinit var staffAdmin: StaffAdminService
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture

    private fun staffOf(tenant: Tenant, manager: Boolean): AppUser =
        staffOf(tenant.tenantId, manager)

    private fun staffOf(tenantId: UUID, manager: Boolean): AppUser {
        val email = "staff-" + UUID.randomUUID() + "@example.test"
        return db.inTenantTransaction(tenantId) { c -> appUserRepo.insertStaff(c, email, tenantId, manager) }
    }

    private fun auditRows(tenantId: UUID, appUserId: UUID?, action: String): Int =
        db.inTenantTransaction(tenantId) { c -> db.queryOne(c,
            "SELECT count(*) AS n FROM audit_log WHERE tenant_id = ? AND entity_type = 'app_user' " +
                "AND entity_id = ? AND action = ?",
            { rs -> rs.getInt("n") }, tenantId, appUserId, action).orElse(0) }

    /** True once some backend is blocked behind `pid`, polled for up to ten seconds. A
     * transaction per poll, because `pg_stat_activity` is snapshotted per transaction. */
    private fun awaitBlockedBehind(pid: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = db.inTransaction { c -> db.queryOne(c,
                "SELECT count(*) AS n FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                { rs -> rs.getInt("n") }, pid).orElse(0) }
            if (blocked > 0) {
                return true
            }
            TimeUnit.MILLISECONDS.sleep(50)
        }
        return false
    }

    // ---------------------------------------------------------------------
    // The boundary: the nav link is UX, @RolesAllowed is what enforces it
    // ---------------------------------------------------------------------

    @Test
    @TestSecurity(user = "clerk@demo-cinema.example", roles = ["tenant_staff"])
    fun plainStaffAreRefusedTheStaffPage_notMerelyDeniedTheLink() {
        given().`when`().get("/admin/staff").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "clerk@demo-cinema.example", roles = ["tenant_staff"])
    fun plainStaffCannotAddAnyone_theWriteEndpointIsGatedToo() {
        // The GET being refused would mean little if the POST that creates staff were
        // reachable by anyone who knew the URL.
        given().formParam("email", "someone@example.test")
            .`when`().post("/admin/staff").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "clerk@demo-cinema.example", roles = ["tenant_staff"])
    fun plainStaffCannotPromoteAnyone() {
        given().formParam("canManage", "true")
            .`when`().post("/admin/staff/" + UUID.randomUUID() + "/manager")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"])
    fun customersAreRefusedTheStaffPage() {
        given().`when`().get("/admin/staff").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun aManagerCanOpenTheStaffPage() {
        given().`when`().get("/admin/staff").then().statusCode(200)
    }

    // ---------------------------------------------------------------------
    // canManageStaff -> the synthetic staff_manager role
    // ---------------------------------------------------------------------

    @Test
    fun theManagerCapabilityIsAColumn_notAFourthRole() {
        val manager = staffOf(fixture.tenant(), true)
        assertTrue(manager.canManageStaff)
        assertEquals(AppUser.ROLE_TENANT_STAFF, manager.role,
            "a manager is staff plus a capability — modelling it as a role would mean " +
                "revisiting every @RolesAllowed(TENANT_STAFF) in the tree")
    }

    @Test
    fun aCustomerCannotBeGivenTheManagerFlag() {
        val tenant = fixture.tenant()
        val customer = fixture.appUser(tenant,
            "cust-" + UUID.randomUUID() + "@example.test", AppUser.ROLE_CUSTOMER)
        // The repository filters on role, so this updates nothing rather than reaching the
        // CHECK constraint — zero rows is what the service turns into "not on your list".
        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.setManageStaffWithinTenant(c, customer.appUserId, tenant.tenantId, true) }
        assertEquals(0, updated)
    }

    // ---------------------------------------------------------------------
    // Tenant scoping, which app_user gets from these predicates and not from RLS
    // ---------------------------------------------------------------------

    @Test
    fun oneTenantsManagerCannotTouchAnothersStaff() {
        val mine = fixture.tenant()
        val theirs = fixture.tenant()
        val victim = staffOf(theirs, false)

        val promoted = db.inTenantTransaction(mine.tenantId) { c ->
            appUserRepo.setManageStaffWithinTenant(c, victim.appUserId, mine.tenantId, true) }
        val deactivated = db.inTenantTransaction(mine.tenantId) { c ->
            appUserRepo.setActiveWithinTenant(c, victim.appUserId, mine.tenantId, false) }

        assertEquals(0, promoted,
            "app_user carries no RLS policy — the tenant_id predicate IS the isolation")
        assertEquals(0, deactivated)
        val after = db.inTransaction { c -> appUserRepo.findById(c, victim.appUserId) }.orElseThrow()
        assertFalse(after.canManageStaff)
        assertTrue(after.active)
    }

    @Test
    fun theStaffListShowsOnlyThisTenantsStaff_andNotItsCustomers() {
        val mine = fixture.tenant()
        val theirs = fixture.tenant()
        val ours = staffOf(mine, false)
        val notOurs = staffOf(theirs, false)
        val ourCustomer = fixture.appUser(mine,
            "cust-" + UUID.randomUUID() + "@example.test", AppUser.ROLE_CUSTOMER)

        val ids = db.inTenantTransaction(mine.tenantId) { c -> appUserRepo.findStaffByTenant(c, mine.tenantId) }
            .stream().map(AppUser::appUserId).toList()

        assertTrue(ids.contains(ours.appUserId))
        assertFalse(ids.contains(notOurs.appUserId), "another tenant's staff must not appear")
        assertFalse(ids.contains(ourCustomer.appUserId), "customers are not staff")
    }

    @Test
    fun deactivatedStaffStayVisible_orNobodyCouldEverReactivateThem() {
        val tenant = fixture.tenant()
        val member = staffOf(tenant, false)
        db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.setActiveWithinTenant(c, member.appUserId, tenant.tenantId, false) }

        val ids = db.inTenantTransaction(tenant.tenantId) { c -> appUserRepo.findStaffByTenant(c, tenant.tenantId) }
            .stream().map(AppUser::appUserId).toList()
        assertTrue(ids.contains(member.appUserId))
    }

    @Test
    fun lockActiveManagersIgnoresDeactivatedAndNonManagers() {
        val tenant = fixture.tenant()
        val manager = staffOf(tenant, true)
        staffOf(tenant, false)
        val deactivatedManager = staffOf(tenant, true)
        db.inTenantTransaction(tenant.tenantId) { c -> appUserRepo.setActiveWithinTenant(
            c, deactivatedManager.appUserId, tenant.tenantId, false) }

        val active = db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.lockActiveManagers(c, tenant.tenantId) }
        assertEquals(listOf(manager.appUserId), active)
    }

    /** The case-insensitivity is the column's `case_insensitive` collation now, not a
     * `lower()` on both sides of the lookup. */
    @Test
    fun findByEmailIgnoresCase_byTheColumnsCollation() {
        val member = staffOf(fixture.tenant(), false)
        val found = db.inTransaction { c ->
            appUserRepo.findByEmail(c, member.email!!.uppercase(java.util.Locale.ROOT)) }
        assertEquals(member.appUserId, found.orElseThrow().appUserId)
    }

    // ---------------------------------------------------------------------
    // The lockout guard. These act on the configured tenant — see the class javadoc.
    // ---------------------------------------------------------------------

    /**
     * The configured tenant has exactly one manager: the demo seed makes its staff
     * identity one and nothing else creates one. Demoting that row must be refused, and because
     * it IS refused this test leaves no state behind to restore.
     */
    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun theLastActiveManagerCannotBeDemoted_orTheTenantLosesTheScreenEntirely() {
        val tenantId = currentTenant.id()
        val managers = db.inTenantTransaction(tenantId) { c -> appUserRepo.findStaffByTenant(c, tenantId) }
            .stream().filter(AppUser::canManageStaff).filter(AppUser::active).toList()
        assertEquals(1, managers.size,
            "precondition: the demo seed leaves the configured tenant exactly one manager")

        val refused = assertThrows(
            StaffAdminService.StaffAdminException::class.java,
            { staffAdmin.setManager(managers[0].appUserId, false) })
        assertTrue(refused.message!!.contains("only staff manager"), refused.message)

        assertTrue(db.inTransaction { c -> appUserRepo.findById(c, managers[0].appUserId) }
            .orElseThrow().canManageStaff, "the refusal must not have half-applied")
    }

    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun theLastActiveManagerCannotBeDeactivatedEither_theGuardIsNotOnlyAboutTheFlag() {
        val tenantId = currentTenant.id()
        val onlyManager = db.inTenantTransaction(tenantId) { c -> appUserRepo.findStaffByTenant(c, tenantId) }
            .stream().filter(AppUser::canManageStaff).filter(AppUser::active).findFirst().orElseThrow()

        assertThrows(StaffAdminService.StaffAdminException::class.java,
            { staffAdmin.setActive(onlyManager.appUserId, false) })
        assertTrue(db.inTransaction { c -> appUserRepo.findById(c, onlyManager.appUserId) }
            .orElseThrow().active)
    }

    /** The mirror of the two above: with a second manager present the guard steps aside. The
     * row it demotes is one this test created, so the tenant ends as it began. */
    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun aManagerCanBeDemotedOnceAnotherExists() {
        val tenantId = currentTenant.id()
        val second = staffOf(tenantId, true)

        staffAdmin.setManager(second.appUserId, false)

        assertFalse(db.inTransaction { c -> appUserRepo.findById(c, second.appUserId) }
            .orElseThrow().canManageStaff)
    }

    /**
     * Two managers demoting each other at once. The other demotion is held open on a second
     * thread, its UPDATE done and its commit not, until this thread's demotion is seen waiting
     * behind it. When the guard only counted, this thread counted two (an uncommitted UPDATE is
     * invisible under READ COMMITTED), went ahead, and the tenant was left with no manager.
     */
    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun twoManagersDemotingEachOtherAtOnce_theSecondIsRefusedRatherThanLeavingNone() {
        val tenantId = currentTenant.id()
        val first = db.inTenantTransaction(tenantId) { c -> appUserRepo.findStaffByTenant(c, tenantId) }
            .stream().filter(AppUser::canManageStaff).filter(AppUser::active).findFirst().orElseThrow()
        val second = staffOf(tenantId, true)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val updated = CountDownLatch(1)
            val overlapped = executor.submit(Callable {
                db.inTenantTransaction(tenantId) { c ->
                    appUserRepo.setManageStaffWithinTenant(c, second.appUserId, tenantId, false)
                    val pid = db.queryOne(c, "SELECT pg_backend_pid() AS pid",
                        { rs -> rs.getInt("pid") }).orElseThrow()
                    updated.countDown()
                    awaitBlockedBehind(pid)
                }
            })
            assertTrue(updated.await(10, TimeUnit.SECONDS), "the other demotion never got going")

            val refused = assertThrows(StaffAdminService.StaffAdminException::class.java) {
                staffAdmin.setManager(first.appUserId, false) }
            assertTrue(refused.message!!.contains("only staff manager"), refused.message)
            assertTrue(overlapped.get(30, TimeUnit.SECONDS),
                "this demotion never waited on the other, so the two did not overlap")
        } finally {
            executor.shutdownNow()
        }

        assertTrue(db.inTransaction { c -> appUserRepo.findById(c, first.appUserId) }
            .orElseThrow().canManageStaff, "the tenant must keep its one remaining manager")
    }

    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun addingAnAddressThatIsAlreadyStaffHereIsRefusedPlainly() {
        val tenantId = currentTenant.id()
        val existing = staffOf(tenantId, false)

        val refused = assertThrows(
            StaffAdminService.StaffAdminException::class.java,
            { staffAdmin.invite(existing.email) })
        assertTrue(refused.message!!.contains("already on your staff list"), refused.message)
    }

    /**
     * The cross-tenant refusal must not say *where* the address is used. Naming the
     * other cinema would turn this form into a lookup for which venues an address works at.
     */
    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun addingAnAddressBelongingToAnotherTenantDoesNotRevealWhichOne() {
        val otherTenant = fixture.tenant()
        val theirs = staffOf(otherTenant, false)

        val refused = assertThrows(
            StaffAdminService.StaffAdminException::class.java,
            { staffAdmin.invite(theirs.email) })
        assertTrue(refused.message!!.contains("already in use on this platform"),
            refused.message)
        assertFalse(refused.message!!.contains(otherTenant.displayName!!),
            "the message must not name the other tenant")
        assertFalse(refused.message!!.contains(otherTenant.slug!!),
            "nor its slug")
    }

    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun anInviteIsLowerCased_soTheStaffListShowsOneSpelling() {
        val mixed = "Mixed-" + UUID.randomUUID() + "@Example.Test"
        val created = staffAdmin.invite(mixed)
        assertEquals(mixed.lowercase(java.util.Locale.ROOT), created.email,
            "the collation already refuses a case-varying duplicate; lower-casing is what " +
                "keeps one spelling of an address on the staff list")
    }

    @Test
    @TestSecurity(user = "mgr@demo-cinema.example", roles = ["tenant_staff", "staff_manager"])
    fun anInvitedStaffMemberIsNotAManagerByDefault() {
        val created = staffAdmin.invite("plain-" + UUID.randomUUID() + "@example.test")
        assertFalse(created.canManageStaff,
            "the capability is granted deliberately afterwards, never by being added")
    }

    // ---------------------------------------------------------------------
    // The audit record — the reason this feature waited on a trigger
    // ---------------------------------------------------------------------

    @Test
    fun addingStaffWritesAnAuditRow_soAGrantIsNeverInvisible() {
        val tenant = fixture.tenant()
        val added = staffOf(tenant, false)
        assertEquals(1, auditRows(tenant.tenantId, added.appUserId, "INSERT"),
            "PlatformAdminBootstrap refuses silent promotion for exactly this reason — " +
                "a privilege grant that leaves no record")
    }

    @Test
    fun promotingWritesAnUpdateRow_recordingTheChangeAndNotOnlyTheCreation() {
        val tenant = fixture.tenant()
        val member = staffOf(tenant, false)

        db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.setManageStaffWithinTenant(c, member.appUserId, tenant.tenantId, true) }

        assertEquals(1, auditRows(tenant.tenantId, member.appUserId, "UPDATE"))
    }

    /**
     * The regression this would catch is a broken sign-in, not a missing audit row:
     * `touchLastLogin` runs with no tenant context on every single login, so a trigger
     * that fired on it would hit `audit_log`'s RLS policy and throw.
     */
    @Test
    fun stampingLastLoginIsNotAudited_becauseThatPathHasNoTenantContextToAuditWith() {
        val tenant = fixture.tenant()
        val member = staffOf(tenant, false)

        assertDoesNotThrow({ db.inTransaction { c -> appUserRepo.touchLastLogin(c, member.appUserId) } },
            "every login writes app_user outside tenant context; auditing that breaks sign-in")
        assertEquals(0, auditRows(tenant.tenantId, member.appUserId, "UPDATE"),
            "last_login_at is not a privilege change")
    }

    /** The same argument for the augmentor's auto-provisioning path, which inserts a customer
     * row on first sign-in — also outside tenant context. */
    @Test
    fun autoProvisioningACustomerIsNotAudited_forTheSameReason() {
        val tenant = fixture.tenant()
        val email = "autocust-" + UUID.randomUUID() + "@example.test"

        val created = assertDoesNotThrow<AppUser> { db.inTransaction { c ->
            appUserRepo.insert(c, email, AppUser.ROLE_CUSTOMER, tenant.tenantId, null) } }
        assertEquals(0, auditRows(tenant.tenantId, created.appUserId, "INSERT"))
    }
}
