package com.protek.booking.security

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.support.TestFixture
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Direct tests of the identity → role → tenant mapping, calling
 * [AppUserAugmentor.augment] rather than going over HTTP.
 *
 * **Why not over HTTP:** `@TestSecurity` installs a `SecurityIdentity`
 * directly and bypasses `SecurityIdentityAugmentor` altogether, so an HTTP test would
 * exercise the anonymous configured-tenant fallback while looking like it proved
 * identity-derived resolution. Calling the augmentor is what actually covers the branch that
 * decides a request's tenant — the single highest-consequence decision in authorization.
 */
@QuarkusTest
class AppUserAugmentorTest {

    companion object {
        /** Runs the supplied blocking work inline — the production context dispatches it to a
         * worker thread, which is irrelevant to what these assertions are about. */
        private val INLINE =
            AuthenticationRequestContext { supplier -> Uni.createFrom().item(supplier.get()) }
    }

    @Inject
    lateinit var augmentor: AppUserAugmentor
    @Inject
    lateinit var appUserRepository: AppUserRepository
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture

    private fun incoming(subject: String, email: String?, emailVerified: Boolean): SecurityIdentity {
        val builder = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal(subject))
        if (email != null) {
            builder.addAttribute("email", email)
            builder.addAttribute("email_verified", emailVerified)
        }
        return builder.build()
    }

    private fun augment(identity: SecurityIdentity): SecurityIdentity =
        augmentor.augment(identity, INLINE).await().indefinitely()

    @Test
    fun anonymousIdentity_isLeftAlone_soPublicBrowsingStaysPublic() {
        val anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build()
        val result = augment(anonymous)
        assertTrue(result.isAnonymous)
        assertTrue(result.roles.isEmpty(),
            "granting any role to an anonymous request would be the quiet start of an " +
                "authorization hole (browsing and the seat map are public)")
    }

    @Test
    fun preProvisionedStaff_getsTheirRoleAndTheirOwnTenant_notTheConfiguredDefault() {
        val other: Tenant = fixture.tenant()
        val email = "staff-" + UUID.randomUUID() + "@example.test"
        db.inTenantTransaction(other.tenantId) { c -> appUserRepository.insert(c, email,
            AppUser.ROLE_TENANT_STAFF, other.tenantId, null) }

        val result = augment(incoming("google-sub-" + UUID.randomUUID(), email, true))

        assertTrue(result.hasRole(Roles.TENANT_STAFF))
        assertEquals(other.tenantId, result.getAttribute<Any?>(AppUserAugmentor.TENANT_ID_ATTRIBUTE),
            "the tenant must come from this identity's app_user row, not from " +
                "booking.tenant.slug — that is the whole of the second rule")
        assertNotNull(result.getAttribute<Any?>(AppUserAugmentor.APP_USER_ID_ATTRIBUTE),
            "app_user_id is needed for audit_log.actor_id (attributability)")
    }

    @Test
    fun firstLogin_bindsTheOidcSubject_andRebindingIsRefusedAfterwards() {
        val tenant: Tenant = fixture.tenant()
        val email = "bind-" + UUID.randomUUID() + "@example.test"
        val provisioned = db.inTenantTransaction(tenant.tenantId) { c -> appUserRepository.insert(
            c, email, AppUser.ROLE_TENANT_STAFF, tenant.tenantId, null) }
        assertNull(provisioned.subject, "provisioned by email; no subject until first login")

        val firstSubject = "sub-first-" + UUID.randomUUID()
        augment(incoming(firstSubject, email, true))
        val afterFirst = db.inTransaction { c -> appUserRepository.findById(c, provisioned.appUserId) }
            .orElseThrow()
        assertEquals(firstSubject, afterFirst.subject)
        assertNotNull(afterFirst.lastLoginAt)

        // A second, different subject presenting the same email must NOT re-point the row.
        // Google's sub is stable while an email address is reassignable, so allowing a rebind
        // would let whoever next owns that address inherit this person's tenant and role.
        val attackerSubject = "sub-second-" + UUID.randomUUID()
        augment(incoming(attackerSubject, email, true))
        val afterSecond = db.inTransaction { c -> appUserRepository.findById(c, provisioned.appUserId) }
            .orElseThrow()
        assertEquals(firstSubject, afterSecond.subject,
            "rebinding an already-bound identity to a new OIDC subject must be refused")
    }

    @Test
    fun unknownEmail_isAutoProvisionedAsCustomerOfTheConfiguredTenant_neverAsStaff() {
        val email = "newcomer-" + UUID.randomUUID() + "@example.test"
        val result = augment(incoming("sub-" + UUID.randomUUID(), email, true))

        assertTrue(result.hasRole(Roles.CUSTOMER))
        assertFalse(result.hasRole(Roles.TENANT_STAFF),
            "auto-provisioning must never grant staff: the worst case for an unexpected " +
                "sign-in has to be an identity that can book a seat, not administer a hall")
        assertFalse(result.hasRole(Roles.PLATFORM_ADMIN))

        val created = db.inTransaction { c -> appUserRepository.findByEmail(c, email) }.orElseThrow()
        assertEquals(AppUser.ROLE_CUSTOMER, created.role)
        assertNotNull(created.tenantId, "a customer is tenant-scoped (Assumption 3)")
    }

    @Test
    fun unverifiedEmail_isNotTreatedAsIdentity() {
        val email = "unverified-" + UUID.randomUUID() + "@example.test"
        val result = augment(incoming("sub-" + UUID.randomUUID(), email, false))

        assertTrue(result.roles.isEmpty(),
            "an unverified address is not identity — anyone can type someone else's address " +
                "into an account they control, and the basis for treating email as " +
                "proportionate identity is that the provider verified it")
        assertTrue(db.inTransaction { c -> appUserRepository.findByEmail(c, email) }.isEmpty,
            "an unverified address must not be auto-provisioned into an app_user row")
    }

    @Test
    fun deactivatedUser_getsNoRoles_butTheRowSurvivesForTheAuditTrail() {
        val tenant: Tenant = fixture.tenant()
        val email = "deactivated-" + UUID.randomUUID() + "@example.test"
        // Both writes carry tenant context: the app_user privilege audit audits the insert of a staff row and any
        // change to its is_active, and the audit insert needs the GUC to satisfy RLS.
        val user = db.inTenantTransaction(tenant.tenantId) { c -> appUserRepository.insert(
            c, email, AppUser.ROLE_TENANT_STAFF, tenant.tenantId, null) }
        db.inTenantTransaction(tenant.tenantId) { c -> appUserRepository.setActive(c, user.appUserId, false) }

        val result = augment(incoming("sub-" + UUID.randomUUID(), email, true))
        assertTrue(result.roles.isEmpty(), "a deactivated identity must carry no roles")
        assertTrue(db.inTransaction { c -> appUserRepository.findById(c, user.appUserId) }.isPresent,
            "deactivated rather than deleted, so audit_log.actor_id keeps pointing at a row " +
                "that still exists")
    }

    @Test
    fun platformAdmin_getsNoTenantAttribute() {
        val email = "padmin-" + UUID.randomUUID() + "@example.test"
        db.inTransaction { c -> appUserRepository.insert(c, email, AppUser.ROLE_PLATFORM_ADMIN, null, null) }

        val result = augment(incoming("sub-" + UUID.randomUUID(), email, true))
        assertTrue(result.hasRole(Roles.PLATFORM_ADMIN))
        assertNull(result.getAttribute<Any?>(AppUserAugmentor.TENANT_ID_ATTRIBUTE),
            "a platform admin has no home tenant; defaulting one would make a cross-tenant " +
                "admin action silently operate on the wrong tenant")
    }
}
