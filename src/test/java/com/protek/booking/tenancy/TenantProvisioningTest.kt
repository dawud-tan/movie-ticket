package com.protek.booking.tenancy

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.TenantAgreementAcceptanceRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.service.BillingService
import com.protek.booking.service.TenantProvisioningService
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Scripted provisioning, and the tenant-count-independent parts built first.
 *
 * What these tests are really protecting is the half-provisioned tenant: one that exists
 * and takes online bookings but has no walk-in placeholder (so every counter sale fails), or
 * no cancellation policy (so bookings freeze a null `policy_document_id` and nobody can
 * later show what was agreed), or no accepted platform agreement (so the liability boundary
 * was never established). Each of those looks fine until the specific moment it
 * doesn't, which is why provisioning asserts all of them together rather than trusting a
 * runbook.
 */
@QuarkusTest
class TenantProvisioningTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var provisioningService: TenantProvisioningService
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var policyRepo: PolicyDocumentRepository
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var acceptanceRepo: TenantAgreementAcceptanceRepository
    @Inject
    lateinit var billingService: BillingService

    private fun sharedTierRequest(slug: String): TenantProvisioningService.ProvisionRequest =
        TenantProvisioningService.ProvisionRequest(
                slug, "Provisioned " + slug, "live_entertainment",
                Tenant.MODE_SHARED, Tenant.MODE_SHARED, "subdomain",
                null, null, null, "ID", "+62-21-5550199",
                slug + "-staff@example.test", "Owner Name", slug + "-owner@example.test",
                "203.0.113.7", TenantProvisioningService.PlanTier.STARTER)

    @Test
    fun provision_createsEverythingATenantNeedsToTransact() {
        val slug = "prov-" + UUID.randomUUID()
        val result = provisioningService.provision(sharedTierRequest(slug))

        val tenant = result.tenant
        assertEquals(slug, tenant.slug)
        assertEquals(Tenant.STATUS_ACTIVE, tenant.status)
        assertFalse(tenant.otherSalesChannelsDisclosed,
                "a new tenant has disclosed nothing until asked — false, not null")

        // The walk-in placeholder, without which every counter sale fails.
        val walkIn = db.inTenantTransaction(tenant.tenantId) { c ->
            customerRepo.findWalkInPlaceholder(c, tenant.tenantId) }.orElseThrow {
                AssertionError("provisioning must create the walk-in placeholder customer") }
        assertEquals(result.walkInCustomerId, walkIn.customerId)
        assertTrue(walkIn.walkInPlaceholder)

        // A cancellation policy, without which reservation.policy_document_id is null and the
        // booking-time policy snapshot does not exist.
        val policy = db.inTenantTransaction(tenant.tenantId) { c ->
            policyRepo.findLatest(c, tenant.tenantId, "cancellation_policy") }.orElseThrow {
                AssertionError("provisioning must create a default cancellation policy") }
        assertEquals(1, policy.version)

        // The platform agreement acceptance — an onboarding requirement.
        assertNotNull(result.agreementAcceptanceId)
        assertEquals(1, db.inTenantTransaction(tenant.tenantId) { c ->
            acceptanceRepo.findByTenant(c, tenant.tenantId) }.size)

        // A staff login, or the tenant cannot manage its own halls.
        val staff = db.inTransaction { c -> appUserRepo.findById(c, result.staffAppUserId) }.orElseThrow()
        assertEquals(AppUser.ROLE_TENANT_STAFF, staff.role)
        assertEquals(tenant.tenantId, staff.tenantId)
        assertNull(staff.subject, "the OIDC subject is bound on first login, not at provisioning")

        // The opening billing period, so the first invoice does not wait for the monthly job.
        val periods = billingService.historyFor(tenant.tenantId)
        assertEquals(1, periods.size)
        assertEquals("STARTER", periods[0].planTier)
        assertEquals("pending", periods[0].status)
    }

    @Test
    fun provision_reportsTheInfrastructureStepsItCannotTake() {
        val slug = "prov-ded-" + UUID.randomUUID()
        val result = provisioningService.provision(
                TenantProvisioningService.ProvisionRequest(
                        slug, "Dedicated " + slug, "live_entertainment",
                        Tenant.MODE_DEDICATED, Tenant.MODE_DEDICATED, "custom",
                        slug + ".example.test", "10.20.30.40", "booking_" + slug.replace('-', '_'),
                        "ID", "+62-21-5550200",
                        slug + "-staff@example.test", "Owner", slug + "-owner@example.test",
                        null, TenantProvisioningService.PlanTier.ENTERPRISE))

        val followUp = result.manualFollowUp
        // Returned rather than logged, because a log line during provisioning is exactly what
        // gets missed. Backup verification is the step most likely to be skipped
        // under time pressure and most likely to matter in an incident, so it appears for every
        // tenant regardless of tier.
        assertTrue(followUp.any { s -> s.contains("Cloud SQL") },
                "a dedicated-data tenant needs its own instance provisioned: " + followUp)
        assertTrue(followUp.any { s -> s.contains("Cloud Run") },
                "a dedicated-compute tenant needs its own service: " + followUp)
        assertTrue(followUp.any { s -> s.contains("Certificate Manager") },
                "a custom-domain tenant needs a certificate map entry: " + followUp)
        assertTrue(followUp.any { s -> s.contains("restore test") },
                "backup restore verification must be reported for every tenant: " + followUp)
        assertTrue(followUp.any { s -> s.contains("PSE") },
                "PSE registration is a go-live gate: " + followUp)
        // A step that names an obligation without saying what to do next is a step someone has
        // to go research before they can act on it, which is how this one sat unactioned. The
        // endpoint is asserted so a later edit cannot quietly drop it back to "confirm the number".
        assertTrue(followUp.any { s -> s.contains("PSE") && s.contains("/pse-registration") },
                "the PSE step must say where the number goes, not only that filing is required: " + followUp)
    }

    @Test
    fun provision_rejectsInconsistentDedicatedFields_withAUsableMessage() {
        val slug = "prov-bad-" + UUID.randomUUID()
        // The dedicated_fields_consistent CHECK would also catch this, but as a constraint-name
        // violation. Failing in validation names the missing field instead.
        val e = assertThrows(BadRequestException::class.java) {
            provisioningService.provision(TenantProvisioningService.ProvisionRequest(
                    slug, "Bad " + slug, "live_entertainment",
                    Tenant.MODE_SHARED, Tenant.MODE_DEDICATED, "subdomain",
                    null, null, null, "ID", "+62-21-5550201",
                    slug + "@example.test", "Owner", slug + "-owner@example.test",
                    null, TenantProvisioningService.PlanTier.BUSINESS)) }
        assertTrue(e.message!!.contains("dedicatedDbHost"), e.message)
    }

    @Test
    fun provision_requiresComplaintPhone_becausePP71MakesItMandatory() {
        val slug = "prov-nophone-" + UUID.randomUUID()
        val e = assertThrows(BadRequestException::class.java) {
            provisioningService.provision(TenantProvisioningService.ProvisionRequest(
                    slug, "No phone", "live_entertainment",
                    Tenant.MODE_SHARED, Tenant.MODE_SHARED, "subdomain",
                    null, null, null, "ID", "  ",
                    slug + "@example.test", "Owner", slug + "-owner@example.test",
                    null, TenantProvisioningService.PlanTier.STARTER)) }
        assertTrue(e.message!!.contains("complaintPhone"), e.message)
    }

    @Test
    fun provision_isNotIdempotent_soAReRunFailsLoudlyRatherThanHalfUpdating() {
        val slug = "prov-dupe-" + UUID.randomUUID()
        provisioningService.provision(sharedTierRequest(slug))
        // tenant.slug is UNIQUE. A silent upsert here would be worse than an error: it would
        // let a re-run partially overwrite a live tenant's configuration.
        assertThrows(RuntimeException::class.java) { provisioningService.provision(sharedTierRequest(slug)) }
    }

    @Test
    fun disclosure_requiresDetailWhenTrue_andClearsItWhenFalse() {
        val slug = "prov-disc-" + UUID.randomUUID()
        val tenant = provisioningService.provision(sharedTierRequest(slug)).tenant

        db.inTransaction { c -> tenantRepo.updateOtherSalesChannelDisclosure(
                c, tenant.tenantId, true, "Walk-in paper ledger at the second box office") }
        val disclosed = db.inTransaction { c -> tenantRepo.findById(c, tenant.tenantId) }.orElseThrow()
        assertTrue(disclosed.otherSalesChannelsDisclosed)
        assertEquals("Walk-in paper ledger at the second box office", disclosed.otherSalesChannelsDetail)

        // The other_sales_channels_consistent CHECK rejects a disclosure with no substance —
        // a flag set to true with an empty description discloses nothing.
        assertThrows(RuntimeException::class.java) { db.inTransaction { c ->
                tenantRepo.updateOtherSalesChannelDisclosure(c, tenant.tenantId, true, "   ") } }

        db.inTransaction { c -> tenantRepo.updateOtherSalesChannelDisclosure(
                c, tenant.tenantId, false, "ignored") }
        val cleared = db.inTransaction { c -> tenantRepo.findById(c, tenant.tenantId) }.orElseThrow()
        assertFalse(cleared.otherSalesChannelsDisclosed)
        assertNull(cleared.otherSalesChannelsDetail,
                "clearing the flag must clear the detail, or the row keeps a description of a "
                        + "channel it claims does not exist")
    }
}
